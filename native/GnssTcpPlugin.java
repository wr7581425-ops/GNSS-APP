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
                @Permission(alias = "bluetooth", strings = {"android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN"})
        })
public class GnssTcpPlugin extends Plugin {

    private volatile Socket rx;
    private volatile OutputStream rxOut;
    private volatile boolean rxRun = false;
    private volatile BluetoothSocket bt;
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private volatile ServerSocket srv;
    private final List<Socket> clientes = new CopyOnWriteArrayList<>();

    private volatile Socket ntrip;
    private volatile OutputStream ntripOut;
    private volatile long rxEnviados = 0;
    private volatile String rxErroEnvio = "";
    private volatile boolean ntripRun = false;
    private volatile long ntripBytes = 0;
    private volatile int ntripGen = 0;
    private volatile long rxDescartados = 0;
    private PowerManager.WakeLock wl;

    // fila da correção para o receptor: a leitura do caster nunca espera o Bluetooth;
    // se acumular mais de 6 KB (~3 s), descarta o atraso, porque correção velha não serve.
    private final LinkedBlockingQueue<byte[]> filaRtcm = new LinkedBlockingQueue<>();
    private volatile int filaBytes = 0;
    // formato do envio da correção ao receptor: "raw" (RTCM puro) ou "vd" (envelope $$VD da ComNav, blocos de 55 bytes)
    private volatile String envelope = "raw";

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

    @PluginMethod
    public void setEnvelope(PluginCall call) {
        envelope = call.getString("mode", "raw");
        call.resolve();
    }
    private Thread escritor;

