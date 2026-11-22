package com.prometeo.music;

import android.content.ContentUris;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.provider.MediaStore;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mini servidor SOLO para tu Wi-Fi: le pasa al Chromecast la canción que está sonando.
 * - Sirve canciones del teléfono (por su número en MediaStore).
 * - "Puente": cuando el televisor no puede abrir una emisora o un canal por su cuenta (links http://,
 *   servidores que no dan permiso al televisor), el teléfono la recibe y se la pasa al televisor.
 *   Solo para lo que el usuario mandó a transmitir; nada se guarda.
 * - Cada vez que se abre usa una clave al azar en la dirección, así otro aparato de la red no puede pedir archivos.
 * - Se apaga al desconectar el Chromecast.
 */
final class CastServer {
    private final Context ctx;
    private ServerSocket ss;
    private ExecutorService pool;
    private String token;
    private volatile boolean running;

    CastServer(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    synchronized void start() throws Exception {
        if (running) return;
        byte[] r = new byte[12]; new SecureRandom().nextBytes(r);
        StringBuilder sb = new StringBuilder(); for (byte b : r) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        token = sb.toString();
        ss = new ServerSocket(0);
        pool = Executors.newCachedThreadPool();
        running = true;
        Thread t = new Thread(new Runnable() { public void run() { loop(); } }, "prometeo-cast-server");
        t.setDaemon(true);
        t.start();
    }

    synchronized void stop() {
        running = false;
        stopLive();
        try { if (ss != null) ss.close(); } catch (Exception ignored) { }
        if (pool != null) pool.shutdownNow();
        ss = null; pool = null;
    }

    boolean isRunning() { return running; }

    /** Dirección de la canción para el Chromecast, o null si el teléfono no está en un Wi-Fi */
    String urlFor(long mediaId, String ext) {
        String ip = localIp();
        if (ip == null || ss == null) return null;
        return "http://" + ip + ":" + ss.getLocalPort() + "/" + token + "/a/" + mediaId + "." + (ext == null || ext.isEmpty() ? "mp3" : ext);
    }

    /** v43: dirección de un video del teléfono para el Chromecast */
    String urlForVideo(long mediaId, String ext) {
        String ip = localIp();
        if (ip == null || ss == null) return null;
        return "http://" + ip + ":" + ss.getLocalPort() + "/" + token + "/v/" + mediaId + "." + (ext == null || ext.isEmpty() ? "mp4" : ext);
    }
    /** v43b: video de memoria USB o elegido a mano (solo el que se está mandando al TV) */
    private volatile android.net.Uri docUri = null;
    private int docN = 0;
    synchronized String urlForDoc(android.net.Uri u, String ext) {
        String ip = localIp();
        if (ip == null || ss == null || u == null) return null;
        docUri = u;
        return "http://" + ip + ":" + ss.getLocalPort() + "/" + token + "/d/" + (++docN) + "." + (ext == null || ext.isEmpty() ? "mp4" : ext);
    }
    /** v43: subtítulos del video (WebVTT) para el Chromecast */
    private volatile byte[] subVtt = null;
    private int subN = 0;
    synchronized String setSubtitle(String vtt) {
        String ip = localIp();
        if (vtt == null || vtt.isEmpty() || ip == null || ss == null) { subVtt = null; return null; }
        subVtt = vtt.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return "http://" + ip + ":" + ss.getLocalPort() + "/" + token + "/s/" + (++subN) + ".vtt";
    }

    static final String UA = "PrometeoPlayer/43";

    /* ---------------- radio en pedacitos (HLS) ---------------- */
    private LiveHls live;

    synchronized void stopLive() { if (live != null) { live.stop(); live = null; } }

    /** Dirección de la lista de pedacitos de una emisora (MP3 o AAC); empieza a recibirla ya */
    synchronized String hlsFor(String upstream, boolean aac) {
        String ip = localIp();
        if (ip == null || ss == null) return null;
        stopLive();
        byte[] r = new byte[6]; new SecureRandom().nextBytes(r);
        StringBuilder sb = new StringBuilder(); for (byte b : r) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        live = new LiveHls(sb.toString(), upstream, aac);
        return base(ip) + "/h/" + live.id + "/live.m3u8";
    }

    private void serveLive(String rest, boolean head, OutputStream out) throws Exception {
        LiveHls L;
        synchronized (this) { L = live; }
        int sl = rest.indexOf('/');
        if (L == null || sl < 0 || !rest.substring(0, sl).equals(L.id)) { send(out, "404 Not Found", null, 0, null, null); return; }
        String file = rest.substring(sl + 1);
        if (file.startsWith("live.m3u8")) {
            String ip = localIp();
            String pl = L.playlist(base(ip == null ? "127.0.0.1" : ip) + "/h/" + L.id + "/");
            if (pl == null) { send(out, "503 Service Unavailable", null, 0, null, null); return; }
            byte[] body = pl.getBytes(StandardCharsets.UTF_8);
            sendNoCache(out, "application/vnd.apple.mpegurl", body.length);
            if (!head) out.write(body);
            out.flush();
            return;
        }
        int dot = file.indexOf('.');
        long seq;
        try { seq = Long.parseLong(dot > 0 ? file.substring(0, dot) : file); } catch (Exception e) { send(out, "404 Not Found", null, 0, null, null); return; }
        byte[] data = L.segment(seq);
        if (data == null) { send(out, "404 Not Found", null, 0, null, null); return; }
        sendNoCache(out, L.mime(), data.length);
        if (!head) out.write(data);
        out.flush();
    }

    private static void sendNoCache(OutputStream out, String mime, long len) throws Exception {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: ").append(mime).append("\r\n");
        h.append("Content-Length: ").append(len).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Headers: Range, Content-Type\r\n");
        h.append("Cache-Control: no-cache\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("ISO-8859-1"));
    }

    /** Dirección "puente" para una emisora o canal de internet */
    String relayFor(String upstream, String ext) {
        String ip = localIp();
        if (ip == null || ss == null) return null;
        return base(ip) + "/p/" + b64(upstream) + "/s." + (ext == null || ext.isEmpty() ? "bin" : ext);
    }

    private String base(String ip) { return "http://" + ip + ":" + ss.getLocalPort() + "/" + token; }

    private static String b64(String s) {
        return android.util.Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }
    private static String unb64(String s) {
        return new String(android.util.Base64.decode(s, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING), StandardCharsets.UTF_8);
    }

    /** Extensión para que el televisor reconozca el tipo por el nombre */
    static String extOf(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        int q = u.indexOf('?'); if (q >= 0) u = u.substring(0, q);
        int h = u.indexOf('#'); if (h >= 0) u = u.substring(0, h);
        int sl = u.lastIndexOf('/'); String f = sl >= 0 ? u.substring(sl + 1) : u;
        int d = f.lastIndexOf('.');
        if (d < 0 || d == f.length() - 1) return "bin";
        String e = f.substring(d + 1).replaceAll("[^a-z0-9]", "");
        return e.isEmpty() || e.length() > 5 ? "bin" : e;
    }

    /** Abre una dirección de internet con el mismo "cartero" que el reproductor del teléfono (ver Net) */
    static Net.Resp open(String address, String range) throws Exception {
        return Net.open(address, range);
    }

    /** Qué es una dirección: lista HLS, lista .pls/.m3u de radio, o audio/video directo.
        Devuelve {url (final o la de dentro de la lista), hls, mime} */
    static String[] probe(String address) throws Exception {
        String cur = address;
        for (int depth = 0; depth < 3; depth++) {
            Net.Resp c = open(cur, null);
            try {
                int code = c.getResponseCode();
                if (code >= 400) throw new Exception("HTTP " + code);
                String fin = c.finalUrl();
                String ct = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
                String ext = extOf(fin);
                boolean maybeList = ct.contains("mpegurl") || ct.contains("scpls") || ext.equals("m3u8") || ext.equals("m3u") || ext.equals("pls")
                        || ct.startsWith("text/") || ct.contains("octet-stream") || ct.isEmpty();
                if (!maybeList) return new String[]{ fin, "0", mimeOf(ct, ext) };
                /* Mirar el principio para saber si es una lista */
                InputStream in = c.getInputStream();
                byte[] head = readSome(in, 2048);
                String txt = new String(head, StandardCharsets.ISO_8859_1).trim();
                if (txt.startsWith("#EXTM3U") && txt.contains("#EXT-X-")) {
                    /* Lista HLS: averiguar de qué son los pedacitos (el televisor necesita saberlo si son AAC o fMP4) */
                    ByteArrayOutputStream all = new ByteArrayOutputStream(); all.write(head);
                    byte[] b = new byte[16384]; int n;
                    while ((n = in.read(b)) > 0) { all.write(b, 0, n); if (all.size() > 512 * 1024) break; }
                    String seg = "";
                    try { seg = hlsSegmentKind(new String(all.toByteArray(), StandardCharsets.UTF_8), fin, 0); } catch (Exception ignored) { }
                    return new String[]{ fin, "1", "application/x-mpegURL", seg };
                }
                if (txt.startsWith("#EXTM3U") || txt.startsWith("[playlist]") || txt.startsWith("http")) {
                    String inner = firstUrl(new String(head, StandardCharsets.UTF_8), fin);
                    if (inner != null) { cur = inner; continue; }
                }
                return new String[]{ fin, "0", mimeOf(ct, ext) };
            } finally { c.disconnect(); }
        }
        return new String[]{ cur, "0", "audio/mpeg" };
    }

    /** Tipo de los pedacitos de una lista HLS: "aac", "mp3", "fmp4", "ts" o "" si no se sabe */
    static String hlsSegmentKind(String m3u, String base, int depth) throws Exception {
        boolean master = m3u.contains("#EXT-X-STREAM-INF");
        if (m3u.contains("#EXT-X-MAP")) return "fmp4";
        String[] lines = m3u.split("\\r?\\n");
        for (String line : lines) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String abs = abs(base, l);
            if (master) {
                if (depth > 1) return "";
                Net.Resp c = open(abs, null);
                try {
                    if (c.getResponseCode() >= 400) return "";
                    byte[] body = readSome(c.getInputStream(), 512 * 1024);
                    return hlsSegmentKind(new String(body, StandardCharsets.UTF_8), c.finalUrl(), depth + 1);
                } finally { c.disconnect(); }
            }
            String e = extOf(abs);
            if (e.equals("aac") || e.equals("adts")) return "aac";
            if (e.equals("mp3")) return "mp3";
            if (e.equals("mp4") || e.equals("m4s") || e.equals("m4a") || e.equals("m4v") || e.equals("cmfa") || e.equals("cmfv")) return "fmp4";
            if (e.equals("ts")) return "ts";
            return "";
        }
        return "";
    }

    static String mimeOf(String ct, String ext) {
        if (ct.contains("aac")) return "audio/aac";
        if (ct.contains("mpeg") && ct.startsWith("audio")) return "audio/mpeg";
        if (ct.contains("ogg") || ct.contains("opus")) return "audio/ogg";
        if (ct.startsWith("audio/mp4") || ct.contains("m4a")) return "audio/mp4";
        if (ct.startsWith("video/mp2t") || ext.equals("ts")) return "video/mp2t";
        if (ct.startsWith("video/")) return ct.split(";")[0].trim();
        if (ext.equals("aac") || ext.equals("aacp")) return "audio/aac";
        if (ext.equals("ogg") || ext.equals("opus")) return "audio/ogg";
        if (ext.equals("mp4") || ext.equals("m4v")) return "video/mp4";
        if (ext.equals("m4a")) return "audio/mp4";
        return "audio/mpeg";
    }

    private static String firstUrl(String txt, String baseUrl) {
        for (String line : txt.split("\\r?\\n")) {
            String l = line.trim();
            int eq = l.indexOf('=');
            if (l.toLowerCase(Locale.ROOT).startsWith("file") && eq > 0) l = l.substring(eq + 1).trim();
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("[")) continue;
            try { return new URL(new URL(baseUrl), l).toString(); } catch (Exception ignored) { }
        }
        return null;
    }

