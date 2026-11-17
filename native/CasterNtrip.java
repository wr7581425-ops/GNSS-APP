package br.com.coletoragnss.app;

import android.net.Network;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Drone RTK: o receptor da base manda RTCM 3 para o celular (Bluetooth ou Wi-Fi) e o app repassa:
 *  - caster no próprio celular: o controle do drone conecta no roteador (hotspot) do celular com IP, porta,
 *    ponto de montagem, usuário e senha. Funciona sem internet.
 *  - envio para um caster na internet (NTRIP server): NTRIP 1 (SOURCE senha /ponto) ou NTRIP 2 (POST com usuário e senha).
 * Só mensagens RTCM 3 com CRC válido saem; o NMEA que vem junto do receptor fica de fora.
 */
public class CasterNtrip {

    interface Ouvinte { void status(JSObject o); }
    interface Rede { Network internet(); }
    static volatile Ouvinte ouvinte;
    static volatile Rede rede;

    // ---------- separação dos quadros RTCM 3 (0xD3, 10 bits de tamanho, carga, CRC-24Q) ----------
    private static final int[] CRC = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int c = i << 16;
            for (int k = 0; k < 8; k++) { c <<= 1; if ((c & 0x1000000) != 0) c ^= 0x1864CFB; }
            CRC[i] = c & 0xFFFFFF;
        }
    }
    static int crc24q(byte[] b, int ini, int n) {
        int c = 0;
        for (int i = ini; i < ini + n; i++) c = ((c << 8) & 0xFFFFFF) ^ CRC[((c >> 16) ^ b[i]) & 0xFF];
        return c;
    }

    private static final byte[] acum = new byte[16384];
    private static int nAcum = 0;
    static volatile long rtcmBytes = 0, rtcmUltimo = 0;
    private static final TreeMap<Integer, Long> tipos = new TreeMap<>();

    static boolean ativo() { return local != null || upDesejado; }

    /** Chamado com cada pedaço lido do receptor. */
    static void alimentar(byte[] buf, int n) {
        if (!ativo()) { nAcum = 0; return; }
        synchronized (acum) {
            for (int k = 0; k < n; ) {
                int cabe = Math.min(n - k, acum.length - nAcum);
                if (cabe <= 0) { nAcum = 0; continue; }
                System.arraycopy(buf, k, acum, nAcum, cabe);
                nAcum += cabe; k += cabe;
                extrair();
            }
        }
    }

    private static void extrair() {
        int i = 0;
        while (i < nAcum) {
            if ((acum[i] & 0xFF) != 0xD3) { i++; continue; }
            if (nAcum - i < 3) break;
            if ((acum[i + 1] & 0xFC) != 0) { i++; continue; }
            int len = ((acum[i + 1] & 0x03) << 8) | (acum[i + 2] & 0xFF);
            int total = len + 6;
            if (nAcum - i < total) break;
            int crc = ((acum[i + 3 + len] & 0xFF) << 16) | ((acum[i + 4 + len] & 0xFF) << 8) | (acum[i + 5 + len] & 0xFF);
            if (len >= 2 && crc24q(acum, i, 3 + len) == crc) {
                byte[] q = new byte[total];
                System.arraycopy(acum, i, q, 0, total);
                int tipo = ((acum[i + 3] & 0xFF) << 4) | ((acum[i + 4] & 0xFF) >> 4);
                synchronized (tipos) { tipos.put(tipo, System.currentTimeMillis()); }
                rtcmBytes += total;
                rtcmUltimo = System.currentTimeMillis();
                distribuir(q);
                i += total;
            } else i++;
        }
        if (i > 0) { System.arraycopy(acum, i, acum, 0, nAcum - i); nAcum -= i; }
    }

    // ---------- saída: cada destino tem a própria fila (um controle lento não trava os outros nem o receptor) ----------
    static class Destino {
        final LinkedBlockingQueue<byte[]> fila = new LinkedBlockingQueue<>();
        volatile int bytesFila = 0;
        volatile long enviados = 0;
        void por(byte[] b) {
            if (bytesFila > 8192) { fila.clear(); bytesFila = 0; } // correção atrasada não serve: descarta
            bytesFila += b.length;
            fila.offer(b);
        }
        byte[] tirar() throws InterruptedException {
            byte[] b = fila.poll(1, TimeUnit.SECONDS);
            if (b != null) bytesFila -= b.length;
            return b;
        }
    }

    static class Cliente extends Destino {
        Socket s; String ip; String usuario = ""; boolean v2; long desde = System.currentTimeMillis();
    }

    private static final List<Cliente> clientes = new CopyOnWriteArrayList<>();
    private static volatile Destino up;

    private static void distribuir(byte[] q) {
        for (Cliente c : clientes) c.por(q);
        Destino u = up;
        if (u != null) u.por(q);
    }

    private static void escreverChunk(OutputStream o, byte[] b) throws Exception {
        o.write((Integer.toHexString(b.length) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        o.write(b);
        o.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
    }

    // ---------- caster no celular ----------
    private static volatile ServerSocket local;
    static volatile int lPorta = 2101;
    static volatile String lMount = "WASURVEY", lUser = "", lPass = "";

    static void iniciarLocal(int porta, String mount, String user, String pass) throws Exception {
        pararLocal();
        lPorta = porta; lMount = mount; lUser = user == null ? "" : user; lPass = pass == null ? "" : pass;
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(porta));
        local = ss;
        iniciarAvisos();
        new Thread(() -> {
            while (local == ss && !ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    new Thread(() -> atender(s)).start();
                } catch (Exception e) { break; }
            }
        }).start();
    }

    static void pararLocal() {
        ServerSocket ss = local;
        local = null;
        try { if (ss != null) ss.close(); } catch (Exception ignored) { }
        for (Cliente c : clientes) { try { c.s.close(); } catch (Exception ignored) { } }
        clientes.clear();
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

    private static String tabela() {
        String str = "STR;" + lMount + ";" + lMount + ";RTCM 3.2;1005(10),1074(1),1084(1),1094(1),1124(1);2;GPS+GLO+GAL+BDS;WASURVEY;BRA;0.00;0.00;0;0;WA Survey;none;"
                + (lUser.isEmpty() && lPass.isEmpty() ? "N" : "B") + ";N;9600;\r\n";
        String corpo = str + "ENDSOURCETABLE\r\n";
        return "SOURCETABLE 200 OK\r\nServer: NTRIP WA Survey/1.0\r\nContent-Type: text/plain\r\nContent-Length: " + corpo.length() + "\r\n\r\n" + corpo;
    }

    private static void atender(Socket s) {
        Cliente c = new Cliente();
        c.s = s;
        c.ip = s.getInetAddress().getHostAddress();
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(10000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String primeira = lerLinha(in);
            if (primeira == null) { s.close(); return; }
            String auth = "";
            String l;
            while ((l = lerLinha(in)) != null && l.length() > 0) {
                String b = l.toLowerCase(Locale.US);
                if (b.startsWith("authorization:")) auth = l.substring(14).trim();
                if (b.startsWith("ntrip-version:") && b.contains("2.0")) c.v2 = true;
            }
            String[] p = primeira.split(" ");
            String caminho = p.length > 1 ? p[1] : "/";
            String mount = caminho.startsWith("/") ? caminho.substring(1) : caminho;
            if (!p[0].equalsIgnoreCase("GET") || mount.isEmpty() || !mount.equalsIgnoreCase(lMount)) {
                out.write(tabela().getBytes(StandardCharsets.ISO_8859_1));
                out.flush(); s.close();
                return;
            }
            if (!(lUser.isEmpty() && lPass.isEmpty())) {
                String u = "", pw = "";
                if (auth.toLowerCase(Locale.US).startsWith("basic ")) {
                    try {
                        String dec = new String(Base64.decode(auth.substring(6).trim(), Base64.DEFAULT), StandardCharsets.UTF_8);
                        int k = dec.indexOf(':');
                        u = k >= 0 ? dec.substring(0, k) : dec; pw = k >= 0 ? dec.substring(k + 1) : "";
                    } catch (Exception ignored) { }
                }
                if (!u.equals(lUser) || !pw.equals(lPass)) {
                    String r = c.v2 ? "HTTP/1.1 401 Unauthorized\r\nNtrip-Version: Ntrip/2.0\r\nWWW-Authenticate: Basic realm=\"/" + lMount + "\"\r\nConnection: close\r\n\r\n"
                            : "HTTP/1.0 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"/" + lMount + "\"\r\n\r\n";
                    out.write(r.getBytes(StandardCharsets.ISO_8859_1));
                    out.flush(); s.close();
                    aviso("recusado", c.ip + ": usuário ou senha errados");
                    return;
                }
                c.usuario = u;
            }
            String ok = c.v2 ? "HTTP/1.1 200 OK\r\nNtrip-Version: Ntrip/2.0\r\nServer: NTRIP WA Survey/1.0\r\nContent-Type: gnss/data\r\nCache-Control: no-store, no-cache, max-age=0\r\nPragma: no-cache\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n\r\n"
                    : "ICY 200 OK\r\n\r\n";
            out.write(ok.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            s.setSoTimeout(0);
            clientes.add(c);
            // o controle manda a posição (GGA) de vez em quando: lê e descarta; serve para saber quando desconectou
            new Thread(() -> {
                try { byte[] b = new byte[512]; while (in.read(b) > 0) { } } catch (Exception ignored) { }
                fecharCliente(c);
            }).start();
            while (clientes.contains(c)) {
                byte[] b = c.tirar();
                if (b == null) continue;
                if (c.v2) escreverChunk(out, b); else out.write(b);
                out.flush();
                c.enviados += b.length;
            }
        } catch (Exception e) {
            fecharCliente(c);
        }
    }

    private static void fecharCliente(Cliente c) {
        clientes.remove(c);
        try { c.s.close(); } catch (Exception ignored) { }
    }

    // ---------- envio para caster na internet ----------
    static volatile boolean upDesejado = false;
    private static volatile int upGen = 0;
    private static volatile Socket upSock;
    static volatile String uHost = "", uMount = "", uUser = "", uPass = "", upSt = "desligado", upMsg = "";
    static volatile int uPorta = 2101, uVer = 1, upRecons = 0;

    static void iniciarUp(String host, int porta, String mount, String user, String pass, int ver) {
        pararUp();
        uHost = host; uPorta = porta; uMount = mount; uUser = user == null ? "" : user; uPass = pass == null ? "" : pass; uVer = ver;
        upDesejado = true; upRecons = 0;
        iniciarAvisos();
        final int gen = ++upGen;
        new Thread(() -> {
            while (upDesejado && gen == upGen) {
                Destino d = new Destino();
                try {
                    upSt = "conectando"; upMsg = uHost + ":" + uPorta;
                    Rede r = rede;
                    Network n = r == null ? null : r.internet();
                    Socket s = n != null ? n.getSocketFactory().createSocket() : new Socket();
                    s.connect(new InetSocketAddress(uHost, uPorta), 10000);
                    s.setTcpNoDelay(true); s.setKeepAlive(true); s.setSoTimeout(10000);
                    upSock = s;
                    OutputStream out = s.getOutputStream();
                    InputStream in = s.getInputStream();
                    String req;
                    if (uVer == 2) {
                        String a = Base64.encodeToString((uUser + ":" + uPass).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                        req = "POST /" + uMount + " HTTP/1.1\r\nHost: " + uHost + ":" + uPorta + "\r\nNtrip-Version: Ntrip/2.0\r\n"
                                + "Authorization: Basic " + a + "\r\nUser-Agent: NTRIP WASurvey/1.0\r\nNtrip-STR: \r\n"
                                + "Content-Type: gnss/data\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n";
                    } else {
                        req = "SOURCE " + uPass + " /" + uMount + "\r\nSource-Agent: NTRIP WASurvey/1.0\r\nSTR: \r\n\r\n";
                    }
                    out.write(req.getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    String resp = lerLinha(in);
                    if (resp == null || !resp.contains("200")) {
                        String m = resp == null ? "sem resposta do caster" : resp;
                        if (resp != null && (resp.contains("401") || resp.toUpperCase(Locale.US).contains("BAD PASSWORD"))) m = "Senha (ou usuário) recusada pelo caster";
                        if (resp != null && resp.contains("409")) m = "Ponto de montagem já está em uso por outra base";
                        if (resp != null && resp.contains("404")) m = "Ponto de montagem não existe no caster";
                        throw new Exception(m);
                    }
                    if (resp.startsWith("HTTP")) { String l; while ((l = lerLinha(in)) != null && l.length() > 0) { } }
                    s.setSoTimeout(0);
                    up = d;
                    upSt = "enviando"; upMsg = uMount; upRecons = 0;
                    long semDados = System.currentTimeMillis();
                    while (upDesejado && gen == upGen) {
                        byte[] b = d.tirar();
                        if (b == null) {
                            // sem correção da base há 30 s: mantém a conexão, só avisa na tela
                            if (System.currentTimeMillis() - semDados > 30000) upMsg = "sem RTCM da base";
                            continue;
                        }
                        semDados = System.currentTimeMillis(); upMsg = uMount;
                        if (uVer == 2) escreverChunk(out, b); else out.write(b);
                        out.flush();
                        d.enviados += b.length;
                    }
                } catch (Exception e) {
                    if (!upDesejado || gen != upGen) break;
                    upSt = "erro";
                    upMsg = e.getMessage() == null ? e.toString() : e.getMessage();
                } finally {
                    if (up == d) up = null;
                    try { if (upSock != null) upSock.close(); } catch (Exception ignored) { }
                    upSock = null;
                }
                if (!upDesejado || gen != upGen) break;
                upRecons++;
                try { Thread.sleep(5000); } catch (Exception e) { break; }
            }
            if (gen == upGen) { upSt = "desligado"; }
        }).start();
    }

    static void pararUp() {
        upDesejado = false;
        upGen++;
        try { if (upSock != null) upSock.close(); } catch (Exception ignored) { }
        upSock = null; up = null;
        upSt = "desligado"; upMsg = "";
    }

    static void pararTudo() { pararLocal(); pararUp(); }

    // ---------- situação para a tela (a cada segundo) ----------
    private static Thread avisos;

    private static void aviso(String tipo, String msg) {
        Ouvinte o = ouvinte;
        if (o == null) return;
        JSObject j = new JSObject();
        j.put("evento", tipo); j.put("msg", msg);
        try { o.status(j); } catch (Exception ignored) { }
    }

    static JSObject estado() {
        JSObject j = new JSObject();
        j.put("local", local != null);
        j.put("porta", lPorta);
        j.put("mount", lMount);
        JSArray cl = new JSArray();
        for (Cliente c : clientes) {
            JSObject x = new JSObject();
            x.put("ip", c.ip); x.put("user", c.usuario); x.put("bytes", c.enviados); x.put("desde", c.desde);
            cl.put(x);
        }
        j.put("clientes", cl);
        j.put("up", upDesejado);
        j.put("upSt", upSt);
        j.put("upMsg", upMsg);
        j.put("upRecons", upRecons);
        Destino u = up;
        j.put("upBytes", u == null ? 0 : u.enviados);
        j.put("rtcmBytes", rtcmBytes);
        j.put("rtcmIdade", rtcmUltimo == 0 ? -1 : System.currentTimeMillis() - rtcmUltimo);
        StringBuilder t = new StringBuilder();
        long agora = System.currentTimeMillis();
        synchronized (tipos) {
            for (java.util.Map.Entry<Integer, Long> e : tipos.entrySet())
                if (agora - e.getValue() < 30000) { if (t.length() > 0) t.append(","); t.append(e.getKey()); }
        }
        j.put("tipos", t.toString());
        return j;
    }

    private static synchronized void iniciarAvisos() {
        if (avisos != null && avisos.isAlive()) return;
        avisos = new Thread(() -> {
            while (ativo()) {
                Ouvinte o = ouvinte;
                if (o != null) { try { o.status(estado()); } catch (Exception ignored) { } }
                try { Thread.sleep(1000); } catch (Exception e) { return; }
            }
            Ouvinte o = ouvinte;
            if (o != null) { try { o.status(estado()); } catch (Exception ignored) { } }
        });
        avisos.setDaemon(true);
        avisos.start();
    }

    static String textoAviso() {
        if (!ativo()) return "";
        StringBuilder b = new StringBuilder("Drone RTK");
        if (local != null) b.append(" · ").append(clientes.size()).append(clientes.size() == 1 ? " controle" : " controles");
        if (upDesejado) b.append(" · caster ").append("enviando".equals(upSt) ? "ok" : upSt);
        return b.toString();
    }
}
