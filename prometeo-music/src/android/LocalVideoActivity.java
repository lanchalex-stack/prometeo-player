package com.prometeo.music;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
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
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * v43 — 🎬 Reproductor de los videos del teléfono (ExoPlayer, AndroidX Media3, licencia Apache 2.0).
 * Continuar donde ibas, ±10 s con doble toque, velocidad, cuadro por cuadro, repetir A-B, 📸 captura,
 * 💬 subtítulos propios (.srt/.vtt/.ass), pistas de audio, brillo y volumen con gestos,
 * bloqueo, ventana flotante y 📡 mandar al TV. Los videos no se copian ni salen del teléfono.
 */
@OptIn(markerClass = UnstableApi.class)
public class LocalVideoActivity extends Activity {

    interface Events { void send(JSONObject o); }
    static volatile Events events;
    /** {items:[{id,n,vol}], i, repeat, s:{textos}} */
    static volatile JSONObject pending;

    /** id = número del video en Android (o -1); u = dirección de un video de memoria USB o elegido a mano; k = clave para recordar posición y subtítulos */
    private static final class It { long id; String n, u, k; long cs = -1, ce = -1, at = -1; }
    private final List<It> items = new ArrayList<>();
    private int idx = 0;
    private boolean repeat = false;
    private JSONObject S = new JSONObject();

    private ExoPlayer player;
    private PlayerView view;
    private FrameLayout root;
    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private LinearLayout topBar, centerBar, bottomBar;
    private TextView tTitle, tSub, bPlay, bFrameB, bFrameF, tCur, tDur, cAB, cSpeed, cAspect;
    private SeekBar seek;
    private View markA, markB, flash;
    private FrameLayout seekBox;
    private boolean dragging = false, controlsShown = true, locked = false, inPip = false;
    private TextView toastView, lockBtn, levelText;
    private LinearLayout levelBox;
    private FrameLayout sheetScrim; private LinearLayout sheet;

    private long abA = -1, abB = -1;
    private int skipGuard = 0;
    private String aspect = "fit";
    private int subSize = 1, subBg = 1;
    private boolean subsOff = false;
    private Subs.Prepared extSub;
    private String extName = "";
    private float speed = 1f;
    private int fxSession = 0;

    private static final int CYAN = 0xFF00E5FF, PINK = 0xFFFF4FD8, DARK = 0x8C000000, LINE = 0xFF2C2C2C;
    private static final int REQ_SUB = 7731;
    private boolean picking = false;
    private static final String PIP_PREV = "com.prometeo.music.LV_PREV", PIP_TOGGLE = "com.prometeo.music.LV_TOGGLE", PIP_NEXT = "com.prometeo.music.LV_NEXT";
    private BroadcastReceiver pipReceiver;

