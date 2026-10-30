package br.com.coletoragnss.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import java.util.Locale;
import android.location.Location;
import android.location.LocationManager;
import java.net.SocketTimeoutException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.getcapacitor.PermissionState;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.lang.reflect.Method;
import java.util.UUID;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.LinkAddress;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Conexões de rede da coletora:
 *  - receptor GNSS por TCP (Wi-Fi do receptor ou IP qualquer), lendo NMEA;
 *  - cliente NTRIP (correção RTCM pela internet), repassada direto ao receptor;
 *  - servidor TCP que replica o NMEA do receptor para outros equipamentos (ecobatímetro, notebook).
 * Os dados binários (RTCM) nunca passam pelo JavaScript.
 */
@CapacitorPlugin(
        name = "GnssTcp",
        permissions = {
                @Permission(alias = "bluetooth", strings = {"android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN"}),
                @Permission(alias = "notif", strings = {"android.permission.POST_NOTIFICATIONS"})
        })
public class GnssTcpPlugin extends Plugin {

    private static volatile Socket rx;
    private static volatile OutputStream rxOut;
    private static volatile boolean rxRun = false;
    private static volatile BluetoothSocket bt;
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static volatile ServerSocket srv;
    private static final List<Socket> clientes = new CopyOnWriteArrayList<>();

    private static volatile Socket ntrip;
    private static volatile OutputStream ntripOut;
    private static volatile long rxEnviados = 0;
    private static volatile String rxErroEnvio = "";
    private static volatile boolean ntripRun = false;
    private static volatile long ntripBytes = 0;
    private static volatile int ntripGen = 0;
    private static volatile long rxDescartados = 0;
    private static PowerManager.WakeLock wl;

    // fila da correção para o receptor: a leitura do caster nunca espera o Bluetooth;
    // se acumular mais de 6 KB (~3 s), descarta o atraso, porque correção velha não serve.
    private static final LinkedBlockingQueue<byte[]> filaRtcm = new LinkedBlockingQueue<>();
    private static volatile int filaBytes = 0;
    // formato do envio da correção ao receptor: "raw" (RTCM puro) ou "vd" (envelope $$VD da ComNav, blocos de 55 bytes)
    private static volatile String envelope = "raw";

    // ---------- funcionamento em segundo plano ----------
    // Todo o estado das conexões é estático: se a tela for fechada (ou o app tirado dos recentes),
    // o serviço em primeiro plano mantém o processo vivo e, ao abrir de novo, a tela nova reencontra tudo ligado.
    private static volatile GnssTcpPlugin atual;
    private static volatile boolean uiViva = false;
    private static volatile boolean uiVisivel = false;
    private static Context app;
    private static Context ctx() { return app; }

    private static volatile int rxGen = 0;
    private static volatile boolean rxDesejado = false;
    private static volatile String rxTipo = "", rxAlvo = "";
    private static volatile int rxCanal = 0, rxPorta = 0, rxTentativas = 0, rxReconexoes = 0;
    private static volatile boolean rxWifi = true;
    private static volatile long rxDesde = 0;

    private static volatile boolean ntripDesejado = false, ntripGga = false;
    private static volatile String nHost = "", nMount = "", nUser = "", nPass = "";
    private static volatile int nPort = 2101, ntripRecons = 0;
    private static volatile String ntripSt = "desligado";

    private static final StringBuilder ggaBuf = new StringBuilder();
    private static volatile String ultGGA = "";
    private static volatile int qualidade = 0;
    private static volatile long ggaJs = 0, ggaEnv = 0, ultAviso = 0;
    private static volatile boolean mockLigado = false;

    private static volatile java.io.FileOutputStream grav;
    private static volatile String gravCaminho = "";
    private static volatile long gravBytes = 0, gravInicio = 0, gravFlush = 0;

    @Override
    public void load() {
        app = getContext().getApplicationContext();
        atual = this;
        uiViva = true;
        uiVisivel = true;
    }

    @Override
    protected void handleOnResume() {
        if (atual != this) return;
        uiVisivel = true;
        // se o serviço estava reconectando sozinho, devolve a reconexão para o app
        if (rxDesejado && !rxRun) status("desconectado", "Reconectando…");
        if (ntripDesejado && !ntripRun) ntripStatus("erro", "Reconectando…");
    }

    @Override
    protected void handleOnPause() {
        if (atual == this) uiVisivel = false;
    }

    private void emitir(String ev, JSObject o) {
        GnssTcpPlugin p = atual;
        if (p == null || !uiViva) return;
        try { p.notifyListeners(ev, o); } catch (Exception ignored) { }
    }

    /** Liga, atualiza ou desliga o serviço com o aviso fixo na barra de notificações. */
    private static void servico() {
        boolean ativo = rxDesejado || ntripDesejado || grav != null || srv != null;
        try {
            if (ativo) GnssService.ligar(app, textoAviso());
            else GnssService.desligar(app);
        } catch (Exception ignored) { }
    }

    private static void aviso(boolean ja) {
        long agora = System.currentTimeMillis();
        if (!ja && agora - ultAviso < 3000) return;
        ultAviso = agora;
        if (GnssService.rodando) { try { GnssService.atualizar(app, textoAviso()); } catch (Exception ignored) { } }
    }

    private static String textoAviso() {
        StringBuilder b = new StringBuilder();
        if (rxDesejado) {
            if (rxRun) {
                String q = qualidade == 4 ? "FIX" : qualidade == 5 ? "FLOAT" : qualidade == 2 ? "DGPS" : qualidade == 1 ? "Autônomo" : "sem posição";
                b.append("Receptor ").append(q);
            } else b.append("Reconectando receptor").append(rxTentativas > 0 ? " (" + rxTentativas + ")" : "…");
        }
        if (ntripDesejado) {
            if (b.length() > 0) b.append(" · ");
            b.append(ntripRun ? "NTRIP " + (ntripBytes / 1024) + " KB" : "NTRIP reconectando…");
        }
        if (grav != null) {
            if (b.length() > 0) b.append(" · ");
            long min = (System.currentTimeMillis() - gravInicio) / 60000;
            b.append(String.format(Locale.US, "Gravando %.1f MB (%d min)", gravBytes / 1048576.0, min));
        }
        if (srv != null) {
            if (b.length() > 0) b.append(" · ");
            b.append("Servidor NMEA (").append(clientes.size()).append(")");
        }
        return b.length() == 0 ? "WA Survey em espera" : b.toString();
    }