    private static byte[] readSome(InputStream in, int max) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[max];
        int n;
        while (o.size() < max && (n = in.read(b, 0, max - o.size())) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }

    static String localIp() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String n = ni.getName() == null ? "" : ni.getName();
                    if (n.startsWith("wlan") || n.startsWith("eth")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) { return null; }
    }

    private void loop() {
        while (running) {
            try {
                final Socket s = ss.accept();
                s.setSoTimeout(20000);
                pool.execute(new Runnable() { public void run() { handle(s); } });
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        return c == -1 && sb.length() == 0 ? null : sb.toString();
    }

    private void handle(Socket s) {
        AssetFileDescriptor afd = null;
        try {
            InputStream in = s.getInputStream();
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            String req = readLine(in);
            if (req == null) { s.close(); return; }
            String range = null, line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int k = line.indexOf(':');
                if (k > 0 && line.substring(0, k).trim().equalsIgnoreCase("range")) range = line.substring(k + 1).trim();
            }
            String[] p = req.split(" ");
            String method = p.length > 0 ? p[0] : "";
            String path = p.length > 1 ? p[1] : "";
            boolean head = "HEAD".equals(method);
            if ("OPTIONS".equals(method)) { send(out, "204 No Content", null, 0, null, null); s.close(); return; }
            if (("GET".equals(method) || head) && path.startsWith("/" + token + "/h/")) { serveLive(path.substring(token.length() + 4), head, out); s.close(); return; }
            if (("GET".equals(method) || head) && path.startsWith("/" + token + "/p/")) { relay(path.substring(token.length() + 4), range, head, out); s.close(); return; }
            if (("GET".equals(method) || head) && path.startsWith("/" + token + "/s/")) {
                byte[] v = subVtt;
                if (v == null) { send(out, "404 Not Found", null, 0, null, null); s.close(); return; }
                send(out, "200 OK", "text/vtt; charset=utf-8", v.length, null, null);
                if (!head) out.write(v);
                out.flush(); s.close(); return;
            }
            String pre = "/" + token + "/a/", preV = "/" + token + "/v/", preD = "/" + token + "/d/";
            boolean isDoc = path.startsWith(preD);
            boolean isVideo = path.startsWith(preV) || isDoc;
            if (!("GET".equals(method) || head) || !(path.startsWith(pre) || isVideo)) { send(out, "404 Not Found", null, 0, null, null); s.close(); return; }
            android.net.Uri uri;
            if (isDoc) {
                uri = docUri;
                if (uri == null || !Library.uriAllowed(ctx, uri)) { send(out, "404 Not Found", null, 0, null, null); s.close(); return; }
            } else {
                String idPart = path.substring(pre.length());
                int dot = idPart.indexOf('.'); if (dot >= 0) idPart = idPart.substring(0, dot);
                long id = Long.parseLong(idPart.replaceAll("[^0-9]", ""));
                uri = isVideo ? Videos.uriOf(id) : ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
            }
            String mime = ctx.getContentResolver().getType(uri);
            if (mime == null) mime = isVideo ? "video/mp4" : "audio/mpeg";
            afd = ctx.getContentResolver().openAssetFileDescriptor(uri, "r");
            if (afd == null) { send(out, "404 Not Found", null, 0, null, null); s.close(); return; }
            FileInputStream fis = afd.createInputStream();
            FileChannel ch = fis.getChannel();
            long base = afd.getStartOffset();
            long total = afd.getLength() >= 0 ? afd.getLength() : ch.size() - base;
            long start = 0, end = total - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                String r = range.substring(6).split(",")[0].trim();
                int dash = r.indexOf('-');
                try {
                    if (dash == 0) { long n = Long.parseLong(r.substring(1)); start = Math.max(0, total - n); }
                    else {
                        start = Long.parseLong(r.substring(0, dash));
                        if (dash < r.length() - 1) end = Math.min(total - 1, Long.parseLong(r.substring(dash + 1)));
                    }
                    partial = true;
                } catch (Exception ignored) { start = 0; end = total - 1; }
                if (start >= total) { send(out, "416 Range Not Satisfiable", mime, 0, "bytes */" + total, null); s.close(); return; }
            }
            long len = end - start + 1;
            send(out, partial ? "206 Partial Content" : "200 OK", mime, len, partial ? ("bytes " + start + "-" + end + "/" + total) : null, null);
            if (!head) {
                ch.position(base + start);
                byte[] buf = new byte[64 * 1024];
                long left = len;
                while (left > 0) {
                    int n = fis.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    left -= n;
                }
            }
            out.flush();
            fis.close();
        } catch (Exception ignored) {
        } finally {
            try { if (afd != null) afd.close(); } catch (Exception ignored) { }
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /* ---------------- puente: emisoras y canales ---------------- */
    private void relay(String rest, String range, boolean head, OutputStream out) {
        Net.Resp c = null;
        try {
            int sl = rest.indexOf('/');
            String upstream = unb64(sl >= 0 ? rest.substring(0, sl) : rest);
            if (!upstream.startsWith("http://") && !upstream.startsWith("https://")) { send(out, "400 Bad Request", null, 0, null, null); return; }
            /* "Range: bytes=0-" (lo que pide el televisor al empezar) NO se pasa a la emisora: muchas emisoras en vivo
               (Shoutcast, CDN) responden mal a eso y el televisor se queda en "Conectando…". El teléfono tampoco lo pide. */
            String fwd = (range == null || range.replace(" ", "").equalsIgnoreCase("bytes=0-")) ? null : range;
            c = open(upstream, fwd);
            int code = c.getResponseCode();
            if (code >= 400) { send(out, "502 Bad Gateway", null, 0, null, null); return; }
            String fin = c.finalUrl();
            String ct = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
            String ext = extOf(fin);
            InputStream in = c.getInputStream();
            boolean listLike = ct.contains("mpegurl") || ext.equals("m3u8") || ext.equals("m3u");
            if (listLike || ct.startsWith("text/") || (ct.contains("octet-stream") && !ext.equals("ts") && !ext.equals("aac") && !ext.equals("mp3") && !ext.equals("mp4") && !ext.equals("m4s"))) {
                /* Puede ser una lista HLS: se lee completa y se cambian sus direcciones para que también pasen por el puente */
                byte[] first = readSome(in, 7);
                if (new String(first, StandardCharsets.ISO_8859_1).startsWith("#EXTM3U")) {
                    ByteArrayOutputStream all = new ByteArrayOutputStream();
                    all.write(first);
                    byte[] b = new byte[16384]; int n;
                    while ((n = in.read(b)) > 0) { all.write(b, 0, n); if (all.size() > 4 * 1024 * 1024) break; }
                    byte[] body = rewrite(new String(all.toByteArray(), StandardCharsets.UTF_8), fin).getBytes(StandardCharsets.UTF_8);
                    send(out, "200 OK", "application/vnd.apple.mpegurl", body.length, null, null);
                    if (!head) out.write(body);
                    out.flush();
                    return;
                }
                streamOut(out, head, fwd == null ? 200 : code, c, mimeOf(ct, ext), in, first);
                return;
            }
            streamOut(out, head, fwd == null ? 200 : code, c, mimeOf(ct, ext), in, null);
        } catch (Exception e) {
            try { send(out, "502 Bad Gateway", null, 0, null, null); } catch (Exception ignored) { }
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) { }
        }
    }

    private void streamOut(OutputStream out, boolean head, int code, Net.Resp c, String mime, InputStream in, byte[] pre) throws Exception {
        long len = code == 206 ? c.getContentLengthLong() : (c.getHeaderField("Content-Range") == null ? c.getContentLengthLong() : -1);
        String cr = c.getHeaderField("Content-Range");
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code == 206 && cr != null ? "206 Partial Content" : "200 OK").append("\r\n");
        h.append("Content-Type: ").append(mime).append("\r\n");
        if (len >= 0) h.append("Content-Length: ").append(len).append("\r\n");
        else h.append("Accept-Ranges: none\r\n");   /* en vivo: no se puede saltar */
        if (code == 206 && cr != null) h.append("Content-Range: ").append(cr).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Headers: Range, Content-Type\r\n");
        h.append("Access-Control-Expose-Headers: Content-Length, Content-Range\r\n");
        h.append("Cache-Control: no-cache\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("ISO-8859-1"));
        if (head) { out.flush(); return; }
        if (pre != null) out.write(pre);
        byte[] buf = new byte[32 * 1024];
        int n;
        /* En vivo no tiene fin: se pasa mientras el televisor siga pidiendo (si se desconecta, write falla y se corta) */
        while (running && (n = in.read(buf)) > 0) { out.write(buf, 0, n); out.flush(); }
    }

    /** Cambia cada dirección de una lista HLS por su dirección "puente" */
    private String rewrite(String m3u, String baseUrl) {
        String ip = localIp();
        String b = base(ip == null ? "127.0.0.1" : ip);
        StringBuilder o = new StringBuilder();
        for (String line : m3u.split("\\r?\\n")) {
            String l = line.trim();
            if (l.isEmpty()) { o.append('\n'); continue; }
            if (l.startsWith("#")) {
                int k = l.indexOf("URI=\"");
                if (k >= 0) {
                    int e = l.indexOf('"', k + 5);
                    if (e > k + 5) {
                        String abs = abs(baseUrl, l.substring(k + 5, e));
                        l = l.substring(0, k + 5) + b + "/p/" + b64(abs) + "/s." + extOf(abs) + l.substring(e);
                    }
                }
                o.append(l).append('\n');
                continue;
            }
            String abs = abs(baseUrl, l);
            o.append(b).append("/p/").append(b64(abs)).append("/s.").append(extOf(abs)).append('\n');
        }
        return o.toString();
    }

    private static String abs(String base, String rel) {
        try { return new URL(new URL(base), rel).toString(); } catch (Exception e) { return rel; }
    }

    private static void send(OutputStream out, String status, String mime, long len, String contentRange, String extra) throws Exception {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append("\r\n");
        if (mime != null) h.append("Content-Type: ").append(mime).append("\r\n");
        h.append("Content-Length: ").append(len).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        if (contentRange != null) h.append("Content-Range: ").append(contentRange).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Headers: Range, Content-Type\r\n");
        h.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("ISO-8859-1"));
        out.flush();
    }
}
