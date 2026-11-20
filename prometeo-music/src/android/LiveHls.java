package com.prometeo.music;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Cortador" de radio para el Chromecast.
 * Algunas emisoras mandan muy poco sonido por segundo; si el televisor las recibe como un chorro continuo,
 * tarda casi un minuto en arrancar y luego se corta. Aquí el teléfono recibe la emisora y la entrega en
 * pedacitos de 2 segundos con una lista (HLS), que es lo que el televisor maneja mejor:
 * arranca enseguida y, si la emisora se corta un momento, el teléfono se reconecta solo.
 * Solo en la memoria del teléfono (los últimos segundos); no se guarda nada.
 */
final class LiveHls {
    static final double SEG = 2.0;          /* segundos por pedacito */
    private static final int KEEP = 15;      /* pedacitos que se guardan (~30 s) */
    static int START_SEGS = 4;               /* colchón al empezar (4 × 2 s) */

    final String id;
    final String upstream;
    final boolean aac;                       /* true: AAC (ADTS); false: MP3 */
    private volatile boolean alive = true;
    private volatile long lastAsk = System.currentTimeMillis();
    private volatile boolean dead = false;

    private static final class Seg { final long seq; final double dur; final byte[] data; Seg(long s, double d, byte[] b) { seq = s; dur = d; data = b; } }
    private final List<Seg> segs = new ArrayList<>();     /* los últimos pedacitos, con su sonido */
    private final List<double[]> all = new ArrayList<>(); /* todos los pedacitos (número, duración) para la lista */
    private long nextSeq = 0;
    private double clock = 0;                /* segundos de audio desde el principio (para la marca de tiempo) */

    LiveHls(String id, String upstream, boolean aac) {
        this.id = id; this.upstream = upstream; this.aac = aac;
        Thread t = new Thread(new Runnable() { public void run() { reader(); } }, "prometeo-live-hls");
        t.setDaemon(true);
        t.start();
    }

    void stop() { alive = false; }
    boolean isAlive() { return alive && !dead; }
    String ext() { return aac ? "aac" : "mp3"; }
    String mime() { return aac ? "audio/aac" : "audio/mpeg"; }

    /* ---------------- lectura de la emisora ---------------- */
    private void reader() {
        int fails = 0;
        byte[] buf = new byte[256 * 1024];
        int len = 0;
        while (alive) {
            if (System.currentTimeMillis() - lastAsk > 45000) break;   /* el televisor ya no pide: se apaga */
            Net.Resp c = null;
            boolean gotData = false;
            try {
                c = CastServer.open(upstream, null);
                if (c.getResponseCode() >= 400) throw new Exception("HTTP " + c.getResponseCode());
                InputStream in = c.getInputStream();
                byte[] tmp = new byte[16384];
                java.io.ByteArrayOutputStream cur = new java.io.ByteArrayOutputStream();
                double curDur = 0;
                int n;
                len = 0;
                while (alive && (n = in.read(tmp)) > 0) {
                    gotData = true; fails = 0;
                    if (len + n > buf.length) len = 0;                 /* basura sin cuadros: se descarta */
                    System.arraycopy(tmp, 0, buf, len, n); len += n;
                    int pos = 0;
                    while (true) {
                        int s = findFrame(buf, pos, len);
                        if (s < 0) { pos = Math.max(pos, len - 8); break; }
                        int[] fr = frame(buf, s, len);
                        if (fr == null) { pos = s; break; }            /* falta llegar el resto del cuadro */
                        if (fr[0] <= 0) { pos = s + 1; continue; }     /* falso cuadro: seguir buscando */
                        double d = fr[1] / (double) fr[2];
                        if (curDur + d > SEG && cur.size() > 0) {
                            addSeg(cur.toByteArray(), curDur);
                            cur.reset(); curDur = 0;
                        }
                        cur.write(buf, s, fr[0]);
                        curDur += d;
                        pos = s + fr[0];
                    }
                    if (pos > 0) { System.arraycopy(buf, pos, buf, 0, len - pos); len -= pos; }
                    if (System.currentTimeMillis() - lastAsk > 45000) break;
                }
            } catch (Exception e) {
                /* se cayó la emisora: se reintenta */
            } finally { if (c != null) try { c.disconnect(); } catch (Exception ignored) { } }
            if (!alive) break;
            if (!gotData) fails++;
            if (fails >= 4) { dead = true; break; }
            try { Thread.sleep(gotData ? 100 : 1200); } catch (InterruptedException ie) { break; }   /* se cayó: reconectar enseguida */
        }
        alive = false;
    }