    /** Acha a última GGA nos dados do receptor: qualidade para o aviso, posição para o caster e o local fictício. */
    private void procurarGGA(String t) {
        String l = null;
        synchronized (ggaBuf) {
            ggaBuf.append(t);
            if (ggaBuf.length() > 4096) ggaBuf.delete(0, ggaBuf.length() - 1024);
            int i = ggaBuf.lastIndexOf("GGA,");
            if (i >= 3) {
                int ini = ggaBuf.lastIndexOf("$", i);
                int fim = ggaBuf.indexOf("*", i);
                if (ini >= 0 && i - ini <= 3 && fim > 0 && fim + 3 <= ggaBuf.length()) {
                    l = ggaBuf.substring(ini, fim + 3);
                    ggaBuf.delete(0, fim + 3);
                } else if (ini >= 0 && i - ini <= 3 && fim < 0) {
                    ggaBuf.delete(0, ini); // linha incompleta: guarda só ela
                }
            }
        }
        if (l == null) return;
        ultGGA = l;
        String[] f = l.split(",", -1);
        if (f.length < 10) return;
        try { qualidade = f[6].length() > 0 ? Integer.parseInt(f[6]) : 0; } catch (Exception e) { qualidade = 0; }
        long agora = System.currentTimeMillis();
        // posição para o caster (VRS): o app manda enquanto está aberto; sem ele, o serviço manda
        OutputStream o = ntripOut;
        if (ntripGga && o != null && agora - ggaJs > 15000 && agora - ggaEnv > 10000 && qualidade > 0) {
            ggaEnv = agora;
            try { synchronized (o) { o.write((l + "\r\n").getBytes(StandardCharsets.ISO_8859_1)); o.flush(); } } catch (Exception ignored) { }
        }
        if (mockLigado && !uiVisivel && qualidade > 0) mockDeGGA(f);
    }

    private static double grausNmea(String v, String h) {
        if (v == null || v.length() < 4) return Double.NaN;
        int p = v.indexOf('.');
        int gl = (p < 0 ? v.length() : p) - 2;
        double g = Double.parseDouble(v.substring(0, gl)) + Double.parseDouble(v.substring(gl)) / 60.0;
        return ("S".equals(h) || "W".equals(h)) ? -g : g;
    }

    private void mockDeGGA(String[] f) {
        try {
            double lat = grausNmea(f[2], f[3]), lon = grausNmea(f[4], f[5]);
            if (Double.isNaN(lat) || Double.isNaN(lon)) return;
            double alt = f[9].length() > 0 ? Double.parseDouble(f[9]) : 0;
            double hdop = f[8].length() > 0 ? Double.parseDouble(f[8]) : 1;
            float acc = qualidade == 4 ? 0.02f : qualidade == 5 ? 0.3f : (float) Math.max(1.0, hdop * 2.5);
            Location loc = new Location(LocationManager.GPS_PROVIDER);
            loc.setLatitude(lat); loc.setLongitude(lon); loc.setAltitude(alt); loc.setAccuracy(acc);
            loc.setTime(System.currentTimeMillis());
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            if (Build.VERSION.SDK_INT >= 26) loc.setVerticalAccuracyMeters(acc * 1.7f);
            lm().setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
        } catch (Exception ignored) { }
    }

    // ---------- gravação dos dados brutos do receptor direto em arquivo (continua com a tela fechada) ----------
    private static void gravarBrutos(byte[] buf, int n) {
        java.io.FileOutputStream g = grav;
        if (g == null) return;
        try {
            g.write(buf, 0, n);
            gravBytes += n;
            long agora = System.currentTimeMillis();
            if (agora - gravFlush > 5000) { gravFlush = agora; g.getFD().sync(); }
        } catch (Exception ignored) { }
    }

    @PluginMethod
    public void recordStart(PluginCall call) {
        String path = call.getString("path", "");
        try {
            fecharGravacao();
            java.io.File f = new java.io.File(ctx().getFilesDir(), path);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            gravBytes = 0;
            gravInicio = System.currentTimeMillis();
            gravCaminho = path;
            grav = new java.io.FileOutputStream(f, false);
            // cabeçalho igual ao CNB gravado pelo próprio receptor (os programas da ComNav reconhecem)
            String cab = call.getString("header", "");
            if (cab.length() > 0) {
                byte[] hb = cab.getBytes(StandardCharsets.ISO_8859_1);
                grav.write(hb);
                int pad = Math.max(0, 135 - hb.length);
                grav.write(new byte[pad]);
                gravBytes = hb.length + pad;
            }
            servico();
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    @PluginMethod
    public void recordStop(PluginCall call) {
        JSObject r = new JSObject();
        r.put("bytes", gravBytes);
        r.put("path", gravCaminho);
        fecharGravacao();
        servico();
        call.resolve(r);
    }

    private static void fecharGravacao() {
        java.io.FileOutputStream g = grav;
        grav = null;
        if (g != null) { try { g.getFD().sync(); } catch (Exception ignored) { } try { g.close(); } catch (Exception ignored) { } }
    }

    /** Situação das conexões, para a tela se reencontrar ao abrir de novo. */
    @PluginMethod
    public void state(PluginCall call) {
        JSObject r = new JSObject();
        r.put("receptor", rxRun);
        r.put("receptorDesejado", rxDesejado);
        r.put("via", rxTipo);
        r.put("alvo", rxAlvo);
        r.put("desde", rxDesde);
        r.put("reconexoes", rxReconexoes);
        r.put("ntrip", ntripRun);
        r.put("ntripDesejado", ntripDesejado);
        r.put("ntripStatus", ntripSt);
        r.put("ntripBytes", ntripBytes);
        r.put("ntripRecons", ntripRecons);
        r.put("mount", nMount);
        r.put("envelope", envelope);
        r.put("gravando", grav != null);
        r.put("gravCaminho", gravCaminho);
        r.put("gravBytes", gravBytes);
        r.put("gravInicio", gravInicio);
        r.put("servidor", srv != null);
        r.put("servico", GnssService.rodando);
        r.put("gga", ultGGA);
        call.resolve(r);
    }

    @PluginMethod
    public void ntripOptions(PluginCall call) {
        ntripGga = call.getBoolean("gga", ntripGga);
        call.resolve();
    }

    // ---------- RINEX ----------
    /** Converte um CNB (dentro da pasta do app) em RINEX 3.04 (.obs + .nav), opcionalmente num .zip. */
    @PluginMethod
    public void cnbToRinex(final PluginCall call) {
        final String path = call.getString("path", "");
        final String nome = call.getString("name", "RINEX");
        final String marcador = call.getString("marker", "");
        final double altura = call.getDouble("antHeight", Double.NaN);
        final double intervalo = call.getDouble("interval", 1.0);
        final double lat = call.getDouble("lat", 0.0), lon = call.getDouble("lon", 0.0), h = call.getDouble("h", 0.0);
        final String receptor = call.getString("receiver", ""), antena = call.getString("antenna", "");
        final boolean zip = call.getBoolean("zip", true);
        final String sistemas = call.getString("systems", "");
        new Thread(() -> {
            try {
                java.io.File src = path.startsWith("/") ? new java.io.File(path) : new java.io.File(ctx().getFilesDir(), path);
                if (!src.exists()) throw new Exception("Arquivo não encontrado: " + path);
                java.io.File dir = new java.io.File(ctx().getFilesDir(), "WASurvey/rinex");
                dir.mkdirs();
                CnbRinex.Resultado r = CnbRinex.converter(src, dir, nome, marcador, altura, intervalo,
                        (lat == 0 && lon == 0) ? null : new double[]{lat, lon, h}, receptor, antena, sistemas.isEmpty() ? null : sistemas);
                JSObject o = new JSObject();
                String base = ctx().getFilesDir().getAbsolutePath() + "/";
                o.put("obs", r.obs.replace(base, ""));
                if (r.nav != null) o.put("nav", r.nav.replace(base, ""));
                o.put("epochs", r.epocas);
                o.put("sats", r.sats);
                o.put("start", r.inicio);
                o.put("end", r.fim);
                o.put("systems", r.sistemas);
                o.put("lat", r.lat); o.put("lon", r.lon); o.put("h", r.h);
                if (zip) {
                    java.io.File z = new java.io.File(dir, nome + "_RINEX.zip");
                    try (java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(z))) {
                        for (String fp : new String[]{r.obs, r.nav}) {
                            if (fp == null) continue;
                            java.io.File f = new java.io.File(fp);
                            zo.putNextEntry(new java.util.zip.ZipEntry(f.getName()));
                            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                                byte[] buf = new byte[65536]; int n;
                                while ((n = in.read(buf)) > 0) zo.write(buf, 0, n);
                            }
                            zo.closeEntry();
                        }
                    }
                    o.put("zip", z.getAbsolutePath().replace(base, ""));
                    o.put("zipBytes", z.length());
                }
                call.resolve(o);
            } catch (Throwable e) {
                call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }).start();
    }

    /** Abre o seletor de arquivos do Android e copia o arquivo escolhido para a pasta do app. */
    @PluginMethod
    public void pickFile(PluginCall call) {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(call, i, "arquivoEscolhido");
    }

    @com.getcapacitor.annotation.ActivityCallback
    private void arquivoEscolhido(final PluginCall call, androidx.activity.result.ActivityResult res) {
        if (call == null) return;
        if (res.getResultCode() != android.app.Activity.RESULT_OK || res.getData() == null || res.getData().getData() == null) { call.reject("cancelado"); return; }
        final android.net.Uri uri = res.getData().getData();
        new Thread(() -> {
            try {
                String nome = "arquivo.cnb";
                try (android.database.Cursor c = ctx().getContentResolver().query(uri, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) { int k = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (k >= 0) nome = c.getString(k); }
                }
                nome = nome.replaceAll("[^A-Za-z0-9._-]", "_");
                java.io.File dir = new java.io.File(ctx().getFilesDir(), "WASurvey/importados");
                dir.mkdirs();
                java.io.File dst = new java.io.File(dir, nome);
                long tot = 0;
                try (InputStream in = ctx().getContentResolver().openInputStream(uri); java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); tot += n; }
                }
                JSObject o = new JSObject();
                o.put("path", "WASurvey/importados/" + nome);
                o.put("name", nome);
                o.put("bytes", tot);
                call.resolve(o);
            } catch (Exception e) {
                call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }).start();
    }

