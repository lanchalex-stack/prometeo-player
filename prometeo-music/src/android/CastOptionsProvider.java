package com.prometeo.music;

import android.content.Context;

import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;
import com.google.android.gms.cast.framework.media.CastMediaOptions;

import java.util.List;

/**
 * Configuración de Chromecast (Google Cast). Usa el reproductor estándar de Google en el televisor
 * ("Default Media Receiver"): no hace falta registrar nada ni hay servidores de Prometeo de por medio.
 * La notificación y los controles de la pantalla de bloqueo los pone Prometeo (no el SDK), para no duplicarlos.
 */
public class CastOptionsProvider implements OptionsProvider {
    @Override
    public CastOptions getCastOptions(Context context) {
        CastMediaOptions media = new CastMediaOptions.Builder()
                .setNotificationOptions(null)
                .setMediaSessionEnabled(false)
                .build();
        return new CastOptions.Builder()
                .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
                .setStopReceiverApplicationWhenEndingSession(true)
                .setCastMediaOptions(media)
                .build();
    }

    @Override
    public List<SessionProvider> getAdditionalSessionProviders(Context context) {
        return null;
    }
}
