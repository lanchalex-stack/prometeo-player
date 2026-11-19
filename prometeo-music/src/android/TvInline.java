package com.prometeo.music;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

/**
 * Reproductor de TV pequeño, dentro de la app (encima de la página, en el recuadro que la página le indica).
 * Los botones (▲ ▼ ⏸ ⛶ ✕) son de la página; aquí solo está el video. Con ⛶ se abre VideoActivity a pantalla completa.
 */
@OptIn(markerClass = UnstableApi.class)
final class TvInline {
    interface Events { void send(JSONObject o); }

    private final Activity act;
    private final View web;
    private final Events events;
    private final Handler h = new Handler(Looper.getMainLooper());
    private FrameLayout box;
    private PlayerView view;
    private ExoPlayer player;
    private String url;
    private int retry = 0;
    private static final long[] RETRY = {2000, 5000, 10000, 20000, 30000};
    private final Runnable retryRun = new Runnable() { public void run() { load(); } };

    TvInline(Activity act, View web, Events events) { this.act = act; this.web = web; this.events = events; }

    private void emit(String state, String extra) {
        JSONObject o = new JSONObject();
        try { o.put("type", "inline"); o.put("state", state); if (extra != null) o.put("code", extra); o.put("u", url == null ? "" : url); } catch (Exception ignored) { }
        try { events.send(o); } catch (Exception ignored) { }
    }

    private void ensure() {
        if (box != null) return;
        box = new FrameLayout(act);
        box.setBackgroundColor(Color.BLACK);
        final float r = 14 * act.getResources().getDisplayMetrics().density;
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        box.setClipToOutline(true);
        view = new PlayerView(act);
        view.setUseController(false);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        view.setShutterBackgroundColor(Color.BLACK);
        view.setKeepContentOnPlayerReset(true);
        box.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        /* Tocar el video = pantalla completa */
        View tap = new View(act);
        tap.setOnClickListener(v -> emit("tap", null));
        box.addView(tap, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.setVisibility(View.GONE);
        ViewGroup content = act.findViewById(android.R.id.content);
        content.addView(box, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.START));

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("PrometeoPlayer/41").setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000).setReadTimeoutMs(20000);
        player = new ExoPlayer.Builder(act)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(act, http)))
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .build();
        player.setHandleAudioBecomingNoisy(true);
        fxSession = AudioFx.newSession(act);
        if (fxSession > 0) { player.setAudioSessionId(fxSession); AudioFx.attach(fxSession); }   /* 🎚️ ecualizador */
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean playing) { if (playing) { retry = 0; emit("playing", null); } else if (player != null && player.getPlaybackState() == Player.STATE_READY) emit("paused", null); }
            @Override public void onPlaybackStateChanged(int s) { if (s == Player.STATE_BUFFERING && retry == 0) emit("buffering", null); }
            @Override public void onPlayerError(PlaybackException e) {
                if (PrometeoMusic.isNetworkProblem(e) && retry < RETRY.length) {
                    h.removeCallbacks(retryRun); h.postDelayed(retryRun, RETRY[retry++]);
                    emit("reconnecting", String.valueOf(retry));
                } else emit("error", e.getErrorCodeName());
            }
        });
        view.setPlayer(player);
    }

    /** Ubica el recuadro: r = {x, y, w, h, vw} en píxeles de la página (vw = ancho de la página) */
    void place(JSONObject r) {
        if (box == null) return;
        if (r == null || !r.optBoolean("show", true) || r.optDouble("w", 0) <= 0) { box.setVisibility(View.GONE); return; }
        float scale = web.getWidth() > 0 && r.optDouble("vw", 0) > 0 ? (float) (web.getWidth() / r.optDouble("vw")) : act.getResources().getDisplayMetrics().density;
        int[] wl = new int[2], pl = new int[2];
        web.getLocationInWindow(wl);
        ((View) box.getParent()).getLocationInWindow(pl);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) box.getLayoutParams();
        p.width = Math.round((float) r.optDouble("w") * scale);
        p.height = Math.round((float) r.optDouble("h") * scale);
        p.leftMargin = wl[0] - pl[0] + Math.round((float) r.optDouble("x") * scale);
        p.topMargin = wl[1] - pl[1] + Math.round((float) r.optDouble("y") * scale);
        p.gravity = Gravity.TOP | Gravity.START;
        box.setLayoutParams(p);
        box.setVisibility(View.VISIBLE);
        box.bringToFront();
    }

    void open(String u, JSONObject rect) {
        ensure();
        url = u; retry = 0;
        place(rect);
        load();
    }

    private void load() {
        if (player == null || url == null) return;
        h.removeCallbacks(retryRun);
        player.stop();
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    void command(String c) {
        if (player == null) return;
        if ("pause".equals(c)) player.pause();
        else if ("play".equals(c) || ("toggle".equals(c) && !player.isPlaying())) {
            if (player.getPlaybackState() == Player.STATE_IDLE || player.getPlaybackState() == Player.STATE_ENDED) { load(); return; }
            if (player.isCurrentMediaItemLive()) player.seekToDefaultPosition();   /* en vivo: vuelve al presente */
            player.play();
        } else if ("toggle".equals(c)) player.pause();
    }

    /** La app pasó a segundo plano o se abrió la pantalla completa */
    void pause() { if (player != null) player.pause(); }

    void close() {
        h.removeCallbacks(retryRun);
        url = null;
        if (player != null) { player.stop(); player.clearMediaItems(); }
        if (box != null) box.setVisibility(View.GONE);
    }

    private int fxSession = 0;

    void release() {
        h.removeCallbacks(retryRun);
        if (view != null) view.setPlayer(null);
        if (player != null) { player.release(); player = null; }
        if (fxSession > 0) { AudioFx.detach(fxSession); fxSession = 0; }
        if (box != null && box.getParent() != null) ((ViewGroup) box.getParent()).removeView(box);
        box = null; view = null;
    }
}
