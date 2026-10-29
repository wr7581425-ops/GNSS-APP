package br.com.coletoragnss.app;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Converte o binário ComNav (CNB do receptor ou os dados brutos gravados na coletora)
 * em RINEX 3.04: observação (.obs) e navegação GPS (.nav).
 * Usa RANGECMPB (140), RAWEPHEMB (41), GLORAWEPHEMB (792) / GLOEPHEMERISB (723) e BESTPOSB (42).
 * Java puro (sem Android) para poder ser testado fora do celular.
 */
public class CnbRinex {
    static final double C = 299792458.0;

    // ---------- sinais: sistema ComNav + tipo de sinal -> código RINEX e frequência ----------
    static String codigo(char sys, int sig, int prnR) {
        switch (sys) {
            case 'G': case 'J':
                if (sig == 0) return "1C"; if (sig == 9) return "2W"; if (sig == 17) return "2L";
                if (sig == 2 || sig == 14) return "5Q"; if (sig == 16) return "1L"; return null;
            case 'R':
                if (sig == 0) return "1C"; if (sig == 5 || sig == 1) return "2C"; if (sig == 6) return "3Q"; return null;
            case 'E':
                if (sig == 2) return "1C"; if (sig == 12) return "5Q"; if (sig == 17) return "7Q"; if (sig == 7) return "6C"; if (sig == 20) return "8Q"; return null;
            case 'C':
                if (sig == 0 || sig == 4) return "2I"; if (sig == 2 || sig == 6) return "6I"; if (sig == 12 || sig == 9) return "5P";
                if (sig == 19 || sig == 1 || sig == 5) return prnR <= 18 ? "7I" : "7D"; if (sig == 8 || sig == 7) return "1P"; return null;
            case 'S':
                if (sig == 0) return "1C"; if (sig == 6) return "5I"; return null;
        }
        return null;
    }

    static double freq(char sys, char banda, int k) {
        switch (sys) {
            case 'G': case 'J': case 'S':
                return banda == '1' ? 1575.42e6 : banda == '2' ? 1227.60e6 : banda == '5' ? 1176.45e6 : 0;
            case 'R':
                return banda == '1' ? 1602e6 + k * 0.5625e6 : banda == '2' ? 1246e6 + k * 0.4375e6 : banda == '3' ? 1202.025e6 : 0;
            case 'E':
                return banda == '1' ? 1575.42e6 : banda == '5' ? 1176.45e6 : banda == '7' ? 1207.14e6 : banda == '6' ? 1278.75e6 : banda == '8' ? 1191.795e6 : 0;
            case 'C':
                return banda == '2' ? 1561.098e6 : banda == '6' ? 1268.52e6 : banda == '5' ? 1176.45e6 : banda == '7' ? 1207.14e6 : banda == '1' ? 1575.42e6 : 0;
        }
        return 0;
    }

    // canal padrão de frequência GLONASS por slot (usado se o arquivo não tiver efeméride)
    static final int[] GLO_K = {0, 1, -4, 5, 6, 1, -4, 5, 6, -2, -7, 0, -1, -2, -7, 0, -1, 4, -3, 3, 2, 4, -3, 3, 2};

    static char sistema(int s) {
        switch (s) { case 0: return 'G'; case 1: return 'R'; case 2: return 'S'; case 3: return 'E'; case 4: return 'C'; case 5: return 'J'; default: return 0; }
    }

    static int prnRinex(char sys, int prn) {
        switch (sys) {
            case 'R': return prn >= 38 ? prn - 37 : prn;
            case 'C': return prn > 140 ? prn - 140 : prn;
            case 'J': return prn > 192 ? prn - 192 : prn;
            case 'S': return prn >= 100 ? prn - 100 : prn;
            default: return prn;
        }
    }

    // ---------- leitura das mensagens ----------
    static int u16(byte[] b, int i) { return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8; }
    static long u32(byte[] b, int i) { return (u16(b, i) | (long) u16(b, i + 2) << 16) & 0xffffffffL; }
    static int i32(byte[] b, int i) { return (int) u32(b, i); }
    static double f64(byte[] b, int i) { long v = u32(b, i) | u32(b, i + 4) << 32; return Double.longBitsToDouble(v); }
    static float f32(byte[] b, int i) { return Float.intBitsToFloat(i32(b, i)); }

    interface Ouvinte { void msg(int id, int semana, long ms, byte[] b, int corpo, int len); }

    static void varrer(byte[] b, Ouvinte o) {
        int i = 0, n = b.length;
        while (i + 28 <= n) {
            if ((b[i] & 0xff) != 0xAA || (b[i + 1] & 0xff) != 0x44 || (b[i + 2] & 0xff) != 0x12) { i++; continue; }
            int hl = b[i + 3] & 0xff, id = u16(b, i + 4), len = u16(b, i + 8), sem = u16(b, i + 14);
            long ms = u32(b, i + 16);
            if (hl < 28 || i + hl + len > n) { i++; continue; }
            o.msg(id, sem, ms, b, i + hl, len);
            i += hl + len + 4;
        }
    }

