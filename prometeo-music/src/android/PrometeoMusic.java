package com.prometeo.music;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Metadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.metadata.icy.IcyInfo;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaPluginPathHandler;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;

/**
 * Prometeo Music: lista la música del teléfono (MediaStore) y la sirve a la página
 * en https://localhost/__prometeo_music__/<id> SIN copiarla.
 * Solo pide un permiso: "Música y audio" (Android 13+) o "Almacenamiento" (Android 12 o menos).
 * No usa internet ni envía datos a ningún lado.
 */
@OptIn(markerClass = UnstableApi.class)
public class PrometeoMusic extends CordovaPlugin {
    private static final int REQ_PERM = 4141;
    private static final String PREFIX = "__prometeo_music__/";
    private CallbackContext permCallback;
    private CordovaPluginPathHandler pathHandler;

    private static String readPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private static Uri collection() {
        return Build.VERSION.SDK_INT >= 29
                ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
    }

    /* Cuando se desconecta el Bluetooth o los audífonos, Android avisa: se pausa todo (música y radio)
       para que no siga sonando por el parlante del teléfono. */
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) sendControl("pause", -1);
        }
    };
    private boolean noisyRegistered = false;

    /* Al conectar el Bluetooth, muchos carros mandan "Play" solos. Lo ignoramos unos segundos
       para que Prometeo no arranque sin que tú lo pidas (el volante funciona normal después). */
    private static final long BT_IGNORE_PLAY_MS = 6000;
    private volatile long btConnectedAt = 0;
    private volatile long lastRemotePlayAt = 0;
    private volatile boolean sessionPlaying = false;
    private boolean btCallbackPrimed = false;
    private final android.media.AudioDeviceCallback btCallback = new android.media.AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(android.media.AudioDeviceInfo[] added) {
            /* La primera llamada solo lista lo que ya estaba conectado al abrir la app */
            if (!btCallbackPrimed) { btCallbackPrimed = true; return; }
            for (android.media.AudioDeviceInfo d : added) {
                if (d.isSink() && (d.getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || d.getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO)) {
                    btConnectedAt = SystemClock.elapsedRealtime();
                    /* Si el "Play" del carro llegó justo antes de que Android avisara la conexión, se deshace */
                    if (lastRemotePlayAt > 0 && btConnectedAt - lastRemotePlayAt < 3000) { lastRemotePlayAt = 0; sendControl("pause", -1); }
                }
            }
        }
    };
    private boolean btRegistered = false;

    private void registerBluetoothWatch() {
        try {
            AudioManager am = (AudioManager) cordova.getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am != null) { am.registerAudioDeviceCallback(btCallback, new Handler(Looper.getMainLooper())); btRegistered = true; }
        } catch (Exception ignored) { }
    }

    private boolean justConnectedBluetooth() {
        return btConnectedAt > 0 && SystemClock.elapsedRealtime() - btConnectedAt < BT_IGNORE_PLAY_MS;
    }

    private void registerNoisy() {
        try {
            IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if (Build.VERSION.SDK_INT >= 33) cordova.getContext().registerReceiver(noisyReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else cordova.getContext().registerReceiver(noisyReceiver, f);
            noisyRegistered = true;
        } catch (Exception ignored) { }
    }

    /** Radio: deja que la página (https://localhost) reproduzca emisoras que transmiten con http:// */
    @Override
    protected void pluginInitialize() {
        registerNoisy();
        registerBluetoothWatch();
        registerNetworkWatch();
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                try {
                    View v = webView.getView();
                    if (v instanceof WebView) {
                        ((WebView) v).getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
                    }
                } catch (Exception ignored) { }
            }
        });
    }

    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext cb) throws JSONException {
        if ("hasPermission".equals(action)) {
            cb.success(cordova.hasPermission(readPermission()) ? 1 : 0);
            return true;
        }
        if ("requestPermission".equals(action)) {
            if (cordova.hasPermission(readPermission())) { cb.success(1); return true; }
            if (permCallback != null) { cb.error("busy"); return true; }
            permCallback = cb;
            cordova.requestPermission(this, REQ_PERM, readPermission());
            return true;
        }
        /* ---- Sesión multimedia: lo que ve el carro por Bluetooth (y los botones del volante) ---- */
        if ("nowPlaying".equals(action)) {
            final JSONObject o = args.optJSONObject(0);
            cordova.getThreadPool().execute(new Runnable() {
                public void run() { try { setNowPlaying(o); cb.success(); } catch (Exception e) { cb.error(String.valueOf(e.getMessage())); } }
            });
            return true;
        }
        if ("playState".equals(action)) {
            try { setPlayState(args.optBoolean(0, false), args.optLong(1, 0)); cb.success(); }
            catch (Exception e) { cb.error(String.valueOf(e.getMessage())); }
            return true;
        }
        if ("controls".equals(action)) {
            controlsCallback = cb;
            PluginResult r = new PluginResult(PluginResult.Status.NO_RESULT);
            r.setKeepCallback(true);
            cb.sendPluginResult(r);
            return true;
        }
        /* ---- Radio con ExoPlayer ---- */
        if ("radioEvents".equals(action)) {
            radioCallback = cb;
            PluginResult r = new PluginResult(PluginResult.Status.NO_RESULT);
            r.setKeepCallback(true);
            cb.sendPluginResult(r);
            return true;
        }
        if ("radioPlay".equals(action)) {
            final String url = args.optString(0, "");
            final String name = args.optString(1, "");
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    try { radioPlay(url, name); cb.success(); } catch (Exception e) { cb.error(String.valueOf(e.getMessage())); }
                }
            });
            return true;
        }
        if ("radioPause".equals(action) || "radioStop".equals(action)) {
            final boolean stop = "radioStop".equals(action);
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    radioWanted = false; cancelRetry();
                    if (radio != null) { if (stop) { radio.stop(); radio.clearMediaItems(); } else radio.pause(); }
                    /* Al quitar la radio se apaga el servicio; en pausa se mantiene para poder seguir desde el volante */
                    if (stop) RadioService.stop(cordova.getContext());
                    cb.success();
                }
            });
            return true;
        }
        /* ---- Video / TV en pantalla completa ---- */
        if ("playVideo".equals(action)) {
            Intent i = new Intent(cordova.getActivity(), VideoActivity.class);
            i.putExtra("url", args.optString(0, ""));
            i.putExtra("title", args.optString(1, ""));
            i.putExtra("error", args.optString(2, ""));
            cordova.getActivity().startActivity(i);
            cb.success();
            return true;
        }
        /* ---- Descargar una lista M3U (sin las restricciones del navegador) ---- */
        if ("fetchText".equals(action)) {
            final String url = args.optString(0, "");
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    try { cb.success(fetchText(url)); } catch (Exception e) { cb.error(String.valueOf(e.getMessage())); }
                }
            });
            return true;
        }
        if ("scan".equals(action)) {
            if (!cordova.hasPermission(readPermission())) { cb.error("permission"); return true; }
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    try { cb.success(scan()); }
                    catch (Exception e) { cb.error(String.valueOf(e.getMessage())); }
                }
            });
            return true;
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_PERM || permCallback == null) return;
        boolean ok = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        CallbackContext cb = permCallback;
        permCallback = null;
        cb.success(ok ? 1 : 0);
    }

    /** Lista todas las canciones del teléfono, sin tonos de llamada, notificaciones ni alarmas. */
    private JSONArray scan() throws JSONException {
        JSONArray out = new JSONArray();
        ContentResolver cr = cordova.getContext().getContentResolver();
        String[] cols = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.MIME_TYPE
        };
        String where = MediaStore.Audio.Media.IS_RINGTONE + "=0 AND "
                + MediaStore.Audio.Media.IS_NOTIFICATION + "=0 AND "
                + MediaStore.Audio.Media.IS_ALARM + "=0 AND "
                + MediaStore.Audio.Media.SIZE + ">0";
        Cursor c = cr.query(collection(), cols, where, null, null);
        if (c == null) return out;
        try {
            int iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
            int iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
            int iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE);
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("id", c.getLong(iId));
                o.put("name", c.isNull(iName) ? "" : c.getString(iName));
                o.put("title", c.isNull(iTitle) ? "" : c.getString(iTitle));
                o.put("artist", c.isNull(iArtist) ? "" : c.getString(iArtist));
                o.put("duration", c.isNull(iDur) ? 0 : c.getLong(iDur));
                o.put("size", c.isNull(iSize) ? 0 : c.getLong(iSize));
                o.put("mime", c.isNull(iMime) ? "" : c.getString(iMime));
                out.put(o);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /* ================= SESIÓN MULTIMEDIA ================= */
    private MediaSession session;
    private CallbackContext controlsCallback;
    private long lastDuration = 0;

    private synchronized MediaSession session() {
        if (session != null) return session;
        session = new MediaSession(cordova.getContext(), "PrometeoPlayer");
        setLegacyFlags(session);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() {
                if (justConnectedBluetooth()) return;
                /* Solo se marca si Prometeo estaba en pausa: si ya sonaba (p. ej. subiste al carro escuchando), no se toca */
                lastRemotePlayAt = sessionPlaying ? 0 : SystemClock.elapsedRealtime();
                sendControl("play", -1);
            }
            @Override public void onPause() { sendControl("pause", -1); }
            @Override public void onStop() { sendControl("pause", -1); }
            @Override public void onSkipToNext() { sendControl("next", -1); }
            @Override public void onSkipToPrevious() { sendControl("prev", -1); }
            @Override public void onSeekTo(long pos) { sendControl("seek", pos); }
        }, new Handler(Looper.getMainLooper()));
        return session;
    }

    @SuppressWarnings("deprecation")
    private static void setLegacyFlags(MediaSession s) {
        /* En Android 8+ ya no hace falta; en versiones viejas activa los botones del volante */
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
    }

    private void sendControl(String what, long pos) {
        CallbackContext cb = controlsCallback;
        if (cb == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("action", what);
            if (pos >= 0) o.put("pos", pos);
            PluginResult r = new PluginResult(PluginResult.Status.OK, o);
            r.setKeepCallback(true);
            cb.sendPluginResult(r);
        } catch (JSONException ignored) { }
    }

    private void setNowPlaying(JSONObject o) {
        if (o == null) return;
        MediaMetadata.Builder b = new MediaMetadata.Builder();
        String title = o.optString("title", "");
        b.putString(MediaMetadata.METADATA_KEY_TITLE, title);
        b.putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title);
        String artist = o.optString("artist", "");
        if (!artist.isEmpty()) {
            b.putString(MediaMetadata.METADATA_KEY_ARTIST, artist);
            b.putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist);
        }
        String album = o.optString("album", "");
        if (!album.isEmpty()) b.putString(MediaMetadata.METADATA_KEY_ALBUM, album);
        lastDuration = o.optLong("duration", 0);
        if (lastDuration > 0) b.putLong(MediaMetadata.METADATA_KEY_DURATION, lastDuration);
        String art = o.optString("art", "");
        if (!art.isEmpty()) {
            try {
                byte[] bytes = Base64.decode(art, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp != null) {
                    int max = Math.max(bmp.getWidth(), bmp.getHeight());
                    if (max > 500) {
                        float k = 500f / max;
                        bmp = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * k)), Math.max(1, Math.round(bmp.getHeight() * k)), true);
                    }
                    b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp);
                    b.putBitmap(MediaMetadata.METADATA_KEY_ART, bmp);
                }
            } catch (Exception ignored) { }
        }
        MediaSession s = session();
        s.setMetadata(b.build());
        if (!s.isActive()) s.setActive(true);
    }

    private void setPlayState(boolean playing, long positionMs) {
        sessionPlaying = playing;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
        PlaybackState st = new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        Math.max(0, positionMs), playing ? 1f : 0f, SystemClock.elapsedRealtime())
                .build();
        MediaSession s = session();
        s.setPlaybackState(st);
        if (!s.isActive()) s.setActive(true);
    }

    /* ================= RADIO CON EXOPLAYER ================= */
    private ExoPlayer radio;
    private CallbackContext radioCallback;

    private void radioEvent(String type, String value) {
        CallbackContext cb = radioCallback;
        if (cb == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("value", value == null ? "" : value);
            PluginResult r = new PluginResult(PluginResult.Status.OK, o);
            r.setKeepCallback(true);
            cb.sendPluginResult(r);
        } catch (JSONException ignored) { }
    }

    /* ================= RECONEXIÓN AUTOMÁTICA ================= */
    /* Si se cae la señal (túnel, wifi que se va), reintenta con calma: 2, 5, 10, 20 s y luego cada 30 s
       hasta unos 2 minutos. Si vuelve el internet antes, reconecta en ese momento. Si el link está malo, no insiste. */
    private static final long[] RETRY_DELAYS_MS = {2000, 5000, 10000, 20000, 30000, 30000, 30000};
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean radioWanted = false;
    private String radioUrl;
    private int retryCount = 0;
    private boolean retryPending = false;
    private android.os.PowerManager.WakeLock retryLock;
    private final Runnable retryRunnable = new Runnable() {
        @Override public void run() {
            retryPending = false;
            if (!radioWanted || radio == null || radioUrl == null) return;
            radio.stop();
            radio.setMediaItem(MediaItem.fromUri(radioUrl));
            radio.prepare();
            radio.play();
        }
    };

    private boolean retrying() { return retryCount > 0 && radioWanted; }

    private void scheduleRetry() {
        if (retryPending) return;
        if (retryCount >= RETRY_DELAYS_MS.length) {
            /* Se rindió: queda en pausa y avisa */
            radioWanted = false; cancelRetry();
            radioEvent("error", "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED");
            return;
        }
        long delay = RETRY_DELAYS_MS[retryCount++];
        retryPending = true;
        holdRetryLock();
        radioEvent("state", "reconnecting");
        RadioService.setReconnecting(cordova.getContext(), true);
        mainHandler.postDelayed(retryRunnable, delay);
    }

    private void cancelRetry() {
        mainHandler.removeCallbacks(retryRunnable);
        RadioService.setReconnecting(cordova.getContext(), false);
        retryPending = false;
        if (!radioWanted) retryCount = 0;
        releaseRetryLock();
    }

    /** Volvió el internet: si estaba esperando para reintentar, lo hace ya. */
    private void retryNow() {
        if (!retryPending || !radioWanted) return;
        mainHandler.removeCallbacks(retryRunnable);
        mainHandler.postDelayed(retryRunnable, 500);
    }

    /* Mantiene el procesador despierto solo mientras reintenta (máximo 3 minutos), para que con la pantalla apagada no se duerma */
    private void holdRetryLock() {
        try {
            if (retryLock == null) {
                android.os.PowerManager pm = (android.os.PowerManager) cordova.getContext().getSystemService(Context.POWER_SERVICE);
                retryLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Prometeo:radioReconnect");
                retryLock.setReferenceCounted(false);
            }
            retryLock.acquire(180000);
        } catch (Exception ignored) { }
    }

    private void releaseRetryLock() {
        try { if (retryLock != null && retryLock.isHeld()) retryLock.release(); } catch (Exception ignored) { }
    }

    /** ¿Fue un problema de señal/servidor (vale la pena reintentar) o un link malo (no insistir)? */
    private static boolean isNetworkProblem(PlaybackException e) {
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED:
            case PlaybackException.ERROR_CODE_TIMEOUT:
            case PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW:
            case PlaybackException.ERROR_CODE_UNSPECIFIED:
                return true;
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                /* 500-599 = el servidor de la emisora tuvo un problema momentáneo; 404 = link malo */
                Throwable c = e.getCause();
                if (c instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)
                    return ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) c).responseCode >= 500;
                return false;
            default:
                return false;
        }
    }

    /* Aviso de Android cuando vuelve el internet (permiso normal, no muestra ningún aviso al usuario) */
    private android.net.ConnectivityManager.NetworkCallback netCallback;

    private void registerNetworkWatch() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) cordova.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            netCallback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network network) {
                    mainHandler.post(new Runnable() { public void run() { retryNow(); } });
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception ignored) { netCallback = null; }
    }

    private void unregisterNetworkWatch() {
        if (netCallback == null) return;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) cordova.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(netCallback);
        } catch (Exception ignored) { }
        netCallback = null;
    }

    private void radioPlay(String url, String name) {
        if (radio == null) {
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent("PrometeoPlayer/41")
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(20000);
            radio = new ExoPlayer.Builder(cordova.getContext())
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(cordova.getContext(), http)))
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                    .build();
            radio.setHandleAudioBecomingNoisy(true);   /* si se desconecta el Bluetooth, se pausa */
            /* Mantiene el wifi y el procesador despiertos mientras suena la radio con la pantalla apagada */
            radio.setWakeMode(C.WAKE_MODE_NETWORK);
            radio.addListener(new Player.Listener() {
                @Override public void onIsPlayingChanged(boolean playing) {
                    if (radio == null) return;
                    if (playing) { retryCount = 0; cancelRetry(); radioEvent("state", "online"); return; }
                    int st = radio.getPlaybackState();
                    if (st == Player.STATE_BUFFERING) return;
                    /* Si se cayó la señal, decide onPlayerError / STATE_ENDED (reconectar), no "pausa" */
                    if (radioWanted && (radio.getPlayerError() != null || st == Player.STATE_ENDED || st == Player.STATE_IDLE)) return;
                    radioEvent("state", "paused");
                }
                @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                    /* Pausa hecha por Android (Bluetooth desconectado, llamada, otra app): no reconectar */
                    if (!playWhenReady) { radioWanted = false; cancelRetry(); }
                }
                @Override public void onPlaybackStateChanged(int s) {
                    if (s == Player.STATE_BUFFERING) { if (!retrying()) radioEvent("state", "connecting"); }
                    else if (s == Player.STATE_ENDED) {
                        /* Una radio en vivo no "termina": si el servidor cortó, es una caída → reconectar */
                        if (radioWanted) scheduleRetry(); else radioEvent("state", "paused");
                    }
                }
                @Override public void onPlayerError(PlaybackException error) {
                    if (radioWanted && isNetworkProblem(error)) scheduleRetry();
                    else { radioWanted = false; cancelRetry(); radioEvent("error", error.getErrorCodeName()); }
                }
                /* "Sonando ahora": título que envían muchas emisoras (ICY) */
                @Override public void onMetadata(Metadata metadata) {
                    for (int i = 0; i < metadata.length(); i++) {
                        Metadata.Entry e = metadata.get(i);
                        if (e instanceof IcyInfo && ((IcyInfo) e).title != null) radioEvent("title", ((IcyInfo) e).title);
                    }
                }
            });
        }
        cancelRetry();
        retryCount = 0;
        radioUrl = url;
        radioWanted = true;
        radio.stop();
        radio.setMediaItem(MediaItem.fromUri(url));
        radio.prepare();
        radio.play();
        radioEvent("state", "connecting");
        /* Servicio en primer plano: así Android no le corta el internet a la radio en segundo plano */
        RadioService.sessionToken = session().getSessionToken();
        if (RadioService.running) RadioService.update(cordova.getContext(), name);
        else RadioService.start(cordova.getContext(), name);
    }

    /* ================= DESCARGAR LISTAS M3U ================= */
    private static String fetchText(String address) throws Exception {
        URL url = new URL(address);
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "PrometeoPlayer/41");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(url, c.getHeaderField("Location"));
                c.disconnect();
                continue;
            }
            if (code >= 400) { c.disconnect(); throw new Exception("HTTP " + code); }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 30 * 1024 * 1024) { in.close(); c.disconnect(); throw new Exception("too big"); }
                out.write(buf, 0, n);
            }
            in.close();
            c.disconnect();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        throw new Exception("too many redirects");
    }

    @Override
    public void onDestroy() {
        if (session != null) { session.setActive(false); session.release(); session = null; }
        radioWanted = false; cancelRetry(); unregisterNetworkWatch();
        if (radio != null) { radio.release(); radio = null; }
        RadioService.stop(cordova.getContext());
        if (btRegistered) {
            try { ((AudioManager) cordova.getContext().getSystemService(Context.AUDIO_SERVICE)).unregisterAudioDeviceCallback(btCallback); } catch (Exception ignored) { }
            btRegistered = false;
        }
        if (noisyRegistered) { try { cordova.getContext().unregisterReceiver(noisyReceiver); } catch (Exception ignored) { } noisyRegistered = false; }
        controlsCallback = null;
        radioCallback = null;
    }

    /** Sirve el archivo original directamente a la página (no se copia nada). */
    @Override
    public CordovaPluginPathHandler getPathHandler() {
        if (pathHandler == null) {
            pathHandler = new CordovaPluginPathHandler(path -> {
                if (path == null || !path.startsWith(PREFIX)) return null;
                try {
                    long id = Long.parseLong(path.substring(PREFIX.length()).replaceAll("[^0-9]", ""));
                    if (!cordova.hasPermission(readPermission())) return null;
                    ContentResolver cr = cordova.getContext().getContentResolver();
                    Uri uri = ContentUris.withAppendedId(collection(), id);
                    InputStream in = cr.openInputStream(uri);
                    if (in == null) return null;
                    String mime = cr.getType(uri);
                    return new WebResourceResponse(mime != null ? mime : "audio/mpeg", null, in);
                } catch (Exception e) {
                    return null;
                }
            });
        }
        return pathHandler;
    }
}
