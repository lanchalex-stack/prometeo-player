package com.prometeo.music;

import android.content.Context;
import android.media.AudioManager;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Ecualizador de Prometeo para la radio y la TV (los reproductores nativos).
 * La música del teléfono se ecualiza dentro de la app (Web Audio) con los mismos ajustes.
 * Usa los efectos de audio de Android solo sobre el sonido de Prometeo (no toca otras apps).
 */
final class AudioFx {
    private AudioFx() { }

    /* Ajustes: 5 bandas en dB (60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz), refuerzo de bajos y envolvente 0..100 */
    private static final double[] FREQ = { 60, 230, 910, 3600, 14000 };
    private static boolean on = false;
    private static final double[] db = new double[5];
    private static int bass = 0, virt = 0;
    private static final double K = 1.8;

    private static final class Fx { Equalizer eq; BassBoost bb; Virtualizer vz; }
    private static final Map<Integer, Fx> live = new HashMap<>();

    /** Número de sesión de audio nuevo para un reproductor (para poder ecualizarlo) */
    static int newSession(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            return am.generateAudioSessionId();
        } catch (Exception e) { return 0; }
    }

    static synchronized void attach(int session) {
        if (session <= 0 || live.containsKey(session)) return;
        Fx f = new Fx();
        try { f.eq = new Equalizer(0, session); } catch (Throwable e) { f.eq = null; }
        try { f.bb = new BassBoost(0, session); } catch (Throwable e) { f.bb = null; }
        try { f.vz = new Virtualizer(0, session); } catch (Throwable e) { f.vz = null; }
        live.put(session, f);
        apply(f);
    }

    static synchronized void detach(int session) {
        Fx f = live.remove(session);
        if (f == null) return;
        try { if (f.eq != null) f.eq.release(); } catch (Throwable ignored) { }
        try { if (f.bb != null) f.bb.release(); } catch (Throwable ignored) { }
        try { if (f.vz != null) f.vz.release(); } catch (Throwable ignored) { }
    }

    /** {on, bands:[5 dB], bass:0..100, virt:0..100} */
    static synchronized void set(JSONObject o) {
        if (o == null) return;
        on = o.optBoolean("on", false);
        JSONArray b = o.optJSONArray("bands");
        for (int i = 0; i < 5; i++) db[i] = b == null ? 0 : Math.max(-12, Math.min(12, b.optDouble(i, 0)));
        bass = Math.max(0, Math.min(100, o.optInt("bass", 0)));
        virt = Math.max(0, Math.min(100, o.optInt("virt", 0)));
        for (Fx f : live.values()) apply(f);
    }

    /** Valor de la curva en una frecuencia cualquiera (interpolando en escala logarítmica) */
    private static double curve(double hz) {
        if (hz <= FREQ[0]) return db[0];
        if (hz >= FREQ[4]) return db[4];
        for (int i = 0; i < 4; i++) {
            if (hz <= FREQ[i + 1]) {
                double t = (Math.log(hz) - Math.log(FREQ[i])) / (Math.log(FREQ[i + 1]) - Math.log(FREQ[i]));
                return db[i] + (db[i + 1] - db[i]) * t;
            }
        }
        return 0;
    }

    private static void apply(Fx f) {
        try {
            if (f.eq != null) {
                short n = f.eq.getNumberOfBands();
                short[] range = f.eq.getBandLevelRange();
                for (short i = 0; i < n; i++) {
                    double hz = f.eq.getCenterFreq(i) / 1000.0;
                    int mb = (int) Math.round(curve(hz) * K * 100);   /* K: más fuerte, para que se note en la radio */
                    mb = Math.max(range[0], Math.min(range[1], mb));
                    f.eq.setBandLevel(i, (short) (on ? mb : 0));
                }
                f.eq.setEnabled(on);
            }
        } catch (Throwable ignored) { }
        try {
            if (f.bb != null) {
                if (f.bb.getStrengthSupported()) f.bb.setStrength((short) (bass * 10));
                f.bb.setEnabled(on && bass > 0);
            }
        } catch (Throwable ignored) { }
        try {
            if (f.vz != null) {
                if (f.vz.getStrengthSupported()) f.vz.setStrength((short) (virt * 10));
                f.vz.setEnabled(on && virt > 0);
            }
        } catch (Throwable ignored) { }
    }
}