    // ---------- observação ----------
    static class Obs { double psr, fase, dop, cno; boolean temFase; int lli; }
    static class Epoca { int semana; long ms; TreeMap<String, Map<String, Obs>> sats = new TreeMap<>(); }

    static class Ephem { // efeméride GPS (LNAV)
        int prn, week, iode, iodc, ura, health, l2c, fit; double toc, af0, af1, af2, crs, dn, m0, cuc, e, cus, sqrtA, toe, cic, om0, cis, i0, crc, w, omd, idot, tgd, tt;
    }

    public static class Resultado { public String obs, nav; public int epocas, sats; public double lat, lon, h; public String inicio, fim, sistemas; }

    public static Resultado converter(File cnb, File saidaDir, String nomeBase, String marcador, double alturaAntena,
                                      double intervalo, double[] llhAprox, String receptor, String antena) throws IOException {
        byte[] b = ler(cnb);
        // cabeçalho de texto do CNB (ReceiverID, MarkerName, AntHigh)
        String cab = new String(b, 0, Math.min(400, b.length), StandardCharsets.ISO_8859_1);
        if (receptor == null || receptor.isEmpty()) receptor = campo(cab, "ReceiverID:");
        if (marcador == null || marcador.isEmpty()) marcador = campo(cab, "MarkerName:");
        if (Double.isNaN(alturaAntena)) { try { alturaAntena = Double.parseDouble(campo(cab, "AntHigh:")); } catch (Exception e) { alturaAntena = 0; } }
        if (marcador == null || marcador.isEmpty()) marcador = nomeBase;

        // 1ª passada: tipos de observação, canais GLONASS, efemérides e posição
        final TreeMap<Character, TreeSet<String>> tipos = new TreeMap<>();
        final Map<Integer, Integer> gloK = new HashMap<>();
        final Map<String, Ephem> efs = new LinkedHashMap<>();
        final double[] pos = new double[4];
        final long[] t = {Long.MAX_VALUE, Long.MIN_VALUE};
        varrer(b, (id, sem, ms, buf, o, len) -> {
            long tt = sem * 604800000L + ms;
            if (id == 140) {
                if (tt < t[0]) t[0] = tt; if (tt > t[1]) t[1] = tt;
                int n = (int) u32(buf, o);
                for (int k = 0; k < n && o + 4 + 24 * k + 24 <= o + len; k++) {
                    int r = o + 4 + 24 * k; long st = u32(buf, r);
                    char sys = sistema((int) (st >> 16) & 7); if (sys == 0) continue;
                    int prn = buf[r + 17] & 0xff;
                    String c = codigo(sys, (int) (st >> 21) & 31, prnRinex(sys, prn)); if (c == null) continue;
                    TreeSet<String> s = tipos.get(sys); if (s == null) { s = new TreeSet<>(); tipos.put(sys, s); } s.add(c);
                }
            } else if (id == 792 && len >= 4) {
                int slot = u16(buf, o), fq = u16(buf, o + 2);
                if (slot >= 38 && slot <= 61) gloK.put(slot - 37, fq - 7); else if (slot >= 1 && slot <= 24) gloK.put(slot, fq - 7);
            } else if (id == 723 && len >= 8) {
                int slot = u16(buf, o), fq = u16(buf, o + 2);
                if (slot >= 38 && slot <= 61) gloK.put(slot - 37, fq - 7);
            } else if (id == 41 && len >= 102) {
                Ephem e = decodificarGPS(buf, o, sem);
                if (e != null) efs.put(e.prn + ":" + e.toc + ":" + e.iode, e);
            } else if (id == 42 && len >= 40 && pos[3] == 0) {
                double la = f64(buf, o + 8), lo = f64(buf, o + 16), hg = f64(buf, o + 24); float und = f32(buf, o + 32);
                if (Math.abs(la) > 0.0001 && Math.abs(la) <= 90) { pos[0] = la; pos[1] = lo; pos[2] = hg + und; pos[3] = 1; }
            }
        });
        if (t[0] == Long.MAX_VALUE) throw new IOException("O arquivo não tem observações brutas (RANGECMPB). Grave de novo com o app atualizado.");

        // ordem dos códigos: por banda, C L D S
        final LinkedHashMap<Character, List<String>> obsTipos = new LinkedHashMap<>();
        for (Map.Entry<Character, TreeSet<String>> en : tipos.entrySet()) {
            List<String> l = new ArrayList<>();
            List<String> ord = new ArrayList<>(en.getValue());
            java.util.Collections.sort(ord, (x, y) -> bandaOrdem(x) - bandaOrdem(y));
            for (String c : ord) { l.add("C" + c); l.add("L" + c); l.add("D" + c); l.add("S" + c); }
            obsTipos.put(en.getKey(), l);
        }

        // posição aproximada: BESTPOS, a informada pelo app ou cálculo simples pelas pseudodistâncias
        double[] xyz;
        if (pos[3] == 1) xyz = llh2xyz(pos[0], pos[1], pos[2]);
        else if (llhAprox != null && !(llhAprox[0] == 0 && llhAprox[1] == 0)) xyz = llh2xyz(llhAprox[0], llhAprox[1], llhAprox[2]);
        else xyz = null;

        Resultado res = new Resultado();
        String nObs = nomeBase + ".obs", nNav = nomeBase + ".nav";
        File fo = new File(saidaDir, nObs);
        final long passo = Math.max(1, Math.round(intervalo * 1000));
        final double[] xyzF = xyz == null ? sppPrimeiro(b, efs) : xyz;
        double[] llh = xyz2llh(xyzF);
        res.lat = llh[0]; res.lon = llh[1]; res.h = llh[2];

        try (final BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(fo), StandardCharsets.US_ASCII), 1 << 16)) {
            cabecalhoObs(w, marcador, receptor, antena, alturaAntena, xyzF, obsTipos, gloK, t[0], intervalo);
            final Map<String, Double> lockAnt = new HashMap<>();
            final int[] cont = {0};
            final TreeSet<String> satsVistos = new TreeSet<>();
            varrer(b, (id, sem, ms, buf, o, len) -> {
                if (id != 140) return;
                long tt = sem * 604800000L + ms;
                long resto = tt % passo; if (resto > 50 && resto < passo - 50) return;
                TreeMap<String, Map<String, Obs>> sats = new TreeMap<>((x, y) -> sysOrdem(x.charAt(0)) != sysOrdem(y.charAt(0)) ? sysOrdem(x.charAt(0)) - sysOrdem(y.charAt(0)) : x.compareTo(y));
                int n = (int) u32(buf, o);
                for (int k = 0; k < n; k++) {
                    int r = o + 4 + 24 * k; if (r + 24 > o + len) break;
                    long st = u32(buf, r);
                    char sys = sistema((int) (st >> 16) & 7); if (sys == 0) continue;
                    int prn = buf[r + 17] & 0xff, pr = prnRinex(sys, prn), sig = (int) (st >> 21) & 31;
                    String c = codigo(sys, sig, pr); if (c == null || pr <= 0) continue;
                    long v = u32(buf, r + 4) | u32(buf, r + 8) << 32;
                    long dopRaw = v & 0xFFFFFFFL; if ((dopRaw & 0x8000000L) != 0) dopRaw -= (1L << 28);
                    double psr = (v >>> 28) / 128.0;
                    double adr = i32(buf, r + 12) / 256.0;
                    long wl = u32(buf, r + 18);
                    double lock = (wl & 0x1FFFFF) / 32.0; int cno = (int) ((wl >> 21) & 31) + 20;
                    int kk = 0;
                    if (sys == 'R') { Integer kx = gloK.get(pr); kk = kx != null ? kx : (pr < GLO_K.length ? GLO_K[pr] : 0); }
                    double f = freq(sys, c.charAt(0), kk); if (f == 0 || psr <= 0) continue;
                    double lam = C / f;
                    Obs ob = new Obs(); ob.psr = psr; ob.dop = dopRaw / 256.0; ob.cno = cno;
                    boolean faseOk = ((st >> 10) & 1) == 1 && adr != 0;
                    if (faseOk) {
                        double rolls = Math.round((psr / lam + adr) / 8388608.0);
                        ob.fase = -(adr - rolls * 8388608.0); ob.temFase = true;
                        String key = sys + "" + pr + c;
                        Double la = lockAnt.get(key);
                        if (la == null || lock < la) ob.lli = 1;
                        lockAnt.put(key, lock);
                    }
                    String sv = String.format(Locale.US, "%c%02d", sys, pr);
                    Map<String, Obs> m = sats.get(sv); if (m == null) { m = new HashMap<>(); sats.put(sv, m); }
                    if (!m.containsKey(c)) m.put(c, ob);
                }
                if (sats.isEmpty()) return;
                try {
                    int[] d = gpsData(tt);
                    double seg = d[5] + (tt % 1000) / 1000.0;
                    w.write(String.format(Locale.US, "> %4d %02d %02d %02d %02d%11.7f  0%3d\n", d[0], d[1], d[2], d[3], d[4], seg, sats.size()));
                    for (Map.Entry<String, Map<String, Obs>> en : sats.entrySet()) {
                        String sv = en.getKey(); satsVistos.add(sv);
                        List<String> lt = obsTipos.get(sv.charAt(0));
                        StringBuilder sb = new StringBuilder(sv);
                        int ultimo = 0; StringBuilder campos = new StringBuilder();
                        for (int q = 0; q < lt.size(); q++) {
                            String ty = lt.get(q); Obs ob = en.getValue().get(ty.substring(1));
                            String cpo = "                ";
                            if (ob != null) {
                                int ssi = Math.max(1, Math.min(9, (int) (ob.cno / 6)));
                                switch (ty.charAt(0)) {
                                    case 'C': cpo = String.format(Locale.US, "%14.3f %d", ob.psr, ssi); break;
                                    case 'L': if (ob.temFase && Math.abs(ob.fase) < 1e10) cpo = String.format(Locale.US, "%14.3f%s%d", ob.fase, ob.lli != 0 ? "1" : " ", ssi); break;
                                    case 'D': cpo = String.format(Locale.US, "%14.3f  ", ob.dop); break;
                                    case 'S': cpo = String.format(Locale.US, "%14.3f  ", ob.cno); break;
                                }
                                if (!cpo.trim().isEmpty()) ultimo = q + 1;
                            }
                            campos.append(cpo);
                        }
                        sb.append(campos.substring(0, Math.min(campos.length(), ultimo * 16)));
                        w.write(rtrim(sb.toString())); w.write('\n');
                    }
                    cont[0]++;
                } catch (IOException ex) { throw new RuntimeException(ex); }
            });
            res.epocas = cont[0]; res.sats = satsVistos.size();
        }
        res.obs = fo.getAbsolutePath();
        // navegação GPS
        if (!efs.isEmpty()) {
            File fn = new File(saidaDir, nNav);
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(fn), StandardCharsets.US_ASCII))) {
                escreverNav(w, efs.values());
            }
            res.nav = fn.getAbsolutePath();
        }
        int[] a = gpsData(t[0]), z = gpsData(t[1]);
        res.inicio = String.format(Locale.US, "%04d-%02d-%02d %02d:%02d:%02d", a[0], a[1], a[2], a[3], a[4], a[5]);
        res.fim = String.format(Locale.US, "%04d-%02d-%02d %02d:%02d:%02d", z[0], z[1], z[2], z[3], z[4], z[5]);
        res.sistemas = obsTipos.keySet().toString();
        return res;
    }

    static int bandaOrdem(String c) { char b = c.charAt(0); return (b == '1' ? 0 : b == '2' ? 1 : b == '5' ? 2 : b == '7' ? 3 : b == '8' ? 4 : b == '6' ? 5 : b == '3' ? 6 : 7) * 1000 + c.charAt(1); }
    static int sysOrdem(char s) { return "GRECJS".indexOf(s); }
    static String rtrim(String s) { int i = s.length(); while (i > 0 && s.charAt(i - 1) == ' ') i--; return s.substring(0, i); }
    static String campo(String cab, String k) { int i = cab.indexOf(k); if (i < 0) return ""; int f = cab.indexOf('\r', i); if (f < 0) f = cab.indexOf('\n', i); if (f < 0) return ""; return cab.substring(i + k.length(), f).trim(); }

    static byte[] ler(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        try (InputStream in = new FileInputStream(f)) { int o = 0, n; while (o < b.length && (n = in.read(b, o, b.length - o)) > 0) o += n; }
        return b;
    }

    static String lin(String conteudo, String rotulo) {
        StringBuilder s = new StringBuilder(conteudo);
        while (s.length() < 60) s.append(' ');
        return s.substring(0, 60) + rotulo + "\n";
    }

    static String pad(String s, int n) { if (s == null) s = ""; if (s.length() > n) return s.substring(0, n); StringBuilder b = new StringBuilder(s); while (b.length() < n) b.append(' '); return b.toString(); }

    static void cabecalhoObs(BufferedWriter w, String marcador, String receptor, String antena, double h, double[] xyz,
                             Map<Character, List<String>> tipos, Map<Integer, Integer> gloK, long t0, double intervalo) throws IOException {
        StringBuilder sysTxt = new StringBuilder();
        w.write(lin(String.format(Locale.US, "%9s%11s%-20s%-20s", "3.04", "", "OBSERVATION DATA", tipos.size() > 1 ? "M: Mixed" : "" + tipos.keySet().iterator().next()), "RINEX VERSION / TYPE"));
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        w.write(lin(pad("WA Survey", 20) + pad("WA Survey", 20) + String.format(Locale.US, "%04d%02d%02d %02d%02d%02d UTC", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)), "PGM / RUN BY / DATE"));
        w.write(lin("Convertido de CNB (ComNav) pelo WA Survey", "COMMENT"));
        w.write(lin(pad(marcador, 60), "MARKER NAME"));
        w.write(lin(pad(marcador, 20), "MARKER NUMBER"));
        w.write(lin("GEODETIC", "MARKER TYPE"));
        w.write(lin(pad("", 20) + pad("", 40), "OBSERVER / AGENCY"));
        String modelo = receptor == null ? "" : receptor.startsWith("T7") ? "COMNAV T70" : receptor.startsWith("S6") ? "COMNAV S6" : "COMNAV";
        w.write(lin(pad(receptor, 20) + pad(modelo, 20) + pad("", 20), "REC # / TYPE / VERS"));
        w.write(lin(pad("", 20) + pad(antena == null ? "" : antena, 20), "ANT # / TYPE"));
        w.write(lin(String.format(Locale.US, "%14.4f%14.4f%14.4f", xyz[0], xyz[1], xyz[2]), "APPROX POSITION XYZ"));
        w.write(lin(String.format(Locale.US, "%14.4f%14.4f%14.4f", h, 0.0, 0.0), "ANTENNA: DELTA H/E/N"));
        for (Map.Entry<Character, List<String>> en : tipos.entrySet()) {
            List<String> l = en.getValue();
            for (int i = 0; i < l.size(); i += 13) {
                StringBuilder s = new StringBuilder(i == 0 ? String.format(Locale.US, "%c  %3d", en.getKey(), l.size()) : "      ");
                for (int j = i; j < Math.min(l.size(), i + 13); j++) s.append(' ').append(l.get(j));
                w.write(lin(s.toString(), "SYS / # / OBS TYPES"));
            }
        }
        w.write(lin(String.format(Locale.US, "%10.3f", intervalo), "INTERVAL"));
        int[] d = gpsData(t0);
        w.write(lin(String.format(Locale.US, "  %04d    %02d    %02d    %02d    %02d   %10.7f     GPS", d[0], d[1], d[2], d[3], d[4], d[5] + (t0 % 1000) / 1000.0), "TIME OF FIRST OBS"));
        for (Map.Entry<Character, List<String>> en : tipos.entrySet())
            for (String ty : en.getValue()) if (ty.charAt(0) == 'L') w.write(lin(String.format(Locale.US, "%c %s %8.5f", en.getKey(), ty, 0.0), "SYS / PHASE SHIFT"));
        if (tipos.containsKey('R')) {
            TreeMap<Integer, Integer> gk = new TreeMap<>(gloK);
            for (int i = 1; i <= 24; i++) if (!gk.containsKey(i)) gk.put(i, GLO_K[i]);
            List<Map.Entry<Integer, Integer>> l = new ArrayList<>(gk.entrySet());
            for (int i = 0; i < l.size(); i += 8) {
                StringBuilder s = new StringBuilder(i == 0 ? String.format(Locale.US, "%3d ", l.size()) : "    ");
                for (int j = i; j < Math.min(l.size(), i + 8); j++) s.append(String.format(Locale.US, "R%02d %2d ", l.get(j).getKey(), l.get(j).getValue()));
                w.write(lin(s.toString(), "GLONASS SLOT / FRQ #"));
            }
            w.write(lin(" C1C    0.000 C1P    0.000 C2C    0.000 C2P    0.000", "GLONASS COD/PHS/BIS"));
        }
        w.write(lin("", "END OF HEADER"));
    }

    // ---------- tempo ----------
    static int[] gpsData(long msGps) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear(); c.set(1980, 0, 6, 0, 0, 0);
        long base = c.getTimeInMillis();
        c.setTimeInMillis(base + (msGps / 1000) * 1000);
        return new int[]{c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)};
    }

    // ---------- efeméride GPS a partir das subframes ----------
    static long bits(byte[] b, int base, int pos, int len) {
        long v = 0;
        for (int i = pos; i < pos + len; i++) v = (v << 1) | ((b[base + i / 8] >> (7 - i % 8)) & 1);
        return v;
    }
    static long sbits(byte[] b, int base, int pos, int len) { long v = bits(b, base, pos, len); if ((v & (1L << (len - 1))) != 0) v -= (1L << len); return v; }
    // posição do bit (1..24) dentro da palavra (1..10) de 24 bits
    static int p(int palavra, int bit) { return (palavra - 1) * 24 + bit - 1; }
    static long jun(byte[] b, int base, int w1, int b1, int n1, int w2, int b2, int n2, boolean sinal) {
        long v = (bits(b, base, p(w1, b1), n1) << n2) | bits(b, base, p(w2, b2), n2);
        int n = n1 + n2; if (sinal && (v & (1L << (n - 1))) != 0) v -= (1L << n); return v;
    }

    static final double[] URA = {2.4, 3.4, 4.85, 6.85, 9.65, 13.65, 24, 48, 96, 192, 384, 768, 1536, 3072, 6144, 6144};

    static Ephem decodificarGPS(byte[] b, int o, int semanaRef) {
        Ephem e = new Ephem();
        e.prn = (int) u32(b, o);
        if (e.prn < 1 || e.prn > 32) return null;
        int s1 = o + 12, s2 = o + 42, s3 = o + 72;
        if (bits(b, s1, p(2, 20), 3) != 1 || bits(b, s2, p(2, 20), 3) != 2 || bits(b, s3, p(2, 20), 3) != 3) return null;
        double P = Math.PI;
        e.tt = bits(b, s1, p(2, 1), 17) * 6.0 - 6.0;
        int wn = (int) bits(b, s1, p(3, 1), 10);
        e.l2c = (int) bits(b, s1, p(3, 11), 2);
        e.ura = (int) bits(b, s1, p(3, 13), 4);
        e.health = (int) bits(b, s1, p(3, 17), 6);
        e.iodc = (int) jun(b, s1, 3, 23, 2, 8, 1, 8, false);
        e.tgd = sbits(b, s1, p(7, 17), 8) * Math.pow(2, -31);
        e.toc = bits(b, s1, p(8, 9), 16) * 16.0;
        e.af2 = sbits(b, s1, p(9, 1), 8) * Math.pow(2, -55);
        e.af1 = sbits(b, s1, p(9, 9), 16) * Math.pow(2, -43);
        e.af0 = sbits(b, s1, p(10, 1), 22) * Math.pow(2, -31);
        e.iode = (int) bits(b, s2, p(3, 1), 8);
        e.crs = sbits(b, s2, p(3, 9), 16) * Math.pow(2, -5);
        e.dn = sbits(b, s2, p(4, 1), 16) * Math.pow(2, -43) * P;
        e.m0 = jun(b, s2, 4, 17, 8, 5, 1, 24, true) * Math.pow(2, -31) * P;
        e.cuc = sbits(b, s2, p(6, 1), 16) * Math.pow(2, -29);
        e.e = jun(b, s2, 6, 17, 8, 7, 1, 24, false) * Math.pow(2, -33);
        e.cus = sbits(b, s2, p(8, 1), 16) * Math.pow(2, -29);
        e.sqrtA = jun(b, s2, 8, 17, 8, 9, 1, 24, false) * Math.pow(2, -19);
        e.toe = bits(b, s2, p(10, 1), 16) * 16.0;
        e.fit = (int) bits(b, s2, p(10, 17), 1);
        e.cic = sbits(b, s3, p(3, 1), 16) * Math.pow(2, -29);
        e.om0 = jun(b, s3, 3, 17, 8, 4, 1, 24, true) * Math.pow(2, -31) * P;
        e.cis = sbits(b, s3, p(5, 1), 16) * Math.pow(2, -29);
        e.i0 = jun(b, s3, 5, 17, 8, 6, 1, 24, true) * Math.pow(2, -31) * P;
        e.crc = sbits(b, s3, p(7, 1), 16) * Math.pow(2, -5);
        e.w = jun(b, s3, 7, 17, 8, 8, 1, 24, true) * Math.pow(2, -31) * P;
        e.omd = sbits(b, s3, p(9, 1), 24) * Math.pow(2, -43) * P;
        e.idot = sbits(b, s3, p(10, 9), 14) * Math.pow(2, -43) * P;
        if (bits(b, s3, p(10, 1), 8) != e.iode || e.sqrtA < 5000) return null;
        // semana completa (WN de 10 bits)
        e.week = semanaRef - ((semanaRef - wn) % 1024 + 1024) % 1024;
        if (semanaRef - e.week > 512) e.week += 1024;
        return e;
    }

    static String d19(double v) {
        if (v == 0) return " 0.000000000000E+00";
        String s = String.format(Locale.US, "%.12E", Math.abs(v));
        int i = s.indexOf('E'); String man = s.substring(0, i); int ex = Integer.parseInt(s.substring(i + 1));
        return (v < 0 ? "-" : " ") + man + "E" + (ex < 0 ? "-" : "+") + String.format(Locale.US, "%02d", Math.abs(ex));
    }

    static void escreverNav(BufferedWriter w, Iterable<Ephem> efs) throws IOException {
        w.write(lin(String.format(Locale.US, "%9s%11s%-20s%-20s", "3.04", "", "N: GNSS NAV DATA", "G: GPS"), "RINEX VERSION / TYPE"));
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        w.write(lin(pad("WA Survey", 20) + pad("WA Survey", 20) + String.format(Locale.US, "%04d%02d%02d %02d%02d%02d UTC", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)), "PGM / RUN BY / DATE"));
        w.write(lin("", "END OF HEADER"));
        TreeMap<String, Ephem> ord = new TreeMap<>();
        for (Ephem e : efs) ord.put(String.format(Locale.US, "%02d%012.0f", e.prn, e.week * 604800.0 + e.toc), e);
        for (Ephem e : ord.values()) {
            int[] d = gpsData((long) ((e.week * 604800.0 + e.toc) * 1000));
            w.write(String.format(Locale.US, "G%02d %04d %02d %02d %02d %02d %02d", e.prn, d[0], d[1], d[2], d[3], d[4], d[5]) + d19(e.af0) + d19(e.af1) + d19(e.af2) + "\n");
            w.write("    " + d19(e.iode) + d19(e.crs) + d19(e.dn) + d19(e.m0) + "\n");
            w.write("    " + d19(e.cuc) + d19(e.e) + d19(e.cus) + d19(e.sqrtA) + "\n");
            w.write("    " + d19(e.toe) + d19(e.cic) + d19(e.om0) + d19(e.cis) + "\n");
            w.write("    " + d19(e.i0) + d19(e.crc) + d19(e.w) + d19(e.omd) + "\n");
            w.write("    " + d19(e.idot) + d19(e.l2c) + d19(e.week) + d19(0) + "\n");
            w.write("    " + d19(URA[e.ura & 15]) + d19(e.health) + d19(e.tgd) + d19(e.iodc) + "\n");
            double tt = e.tt; if (tt < 0) tt += 604800;
            w.write("    " + d19(tt) + d19(e.fit == 0 ? 4 : 6) + "\n");
        }
    }

    // ---------- geodésia ----------
    static final double A = 6378137.0, F = 1 / 298.257222101, E2 = F * (2 - F);
    static double[] llh2xyz(double lat, double lon, double h) {
        double la = Math.toRadians(lat), lo = Math.toRadians(lon), N = A / Math.sqrt(1 - E2 * Math.sin(la) * Math.sin(la));
        return new double[]{(N + h) * Math.cos(la) * Math.cos(lo), (N + h) * Math.cos(la) * Math.sin(lo), (N * (1 - E2) + h) * Math.sin(la)};
    }
    static double[] xyz2llh(double[] x) {
        double p = Math.hypot(x[0], x[1]); if (p < 1) return new double[]{0, 0, 0};
        double lat = Math.atan2(x[2], p * (1 - E2)), h = 0;
        for (int i = 0; i < 10; i++) { double N = A / Math.sqrt(1 - E2 * Math.sin(lat) * Math.sin(lat)); h = p / Math.cos(lat) - N; lat = Math.atan2(x[2], p * (1 - E2 * N / (N + h))); }
        return new double[]{Math.toDegrees(lat), Math.toDegrees(Math.atan2(x[1], x[0])), h};
    }

    // posição do satélite GPS (efeméride transmitida); retorna x,y,z,dt
    static double[] satPos(Ephem e, double t) {
        final double GM = 3.986005e14, OMGE = 7.2921151467e-5;
        double tk = t - e.toe; if (tk > 302400) tk -= 604800; if (tk < -302400) tk += 604800;
        double a = e.sqrtA * e.sqrtA, n = Math.sqrt(GM / (a * a * a)) + e.dn, M = e.m0 + n * tk, E = M;
        for (int i = 0; i < 20; i++) E = M + e.e * Math.sin(E);
        double v = Math.atan2(Math.sqrt(1 - e.e * e.e) * Math.sin(E), Math.cos(E) - e.e), u = v + e.w;
        double du = e.cus * Math.sin(2 * u) + e.cuc * Math.cos(2 * u), dr = e.crs * Math.sin(2 * u) + e.crc * Math.cos(2 * u), di = e.cis * Math.sin(2 * u) + e.cic * Math.cos(2 * u);
        u += du; double r = a * (1 - e.e * Math.cos(E)) + dr, i = e.i0 + e.idot * tk + di;
        double xp = r * Math.cos(u), yp = r * Math.sin(u), Om = e.om0 + (e.omd - OMGE) * tk - OMGE * e.toe;
        double tc = t - e.toc; if (tc > 302400) tc -= 604800; if (tc < -302400) tc += 604800;
        double dt = e.af0 + e.af1 * tc + e.af2 * tc * tc - 2 * Math.sqrt(GM) * e.e * e.sqrtA * Math.sin(E) / (C * C) - e.tgd;
        return new double[]{xp * Math.cos(Om) - yp * Math.cos(i) * Math.sin(Om), xp * Math.sin(Om) + yp * Math.cos(i) * Math.cos(Om), yp * Math.sin(i), dt};
    }

    /** Posição simples (pseudodistância L1 GPS, sem correção atmosférica) na primeira época com 5+ satélites. */
    static double[] sppPrimeiro(byte[] b, Map<String, Ephem> efs) {
        final double[][] res = {null};
        final Map<Integer, Ephem> porPrn = new HashMap<>();
        for (Ephem e : efs.values()) porPrn.put(e.prn, e);
        if (porPrn.size() < 5) return new double[]{0, 0, 0};
        final int[] tentativas = {0};
        try {
            varrer(b, (id, sem, ms, buf, o, len) -> {
                if (res[0] != null || id != 140 || tentativas[0] > 50) return;
                tentativas[0]++;
                List<double[]> sv = new ArrayList<>();
                double t = ms / 1000.0;
                int n = (int) u32(buf, o);
                for (int k = 0; k < n; k++) {
                    int r = o + 4 + 24 * k; long st = u32(buf, r);
                    if (((st >> 16) & 7) != 0 || ((st >> 21) & 31) != 0) continue;
                    Ephem e = porPrn.get(buf[r + 17] & 0xff); if (e == null) continue;
                    long v = u32(buf, r + 4) | u32(buf, r + 8) << 32; double psr = (v >>> 28) / 128.0;
                    double[] s = satPos(e, t - psr / C);
                    // rotação da Terra durante o percurso do sinal
                    double th = 7.2921151467e-5 * psr / C, x = s[0] * Math.cos(th) + s[1] * Math.sin(th), y = -s[0] * Math.sin(th) + s[1] * Math.cos(th);
                    sv.add(new double[]{x, y, s[2], psr + C * s[3]});
                }
                if (sv.size() < 5) return;
                double[] x = {0, 0, 0, 0};
                for (int it = 0; it < 10; it++) {
                    double[][] H = new double[sv.size()][4]; double[] dy = new double[sv.size()];
                    for (int j = 0; j < sv.size(); j++) {
                        double[] s = sv.get(j); double rr = Math.sqrt(sq(s[0] - x[0]) + sq(s[1] - x[1]) + sq(s[2] - x[2]));
                        H[j][0] = (x[0] - s[0]) / rr; H[j][1] = (x[1] - s[1]) / rr; H[j][2] = (x[2] - s[2]) / rr; H[j][3] = 1; dy[j] = s[3] - rr - x[3];
                    }
                    double[] dx = mmq(H, dy); if (dx == null) return;
                    for (int q = 0; q < 4; q++) x[q] += dx[q];
                    if (Math.abs(dx[0]) + Math.abs(dx[1]) + Math.abs(dx[2]) < 1e-3) break;
                }
                double rr = Math.sqrt(sq(x[0]) + sq(x[1]) + sq(x[2]));
                if (rr > 6.3e6 && rr < 6.4e6) res[0] = new double[]{x[0], x[1], x[2]};
            });
        } catch (Exception ignored) { }
        return res[0] != null ? res[0] : new double[]{0, 0, 0};
    }
    static double sq(double v) { return v * v; }
    static double[] mmq(double[][] H, double[] y) {
        int n = 4; double[][] N = new double[n][n + 1];
        for (int i = 0; i < H.length; i++) for (int a = 0; a < n; a++) { for (int b = 0; b < n; b++) N[a][b] += H[i][a] * H[i][b]; N[a][n] += H[i][a] * y[i]; }
        for (int c = 0; c < n; c++) {
            int pv = c; for (int r = c + 1; r < n; r++) if (Math.abs(N[r][c]) > Math.abs(N[pv][c])) pv = r;
            double[] tmp = N[c]; N[c] = N[pv]; N[pv] = tmp; if (Math.abs(N[c][c]) < 1e-12) return null;
            for (int r = 0; r < n; r++) if (r != c) { double f = N[r][c] / N[c][c]; for (int k = c; k <= n; k++) N[r][k] -= f * N[c][k]; }
        }
        double[] x = new double[n]; for (int i = 0; i < n; i++) x[i] = N[i][n] / N[i][i]; return x;
    }

    /** Teste fora do celular: java CnbRinex arquivo.cnb pastaSaida nome [intervalo] */
    public static void main(String[] a) throws Exception {
        long t0 = System.currentTimeMillis();
        Resultado r = converter(new File(a[0]), new File(a[1]), a[2], "", Double.NaN, a.length > 3 ? Double.parseDouble(a[3]) : 1.0, null, "", "");
        System.out.println("obs=" + r.obs + " nav=" + r.nav + " epocas=" + r.epocas + " sats=" + r.sats + " " + r.inicio + " -> " + r.fim + " sis=" + r.sistemas
                + String.format(Locale.US, " pos=%.7f %.7f %.2f", r.lat, r.lon, r.h) + " em " + (System.currentTimeMillis() - t0) + " ms");
    }
}
