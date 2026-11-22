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
    private static final String PREFIX_URI = "__prometeo_uri__/";
    private CallbackContext permCallback;
    private CordovaPluginPathHandler pathHandler;

    /* v43: permiso de "Videos" (aparte del de música) */
    private static final int REQ_VPERM = 4143;
    private CallbackContext vpermCallback, videoCallback;
    private static final String PREFIX_VTHUMB = "__prometeo_vthumb__/";
    private boolean pickVideoMode = false;
    private static final String PERM_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED";
    /** 1 = todos los videos, 2 = acceso limitado (solo los que el usuario eligió), 0 = sin permiso */
    private int videoAccess() {
        if (cordova.hasPermission(videoPermission())) return 1;
        if (Build.VERSION.SDK_INT >= 34 && cordova.hasPermission(PERM_USER_SELECTED)) return 2;
        return 0;
    }
    private static String videoPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_EXTERNAL_STORAGE;
    }
    private void sendVideo(JSONObject o) {
        CallbackContext c = videoCallback; if (c == null) return;
        PluginResult r = new PluginResult(PluginResult.Status.OK, o); r.setKeepCallback(true); c.sendPluginResult(r);
    }

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
        registerVolumeWatch();
        /* Botones de la notificación de música (Android 12 o menos): igual que los del volante */
        RadioService.controls = new RadioService.Controls() {
            public void on(String what) { sendControl(what, -1); }
        };
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
        /* ---- v43: 🎬 videos del teléfono ---- */
        if ("hasVideoPermission".equals(action)) { cb.success(videoAccess()); return true; }
        if ("requestVideoPermission".equals(action)) {
            /* more = true: "＋ Elegir más" con acceso limitado (Android vuelve a mostrar su selector) */
            if (videoAccess() == 1) { cb.success(1); return true; }
            if (vpermCallback != null) { cb.error("busy"); return true; }
            vpermCallback = cb;
            if (Build.VERSION.SDK_INT >= 34) cordova.requestPermissions(this, REQ_VPERM, new String[]{ Manifest.permission.READ_MEDIA_VIDEO, PERM_USER_SELECTED });
            else cordova.requestPermission(this, REQ_VPERM, videoPermission());
            return true;
        }
        if ("pickVideo".equals(action)) {
            /* v43b: 🎬 elegir videos sueltos con el selector de Android */
            if (pickCallback != null) pickCallback.error("cancel");
            pickCallback = cb;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("video/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            pickVideoMode = true;
            try { cordova.startActivityForResult(this, i, REQ_PICK); }
            catch (Exception e) { pickCallback = null; cb.error("nopicker"); }
            return true;
        }
        if ("videoDurations".equals(action)) {
            final JSONArray us = args.optJSONArray(0);
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    JSONObject out = new JSONObject();
                    for (int i = 0; us != null && i < us.length() && i < 400; i++) {
                        String u = us.optString(i, "");
                        try { if (Library.uriAllowed(cordova.getContext(), Uri.parse(u))) out.put(u, Videos.durationDoc(cordova.getContext(), Uri.parse(u))); } catch (Exception ignored) { }
                    }
                    cb.success(out);
                }
            });
            return true;
        }
        if ("scanVideos".equals(action)) {
            if (videoAccess() == 0) { cb.error("permission"); return true; }
            cordova.getThreadPool().execute(new Runnable() {
                public void run() { try { cb.success(Videos.scan(cordova.getContext())); } catch (Exception e) { cb.error(String.valueOf(e.getMessage())); } }
            });
            return true;
        }
        if ("videoState".equals(action)) {
            try { cb.success(Videos.state(cordova.getContext())); } catch (Exception e) { cb.error(String.valueOf(e.getMessage())); }
            return true;
        }
        if ("videoMovePos".equals(action)) {
            Videos.movePos(cordova.getContext(), args.optString(0, ""), args.optString(1, ""));
            cb.success();
            return true;
        }
        if ("videoEvents".equals(action)) {
            videoCallback = cb;
            LocalVideoActivity.events = new LocalVideoActivity.Events() { public void send(JSONObject o) { sendVideo(o); } };
            PluginResult r = new PluginResult(PluginResult.Status.NO_RESULT); r.setKeepCallback(true); cb.sendPluginResult(r);
            return true;
        }
        if ("playLocal".equals(action)) {
            if (tvInline != null) cordova.getActivity().runOnUiThread(new Runnable() { public void run() { if (tvInline != null) tvInline.pause(); } });
            LocalVideoActivity.pending = args.optJSONObject(0);
            Intent i = new Intent(cordova.getActivity(), LocalVideoActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            cordova.getActivity().startActivity(i);
            cb.success();
            return true;
        }
        /* 📸 captura del canal en pantalla pequeña */
        if ("tvShot".equals(action)) {
            final String name = args.optString(0, "TV");
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    if (tvInline == null) { cb.error("notv"); return; }
                    tvInline.shot(name, new Shots.Done() {
                        public void done(boolean ok, boolean black, String where) {
                            JSONObject o = new JSONObject();
                            try { o.put("ok", ok); o.put("black", black); } catch (JSONException ignored) { }
                            cb.success(o);
                        }
                    });
                }
            });
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
            final boolean playing = args.optBoolean(0, false);
            try { setPlayState(playing, args.optLong(1, 0)); cb.success(); }
            catch (Exception e) { cb.error(String.valueOf(e.getMessage())); }
            /* Música local: el mismo servicio en primer plano que la radio, para que Android no la corte */
            if ("music".equals(args.optString(2, ""))) {
                cordova.getActivity().runOnUiThread(new Runnable() { public void run() { musicState(playing); } });
            }
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
            final boolean tv = "tv".equals(args.optString(2, ""));
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    try { radioAudioOnly = tv; radioPlay(url, name); cb.success(); } catch (Exception e) { cb.error(String.valueOf(e.getMessage())); }
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
                    /* Al quitar la radio se apaga el servicio (salvo que pase a sonar una canción);
                       en pausa se mantiene para poder seguir desde el volante */
                    if (stop) { mainHandler.removeCallbacks(stopIfIdle); mainHandler.postDelayed(stopIfIdle, 1200); }
                    cb.success();
                }
            });
            return true;
        }
        /* ---- Video / TV en pantalla completa ---- */
        if ("tvEvents".equals(action)) {
            tvCallback = cb;
            VideoActivity.events = new VideoActivity.Events() {
                public void send(JSONObject o) { sendTv(o); }
            };
            PluginResult r = new PluginResult(PluginResult.Status.NO_RESULT);
            r.setKeepCallback(true);
            cb.sendPluginResult(r);
            return true;
        }
        /* ---- 🎚️ Ecualizador (radio y TV; la música se ecualiza en la app) ---- */
        if ("audioFx".equals(action)) {
            AudioFx.set(args.optJSONObject(0));
            cb.success();
            return true;
        }
        /* ---- Chromecast ---- */
        if ("castEvents".equals(action)) {
            castCallback = cb;
            PluginResult r = new PluginResult(PluginResult.Status.NO_RESULT);
            r.setKeepCallback(true);
            cb.sendPluginResult(r);
            return true;
        }
        if (action.startsWith("cast")) {
            final JSONArray a = args;
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        CastHelper c = cast();
                        switch (action) {
                            case "castAvailable": {
                                JSONObject o = new JSONObject();
                                o.put("ok", c.available()); o.put("on", c.available() && c.isConnected()); o.put("device", c.available() ? c.deviceName() : "");
                                cb.success(o); break;
                            }
                            case "castScan": c.scan(a.optBoolean(0, true)); cb.success(c.routes()); break;
                            case "castConnect": if (c.connect(a.optString(0, ""))) cb.success(); else cb.error("notFound"); break;
                            case "castDisconnect": c.disconnect(); cb.success(); break;
                            case "castLoad": {
                                JSONObject o = a.optJSONObject(0);
                                String err = o == null ? "bad" : c.load(o);
                                if (err == null) {
                                    /* Música (o emisora/canal por el puente) en el TV: el teléfono se la pasa, así que la app se mantiene activa */
                                    if ("music".equals(o.optString("kind")) || o.optBoolean("relay", false)) {
                                        RadioService.sessionToken = session().getSessionToken();
                                        RadioService.startMusic(cordova.getContext(), o.optString("title", ""), o.optString("artist", ""), true);
                                    }
                                    cb.success();
                                } else cb.error(err);
                                break;
                            }
                            case "castCmd": c.command(a.optString(0, ""), a.optLong(1, 0)); cb.success(); break;
                            default: cb.error("unknown");
                        }
                    } catch (Throwable e) { cb.error(String.valueOf(e.getMessage())); }
                }
            });
            return true;
        }
        /* ---- TV en pequeño, dentro de la app ---- */
        if ("tvInline".equals(action) || "tvInlineRect".equals(action) || "tvInlineCmd".equals(action) || "tvClose".equals(action)) {
            final String url = args.optString(0, "");
            final JSONObject rect = "tvInline".equals(action) ? args.optJSONObject(1) : args.optJSONObject(0);
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        if ("tvInline".equals(action)) inline().open(url, rect);
                        else if ("tvInlineRect".equals(action)) { if (tvInline != null) tvInline.place(rect); }
                        else if ("tvInlineCmd".equals(action)) { if (tvInline != null) tvInline.command(url); }
                        else if (tvInline != null) tvInline.close();
                        cb.success();
                    } catch (Exception e) { cb.error(String.valueOf(e.getMessage())); }
                }
            });
            return true;
        }
        if ("playVideo".equals(action)) {
            if (tvInline != null) cordova.getActivity().runOnUiThread(new Runnable() { public void run() { if (tvInline != null) tvInline.pause(); } });
            /* La lista de canales va aparte (puede ser muy grande para un Intent) */
            VideoActivity.pending = args.optJSONObject(3);
            Intent i = new Intent(cordova.getActivity(), VideoActivity.class);
            i.putExtra("url", args.optString(0, ""));
            i.putExtra("title", args.optString(1, ""));
            i.putExtra("error", args.optString(2, ""));
            cordova.getActivity().startActivity(i);
            cb.success();
            return true;
        }
        /* ---- Descargar una lista M3U (sin las restricciones del navegador) ---- */
        if ("fetchPage".equals(action)) {
            final String url = args.optString(0, "");
            final boolean browser = args.optBoolean(1, true);
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    try { cb.success(fetchPage(url, browser)); } catch (Exception e) { cb.error(String.valueOf(e.getMessage())); }
                }
            });
            return true;
        }
        if ("rbServers".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    JSONArray out = new JSONArray();
                    try {
                        /* Como pide Radio Browser: buscar sus servidores por DNS (no hay una lista fija) */
                        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<String>();
                        for (java.net.InetAddress a : java.net.InetAddress.getAllByName("all.api.radio-browser.info")) {
                            String h = a.getCanonicalHostName();
                            if (h != null && h.endsWith(".api.radio-browser.info")) names.add(h);
                        }
                        for (String h : names) out.put(h);
                    } catch (Exception ignored) { }
                    cb.success(out);
                }
            });
            return true;
        }
        /* Copia de seguridad: guarda un archivo de texto en Descargas/Prometeo (no sale del teléfono) */
        if ("saveDownload".equals(action)) {
            final String name = args.optString(0, "prometeo.json").replaceAll("[\\\\/:*?\"<>|]", "_");
            final String text = args.optString(1, "");
            final boolean overwrite = args.optBoolean(2, false);
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    try { cb.success(saveDownload(name, text, overwrite)); }
                    catch (Exception e) { cb.error(String.valueOf(e.getMessage())); }
                }
            });
            return true;
        }
        /* Copias de seguridad que Prometeo guardó en Descargas/Prometeo (las más nuevas primero) */
        if ("listDownloads".equals(action) || "readDownload".equals(action) || "deleteDownload".equals(action)) {
            final String id = args.optString(0, "");
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    try {
                        if ("listDownloads".equals(action)) cb.success(listDownloads());
                        else if ("readDownload".equals(action)) cb.success(readDownload(id));
                        else { deleteDownload(id); cb.success(); }
                    } catch (Exception e) { cb.error(String.valueOf(e.getMessage())); }
                }
            });
            return true;
        }
        if ("fetchText".equals(action)) {
            final String url = args.optString(0, "");
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    try { cb.success(fetchText(url)); } catch (Exception e) { cb.error(String.valueOf(e.getMessage())); }
                }
            });
            return true;
        }
        if ("lockStatus".equals(action)) {
            cb.success(deviceSecure() ? 1 : 0);
            return true;
        }
        if ("openLockSettings".equals(action)) {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                cordova.getActivity().startActivity(i);
            } catch (Exception e) {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    cordova.getActivity().startActivity(i);
                } catch (Exception ignored) { }
            }
            cb.success();
            return true;
        }
        if ("authenticate".equals(action)) {
            final String title = args.optString(0, "Prometeo Player");
            final String sub = args.optString(1, "");
            cordova.getActivity().runOnUiThread(new Runnable() { public void run() { authenticate(title, sub, cb); } });
            return true;
        }
        /* ---- v42: memorias (interna, tarjeta SD, USB) y "Agregar música" ---- */
        if ("volumes".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() { try { cb.success(Library.volumes(cordova.getContext())); } catch (Exception e) { cb.error(String.valueOf(e.getMessage())); } }
            });
            return true;
        }
        if ("volEvents".equals(action)) {
            volCallback = cb;
            PluginResult r = new PluginResult(PluginResult.Status.NO_RESULT);
            r.setKeepCallback(true);
            cb.sendPluginResult(r);
            return true;
        }
        if ("rescan".equals(action)) {
            final JSONArray dirs = args.optJSONArray(0);
            cordova.getThreadPool().execute(new Runnable() {
                public void run() { Library.rescan(cordova.getContext(), dirs, 8000); cb.success(); }
            });
            return true;
        }
        if ("pickAudio".equals(action)) {
            if (pickCallback != null) pickCallback.error("cancel");
            pickCallback = cb;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("audio/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            pickVideoMode = false;
            try { cordova.startActivityForResult(this, i, REQ_PICK); }
            catch (Exception e) { pickCallback = null; cb.error("nopicker"); }
            return true;
        }
        if ("pickTree".equals(action)) {
            if (pickCallback != null) pickCallback.error("cancel");
            pickCallback = cb;
            Intent i = null;
            if (Build.VERSION.SDK_INT >= 29) {
                android.os.storage.StorageVolume v = Library.findVol(cordova.getContext(), args.optString(0, ""));
                if (v != null) { try { i = v.createOpenDocumentTreeIntent(); } catch (Exception ignored) { i = null; } }
            }
            if (i == null) i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            try { cordova.startActivityForResult(this, i, REQ_TREE); }
            catch (Exception e) { pickCallback = null; cb.error("nopicker"); }
            return true;
        }
        if ("listTree".equals(action)) {
            final String tree = args.optString(0, "");
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    try {
                        Uri u = Uri.parse(tree);
                        if (!Library.uriAllowed(cordova.getContext(), u)) { cb.error("notallowed"); return; }
                        cb.success(Library.listTree(cordova.getContext(), u));
                    } catch (Exception e) { cb.error(String.valueOf(e.getMessage())); }
                }
            });
            return true;
        }
        if ("releaseUri".equals(action)) {
            try { cordova.getContext().getContentResolver().releasePersistableUriPermission(Uri.parse(args.optString(0, "")), Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
            cb.success();
            return true;
        }
        if ("organize".equals(action)) {
            final JSONObject job = args.optJSONObject(0);
            if (Build.VERSION.SDK_INT < 30) { cb.error("android11"); return true; }
            if (organizing) { cb.error("busy"); return true; }
            organizing = true;
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    try { JSONObject r = organize(job, cb); PluginResult pr = new PluginResult(PluginResult.Status.OK, r); cb.sendPluginResult(pr); }
                    catch (Exception e) { cb.error(String.valueOf(e.getMessage())); }
                    finally { organizing = false; }
                }
            });
            return true;
        }
        /* ---- v42: copiar y compartir links (canales y emisoras) ---- */
        if ("copyText".equals(action)) {
            final String text = args.optString(0, "");
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) cordova.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Prometeo", text));
                        cb.success();
                    } catch (Exception e) { cb.error(String.valueOf(e.getMessage())); }
                }
            });
            return true;
        }
        if ("shareText".equals(action)) {
            try {
                Intent s = new Intent(Intent.ACTION_SEND);
                s.setType("text/plain");
                s.putExtra(Intent.EXTRA_TEXT, args.optString(0, ""));
                String subject = args.optString(1, "");
                if (!subject.isEmpty()) s.putExtra(Intent.EXTRA_SUBJECT, subject);
                Intent ch = Intent.createChooser(s, subject.isEmpty() ? null : subject);
                ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                cordova.getActivity().startActivity(ch);
                cb.success();
            } catch (Exception e) { cb.error(String.valueOf(e.getMessage())); }
            return true;
        }
        /* ---- v42: probar un link (lista M3U, canal o emisora) antes de guardarlo: solo se conecta y se cierra ---- */
        if ("probeUrl".equals(action)) {
            final String url = args.optString(0, "");
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    JSONObject o = new JSONObject();
                    Net.Resp r = null;
                    try {
                        if (!url.matches("(?i)^https?://.+")) { o.put("ok", false); o.put("why", "proto"); cb.success(o); return; }
                        r = Net.open(url, null);
                        int code = r.getResponseCode();
                        o.put("ok", code < 400 || code == 0);
                        o.put("code", code);
                        String ct = r.getContentType();
                        o.put("ctype", ct == null ? "" : ct);
                        cb.success(o);
                    } catch (Exception e) {
                        try { o.put("ok", false); o.put("why", Net.why(e)); } catch (JSONException ignored) { }
                        cb.success(o);
                    } finally { if (r != null) r.disconnect(); }
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
        if (requestCode == REQ_VPERM) {
            CallbackContext c = vpermCallback; vpermCallback = null;
            if (c != null) c.success(videoAccess());
            return;
        }
        if (requestCode != REQ_PERM || permCallback == null) return;
        boolean ok = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        CallbackContext cb = permCallback;
        permCallback = null;
        cb.success(ok ? 1 : 0);
    }

    /** Solo la carpeta (sin el nombre del archivo ni /storage/emulated/0/): nunca sale del teléfono */
    private static String folderOf(String p) {
        if (p == null) return "";
        String f = p.replace('\\', '/');
        if (Build.VERSION.SDK_INT < 29) {
            int cut = f.lastIndexOf('/');
            if (cut >= 0) f = f.substring(0, cut + 1);
            f = f.replaceFirst("^/storage/(emulated/\\d+|[^/]+)/", "").replaceFirst("^/sdcard/", "");
        }
        return f;
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
                MediaStore.Audio.Media.MIME_TYPE,
                /* carpeta: para dejar fuera notas de voz de WhatsApp, Telegram y grabaciones (privacidad) */
                Build.VERSION.SDK_INT >= 29 ? MediaStore.Audio.Media.RELATIVE_PATH : MediaStore.Audio.Media.DATA,
                /* v42: en qué memoria está (interna o tarjeta SD) y cuándo llegó */
                Build.VERSION.SDK_INT >= 29 ? MediaStore.MediaColumns.VOLUME_NAME : MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATE_ADDED
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
            int iPath = c.getColumnIndex(Build.VERSION.SDK_INT >= 29 ? MediaStore.Audio.Media.RELATIVE_PATH : MediaStore.Audio.Media.DATA);
            int iRec = Build.VERSION.SDK_INT >= 31 ? c.getColumnIndex("is_recording") : -1;
            int iVol = Build.VERSION.SDK_INT >= 29 ? c.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME) : -1;
            int iDate = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED);
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("id", c.getLong(iId));
                o.put("name", c.isNull(iName) ? "" : c.getString(iName));
                o.put("title", c.isNull(iTitle) ? "" : c.getString(iTitle));
                o.put("artist", c.isNull(iArtist) ? "" : c.getString(iArtist));
                o.put("duration", c.isNull(iDur) ? 0 : c.getLong(iDur));
                o.put("size", c.isNull(iSize) ? 0 : c.getLong(iSize));
                o.put("mime", c.isNull(iMime) ? "" : c.getString(iMime));
                o.put("folder", folderOf(iPath < 0 || c.isNull(iPath) ? "" : c.getString(iPath)));
                o.put("rec", iRec >= 0 && !c.isNull(iRec) && c.getInt(iRec) == 1);
                o.put("vol", iVol >= 0 && !c.isNull(iVol) ? c.getString(iVol) : Library.PRIMARY);
                o.put("date", iDate >= 0 && !c.isNull(iDate) ? c.getLong(iDate) * 1000 : 0);
                out.put(o);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /* ================= BLOQUEO DE AUDIOS PRIVADOS =================
       Lo verifica Android con la huella, el PIN o el patrón del propio teléfono. Prometeo no guarda ninguna clave.
       Solo métodos fuertes: el reconocimiento de cara de muchos teléfonos es "débil" y no se acepta. */
    private static final int REQ_AUTH = 4243;
    private CallbackContext authCallback;

    private boolean deviceSecure() {
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager) cordova.getContext().getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isDeviceSecure();
        } catch (Exception e) { return false; }
    }

    private void authenticate(String title, String sub, final CallbackContext cb) {
        if (!deviceSecure()) { cb.error("nolock"); return; }
        if (authCallback != null) { authCallback.error("cancel"); authCallback = null; }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.hardware.biometrics.BiometricPrompt prompt = new android.hardware.biometrics.BiometricPrompt.Builder(cordova.getActivity())
                        .setTitle(title)
                        .setSubtitle(sub)
                        .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                                | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                        .build();
                prompt.authenticate(new android.os.CancellationSignal(), cordova.getActivity().getMainExecutor(),
                        new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                            @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) { cb.success(1); }
                            @Override public void onAuthenticationError(int code, CharSequence msg) { cb.error("cancel"); }
                        });
            } else {
                /* Android 6 a 10: la pantalla de confirmación del bloqueo del teléfono (PIN, patrón o huella) */
                android.app.KeyguardManager km = (android.app.KeyguardManager) cordova.getContext().getSystemService(Context.KEYGUARD_SERVICE);
                @SuppressWarnings("deprecation")
                Intent i = km.createConfirmDeviceCredentialIntent(title, sub);
                if (i == null) { cb.error("nolock"); return; }
                authCallback = cb;
                cordova.startActivityForResult(this, i, REQ_AUTH);
            }
        } catch (Exception e) {
            cb.error("fail");
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        if (requestCode == REQ_SYS) {
            sysResult = resultCode;
            java.util.concurrent.CountDownLatch l = sysLatch;
            if (l != null) l.countDown();
            return;
        }
        if (requestCode == REQ_PICK || requestCode == REQ_TREE) {
            final CallbackContext c = pickCallback; pickCallback = null;
            if (c == null) return;
            if (resultCode != android.app.Activity.RESULT_OK || data == null) { c.error("cancel"); return; }
            if (requestCode == REQ_TREE) {
                Uri tree = data.getData();
                if (tree == null) { c.error("cancel"); return; }
                try { cordova.getContext().getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
                c.success(tree.toString());
                return;
            }
            final boolean keepAll = pickVideoMode; pickVideoMode = false;
            final java.util.ArrayList<Uri> uris = new java.util.ArrayList<Uri>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) { Uri u = data.getClipData().getItemAt(i).getUri(); if (u != null) uris.add(u); }
            } else if (data.getData() != null) uris.add(data.getData());
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    JSONArray out = new JSONArray();
                    ContentResolver cr = cordova.getContext().getContentResolver();
                    for (Uri u : uris) {
                        try {
                            JSONObject o = Library.describeDoc(cordova.getContext(), u);
                            /* Si es una canción que Android ya conoce, se usa esa (no hace falta guardar el permiso) */
                            if (keepAll || o.optLong("mid", -1) < 0) { try { cr.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { } }
                            out.put(o);
                        } catch (Exception ignored) { }
                    }
                    c.success(out);
                }
            });
            return;
        }
        if (requestCode != REQ_AUTH) return;
        CallbackContext c = authCallback; authCallback = null;
        if (c == null) return;
        if (resultCode == android.app.Activity.RESULT_OK) c.success(1); else c.error("cancel");
    }

    /* ================= v42: AGREGAR MÚSICA, MEMORIAS Y ORDENAR ================= */
    private static final int REQ_PICK = 4244, REQ_TREE = 4245, REQ_SYS = 4246;
    private CallbackContext pickCallback, volCallback;
    private volatile java.util.concurrent.CountDownLatch sysLatch;
    private volatile int sysResult = 0;
    private volatile boolean organizing = false;

    /** Muestra una ventana del propio Android (por ejemplo "¿Permitir que Prometeo modifique estos archivos?") y espera la respuesta */
    private boolean askSystem(final android.app.PendingIntent pi) {
        final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
        sysLatch = l; sysResult = 0;
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                try {
                    cordova.setActivityResultCallback(PrometeoMusic.this);
                    cordova.getActivity().startIntentSenderForResult(pi.getIntentSender(), REQ_SYS, null, 0, 0, 0);
                } catch (Exception e) { sysResult = 0; l.countDown(); }
            }
        });
        try { l.await(); } catch (InterruptedException e) { return false; }
        sysLatch = null;
        return sysResult == android.app.Activity.RESULT_OK;
    }

    private void orgEvent(CallbackContext cb, JSONObject o) {
        PluginResult r = new PluginResult(PluginResult.Status.OK, o);
        r.setKeepCallback(true);
        cb.sendPluginResult(r);
    }

    /**
     * 🗂️ Ordenar mi música (Android 11+): job = {items:[{mid, vol, rel}], dels:[mid]}
     * - misma memoria: se cambia de carpeta (el archivo no se copia, no ocupa más)
     * - otra memoria (p. ej. del teléfono a la tarjeta SD): se copia y luego se borra el original;
     *   si Android no deja borrar el original, se borra la copia (así nunca queda repetida)
     * - dels: canciones repetidas que se quitan (el usuario lo eligió en la ventana de Prometeo)
     * Android muestra su propia ventana para pedir permiso sobre los archivos de otras apps.
     */
    private JSONObject organize(JSONObject job, CallbackContext cb) throws Exception {
        ContentResolver cr = cordova.getContext().getContentResolver();
        JSONObject res = new JSONObject();
        JSONObject ids = new JSONObject();
        JSONArray items = job == null ? null : job.optJSONArray("items");
        JSONArray dels = job == null ? null : job.optJSONArray("dels");
        int total = (items == null ? 0 : items.length()) + (dels == null ? 0 : dels.length());
        int moved = 0, failed = 0, skipped = 0, deleted = 0, done = 0;
        java.util.ArrayList<Uri> all = new java.util.ArrayList<Uri>();
        java.util.ArrayList<JSONObject> work = new java.util.ArrayList<JSONObject>();
        String[] cols = { MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE };
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i); if (it == null) continue;
            long mid = it.optLong("mid", -1); if (mid < 0) continue;
            Uri u = ContentUris.withAppendedId(collection(), mid);
            Cursor c = null;
            try {
                c = cr.query(u, cols, null, null, null);
                if (c == null || !c.moveToFirst()) { failed++; continue; }
                JSONObject w = new JSONObject();
                w.put("mid", mid); w.put("uri", u.toString());
                w.put("fromVol", c.getString(0)); w.put("fromRel", c.isNull(1) ? "" : c.getString(1));
                w.put("name", c.isNull(2) ? "" : c.getString(2)); w.put("mime", c.isNull(3) ? "audio/mpeg" : c.getString(3));
                String rel = it.optString("rel", "Music/Prometeo/"); if (!rel.endsWith("/")) rel += "/";
                w.put("vol", it.optString("vol", w.getString("fromVol"))); w.put("rel", rel);
                if (w.getString("vol").equals(w.getString("fromVol")) && rel.equalsIgnoreCase(w.getString("fromRel"))) { skipped++; continue; }
                work.add(w); all.add(u);
            } catch (Exception e) { failed++; }
            finally { if (c != null) c.close(); }
        }
        java.util.ArrayList<Uri> delUris = new java.util.ArrayList<Uri>();
        for (int i = 0; dels != null && i < dels.length(); i++) {
            long mid = dels.optLong(i, -1); if (mid < 0) continue;
            Uri u = ContentUris.withAppendedId(collection(), mid); delUris.add(u); all.add(u);
        }
        if (!all.isEmpty()) {
            /* Una sola ventana de Android para todos los archivos */
            if (!askSystem(MediaStore.createWriteRequest(cr, all))) { res.put("denied", true); res.put("skipped", skipped); return res; }
        }
        java.util.ArrayList<Uri[]> pending = new java.util.ArrayList<Uri[]>();   /* [original, copia] que falta borrar */
        java.util.ArrayList<Long> pendingMid = new java.util.ArrayList<Long>();
        byte[] buf = new byte[65536];
        for (JSONObject w : work) {
            done++;
            JSONObject ev = new JSONObject(); ev.put("type", "progress"); ev.put("done", done); ev.put("total", total); ev.put("name", w.optString("name")); orgEvent(cb, ev);
            Uri src = Uri.parse(w.getString("uri"));
            try {
                if (w.getString("vol").equals(w.getString("fromVol"))) {
                    android.content.ContentValues v = new android.content.ContentValues();
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, w.getString("rel"));
                    if (cr.update(src, v, null, null) > 0) moved++; else failed++;
                } else {
                    android.content.ContentValues v = new android.content.ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, w.getString("name"));
                    v.put(MediaStore.MediaColumns.MIME_TYPE, w.getString("mime"));
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, w.getString("rel"));
                    v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    Uri dst = cr.insert(MediaStore.Audio.Media.getContentUri(w.getString("vol")), v);
                    if (dst == null) { failed++; continue; }
                    boolean ok = false;
                    InputStream in = null; java.io.OutputStream os = null;
                    try {
                        in = cr.openInputStream(src); os = cr.openOutputStream(dst);
                        if (in != null && os != null) { int n; while ((n = in.read(buf)) > 0) os.write(buf, 0, n); ok = true; }
                    } catch (Exception e) { ok = false; }
                    finally { try { if (in != null) in.close(); } catch (Exception ignored) { } try { if (os != null) os.close(); } catch (Exception ignored) { ok = false; } }
                    if (!ok) { try { cr.delete(dst, null, null); } catch (Exception ignored) { } failed++; continue; }
                    android.content.ContentValues v2 = new android.content.ContentValues();
                    v2.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    cr.update(dst, v2, null, null);
                    boolean gone = false;
                    try { gone = cr.delete(src, null, null) > 0; } catch (Exception e) { gone = false; }
                    if (gone) { moved++; ids.put(String.valueOf(w.getLong("mid")), ContentUris.parseId(dst)); }
                    else { pending.add(new Uri[]{ src, dst }); pendingMid.add(w.getLong("mid")); }
                }
            } catch (Exception e) { failed++; }
        }
        java.util.ArrayList<Uri> delLeft = new java.util.ArrayList<Uri>();
        for (Uri u : delUris) {
            done++;
            boolean gone = false;
            try { gone = cr.delete(u, null, null) > 0; } catch (Exception e) { gone = false; }
            if (gone) deleted++; else delLeft.add(u);
        }
        if (!pending.isEmpty() || !delLeft.isEmpty()) {
            java.util.ArrayList<Uri> ask = new java.util.ArrayList<Uri>(delLeft);
            for (Uri[] p : pending) ask.add(p[0]);
            boolean ok = askSystem(MediaStore.createDeleteRequest(cr, ask));
            for (int i = 0; i < pending.size(); i++) {
                Uri[] p = pending.get(i);
                if (ok) { moved++; ids.put(String.valueOf(pendingMid.get(i)), ContentUris.parseId(p[1])); }
                else { try { cr.delete(p[1], null, null); } catch (Exception ignored) { } failed++; }   /* sin repetidas: se quita la copia */
            }
            if (ok) deleted += delLeft.size();
        }
        res.put("type", "done");
        res.put("moved", moved); res.put("failed", failed); res.put("skipped", skipped); res.put("deleted", deleted); res.put("ids", ids);
        return res;
    }

    /* Aviso cuando se conecta o se saca una tarjeta SD o memoria USB */
    private Object volWatch;
    private BroadcastReceiver volReceiver;
    private void volChanged() {
        CallbackContext c = volCallback; if (c == null) return;
        try {
            JSONObject o = new JSONObject(); o.put("type", "volumes");
            PluginResult r = new PluginResult(PluginResult.Status.OK, o); r.setKeepCallback(true); c.sendPluginResult(r);
        } catch (JSONException ignored) { }
    }
    private void registerVolumeWatch() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.os.storage.StorageManager sm = (android.os.storage.StorageManager) cordova.getContext().getSystemService(Context.STORAGE_SERVICE);
                android.os.storage.StorageManager.StorageVolumeCallback cbk = new android.os.storage.StorageManager.StorageVolumeCallback() {
                    @Override public void onStateChanged(android.os.storage.StorageVolume v) { volChanged(); }
                };
                sm.registerStorageVolumeCallback(cordova.getContext().getMainExecutor(), cbk);
                volWatch = cbk;
            } else {
                volReceiver = new BroadcastReceiver() { @Override public void onReceive(Context ctx, Intent intent) { volChanged(); } };
                IntentFilter f = new IntentFilter();
                f.addAction(Intent.ACTION_MEDIA_MOUNTED); f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
                f.addAction(Intent.ACTION_MEDIA_REMOVED); f.addAction(Intent.ACTION_MEDIA_EJECT); f.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
                f.addDataScheme("file");
                cordova.getContext().registerReceiver(volReceiver, f);
            }
        } catch (Exception ignored) { }
    }
    private void unregisterVolumeWatch() {
        try {
            if (volWatch != null && Build.VERSION.SDK_INT >= 30) {
                android.os.storage.StorageManager sm = (android.os.storage.StorageManager) cordova.getContext().getSystemService(Context.STORAGE_SERVICE);
                sm.unregisterStorageVolumeCallback((android.os.storage.StorageManager.StorageVolumeCallback) volWatch);
            }
            if (volReceiver != null) cordova.getContext().unregisterReceiver(volReceiver);
        } catch (Exception ignored) { }
        volWatch = null; volReceiver = null; volCallback = null;
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

    /* Lo último que sonó de la música (para la notificación) */
    private volatile String musicTitle = "", musicArtist = "";

    /** Se apaga el servicio solo si ya no suena nada: ni radio ni música */
    private final Runnable stopIfIdle = new Runnable() {
        @Override public void run() {
            if (!radioWanted && !RadioService.music) RadioService.stop(cordova.getContext());
        }
    };

    /** Play/pausa de una canción: arranca o actualiza la notificación de la música.
        En pausa se mantiene (para seguir desde el volante o la notificación). */
    private void musicState(boolean playing) {
        if (radioWanted) return;                      /* la radio manda */
        mainHandler.removeCallbacks(stopIfIdle);
        RadioService.sessionToken = session().getSessionToken();
        RadioService.art = musicArt;
        if (RadioService.running && RadioService.music) RadioService.updateMusic(cordova.getContext(), musicTitle, musicArtist, playing);
        else if (playing) RadioService.startMusic(cordova.getContext(), musicTitle, musicArtist, true);
    }

    private void setNowPlaying(JSONObject o) {
        if (o == null) return;
        final boolean isMusic = !"radio".equals(o.optString("kind", ""));
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
                    if (isMusic) musicArt = bmp;
                }
            } catch (Exception ignored) { }
        } else if (isMusic) musicArt = null;
        MediaSession s = session();
        s.setMetadata(b.build());
        if (!s.isActive()) s.setActive(true);
        if (isMusic) {
            musicTitle = title; musicArtist = artist;
            final Bitmap a = musicArt;
            cordova.getActivity().runOnUiThread(new Runnable() { public void run() {
                if (!RadioService.music) return;
                RadioService.art = a;
                RadioService.updateMusic(cordova.getContext(), musicTitle, musicArtist, null);
            } });
        }
    }
    private volatile Bitmap musicArt;

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
    private CallbackContext radioCallback, tvCallback;
    private TvInline tvInline;
    private CastHelper castHelper;
    private CallbackContext castCallback;

    private CastHelper cast() {
        if (castHelper == null) castHelper = new CastHelper(cordova.getActivity(), new CastHelper.Events() {
            public void send(JSONObject o) {
                CallbackContext c = castCallback; if (c == null) return;
                PluginResult r = new PluginResult(PluginResult.Status.OK, o);
                r.setKeepCallback(true);
                c.sendPluginResult(r);
            }
        });
        return castHelper;
    }

    private void sendTv(JSONObject o) {
        CallbackContext c = tvCallback; if (c == null) return;
        PluginResult r = new PluginResult(PluginResult.Status.OK, o);
        r.setKeepCallback(true);
        c.sendPluginResult(r);
    }

    private TvInline inline() {
        if (tvInline == null) tvInline = new TvInline(cordova.getActivity(), webView.getView(), new TvInline.Events() { public void send(JSONObject o) { sendTv(o); } });
        return tvInline;
    }

    /* La app pasa a segundo plano: el TV pequeño se pausa (la radio y la música siguen) */
    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);
        if (tvInline != null) tvInline.pause();
    }
    /** TV en "solo audio": sin video y con la calidad más baja (gasta menos datos y batería) */
    private boolean radioAudioOnly = false;

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
            radio.setMediaItem(MediaItem.fromUri(freshUrl(radioUrl)));
            radio.prepare();
            radio.play();
        }
    };

    /* skey = marca de hora que ponen los reproductores de algunas emisoras (no es una clave):
       al reconectar se pone al día, igual que hace la página al darle play. El resto del link no se toca. */
    private static final java.util.regex.Pattern SKEY = java.util.regex.Pattern.compile("(?i)(skey(?:%3A|%3D|:|=))(\\d{10,13})");
    static String freshUrl(String u) {
        if (u == null) return null;
        java.util.regex.Matcher m = SKEY.matcher(u);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            long now = System.currentTimeMillis();
            String v = m.group(2).length() >= 13 ? String.valueOf(now) : String.valueOf(now / 1000);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(1) + v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

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
    /** Escribe el archivo y devuelve dónde quedó (para mostrárselo al usuario) */
    private static final String DL_REL = android.os.Environment.DIRECTORY_DOWNLOADS + "/Prometeo/";

    private java.io.File dlDir() {
        return new java.io.File(cordova.getContext().getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "Prometeo");
    }

    /** Busca un archivo NUESTRO en Descargas/Prometeo (Android solo nos deja ver los que creó esta instalación).
        LIKE: sin importar mayúsculas, por si la carpeta ya existía como "prometeo" */
    private android.net.Uri findOwn(String name) {
        android.database.Cursor c = null;
        try {
            c = cordova.getContext().getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{ MediaStore.MediaColumns._ID },
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? COLLATE NOCASE AND " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    new String[]{ name, DL_REL }, null);
            if (c != null && c.moveToFirst()) return android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        return null;
    }

    /** overwrite=true: si ya existe (y es nuestro) se reescribe; si no (por ejemplo, después de reinstalar) se crea uno nuevo */
    private String saveDownload(String name, String text, boolean overwrite) throws Exception {
        byte[] data = text.getBytes("UTF-8");
        android.content.Context ctx = cordova.getContext();
        if (Build.VERSION.SDK_INT >= 29) {
            android.net.Uri uri = overwrite ? findOwn(name) : null;
            if (uri != null) {
                java.io.OutputStream os = ctx.getContentResolver().openOutputStream(uri, "wt");
                if (os == null) throw new Exception("no se pudo escribir");
                try { os.write(data); } finally { os.close(); }
                try {
                    android.content.ContentValues v = new android.content.ContentValues();
                    v.put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000);
                    ctx.getContentResolver().update(uri, v, null, null);
                } catch (Exception ignored) { }
                return "Download/Prometeo/" + name;
            }
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Prometeo");
            uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("no se pudo crear el archivo");
            java.io.OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            if (os == null) throw new Exception("no se pudo escribir");
            try { os.write(data); } finally { os.close(); }
            String real = name;
            android.database.Cursor c = null;
            try {
                c = ctx.getContentResolver().query(uri, new String[]{ MediaStore.MediaColumns.DISPLAY_NAME }, null, null, null);
                if (c != null && c.moveToFirst() && c.getString(0) != null) real = c.getString(0);
            } catch (Exception ignored) {
            } finally { if (c != null) c.close(); }
            return "Download/Prometeo/" + real;
        }
        /* Android 9 o menos: carpeta de la app (no necesita permisos) */
        java.io.File dir = dlDir();
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("no se pudo crear la carpeta");
        java.io.File f = new java.io.File(dir, name);
        if (!overwrite && f.exists()) {
            String base = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
            for (int i = 1; f.exists() && i < 100; i++) f = new java.io.File(dir, base + " (" + i + ").json");
        }
        java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
        try { fo.write(data); } finally { fo.close(); }
        return f.getAbsolutePath();
    }

    private JSONArray listDownloads() throws Exception {
        JSONArray a = new JSONArray();
        if (Build.VERSION.SDK_INT >= 29) {
            android.database.Cursor c = null;
            try {
                c = cordova.getContext().getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{ MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE },
                        MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", new String[]{ DL_REL },
                        MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
                while (c != null && c.moveToNext()) {
                    String n = c.getString(1);
                    if (n == null || !n.toLowerCase(java.util.Locale.ROOT).endsWith(".json")) continue;
                    JSONObject o = new JSONObject();
                    o.put("id", android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)).toString());
                    o.put("name", n);
                    o.put("date", c.getLong(2) * 1000);
                    o.put("size", c.getLong(3));
                    a.put(o);
                }
            } finally { if (c != null) c.close(); }
            return a;
        }
        java.io.File[] fs = dlDir().listFiles();
        if (fs == null) return a;
        java.util.Arrays.sort(fs, new java.util.Comparator<java.io.File>() {
            public int compare(java.io.File x, java.io.File y) { return Long.compare(y.lastModified(), x.lastModified()); }
        });
        for (java.io.File f : fs) {
            if (!f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".json")) continue;
            JSONObject o = new JSONObject();
            o.put("id", f.getAbsolutePath()); o.put("name", f.getName()); o.put("date", f.lastModified()); o.put("size", f.length());
            a.put(o);
        }
        return a;
    }

    /** Solo archivos de Descargas/Prometeo (de esta app) */
    private boolean ownId(String id) {
        if (id.startsWith("content://")) return id.startsWith(MediaStore.Downloads.EXTERNAL_CONTENT_URI.toString() + "/");
        try { return new java.io.File(id).getCanonicalPath().startsWith(dlDir().getCanonicalPath() + java.io.File.separator); }
        catch (Exception e) { return false; }
    }

    private String readDownload(String id) throws Exception {
        if (!ownId(id)) throw new Exception("not allowed");
        InputStream in = id.startsWith("content://") ? cordova.getContext().getContentResolver().openInputStream(android.net.Uri.parse(id)) : new java.io.FileInputStream(id);
        if (in == null) throw new Exception("no se pudo leer");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); if (out.size() > 20 * 1024 * 1024) throw new Exception("too big"); }
        } finally { in.close(); }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private void deleteDownload(String id) throws Exception {
        if (!ownId(id)) throw new Exception("not allowed");
        if (id.startsWith("content://")) cordova.getContext().getContentResolver().delete(android.net.Uri.parse(id), null, null);
        else new java.io.File(id).delete();
    }

    static boolean isNetworkProblem(PlaybackException e) {
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

    private int radioFx = 0;

    private void radioPlay(String url, String name) {
        if (radio == null) {
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent("PrometeoPlayer/43")
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(20000);
            radio = new ExoPlayer.Builder(cordova.getContext())
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(cordova.getContext(), http)))
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                    .build();
            radio.setHandleAudioBecomingNoisy(true);   /* si se desconecta el Bluetooth, se pausa */
            radioFx = AudioFx.newSession(cordova.getContext());
            if (radioFx > 0) { radio.setAudioSessionId(radioFx); AudioFx.attach(radioFx); }   /* 🎚️ ecualizador */
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
        radio.setTrackSelectionParameters(radio.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, radioAudioOnly)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, radioAudioOnly)
                .setForceLowestBitrate(radioAudioOnly).build());
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
        mainHandler.removeCallbacks(stopIfIdle);
        RadioService.sessionToken = session().getSessionToken();
        RadioService.music = false;   /* la notificación pasa a ser de la radio */
        if (RadioService.running) RadioService.update(cordova.getContext(), name);
        else RadioService.start(cordova.getContext(), name);
    }

    /* ================= DESCARGAR LISTAS M3U ================= */
    /** Para "Agregar emisora": lee una página o un JSON (hasta 3 MB). Si el link ya es audio, NO lo descarga:
        solo avisa que es una transmisión. Solo se usa cuando el usuario lo pide. */
    private static JSONObject fetchPage(String address, boolean browser) throws Exception {
        URL url = new URL(address);
        for (int hop = 0; hop < 8; hop++) {
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", browser
                    ? "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + ") AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36 PrometeoPlayer/43"
                    : "PrometeoPlayer/43");
            c.setRequestProperty("Accept", browser ? "text/html,application/xhtml+xml,*/*;q=0.8" : "application/json,*/*");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(url, c.getHeaderField("Location"));
                c.disconnect();
                continue;
            }
            if (code >= 400) { c.disconnect(); throw new Exception("HTTP " + code); }
            String ct = c.getContentType() == null ? "" : c.getContentType().toLowerCase();
            JSONObject o = new JSONObject();
            o.put("url", url.toString());
            o.put("ctype", ct);
            boolean audio = ct.startsWith("audio/") || ct.contains("application/ogg") || ct.contains("octet-stream")
                    || c.getHeaderField("icy-name") != null || c.getHeaderField("icy-br") != null || c.getHeaderField("icy-metaint") != null;
            boolean playlist = ct.contains("mpegurl") || ct.contains("scpls") || ct.contains("x-scpls");
            if (audio && !playlist) { c.disconnect(); o.put("type", "audio"); return o; }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                out.write(buf, 0, n);
                if (total > 3 * 1024 * 1024) break;
            }
            in.close();
            c.disconnect();
            o.put("type", "text");
            o.put("text", new String(out.toByteArray(), StandardCharsets.UTF_8));
            return o;
        }
        throw new Exception("too many redirects");
    }

    private static String fetchText(String address) throws Exception {
        URL url = new URL(address);
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "PrometeoPlayer/43");
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
        radioWanted = false; cancelRetry(); unregisterNetworkWatch(); unregisterVolumeWatch();
        mainHandler.removeCallbacks(stopIfIdle);
        RadioService.controls = null; RadioService.music = false; RadioService.art = null;
        if (radio != null) { radio.release(); radio = null; }
        if (radioFx > 0) { AudioFx.detach(radioFx); radioFx = 0; }
        RadioService.stop(cordova.getContext());
        if (btRegistered) {
            try { ((AudioManager) cordova.getContext().getSystemService(Context.AUDIO_SERVICE)).unregisterAudioDeviceCallback(btCallback); } catch (Exception ignored) { }
            btRegistered = false;
        }
        if (noisyRegistered) { try { cordova.getContext().unregisterReceiver(noisyReceiver); } catch (Exception ignored) { } noisyRegistered = false; }
        controlsCallback = null;
        radioCallback = null;
        tvCallback = null; VideoActivity.events = null; LocalVideoActivity.events = null; videoCallback = null;
        if (tvInline != null) { tvInline.release(); tvInline = null; }
        if (castHelper != null) { castHelper.release(); castHelper = null; }
        castCallback = null;
    }

    /** Sirve el archivo original directamente a la página (no se copia nada). */
    @Override
    public CordovaPluginPathHandler getPathHandler() {
        if (pathHandler == null) {
            pathHandler = new CordovaPluginPathHandler(path -> {
                if (path != null && path.startsWith(PREFIX_VTHUMB)) {
                    /* v43: miniatura de un video */
                    try {
                        String rest = path.substring(PREFIX_VTHUMB.length());
                        if (rest.startsWith("u/")) {
                            /* v43b: video de memoria USB o elegido a mano */
                            String b = rest.substring(2); int sl = b.indexOf('/'); if (sl >= 0) b = b.substring(0, sl);
                            Uri u = Uri.parse(new String(Base64.decode(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8));
                            if (!"content".equals(u.getScheme()) || !Library.uriAllowed(cordova.getContext(), u)) return null;
                            byte[] jpg = Videos.thumbDoc(cordova.getContext(), u);
                            return jpg == null ? null : new WebResourceResponse("image/jpeg", null, new java.io.ByteArrayInputStream(jpg));
                        }
                        if (videoAccess() == 0) return null;
                        String idp = path.substring(PREFIX_VTHUMB.length()).replaceAll("[^0-9].*$", "");
                        byte[] jpg = Videos.thumb(cordova.getContext(), Long.parseLong(idp));
                        if (jpg == null) return null;
                        return new WebResourceResponse("image/jpeg", null, new java.io.ByteArrayInputStream(jpg));
                    } catch (Exception e) { return null; }
                }
                if (path != null && path.startsWith(PREFIX_URI)) {
                    /* v42: canción elegida con el selector de Android (o de una memoria USB permitida) */
                    try {
                        String b = path.substring(PREFIX_URI.length());
                        int dot = b.indexOf('/'); if (dot >= 0) b = b.substring(0, dot);
                        Uri u = Uri.parse(new String(Base64.decode(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8));
                        if (!"content".equals(u.getScheme()) || !Library.uriAllowed(cordova.getContext(), u)) return null;
                        ContentResolver cr = cordova.getContext().getContentResolver();
                        InputStream in = cr.openInputStream(u);
                        if (in == null) return null;
                        String mime = cr.getType(u);
                        return new WebResourceResponse(mime != null && mime.startsWith("audio/") ? mime : "audio/mpeg", null, in);
                    } catch (Exception e) { return null; }
                }
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