    private synchronized void addSeg(byte[] audio, double dur) {
        byte[] id3 = id3Timestamp(clock);
        byte[] all = new byte[id3.length + audio.length];
        System.arraycopy(id3, 0, all, 0, id3.length);
        System.arraycopy(audio, 0, all, id3.length, audio.length);
        segs.add(new Seg(nextSeq, dur, all));
        this.all.add(new double[]{ nextSeq, dur });
        nextSeq++;
        while (this.all.size() > 5400) this.all.remove(0);
        clock += dur;
        while (segs.size() > KEEP) segs.remove(0);
        notifyAll();
    }

    /* Marca de tiempo que piden las listas HLS de audio (ID3 PRIV com.apple.streaming.transportStreamTimestamp) */
    static byte[] id3Timestamp(double seconds) {
        long pts = ((long) Math.floor(seconds * 90000)) & 0x1FFFFFFFFL;
        byte[] owner = "com.apple.streaming.transportStreamTimestamp".getBytes(StandardCharsets.ISO_8859_1);
        int frameData = owner.length + 1 + 8;
        int frameSize = 10 + frameData;
        byte[] b = new byte[10 + frameSize];
        b[0] = 'I'; b[1] = 'D'; b[2] = '3'; b[3] = 4; b[4] = 0; b[5] = 0;
        syncsafe(b, 6, frameSize);
        int p = 10;
        b[p++] = 'P'; b[p++] = 'R'; b[p++] = 'I'; b[p++] = 'V';
        syncsafe(b, p, frameData); p += 4;
        b[p++] = 0; b[p++] = 0;
        System.arraycopy(owner, 0, b, p, owner.length); p += owner.length;
        b[p++] = 0;
        for (int i = 7; i >= 0; i--) b[p++] = (byte) (pts >>> (i * 8));
        return b;
    }

    private static void syncsafe(byte[] b, int o, int v) {
        b[o] = (byte) ((v >> 21) & 0x7F); b[o + 1] = (byte) ((v >> 14) & 0x7F); b[o + 2] = (byte) ((v >> 7) & 0x7F); b[o + 3] = (byte) (v & 0x7F);
    }

    /* ---------------- cuadros de MP3 / AAC ---------------- */
    private int findFrame(byte[] b, int from, int len) {
        for (int i = from; i + 1 < len; i++) {
            if ((b[i] & 0xFF) != 0xFF) continue;
            int b1 = b[i + 1] & 0xFF;
            if (aac ? (b1 & 0xF6) == 0xF0 : (b1 & 0xE0) == 0xE0) return i;
        }
        return -1;
    }

    private static final int[][] BR = {
            { 0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448 },   /* MPEG1 L1 */
            { 0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384 },      /* MPEG1 L2 */
            { 0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320 },       /* MPEG1 L3 */
            { 0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256 },      /* MPEG2 L1 */
            { 0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160 } };          /* MPEG2 L2/L3 */
    private static final int[] AAC_SR = { 96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350 };

    /** {largo en bytes, muestras, frecuencia}; null = faltan bytes; largo 0 = no es un cuadro válido */
    private int[] frame(byte[] b, int s, int len) {
        int[] h = header(b, s, len);
        if (h == null || h[0] <= 0) return h;
        /* comprobar que el siguiente también empieza bien (para no confundir basura con un cuadro) */
        int nx = s + h[0];
        if (nx + 7 > len) return len - s > 200000 ? h : null;
        int[] h2 = header(b, nx, len);
        if (h2 == null) return null;
        if (h2[0] <= 0 || h2[2] != h[2]) return new int[]{ 0, 0, 0 };
        return h;
    }

