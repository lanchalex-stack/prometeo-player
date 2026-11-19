package com.prometeo.music;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Rational;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Reproductor de TV / IPTV de Prometeo (v41) con ExoPlayer (AndroidX Media3, licencia Apache 2.0).
 * Cambiar de canal (botones y deslizando), lista de canales, ajuste de pantalla, brillo y volumen con gestos,
 * reconexión automática, favoritos, calidad, audio y subtítulos, bloqueo de pantalla, temporizador,
 * ventana flotante (PiP) y "solo audio". Solo se conecta a la dirección del canal que eligió el usuario.
 */
@OptIn(markerClass = UnstableApi.class)
public class VideoActivity extends Activity {

    /* ---- Comunicación con la página (favoritos, último canal, solo audio, ajustes) ---- */
    interface Events { void send(JSONObject o); }
    static volatile Events events;
    /** Lista de canales que manda la página (puede ser grande: no va dentro del Intent) */
    static volatile JSONObject pending;

    private static final class Ch { String n, u, g; boolean f; }

    private final List<Ch> list = new ArrayList<>();
    private int idx = 0;
    private JSONObject S = new JSONObject();
    private String aspect = "fit", quality = "auto";

    private ExoPlayer player;
    private PlayerView view;
    private FrameLayout root, videoBox;
    private final Handler h = new Handler(Looper.getMainLooper());

    /* controles */
    private LinearLayout topBar, centerBar, bottomBar;
    private TextView tTitle, tSub, bStar, bAspect, bPlay, tNext, bWindow;
    private boolean controlsShown = true;

    /* avisos */
    private LinearLayout msgBox; private TextView msgTitle, msgSub; private ProgressBar msgSpin;
    private LinearLayout swipeBox; private TextView swipeHint, swipeName, swipeSub;
    private LinearLayout levelBox; private TextView levelIcon, levelText; private View levelFill; private FrameLayout levelTrack;
    private TextView toastView;

    /* bloqueo */
    private boolean locked = false;
    private TextView lockBtn, lockHint;

    /* lista de canales */
    private LinearLayout panel; private EditText panelSearch; private LinearLayout panelChips; private ListView panelList;
    private final List<Integer> shown = new ArrayList<>();
    private String panelGroup = "";   /* "" todos, "★" favoritos, o el nombre del grupo */
    private ChAdapter adapter;

    /* ajustes */
    private FrameLayout sheetScrim; private LinearLayout sheet; private TextView sleepLeft;

    /* reconexión */
    private static final long[] RETRY = {2000, 5000, 10000, 20000, 30000, 30000, 30000};
    private int retryCount = 0;
    private boolean bad = false;

    /* temporizador */
    private long sleepEnd = 0;
    private int sleepMin = 0;

    /* PiP */
    private boolean inPip = false;
    private static final String PIP_PREV = "com.prometeo.music.TV_PREV", PIP_TOGGLE = "com.prometeo.music.TV_TOGGLE", PIP_NEXT = "com.prometeo.music.TV_NEXT";
    private BroadcastReceiver pipReceiver;

    private final Runnable hideControls = new Runnable() { public void run() { if (player != null && player.isPlaying() && !panelOpen() && !sheetOpen()) setControls(false); } };
    private final Runnable retryRun = new Runnable() { public void run() { if (player != null) { startCurrent(false); } } };
    private final Runnable sleepTick = new Runnable() {
        public void run() {
            if (sleepEnd == 0) return;
            long left = sleepEnd - SystemClock.elapsedRealtime();
            if (left <= 0) { sleepEnd = 0; if (player != null) player.pause(); finish(); return; }
            if (sleepLeft != null) sleepLeft.setText(fmt(s("sleepIn", "Se apaga en {0}"), mmss(left)));
            h.postDelayed(this, 1000);
        }
    };

