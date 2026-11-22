package com.prometeo.music;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * v43 — 🎬 Videos del teléfono (sin copiarlos): lista, miniaturas y "Seguir viendo".
 * Solo lee lo que Android ya tiene en su índice de videos. Nada sale del teléfono.
 */
final class Videos {
    private Videos() { }

    static Uri collection() {
        return Build.VERSION.SDK_INT >= 29 ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    }

    static Uri uriOf(long id) { return ContentUris.withAppendedId(collection(), id); }

    private static String folderOf(String p) {
        if (p == null) return "";
        int i = p.lastIndexOf('/');
        String d = i >= 0 ? p.substring(0, i + 1) : "";
        d = d.replaceFirst("^/storage/emulated/\\d+/", "").replaceFirst("^/storage/[^/]+/", "").replaceFirst("^/sdcard/", "");
        return d;
    }

    /** [{id,name,title,duration,size,mime,folder,vol,date,w,h}] */
    static JSONArray scan(Context ctx) throws JSONException {
        JSONArray out = new JSONArray();
        String[] proj = {
                MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE, MediaStore.Video.Media.MIME_TYPE,
                Build.VERSION.SDK_INT >= 29 ? MediaStore.Video.Media.RELATIVE_PATH : MediaStore.Video.Media.DATA,
                Build.VERSION.SDK_INT >= 29 ? MediaStore.MediaColumns.VOLUME_NAME : MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT
        };
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(collection(), proj, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC");
            while (c != null && c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("id", c.getLong(0));
                o.put("name", c.isNull(1) ? "" : c.getString(1));
                o.put("title", c.isNull(2) ? "" : c.getString(2));
                o.put("duration", c.isNull(3) ? 0 : c.getLong(3));
                o.put("size", c.isNull(4) ? 0 : c.getLong(4));
                o.put("mime", c.isNull(5) ? "" : c.getString(5));
                String p = c.isNull(6) ? "" : c.getString(6);
                o.put("folder", Build.VERSION.SDK_INT >= 29 ? p : folderOf(p));
                o.put("vol", Build.VERSION.SDK_INT >= 29 && !c.isNull(7) ? c.getString(7) : Library.PRIMARY);
                o.put("date", c.isNull(8) ? 0 : c.getLong(8) * 1000);
                o.put("w", c.isNull(9) ? 0 : c.getInt(9));
                o.put("h", c.isNull(10) ? 0 : c.getInt(10));
                out.put(o);
            }
        } finally { if (c != null) c.close(); }
        return out;
    }

    /** Miniatura JPEG (o null) */
    @SuppressWarnings("deprecation")
    static byte[] thumb(Context ctx, long id) {
        Bitmap b = null;
        try {
            if (Build.VERSION.SDK_INT >= 29) b = ctx.getContentResolver().loadThumbnail(uriOf(id), new Size(384, 216), null);
            else b = MediaStore.Video.Thumbnails.getThumbnail(ctx.getContentResolver(), id, MediaStore.Video.Thumbnails.MINI_KIND, null);
        } catch (Exception e) { b = null; }
        if (b == null) return null;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 80, bo);
        b.recycle();
        return bo.toByteArray();
    }

    /** v43b: miniatura de un video de memoria USB o elegido con el selector (o null) */
    static byte[] thumbDoc(Context ctx, Uri u) {
        Bitmap b = null;
        try { b = android.provider.DocumentsContract.getDocumentThumbnail(ctx.getContentResolver(), u, new android.graphics.Point(384, 216), null); } catch (Exception e) { b = null; }
        if (b == null) {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            try {
                r.setDataSource(ctx, u);
                Bitmap f = r.getFrameAtTime(2000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (f != null) {
                    float k = Math.min(1f, 384f / Math.max(1, f.getWidth()));
                    b = k < 1f ? Bitmap.createScaledBitmap(f, Math.max(1, Math.round(f.getWidth() * k)), Math.max(1, Math.round(f.getHeight() * k)), true) : f;
                    if (b != f) f.recycle();
                }
            } catch (Exception e) { b = null; }
            finally { try { r.release(); } catch (Exception ignored) { } }
        }
        if (b == null) return null;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 80, bo);
        b.recycle();
        return bo.toByteArray();
    }

    /** v43b: duración de un video de memoria USB (ms, o 0) */
    static long durationDoc(Context ctx, Uri u) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try { r.setDataSource(ctx, u); String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION); return d == null ? 0 : Long.parseLong(d); }
        catch (Exception e) { return 0; }
        finally { try { r.release(); } catch (Exception ignored) { } }
    }

    /* ---- Seguir viendo: posición de cada video (se guarda en el teléfono) ---- */
    static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences("prometeo_video", Context.MODE_PRIVATE); }

    /** key = número del video, o "u…" para un video de memoria USB / elegido a mano */
    static void savePos(Context ctx, String id, long pos, long dur) {
        if (id == null || id.isEmpty() || "-1".equals(id)) return;
        prefs(ctx).edit().putLong("p_" + id, pos).putLong("d_" + id, dur).putLong("t_" + id, System.currentTimeMillis()).apply();
    }

    /** {"id":{p,d,t}} */
    static JSONObject state(Context ctx) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> e : prefs(ctx).getAll().entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("p_")) continue;
            String id = k.substring(2);
            JSONObject v = new JSONObject();
            v.put("p", prefs(ctx).getLong("p_" + id, 0));
            v.put("d", prefs(ctx).getLong("d_" + id, 0));
            v.put("t", prefs(ctx).getLong("t_" + id, 0));
            o.put(id, v);
        }
        return o;
    }

    /** Pasa la posición guardada de un video a su nuevo número (si se movió de carpeta) */
    static void movePos(Context ctx, String from, String to) {
        SharedPreferences p = prefs(ctx);
        if (!p.contains("p_" + from)) return;
        SharedPreferences.Editor e = p.edit();
        for (String k : new String[]{ "p_", "d_", "t_", "o_" }) if (p.contains(k + from)) { e.putLong(k + to, p.getLong(k + from, 0)); e.remove(k + from); }
        if (p.contains("s_" + from)) { e.putString("s_" + to, p.getString("s_" + from, "")); e.remove("s_" + from); }
        e.apply();
    }
}
