package com.prometeo.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.os.Build;
import android.os.IBinder;

/**
 * Mantiene viva la reproducción (radio o música) cuando Prometeo está en segundo plano.
 * Android corta el internet a las apps que no están en primer plano, y algunos teléfonos
 * (p. ej. "Optimizar" de Samsung) cierran las apps que no avisan que están sonando.
 * Un "servicio en primer plano" de tipo reproducción multimedia es la forma oficial
 * de decirle al sistema "estoy reproduciendo audio, no me cortes".
 * Solo se activa mientras hay una emisora o una canción puesta. No envía datos a ningún lado.
 */
public class RadioService extends Service {
    private static final String CHANNEL = "prometeo_radio";
    private static final int NOTIF_ID = 4142;
    static final String ACT_PREV = "com.prometeo.music.PREV";
    static final String ACT_TOGGLE = "com.prometeo.music.TOGGLE";
    static final String ACT_NEXT = "com.prometeo.music.NEXT";

    /** Botones de la notificación (Android 12 o menos) → los mismos controles que el volante */
    interface Controls { void on(String what); }
    static volatile Controls controls;

    static volatile MediaSession.Token sessionToken;
    static volatile String title = "Prometeo Radio";
    static volatile boolean running = false;
    static volatile boolean reconnecting = false;

    /* ---- Música local ---- */
    static volatile boolean music = false;          /* true = la notificación es de la música, no de la radio */
    static volatile String artist = "";
    static volatile boolean playing = true;
    static volatile Bitmap art;

    /** Muestra "Reconectando…" en la notificación mientras se recupera la señal. */
    static void setReconnecting(Context ctx, boolean on) {
        if (reconnecting == on) return;
        reconnecting = on;
        update(ctx, null);
    }

    private static String reconnectingText() {
        String l = java.util.Locale.getDefault().getLanguage();
        if ("en".equals(l)) return "Reconnecting…";
        if ("fr".equals(l)) return "Reconnexion…";
        return "Reconectando…";   /* es y pt */
    }

    /** Se llama al darle play a una emisora (con la app abierta). */
    static void start(Context ctx, String t) {
        music = false;
        if (t != null && t.length() > 0) title = t;
        launch(ctx);
    }

    /** Se llama al darle play a una canción: protege la música igual que la radio. */
    static void startMusic(Context ctx, String t, String a, boolean isPlaying) {
        music = true;
        reconnecting = false;
        title = (t != null && t.length() > 0) ? t : "Prometeo Player";
        artist = a == null ? "" : a;
        playing = isPlaying;
        if (running) update(ctx, null); else launch(ctx);
    }

    /** Cambió la canción o pasó de play a pausa: solo actualiza la notificación. */
    static void updateMusic(Context ctx, String t, String a, Boolean isPlaying) {
        if (!music) return;
        if (t != null && t.length() > 0) title = t;
        if (a != null) artist = a;
        if (isPlaying != null) playing = isPlaying;
        update(ctx, null);
    }

    private static void launch(Context ctx) {
        Intent i = new Intent(ctx, RadioService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
        } catch (Exception e) {
            /* Si Android no deja iniciarlo (p. ej. desde segundo plano), sigue sonando igual que antes */
            if (running) update(ctx, null);
        }
    }

    /** Cambia el texto de la notificación (otra emisora) sin reiniciar el servicio. */
    static void update(Context ctx, String t) {
        if (t != null && t.length() > 0) title = t;
        if (!running) return;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, build(ctx));
        } catch (Exception ignored) { }
    }

    static void stop(Context ctx) {
        try { ctx.stopService(new Intent(ctx, RadioService.class)); } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = build(this);
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIF_ID, n);
            running = true;
        } catch (Exception e) {
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        /* Botón tocado en la notificación */
        String act = intent == null ? null : intent.getAction();
        if (act != null) {
            Controls c = controls;
            if (c == null) { stopSelf(); return START_NOT_STICKY; }   /* Prometeo ya no está abierto */
            if (ACT_PREV.equals(act)) c.on("prev");
            else if (ACT_NEXT.equals(act)) c.on("next");
            else if (ACT_TOGGLE.equals(act)) c.on(playing ? "pause" : "play");
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        reconnecting = false;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForegroundLegacy();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundLegacy() { stopForeground(true); }

    /* Si el usuario cierra Prometeo desde "recientes", se apaga la reproducción también */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private static PendingIntent actionIntent(Context ctx, String action, int req) {
        Intent i = new Intent(ctx, RadioService.class).setAction(action);
        int fl = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(ctx, req, i, fl) : PendingIntent.getService(ctx, req, i, fl);
    }

    @SuppressWarnings("deprecation")
    private static Notification build(Context ctx) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Radio", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Radio sonando en segundo plano");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(ctx, CHANNEL);
        } else {
            b = new Notification.Builder(ctx);
        }
        /* Ícono pequeño monocromo del sistema: el ícono de la app (a color, adaptativo) se ve como una mancha blanca en la barra */
        b.setSmallIcon(android.R.drawable.ic_media_play)
         .setContentTitle(title)
         .setOngoing(true)
         .setShowWhen(false)
         .setVisibility(Notification.VISIBILITY_PUBLIC)
         .setCategory(Notification.CATEGORY_TRANSPORT);
        if (music) b.setContentText(artist.length() > 0 ? artist : "Prometeo Player");
        else b.setContentText(reconnecting ? reconnectingText() : "Prometeo Radio");
        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (open != null) {
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            b.setContentIntent(PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        /* Con la sesión multimedia, Android muestra los controles (pausa, siguiente) en la notificación y la pantalla de bloqueo */
        Notification.MediaStyle style = new Notification.MediaStyle();
        if (sessionToken != null) style.setMediaSession(sessionToken);
        if (music) {
            Bitmap a = art;
            if (a != null) b.setLargeIcon(a);
            /* Android 13+ arma los botones solo desde la sesión; en Android 12 o menos van aquí */
            if (Build.VERSION.SDK_INT < 33) {
                b.addAction(new Notification.Action.Builder(Icon.createWithResource(ctx, android.R.drawable.ic_media_previous), "Anterior", actionIntent(ctx, ACT_PREV, 1)).build());
                b.addAction(new Notification.Action.Builder(Icon.createWithResource(ctx, playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play),
                        playing ? "Pausa" : "Play", actionIntent(ctx, ACT_TOGGLE, 2)).build());
                b.addAction(new Notification.Action.Builder(Icon.createWithResource(ctx, android.R.drawable.ic_media_next), "Siguiente", actionIntent(ctx, ACT_NEXT, 3)).build());
                style.setShowActionsInCompactView(0, 1, 2);
            }
        }
        b.setStyle(style);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
}