    private synchronized void iniciarEscritor() {
        if (escritor != null && escritor.isAlive()) return;
        escritor = new Thread(() -> {
            while (true) {
                try {
                    byte[] b = filaRtcm.poll(1, TimeUnit.SECONDS);
                    if (b == null) continue;
                    filaBytes -= b.length;
                    OutputStream o = rxOut;
                    if (o == null) { rxErroEnvio = "receptor não conectado"; continue; }
                    byte[] w = "vd".equals(envelope) ? envelopeVD(b) : b;
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
                    PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
                    wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ColetoraGNSS:ntrip");
                    wl.setReferenceCounted(false);
                }
                if (!wl.isHeld()) wl.acquire(12 * 60 * 60 * 1000L);
            } else if (wl != null && wl.isHeld()) wl.release();
        } catch (Exception ignored) { }
    }

    // ---------- redes ----------
    private ConnectivityManager cm() {
        return (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
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
        notifyListeners("status", o);
    }

    // ---------- receptor ----------
    @PluginMethod
    public void connect(final PluginCall call) {
        final String host = call.getString("host", "192.168.1.1");
        final int port = call.getInt("port", 9902);
        final boolean usarWifi = call.getBoolean("wifi", true);
        fecharReceptor();
        new Thread(() -> {
            boolean respondeu = false;
            try {
                Socket s = novoSocket(usarWifi ? redeWifi() : null);
                s.connect(new InetSocketAddress(host, port), 8000);
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                rx = s;
                rxOut = s.getOutputStream();
                rxRun = true;
                JSObject r = new JSObject();
                r.put("connected", true);
                call.resolve(r);
                respondeu = true;
                status("conectado", host + ":" + port);
                lerReceptor(s.getInputStream());
            } catch (Exception e) {
                if (!respondeu) call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
                else if (rxRun) status("desconectado", e.getMessage());
            } finally {
                rxRun = false;
            }
        }).start();
    }

    private void lerReceptor(InputStream in) throws Exception {
        byte[] buf = new byte[4096];
        int n;
        while (rxRun && (n = in.read(buf)) > 0) {
            repassarClientes(buf, n);
            JSObject d = new JSObject();
            d.put("text", new String(buf, 0, n, StandardCharsets.ISO_8859_1));
            notifyListeners("data", d);
        }
        if (rxRun) status("desconectado", "O receptor encerrou a conexão");
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
    private void conectarBt(final PluginCall call) {
        final String addr = call.getString("address", "");
        fecharReceptor();
        new Thread(() -> {
            boolean respondeu = false;
            try {
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (ad == null) throw new Exception("Este celular não tem Bluetooth");
                if (!ad.isEnabled()) throw new Exception("Ligue o Bluetooth do celular");
                ad.cancelDiscovery();
                BluetoothDevice dev = ad.getRemoteDevice(addr);
                BluetoothSocket s = null;
                Exception ultimo = null;
                try { s = dev.createRfcommSocketToServiceRecord(SPP); s.connect(); }
                catch (Exception e1) {
                    ultimo = e1;
                    try { if (s != null) s.close(); } catch (Exception ignored) { }
                    s = null;
                    try { s = dev.createInsecureRfcommSocketToServiceRecord(SPP); s.connect(); ultimo = null; }
                    catch (Exception e2) {
                        ultimo = e2;
                        try { if (s != null) s.close(); } catch (Exception ignored) { }
                        s = null;
                        try {
                            Method m = dev.getClass().getMethod("createRfcommSocket", int.class);
                            s = (BluetoothSocket) m.invoke(dev, 1);
                            s.connect();
                            ultimo = null;
                        } catch (Exception e3) { ultimo = e3; s = null; }
                    }
                }
                if (s == null) throw ultimo != null ? ultimo : new Exception("Falha ao conectar");
                bt = s;
                rxOut = s.getOutputStream();
                rxRun = true;
                JSObject r = new JSObject();
                r.put("connected", true);
                call.resolve(r);
                respondeu = true;
                status("conectado", addr);
                lerReceptor(s.getInputStream());
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                if (!respondeu) call.reject(msg);
                else if (rxRun) status("desconectado", msg);
            } finally {
                rxRun = false;
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
        fecharReceptor();
        call.resolve();
    }

    private void fecharReceptor() {
        rxRun = false;
        try { if (rx != null) rx.close(); } catch (Exception ignored) { }
        try { if (bt != null) bt.close(); } catch (Exception ignored) { }
        rx = null;
        bt = null;
        rxOut = null;
    }

    // ---------- servidor NMEA ----------
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
        notifyListeners("clients", o);
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
        o.put("status", st);
        o.put("msg", msg == null ? "" : msg);
        o.put("bytes", ntripBytes);
        o.put("enviados", rxEnviados);
        o.put("erroEnvio", rxErroEnvio);
        o.put("descartados", rxDescartados);
        notifyListeners("ntrip", o);
    }

    @PluginMethod
    public void ntripConnect(final PluginCall call) {
        final String host = call.getString("host", "");
        final int port = call.getInt("port", 2101);
        final String mount = call.getString("mount", "");
        final String user = call.getString("user", "");
        final String pass = call.getString("pass", "");
        fecharNtrip();
        final int gen = ++ntripGen;
        iniciarEscritor();
        segurarCpu(true);
        call.resolve();
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
                    if (gen == ntripGen) { ntripStatus("erro", motivo); fecharNtrip(); }
                    return;
                }
                if (primeira.startsWith("HTTP")) {
                    String l;
                    while ((l = lerLinha(in)) != null && l.length() > 0) { /* cabeçalhos */ }
                }
                ntripStatus("recebendo", mount);
                byte[] buf = new byte[2048];
                int n;
                long ultimoAviso = 0;
                while (ntripRun && gen == ntripGen && (n = in.read(buf)) > 0) {
                    ntripBytes += n;
                    enfileirar(buf, n);
                    long agora = System.currentTimeMillis();
                    if (agora - ultimoAviso > 1000) { ultimoAviso = agora; ntripStatus("recebendo", mount); }
                }
                if (ntripRun && gen == ntripGen) ntripStatus("erro", "O caster encerrou a conexão");
            } catch (SocketTimeoutException te) {
                if (ntripRun && gen == ntripGen) ntripStatus("erro", "Sem dados do caster há 15 s");
            } catch (Exception e) {
                if (ntripRun && gen == ntripGen) ntripStatus("erro", e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                if (gen == ntripGen) ntripRun = false;
            }
        }).start();
    }

    @PluginMethod
    public void ntripGGA(PluginCall call) {
        try {
            String line = call.getString("line", "");
            if (ntripOut != null && line.length() > 0) {
                ntripOut.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                ntripOut.flush();
            }
            call.resolve();
        } catch (Exception e) {
            call.reject(e.toString());
        }
    }

    @PluginMethod
    public void ntripDisconnect(PluginCall call) {
        fecharNtrip();
        ntripGen++;
        segurarCpu(false);
        filaRtcm.clear();
        filaBytes = 0;
        ntripStatus("desligado", "");
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
        fecharReceptor();
        fecharNtrip();
        pararServidor();
    }

    // ---------- localização simulada (outros apps usam a posição do receptor) ----------
    private LocationManager lm() { return (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE); }

    @SuppressWarnings("deprecation")
    @PluginMethod
    public void mockStart(PluginCall call) {
        try {
            LocationManager m = lm();
            try { m.removeTestProvider(LocationManager.GPS_PROVIDER); } catch (Exception ignored) { }
            m.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true, 1, 1);
            m.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
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
        try { lm().removeTestProvider(LocationManager.GPS_PROVIDER); } catch (Exception ignored) { }
        call.resolve();
    }

    // ---------- voz (avisos falados em português) ----------
    private TextToSpeech tts;
    private volatile boolean ttsOk = false;

    @PluginMethod
    public void speak(PluginCall call) {
        final String txt = call.getString("text", "");
        try {
            if (tts == null) {
                tts = new TextToSpeech(getContext(), st -> {
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
                WifiManager wm = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
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
}