    private final Runnable hideControls = new Runnable() { public void run() { if (player != null && player.isPlaying() && !sheetOpen()) setControls(false); } };
    private final Runnable tick = new Runnable() {
        public void run() {
            if (player != null) {
                long pos = player.getCurrentPosition(), dur = Math.max(0, player.getDuration());
                if (abA >= 0 && abB > abA && pos >= abB) { player.seekTo(abA); pos = abA; }
                if (!dragging && dur > 0) seek.setProgress((int) (pos * 1000 / dur));
                tCur.setText(mmss(pos)); tDur.setText(dur > 0 ? mmss(dur) : "--:--");
                placeMarks(dur);
            }
            h.postDelayed(this, 250);
        }
    };
    private final Runnable saveTick = new Runnable() { public void run() { savePos(); h.postDelayed(this, 5000); } };

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
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        prefs = Videos.prefs(this);
        subSize = prefs.getInt("subSize", 1); subBg = prefs.getInt("subBg", 1); aspect = prefs.getString("aspect", "fit");
        readIntent();
        if (items.isEmpty()) { finish(); return; }
        buildUi();
        buildPlayer();
        immersive();
        startCurrent(-1);
        h.post(tick); h.postDelayed(saveTick, 5000);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        savePos();
        readIntent();
        if (items.isEmpty()) { finish(); return; }
        closeSheet(); abClear();
        startCurrent(-1);
    }

    private void readIntent() {
        JSONObject o = pending; pending = null;
        if (o == null) return;
        items.clear();
        JSONArray a = o.optJSONArray("items");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i); if (x == null) continue;
            It it = new It(); it.id = x.optLong("id", -1); it.n = x.optString("n", ""); it.u = x.optString("u", "");
            if (it.u.isEmpty()) it.u = null;
            it.k = it.u != null ? x.optString("k", "u" + Math.abs(it.u.hashCode())) : String.valueOf(it.id);
            /* v44: cs/ce = solo un tramo (vista previa de cortar); at = empezar en este punto (al volver del TV) */
            it.cs = x.optLong("cs", -1); it.ce = x.optLong("ce", -1); it.at = x.optLong("at", -1);
            if (it.id >= 0 || it.u != null) items.add(it);
        }
        idx = Math.max(0, Math.min(o.optInt("i", 0), Math.max(0, items.size() - 1)));
        repeat = o.optBoolean("repeat", false);
        JSONObject st = o.optJSONObject("s"); if (st != null) S = st;
    }

    /* ============================ TEXTOS ============================ */
    private String s(String k, String def) { String v = S.optString(k, ""); return v.length() > 0 ? v : def; }
    private static String fmt(String t, Object... a) { for (int i = 0; i < a.length; i++) t = t.replace("{" + i + "}", String.valueOf(a[i])); return t; }
    private static String mmss(long ms) {
        long sec = Math.max(0, ms / 1000);
        return sec >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60) : String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
    }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /* ============================ INTERFAZ ============================ */
    private GradientDrawable round(int color, float radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radiusDp));
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
    private TextView btn(String t, int sizeDp, int bg, int fg, String desc, View.OnClickListener l) {
        TextView b = text(t, sizeDp >= 60 ? 24 : 17, fg, true);
        b.setGravity(Gravity.CENTER); b.setBackground(round(bg, sizeDp / 2f, 0)); b.setContentDescription(desc); b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return b;
    }
    private TextView pill(String t, int stroke, View.OnClickListener l) {
        TextView b = text(t, 13, Color.WHITE, true);
        b.setGravity(Gravity.CENTER); b.setPadding(dp(12), 0, dp(12), 0);
        b.setBackground(round(0x24FFFFFF, 18, stroke == 0 ? 0x38FFFFFF : stroke));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)); p.setMargins(0, 0, dp(7), 0);
        b.setLayoutParams(p);
        return b;
    }
    private static void margins(View v, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
        if (p == null) p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b); v.setLayoutParams(p);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new PlayerView(this);
        view.setUseController(false);
        view.setKeepContentOnPlayerReset(true);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        view.setShutterBackgroundColor(Color.BLACK);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        View touch = new View(this);
        touch.setOnTouchListener(new Gestures());
        root.addView(touch, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        /* ---- arriba: volver, título, 📡, 📸, ⚙ ---- */
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL); topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(14), dp(12), dp(14), dp(24));
        topBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB3000000, 0x00000000}));
        TextView back = btn("‹", 40, DARK, Color.WHITE, s("back", "Volver"), v -> finish());
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        topBar.addView(back);
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        tTitle = text("", 16, Color.WHITE, true); tTitle.setSingleLine(true); tTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tSub = text("", 12, 0xFFC8D3E0, false); tSub.setSingleLine(true);
        titles.addView(tTitle); titles.addView(tSub);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); tp.setMargins(dp(10), 0, dp(6), 0);
        topBar.addView(titles, tp);
        TextView bCast = btn("📡", 40, DARK, Color.WHITE, s("cast", "Mandar al TV"), v -> castIt());
        bCast.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15); topBar.addView(bCast); margins(bCast, dp(4), 0, dp(4), 0);
        TextView bShot = btn("📸", 40, DARK, Color.WHITE, s("shot", "Captura de pantalla"), v -> shot());
        bShot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15); bShot.setBackground(round(DARK, 20, CYAN));
        topBar.addView(bShot); margins(bShot, dp(4), 0, dp(4), 0);
        TextView bSet = btn("⚙", 40, DARK, Color.WHITE, s("settings", "Ajustes"), v -> openSheet());
        topBar.addView(bSet); margins(bSet, dp(4), 0, 0, 0);
        root.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        /* ---- centro: ⏮ ◀︎| ▶ |▶︎ ⏭ (los de cuadro por cuadro solo en pausa) ---- */
        centerBar = new LinearLayout(this);
        centerBar.setOrientation(LinearLayout.HORIZONTAL); centerBar.setGravity(Gravity.CENTER);
        TextView bPrev = btn("⏮", 50, DARK, Color.WHITE, s("prevV", "Video anterior"), v -> change(-1));
        bFrameB = btn("◀|", 50, DARK, CYAN, s("frameB", "Cuadro anterior"), v -> frame(-1));
        bFrameB.setBackground(round(DARK, 25, CYAN)); bFrameB.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bPlay = btn("⏸", 72, CYAN, Color.BLACK, s("pause", "Pausa"), v -> togglePlay());
        bFrameF = btn("|▶", 50, DARK, CYAN, s("frameF", "Cuadro siguiente"), v -> frame(1));
        bFrameF.setBackground(round(DARK, 25, CYAN)); bFrameF.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        TextView bNext = btn("⏭", 50, DARK, Color.WHITE, s("nextV", "Video siguiente"), v -> change(1));
        centerBar.addView(bPrev); centerBar.addView(bFrameB); centerBar.addView(bPlay); centerBar.addView(bFrameF); centerBar.addView(bNext);
        margins(bFrameB, dp(14), 0, 0, 0); margins(bPlay, dp(18), 0, dp(18), 0); margins(bNext, dp(14), 0, 0, 0);
        root.addView(centerBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        /* ---- abajo: barra con marcas A-B y botones ---- */
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(14), dp(24), dp(14), dp(12));
        bottomBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000}));
        LinearLayout seekRow = new LinearLayout(this); seekRow.setOrientation(LinearLayout.HORIZONTAL); seekRow.setGravity(Gravity.CENTER_VERTICAL);
        tCur = text("0:00", 12, Color.WHITE, false); tDur = text("0:00", 12, Color.WHITE, false);
        seekBox = new FrameLayout(this);
        seek = new SeekBar(this); seek.setMax(1000);
        if (Build.VERSION.SDK_INT >= 21) { seek.setProgressTintList(android.content.res.ColorStateList.valueOf(CYAN)); seek.setThumbTintList(android.content.res.ColorStateList.valueOf(CYAN)); }
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean user) { if (user && player != null) tCur.setText(mmss(Math.max(0, player.getDuration()) * p / 1000)); }
            public void onStartTrackingTouch(SeekBar sb) { dragging = true; h.removeCallbacks(hideControls); }
            public void onStopTrackingTouch(SeekBar sb) { dragging = false; if (player != null && player.getDuration() > 0) player.seekTo(player.getDuration() * sb.getProgress() / 1000); scheduleHide(); }
        });
        seekBox.addView(seek, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        markA = new View(this); markA.setBackground(round(PINK, 2, 0)); markA.setVisibility(View.GONE);
        markB = new View(this); markB.setBackground(round(PINK, 2, 0)); markB.setVisibility(View.GONE);
        seekBox.addView(markA, new FrameLayout.LayoutParams(dp(4), dp(16), Gravity.CENTER_VERTICAL | Gravity.START));
        seekBox.addView(markB, new FrameLayout.LayoutParams(dp(4), dp(16), Gravity.CENTER_VERTICAL | Gravity.START));
        seekRow.addView(tCur);
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(0, dp(36), 1f); sbp.setMargins(dp(6), 0, dp(6), 0);
        seekRow.addView(seekBox, sbp);
        seekRow.addView(tDur);
        bottomBar.addView(seekRow);
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this); chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.addView(pill("⏪ 10", 0, v -> jump(-10000)));
        chips.addView(pill("⏩ 10", 0, v -> jump(10000)));
        cSpeed = pill("1x", 0, v -> cycleSpeed()); chips.addView(cSpeed);
        cAB = pill("🔁 A-B", CYAN, v -> abTap()); cAB.setTextColor(CYAN); chips.addView(cAB);
        cAspect = pill("⛶ " + aspectName(aspect), 0, v -> cycleAspect()); chips.addView(cAspect);
        TextView cSub = pill("💬 " + s("subs", "Subtítulos"), CYAN, v -> openSheet()); cSub.setTextColor(CYAN); chips.addView(cSub);
        chips.addView(pill("🔊 " + s("audio", "Audio"), 0, v -> openSheet()));
        chips.addView(pill("🔒", 0, v -> setLocked(true)));
        if (pipSupported()) chips.addView(pill("⧉ " + s("window", "Ventana"), 0, v -> enterPip()));
        hs.addView(chips);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); hp.topMargin = dp(4);
        bottomBar.addView(hs, hp);
        root.addView(bottomBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        /* ---- brillo / volumen ---- */
        levelBox = new LinearLayout(this); levelBox.setOrientation(LinearLayout.VERTICAL); levelBox.setGravity(Gravity.CENTER);
        levelBox.setPadding(dp(14), dp(10), dp(14), dp(10)); levelBox.setBackground(round(0x8C000000, 14, 0));
        levelText = text("", 15, Color.WHITE, true); levelBox.addView(levelText);
        levelBox.setVisibility(View.GONE);
        root.addView(levelBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.START));

        /* ---- bloqueo ---- */
        lockBtn = btn("🔒", 56, 0x8C000000, CYAN, s("unlock", "Desbloquear pantalla"), v -> toast(s("lockedHint", "Pantalla bloqueada · mantén el candado para desbloquear")));
        lockBtn.setBackground(round(0x8C000000, 28, CYAN));
        lockBtn.setOnLongClickListener(v -> { setLocked(false); return true; });
        lockBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams lbp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER_VERTICAL | Gravity.START); lbp.leftMargin = dp(22);
        root.addView(lockBtn, lbp);

        /* ---- destello de la captura y avisito ---- */
        flash = new View(this); flash.setBackgroundColor(Color.WHITE); flash.setAlpha(0f); flash.setClickable(false);
        root.addView(flash, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        toastView = text("", 13, Color.WHITE, true);
        toastView.setPadding(dp(14), dp(8), dp(14), dp(8)); toastView.setBackground(round(0xE0242424, 18, 0xFF333333));
        toastView.setVisibility(View.GONE);
        FrameLayout.LayoutParams tvp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL); tvp.topMargin = dp(80);
        root.addView(toastView, tvp);

        buildSheet();
        root.setOnApplyWindowInsetsListener((v, ins) -> { padForInsets(ins); return ins; });
        setContentView(root);
        applyAspect(); applySubStyle();
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
        topBar.setPadding(dp(14) + l, dp(12) + t, dp(14) + r, dp(24));
        bottomBar.setPadding(dp(14) + l, dp(24), dp(14) + r, dp(12) + b);
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) { c.hide(WindowInsets.Type.systemBars()); c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    private final Runnable hideToast = new Runnable() { public void run() { toastView.setVisibility(View.GONE); } };
    private void toast(String t) { toastView.setText(t); toastView.setVisibility(View.VISIBLE); h.removeCallbacks(hideToast); h.postDelayed(hideToast, 2200); }

    /* ============================ REPRODUCTOR ============================ */
    private void buildPlayer() {
        /* Listo para un decodificador extra (FFmpeg) cuando se agregue; sin él, usa los del teléfono */
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        player = new ExoPlayer.Builder(this, rf)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .build();
        player.setHandleAudioBecomingNoisy(true);
        player.setSeekParameters(SeekParameters.EXACT);
        fxSession = AudioFx.newSession(this);
        if (fxSession > 0) { player.setAudioSessionId(fxSession); AudioFx.attach(fxSession); }   /* 🎚️ ecualizador y volumen extra */
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) { skipBroken(); }
            @Override public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_READY) skipGuard = 0;
                if (st == Player.STATE_ENDED) {
                    clearPos();
                    if (idx < items.size() - 1 || (repeat && items.size() > 1)) change(1);
                    else if (repeat) { player.seekTo(0); player.play(); }
                    else setControls(true);
                }
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                bPlay.setText(playing ? "⏸" : "▶");
                bPlay.setContentDescription(playing ? s("pause", "Pausa") : s("play", "Reproducir"));
                int fv = playing ? View.GONE : View.VISIBLE;
                bFrameB.setVisibility(fv); bFrameF.setVisibility(fv);
                updatePip();
                if (playing) scheduleHide(); else { h.removeCallbacks(hideControls); savePos(); }
            }
            @Override public void onVideoSizeChanged(VideoSize vs) { updatePip(); }
            @Override public void onTracksChanged(Tracks tracks) { checkAudio(tracks); if (sheetOpen()) fillSheet(); }
        });
        view.setPlayer(player);
    }

    private It cur() { return items.isEmpty() ? null : items.get(idx); }

    /** Pone el video actual. at = posición (-1 = donde lo dejaste) */
    private void startCurrent(long at) {
        It it = cur(); if (it == null || player == null) return;
        if (at < 0 && it.at >= 0) { at = it.at; it.at = -1; }
        if (it.cs >= 0) at = 0;
        if (at < 0) {
            long p = prefs.getLong("p_" + it.k, 0), d = prefs.getLong("d_" + it.k, 0);
            at = (p > 3000 && (d <= 0 || p < d - 10000)) ? p : 0;
            if (at > 0) toast("▶ " + fmt(s("resumeAt", "Sigues desde {0}"), mmss(at)));
        }
        MediaItem.Builder b = new MediaItem.Builder().setUri(it.u != null ? Uri.parse(it.u) : Videos.uriOf(it.id));
        if (it.cs >= 0) {
            MediaItem.ClippingConfiguration.Builder cb = new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(it.cs);
            if (it.ce > it.cs) cb.setEndPositionMs(it.ce);
            b.setClippingConfiguration(cb.build());
        }
        extSub = null; extName = "";
        String su = prefs.getString("s_" + it.k, null);
        if (su != null) {
            try {
                Uri u = Uri.parse(su);
                extName = Subs.nameOf(this, u);
                extSub = Subs.prepare(this, u, "v" + it.k, prefs.getLong("o_" + it.k, 0));
                b.setSubtitleConfigurations(Collections.singletonList(new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(extSub.file))
                        .setMimeType(extSub.mime).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).setLabel("📄 " + extName).build()));
            } catch (Exception e) { extSub = null; }
        }
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO).clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subsOff || extSub == null).build());
        player.setMediaItem(b.build(), at);
        player.prepare();
        player.setPlaybackSpeed(speed);
        player.setPlayWhenReady(true);
        tTitle.setText(it.n);
        tSub.setText(items.size() > 1 ? fmt(s("nOfM", "{0} de {1}"), idx + 1, items.size()) : "");
        JSONObject e = new JSONObject();
        try { e.put("type", "watch"); e.put("id", it.id); e.put("k", it.k); if (it.u != null) e.put("u", it.u); e.put("i", idx); } catch (Exception ignored) { }
        emit(e);
        setControls(true);
    }

    /** Un video que no se puede abrir (tarjeta sacada, archivo borrado): se salta, sin mezclar títulos */
    private void skipBroken() {
        It it = cur(); if (it == null) return;
        player.stop(); player.clearMediaItems();
        if (items.size() > 1 && skipGuard < items.size()) {
            skipGuard++;
            toast("⚠️ " + fmt(s("skipped", "No se pudo abrir \"{0}\": se saltó"), it.n));
            idx = (idx + 1) % items.size();
            h.postDelayed(() -> startCurrent(-1), 900);
        } else {
            skipGuard = 0;
            toast("⚠️ " + fmt(s("cantOpen", "No se pudo abrir \"{0}\""), it.n));
            JSONObject e = new JSONObject();
            try { e.put("type", "error"); e.put("id", it.id); e.put("k", it.k); if (it.u != null) e.put("u", it.u); } catch (Exception ignored) { }
            emit(e);
            h.postDelayed(this::finish, 2200);
        }
    }

    /** 🔇 Si ningún audio del video se puede reproducir, se avisa en vez de quedar mudo */
    private void checkAudio(Tracks tracks) {
        boolean any = false, ok = false; String mime = "";
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
            any = true;
            if (g.isSupported()) ok = true; else if (mime.isEmpty() && g.length > 0 && g.getTrackFormat(0).sampleMimeType != null) mime = g.getTrackFormat(0).sampleMimeType;
        }
        if (any && !ok) toast("🔇 " + fmt(s("noAudio", "Este audio ({0}) no es compatible"), audioName(mime)));
    }
    private static String audioName(String m) {
        if (m == null) return "?";
        switch (m) {
            case "audio/ac3": return "AC3 Dolby";
            case "audio/eac3": case "audio/eac3-joc": return "E-AC3 Dolby";
            case "audio/vnd.dts": case "audio/vnd.dts.hd": case "audio/vnd.dts.uhd;profile=p2": return "DTS";
            case "audio/true-hd": return "TrueHD";
            default: return m.replace("audio/", "").toUpperCase(Locale.ROOT);
        }
    }

    private void change(int dir) {
        if (items.size() < 2) { if (dir > 0) toast(s("lastV", "Es el último video")); else if (player != null) player.seekTo(0); return; }
        savePos(); abClear();
        idx = (idx + dir + items.size()) % items.size();
        startCurrent(-1);
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.getPlaybackState() == Player.STATE_ENDED) { player.seekTo(0); player.play(); return; }
        if (player.getPlaybackState() == Player.STATE_IDLE) { startCurrent(-1); return; }
        if (player.isPlaying()) player.pause(); else player.play();
        setControls(true);
    }

    private void jump(long ms) {
        if (player == null) return;
        long d = player.getDuration();
        long p = Math.max(0, player.getCurrentPosition() + ms);
        if (d > 0) p = Math.min(d - 500, p);
        player.seekTo(p);
        toast(ms < 0 ? "⏪ 10 s" : "⏩ 10 s");
        setControls(true);
    }

    /** 🎞️ cuadro por cuadro (en pausa) */
    private void frame(int dir) {
        if (player == null) return;
        player.pause();
        float fps = 30f;
        Format f = player.getVideoFormat();
        if (f != null && f.frameRate > 1) fps = f.frameRate;
        long step = Math.max(1, Math.round(1000f / fps));
        player.seekTo(Math.max(0, player.getCurrentPosition() + dir * step));
        setControls(true);
    }

    private void cycleSpeed() {
        float[] sp = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        int i = 0; for (int k = 0; k < sp.length; k++) if (Math.abs(sp[k] - speed) < 0.01f) i = k;
        setSpeed(sp[(i + 1) % sp.length]);
    }
    private void setSpeed(float v) {
        speed = v; if (player != null) player.setPlaybackSpeed(v);
        String t = (v == Math.round(v) ? String.valueOf(Math.round(v)) : String.valueOf(v)) + "x";
        cSpeed.setText(t); toast("⏱ " + t);
    }

    /* ---- 🔁 A-B ---- */
    private void abTap() {
        if (player == null) return;
        long p = player.getCurrentPosition();
        if (abA < 0) { abA = p; toast(fmt(s("abA", "A marcado en {0} · toca otra vez para marcar B"), mmss(p))); }
        else if (abB < 0) {
            if (p <= abA + 1000) { toast(s("abShort", "B tiene que ir después de A")); return; }
            abB = p; player.seekTo(abA); toast("🔁 " + fmt(s("abOn", "Repitiendo de {0} a {1}"), mmss(abA), mmss(abB)));
        } else { abClear(); toast(s("abOff", "Repetir A-B apagado")); }
        paintAB();
    }
    private void abClear() { abA = -1; abB = -1; paintAB(); }
    private void paintAB() {
        if (cAB == null) return;
        cAB.setText(abA < 0 ? "🔁 A-B" : (abB < 0 ? "A " + mmss(abA) + " → B" : "🔁 " + mmss(abA) + "–" + mmss(abB) + " ✕"));
        cAB.setBackground(round(abB >= 0 ? CYAN : 0x24FFFFFF, 18, CYAN));
        cAB.setTextColor(abB >= 0 ? Color.BLACK : CYAN);
        markA.setVisibility(abA >= 0 ? View.VISIBLE : View.GONE);
        markB.setVisibility(abB >= 0 ? View.VISIBLE : View.GONE);
    }
    private void placeMarks(long dur) {
        if (dur <= 0 || seekBox.getWidth() == 0) return;
        int pad = seek.getPaddingLeft(), w = seekBox.getWidth() - pad - seek.getPaddingRight();
        if (abA >= 0) ((FrameLayout.LayoutParams) markA.getLayoutParams()).leftMargin = pad + (int) (w * abA / dur) - dp(2);
        if (abB >= 0) ((FrameLayout.LayoutParams) markB.getLayoutParams()).leftMargin = pad + (int) (w * abB / dur) - dp(2);
        markA.requestLayout(); markB.requestLayout();
    }

    /* ---- 📸 captura ---- */
    private void shot() {
        if (player == null) return;
        It it = cur(); if (it == null) return;
        VideoSize vs = player.getVideoSize();
        Shots.capture(this, view.getVideoSurfaceView(), vs.width, vs.height, Shots.nameForVideo(it.n, player.getCurrentPosition()), (ok, black, where) -> {
            if (!ok) { toast("⚠️ " + s("shotFail", "No se pudo guardar la captura")); return; }
            flash.setAlpha(0.85f); flash.animate().alpha(0f).setDuration(320).start();
            toast(black ? "📸 " + s("shotBlack", "Guardada, pero salió negra (este video está protegido)") : "📸 " + s("shotSaved", "Guardada en Imágenes/Prometeo"));
        });
    }

    /* ---- 📡 mandar al TV ---- */
    private void castIt() {
        It it = cur(); if (it == null || player == null) return;
        savePos();
        JSONObject e = new JSONObject();
        try { e.put("type", "cast"); e.put("id", it.id); e.put("k", it.k); if (it.u != null) e.put("u", it.u); e.put("pos", player.getCurrentPosition()); e.put("i", idx); } catch (Exception ignored) { }
        emit(e);
        finish();
    }

    private void editReq(String what) {
        It it = cur(); if (it == null) return;
        JSONObject e = new JSONObject();
        try { e.put("type", "edit"); e.put("what", what); e.put("id", it.id); e.put("k", it.k); if (it.u != null) e.put("u", it.u); if (player != null) e.put("pos", player.getCurrentPosition()); } catch (Exception ignored) { }
        savePos(); emit(e); finish();
    }
    private void emit(JSONObject e) { Events ev = events; if (ev != null) try { ev.send(e); } catch (Exception ignored) { } }

    private void savePos() {
        It it = cur(); if (it == null || player == null) return;
        long d = player.getDuration(), p = player.getCurrentPosition();
        if (it.cs >= 0) return; /* la vista previa de un tramo no cambia "Seguir viendo" */
        if (d > 0 && player.getPlaybackState() != Player.STATE_IDLE) Videos.savePos(this, it.k, p, d);
    }
    private void clearPos() { It it = cur(); if (it != null && it.cs < 0 && player != null) Videos.savePos(this, it.k, 0, Math.max(0, player.getDuration())); }

    /* ============================ CONTROLES ============================ */
    private void setControls(boolean show) {
        controlsShown = show && !locked && !inPip;
        int v = controlsShown ? View.VISIBLE : View.GONE;
        topBar.setVisibility(v); centerBar.setVisibility(v); bottomBar.setVisibility(v);
        if (controlsShown) scheduleHide();
    }
    private void scheduleHide() { h.removeCallbacks(hideControls); h.postDelayed(hideControls, 4000); }
    private void setLocked(boolean on) {
        locked = on; closeSheet();
        if (on) { setControls(false); lockBtn.setVisibility(View.VISIBLE); toast(s("lockedHint", "Pantalla bloqueada · mantén el candado para desbloquear")); }
        else { lockBtn.setVisibility(View.GONE); setControls(true); toast("🔓 " + s("unlocked", "Pantalla desbloqueada")); }
    }

    /* ============================ GESTOS ============================ */
    private final class Gestures implements View.OnTouchListener {
        private final GestureDetector gd;
        private int zone = 0; private float startY, startX, startLevel; private boolean scrolling = false, ignore = false;
        Gestures() {
            gd = new GestureDetector(LocalVideoActivity.this, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (locked) { lockBtn.setVisibility(View.VISIBLE); return true; }
                    setControls(!controlsShown); return true;
                }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (locked || root == null) return true;
                    jump(e.getX() < root.getWidth() / 2f ? -10000 : 10000); return true;
                }
            });
        }
        @Override public boolean onTouch(View v, MotionEvent e) {
            gd.onTouchEvent(e);
            if (locked || inPip) return true;
            int w = v.getWidth(), hh = Math.max(1, v.getHeight());
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    zone = e.getX() < w / 2f ? 1 : 3; startY = e.getY(); startX = e.getX(); scrolling = false;
                    ignore = startY < dp(48) || startY > hh - dp(48);
                    startLevel = zone == 1 ? getBrightness() : getVolume();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (ignore) return true;
                    float dy = startY - e.getY(), dx = e.getX() - startX;
                    if (!scrolling) { if (Math.abs(dy) < dp(18)) return true; if (Math.abs(dy) < Math.abs(dx) * 1.5f) { ignore = true; return true; } }
                    scrolling = true;
                    float lv = Math.max(0f, Math.min(1f, startLevel + dy / (hh * 0.7f)));
                    if (zone == 1) setBrightness(lv); else setVolume(lv);
                    showLevel(zone == 1, lv);
                    return true;
                }
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                    h.removeCallbacks(hideLevel); h.postDelayed(hideLevel, 700); scrolling = false; return true;
            }
            return true;
        }
    }
    private final Runnable hideLevel = new Runnable() { public void run() { levelBox.setVisibility(View.GONE); } };
    private void showLevel(boolean bright, float lv) {
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) levelBox.getLayoutParams();
        p.gravity = Gravity.CENTER_VERTICAL | (bright ? Gravity.START : Gravity.END);
        p.leftMargin = bright ? dp(60) : 0; p.rightMargin = bright ? 0 : dp(60);
        levelBox.setLayoutParams(p);
        levelText.setText((bright ? "☀ " : "🔊 ") + Math.round(lv * 100) + "%");
        levelBox.setVisibility(View.VISIBLE); h.removeCallbacks(hideLevel);
    }
    private float getBrightness() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b >= 0) return b;
        try { return android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f; } catch (Exception e) { return 0.5f; }
    }
    private void setBrightness(float v) { WindowManager.LayoutParams lp = getWindow().getAttributes(); lp.screenBrightness = Math.max(0.02f, v); getWindow().setAttributes(lp); }
    private float getVolume() { AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE); return am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)); }
    private void setVolume(float v) { try { AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE); am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0); } catch (Exception ignored) { } }

    /* ============================ AJUSTE DE PANTALLA ============================ */
    private static final String[] ASPECTS = {"fit", "fill", "zoom"};
    private String aspectName(String a) { return "fill".equals(a) ? s("fill", "Rellenar") : ("zoom".equals(a) ? s("zoom", "Zoom") : s("fit", "Ajustar")); }
    private void cycleAspect() {
        int i = 0; for (int k = 0; k < ASPECTS.length; k++) if (ASPECTS[k].equals(aspect)) i = k;
        aspect = ASPECTS[(i + 1) % ASPECTS.length]; prefs.edit().putString("aspect", aspect).apply();
        applyAspect(); cAspect.setText("⛶ " + aspectName(aspect)); toast("⛶ " + aspectName(aspect));
    }
    private void applyAspect() {
        if (view == null) return;
        view.setResizeMode("fill".equals(aspect) ? AspectRatioFrameLayout.RESIZE_MODE_FILL : ("zoom".equals(aspect) ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT));
    }
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); if (root != null) root.post(this::applyAspect); }

    /* ============================ 💬 SUBTÍTULOS ============================ */
    private void applySubStyle() {
        SubtitleView sv = view == null ? null : view.getSubtitleView();
        if (sv == null) return;
        int bg = subBg == 2 ? 0xCC000000 : Color.TRANSPARENT;
        int edge = subBg == 1 ? CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW : CaptionStyleCompat.EDGE_TYPE_NONE;
        sv.setStyle(new CaptionStyleCompat(Color.WHITE, bg, Color.TRANSPARENT, edge, Color.BLACK, null));
        sv.setApplyEmbeddedStyles(false);
        sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * (subSize == 0 ? 0.8f : (subSize == 2 ? 1.35f : 1f)));
    }

    private void pickSubtitle() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        picking = true;
        try { startActivityForResult(i, REQ_SUB); } catch (Exception e) { picking = false; toast("⚠️ " + s("noPicker", "No se pudo abrir el selector de archivos")); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SUB) picking = false;
        if (req != REQ_SUB || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        String n = Subs.nameOf(this, u).toLowerCase(Locale.ROOT);
        if (!(n.endsWith(".srt") || n.endsWith(".vtt") || n.endsWith(".ass") || n.endsWith(".ssa"))) { toast("⚠️ " + s("subType", "Elige un archivo .srt, .vtt o .ass")); return; }
        try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
        It it = cur(); if (it == null) return;
        prefs.edit().putString("s_" + it.k, u.toString()).putLong("o_" + it.k, 0).apply();
        subsOff = false;
        reloadKeep();
        toast("💬 " + Subs.nameOf(this, u));
    }

    private void reloadKeep() {
        if (player == null) return;
        long p = player.getCurrentPosition(); boolean play = player.getPlayWhenReady();
        startCurrent(p);
        player.setPlayWhenReady(play);
    }

    private void subOffset(long d) {
        It it = cur(); if (it == null) return;
        long o = prefs.getLong("o_" + it.k, 0) + d;
        prefs.edit().putLong("o_" + it.k, o).apply();
        reloadKeep();
        toast("💬 " + (o > 0 ? "+" : "") + String.format(Locale.ROOT, "%.1f s", o / 1000f));
    }

    private void removeSub() {
        It it = cur(); if (it == null) return;
        String su = prefs.getString("s_" + it.k, null);
        prefs.edit().remove("s_" + it.k).remove("o_" + it.k).apply();
        if (su != null) try { getContentResolver().releasePersistableUriPermission(Uri.parse(su), Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
        reloadKeep();
    }

    /* ============================ HOJA: velocidad, audio, subtítulos ============================ */
    private void buildSheet() {
        sheetScrim = new FrameLayout(this);
        sheetScrim.setBackgroundColor(0x73000000);
        sheetScrim.setOnClickListener(v -> closeSheet());
        sheetScrim.setVisibility(View.GONE);
        ScrollView sc = new ScrollView(this);
        sheet = new LinearLayout(this); sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(14), dp(20), dp(18));
        sc.addView(sheet);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xFF161616);
        float r = dp(22); bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0}); bg.setStroke(dp(1), LINE);
        sc.setBackground(bg); sc.setOnClickListener(v -> { });
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sheetScrim.addView(sc, sp);
        root.addView(sheetScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
    private boolean sheetOpen() { return sheetScrim != null && sheetScrim.getVisibility() == View.VISIBLE; }
    private void openSheet() {
        fillSheet();
        View sc = (View) sheet.getParent();
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) sc.getLayoutParams();
        p.width = root.getWidth() > dp(820) ? dp(760) : ViewGroup.LayoutParams.MATCH_PARENT;
        p.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        sc.setLayoutParams(p);
        sheetScrim.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideControls);
    }
    private void closeSheet() { if (sheetScrim != null) sheetScrim.setVisibility(View.GONE); }

    private LinearLayout chipRow(String label) {
        TextView l = text(label.toUpperCase(Locale.getDefault()), 10, 0xFF9A9A9A, true);
        l.setShadowLayer(0, 0, 0, 0); l.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.setMargins(0, dp(12), 0, dp(6));
        sheet.addView(l, lp);
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(row); sheet.addView(hs);
        return row;
    }
    private void chip(LinearLayout row, String t, boolean on, View.OnClickListener l) {
        TextView c = text(t, 12, on ? Color.BLACK : 0xFFCCCCCC, true);
        c.setShadowLayer(0, 0, 0, 0); c.setGravity(Gravity.CENTER); c.setPadding(dp(12), 0, dp(12), 0);
        c.setBackground(on ? round(CYAN, 16, 0) : round(0x00000000, 16, 0xFF333333));
        c.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)); p.setMargins(0, 0, dp(6), 0);
        row.addView(c, p);
    }
    private static String trackName(Format f, int n, String fallback) {
        if (f.label != null && f.label.length() > 0) return f.label;
        String lang = f.language;
        if (lang != null && lang.length() > 0 && !"und".equals(lang)) {
            String d = Locale.forLanguageTag(lang).getDisplayLanguage(Locale.getDefault());
            if (d != null && d.length() > 0) return Character.toUpperCase(d.charAt(0)) + d.substring(1);
        }
        return fallback + " " + n;
    }

    private void fillSheet() {
        sheet.removeAllViews();
        View handle = new View(this); handle.setBackground(round(0xFF3A3A3A, 3, 0));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(44), dp(5)); hp.gravity = Gravity.CENTER_HORIZONTAL; hp.bottomMargin = dp(4);
        sheet.addView(handle, hp);

        /* 💬 subtítulos */
        List<Tracks.Group> audio = new ArrayList<>(), subs = new ArrayList<>();
        if (player != null) for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() == C.TRACK_TYPE_AUDIO) audio.add(g);
            else if (g.getType() == C.TRACK_TYPE_TEXT && g.isSupported()) subs.add(g);
        }
        /* v44: ✂️ cortar / 🎵 extraer audio (se hace en la app; el reproductor se cierra) */
        final It ci = cur();
        if (ci != null && ci.cs < 0) {
            LinearLayout re = chipRow("🛠 " + s("tools", "Herramientas"));
            chip(re, "✂️ " + s("cut", "Cortar video"), false, v -> editReq("cut"));
            chip(re, "🎵 " + s("extract", "Extraer audio"), false, v -> editReq("audio"));
        }
        LinearLayout r1 = chipRow("💬 " + s("subs", "Subtítulos"));
        final boolean off = player == null || player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
        chip(r1, s("subsOff", "Sin subtítulos"), off, v -> {
            subsOff = true;
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            h.postDelayed(this::fillSheet, 300);
        });
        int n = 1;
        for (final Tracks.Group g : subs) {
            chip(r1, trackName(g.getTrackFormat(0), n++, s("subs", "Subtítulos")), !off && g.isSelected(), v -> {
                subsOff = false;
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), 0)).build());
                h.postDelayed(this::fillSheet, 300);
            });
        }
        chip(r1, "📂 " + s("loadSubs", "Cargar subtítulos (.srt, .vtt, .ass)…"), false, v -> pickSubtitle());
        if (extSub != null) {
            chip(r1, "✕ " + extName, false, v -> { removeSub(); h.postDelayed(this::fillSheet, 300); });
            It it = cur(); long o = it == null ? 0 : prefs.getLong("o_" + it.k, 0);
            LinearLayout r2 = chipRow(s("subTime", "Tiempo de los subtítulos"));
            chip(r2, "−0,5 s", false, v -> { subOffset(-500); fillSheet(); });
            chip(r2, (o > 0 ? "+" : "") + String.format(Locale.getDefault(), "%.1f s", o / 1000f), true, v -> { });
            chip(r2, "+0,5 s", false, v -> { subOffset(500); fillSheet(); });
        }
        LinearLayout r3 = chipRow(s("subSize", "Tamaño"));
        String[] sz = {"A−", s("normal", "Normal"), "A+"};
        for (int i = 0; i < 3; i++) { final int fi = i; chip(r3, sz[i], subSize == i, v -> { subSize = fi; prefs.edit().putInt("subSize", fi).apply(); applySubStyle(); fillSheet(); }); }
        LinearLayout r4 = chipRow(s("subBg", "Fondo"));
        String[] bgs = {s("bgNone", "Sin fondo"), s("bgShadow", "Sombra"), s("bgBlack", "Negro")};
        for (int i = 0; i < 3; i++) { final int fi = i; chip(r4, bgs[i], subBg == i, v -> { subBg = fi; prefs.edit().putInt("subBg", fi).apply(); applySubStyle(); fillSheet(); }); }

        /* 🔊 audio del video */
        if (!audio.isEmpty()) {
            LinearLayout r5 = chipRow("🔊 " + s("audioTrack", "Audio del video"));
            int k = 1;
            for (final Tracks.Group g : audio) {
                Format f = g.getTrackFormat(0);
                String name = trackName(f, k++, s("audio", "Audio")) + " · " + audioName(f.sampleMimeType) + (g.isSupported() ? "" : " 🔇");
                chip(r5, name, g.isSelected(), v -> {
                    if (!g.isSupported()) { toast("🔇 " + fmt(s("noAudio", "Este audio ({0}) no es compatible"), audioName(f.sampleMimeType))); return; }
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), 0)).build());
                    h.postDelayed(this::fillSheet, 300);
                });
            }
        }
        /* ⏱ velocidad y ⛶ pantalla */
        LinearLayout r6 = chipRow("⏱ " + s("speed", "Velocidad"));
        float[] sp = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        for (float v0 : sp) { final float fv = v0; chip(r6, (fv == Math.round(fv) ? String.valueOf(Math.round(fv)) : String.valueOf(fv)) + "x", Math.abs(fv - speed) < 0.01f, v -> { setSpeed(fv); fillSheet(); }); }
        LinearLayout r7 = chipRow("⛶ " + s("screenFit", "Ajuste de pantalla"));
        for (String a : ASPECTS) { final String fa = a; chip(r7, aspectName(a), a.equals(aspect), v -> { aspect = fa; prefs.edit().putString("aspect", fa).apply(); applyAspect(); cAspect.setText("⛶ " + aspectName(fa)); fillSheet(); }); }
    }

    /* ============================ VENTANA FLOTANTE (PiP) ============================ */
    private boolean pipSupported() { return Build.VERSION.SDK_INT >= 26 && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE); }
    private PictureInPictureParams pipParams() {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
        Rational r = new Rational(16, 9);
        if (player != null) {
            VideoSize vs = player.getVideoSize();
            if (vs.width > 0 && vs.height > 0) {
                float k = Math.max(0.42f, Math.min(2.38f, vs.width * (vs.pixelWidthHeightRatio > 0 ? vs.pixelWidthHeightRatio : 1f) / vs.height));
                r = new Rational(Math.round(k * 1000), 1000);
            }
        }
        b.setAspectRatio(r);
        List<RemoteAction> acts = new ArrayList<>();
        boolean playing = player != null && player.isPlaying();
        if (items.size() > 1) acts.add(remote(android.R.drawable.ic_media_previous, s("prevV", "Video anterior"), PIP_PREV, 11));
        acts.add(remote(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? s("pause", "Pausa") : s("play", "Reproducir"), PIP_TOGGLE, 12));
        if (items.size() > 1) acts.add(remote(android.R.drawable.ic_media_next, s("nextV", "Video siguiente"), PIP_NEXT, 13));
        b.setActions(acts);
        if (Build.VERSION.SDK_INT >= 31) { b.setAutoEnterEnabled(false); b.setSeamlessResizeEnabled(true); }
        return b.build();
    }
    private RemoteAction remote(int icon, String title, String action, int req) {
        PendingIntent pi = PendingIntent.getBroadcast(this, req, new Intent(action).setPackage(getPackageName()), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new RemoteAction(Icon.createWithResource(this, icon), title, title, pi);
    }
    private void updatePip() { if (pipSupported()) try { setPictureInPictureParams(pipParams()); } catch (Exception ignored) { } }
    private void enterPip() {
        if (!pipSupported()) return;
        closeSheet();
        try { enterPictureInPictureMode(pipParams()); } catch (Exception e) { toast(s("noPip", "La ventana flotante está desactivada en los ajustes del teléfono")); }
    }
    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, Configuration c) {
        super.onPictureInPictureModeChanged(isInPip, c);
        inPip = isInPip;
        if (isInPip) {
            setControls(false); closeSheet();
            pipReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent i) {
                    String a = i.getAction();
                    if (PIP_PREV.equals(a)) change(-1); else if (PIP_NEXT.equals(a)) change(1); else if (PIP_TOGGLE.equals(a)) togglePlay();
                    updatePip();
                }
            };
            IntentFilter f = new IntentFilter(); f.addAction(PIP_PREV); f.addAction(PIP_NEXT); f.addAction(PIP_TOGGLE);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(pipReceiver, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(pipReceiver, f);
        } else {
            if (pipReceiver != null) { try { unregisterReceiver(pipReceiver); } catch (Exception ignored) { } pipReceiver = null; }
            setControls(true);
        }
    }

    /* ============================ CICLO DE VIDA ============================ */
    private boolean wasPlaying = true;
    @Override protected void onStart() { super.onStart(); if (player != null && wasPlaying) player.play(); }
    @Override
    protected void onStop() {
        super.onStop();
        if (isChangingConfigurations()) return;
        savePos();
        if (inPip) { inPip = false; if (player != null) player.pause(); finish(); return; }
        if (player != null) { wasPlaying = player.isPlaying(); player.pause(); }
        /* v43b: mientras eliges subtítulos con el selector de Android, el video espera (no se cierra) */
        if (picking) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isInteractive() && !isFinishing()) finish();
    }
    @Override
    public void onBackPressed() {
        if (locked) { lockBtn.setVisibility(View.VISIBLE); return; }
        if (sheetOpen()) { closeSheet(); return; }
        super.onBackPressed();
    }
    @Override
    protected void onDestroy() {
        savePos();
        h.removeCallbacksAndMessages(null);
        if (pipReceiver != null) { try { unregisterReceiver(pipReceiver); } catch (Exception ignored) { } pipReceiver = null; }
        It it = cur();
        if (view != null) view.setPlayer(null);
        if (player != null) { player.release(); player = null; }
        if (fxSession > 0) { AudioFx.detach(fxSession); fxSession = 0; }
        JSONObject e = new JSONObject();
        try { e.put("type", "closed"); if (it != null) { e.put("id", it.id); e.put("k", it.k); } } catch (Exception ignored) { }
        emit(e);
        super.onDestroy();
    }
}
