package com.prometeo.music;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * v42 — Biblioteca: memorias del teléfono (interna, tarjeta SD, USB), volver a revisar carpetas,
 * canciones elegidas con el selector de Android y carpetas de una memoria USB que el usuario permitió.
 * Todo queda en el teléfono: no se envía nada a ningún lado.
 */
final class Library {
    private Library() { }

    static final String PRIMARY = "external_primary";

    /** Nombre de la memoria como lo usa Android para la música (external_primary, o el número de la tarjeta) */
    static String volName(StorageVolume v) {
        if (Build.VERSION.SDK_INT >= 30) {
            String n = v.getMediaStoreVolumeName();
            if (n != null) return n;
        }
        if (v.isPrimary()) return PRIMARY;
        String u = v.getUuid();
        return u == null ? "" : u.toLowerCase(Locale.ROOT);
    }

    static File volDir(StorageVolume v) {
        if (Build.VERSION.SDK_INT >= 30) return v.getDirectory();
        if (v.isPrimary()) return Environment.getExternalStorageDirectory();
        String u = v.getUuid();
        return u == null ? null : new File("/storage/" + u);
    }

    static StorageVolume findVol(Context ctx, String name) {
        try {
            StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            for (StorageVolume v : sm.getStorageVolumes()) if (name.equals(volName(v))) return v;
        } catch (Exception ignored) { }
        return null;
    }

    /** Lista de memorias: [{name, label, removable, primary, state, free, total, indexed, songs}] */
    static JSONArray volumes(Context ctx) throws JSONException {
        JSONArray out = new JSONArray();
        Set<String> indexed = new HashSet<String>();
        if (Build.VERSION.SDK_INT >= 29) {
            try { indexed.addAll(MediaStore.getExternalVolumeNames(ctx)); } catch (Exception ignored) { }
        }
        StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
        List<StorageVolume> list;
        try { list = sm.getStorageVolumes(); } catch (Exception e) { return out; }
        for (StorageVolume v : list) {
            JSONObject o = new JSONObject();
            String name = volName(v);
            o.put("name", name);
            String label = "";
            try { label = v.getDescription(ctx); } catch (Exception ignored) { }
            o.put("label", label == null ? "" : label);
            o.put("removable", v.isRemovable());
            o.put("primary", v.isPrimary());
            String st = v.getState();
            o.put("state", st == null ? "" : st);
            boolean mounted = Environment.MEDIA_MOUNTED.equals(st) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(st);
            o.put("mounted", mounted);
            long free = -1, total = -1;
            File dir = null;
            try { dir = volDir(v); } catch (Exception ignored) { }
            if (dir != null && mounted) {
                try { StatFs fs = new StatFs(dir.getPath()); free = fs.getAvailableBytes(); total = fs.getTotalBytes(); } catch (Exception ignored) { }
            }
            o.put("free", free);
            o.put("total", total);
            boolean idx = Build.VERSION.SDK_INT < 29 ? v.isPrimary() : indexed.contains(name);
            o.put("indexed", idx);
            int songs = -1;
            if (idx && mounted && Build.VERSION.SDK_INT >= 29) {
                Cursor c = null;
                try {
                    c = ctx.getContentResolver().query(MediaStore.Audio.Media.getContentUri(name), new String[]{ MediaStore.Audio.Media._ID },
                            MediaStore.Audio.Media.IS_RINGTONE + "=0 AND " + MediaStore.Audio.Media.IS_NOTIFICATION + "=0 AND " + MediaStore.Audio.Media.IS_ALARM + "=0", null, null);
                    if (c != null) songs = c.getCount();
                } catch (Exception ignored) {
                } finally { if (c != null) c.close(); }
            }
            o.put("songs", songs);
            out.put(o);
        }
        return out;
    }

