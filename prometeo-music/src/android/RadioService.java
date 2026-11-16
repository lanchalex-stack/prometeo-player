package com.prometeo.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.session.MediaSession;
import android.os.Build;
import android.os.IBinder;

/**
 * Mantiene viva la radio cuando Prometeo está en segundo plano.
 * Android (15/16) corta el internet a las apps que no están en primer plano;
 * un "servicio en primer plano" de tipo reproducción multimedia es la forma oficial
 * de decirle al sistema "estoy reproduciendo audio, no me cortes".
 * Solo se activa mientras hay una emisora puesta. No envía datos a ningún lado.
 */
public class RadioService extends Service {
    private static final String CHANNEL = "prometeo_radio";
    private static final int NOTIF_ID = 4142;
    static volatile MediaSession.Token sessionToken;
    static volatile String title = "Prometeo Radio";
    static volatile boolean running = false;
    static volatile boolean reconnecting = false;

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
        if (t != null && t.length() > 0) title = t;
        Intent i = new Intent(ctx, RadioService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
        } catch (Exception e) {
            /* Si Android no deja iniciarlo (p. ej. desde segundo plano), la radio sigue igual que antes */
            if (running) update(ctx, t);
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

    /* Si el usuario cierra Prometeo desde "recientes", se apaga la radio también */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

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
         .setContentText(reconnecting ? reconnectingText() : "Prometeo Radio")
         .setOngoing(true)
         .setShowWhen(false)
         .setVisibility(Notification.VISIBILITY_PUBLIC)
         .setCategory(Notification.CATEGORY_TRANSPORT);
        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (open != null) {
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            b.setContentIntent(PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        /* Con la sesión multimedia, Android muestra los controles (pausa, emisora siguiente) en la notificación y la pantalla de bloqueo */
        Notification.MediaStyle style = new Notification.MediaStyle();
        if (sessionToken != null) style.setMediaSession(sessionToken);
        b.setStyle(style);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
}
