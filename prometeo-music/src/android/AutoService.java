package com.prometeo.music;

import android.content.Context;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.service.media.MediaBrowserService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * v44 — 🚗 Android Auto: muestra en la pantalla del carro la música, las listas, la radio y los favoritos.
 * La música suena en la app (Prometeo tiene que estar abierto en el teléfono); el carro solo manda
 * "reproduce esto", "pausa", "siguiente"… a la misma sesión que ya usan el Bluetooth y el volante.
 * La lista que se muestra la manda la app (catálogo); no sale del teléfono.
 */
public class AutoService extends MediaBrowserService {
    private static MediaSession shared;
    private static AutoService alive;
    static volatile boolean appAlive = false;
    private static JSONObject catalog;

    /** La única sesión multimedia de Prometeo (la usan la app, el Bluetooth y Android Auto) */
    static synchronized MediaSession shared(Context ctx) {
        if (shared == null) {
            shared = new MediaSession(ctx.getApplicationContext(), "PrometeoPlayer");
            idle(ctx);
        }
        return shared;
    }

    /** Sin la app abierta: el carro muestra un aviso en vez de quedarse esperando */
    static synchronized void idle(Context ctx) {
        if (shared == null) return;
        String msg = "Abre Prometeo en el teléfono";
        try { JSONObject c = catalog(ctx); if (c != null && c.optString("openApp", "").length() > 0) msg = c.optString("openApp"); } catch (Exception ignored) { }
        final String m = msg;
        shared.setCallback(new MediaSession.Callback() {
            @Override public void onPlayFromMediaId(String id, Bundle extras) { err(m); }
            @Override public void onPlay() { err(m); }
            @Override public void onPlayFromSearch(String q, Bundle extras) { err(m); }
        });
    }

    private static void err(String m) {
        if (shared == null) return;
        shared.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_ERROR, 0, 0f).setErrorMessage(m)
                .setActions(PlaybackState.ACTION_PLAY_FROM_MEDIA_ID | PlaybackState.ACTION_PLAY_FROM_SEARCH).build());
    }

    /* ---------------- catálogo (lo manda la app) ---------------- */
    private static File file(Context ctx) { return new File(ctx.getFilesDir(), "auto_catalog.json"); }

    static synchronized void setCatalog(Context ctx, String json) {
        try {
            catalog = new JSONObject(json);
            FileOutputStream fo = new FileOutputStream(file(ctx));
            try { fo.write(json.getBytes(StandardCharsets.UTF_8)); } finally { fo.close(); }
        } catch (Exception ignored) { }
        AutoService s = alive;
        if (s != null) {
            try { s.notifyChildrenChanged(ROOT); for (String k : new String[]{ "music", "lists", "radio", "favs" }) s.notifyChildrenChanged(k); } catch (Exception ignored) { }
        }
    }

    private static synchronized JSONObject catalog(Context ctx) {
        if (catalog != null) return catalog;
        try {
            File f = file(ctx);
            if (!f.isFile()) return null;
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            try { int off = 0, n; while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n; } finally { in.close(); }
            catalog = new JSONObject(new String(b, StandardCharsets.UTF_8));
        } catch (Exception e) { catalog = null; }
        return catalog;
    }

    /* ---------------- el carro pregunta qué mostrar ---------------- */
    private static final String ROOT = "root";

    @Override public void onCreate() {
        super.onCreate();
        alive = this;
        setSessionToken(shared(this).getSessionToken());
        if (!appAlive) idle(this);
    }

    @Override public void onDestroy() { if (alive == this) alive = null; super.onDestroy(); }

    @Override public BrowserRoot onGetRoot(String pkg, int uid, Bundle hints) {
        Bundle extras = new Bundle();
        /* listas en cuadrícula, canciones en filas */
        extras.putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1);
        extras.putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1);
        return new BrowserRoot(ROOT, extras);
    }

    private static MediaBrowser.MediaItem folder(String id, String title, String sub) {
        return new MediaBrowser.MediaItem(new MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(sub).build(), MediaBrowser.MediaItem.FLAG_BROWSABLE);
    }
    private static MediaBrowser.MediaItem playable(String id, String title, String sub) {
        return new MediaBrowser.MediaItem(new MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(sub).build(), MediaBrowser.MediaItem.FLAG_PLAYABLE);
    }

    @Override public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        List<MediaBrowser.MediaItem> out = new ArrayList<>();
        JSONObject c = catalog(this);
        try {
            JSONObject L = c == null ? new JSONObject() : c.optJSONObject("labels");
            if (L == null) L = new JSONObject();
            if (c == null) {
                out.add(playable("none", "Abre Prometeo en el teléfono", "Prometeo Player"));
            } else if (ROOT.equals(parentId)) {
                out.add(folder("music", L.optString("music", "🎵 Música"), ""));
                out.add(folder("lists", L.optString("lists", "📋 Listas"), ""));
                out.add(folder("radio", L.optString("radio", "📻 Radio"), ""));
                out.add(folder("favs", L.optString("favs", "⭐ Favoritos"), ""));
            } else if ("music".equals(parentId)) {
                JSONArray a = c.optJSONArray("music");
                out.add(playable("mix", L.optString("mix", "🔀 Mezclar todo"), a == null ? "" : String.valueOf(a.length())));
                for (int i = 0; a != null && i < a.length() && i < 1500; i++) { JSONObject s = a.getJSONObject(i); out.add(playable("s:" + s.optString("id"), s.optString("t"), s.optString("a"))); }
            } else if ("lists".equals(parentId)) {
                JSONArray a = c.optJSONArray("lists");
                for (int i = 0; a != null && i < a.length(); i++) { JSONObject p = a.getJSONObject(i); out.add(folder("pl:" + p.optString("id"), p.optString("n"), p.optString("sub"))); }
            } else if (parentId.startsWith("pl:")) {
                JSONArray a = c.optJSONArray("lists");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject p = a.getJSONObject(i);
                    if (!parentId.equals("pl:" + p.optString("id"))) continue;
                    JSONArray it = p.optJSONArray("items");
                    for (int k = 0; it != null && k < it.length() && k < 1500; k++) { JSONObject s = it.getJSONObject(k); out.add(playable("pls:" + p.optString("id") + ":" + s.optString("id"), s.optString("t"), s.optString("a"))); }
                }
            } else if ("radio".equals(parentId)) {
                JSONArray a = c.optJSONArray("radio");
                for (int i = 0; a != null && i < a.length(); i++) { JSONObject r = a.getJSONObject(i); out.add(playable("r:" + r.optString("id"), r.optString("n"), r.optString("sub"))); }
            } else if ("favs".equals(parentId)) {
                JSONArray a = c.optJSONArray("favs");
                for (int i = 0; a != null && i < a.length(); i++) { JSONObject s = a.getJSONObject(i); out.add(playable(s.optString("id"), s.optString("t"), s.optString("a"))); }
            }
        } catch (Exception ignored) { }
        result.sendResult(out);
    }
}
