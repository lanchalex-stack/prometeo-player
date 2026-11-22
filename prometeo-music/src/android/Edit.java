package com.prometeo.music;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import com.prometeo.lame.Mp3Enc;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v44 — ✂️ Cortar video y 🎵 extraer audio (MP3 o M4A).
 * Todo pasa dentro del teléfono: el original no se toca y el resultado se guarda en
 * Películas/Prometeo (video) o Música/Prometeo (audio). Nada se sube a ningún lado.
 * - Cortar y M4A: Media3 Transformer (Apache 2.0).
 * - MP3: el audio se decodifica con Android y se codifica con jump3r (LAME en Java, LGPL 2.1+).
 */
final class Edit {
    private Edit() { }

    interface Ev { void ev(JSONObject o); }

    private static volatile boolean busy = false, cancelled = false;
    private static Transformer current;

    static boolean busy() { return busy; }

    static void cancel() {
        cancelled = true;
        final Transformer t = current;
        if (t != null) new Handler(Looper.getMainLooper()).post(new Runnable() { public void run() { try { t.cancel(); } catch (Exception ignored) { } } });
    }

    private static JSONObject o(String type) { JSONObject j = new JSONObject(); try { j.put("type", type); } catch (Exception ignored) { } return j; }
    private static void progress(Ev ev, int p) { JSONObject j = o("progress"); try { j.put("p", Math.max(0, Math.min(100, p))); } catch (Exception ignored) { } ev.ev(j); }
    private static void fail(Ev ev, String msg) { busy = false; current = null; JSONObject j = o("error"); try { j.put("msg", msg); } catch (Exception ignored) { } ev.ev(j); }

    /* ---------------- nombres: base_001, base_002… ---------------- */
    static String clean(String n) {
        if (n == null) n = "";
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
        if (n.length() > 80) n = n.substring(0, 80).trim();
        return n.isEmpty() ? "Prometeo" : n;
    }

