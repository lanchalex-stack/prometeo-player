package com.prometeo.music;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaSeekOptions;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.MediaTrack;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.api.ResultCallback;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Chromecast para Prometeo: buscar televisores/bocinas en tu Wi-Fi, conectar, mandar música, radio o TV,
 * y controlar play/pausa/adelantar. Usa el reproductor estándar de Google en el televisor.
 * Solo se conecta cuando el usuario toca "Transmitir".
 */
final class CastHelper {
    interface Events { void send(JSONObject o); }

    private final Activity act;
    private final Events events;
    private final Handler h = new Handler(Looper.getMainLooper());
    private CastContext cc;
    private boolean failed = false;
    private MediaRouter router;
    private MediaRouteSelector selector;
    private boolean scanning = false;
    private CastServer server;
    private RemoteMediaClient rmc;
    private volatile boolean sentSubs = false;
    private String kind = "";       /* music | radio | tv */
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    CastHelper(Activity act, Events events) { this.act = act; this.events = events; }

    private void emit(JSONObject o) { try { events.send(o); } catch (Exception ignored) { } }

    /** true si el teléfono tiene los servicios de Google para Chromecast */
    boolean available() {
        init();
        return cc != null;
    }

    private void init() {
        if (cc != null || failed) return;
        try {
            cc = CastContext.getSharedInstance(act);
            selector = cc.getMergedSelector();
            router = MediaRouter.getInstance(act);
            cc.getSessionManager().addSessionManagerListener(sessionListener, CastSession.class);
            CastSession cur = cc.getSessionManager().getCurrentCastSession();
            if (cur != null && cur.isConnected()) attach(cur);
        } catch (Throwable e) {
            failed = true; cc = null;
        }
    }

    /* ---------------- buscar aparatos ---------------- */
    private final MediaRouter.Callback routerCb = new MediaRouter.Callback() {
        @Override public void onRouteAdded(MediaRouter r, MediaRouter.RouteInfo route) { emitRoutes(); }
        @Override public void onRouteRemoved(MediaRouter r, MediaRouter.RouteInfo route) { emitRoutes(); }
        @Override public void onRouteChanged(MediaRouter r, MediaRouter.RouteInfo route) { emitRoutes(); }
    };

    void scan(boolean on) {
        init();
        if (router == null || selector == null) return;
        if (on && !scanning) { router.addCallback(selector, routerCb, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN); scanning = true; }
        else if (!on && scanning) { router.removeCallback(routerCb); scanning = false; }
        if (on) emitRoutes();
    }

    JSONArray routes() {
        JSONArray a = new JSONArray();
        if (router == null || selector == null) return a;
        for (MediaRouter.RouteInfo r : router.getRoutes()) {
            if (r.isDefault() || !r.isEnabled() || !r.matchesSelector(selector)) continue;
            JSONObject o = new JSONObject();
            try {
                o.put("id", r.getId());
                o.put("name", r.getName());
                o.put("desc", r.getDescription() == null ? "" : r.getDescription());
                o.put("on", r.isSelected());
            } catch (Exception ignored) { }
            a.put(o);
        }
        return a;
    }

    private void emitRoutes() {
        JSONObject o = new JSONObject();
        try { o.put("type", "castRoutes"); o.put("list", routes()); } catch (Exception ignored) { }
        emit(o);
    }

    boolean connect(String id) {
        init();
        if (router == null) return false;
        for (MediaRouter.RouteInfo r : router.getRoutes()) {
            if (r.getId().equals(id)) { r.select(); return true; }
        }
        return false;
    }

    void disconnect() {
        if (cc == null) return;
        try { cc.getSessionManager().endCurrentSession(true); } catch (Exception ignored) { }
    }

