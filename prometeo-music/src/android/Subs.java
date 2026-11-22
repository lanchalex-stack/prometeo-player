package com.prometeo.music;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v43 — 💬 Subtítulos que el usuario carga (.srt, .vtt, .ass/.ssa).
 * Se leen del archivo que eligió con el selector de Android, se arreglan los acentos (ñ, á…)
 * y se corre el tiempo si pidió adelantar o atrasar. Se guarda una copia arreglada en la caché de la app.
 */
final class Subs {
    private Subs() { }

    static final class Prepared { File file; String mime; String vtt; }

    static String nameOf(Context ctx, Uri u) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(u, new String[]{ OpenableColumns.DISPLAY_NAME }, null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        String p = u.getLastPathSegment();
        return p == null ? "subtitulos" : p.substring(p.lastIndexOf('/') + 1);
    }

    /** Texto con los acentos bien: si no es UTF-8 válido, se lee como Windows-1252 (lo típico de los .srt viejos) */
    static String decode(byte[] b) {
        int off = (b.length >= 3 && (b[0] & 255) == 0xEF && (b[1] & 255) == 0xBB && (b[2] & 255) == 0xBF) ? 3 : 0;
        if (b.length >= 2 && (b[0] & 255) == 0xFF && (b[1] & 255) == 0xFE) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        if (b.length >= 2 && (b[0] & 255) == 0xFE && (b[1] & 255) == 0xFF) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, off, b.length - off)).toString();
        } catch (CharacterCodingException e) {
            Charset w = Charset.isSupported("windows-1252") ? Charset.forName("windows-1252") : StandardCharsets.ISO_8859_1;
            return new String(b, off, b.length - off, w);
        }
    }

    static String kindOf(String name, String text) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String t = text.trim();
        if (n.endsWith(".vtt") || t.startsWith("WEBVTT")) return "vtt";
        if (n.endsWith(".ass") || n.endsWith(".ssa") || t.startsWith("[Script Info]")) return "ass";
        return "srt";
    }

    private static final Pattern TIME = Pattern.compile("(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})([,.])(\\d{2,3})");

    private static long ms(Matcher m) {
        long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long mi = Long.parseLong(m.group(2)), s = Long.parseLong(m.group(3));
        String f = m.group(5); long frac = Long.parseLong(f); if (f.length() == 2) frac *= 10;
        return ((h * 60 + mi) * 60 + s) * 1000 + frac;
    }

    private static String fmt(long t, String sep, boolean centis, boolean shortHour) {
        t = Math.max(0, t);
        long h = t / 3600000, mi = (t / 60000) % 60, s = (t / 1000) % 60, f = t % 1000;
        if (centis) return String.format(Locale.ROOT, "%d:%02d:%02d%s%02d", h, mi, s, sep, f / 10);
        return String.format(Locale.ROOT, shortHour ? "%d:%02d:%02d%s%03d" : "%02d:%02d:%02d%s%03d", h, mi, s, sep, f);
    }

    /** Corre todos los tiempos "offset" milisegundos (solo en las líneas de tiempo) */
    static String shift(String text, String kind, long offset) {
        if (offset == 0) return text;
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            boolean timing = "ass".equals(kind) ? line.startsWith("Dialogue:") : line.contains("-->");
            if (!timing) { out.append(line).append('\n'); continue; }
            Matcher m = TIME.matcher(line);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                long t = ms(m) + offset;
                String rep = "ass".equals(kind) ? fmt(t, ".", true, true) : fmt(t, m.group(4), false, false);
                m.appendReplacement(sb, Matcher.quoteReplacement(rep));
            }
            m.appendTail(sb);
            out.append(sb).append('\n');
        }
        return out.toString();
    }

    /** SRT → WebVTT (el Chromecast entiende WebVTT) */
    static String toVtt(String text, String kind) {
        if ("vtt".equals(kind)) return text;
        if ("ass".equals(kind)) {
            StringBuilder v = new StringBuilder("WEBVTT\n\n");
            for (String line : text.split("\n")) {
                if (!line.startsWith("Dialogue:")) continue;
                String[] p = line.substring(9).split(",", 10);
                if (p.length < 10) continue;
                Matcher a = TIME.matcher(p[1]), b = TIME.matcher(p[2]);
                if (!a.find() || !b.find()) continue;
                String t = p[9].replaceAll("\\{[^}]*\\}", "").replace("\\N", "\n").replace("\\n", "\n").trim();
                if (t.isEmpty()) continue;
                v.append(fmt(ms(a), ".", false, false)).append(" --> ").append(fmt(ms(b), ".", false, false)).append('\n').append(t).append("\n\n");
            }
            return v.toString();
        }
        StringBuilder v = new StringBuilder("WEBVTT\n\n");
        for (String line : text.split("\n")) {
            if (line.contains("-->")) v.append(line.replace(',', '.')).append('\n');
            else v.append(line).append('\n');
        }
        return v.toString();
    }

    /** Lee el archivo elegido, lo arregla y deja una copia lista para el reproductor */
    static Prepared prepare(Context ctx, Uri src, String key, long offset) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(src);
        if (in == null) throw new Exception("open");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[16384]; int n, total = 0;
            while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); total += n; if (total > 8 * 1024 * 1024) throw new Exception("big"); }
        } finally { in.close(); }
        String text = decode(bo.toByteArray()).replace("\r\n", "\n").replace('\r', '\n');
        String kind = kindOf(nameOf(ctx, src), text);
        text = shift(text, kind, offset);
        File dir = new File(ctx.getCacheDir(), "subs");
        if (!dir.isDirectory()) dir.mkdirs();
        Prepared p = new Prepared();
        p.file = new File(dir, key.replaceAll("[^A-Za-z0-9_-]", "_") + "." + kind);
        FileOutputStream fo = new FileOutputStream(p.file);
        try { fo.write(text.getBytes(StandardCharsets.UTF_8)); } finally { fo.close(); }
        p.mime = "vtt".equals(kind) ? "text/vtt" : ("ass".equals(kind) ? "text/x-ssa" : "application/x-subrip");
        p.vtt = toVtt(text, kind);
        return p;
    }
}
