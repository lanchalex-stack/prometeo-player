package com.prometeo.music;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

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
import androidx.media3.ui.PlayerView;

/**
 * Pantalla completa para ver un canal de TV / IPTV con ExoPlayer (AndroidX Media3, licencia Apache 2.0).
 * Solo se conecta a la dirección del canal que eligió el usuario.
 */
@OptIn(markerClass = UnstableApi.class)
public class VideoActivity extends Activity {
    private ExoPlayer player;
    private PlayerView view;
    private TextView msg;
    private boolean wasPlaying = true;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        String url = getIntent().getStringExtra("url");
        String title = getIntent().getStringExtra("title");
        final String errorText = getIntent().getStringExtra("error");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        view = new PlayerView(this);
        view.setKeepContentOnPlayerReset(true);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (title != null && !title.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(title);
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            t.setShadowLayer(6f, 0f, 1f, Color.BLACK);
            int p = dp(14);
            t.setPadding(p, p, p, p);
            root.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        }

        msg = new TextView(this);
        msg.setTextColor(Color.WHITE);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(dp(24), dp(24), dp(24), dp(24));
        msg.setVisibility(View.GONE);
        root.addView(msg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        setContentView(root);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("PrometeoPlayer/41")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http)))
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .build();
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                msg.setText((errorText != null ? errorText : "No se pudo abrir el canal") + "\n(" + error.getErrorCodeName() + ")");
                msg.setVisibility(View.VISIBLE);
            }
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_READY) msg.setVisibility(View.GONE);
            }
        });
        view.setPlayer(player);

        if (url == null || url.isEmpty()) { finish(); return; }
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null && wasPlaying) player.play();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) { wasPlaying = player.isPlaying(); player.pause(); }
    }

    @Override
    protected void onDestroy() {
        if (view != null) view.setPlayer(null);
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
