package com.prometeo.lame;

import com.prometeo.lame.mp3.BitStream;
import com.prometeo.lame.mp3.GainAnalysis;
import com.prometeo.lame.mp3.GetAudio;
import com.prometeo.lame.mp3.ID3Tag;
import com.prometeo.lame.mp3.Lame;
import com.prometeo.lame.mp3.LameGlobalFlags;
import com.prometeo.lame.mp3.MPEGMode;
import com.prometeo.lame.mp3.Parse;
import com.prometeo.lame.mp3.Presets;
import com.prometeo.lame.mp3.Quantize;
import com.prometeo.lame.mp3.QuantizePVT;
import com.prometeo.lame.mp3.Reservoir;
import com.prometeo.lame.mp3.Takehiro;
import com.prometeo.lame.mp3.VBRTag;
import com.prometeo.lame.mp3.Version;
import com.prometeo.lame.mpg.Common;
import com.prometeo.lame.mpg.Interface;
import com.prometeo.lame.mpg.MPGLib;

/**
 * Prometeo — envoltura sencilla del codificador MP3 jump3r (port en Java de LAME 3.98.4, LGPL 2.1+).
 * Reemplaza a lowlevel/LameEncoder del original, que usa javax.sound (no existe en Android).
 * El código de jump3r (carpetas mp3/ y mpg/) se usa sin cambios, solo con otro nombre de paquete.
 */
public final class Mp3Enc {
    private final Lame lame;
    private final LameGlobalFlags gfp;
    private final int channels;
    private int[] l = new int[0], r = new int[0];

    /** sampleRate: el del audio original; channels: 1 o 2; kbps: p. ej. 192 */
    public Mp3Enc(int sampleRate, int channels, int kbps) {
        this.channels = channels == 1 ? 1 : 2;
        lame = new Lame();
        GetAudio gaud = new GetAudio(); GainAnalysis ga = new GainAnalysis(); BitStream bs = new BitStream();
        Presets p = new Presets(); QuantizePVT qupvt = new QuantizePVT(); Quantize qu = new Quantize();
        VBRTag vbr = new VBRTag(); Version ver = new Version(); ID3Tag id3 = new ID3Tag(); Reservoir rv = new Reservoir();
        Takehiro tak = new Takehiro(); Parse parse = new Parse(); MPGLib mpg = new MPGLib(); Interface intf = new Interface(); Common common = new Common();
        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg);
        bs.setModules(ga, mpg, ver, vbr);
        id3.setModules(bs, ver);
        p.setModules(lame);
        qu.setModules(bs, rv, qupvt, tak);
        qupvt.setModules(tak, rv, lame.enc.psy);
        rv.setModules(bs);
        tak.setModules(qupvt);
        vbr.setModules(lame, bs, ver);
        gaud.setModules(parse, mpg);
        parse.setModules(ver, id3, p);
        mpg.setModules(intf, common);
        intf.setModules(vbr, common);
        gfp = lame.lame_init();
        gfp.num_channels = this.channels;
        gfp.in_samplerate = sampleRate;
        gfp.mode = this.channels == 1 ? MPEGMode.MONO : MPEGMode.JOINT_STEREO;
        gfp.brate = kbps;
        gfp.quality = 5;
        id3.id3tag_init(gfp);
        gfp.write_id3tag_automatic = false;
        gfp.findReplayGain = false;
        if (lame.lame_init_params(gfp) < 0) throw new IllegalArgumentException("lame");
    }

    /** Tamaño seguro del búfer de salida para n muestras por canal */
    public static int outSize(int n) { return (int) (1.25 * n + 7200); }

    /** pcm: 16 bits intercalado (L,R,L,R… o mono); n = muestras por canal. Devuelve bytes escritos en out */
    public int encode(short[] pcm, int n, byte[] out) {
        if (l.length < n) { l = new int[n]; r = new int[n]; }
        /* Ojo: en jump3r, lame_encode_buffer_int usa los valores tal cual (escala de 16 bits), sin dividir entre 65536 como el LAME en C */
        if (channels == 2) for (int i = 0; i < n; i++) { l[i] = pcm[2 * i]; r[i] = pcm[2 * i + 1]; }
        else for (int i = 0; i < n; i++) { l[i] = r[i] = pcm[i]; }
        return lame.lame_encode_buffer_int(gfp, l, r, n, out, 0, out.length);
    }

    public int flush(byte[] out) { return lame.lame_encode_flush(gfp, out, 0, out.length); }
}