    /* ---------------- sesión ---------------- */
    private final SessionManagerListener<CastSession> sessionListener = new SessionManagerListener<CastSession>() {
        @Override public void onSessionStarting(CastSession s) { state("connecting", s); }
        @Override public void onSessionStarted(CastSession s, String id) { attach(s); state("connected", s); }
        @Override public void onSessionStartFailed(CastSession s, int err) { state("failed", s); }
        @Override public void onSessionEnding(CastSession s) { }
        @Override public void onSessionEnded(CastSession s, int err) { detach(); state("disconnected", s); }
        @Override public void onSessionResuming(CastSession s, String id) { }
        @Override public void onSessionResumed(CastSession s, boolean wasSuspended) { attach(s); state("connected", s); }
        @Override public void onSessionResumeFailed(CastSession s, int err) { detach(); state("disconnected", s); }
        @Override public void onSessionSuspended(CastSession s, int reason) { state("suspended", s); }
    };

    private void state(String st, CastSession s) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "cast"); o.put("state", st);
            String name = "";
            try { if (s != null && s.getCastDevice() != null) name = s.getCastDevice().getFriendlyName(); } catch (Exception ignored) { }
            o.put("device", name == null ? "" : name);
        } catch (Exception ignored) { }
        emit(o);
    }

    private final RemoteMediaClient.Callback mediaCb = new RemoteMediaClient.Callback() {
        @Override public void onStatusUpdated() { emitStatus(-1, -1); }
    };
    private final RemoteMediaClient.ProgressListener progressCb = new RemoteMediaClient.ProgressListener() {
        @Override public void onProgressUpdated(long pos, long dur) { emitStatus(pos, dur); }
    };

    private void attach(CastSession s) {
        RemoteMediaClient c = s.getRemoteMediaClient();
        if (c == rmc) return;
        detachClient();
        rmc = c;
        if (rmc != null) { rmc.registerCallback(mediaCb); rmc.addProgressListener(progressCb, 1000); }
    }

    private void detachClient() {
        if (rmc != null) { try { rmc.unregisterCallback(mediaCb); rmc.removeProgressListener(progressCb); } catch (Exception ignored) { } }
        rmc = null;
    }

    private void detach() {
        detachClient();
        if (server != null) { server.stop(); server = null; }
        keepAwake(false);
    }

    private void emitStatus(long pos, long dur) {
        if (rmc == null) return;
        JSONObject o = new JSONObject();
        try {
            int ps = rmc.getPlayerState();
            String st = ps == MediaStatus.PLAYER_STATE_PLAYING ? "playing"
                    : ps == MediaStatus.PLAYER_STATE_PAUSED ? "paused"
                    : ps == MediaStatus.PLAYER_STATE_BUFFERING || ps == MediaStatus.PLAYER_STATE_LOADING ? "buffering" : "idle";
            o.put("type", "castStatus"); o.put("state", st); o.put("kind", kind);
            /* De qué pedido es este estado: mientras el televisor siga con lo anterior, se informa el pedido anterior */
            MediaInfo mi = null;
            try { mi = rmc.getMediaInfo(); } catch (Exception ignored) { }
            boolean mine = mi != null && sentUrl != null && sentUrl.equals(mi.getContentId());
            o.put("req", mine ? sentReq : prevReq);
            if (ps == MediaStatus.PLAYER_STATE_IDLE) {
                int r = rmc.getIdleReason();
                o.put("idle", r == MediaStatus.IDLE_REASON_FINISHED ? "finished" : r == MediaStatus.IDLE_REASON_ERROR ? "error"
                        : r == MediaStatus.IDLE_REASON_CANCELED ? "canceled" : r == MediaStatus.IDLE_REASON_INTERRUPTED ? "interrupted" : "none");
            }
            o.put("pos", pos >= 0 ? pos : rmc.getApproximateStreamPosition());
            o.put("dur", dur >= 0 ? dur : rmc.getStreamDuration());
            try { CastSession cs = cc == null ? null : cc.getSessionManager().getCurrentCastSession(); if (cs != null) o.put("vol", Math.round(cs.getVolume() * 100)); } catch (Exception ignored) { }
            o.put("subs", sentSubs);
        } catch (Exception ignored) { }
        emit(o);
    }

    /* ---------------- mandar algo al televisor ---------------- */
    /**
     * o = {kind:'music'|'radio'|'tv', url?, mid?, ext?, mime?, title, artist?, pos?(ms), dur?(ms)}
     * Devuelve null si se mandó, o un texto con el problema.
     */
    String load(final JSONObject o) {
        CastSession s = cc == null ? null : cc.getSessionManager().getCurrentCastSession();
        if (s == null || !s.isConnected()) return "noSession";
        attach(s);
        if (rmc == null) return "noSession";
        kind = o.optString("kind", "radio");
        wantReq = o.optInt("req", 0);
        final int myGen = ++gen;
        String url = o.optString("url", "");
        String mime = o.optString("mime", "");
        boolean video = "video".equals(kind);
        boolean music = "music".equals(kind) || video;
        boolean relay = !music && o.optBoolean("relay", false);
        if (server != null && !"radio".equals(kind)) server.stopLive();   /* ya no se necesita la radio en pedacitos */
        if (music || relay) {
            try {
                if (server == null) server = new CastServer(act);
                server.start();
            } catch (Exception e) { return "server"; }
            if (CastServer.localIp() == null) return "noWifi";
            keepAwake(true);
        } else keepAwake(false);
        if (video) {
            /* v43: 🎬 video del teléfono (y sus subtítulos, si le cargaste unos) */
            long mid = o.optLong("mid", -1);
            String du = o.optString("uri", "");
            String key = o.optString("key", String.valueOf(mid));
            url = du.isEmpty() ? server.urlForVideo(mid, o.optString("ext", "mp4")) : server.urlForDoc(android.net.Uri.parse(du), o.optString("ext", "mp4"));
            if (url == null) return "noWifi";
            if (mime.isEmpty()) mime = "video/mp4";
            String subUrl = null;
            try {
                android.content.SharedPreferences vp = Videos.prefs(act);
                String su = vp.getString("s_" + key, null);
                if (su != null) subUrl = server.setSubtitle(Subs.prepare(act, android.net.Uri.parse(su), "c" + key, vp.getLong("o_" + key, 0)).vtt);
                else server.setSubtitle(null);
            } catch (Exception ignored) { }
            try { if (subUrl != null) o.put("subUrl", subUrl); } catch (Exception ignored) { }
            send(o, url, mime);
            return null;
        }
        if (music) {
            url = server.urlFor(o.optLong("mid", -1), o.optString("ext", "mp3"));
            if (url == null) return "noWifi";
            if (mime.isEmpty()) mime = "audio/mpeg";
            send(o, url, mime);
            return null;
        }
        if (!relay) {
            if (mime.isEmpty()) mime = guessMime(url, "tv".equals(kind));
            send(o, url, mime);
            return null;
        }
        /* Puente: el teléfono averigua qué es (lista HLS, lista de radio o audio directo) y se lo pasa al televisor */
        final String src = url;
        final String k = kind;
        new Thread(new Runnable() { public void run() {
            String[] info = null;
            String err = "";
            try { info = CastServer.probe(src); } catch (Exception ex) { err = Net.why(ex); }
            final String[] fi = info;
            final String why = err;
            h.post(new Runnable() { public void run() {
                if (myGen != gen) return;   /* ya se pidió otra cosa */
                CastServer sv = server;
                String u = null, mime = fi == null ? "" : fi[2], mode = "relay";
                /* Radio en chorro (MP3/AAC): en pedacitos, así el televisor arranca enseguida.
                   Si el pedido dice plain:true (ya se probó en pedacitos y no anduvo), va como chorro. */
                boolean audio = mime.equals("audio/mpeg") || mime.equals("audio/aac");
                if (fi != null && sv != null && "radio".equals(k) && "0".equals(fi[1]) && audio && !o.optBoolean("plain", false)) {
                    u = sv.hlsFor(fi[0], mime.equals("audio/aac"));
                    if (u != null) {
                        mode = "hls";
                        mime = "application/x-mpegURL";
                        try { o.put("seg", fi[2].equals("audio/aac") ? "aac" : "mp3"); } catch (Exception ignored) { }
                    }
                }
                if (u == null && fi != null && sv != null) {
                    u = sv.relayFor(fi[0], "1".equals(fi[1]) ? "m3u8" : CastServer.extOf(fi[0]));
                    try { if (fi.length > 3) o.put("seg", fi[3]); } catch (Exception ignored) { }
                }
                info(k, fi, mode, src, why);
                if (u == null) { loadError(k, 0); return; }
                send(o, u, mime);
            } });
        } }, "prometeo-cast-probe").start();
        return null;
    }

    private int gen = 0;
    private int sentReq = 0, prevReq = 0;
    private String sentUrl = null;

    private int wantReq = 0;

    /** Para el diagnóstico (botón v41): qué se averiguó de la emisora o canal y cómo se mandó */
    private void info(String k, String[] fi, String mode, String src, String why) {
        JSONObject e = new JSONObject();
        try {
            e.put("type", "castInfo"); e.put("kind", k); e.put("mode", fi == null ? "fail" : mode);
            e.put("mime", fi == null ? "" : fi[2]); e.put("seg", fi != null && fi.length > 3 ? fi[3] : "");
            String host = ""; try { host = new java.net.URL(src).getHost(); } catch (Exception ignored) { }
            e.put("host", host); e.put("req", wantReq); e.put("why", why == null ? "" : why);
        } catch (Exception ignored) { }
        emit(e);
    }

    private void loadError(String k, int code) {
        JSONObject e = new JSONObject();
        try { e.put("type", "castStatus"); e.put("state", "idle"); e.put("idle", "error"); e.put("kind", k); e.put("code", code); e.put("req", wantReq); } catch (Exception ignored) { }
        emit(e);
    }

    private void send(JSONObject o, String url, String mime) {
        if (rmc == null) { loadError(kind, -1); return; }
        if (sentReq != o.optInt("req", 0)) prevReq = sentReq;
        sentReq = o.optInt("req", 0);
        sentUrl = url;
        boolean video = "video".equals(kind);
        boolean music = "music".equals(kind) || video;
        MediaMetadata md = new MediaMetadata(video ? MediaMetadata.MEDIA_TYPE_MOVIE : ("music".equals(kind) ? MediaMetadata.MEDIA_TYPE_MUSIC_TRACK : ("tv".equals(kind) ? MediaMetadata.MEDIA_TYPE_MOVIE : MediaMetadata.MEDIA_TYPE_MUSIC_TRACK)));
        md.putString(MediaMetadata.KEY_TITLE, o.optString("title", "Prometeo"));
        String artist = o.optString("artist", "");
        if (!artist.isEmpty()) md.putString(music ? MediaMetadata.KEY_ARTIST : MediaMetadata.KEY_SUBTITLE, artist);
        MediaInfo.Builder mb = new MediaInfo.Builder(url)
                .setStreamType(music ? MediaInfo.STREAM_TYPE_BUFFERED : MediaInfo.STREAM_TYPE_LIVE)
                .setContentType(mime)
                .setMetadata(md);
        if (music && o.optLong("dur", 0) > 0) mb.setStreamDuration(o.optLong("dur"));
        String subUrl = o.optString("subUrl", "");
        sentSubs = video && !subUrl.isEmpty();
        if (video && !subUrl.isEmpty()) {
            MediaTrack tr = new MediaTrack.Builder(1, MediaTrack.TYPE_TEXT).setName("Subtítulos").setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                    .setContentId(subUrl).setContentType("text/vtt").setLanguage("es").build();
            java.util.List<MediaTrack> tl = new java.util.ArrayList<>(); tl.add(tr);
            mb.setMediaTracks(tl);
        }
        /* Listas HLS: decirle al televisor de qué son los pedacitos (sin esto, las de radio en AAC no suenan) */
        String seg = o.optString("seg", "");
        try {
            if (seg.equals("aac") || seg.equals("mp3")) mb.setHlsSegmentFormat(seg);
            else if (seg.equals("fmp4")) { mb.setHlsSegmentFormat("fmp4"); if ("tv".equals(kind)) mb.setHlsVideoSegmentFormat("fmp4"); }
        } catch (Throwable ignored) { }
        MediaLoadRequestData.Builder rb = new MediaLoadRequestData.Builder()
                .setMediaInfo(mb.build())
                .setAutoplay(true)
                .setCurrentTime(music ? Math.max(0, o.optLong("pos", 0)) : 0);
        if (video && !subUrl.isEmpty()) rb.setActiveTrackIds(new long[]{ 1 });
        MediaLoadRequestData req = rb.build();
        final String k = kind;
        try {
            rmc.load(req).setResultCallback(new ResultCallback<RemoteMediaClient.MediaChannelResult>() {
                @Override public void onResult(RemoteMediaClient.MediaChannelResult r) {
                    if (r.getStatus().isSuccess()) return;
                    loadError(k, r.getStatus().getStatusCode());
                }
            });
        } catch (Exception e) { loadError(k, -2); }
    }

    private static String guessMime(String url, boolean tv) {
        String u = url.toLowerCase(java.util.Locale.ROOT);
        int q = u.indexOf('?'); if (q >= 0) u = u.substring(0, q);
        if (u.contains(".m3u8")) return "application/x-mpegURL";
        if (u.endsWith(".mpd")) return "application/dash+xml";
        if (u.endsWith(".mp4") || u.endsWith(".m4v")) return "video/mp4";
        if (u.endsWith(".ts")) return "video/mp2t";
        if (u.endsWith(".aac") || u.endsWith(".aacp")) return "audio/aac";
        if (u.endsWith(".ogg") || u.endsWith(".opus")) return "audio/ogg";
        if (u.endsWith(".mp3")) return "audio/mpeg";
        return tv ? "application/x-mpegURL" : "audio/mpeg";
    }

    void command(String c, long ms) {
        if (rmc == null) return;
        try {
            switch (c) {
                case "play": rmc.play(); break;
                case "pause": rmc.pause(); break;
                case "toggle": if (rmc.isPlaying()) rmc.pause(); else rmc.play(); break;
                case "stop": rmc.stop(); keepAwake(false); if (server != null) { server.stopLive(); server.closeClients(); } break;
                case "seek": rmc.seek(new MediaSeekOptions.Builder().setPosition(Math.max(0, ms)).build()); break;
                /* v44: control del video en el TV */
                case "volume": { CastSession s = cc == null ? null : cc.getSessionManager().getCurrentCastSession(); if (s != null) s.setVolume(Math.max(0, Math.min(100, ms)) / 100.0); break; }
                case "subs": rmc.setActiveMediaTracks(ms > 0 ? new long[]{ 1 } : new long[0]); break;
                default: break;
            }
        } catch (Exception ignored) { }
    }

    boolean isConnected() {
        if (cc == null) return false;
        CastSession s = cc.getSessionManager().getCurrentCastSession();
        return s != null && s.isConnected();
    }

    String deviceName() {
        try { CastSession s = cc.getSessionManager().getCurrentCastSession(); return s.getCastDevice().getFriendlyName(); }
        catch (Exception e) { return ""; }
    }

    /* Mientras el Chromecast toca tu música, el teléfono no se duerme ni apaga el Wi-Fi (le está pasando la canción) */
    @SuppressWarnings("deprecation")
    private void keepAwake(boolean on) {
        try {
            if (on) {
                if (wake == null) {
                    PowerManager pm = (PowerManager) act.getSystemService(Context.POWER_SERVICE);
                    wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "prometeo:cast");
                    wake.setReferenceCounted(false);
                }
                if (!wake.isHeld()) wake.acquire(6 * 60 * 60 * 1000L);
                if (wifi == null) {
                    WifiManager wm = (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "prometeo:cast");
                    wifi.setReferenceCounted(false);
                }
                if (!wifi.isHeld()) wifi.acquire();
            } else {
                if (wake != null && wake.isHeld()) wake.release();
                if (wifi != null && wifi.isHeld()) wifi.release();
            }
        } catch (Exception ignored) { }
    }

    void release() {
        scan(false);
        detach();
        try { if (cc != null) cc.getSessionManager().removeSessionManagerListener(sessionListener, CastSession.class); } catch (Exception ignored) { }
    }
}