    /** Siguiente nombre libre: "base_001" (busca en Música o en Películas, cualquier extensión) */
    static String nextName(Context ctx, String base, boolean video) {
        base = clean(base).replaceAll("_\\d{3}$", "");
        Uri col = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        int max = 0;
        Pattern p = Pattern.compile("^" + Pattern.quote(base) + "_(\\d{3,})\\.[A-Za-z0-9]+$");
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(col, new String[]{ MediaStore.MediaColumns.DISPLAY_NAME },
                    MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?", new String[]{ base + "\\_%" }, null);
            while (c != null && c.moveToNext()) {
                Matcher m = p.matcher(c.isNull(0) ? "" : c.getString(0));
                if (m.find()) max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        return String.format(Locale.ROOT, "%s_%03d", base, max + 1);
    }

    private static boolean nameTaken(Context ctx, String display, boolean video) {
        Uri col = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(col, new String[]{ MediaStore.MediaColumns._ID }, MediaStore.MediaColumns.DISPLAY_NAME + " = ?", new String[]{ display }, null);
            return c != null && c.moveToFirst();
        } catch (Exception e) { return false; } finally { if (c != null) c.close(); }
    }

    /* ---------------- guardar el resultado donde lo ve la Galería / la música ---------------- */
    /** Copia el archivo terminado a Música/Prometeo o Películas/Prometeo. Devuelve [uri, nombre final] */
    private static String[] publish(Context ctx, File f, String name, String ext, String mime, boolean video) throws Exception {
        String display = name + "." + ext;
        for (int k = 2; nameTaken(ctx, display, video) && k < 999; k++) display = name + " (" + k + ")." + ext;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, display);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_MUSIC) + "/Prometeo");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            if (!video) v.put(MediaStore.Audio.Media.IS_MUSIC, 1);
            Uri col = video ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) : MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri u = ctx.getContentResolver().insert(col, v);
            if (u == null) throw new Exception("insert");
            OutputStream os = ctx.getContentResolver().openOutputStream(u);
            if (os == null) throw new Exception("open");
            InputStream in = new FileInputStream(f);
            try { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) os.write(b, 0, n); } finally { in.close(); os.close(); }
            ContentValues v2 = new ContentValues(); v2.put(MediaStore.MediaColumns.IS_PENDING, 0);
            ctx.getContentResolver().update(u, v2, null, null);
            return new String[]{ u.toString(), display };
        }
        /* Android 9 o anterior: carpeta de la app (se ve en la Galería / en la música) */
        File dir = new File(ctx.getExternalFilesDir(video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_MUSIC), "Prometeo");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("dir");
        File out = new File(dir, display);
        InputStream in = new FileInputStream(f); OutputStream os = new FileOutputStream(out);
        try { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) os.write(b, 0, n); } finally { in.close(); os.close(); }
        MediaScannerConnection.scanFile(ctx, new String[]{ out.getPath() }, new String[]{ mime }, null);
        return new String[]{ Uri.fromFile(out).toString(), display };
    }

    /** "Deshacer": borra el archivo que Prometeo acaba de crear */
    static boolean delete(Context ctx, String uri) {
        try {
            Uri u = Uri.parse(uri);
            if ("file".equals(u.getScheme())) return new File(u.getPath()).delete();
            return ctx.getContentResolver().delete(u, null, null) > 0;
        } catch (Exception e) { return false; }
    }

    private static void done(Context ctx, Ev ev, File tmp, String name, String ext, String mime, boolean video) {
        try {
            String[] r = publish(ctx, tmp, name, ext, mime, video);
            JSONObject j = o("done");
            j.put("uri", r[0]); j.put("name", r[1]); j.put("kind", video ? "video" : "audio");
            try { j.put("mid", ContentUris.parseId(Uri.parse(r[0]))); } catch (Exception ignored) { j.put("mid", -1); }
            busy = false; current = null;
            ev.ev(j);
        } catch (Exception e) { fail(ev, "save"); }
        finally { tmp.delete(); }
    }

    private static MediaItem item(Uri src, long a, long b) {
        MediaItem.ClippingConfiguration.Builder cb = new MediaItem.ClippingConfiguration.Builder();
        if (a > 0) cb.setStartPositionMs(a);
        if (b > 0) cb.setEndPositionMs(b);
        return new MediaItem.Builder().setUri(src).setClippingConfiguration(cb.build()).build();
    }

    /* ---------------- ✂️ cortar video / 🎵 M4A (Media3 Transformer) ---------------- */
    static void transform(final Context ctx, final Uri src, final long a, final long b, final String name, final boolean audioOnly, final Ev ev) {
        if (busy) { fail(ev, "busy"); return; }
        busy = true; cancelled = false;
        final Handler main = new Handler(Looper.getMainLooper());
        main.post(new Runnable() { public void run() {
            try {
                File dir = new File(ctx.getCacheDir(), "edit"); if (!dir.isDirectory()) dir.mkdirs();
                final File tmp = new File(dir, "job_" + System.currentTimeMillis() + (audioOnly ? ".m4a" : ".mp4"));
                Transformer.Builder tb = new Transformer.Builder(ctx).addListener(new Transformer.Listener() {
                    @Override public void onCompleted(Composition c, ExportResult r) {
                        current = null;
                        new Thread(new Runnable() { public void run() {
                            if (audioOnly) done(ctx, ev, tmp, name, "m4a", "audio/mp4", false);
                            else done(ctx, ev, tmp, name, "mp4", "video/mp4", true);
                        } }).start();
                    }
                    @Override public void onError(Composition c, ExportResult r, ExportException e) {
                        tmp.delete();
                        fail(ev, cancelled ? "cancel" : "transform:" + e.getErrorCodeName());
                    }
                });
                if (audioOnly) tb.setAudioMimeType(MimeTypes.AUDIO_AAC);
                final Transformer t = tb.build();
                current = t;
                EditedMediaItem em = new EditedMediaItem.Builder(item(src, a, b)).setRemoveVideo(audioOnly).build();
                t.start(em, tmp.getAbsolutePath());
                final ProgressHolder ph = new ProgressHolder();
                main.postDelayed(new Runnable() { public void run() {
                    if (current != t) return;
                    try { if (t.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) progress(ev, ph.progress); } catch (Exception ignored) { }
                    main.postDelayed(this, 500);
                } }, 500);
            } catch (Throwable e) { fail(ev, "transform"); }
        } });
    }

    /* ---------------- 🎵 MP3 (Android decodifica, jump3r/LAME codifica) ---------------- */
    static void mp3(final Context ctx, final Uri src, final long a, final long b, final String name, final Ev ev) {
        if (busy) { fail(ev, "busy"); return; }
        busy = true; cancelled = false;
        new Thread(new Runnable() { public void run() {
            MediaExtractor ex = new MediaExtractor(); MediaCodec dec = null; FileOutputStream fo = null;
            File dir = new File(ctx.getCacheDir(), "edit"); if (!dir.isDirectory()) dir.mkdirs();
            File tmp = new File(dir, "job_" + System.currentTimeMillis() + ".mp3");
            try {
                ex.setDataSource(ctx, src, null);
                int tr = -1; MediaFormat f = null;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat fi = ex.getTrackFormat(i); String m = fi.getString(MediaFormat.KEY_MIME);
                    if (m != null && m.startsWith("audio/")) { tr = i; f = fi; break; }
                }
                if (tr < 0) { fail(ev, "noaudio"); return; }
                ex.selectTrack(tr);
                long durUs = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 0;
                long aUs = Math.max(0, a) * 1000, bUs = b > 0 ? b * 1000 : (durUs > 0 ? durUs : Long.MAX_VALUE);
                if (aUs > 0) ex.seekTo(aUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                dec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
                dec.configure(f, null, null, 0);
                dec.start();
                fo = new FileOutputStream(tmp);
                int sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                boolean flt = false;
                Mp3Enc enc = null; byte[] out = new byte[0]; short[] pcm = new short[0];
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inEos = false, outEos = false; int lastP = -1;
                while (!outEos) {
                    if (cancelled) throw new InterruptedException();
                    if (!inEos) {
                        int ii = dec.dequeueInputBuffer(10000);
                        if (ii >= 0) {
                            ByteBuffer ib = dec.getInputBuffer(ii);
                            int n = ib == null ? -1 : ex.readSampleData(ib, 0);
                            long t = ex.getSampleTime();
                            if (n < 0 || t > bUs) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true; }
                            else { dec.queueInputBuffer(ii, 0, n, t, 0); ex.advance(); }
                        }
                    }
                    int oi = dec.dequeueOutputBuffer(info, 10000);
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = dec.getOutputFormat();
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING)) flt = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4;
                        continue;
                    }
                    if (oi < 0) continue;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                    ByteBuffer ob = dec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size); ob.order(ByteOrder.nativeOrder());
                        int bps = flt ? 4 : 2, frames = info.size / (bps * Math.max(1, ch));
                        long pts = info.presentationTimeUs; long frameUs = 1000000L / Math.max(1, sr);
                        int skip = pts < aUs ? (int) Math.min(frames, (aUs - pts) / frameUs) : 0;
                        int keep = frames - skip;
                        if (bUs != Long.MAX_VALUE && pts + (long) frames * frameUs > bUs) keep = (int) Math.max(0, Math.min(keep, (bUs - pts) / frameUs - skip));
                        if (keep > 0) {
                            int oc = ch == 1 ? 1 : 2;
                            if (enc == null) enc = new Mp3Enc(sr, oc, 192);
                            if (pcm.length < keep * oc) pcm = new short[keep * oc];
                            for (int i = 0; i < keep; i++) {
                                int base = (skip + i) * ch;
                                float l, r;
                                if (flt) { l = ob.getFloat(info.offset + base * 4); r = ch > 1 ? ob.getFloat(info.offset + (base + 1) * 4) : l; if (ch > 2) { float c = ob.getFloat(info.offset + (base + 2) * 4); l += 0.7f * c; r += 0.7f * c; } l *= 32767f; r *= 32767f; }
                                else { l = ob.getShort(info.offset + base * 2); r = ch > 1 ? ob.getShort(info.offset + (base + 1) * 2) : l; if (ch > 2) { float c = ob.getShort(info.offset + (base + 2) * 2); l += 0.7f * c; r += 0.7f * c; } }
                                short sl = (short) Math.max(-32768, Math.min(32767, Math.round(l))), srr = (short) Math.max(-32768, Math.min(32767, Math.round(r)));
                                if (oc == 1) pcm[i] = sl; else { pcm[2 * i] = sl; pcm[2 * i + 1] = srr; }
                            }
                            int need = Mp3Enc.outSize(keep); if (out.length < need) out = new byte[need];
                            int w = enc.encode(pcm, keep, out); if (w > 0) fo.write(out, 0, w);
                        }
                        long span = (bUs != Long.MAX_VALUE ? bUs : durUs) - aUs;
                        if (span > 0) { int p = (int) ((pts - aUs) * 100 / span); if (p != lastP && p >= 0) { lastP = p; progress(ev, p); } }
                    }
                    dec.releaseOutputBuffer(oi, false);
                }
                if (enc == null) { fail(ev, "noaudio"); return; }
                if (out.length < 7200) out = new byte[7200];
                int w = enc.flush(out); if (w > 0) fo.write(out, 0, w);
                fo.close(); fo = null;
                done(ctx, ev, tmp, name, "mp3", "audio/mpeg", false);
            } catch (InterruptedException ie) { tmp.delete(); fail(ev, "cancel");
            } catch (Throwable e) { tmp.delete(); fail(ev, "mp3"); }
            finally {
                try { if (fo != null) fo.close(); } catch (Exception ignored) { }
                try { if (dec != null) { dec.stop(); dec.release(); } } catch (Exception ignored) { }
                try { ex.release(); } catch (Exception ignored) { }
            }
        } }).start();
    }
}
