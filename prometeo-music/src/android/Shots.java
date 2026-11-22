package com.prometeo.music;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * v43 — 📸 Captura de pantalla de un video o de un canal de TV.
 * Copia la imagen que se está viendo (PixelCopy) y la guarda al instante en Imágenes/Prometeo,
 * así aparece en la Galería. No se envía a ningún lado.
 */
final class Shots {
    private Shots() { }

    interface Done { void done(boolean ok, boolean black, String where); }

    /** Nombre seguro para el archivo: sin caracteres raros, máximo 60 letras */
    static String clean(String n) {
        if (n == null) n = "";
        n = n.replaceAll("\\.[A-Za-z0-9]{2,4}$", "").replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
        if (n.length() > 60) n = n.substring(0, 60).trim();
        return n.isEmpty() ? "video" : n;
    }

    /** prometeo-Video-3m12s.jpg */
    static String nameForVideo(String title, long posMs) {
        long s = Math.max(0, posMs / 1000);
        String t = s >= 3600 ? (s / 3600) + "h" + ((s / 60) % 60) + "m" + (s % 60) + "s" : (s / 60) + "m" + (s % 60) + "s";
        return "prometeo-" + clean(title) + "-" + t + ".jpg";
    }

    /** prometeo-Canal-20261121-193512.jpg */
    static String nameForChannel(String ch) {
        return "prometeo-" + clean(ch) + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".jpg";
    }

    /** Copia lo que se ve en el video. videoW/H: tamaño real del video (si se sabe) para que la foto salga nítida */
    static void capture(final Context ctx, View videoSurface, int videoW, int videoH, final String fileName, final Done cb) {
        if (!(videoSurface instanceof SurfaceView) || Build.VERSION.SDK_INT < 24) { cb.done(false, false, null); return; }
        SurfaceView sv = (SurfaceView) videoSurface;
        int w = videoW > 0 ? videoW : sv.getWidth(), h = videoH > 0 ? videoH : sv.getHeight();
        /* sin pasarse de 1920 en el lado más largo (para no llenar la memoria) */
        float k = Math.min(1f, 1920f / Math.max(1, Math.max(w, h)));
        w = Math.max(2, Math.round(w * k)); h = Math.max(2, Math.round(h * k));
        if (sv.getHolder() == null || sv.getHolder().getSurface() == null || !sv.getHolder().getSurface().isValid()) { cb.done(false, false, null); return; }
        final Bitmap bmp;
        try { bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); } catch (Throwable e) { cb.done(false, false, null); return; }
        final Handler main = new Handler(Looper.getMainLooper());
        try {
            PixelCopy.request(sv, bmp, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override public void onPixelCopyFinished(final int result) {
                    if (result != PixelCopy.SUCCESS) { bmp.recycle(); cb.done(false, false, null); return; }
                    final boolean black = isBlack(bmp);
                    new Thread(new Runnable() { public void run() {
                        final String where = save(ctx, bmp, fileName);
                        bmp.recycle();
                        main.post(new Runnable() { public void run() { cb.done(where != null, black, where); } });
                    } }).start();
                }
            }, main);
        } catch (Throwable e) { bmp.recycle(); cb.done(false, false, null); }
    }

    /** ¿Salió negra? (pasa con canales protegidos) */
    static boolean isBlack(Bitmap b) {
        int max = 0, W = b.getWidth(), H = b.getHeight();
        for (int y = 1; y < 12; y++) for (int x = 1; x < 12; x++) {
            int c = b.getPixel(x * W / 12, y * H / 12);
            int v = Math.max((c >> 16) & 255, Math.max((c >> 8) & 255, c & 255));
            if (v > max) max = v;
        }
        return max < 14;
    }

    /** Guarda en Imágenes/Prometeo. Devuelve la carpeta (para el aviso) o null */
    static String save(Context ctx, Bitmap bmp, String fileName) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Prometeo");
                v.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri u = ctx.getContentResolver().insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                if (u == null) return null;
                OutputStream os = ctx.getContentResolver().openOutputStream(u);
                if (os == null) return null;
                try { bmp.compress(Bitmap.CompressFormat.JPEG, 92, os); } finally { os.close(); }
                ContentValues v2 = new ContentValues(); v2.put(MediaStore.Images.Media.IS_PENDING, 0);
                ctx.getContentResolver().update(u, v2, null, null);
                return "Pictures/Prometeo";
            }
            /* Android 9 o anterior: carpeta de imágenes de la app (se ve en la Galería) */
            File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Prometeo");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File f = new File(dir, fileName);
            FileOutputStream fo = new FileOutputStream(f);
            try { bmp.compress(Bitmap.CompressFormat.JPEG, 92, fo); } finally { fo.close(); }
            MediaScannerConnection.scanFile(ctx, new String[]{ f.getPath() }, new String[]{ "image/jpeg" }, null);
            return f.getParent();
        } catch (Exception e) { return null; }
    }
}