    /** Le pide a Android que vuelva a revisar estas carpetas (por si moviste archivos hace poco). Espera como máximo waitMs. */
    static void rescan(Context ctx, JSONArray dirs, long waitMs) {
        Set<String> paths = new HashSet<String>();
        try {
            StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (!Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
                File root = volDir(v);
                if (root == null) continue;
                String name = volName(v);
                for (String d : new String[]{ Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_DOWNLOADS }) {
                    File f = new File(root, d);
                    if (f.isDirectory()) paths.add(f.getPath());
                }
                if (dirs != null) {
                    for (int i = 0; i < dirs.length(); i++) {
                        JSONObject o = dirs.optJSONObject(i);
                        if (o == null || !name.equals(o.optString("vol", PRIMARY))) continue;
                        String rel = o.optString("rel", "");
                        if (rel.contains("..")) continue;
                        File f = new File(root, rel);
                        if (f.isDirectory()) paths.add(f.getPath());
                    }
                }
            }
        } catch (Exception ignored) { }
        if (paths.isEmpty()) return;
        final CountDownLatch latch = new CountDownLatch(paths.size());
        try {
            MediaScannerConnection.scanFile(ctx, paths.toArray(new String[0]), null, new MediaScannerConnection.OnScanCompletedListener() {
                @Override public void onScanCompleted(String path, Uri uri) { latch.countDown(); }
            });
            latch.await(waitMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) { }
    }

    /** Datos de un archivo elegido con el selector de Android. mid = número en la música del teléfono (o -1) */
    static JSONObject describeDoc(Context ctx, Uri uri) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("uri", uri.toString());
        String name = "";
        long size = 0;
        ContentResolver cr = ctx.getContentResolver();
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{ OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE }, null, null, null);
            if (c != null && c.moveToFirst()) {
                if (!c.isNull(0)) name = c.getString(0);
                if (!c.isNull(1)) size = c.getLong(1);
            }
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        o.put("name", name == null ? "" : name);
        o.put("size", size);
        String mime = null;
        try { mime = cr.getType(uri); } catch (Exception ignored) { }
        o.put("mime", mime == null ? "" : mime);
        long mid = -1;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Uri m = MediaStore.getMediaUri(ctx, uri);
                if (m != null) mid = ContentUris.parseId(m);
            } catch (Exception ignored) { }
        }
        o.put("mid", mid);
        return o;
    }

    static boolean isVideoName(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        return l.endsWith(".mp4") || l.endsWith(".mkv") || l.endsWith(".mov") || l.endsWith(".avi") || l.endsWith(".m4v")
                || l.endsWith(".3gp") || l.endsWith(".ts") || l.endsWith(".wmv") || l.endsWith(".flv") || l.endsWith(".mpg") || l.endsWith(".mpeg");
    }
    private static boolean isAudioName(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        return l.endsWith(".mp3") || l.endsWith(".m4a") || l.endsWith(".aac") || l.endsWith(".flac") || l.endsWith(".ogg")
                || l.endsWith(".oga") || l.endsWith(".opus") || l.endsWith(".wav") || l.endsWith(".webm") || l.endsWith(".mka");
    }

    /** Todas las canciones dentro de una carpeta permitida (por ejemplo, una memoria USB): [{uri, name, size, mime, folder}] */
    static JSONArray listTree(Context ctx, Uri tree) throws JSONException {
        JSONArray out = new JSONArray();
        ContentResolver cr = ctx.getContentResolver();
        ArrayDeque<String[]> q = new ArrayDeque<String[]>();
        q.add(new String[]{ DocumentsContract.getTreeDocumentId(tree), "" });
        int dirs = 0;
        String[] cols = { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE };
        while (!q.isEmpty() && dirs < 2000 && out.length() < 8000) {
            String[] cur = q.poll();
            dirs++;
            Cursor c = null;
            try {
                c = cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, cur[0]), cols, null, null, null);
                while (c != null && c.moveToNext()) {
                    String id = c.getString(0), name = c.isNull(1) ? "" : c.getString(1), mime = c.isNull(2) ? "" : c.getString(2);
                    if (name == null || name.startsWith(".")) continue;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        if (cur[1].split("/").length < 10) q.add(new String[]{ id, cur[1] + name + "/" });
                    } else if ((mime != null && mime.startsWith("video/")) || (isVideoName(name) && (mime == null || !mime.startsWith("audio/")))) {
                        /* v43b: los videos de la memoria también se cuentan (para la pestaña 🎬 Videos) */
                        JSONObject o = new JSONObject();
                        o.put("uri", DocumentsContract.buildDocumentUriUsingTree(tree, id).toString());
                        o.put("name", name);
                        o.put("size", c.isNull(3) ? 0 : c.getLong(3));
                        o.put("mime", mime == null ? "" : mime);
                        o.put("folder", cur[1]);
                        o.put("video", true);
                        out.put(o);
                    } else if ((mime != null && mime.startsWith("audio/")) || isAudioName(name)) {
                        JSONObject o = new JSONObject();
                        o.put("uri", DocumentsContract.buildDocumentUriUsingTree(tree, id).toString());
                        o.put("name", name);
                        o.put("size", c.isNull(3) ? 0 : c.getLong(3));
                        o.put("mime", mime == null ? "" : mime);
                        o.put("folder", cur[1]);
                        out.put(o);
                    }
                }
            } catch (Exception ignored) {
            } finally { if (c != null) c.close(); }
        }
        return out;
    }

    /** Solo se pueden abrir archivos (o carpetas) que el usuario eligió con el selector de Android */
    static boolean uriAllowed(Context ctx, Uri uri) {
        String s = uri.toString();
        try {
            for (UriPermission p : ctx.getContentResolver().getPersistedUriPermissions()) {
                if (!p.isReadPermission()) continue;
                String pu = p.getUri().toString();
                if (s.equals(pu) || s.startsWith(pu + "/document/")) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }
}
