package com.prometeo.music;

import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Cómo el "puente" del Chromecast pide una emisora o canal a internet.
 * Usa EXACTAMENTE el mismo "cartero" que el reproductor del teléfono (ExoPlayer: DefaultHttpDataSource,
 * mismo nombre de app, mismas redirecciones). Así, si una emisora suena en el teléfono, el puente también la puede pedir
 * (antes, algunos servidores como iHeart rechazaban al puente).
 */
@OptIn(markerClass = UnstableApi.class)
final class Net {
    private Net() { }

    static final class Resp {
        private final DefaultHttpDataSource ds;
        private final long length;
        private final InputStream in;

        Resp(DefaultHttpDataSource ds, long length) {
            this.ds = ds;
            this.length = length;
            this.in = new InputStream() {
                @Override public int read() throws IOException {
                    byte[] b = new byte[1];
                    int n = read(b, 0, 1);
                    return n <= 0 ? -1 : (b[0] & 0xFF);
                }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (len == 0) return 0;
                    int n = Resp.this.ds.read(b, off, len);
                    return n < 0 ? -1 : n;    /* C.RESULT_END_OF_INPUT = -1 */
                }
                @Override public void close() { disconnect(); }
            };
        }

        int getResponseCode() { return ds.getResponseCode(); }
        String finalUrl() { Uri u = ds.getUri(); return u == null ? "" : u.toString(); }
        InputStream getInputStream() { return in; }
        long getContentLengthLong() { return length; }

        String getHeaderField(String name) {
            Map<String, List<String>> h = ds.getResponseHeaders();
            if (h == null) return null;
            for (Map.Entry<String, List<String>> e : h.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && e.getValue() != null && !e.getValue().isEmpty()) return e.getValue().get(0);
            }
            return null;
        }

        String getContentType() { return getHeaderField("Content-Type"); }

        void disconnect() { try { ds.close(); } catch (Exception ignored) { } }
    }

    /** range: null o "bytes=a-b" */
    static Resp open(String url, String range) throws IOException {
        DefaultHttpDataSource ds = new DefaultHttpDataSource.Factory()
                .setUserAgent(CastServer.UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(25000)
                .createDataSource();
        DataSpec.Builder b = new DataSpec.Builder().setUri(Uri.parse(url));
        if (range != null && range.startsWith("bytes=")) {
            try {
                String r = range.substring(6).split(",")[0].trim();
                int dash = r.indexOf('-');
                if (dash > 0) {
                    long a = Long.parseLong(r.substring(0, dash));
                    b.setPosition(a);
                    if (dash < r.length() - 1) b.setLength(Long.parseLong(r.substring(dash + 1)) - a + 1);
                }
            } catch (Exception ignored) { }
        }
        try {
            long len = ds.open(b.build());
            return new Resp(ds, len);
        } catch (IOException e) {
            try { ds.close(); } catch (Exception ignored) { }
            throw e;
        }
    }

    /** Motivo corto y entendible de un error, para el registro del diagnóstico */
    static String why(Throwable e) {
        if (e instanceof HttpDataSource.InvalidResponseCodeException) return "HTTP " + ((HttpDataSource.InvalidResponseCodeException) e).responseCode;
        String m = e.getMessage();
        String n = e.getClass().getSimpleName();
        Throwable c = e.getCause();
        if (c != null && c != e) { n = n + "/" + c.getClass().getSimpleName(); if (m == null) m = c.getMessage(); }
        if (m != null && m.length() > 80) m = m.substring(0, 80);
        return m == null ? n : n + ": " + m;
    }
}