    private int[] header(byte[] b, int s, int len) {
        if (s + 7 > len) return null;
        int b0 = b[s] & 0xFF, b1 = b[s + 1] & 0xFF, b2 = b[s + 2] & 0xFF;
        if (b0 != 0xFF) return new int[]{ 0, 0, 0 };
        if (aac) {
            if ((b1 & 0xF6) != 0xF0) return new int[]{ 0, 0, 0 };
            int sfi = (b2 >> 2) & 0xF;
            if (sfi >= AAC_SR.length) return new int[]{ 0, 0, 0 };
            int fl = ((b[s + 3] & 0x03) << 11) | ((b[s + 4] & 0xFF) << 3) | ((b[s + 5] & 0xE0) >> 5);
            int blocks = (b[s + 6] & 0x03) + 1;
            if (fl < 7) return new int[]{ 0, 0, 0 };
            return new int[]{ fl, 1024 * blocks, AAC_SR[sfi] };
        }
        if ((b1 & 0xE0) != 0xE0) return new int[]{ 0, 0, 0 };
        int ver = (b1 >> 3) & 3, layer = (b1 >> 1) & 3;
        if (ver == 1 || layer == 0) return new int[]{ 0, 0, 0 };
        int bri = (b2 >> 4) & 0xF, sri = (b2 >> 2) & 3, pad = (b2 >> 1) & 1;
        if (bri == 0 || bri == 15 || sri == 3) return new int[]{ 0, 0, 0 };
        boolean m1 = ver == 3;
        int[] srs = m1 ? new int[]{ 44100, 48000, 32000 } : ver == 2 ? new int[]{ 22050, 24000, 16000 } : new int[]{ 11025, 12000, 8000 };
        int sr = srs[sri];
        int L = 4 - layer;   /* 1, 2 o 3 */
        int kbps = m1 ? BR[L - 1][bri] : (L == 1 ? BR[3][bri] : BR[4][bri]);
        int samples, fl;
        if (L == 1) { samples = 384; fl = (12 * kbps * 1000 / sr + pad) * 4; }
        else if (L == 2 || m1) { samples = 1152; fl = 144 * kbps * 1000 / sr + pad; }
        else { samples = 576; fl = 72 * kbps * 1000 / sr + pad; }
        if (fl < 21) return new int[]{ 0, 0, 0 };
        return new int[]{ fl, samples, sr };
    }

    /* ---------------- lo que pide el televisor ---------------- */
    /** Lista de pedacitos. La primera vez espera a tener un colchón de unos 8 s, así el televisor no se traba. */
    synchronized String playlist(String segBase) throws Exception {
        lastAsk = System.currentTimeMillis();
        long until = System.currentTimeMillis() + 20000;
        while (segs.size() < START_SEGS && isAlive() && System.currentTimeMillis() < until) wait(500);
        if (segs.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:").append((int) Math.ceil(SEG)).append('\n');
        sb.append("#EXT-X-START:TIME-OFFSET=-").append(String.format(Locale.ROOT, "%.1f", START_SEGS * SEG - 0.5)).append(",PRECISE=YES\n");
        /* EVENT: la lista solo crece (el televisor no se ve obligado a saltar al borde en vivo y no se traba) */
        sb.append("#EXT-X-PLAYLIST-TYPE:EVENT\n");
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append((long) all.get(0)[0]).append('\n');
        for (double[] g : all) {
            sb.append("#EXTINF:").append(String.format(Locale.ROOT, "%.3f", g[1])).append(",\n");
            sb.append(segBase).append((long) g[0]).append('.').append(ext()).append('\n');
        }
        return sb.toString();
    }

    synchronized byte[] segment(long seq) throws Exception {
        lastAsk = System.currentTimeMillis();
        long until = System.currentTimeMillis() + 6000;
        while (true) {
            for (Seg g : segs) if (g.seq == seq) return g.data;
            if (seq < nextSeq || !isAlive() || System.currentTimeMillis() > until) return null;
            wait(300);
        }
    }
}