    /* ===================================================================== */

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);   /* los botones de volumen controlan el canal */
        readIntent(getIntent());
        buildUi();
        buildPlayer();
        immersive();
        startCurrent(true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIntent(intent);
        closePanel(); closeSheet();
        startCurrent(true);
    }

    private void readIntent(Intent in) {
        list.clear();
        JSONObject o = pending; pending = null;
        if (o != null) {
            JSONArray a = o.optJSONArray("list");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i); if (c == null) continue;
                Ch ch = new Ch(); ch.n = c.optString("n", ""); ch.u = c.optString("u", ""); ch.g = c.optString("g", ""); ch.f = c.optBoolean("f", false);
                if (ch.u.length() > 0) list.add(ch);
            }
            idx = Math.max(0, Math.min(o.optInt("i", 0), Math.max(0, list.size() - 1)));
            JSONObject st = o.optJSONObject("s"); if (st != null) S = st;
            aspect = o.optString("aspect", "fit");
            quality = o.optString("quality", "auto");
        }
        if (list.isEmpty()) {
            /* Compatibilidad: un solo canal por el Intent */
            Ch ch = new Ch(); ch.u = in.getStringExtra("url"); ch.n = in.getStringExtra("title"); ch.g = "";
            if (ch.u != null && ch.u.length() > 0) { if (ch.n == null) ch.n = ""; list.add(ch); }
            idx = 0;
            String err = in.getStringExtra("error");
            if (err != null && err.length() > 0) try { S.put("fail", err); } catch (Exception ignored) { }
        }
        if (list.isEmpty()) finish();
    }

    /* ============================ TEXTOS ============================ */
    private String s(String k, String def) { String v = S.optString(k, ""); return v.length() > 0 ? v : def; }
    private static String fmt(String t, Object... a) { for (int i = 0; i < a.length; i++) t = t.replace("{" + i + "}", String.valueOf(a[i])); return t; }
    private static String mmss(long ms) { long sec = Math.max(0, ms / 1000); return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60); }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /* ============================ INTERFAZ ============================ */
    private static final int CYAN = 0xFF00E5FF, GOLD = 0xFFFFC83C, DARK = 0x8C000000, PANEL = 0xF00E1014, LINE = 0xFF2C2C2C;

    private GradientDrawable round(int color, float radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private TextView text(String t, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextColor(color); v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setShadowLayer(4f, 0f, 1f, 0x99000000);
        return v;
    }

    /** Botón redondo o "píldora" */
    private TextView btn(String t, int sizeDp, int bg, int fg, String desc, View.OnClickListener l) {
        TextView b = text(t, sizeDp >= 60 ? 24 : 17, fg, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(bg, sizeDp / 2f, 0));
        b.setContentDescription(desc);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return b;
    }

    private TextView pill(String t, int bg, int fg, int stroke, View.OnClickListener l) {
        TextView b = text(t, 13, fg, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setBackground(round(bg, 20, stroke));
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        return b;
    }

    private static LinearLayout.LayoutParams margins(View v, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
        if (p == null) p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b); v.setLayoutParams(p); return p;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        videoBox = new FrameLayout(this);
        view = new PlayerView(this);
        view.setUseController(false);
        view.setKeepContentOnPlayerReset(true);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        view.setShutterBackgroundColor(Color.BLACK);
        videoBox.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addView(videoBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        /* capa de gestos (debajo de los botones) */
        View touch = new View(this);
        touch.setOnTouchListener(new Gestures());
        root.addView(touch, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        /* ---- barra de arriba ---- */
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(12), dp(16), dp(24));
        topBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB3000000, 0x00000000}));
        TextView back = btn("‹", 40, DARK, Color.WHITE, s("back", "Volver"), v -> finish());
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        topBar.addView(back);
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        tTitle = text("", 16, Color.WHITE, true); tTitle.setSingleLine(true); tTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tSub = text("", 12, 0xFFC8D3E0, false); tSub.setSingleLine(true); tSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(tTitle); titles.addView(tSub);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMargins(dp(10), 0, dp(6), 0);
        topBar.addView(titles, tp);
        bStar = btn("☆", 40, DARK, GOLD, s("favAdd", "Agregar a Favoritos"), v -> toggleFav());
        topBar.addView(bStar); margins(bStar, dp(4), 0, dp(4), 0);
        bAspect = pill("⛶ " + aspectName(aspect), DARK, Color.WHITE, 0, v -> cycleAspect());
        topBar.addView(bAspect); margins(bAspect, dp(4), 0, dp(4), 0);
        TextView bSet = btn("⚙", 40, DARK, Color.WHITE, s("settings", "Ajustes"), v -> openSheet());
        topBar.addView(bSet); margins(bSet, dp(4), 0, dp(4), 0);
        TextView bLock = btn("🔒", 40, DARK, Color.WHITE, s("lock", "Bloquear pantalla"), v -> setLocked(true));
        bLock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        topBar.addView(bLock); margins(bLock, dp(4), 0, dp(4), 0);
        TextView bList = btn("☰", 40, CYAN, Color.BLACK, s("channels", "Canales"), v -> openPanel());
        topBar.addView(bList); margins(bList, dp(4), 0, 0, 0);
        root.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        /* ---- centro: anterior / pausa / siguiente ---- */
        centerBar = new LinearLayout(this);
        centerBar.setOrientation(LinearLayout.HORIZONTAL);
        centerBar.setGravity(Gravity.CENTER);
        TextView bPrev = btn("▲", 54, DARK, Color.WHITE, s("prevCh", "Canal anterior"), v -> change(-1));
        bPlay = btn("⏸", 72, CYAN, Color.BLACK, s("pause", "Pausa"), v -> togglePlay());
        TextView bNextB = btn("▼", 54, DARK, Color.WHITE, s("nextCh", "Canal siguiente"), v -> change(1));
        centerBar.addView(bPrev); centerBar.addView(bPlay); centerBar.addView(bNextB);
        margins(bPlay, dp(34), 0, dp(34), 0);
        root.addView(centerBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        /* ---- barra de abajo ---- */
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(16), dp(24), dp(16), dp(12));
        bottomBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB3000000, 0x00000000}));
        tNext = text("", 12, 0xFFC8D3E0, false); tNext.setSingleLine(true); tNext.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bottomBar.addView(tNext, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView bSleep = pill("🌙 " + s("sleep", "Dormir"), DARK, Color.WHITE, 0, v -> openSheet());
        bottomBar.addView(bSleep); margins(bSleep, dp(8), 0, 0, 0);
        bWindow = pill("⧉ " + s("window", "Ventana"), DARK, Color.WHITE, 0, v -> enterPip());
        bottomBar.addView(bWindow); margins(bWindow, dp(8), 0, 0, 0);
        if (!pipSupported()) bWindow.setVisibility(View.GONE);
        TextView bAudio = pill("🎧 " + s("audioOnly", "Solo audio"), DARK, CYAN, CYAN, v -> audioOnly());
        bottomBar.addView(bAudio); margins(bAudio, dp(8), 0, 0, 0);
        root.addView(bottomBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        /* ---- aviso: reconectando / error ---- */
        msgBox = new LinearLayout(this);
        msgBox.setOrientation(LinearLayout.HORIZONTAL);
        msgBox.setGravity(Gravity.CENTER_VERTICAL);
        msgBox.setPadding(dp(18), dp(12), dp(18), dp(12));
        msgBox.setBackground(round(0x99000000, 16, 0x80FFAA3C));
        msgSpin = new ProgressBar(this);
        msgBox.addView(msgSpin, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout mt = new LinearLayout(this); mt.setOrientation(LinearLayout.VERTICAL);
        msgTitle = text("", 14, Color.WHITE, true); msgSub = text("", 12, 0xFFF3DCC0, false);
        mt.addView(msgTitle); mt.addView(msgSub);
        LinearLayout.LayoutParams mtp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); mtp.setMargins(dp(12), 0, 0, 0);
        msgBox.addView(mt, mtp);
        msgBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        mp.topMargin = dp(120);
        root.addView(msgBox, mp);

        /* ---- vista previa al deslizar para cambiar de canal ---- */
        swipeBox = new LinearLayout(this);
        swipeBox.setOrientation(LinearLayout.VERTICAL);
        swipeBox.setGravity(Gravity.CENTER_HORIZONTAL);
        swipeBox.setPadding(dp(16), dp(10), dp(16), dp(10));
        swipeBox.setBackground(round(0x8C000000, 14, 0x8000E5FF));
        swipeHint = text("", 11, 0xFF9FD8DF, false); swipeName = text("", 15, Color.WHITE, true); swipeSub = text("", 11, 0xFFC8D3E0, false);
        swipeBox.addView(swipeHint); swipeBox.addView(swipeName); swipeBox.addView(swipeSub);
        swipeBox.setVisibility(View.GONE);
        root.addView(swipeBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        /* ---- nivel de brillo / volumen ---- */
        levelBox = new LinearLayout(this);
        levelBox.setOrientation(LinearLayout.VERTICAL);
        levelBox.setGravity(Gravity.CENTER_HORIZONTAL);
        levelBox.setPadding(dp(12), dp(12), dp(12), dp(12));
        levelBox.setBackground(round(0x8C000000, 16, 0));
        levelIcon = text("", 18, Color.WHITE, false);
        levelTrack = new FrameLayout(this);
        levelTrack.setBackground(round(0x33FFFFFF, 4, 0));
        levelFill = new View(this);
        levelTrack.addView(levelFill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM));
        levelText = text("", 13, Color.WHITE, true);
        levelBox.addView(levelIcon);
        LinearLayout.LayoutParams ltp = new LinearLayout.LayoutParams(dp(8), dp(140)); ltp.setMargins(0, dp(8), 0, dp(8));
        levelBox.addView(levelTrack, ltp);
        levelBox.addView(levelText);
        levelBox.setVisibility(View.GONE);
        root.addView(levelBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.START));

        /* ---- bloqueo ---- */
        lockBtn = btn("🔒", 56, 0x8C000000, CYAN, s("unlock", "Desbloquear pantalla"), v -> { showLockHint(); });
        lockBtn.setBackground(round(0x8C000000, 28, CYAN));
        lockBtn.setOnLongClickListener(v -> { setLocked(false); return true; });
        lockBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams lbp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER_VERTICAL | Gravity.START); lbp.leftMargin = dp(22);
        root.addView(lockBtn, lbp);
        lockHint = text(s("lockedHint", "Pantalla bloqueada · mantén el candado para desbloquear"), 12, 0xFFCFEFF4, false);
        lockHint.setPadding(dp(11), dp(7), dp(11), dp(7));
        lockHint.setBackground(round(0x8C000000, 10, 0));
        lockHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams lhp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.START); lhp.leftMargin = dp(90);
        root.addView(lockHint, lhp);

        /* ---- avisito corto ---- */
        toastView = text("", 13, Color.WHITE, true);
        toastView.setPadding(dp(14), dp(8), dp(14), dp(8));
        toastView.setBackground(round(0xCC000000, 18, 0));
        toastView.setVisibility(View.GONE);
        FrameLayout.LayoutParams tvp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); tvp.bottomMargin = dp(80);
        root.addView(toastView, tvp);

        buildPanel();
        buildSheet();

        /* Notch / barras del sistema: los controles no quedan debajo */
        root.setOnApplyWindowInsetsListener((v, ins) -> { padForInsets(ins); return ins; });
        setContentView(root);
        applyAspect();
    }

    @SuppressWarnings("deprecation")
    private void padForInsets(WindowInsets ins) {
        int l = 0, t = 0, r = 0, b = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets c = ins.getInsets(WindowInsets.Type.displayCutout());
            l = c.left; t = c.top; r = c.right; b = c.bottom;
        } else if (Build.VERSION.SDK_INT >= 28 && ins.getDisplayCutout() != null) {
            l = ins.getDisplayCutout().getSafeInsetLeft(); t = ins.getDisplayCutout().getSafeInsetTop();
            r = ins.getDisplayCutout().getSafeInsetRight(); b = ins.getDisplayCutout().getSafeInsetBottom();
        }
        topBar.setPadding(dp(16) + l, dp(12) + t, dp(16) + r, dp(24));
        bottomBar.setPadding(dp(16) + l, dp(24), dp(16) + r, dp(12) + b);
        panel.setPadding(dp(14), dp(14) + t, dp(14) + r, dp(10) + b);
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    private void toast(String t) {
        toastView.setText(t);
        toastView.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideToast);
        h.postDelayed(hideToast, 1800);
    }
    private final Runnable hideToast = new Runnable() { public void run() { toastView.setVisibility(View.GONE); } };

    /* ============================ REPRODUCTOR ============================ */
    private void buildPlayer() {
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
        fxSession = AudioFx.newSession(this);
        if (fxSession > 0) { player.setAudioSessionId(fxSession); AudioFx.attach(fxSession); }   /* 🎚️ ecualizador */
        /* Subtítulos apagados al empezar (se prenden desde Ajustes) */
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        applyQuality();
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                if (PrometeoMusic.isNetworkProblem(error) && retryCount < RETRY.length) {
                    long d = RETRY[retryCount++];
                    showMsg(true, "📡 " + s("reconnecting", "Sin señal, reconectando…"),
                            fmt(s("attempt", "Intento {0} · vuelve solo cuando regrese la señal"), retryCount));
                    h.removeCallbacks(retryRun);
                    h.postDelayed(retryRun, d);
                } else {
                    bad = true;
                    showMsg(false, s("fail", "No se pudo abrir el canal"), "(" + error.getErrorCodeName() + ") · " + s("tryOther", "Prueba con otro canal ▲ ▼"));
                    setControls(true);
                }
            }
            @Override public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_READY) { retryCount = 0; bad = false; msgBox.setVisibility(View.GONE); }
                else if (st == Player.STATE_ENDED && player.isCurrentMediaItemLive() && retryCount < RETRY.length) {
                    /* Un canal en vivo no "termina": si se cortó, se reconecta */
                    h.removeCallbacks(retryRun); h.postDelayed(retryRun, RETRY[retryCount++]);
                    showMsg(true, "📡 " + s("reconnecting", "Sin señal, reconectando…"), fmt(s("attempt", "Intento {0} · vuelve solo cuando regrese la señal"), retryCount));
                }
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                bPlay.setText(playing ? "⏸" : "▶");
                bPlay.setContentDescription(playing ? s("pause", "Pausa") : s("play", "Reproducir"));
                updatePip();
                if (playing) scheduleHide(); else h.removeCallbacks(hideControls);
            }
            @Override public void onVideoSizeChanged(VideoSize vs) { applyAspect(); updatePip(); }
            @Override public void onTracksChanged(Tracks tracks) { if (sheetOpen()) fillSheet(); }
        });
        view.setPlayer(player);
    }

    private void showMsg(boolean spinning, String title, String sub) {
        msgSpin.setVisibility(spinning ? View.VISIBLE : View.GONE);
        msgTitle.setText(title); msgSub.setText(sub);
        msgBox.setVisibility(View.VISIBLE);
    }

    private Ch cur() { return list.isEmpty() ? null : list.get(idx); }

    /** Pone el canal actual. fresh = canal nuevo (no un reintento) */
    private void startCurrent(boolean fresh) {
        Ch c = cur(); if (c == null || player == null) return;
        h.removeCallbacks(retryRun);
        if (fresh) {
            retryCount = 0; bad = false; msgBox.setVisibility(View.GONE);
            /* Al cambiar de canal, audio y subtítulos vuelven a lo normal */
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO).clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            JSONObject e = new JSONObject();
            try { e.put("type", "watch"); e.put("u", c.u); e.put("n", c.n); e.put("g", c.g); e.put("i", idx); } catch (Exception ignored) { }
            emit(e);
        }
        player.stop();
        player.setMediaItem(MediaItem.fromUri(c.u));
        player.prepare();
        player.setPlayWhenReady(true);
        updateTexts();
        setControls(true);
    }

    private void updateTexts() {
        Ch c = cur(); if (c == null) return;
        tTitle.setText(c.n);
        StringBuilder sb = new StringBuilder();
        if (c.g != null && c.g.length() > 0) sb.append(c.g).append(" · ");
        sb.append("● ").append(s("live", "EN VIVO"));
        if (list.size() > 1) sb.append(" · ").append(fmt(s("nOfM", "{0} de {1}"), idx + 1, list.size()));
        tSub.setText(sb.toString());
        bStar.setText(c.f ? "★" : "☆");
        bStar.setContentDescription(c.f ? s("favDel", "Quitar de Favoritos") : s("favAdd", "Agregar a Favoritos"));
        if (list.size() > 1) tNext.setText(s("next", "Siguiente:") + " " + list.get((idx + 1) % list.size()).n);
        else tNext.setText("");
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void change(int dir) {
        if (list.size() < 2) { toast(s("oneCh", "Solo hay un canal en esta lista")); return; }
        idx = (idx + dir + list.size()) % list.size();
        startCurrent(true);
    }

    private void playIndex(int i) {
        if (i < 0 || i >= list.size()) return;
        idx = i; startCurrent(true);
    }

    private void togglePlay() {
        if (player == null) return;
        if (bad || player.getPlaybackState() == Player.STATE_IDLE || player.getPlaybackState() == Player.STATE_ENDED) { startCurrent(false); return; }
        if (player.isPlaying()) player.pause();
        else {
            /* En vivo: al seguir, vuelve al presente */
            if (player.isCurrentMediaItemLive()) player.seekToDefaultPosition();
            player.play();
        }
        setControls(true);
    }

    private void toggleFav() {
        Ch c = cur(); if (c == null) return;
        c.f = !c.f;
        JSONObject e = new JSONObject();
        try { e.put("type", "fav"); e.put("u", c.u); e.put("n", c.n); e.put("g", c.g); e.put("on", c.f); } catch (Exception ignored) { }
        emit(e);
        toast((c.f ? "★ " + s("favAdded", "Agregado a Favoritos") : "☆ " + s("favRemoved", "Quitado de Favoritos")));
        updateTexts();
    }

    private void emit(JSONObject e) { Events ev = events; if (ev != null) try { ev.send(e); } catch (Exception ignored) { } }

    private void saveSettings() {
        JSONObject e = new JSONObject();
        try { e.put("type", "settings"); e.put("aspect", aspect); e.put("quality", quality); } catch (Exception ignored) { }
        emit(e);
    }

    /* ============================ CONTROLES ============================ */
    private void setControls(boolean show) {
        controlsShown = show && !locked && !inPip;
        int v = controlsShown ? View.VISIBLE : View.GONE;
        topBar.setVisibility(v); centerBar.setVisibility(v); bottomBar.setVisibility(v);
        if (controlsShown) scheduleHide();
    }

    private void scheduleHide() { h.removeCallbacks(hideControls); h.postDelayed(hideControls, 4000); }

    private void setLocked(boolean on) {
        locked = on;
        closePanel(); closeSheet();
        if (on) { setControls(false); lockBtn.setVisibility(View.VISIBLE); showLockHint(); }
        else { lockBtn.setVisibility(View.GONE); lockHint.setVisibility(View.GONE); setControls(true); toast("🔓 " + s("unlocked", "Pantalla desbloqueada")); }
    }

    private final Runnable hideLock = new Runnable() { public void run() { if (locked) { lockBtn.setVisibility(View.GONE); lockHint.setVisibility(View.GONE); } } };
    private void showLockHint() {
        lockBtn.setVisibility(View.VISIBLE); lockHint.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideLock); h.postDelayed(hideLock, 3000);
    }

    /* ============================ GESTOS ============================ */
    private final class Gestures implements View.OnTouchListener {
        private final GestureDetector gd;
        private int zone = 0;          /* 1 brillo, 2 canal, 3 volumen */
        private float startX, startY, startLevel, total;
        private boolean scrolling = false, ignore = false;
        Gestures() {
            gd = new GestureDetector(VideoActivity.this, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (locked) { showLockHint(); return true; }
                    if (panelOpen()) { closePanel(); return true; }
                    setControls(!controlsShown); return true;
                }
            });
        }
        @Override public boolean onTouch(View v, MotionEvent e) {
            if (locked || inPip) { gd.onTouchEvent(e); return true; }
            gd.onTouchEvent(e);
            int w = v.getWidth(), hh = Math.max(1, v.getHeight());
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    zone = e.getX() < w / 3f ? 1 : (e.getX() > w * 2f / 3f ? 3 : 2);
                    startX = e.getX(); startY = e.getY(); total = 0; scrolling = false;
                    /* Cerca del borde de arriba o de abajo es el gesto de Android (barras del sistema): no se toca */
                    ignore = startY < dp(48) || startY > hh - dp(48);
                    startLevel = zone == 1 ? getBrightness() : (zone == 3 ? getVolume() : 0);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (ignore) return true;
                    float dy = startY - e.getY();     /* positivo = hacia arriba */
                    float dx = e.getX() - startX;
                    if (!scrolling) {
                        if (Math.abs(dy) < dp(18)) return true;
                        /* Solo cuenta si el dedo va claramente hacia arriba o hacia abajo */
                        if (Math.abs(dy) < Math.abs(dx) * 1.5f) { ignore = true; return true; }
                    }
                    scrolling = true;
                    if (zone == 1 || zone == 3) {
                        float lv = Math.max(0f, Math.min(1f, startLevel + dy / (hh * 0.7f)));
                        if (zone == 1) setBrightness(lv); else setVolume(lv);
                        showLevel(zone == 1, lv);
                    } else {
                        total = dy;
                        showSwipe(total);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (scrolling && !ignore && zone == 2 && list.size() > 1 && Math.abs(total) > dp(90) && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        /* deslizar hacia arriba = el siguiente (como pasar la página); hacia abajo = el anterior */
                        change(total > 0 ? 1 : -1);
                    }
                    swipeBox.setVisibility(View.GONE);
                    h.removeCallbacks(hideLevel); h.postDelayed(hideLevel, 700);
                    scrolling = false;
                    return true;
            }
            return true;
        }
    }

    private void showSwipe(float dy) {
        if (list.size() < 2) return;
        int dir = dy > 0 ? 1 : -1;
        Ch c = list.get((idx + dir + list.size()) % list.size());
        int pos = (idx + dir + list.size()) % list.size();
        boolean ready = Math.abs(dy) > dp(90);
        swipeHint.setText((dir > 0 ? "▼ " : "▲ ") + (ready ? s("release", "Suelta para cambiar") : s("keepSliding", "Sigue deslizando")));
        swipeName.setText(c.n);
        swipeSub.setText(fmt(s("chNofM", "Canal {0} de {1}"), pos + 1, list.size()) + (c.f ? " · ⭐" : ""));
        swipeBox.setVisibility(View.VISIBLE);
    }

    private final Runnable hideLevel = new Runnable() { public void run() { levelBox.setVisibility(View.GONE); } };
    private void showLevel(boolean bright, float lv) {
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) levelBox.getLayoutParams();
        p.gravity = Gravity.CENTER_VERTICAL | (bright ? Gravity.START : Gravity.END);
        p.leftMargin = bright ? dp(60) : 0; p.rightMargin = bright ? 0 : dp(60);
        levelBox.setLayoutParams(p);
        levelIcon.setText(bright ? "☀" : "🔊");
        levelText.setText(Math.round(lv * 100) + "%");
        levelFill.setBackground(round(bright ? 0xFFFFD76A : CYAN, 4, 0));
        FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) levelFill.getLayoutParams();
        fp.height = Math.round(dp(140) * lv); levelFill.setLayoutParams(fp);
        levelBox.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideLevel);
    }

    private float getBrightness() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b >= 0) return b;
        try { return android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f; }
        catch (Exception e) { return 0.5f; }
    }
    private void setBrightness(float v) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = Math.max(0.02f, v);
        getWindow().setAttributes(lp);
    }
    private float getVolume() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
    }
    private void setVolume(float v) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v * max), 0);
        } catch (Exception ignored) { }
    }

    /* ============================ AJUSTE DE PANTALLA ============================ */
    private static final String[] ASPECTS = {"fit", "fill", "zoom", "16:9", "4:3"};
    private String aspectName(String a) {
        switch (a) {
            case "fill": return s("fill", "Rellenar");
            case "zoom": return s("zoom", "Zoom");
            case "16:9": return "16:9";
            case "4:3": return "4:3";
            default: return s("fit", "Ajustar");
        }
    }
    private void cycleAspect() {
        int i = 0; for (int k = 0; k < ASPECTS.length; k++) if (ASPECTS[k].equals(aspect)) i = k;
        setAspect(ASPECTS[(i + 1) % ASPECTS.length]);
        toast("⛶ " + aspectName(aspect));
    }
    private void setAspect(String a) {
        aspect = a; applyAspect(); saveSettings();
        bAspect.setText("⛶ " + aspectName(aspect));
        setControls(true);
    }
    private void applyAspect() {
        if (view == null) return;
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) view.getLayoutParams();
        float ratio = "16:9".equals(aspect) ? 16f / 9f : ("4:3".equals(aspect) ? 4f / 3f : 0f);
        if (ratio > 0) {
            int W = root.getWidth(), H = root.getHeight();
            if (W == 0 || H == 0) { root.post(this::applyAspect); return; }
            int w = W, hgt = Math.round(W / ratio);
            if (hgt > H) { hgt = H; w = Math.round(H * ratio); }
            p.width = w; p.height = hgt;
            view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
        } else {
            p.width = ViewGroup.LayoutParams.MATCH_PARENT; p.height = ViewGroup.LayoutParams.MATCH_PARENT;
            view.setResizeMode("fill".equals(aspect) ? AspectRatioFrameLayout.RESIZE_MODE_FILL
                    : ("zoom".equals(aspect) ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT));
        }
        p.gravity = Gravity.CENTER;
        view.setLayoutParams(p);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        root.post(this::applyAspect);
    }

    /* ============================ CALIDAD, AUDIO, SUBTÍTULOS ============================ */
    private void applyQuality() {
        if (player == null) return;
        TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon()
                .clearVideoSizeConstraints().setForceHighestSupportedBitrate(false).setForceLowestBitrate(false);
        if ("high".equals(quality)) b.setForceHighestSupportedBitrate(true);
        else if ("medium".equals(quality)) b.setMaxVideoSize(1280, 720);
        else if ("low".equals(quality)) b.setMaxVideoSize(854, 480);
        player.setTrackSelectionParameters(b.build());
    }

    /** Alturas de video que trae el canal (para mostrar solo las calidades que existen) */
    private LinkedHashSet<String> availableQualities() {
        int max = 0, min = Integer.MAX_VALUE, count = 0;
        LinkedHashSet<Integer> hs = new LinkedHashSet<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < g.length; i++) {
                int ht = g.getTrackFormat(i).height;
                if (ht > 0 && hs.add(ht)) { count++; max = Math.max(max, ht); min = Math.min(min, ht); }
            }
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add("auto");
        if (count > 1) {
            if (max > 720) out.add("high");
            for (int ht : hs) if (ht > 480 && ht <= 720) { out.add("medium"); break; }
            if (min <= 480) out.add("low");
            if (out.size() == 2 && !out.contains("high")) out.add("high");
        }
        return out;
    }

    private String qualityName(String q) {
        switch (q) {
            case "high": return s("qHigh", "Alta · 1080p");
            case "medium": return s("qMed", "Media · 720p");
            case "low": return s("qLow", "Baja · 480p");
            default: return s("qAuto", "Automática");
        }
    }

    private static String trackName(Format f, int n, String fallback) {
        String lang = f.language;
        String name = null;
        if (f.label != null && f.label.length() > 0) name = f.label;
        else if (lang != null && lang.length() > 0 && !"und".equals(lang)) {
            Locale l = Locale.forLanguageTag(lang);
            String d = l.getDisplayLanguage(Locale.getDefault());
            if (d != null && d.length() > 0) name = Character.toUpperCase(d.charAt(0)) + d.substring(1);
        }
        return name != null ? name : fallback + " " + n;
    }

    /* ============================ HOJA DE AJUSTES ============================ */
    private void buildSheet() {
        sheetScrim = new FrameLayout(this);
        sheetScrim.setBackgroundColor(0x73000000);
        sheetScrim.setOnClickListener(v -> closeSheet());
        sheetScrim.setVisibility(View.GONE);
        ScrollView sc = new ScrollView(this);
        sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(14), dp(20), dp(18));
        sc.addView(sheet);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF161616);
        float r = dp(22);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(dp(1), LINE);
        sc.setBackground(bg);
        sc.setOnClickListener(v -> { });
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sheetScrim.addView(sc, sp);
        sc.setLayoutParams(sp);
        root.addView(sheetScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private boolean sheetOpen() { return sheetScrim != null && sheetScrim.getVisibility() == View.VISIBLE; }

    private void openSheet() {
        closePanel();
        fillSheet();
        View sc = (View) sheet.getParent();
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) sc.getLayoutParams();
        int W = root.getWidth();
        p.width = W > dp(820) ? dp(760) : ViewGroup.LayoutParams.MATCH_PARENT;
        sc.setLayoutParams(p);
        sheetScrim.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideControls);
    }

    private void closeSheet() { if (sheetScrim != null) sheetScrim.setVisibility(View.GONE); }

    private LinearLayout chipRow(String label) {
        TextView l = text(label.toUpperCase(Locale.getDefault()), 10, 0xFF9A9A9A, true);
        l.setShadowLayer(0, 0, 0, 0);
        l.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(12), 0, dp(6));
        sheet.addView(l, lp);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(row);
        sheet.addView(hs);
        return row;
    }

    private void chip(LinearLayout row, String t, boolean on, View.OnClickListener l) {
        TextView c = text(t, 12, on ? Color.BLACK : 0xFFCCCCCC, true);
        c.setShadowLayer(0, 0, 0, 0);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(12), 0, dp(12), 0);
        c.setBackground(on ? round(CYAN, 16, 0) : round(0x00000000, 16, 0xFF333333));
        c.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        p.setMargins(0, 0, dp(6), 0);
        row.addView(c, p);
    }

    private void fillSheet() {
        sheet.removeAllViews();
        View handle = new View(this); handle.setBackground(round(0xFF3A3A3A, 3, 0));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(44), dp(5)); hp.gravity = Gravity.CENTER_HORIZONTAL; hp.bottomMargin = dp(4);
        sheet.addView(handle, hp);

        LinearLayout r1 = chipRow(s("screenFit", "Ajuste de pantalla"));
        for (String a : ASPECTS) { final String fa = a; chip(r1, aspectName(a), a.equals(aspect), v -> { setAspect(fa); fillSheet(); }); }

        LinearLayout r2 = chipRow(s("quality", "Calidad"));
        for (String q : availableQualities()) { final String fq = q; chip(r2, qualityName(q), q.equals(quality), v -> { quality = fq; applyQuality(); saveSettings(); fillSheet(); }); }

        /* idioma del audio */
        List<Tracks.Group> audio = new ArrayList<>(), subs = new ArrayList<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() == C.TRACK_TYPE_AUDIO && g.isSupported()) audio.add(g);
            else if (g.getType() == C.TRACK_TYPE_TEXT && g.isSupported()) subs.add(g);
        }
        if (audio.size() > 1) {
            LinearLayout r3 = chipRow(s("audioLang", "Idioma del audio"));
            int n = 1;
            for (final Tracks.Group g : audio) {
                chip(r3, trackName(g.getTrackFormat(0), n++, s("audio", "Audio")), g.isSelected(), v -> {
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), 0)).build());
                    h.postDelayed(this::fillSheet, 300);
                });
            }
        }
        if (!subs.isEmpty()) {
            LinearLayout r4 = chipRow(s("subs", "Subtítulos"));
            final boolean off = player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
            chip(r4, s("subsOff", "Desactivados"), off, v -> {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
                h.postDelayed(this::fillSheet, 300);
            });
            int n = 1;
            for (final Tracks.Group g : subs) {
                chip(r4, trackName(g.getTrackFormat(0), n++, s("subs", "Subtítulos")), !off && g.isSelected(), v -> {
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), 0)).build());
                    h.postDelayed(this::fillSheet, 300);
                });
            }
        }

        LinearLayout r5 = chipRow("🌙 " + s("sleepT", "Temporizador para dormir"));
        int[] mins = {0, 15, 30, 60, 90};
        for (int m : mins) {
            final int fm = m;
            chip(r5, m == 0 ? s("sleepOff", "Apagado") : m + " min", (sleepEnd > 0 ? sleepMin : 0) == m, v -> { setSleep(fm); fillSheet(); });
        }
        sleepLeft = text("", 12, 0xFF9FD8DF, false);
        sleepLeft.setShadowLayer(0, 0, 0, 0);
        sheet.addView(sleepLeft);
        if (sleepEnd > 0) sleepLeft.setText(fmt(s("sleepIn", "Se apaga en {0}"), mmss(sleepEnd - SystemClock.elapsedRealtime())));

        TextView note = text(s("onlyOpts", "Solo aparecen las opciones que el canal trae (algunos tienen una sola calidad o un solo idioma)."), 11, 0xFF777777, false);
        note.setShadowLayer(0, 0, 0, 0);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); np.topMargin = dp(12);
        sheet.addView(note, np);
    }

    private void setSleep(int minutes) {
        h.removeCallbacks(sleepTick);
        sleepMin = minutes;
        if (minutes <= 0) { sleepEnd = 0; toast("🌙 " + s("sleepOff", "Apagado")); return; }
        sleepEnd = SystemClock.elapsedRealtime() + minutes * 60000L;
        toast("🌙 " + fmt(s("sleepIn", "Se apaga en {0}"), minutes + " min"));
        h.post(sleepTick);
    }

    /* ============================ LISTA DE CANALES ============================ */
    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(PANEL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(10));
        panel.setClickable(true);
        panel.setFocusableInTouchMode(true);   /* así el buscador no agarra el foco (ni abre el teclado) solo */

        LinearLayout head = new LinearLayout(this); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView ht = text(s("channels", "Canales"), 15, Color.WHITE, true);
        head.addView(ht, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView x = btn("✕", 34, 0xFF1F1F1F, 0xFFCCCCCC, s("close", "Cerrar"), v -> closePanel());
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        head.addView(x);
        panel.addView(head);

        panelSearch = new EditText(this);
        panelSearch.setHint("🔍 " + s("searchCh", "Buscar canal…"));
        panelSearch.setHintTextColor(0xFF8A8A8A);
        panelSearch.setTextColor(Color.WHITE);
        panelSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        panelSearch.setSingleLine(true);
        panelSearch.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN | android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        panelSearch.setBackground(round(0xFF1F1F1F, 12, LINE));
        panelSearch.setPadding(dp(12), dp(8), dp(12), dp(8));
        panelSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            public void onTextChanged(CharSequence c, int a, int b, int d) { }
            public void afterTextChanged(Editable e) { refilter(); }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); sp.setMargins(0, dp(10), 0, dp(8));
        panel.addView(panelSearch, sp);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        panelChips = new LinearLayout(this); panelChips.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(panelChips);
        panel.addView(hs);

        panelList = new ListView(this);
        panelList.setDivider(null);
        panelList.setVerticalScrollBarEnabled(true);
        adapter = new ChAdapter();
        panelList.setAdapter(adapter);
        panelList.setOnItemClickListener((p, v, pos, id) -> { if (pos < shown.size()) { playIndex(shown.get(pos)); closePanel(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); lp.topMargin = dp(8);
        panel.addView(panelList, lp);

        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(dp(380), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
    }

    private boolean panelOpen() { return panel != null && panel.getVisibility() == View.VISIBLE; }

    private void openPanel() {
        closeSheet();
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) panel.getLayoutParams();
        int W = root.getWidth();
        p.width = W > 0 ? Math.min(dp(380), Math.round(W * 0.9f)) : dp(380);
        panel.setLayoutParams(p);
        buildPanelChips();
        refilter();
        panel.setVisibility(View.VISIBLE);
        panel.requestFocus();
        setControls(false);
        /* el canal actual queda a la vista */
        int pos = shown.indexOf(idx);
        if (pos >= 0) panelList.setSelection(Math.max(0, pos - 2));
    }

    private void closePanel() {
        if (panel == null || panel.getVisibility() != View.VISIBLE) return;
        panel.setVisibility(View.GONE);
        try { ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(panelSearch.getWindowToken(), 0); } catch (Exception ignored) { }
        immersive();
    }

    private void buildPanelChips() {
        panelChips.removeAllViews();
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        boolean anyFav = false;
        for (Ch c : list) { if (c.g != null && c.g.length() > 0) groups.add(c.g); if (c.f) anyFav = true; }
        if (!groups.contains(panelGroup) && !"★".equals(panelGroup)) panelGroup = "";
        if (groups.size() < 2 && !anyFav) { ((View) panelChips.getParent()).setVisibility(View.GONE); return; }
        ((View) panelChips.getParent()).setVisibility(View.VISIBLE);
        addPanelChip(s("all", "Todos"), "");
        if (anyFav) addPanelChip("⭐ " + s("favs", "Favoritos"), "★");
        if (groups.size() > 1) for (String g : groups) addPanelChip(g, g);
    }

    private void addPanelChip(String t, final String key) {
        boolean on = key.equals(panelGroup);
        boolean fav = "★".equals(key);
        TextView c = text(t, 11, on ? Color.BLACK : (fav ? 0xFFFFD76A : 0xFFCCCCCC), true);
        c.setShadowLayer(0, 0, 0, 0);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(11), 0, dp(11), 0);
        c.setBackground(on ? round(fav ? GOLD : CYAN, 16, 0) : round(fav ? 0x24FFC83C : 0x00000000, 16, fav ? GOLD : LINE));
        c.setOnClickListener(v -> { panelGroup = key; buildPanelChips(); refilter(); });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)); p.setMargins(0, 0, dp(6), 0);
        panelChips.addView(c, p);
    }

    private void refilter() {
        shown.clear();
        String q = panelSearch.getText().toString().trim().toLowerCase(Locale.getDefault());
        for (int i = 0; i < list.size(); i++) {
            Ch c = list.get(i);
            if ("★".equals(panelGroup) && !c.f) continue;
            if (panelGroup.length() > 0 && !"★".equals(panelGroup) && !panelGroup.equals(c.g)) continue;
            if (q.length() > 0 && !c.n.toLowerCase(Locale.getDefault()).contains(q) && (c.g == null || !c.g.toLowerCase(Locale.getDefault()).contains(q))) continue;
            shown.add(i);
        }
        adapter.notifyDataSetChanged();
    }

    private static final int[] TILE = {0xFF2B6CFF, 0xFFFF7A1A, 0xFFB388FF, 0xFF1FBF6A, 0xFFFF4D5E, 0xFF00B8D4, 0xFFFFB300, 0xFF7C4DFF};

    private final class ChAdapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int p) { return list.get(shown.get(p)); }
        public long getItemId(int p) { return shown.get(p); }
        public View getView(int pos, View convert, ViewGroup parent) {
            final int i = shown.get(pos);
            final Ch c = list.get(i);
            LinearLayout row = new LinearLayout(VideoActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(7), dp(8), dp(7));
            boolean now = i == idx;
            if (now) row.setBackground(round(0x1F00E5FF, 10, 0x6600E5FF));
            TextView tile = text(initials(c.n), 11, Color.WHITE, true);
            tile.setGravity(Gravity.CENTER);
            tile.setBackground(round(TILE[Math.abs(c.n.hashCode()) % TILE.length], 6, 0));
            row.addView(tile, new LinearLayout.LayoutParams(dp(46), dp(30)));
            LinearLayout info = new LinearLayout(VideoActivity.this); info.setOrientation(LinearLayout.VERTICAL);
            TextView n = text((now ? "▶ " : "") + c.n, 13, now ? CYAN : Color.WHITE, true); n.setSingleLine(true); n.setEllipsize(android.text.TextUtils.TruncateAt.END); n.setShadowLayer(0, 0, 0, 0);
            TextView g = text(now ? s("watching", "Viendo ahora") : (c.g == null ? "" : c.g), 10, 0xFF8A8A8A, false); g.setSingleLine(true); g.setShadowLayer(0, 0, 0, 0);
            info.addView(n); info.addView(g);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); ip.setMargins(dp(10), 0, dp(6), 0);
            row.addView(info, ip);
            TextView star = text(c.f ? "★" : "☆", 18, c.f ? GOLD : 0xFF555555, false);
            star.setShadowLayer(0, 0, 0, 0);
            star.setPadding(dp(8), dp(4), dp(4), dp(4));
            star.setContentDescription(c.f ? s("favDel", "Quitar de Favoritos") : s("favAdd", "Agregar a Favoritos"));
            star.setOnClickListener(v -> {
                c.f = !c.f;
                JSONObject e = new JSONObject();
                try { e.put("type", "fav"); e.put("u", c.u); e.put("n", c.n); e.put("g", c.g); e.put("on", c.f); } catch (Exception ignored) { }
                emit(e);
                if (i == idx) updateTexts(); else notifyDataSetChanged();
            });
            row.addView(star);
            return row;
        }
    }

    private static String initials(String n) {
        StringBuilder sb = new StringBuilder();
        for (String w : n.trim().split("\\s+")) { if (w.length() > 0 && Character.isLetterOrDigit(w.charAt(0))) sb.append(Character.toUpperCase(w.charAt(0))); if (sb.length() == 2) break; }
        return sb.length() > 0 ? sb.toString() : "TV";
    }

    /* ============================ SOLO AUDIO ============================ */
    private void audioOnly() {
        Ch c = cur(); if (c == null) return;
        JSONObject e = new JSONObject();
        try { e.put("type", "audio"); e.put("u", c.u); e.put("n", c.n); e.put("g", c.g); e.put("i", idx); } catch (Exception ignored) { }
        if (player != null) player.stop();
        emit(e);
        finish();
    }

    /* ============================ VENTANA FLOTANTE (PiP) ============================ */
    private boolean pipSupported() {
        return Build.VERSION.SDK_INT >= 26 && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private PictureInPictureParams pipParams() {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
        Rational r = new Rational(16, 9);
        if (player != null) {
            VideoSize vs = player.getVideoSize();
            if (vs.width > 0 && vs.height > 0) {
                float k = vs.width * (vs.pixelWidthHeightRatio > 0 ? vs.pixelWidthHeightRatio : 1f) / vs.height;
                k = Math.max(0.42f, Math.min(2.38f, k));
                r = new Rational(Math.round(k * 1000), 1000);
            }
        }
        b.setAspectRatio(r);
        List<RemoteAction> acts = new ArrayList<>();
        boolean playing = player != null && player.isPlaying();
        if (list.size() > 1) acts.add(remote(android.R.drawable.ic_media_previous, s("prevCh", "Canal anterior"), PIP_PREV, 1));
        acts.add(remote(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? s("pause", "Pausa") : s("play", "Reproducir"), PIP_TOGGLE, 2));
        if (list.size() > 1) acts.add(remote(android.R.drawable.ic_media_next, s("nextCh", "Canal siguiente"), PIP_NEXT, 3));
        b.setActions(acts);
        /* La ventana flotante solo se abre con el botón ⧉ (no entra sola al salir) */
        if (Build.VERSION.SDK_INT >= 31) { b.setAutoEnterEnabled(false); b.setSeamlessResizeEnabled(true); }
        return b.build();
    }

    private RemoteAction remote(int icon, String title, String action, int req) {
        Intent i = new Intent(action).setPackage(getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(this, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new RemoteAction(Icon.createWithResource(this, icon), title, title, pi);
    }

    private void updatePip() {
        if (!pipSupported()) return;
        try { setPictureInPictureParams(pipParams()); } catch (Exception ignored) { }
    }

    private void enterPip() {
        if (!pipSupported()) return;
        closePanel(); closeSheet();
        try { enterPictureInPictureMode(pipParams()); } catch (Exception e) { toast(s("noPip", "La ventana flotante está desactivada en los ajustes del teléfono")); }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, Configuration c) {
        super.onPictureInPictureModeChanged(isInPip, c);
        inPip = isInPip;
        if (isInPip) {
            setControls(false); closePanel(); closeSheet();
            pipReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent i) {
                    String a = i.getAction();
                    if (PIP_PREV.equals(a)) change(-1);
                    else if (PIP_NEXT.equals(a)) change(1);
                    else if (PIP_TOGGLE.equals(a)) togglePlay();
                    updatePip();
                }
            };
            IntentFilter f = new IntentFilter(); f.addAction(PIP_PREV); f.addAction(PIP_NEXT); f.addAction(PIP_TOGGLE);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(pipReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(pipReceiver, f);
        } else {
            if (pipReceiver != null) { try { unregisterReceiver(pipReceiver); } catch (Exception ignored) { } pipReceiver = null; }
            setControls(true);
            root.post(this::applyAspect);
        }
    }

    /* ============================ CICLO DE VIDA ============================ */
    private boolean wasPlaying = true;

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null && wasPlaying && !bad) player.play();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isChangingConfigurations()) return;
        /* Se cerró la ventana flotante (✕): se detiene el canal */
        if (inPip) { inPip = false; if (player != null) player.pause(); finish(); return; }
        if (player != null) { wasPlaying = player.isPlaying(); player.pause(); }
        /* Si saliste de la app (no fue solo apagar la pantalla), se cierra el canal: para escuchar en segundo plano está "Solo audio" */
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isInteractive() && !isFinishing()) finish();
    }

    @Override
    public void onBackPressed() {
        if (locked) { showLockHint(); return; }
        if (sheetOpen()) { closeSheet(); return; }
        if (panelOpen()) { closePanel(); return; }
        super.onBackPressed();
    }

    private int fxSession = 0;

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (pipReceiver != null) { try { unregisterReceiver(pipReceiver); } catch (Exception ignored) { } pipReceiver = null; }
        if (view != null) view.setPlayer(null);
        if (player != null) { player.release(); player = null; }
        if (fxSession > 0) { AudioFx.detach(fxSession); fxSession = 0; }
        JSONObject e = new JSONObject();
        try { e.put("type", "closed"); } catch (Exception ignored) { }
        emit(e);
        super.onDestroy();
    }
}