    /** Copia um arquivo da pasta do app para Downloads/WASurvey (fica visível no gerenciador de arquivos). */
    @PluginMethod
    public void saveToDownloads(final PluginCall call) {
        final String path = call.getString("path", "");
        final String mime = call.getString("mime", "application/octet-stream");
        new Thread(() -> {
            try {
                java.io.File src = new java.io.File(ctx().getFilesDir(), path);
                if (!src.exists()) throw new Exception("Arquivo não encontrado");
                String nome = call.getString("name", src.getName());
                java.io.OutputStream out;
                String onde;
                if (Build.VERSION.SDK_INT >= 29) {
                    android.content.ContentValues v = new android.content.ContentValues();
                    v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, nome);
                    v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/WASurvey");
                    android.net.Uri u = ctx().getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (u == null) throw new Exception("Não consegui criar o arquivo em Downloads");
                    out = ctx().getContentResolver().openOutputStream(u);
                    onde = "Download/WASurvey/" + nome;
                } else {
                    java.io.File d = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "WASurvey");
                    d.mkdirs();
                    java.io.File f = new java.io.File(d, nome);
                    out = new java.io.FileOutputStream(f);
                    onde = f.getAbsolutePath();
                }
                try (InputStream in = new java.io.FileInputStream(src); java.io.OutputStream o2 = out) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) o2.write(buf, 0, n);
                }
                JSObject o = new JSObject();
                o.put("where", onde);
                call.resolve(o);
            } catch (Exception e) {
                call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }).start();
    }

    /** Compartilha arquivo(s) com o tipo certo e sem texto junto (o WhatsApp mandava só o nome do .dxf). */
    @PluginMethod
    public void shareFiles(final PluginCall call) {
        try {
            JSArray lista = call.getArray("files", new JSArray());
            String mime = call.getString("mime", "application/octet-stream");
            java.util.ArrayList<android.net.Uri> uris = new java.util.ArrayList<>();
            java.io.File pasta = new java.io.File(ctx().getCacheDir(), "compartilhar");
            pasta.mkdirs();
            for (int i = 0; i < lista.length(); i++) {
                org.json.JSONObject o = lista.getJSONObject(i);
                String path = o.getString("path");
                String dir = o.optString("dir", "data");
                java.io.File src = path.startsWith("/") ? new java.io.File(path) : new java.io.File("cache".equals(dir) ? ctx().getCacheDir() : ctx().getFilesDir(), path);
                if (!src.exists()) { call.reject("Arquivo não encontrado: " + src.getName()); return; }
                java.io.File dst = new java.io.File(pasta, src.getName());
                if (!src.getCanonicalPath().equals(dst.getCanonicalPath())) {
                    try (InputStream in = new java.io.FileInputStream(src); java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                        byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                }
                uris.add(androidx.core.content.FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", dst));
            }
            if (uris.isEmpty()) { call.reject("Nada para enviar"); return; }
            android.content.Intent i = new android.content.Intent(uris.size() > 1 ? android.content.Intent.ACTION_SEND_MULTIPLE : android.content.Intent.ACTION_SEND);
            i.setType(mime);
            if (uris.size() > 1) i.putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris);
            else i.putExtra(android.content.Intent.EXTRA_STREAM, uris.get(0));
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setClipData(android.content.ClipData.newRawUri("", uris.get(0)));
            android.content.Intent ch = android.content.Intent.createChooser(i, call.getString("title", "Enviar arquivo"));
            ch.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getActivity().startActivity(ch);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    // ---------- atualização do app ----------
    /** Baixa o APK novo e abre o instalador do Android (instala por cima, mantendo os dados). */
    @PluginMethod
    public void installUpdate(final PluginCall call) {
        final String url = call.getString("url", "");
        if (Build.VERSION.SDK_INT >= 26 && !ctx().getPackageManager().canRequestPackageInstalls()) {
            try {
                android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + ctx().getPackageName()));
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx().startActivity(i);
            } catch (Exception ignored) { }
            call.reject("PERMISSAO");
            return;
        }
        new Thread(() -> {
            try {
                java.io.File dir = new java.io.File(ctx().getCacheDir(), "compartilhar");
                dir.mkdirs();
                java.io.File apk = new java.io.File(dir, "WASurvey-atualizacao.apk");
                String atual = url;
                java.net.HttpURLConnection c = null;
                for (int r = 0; r < 6; r++) {
                    c = (java.net.HttpURLConnection) new java.net.URL(atual).openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.setRequestProperty("User-Agent", "WASurvey");
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400) { atual = c.getHeaderField("Location"); c.disconnect(); continue; }
                    if (code != 200) throw new Exception("HTTP " + code);
                    break;
                }
                long total = c.getContentLengthLong(), lido = 0, ultimo = 0;
                try (InputStream in = c.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(apk)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n); lido += n;
                        long agora = System.currentTimeMillis();
                        if (agora - ultimo > 400) { ultimo = agora; JSObject o = new JSObject(); o.put("bytes", lido); o.put("total", total); emitir("update", o); }
                    }
                }
                android.net.Uri u = androidx.core.content.FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", apk);
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                i.setDataAndType(u, "application/vnd.android.package-archive");
                i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx().startActivity(i);
                call.resolve();
            } catch (Exception e) {
                call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }).start();
    }

    // ---------- permissões para o segundo plano ----------
    @PluginMethod
    public void backgroundInfo(PluginCall call) {
        JSObject r = new JSObject();
        try {
            PowerManager pm = (PowerManager) ctx().getSystemService(Context.POWER_SERVICE);
            r.put("semEconomia", Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(ctx().getPackageName()));
            r.put("notificacao", Build.VERSION.SDK_INT < 33 || getPermissionState("notif") == PermissionState.GRANTED);
            r.put("fabricante", Build.MANUFACTURER);
        } catch (Exception e) { r.put("erro", e.toString()); }
        call.resolve(r);
    }

    @PluginMethod
    public void askNotifications(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notif") != PermissionState.GRANTED) {
            requestPermissionForAlias("notif", call, "notifPerm");
            return;
        }
        call.resolve();
    }

    @PermissionCallback
    private void notifPerm(PluginCall call) {
        if (GnssService.rodando) aviso(true);
        call.resolve();
    }

    @SuppressLint("BatteryLife")
    @PluginMethod
    public void askBattery(PluginCall call) {
        try {
            android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + ctx().getPackageName()));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            try {
                android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(android.net.Uri.parse("package:" + ctx().getPackageName()));
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx().startActivity(i);
                call.resolve();
            } catch (Exception e2) { call.reject(e2.toString()); }
        }
    }

    /** Chamado pelo botão "Desligar tudo" da notificação. */
    static void desligarTudo() {
        rxDesejado = false; rxGen++;
        rxRun = false;
        try { if (rx != null) rx.close(); } catch (Exception ignored) { }
        try { if (bt != null) bt.close(); } catch (Exception ignored) { }
        rx = null; bt = null; rxOut = null;
        ntripDesejado = false; ntripGen++; ntripRun = false;
        try { if (ntrip != null) ntrip.close(); } catch (Exception ignored) { }
        ntrip = null; ntripOut = null;
        fecharGravacao();
        try { if (srv != null) srv.close(); } catch (Exception ignored) { }
        srv = null;
        for (Socket c : clientes) { try { c.close(); } catch (Exception ignored) { } }
        clientes.clear();
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) { }
        GnssTcpPlugin p = atual;
        if (p != null) { p.status("desconectado", "Desligado pela notificação"); p.ntripStatus("desligado", ""); }
    }

    private static byte[] envelopeVD(byte[] b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(b.length + b.length / 55 * 9 + 16);
        for (int i = 0; i < b.length; i += 55) {
            int n = Math.min(55, b.length - i);
            int x = 'V' ^ 'D' ^ 0x18 ^ n;
            o.write('$'); o.write('$'); o.write('V'); o.write('D'); o.write(0x18); o.write(n);
            for (int k = 0; k < n; k++) { o.write(b[i + k]); x ^= (b[i + k] & 0xff); }
            o.write(x & 0xff); o.write('\r'); o.write('\n');
        }
        return o.toByteArray();
    }

    /** Trimble: a correção vai em pacotes DCOL 0x99 = [0x0b + RTCM com os bits invertidos], até 253 bytes cada (visto no app da Trimble). */
    private static byte[] envelopeTrimble(byte[] b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(b.length + b.length / 253 * 7 + 16);
        for (int i = 0; i < b.length; i += 253) {
            int n = Math.min(253, b.length - i), len = n + 1;
            int cs = 0x00 + 0x99 + len + 0x0b;
            o.write(0x02); o.write(0x00); o.write(0x99); o.write(len); o.write(0x0b);
            for (int k = 0; k < n; k++) { int v = (~b[i + k]) & 0xff; o.write(v); cs += v; }
            o.write(cs & 0xff); o.write(0x03);
        }
        return o.toByteArray();
    }

    @PluginMethod
    public void setEnvelope(PluginCall call) {
        envelope = call.getString("mode", "raw");
        call.resolve();
    }
    private static Thread escritor;

    private static synchronized void iniciarEscritor() {
        if (escritor != null && escritor.isAlive()) return;
        escritor = new Thread(() -> {
            while (true) {
                try {
                    byte[] b = filaRtcm.poll(1, TimeUnit.SECONDS);
                    if (b == null) continue;
                    filaBytes -= b.length;
                    OutputStream o = rxOut;
                    if (o == null) { rxErroEnvio = "receptor não conectado"; continue; }
                    byte[] w = "vd".equals(envelope) ? envelopeVD(b) : "tr".equals(envelope) ? envelopeTrimble(b) : b;
                    synchronized (o) { o.write(w); o.flush(); }
                    rxEnviados += b.length;
                    rxErroEnvio = "";
                } catch (InterruptedException ie) { return; }
                catch (Exception ex) { rxErroEnvio = ex.getMessage() == null ? ex.toString() : ex.getMessage(); }
            }
        });
        escritor.setDaemon(true);
        escritor.start();
    }

    private void enfileirar(byte[] buf, int n) {
        if (filaBytes > 6144) { filaRtcm.clear(); rxDescartados += filaBytes; filaBytes = 0; }
        byte[] c = new byte[n];
        System.arraycopy(buf, 0, c, 0, n);
        filaBytes += n;
        filaRtcm.offer(c);
    }

    private void segurarCpu(boolean on) {
        try {
            if (on) {
                if (wl == null) {
                    PowerManager pm = (PowerManager) ctx().getSystemService(Context.POWER_SERVICE);
                    wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ColetoraGNSS:ntrip");
                    wl.setReferenceCounted(false);
                }
                if (!wl.isHeld()) wl.acquire(12 * 60 * 60 * 1000L);
            } else if (wl != null && wl.isHeld()) wl.release();
        } catch (Exception ignored) { }
    }

    // ---------- redes ----------
    private ConnectivityManager cm() {
        return (ConnectivityManager) ctx().getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /** Rede Wi-Fi atual (o Wi-Fi do receptor normalmente não tem internet). */
    private Network redeWifi() {
        try {
            for (Network n : cm().getAllNetworks()) {
                NetworkCapabilities c = cm().getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Rede com internet de verdade (dados móveis, quando o Wi-Fi é do receptor). */
    private Network redeInternet() {
        try {
            for (Network n : cm().getAllNetworks()) {
                NetworkCapabilities c = cm().getNetworkCapabilities(n);
                if (c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return n;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private Socket novoSocket(Network n) throws Exception {
        return n != null ? n.getSocketFactory().createSocket() : new Socket();
    }

    private void status(String tipo, String msg) {
        JSObject o = new JSObject();
        o.put("kind", tipo);
        o.put("msg", msg == null ? "" : msg);
        emitir("status", o);
    }

    // ---------- receptor ----------
    @PluginMethod
    public void connect(final PluginCall call) {
        final String host = call.getString("host", "192.168.1.1");
        final int port = call.getInt("port", 9902);
        final boolean usarWifi = call.getBoolean("wifi", true);
        fecharReceptor();
        final int gen = ++rxGen;
        new Thread(() -> {
            boolean respondeu = false;
            try {
                Socket s = abrirTcp(host, port, usarWifi);
                if (gen != rxGen) { s.close(); return; }
                rx = s;
                rxOut = s.getOutputStream();
                rxRun = true;
                rxTipo = "tcp"; rxAlvo = host; rxPorta = port; rxWifi = usarWifi; rxDesejado = true; rxDesde = System.currentTimeMillis();
                JSObject r = new JSObject();
                r.put("connected", true);
                call.resolve(r);
                respondeu = true;
                status("conectado", host + ":" + port);
                servico();
                lerReceptor(s.getInputStream());
                caiu(gen, "O receptor encerrou a conexão");
            } catch (Exception e) {
                if (!respondeu) call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
                else caiu(gen, e.getMessage());
            } finally {
                if (gen == rxGen) rxRun = false;
            }
        }).start();
    }

    private Socket abrirTcp(String host, int port, boolean usarWifi) throws Exception {
        Socket s = novoSocket(usarWifi ? redeWifi() : null);
        s.connect(new InetSocketAddress(host, port), 8000);
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        return s;
    }

    private void lerReceptor(InputStream in) throws Exception {
        byte[] buf = new byte[4096];
        int n;
        while (rxRun && (n = in.read(buf)) > 0) {
            if (repasseBruto) repassarClientes(buf, n);
            gravarBrutos(buf, n);
            String t = new String(buf, 0, n, StandardCharsets.ISO_8859_1);
            procurarGGA(t);
            JSObject d = new JSObject();
            d.put("text", t);
            emitir("data", d);
            aviso(false);
        }
    }

    /** A conexão com o receptor caiu: com a tela aberta, o app reconecta; com ela fechada, o serviço reconecta sozinho. */
    private void caiu(int gen, String msg) {
        if (gen != rxGen || !rxRun) return;
        rxRun = false;
        if (uiVisivel || !rxDesejado) { status("desconectado", msg); aviso(true); return; }
        aviso(true);
        reconectarSozinho(gen);
    }

    @SuppressLint("MissingPermission")
    private void reconectarSozinho(final int gen) {
        new Thread(() -> {
            rxTentativas = 0;
            while (rxDesejado && gen == rxGen && !uiVisivel) {
                try { Thread.sleep(4000); } catch (Exception e) { return; }
                if (!rxDesejado || gen != rxGen || uiVisivel) break;
                rxTentativas++;
                aviso(true);
                try {
                    InputStream in;
                    if ("bt".equals(rxTipo)) {
                        BluetoothSocket s = abrirBt(rxAlvo, rxCanal);
                        if (gen != rxGen) { s.close(); return; }
                        bt = s; rxOut = s.getOutputStream(); in = s.getInputStream();
                    } else {
                        Socket s = abrirTcp(rxAlvo, rxPorta, rxWifi);
                        if (gen != rxGen) { s.close(); return; }
                        rx = s; rxOut = s.getOutputStream(); in = s.getInputStream();
                    }
                    rxRun = true; rxTentativas = 0; rxReconexoes++;
                    status("conectado", rxAlvo);
                    aviso(true);
                    lerReceptor(in);
                } catch (Exception ignored) {
                } finally {
                    if (gen == rxGen) {
                        rxRun = false;
                        try { if (bt != null) bt.close(); } catch (Exception ignored) { }
                        try { if (rx != null) rx.close(); } catch (Exception ignored) { }
                    }
                }
            }
            // a tela voltou e o receptor ainda não voltou: o app assume a reconexão
            if (gen == rxGen && rxDesejado && !rxRun) status("desconectado", "Reconectando…");
        }).start();
    }

    // ---------- Bluetooth clássico (SPP) ----------
    private boolean precisaPermissaoBt() {
        return Build.VERSION.SDK_INT >= 31 && getPermissionState("bluetooth") != PermissionState.GRANTED;
    }

    @PluginMethod
    public void btPaired(PluginCall call) {
        if (precisaPermissaoBt()) { requestPermissionForAlias("bluetooth", call, "btPermPareados"); return; }
        listarPareados(call);
    }

    @PermissionCallback
    private void btPermPareados(PluginCall call) {
        if (precisaPermissaoBt()) { call.reject("Permissão de Bluetooth negada. Libere em Configurações › Apps › Coletora GNSS › Permissões."); return; }
        listarPareados(call);
    }

    @SuppressLint("MissingPermission")
    private void listarPareados(PluginCall call) {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) { call.reject("Este celular não tem Bluetooth"); return; }
        if (!ad.isEnabled()) { call.reject("Ligue o Bluetooth do celular"); return; }
        JSArray lista = new JSArray();
        for (BluetoothDevice d : ad.getBondedDevices()) {
            JSObject o = new JSObject();
            o.put("name", d.getName() == null ? d.getAddress() : d.getName());
            o.put("address", d.getAddress());
            lista.put(o);
        }
        JSObject r = new JSObject();
        r.put("devices", lista);
        call.resolve(r);
    }

    @PluginMethod
    public void btConnect(PluginCall call) {
        if (precisaPermissaoBt()) { requestPermissionForAlias("bluetooth", call, "btPermConectar"); return; }
        conectarBt(call);
    }

    @PermissionCallback
    private void btPermConectar(PluginCall call) {
        if (precisaPermissaoBt()) { call.reject("Permissão de Bluetooth negada"); return; }
        conectarBt(call);
    }

    @SuppressLint("MissingPermission")
    private BluetoothSocket abrirBt(String addr, int canal) throws Exception {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) throw new Exception("Este celular não tem Bluetooth");
        if (!ad.isEnabled()) throw new Exception("Ligue o Bluetooth do celular");
        ad.cancelDiscovery();
        BluetoothDevice dev = ad.getRemoteDevice(addr);
        BluetoothSocket s = null;
        Exception ultimo = null;
        if (canal > 0) {
            try {
                Method m = dev.getClass().getMethod("createRfcommSocket", int.class);
                s = (BluetoothSocket) m.invoke(dev, canal);
                s.connect();
                return s;
            } catch (Exception e0) {
                try { if (s != null) s.close(); } catch (Exception ignored) { }
                throw new Exception("Canal " + canal + " não abriu: " + e0.getMessage());
            }
        }
        try { s = dev.createRfcommSocketToServiceRecord(SPP); s.connect(); return s; }
        catch (Exception e1) {
            ultimo = e1;
            try { if (s != null) s.close(); } catch (Exception ignored) { }
            s = null;
        }
        try { s = dev.createInsecureRfcommSocketToServiceRecord(SPP); s.connect(); return s; }
        catch (Exception e2) {
            ultimo = e2;
            try { if (s != null) s.close(); } catch (Exception ignored) { }
            s = null;
        }
        try {
            Method m = dev.getClass().getMethod("createRfcommSocket", int.class);
            s = (BluetoothSocket) m.invoke(dev, 1);
            s.connect();
            return s;
        } catch (Exception e3) { ultimo = e3; try { if (s != null) s.close(); } catch (Exception ignored) { } }
        throw ultimo != null ? ultimo : new Exception("Falha ao conectar");
    }

    private void conectarBt(final PluginCall call) {
        final String addr = call.getString("address", "");
        final int canal = call.getInt("channel", 0);
        fecharReceptor();
        final int gen = ++rxGen;
        new Thread(() -> {
            boolean respondeu = false;
            try {
                BluetoothSocket s = abrirBt(addr, canal);
                if (gen != rxGen) { s.close(); return; }
                bt = s;
                rxOut = s.getOutputStream();
                rxRun = true;
                rxTipo = "bt"; rxAlvo = addr; rxCanal = canal; rxDesejado = true; rxDesde = System.currentTimeMillis();
                JSObject r = new JSObject();
                r.put("connected", true);
                call.resolve(r);
                respondeu = true;
                status("conectado", addr);
                servico();
                lerReceptor(s.getInputStream());
                caiu(gen, "O receptor encerrou a conexão");
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                if (!respondeu) call.reject(msg);
                else caiu(gen, msg);
            } finally {
                if (gen == rxGen) rxRun = false;
            }
        }).start();
    }

    @PluginMethod
    public void write(PluginCall call) {
        try {
            String data = call.getString("data", "");
            if (rxOut == null) { call.reject("Receptor não conectado"); return; }
            OutputStream o = rxOut;
            synchronized (o) { o.write(data.getBytes(StandardCharsets.ISO_8859_1)); o.flush(); }
            call.resolve();
        } catch (Exception e) {
            call.reject(e.toString());
        }
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        rxDesejado = false;
        rxGen++;
        fecharReceptor();
        servico();
        call.resolve();
    }

    private void fecharReceptor() {
        repasseBruto = true;
        rxRun = false;
        try { if (rx != null) rx.close(); } catch (Exception ignored) { }
        try { if (bt != null) bt.close(); } catch (Exception ignored) { }
        rx = null;
        bt = null;
        rxOut = null;
    }

    // ---------- servidor NMEA ----------
    private static volatile boolean repasseBruto = true;

    /** Trimble manda binário (GSOF): o app converte em NMEA e envia pelo serverSend; então desliga o repasse bruto. */
    @PluginMethod
    public void setRawRelay(PluginCall call) {
        repasseBruto = call.getBoolean("on", true);
        call.resolve();
    }

    private void repassarClientes(byte[] buf, int n) {
        for (Socket c : clientes) {
            try {
                c.getOutputStream().write(buf, 0, n);
            } catch (Exception e) {
                clientes.remove(c);
                try { c.close(); } catch (Exception ignored) { }
                avisarClientes();
            }
        }
    }

    private void avisarClientes() {
        JSObject o = new JSObject();
        JSArray lista = new JSArray();
        for (Socket c : clientes) lista.put(c.getInetAddress().getHostAddress());
        o.put("count", clientes.size());
        o.put("list", lista);
        emitir("clients", o);
    }

    private JSArray ipsLocais() {
        JSArray ips = new JSArray();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) ips.put(ni.getName() + " " + a.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return ips;
    }

    @PluginMethod
    public void startServer(final PluginCall call) {
        final int port = call.getInt("port", 5017);
        pararServidor();
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port));
            srv = ss;
            JSObject r = new JSObject();
            r.put("ips", ipsLocais());
            call.resolve(r);
            servico();
            new Thread(() -> {
                while (srv != null && !srv.isClosed()) {
                    try {
                        Socket c = srv.accept();
                        c.setTcpNoDelay(true);
                        clientes.add(c);
                        avisarClientes();
                    } catch (Exception e) {
                        break;
                    }
                }
            }).start();
        } catch (Exception e) {
            call.reject(e.toString());
        }
    }

    /** Envia texto (NMEA gerado no app, ex.: GPS do celular) para os equipamentos conectados. */
    @PluginMethod
    public void serverSend(PluginCall call) {
        byte[] b = call.getString("data", "").getBytes(StandardCharsets.ISO_8859_1);
        if (b.length > 0) repassarClientes(b, b.length);
        call.resolve();
    }

    @PluginMethod
    public void stopServer(PluginCall call) {
        pararServidor();
        servico();
        call.resolve();
    }

    private void pararServidor() {
        try { if (srv != null) srv.close(); } catch (Exception ignored) { }
        srv = null;
        for (Socket c : clientes) { try { c.close(); } catch (Exception ignored) { } }
        clientes.clear();
        avisarClientes();
    }

    // ---------- NTRIP ----------
    private static String auth(String user, String pass) {
        String s = (user == null ? "" : user) + ":" + (pass == null ? "" : pass);
        return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static String lerLinha(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 4096) break;
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    private void ntripStatus(String st, String msg) {
        JSObject o = new JSObject();
        ntripSt = st;
        o.put("status", st);
        o.put("msg", msg == null ? "" : msg);
        o.put("bytes", ntripBytes);
        o.put("enviados", rxEnviados);
        o.put("erroEnvio", rxErroEnvio);
        o.put("descartados", rxDescartados);
        emitir("ntrip", o);
    }

    @PluginMethod
    public void ntripConnect(final PluginCall call) {
        final String host = call.getString("host", "");
        final int port = call.getInt("port", 2101);
        final String mount = call.getString("mount", "");
        final String user = call.getString("user", "");
        final String pass = call.getString("pass", "");
        ntripGga = call.getBoolean("gga", ntripGga);
        call.resolve();
        abrirNtrip(host, port, mount, user, pass);
    }

    private void abrirNtrip(final String host, final int port, final String mount, final String user, final String pass) {
        fecharNtrip();
        final int gen = ++ntripGen;
        nHost = host; nPort = port; nMount = mount; nUser = user; nPass = pass; ntripDesejado = true;
        iniciarEscritor();
        segurarCpu(true);
        servico();
        new Thread(() -> {
            try {
                ntripRun = true;
                ntripBytes = 0;
                rxEnviados = 0;
                rxDescartados = 0;
                rxErroEnvio = "";
                ntripStatus("conectando", host);
                Socket s = novoSocket(redeInternet());
                s.connect(new InetSocketAddress(host, port), 10000);
                s.setSoTimeout(15000);
                s.setKeepAlive(true);
                s.setTcpNoDelay(true);
                if (gen != ntripGen) { s.close(); return; }
                ntrip = s;
                ntripOut = s.getOutputStream();
                String req = "GET /" + mount + " HTTP/1.0\r\n"
                        + "Host: " + host + ":" + port + "\r\n"
                        + "User-Agent: NTRIP ColetoraGNSS/1.0\r\n"
                        + "Accept: */*\r\n"
                        + "Authorization: Basic " + auth(user, pass) + "\r\n"
                        + "Connection: close\r\n\r\n";
                ntripOut.write(req.getBytes(StandardCharsets.ISO_8859_1));
                ntripOut.flush();
                InputStream in = s.getInputStream();
                String primeira = lerLinha(in);
                if (primeira == null || !primeira.contains("200")) {
                    String motivo = primeira == null ? "sem resposta" : primeira;
                    if (primeira != null && primeira.contains("SOURCETABLE")) motivo = "Ponto de montagem não encontrado";
                    if (primeira != null && primeira.contains("401")) motivo = "Usuário ou senha recusados";
                    if (gen == ntripGen) { ntripDesejado = false; ntripStatus("erro", motivo); fecharNtrip(); servico(); }
                    return;
                }
                if (primeira.startsWith("HTTP")) {
                    String l;
                    while ((l = lerLinha(in)) != null && l.length() > 0) { /* cabeçalhos */ }
                }
                ntripStatus("recebendo", mount);
                aviso(true);
                byte[] buf = new byte[2048];
                int n;
                long ultimoAviso = 0;
                while (ntripRun && gen == ntripGen && (n = in.read(buf)) > 0) {
                    ntripBytes += n;
                    enfileirar(buf, n);
                    long agora = System.currentTimeMillis();
                    if (agora - ultimoAviso > 1000) { ultimoAviso = agora; ntripStatus("recebendo", mount); aviso(false); }
                }
                if (ntripRun && gen == ntripGen) ntripCaiu(gen, "O caster encerrou a conexão");
            } catch (SocketTimeoutException te) {
                if (ntripRun && gen == ntripGen) ntripCaiu(gen, "Sem dados do caster há 15 s");
            } catch (Exception e) {
                if (ntripRun && gen == ntripGen) ntripCaiu(gen, e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                if (gen == ntripGen) ntripRun = false;
            }
        }).start();
    }

    /** O caster caiu: com a tela aberta o app decide; com ela fechada o serviço reconecta sozinho. */
    private void ntripCaiu(final int gen, String msg) {
        if (uiVisivel || !ntripDesejado) { ntripStatus("erro", msg); return; }
        ntripRecons++;
        aviso(true);
        new Thread(() -> {
            try { Thread.sleep(5000); } catch (Exception e) { return; }
            if (gen != ntripGen || !ntripDesejado) return;
            if (uiVisivel) { ntripStatus("erro", "Reconectando…"); return; }
            abrirNtrip(nHost, nPort, nMount, nUser, nPass);
        }).start();
    }

    @PluginMethod
    public void ntripGGA(PluginCall call) {
        try {
            String line = call.getString("line", "");
            OutputStream o = ntripOut;
            if (o != null && line.length() > 0) {
                ggaJs = System.currentTimeMillis();
                synchronized (o) { o.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1)); o.flush(); }
            }
            call.resolve();
        } catch (Exception e) {
            call.reject(e.toString());
        }
    }

    @PluginMethod
    public void ntripDisconnect(PluginCall call) {
        ntripDesejado = false;
        fecharNtrip();
        ntripGen++;
        segurarCpu(false);
        filaRtcm.clear();
        filaBytes = 0;
        ntripStatus("desligado", "");
        servico();
        call.resolve();
    }

    private void fecharNtrip() {
        ntripRun = false;
        try { if (ntrip != null) ntrip.close(); } catch (Exception ignored) { }
        ntrip = null;
        ntripOut = null;
    }

    /** Baixa a lista de pontos de montagem (sourcetable) do caster. */
    @PluginMethod
    public void ntripSourcetable(final PluginCall call) {
        final String host = call.getString("host", "");
        final int port = call.getInt("port", 2101);
        final String user = call.getString("user", "");
        final String pass = call.getString("pass", "");
        new Thread(() -> {
            try (Socket s = novoSocket(redeInternet())) {
                s.connect(new InetSocketAddress(host, port), 10000);
                s.setSoTimeout(15000);
                String req = "GET / HTTP/1.0\r\nUser-Agent: NTRIP ColetoraGNSS/1.0\r\n"
                        + "Authorization: Basic " + auth(user, pass) + "\r\n\r\n";
                s.getOutputStream().write(req.getBytes(StandardCharsets.ISO_8859_1));
                InputStream in = s.getInputStream();
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    b.write(buf, 0, n);
                    if (b.toString("ISO-8859-1").contains("ENDSOURCETABLE")) break;
                    if (b.size() > 2_000_000) break;
                }
                JSObject r = new JSObject();
                r.put("text", b.toString("ISO-8859-1"));
                call.resolve(r);
            } catch (Exception e) {
                call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }).start();
    }

    @Override
    protected void handleOnDestroy() {
        // a tela foi fechada (ou o app tirado dos recentes): as conexões continuam no serviço
        if (atual == this) { uiViva = false; uiVisivel = false; }
    }

    // ---------- localização simulada (outros apps usam a posição do receptor) ----------
    private LocationManager lm() { return (LocationManager) ctx().getSystemService(Context.LOCATION_SERVICE); }

    @SuppressWarnings("deprecation")
    @PluginMethod
    public void mockStart(PluginCall call) {
        try {
            LocationManager m = lm();
            try { m.removeTestProvider(LocationManager.GPS_PROVIDER); } catch (Exception ignored) { }
            m.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true, 1, 1);
            m.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            mockLigado = true;
            call.resolve();
        } catch (SecurityException se) {
            call.reject("Escolha a Coletora GNSS como app de local fictício nas Opções do desenvolvedor do Android");
        } catch (Exception e) {
            call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    @PluginMethod
    public void mockSet(PluginCall call) {
        try {
            Location l = new Location(LocationManager.GPS_PROVIDER);
            l.setLatitude(call.getDouble("lat", 0.0));
            l.setLongitude(call.getDouble("lon", 0.0));
            l.setAltitude(call.getDouble("alt", 0.0));
            l.setAccuracy(call.getDouble("acc", 1.0).floatValue());
            l.setSpeed(call.getDouble("speed", 0.0).floatValue());
            l.setBearing(call.getDouble("bearing", 0.0).floatValue());
            l.setTime(System.currentTimeMillis());
            l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            if (Build.VERSION.SDK_INT >= 26) {
                l.setVerticalAccuracyMeters(call.getDouble("acc", 1.0).floatValue() * 1.7f);
                l.setSpeedAccuracyMetersPerSecond(0.1f);
                l.setBearingAccuracyDegrees(5f);
            }
            lm().setTestProviderLocation(LocationManager.GPS_PROVIDER, l);
            call.resolve();
        } catch (SecurityException se) {
            call.reject("Permissão de local fictício retirada nas Opções do desenvolvedor");
        } catch (Exception e) {
            call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    @PluginMethod
    public void mockStop(PluginCall call) {
        mockLigado = false;
        try { lm().removeTestProvider(LocationManager.GPS_PROVIDER); } catch (Exception ignored) { }
        call.resolve();
    }

    // ---------- voz (avisos falados em português) ----------
    private static TextToSpeech tts;
    private static volatile boolean ttsOk = false;

    @PluginMethod
    public void speak(PluginCall call) {
        final String txt = call.getString("text", "");
        try {
            if (tts == null) {
                tts = new TextToSpeech(ctx(), st -> {
                    if (st == TextToSpeech.SUCCESS) {
                        int r = tts.setLanguage(new Locale("pt", "BR"));
                        ttsOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                        if (!ttsOk) { tts.setLanguage(Locale.getDefault()); ttsOk = true; }
                        if (txt.length() > 0) tts.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "c");
                    }
                });
            } else if (ttsOk && txt.length() > 0) {
                tts.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "c");
            }
            call.resolve();
        } catch (Exception e) {
            call.reject(e.toString());
        }
    }

    // ---------- descobrir o receptor na rede Wi-Fi dele ----------
    private static String ipv4(int v) {
        return (v & 0xff) + "." + ((v >> 8) & 0xff) + "." + ((v >> 16) & 0xff) + "." + ((v >> 24) & 0xff);
    }

    @SuppressWarnings("deprecation")
    @PluginMethod
    public void wifiInfo(PluginCall call) {
        JSObject r = new JSObject();
        try {
            Network n = redeWifi();
            if (n != null) {
                LinkProperties lp = cm().getLinkProperties(n);
                if (lp != null) {
                    for (RouteInfo ri : lp.getRoutes()) {
                        if (ri.getGateway() instanceof Inet4Address && !ri.getGateway().isAnyLocalAddress()) { r.put("gateway", ri.getGateway().getHostAddress()); break; }
                    }
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        if (la.getAddress() instanceof Inet4Address) r.put("ip", la.getAddress().getHostAddress());
                    }
                    if (Build.VERSION.SDK_INT >= 30 && lp.getDhcpServerAddress() != null) r.put("dhcp", lp.getDhcpServerAddress().getHostAddress());
                }
            }
            try {
                WifiManager wm = (WifiManager) ctx().getSystemService(Context.WIFI_SERVICE);
                if (wm != null && wm.getDhcpInfo() != null) {
                    if (wm.getDhcpInfo().gateway != 0 && !r.has("gateway")) r.put("gateway", ipv4(wm.getDhcpInfo().gateway));
                    if (wm.getDhcpInfo().serverAddress != 0 && !r.has("dhcp")) r.put("dhcp", ipv4(wm.getDhcpInfo().serverAddress));
                    if (wm.getConnectionInfo() != null) r.put("ssid", wm.getConnectionInfo().getSSID());
                }
            } catch (Exception ignored) { }
            call.resolve(r);
        } catch (Exception e) {
            call.reject(e.toString());
        }
    }

    /** Varre uma faixa de portas em paralelo (rede local: portas fechadas respondem na hora). */
    @PluginMethod
    public void scanRange(final PluginCall call) {
        final String host = call.getString("host", "");
        final int de = call.getInt("from", 1), ate = call.getInt("to", 65535);
        new Thread(() -> {
            final List<Integer> abertas = Collections.synchronizedList(new java.util.ArrayList<>());
            try {
                final Network n = redeWifi();
                java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(96);
                for (int p = de; p <= ate; p++) {
                    final int port = p;
                    ex.submit(() -> {
                        try { Socket s = novoSocket(n); s.connect(new InetSocketAddress(host, port), 400); s.close(); abertas.add(port); } catch (Exception ignored) { }
                    });
                }
                ex.shutdown();
                ex.awaitTermination(240, java.util.concurrent.TimeUnit.SECONDS);
                JSArray a = new JSArray();
                List<Integer> ord = new java.util.ArrayList<>(abertas);
                Collections.sort(ord);
                for (int v : ord) a.put(v);
                JSObject r = new JSObject();
                r.put("open", a);
                call.resolve(r);
            } catch (Exception e) {
                call.reject(e.toString());
            }
        }).start();
    }

    @PluginMethod
    public void scanPorts(final PluginCall call) {
        final String host = call.getString("host", "");
        final JSArray ports = call.getArray("ports", new JSArray());
        new Thread(() -> {
            JSArray abertas = new JSArray();
            try {
                Network n = redeWifi();
                for (int i = 0; i < ports.length(); i++) {
                    int port = ports.getInt(i);
                    try {
                        Socket s = novoSocket(n);
                        s.connect(new InetSocketAddress(host, port), 700);
                        s.close();
                        abertas.put(port);
                    } catch (Exception ignored) { }
                }
                JSObject r = new JSObject();
                r.put("open", abertas);
                call.resolve(r);
            } catch (Exception e) {
                call.reject(e.toString());
            }
        }).start();
    }

    /** Conecta numa porta e lê o que chegar em até 2,5 s (para saber se é a porta de dados NMEA). */
    @PluginMethod
    public void probePort(final PluginCall call) {
        final String host = call.getString("host", "");
        final int port = call.getInt("port", 0);
        new Thread(() -> {
            JSObject r = new JSObject();
            try {
                Socket s = novoSocket(redeWifi());
                s.connect(new InetSocketAddress(host, port), 1500);
                s.setSoTimeout(400);
                InputStream in = s.getInputStream();
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                long fim = System.currentTimeMillis() + 2500;
                byte[] buf = new byte[1024];
                boolean fechou = false;
                while (System.currentTimeMillis() < fim && b.size() < 4096) {
                    try { int n = in.read(buf); if (n < 0) { fechou = true; break; } b.write(buf, 0, n); } catch (SocketTimeoutException te) { }
                }
                s.close();
                r.put("bytes", b.size());
                r.put("closed", fechou);
                r.put("text", new String(b.toByteArray(), 0, Math.min(b.size(), 600), StandardCharsets.ISO_8859_1));
            } catch (Exception e) {
                r.put("bytes", -1);
                r.put("error", e.toString());
            }
            call.resolve(r);
        }).start();
    }
}
