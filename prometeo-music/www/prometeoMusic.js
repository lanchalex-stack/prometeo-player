var exec = require('cordova/exec');

function call(action) {
    return new Promise(function (ok, fail) {
        exec(ok, fail, 'PrometeoMusic', action, []);
    });
}

module.exports = {
    /** true si ya tiene permiso de leer la música */
    hasPermission: function () { return call('hasPermission').then(function (r) { return r === 1; }); },
    /** muestra la ventana de permiso de Android; true si el usuario aceptó */
    requestPermission: function () { return call('requestPermission').then(function (r) { return r === 1; }); },
    /** lista de canciones: [{id,name,title,artist,duration,size,mime}] */
    scan: function () { return call('scan'); },
    /** dirección para reproducir una canción sin copiarla */
    url: function (id) { return '/__prometeo_music__/' + id; },
    /** lo que muestra el carro por Bluetooth: {title, artist, album, duration(ms), art(base64 JPEG)} */
    nowPlaying: function (info) {
        return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'nowPlaying', [info || {}]); });
    },
    /** play/pausa y posición actual (ms) para el carro */
    playState: function (playing, posMs) {
        return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'playState', [!!playing, Math.round(posMs || 0)]); });
    },
    /* ---- Radio con ExoPlayer ---- */
    /** eventos de la radio: fn({type:'state', value:'connecting'|'online'|'paused'} | {type:'title', value} | {type:'error', value}) */
    onRadio: function (fn) {
        exec(function (ev) { if (ev && fn) fn(ev); }, function () {}, 'PrometeoMusic', 'radioEvents', []);
    },
    radioPlay: function (url, name) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'radioPlay', [url, name || '']); }); },
    radioPause: function () { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'radioPause', []); }); },
    radioStop: function () { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'radioStop', []); }); },
    /* ---- TV / IPTV ---- */
    /** abre el canal a pantalla completa con ExoPlayer */
    playVideo: function (url, title, errorText) {
        return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'playVideo', [url, title || '', errorText || '']); });
    },
    /** descarga el texto de una lista M3U */
    fetchText: function (url) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'fetchText', [url]); }); },
    /** botones del volante / del carro: fn({action:'play'|'pause'|'next'|'prev'|'seek', pos}) */
    onControl: function (fn) {
        exec(function (ev) { if (ev && fn) fn(ev); }, function () {}, 'PrometeoMusic', 'controls', []);
    }
};
