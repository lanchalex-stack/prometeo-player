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
    /** lista de canciones: [{id,name,title,artist,duration,size,mime,folder,rec}] */
    scan: function () { return call('scan'); },
    /** dirección para reproducir una canción sin copiarla */
    url: function (id) { return '/__prometeo_music__/' + id; },
    /** lo que muestra el carro por Bluetooth: {title, artist, album, duration(ms), art(base64 JPEG)} */
    nowPlaying: function (info) {
        return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'nowPlaying', [info || {}]); });
    },
    /** play/pausa y posición actual (ms) para el carro; kind 'music' protege la música en segundo plano */
    playState: function (playing, posMs, kind) {
        return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'playState', [!!playing, Math.round(posMs || 0), kind || '']); });
    },
    /* ---- Radio con ExoPlayer ---- */
    /** eventos de la radio: fn({type:'state', value:'connecting'|'online'|'paused'} | {type:'title', value} | {type:'error', value}) */
    onRadio: function (fn) {
        exec(function (ev) { if (ev && fn) fn(ev); }, function () {}, 'PrometeoMusic', 'radioEvents', []);
    },
    /** mode 'tv' = canal de TV en solo audio (sin video, calidad más baja) */
    radioPlay: function (url, name, mode) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'radioPlay', [url, name || '', mode || '']); }); },
    radioPause: function () { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'radioPause', []); }); },
    radioStop: function () { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'radioStop', []); }); },
    /* ---- TV / IPTV ---- */
    /** abre el canal a pantalla completa con ExoPlayer */
    /** opts (opcional): {list:[{n,u,g,f}], i, s:{textos}, aspect, quality} para cambiar de canal dentro del reproductor */
    playVideo: function (url, title, errorText, opts) {
        return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'playVideo', [url, title || '', errorText || '', opts || null]); });
    },
    /* ---- Chromecast ---- */
    castAvailable: function () { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'castAvailable', []); }); },
    castScan: function (on) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'castScan', [on !== false]); }); },
    castConnect: function (id) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'castConnect', [id]); }); },
    castDisconnect: function () { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'castDisconnect', []); }); },
    /** o = {kind:'music'|'radio'|'tv', url | mid+ext+mime, title, artist, pos, dur} */
    castLoad: function (o) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'castLoad', [o]); }); },
    /** 'play' | 'pause' | 'toggle' | 'stop' | 'seek' (ms) */
    castCmd: function (c, ms) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'castCmd', [c, Math.round(ms || 0)]); }); },
    /** avisos: {type:'cast', state, device} | {type:'castRoutes', list} | {type:'castStatus', state, idle, pos, dur, kind} */
    onCast: function (fn) { exec(function (ev) { if (ev && fn) fn(ev); }, function () {}, 'PrometeoMusic', 'castEvents', []); },
    /** guarda un archivo de texto en Descargas/Prometeo; devuelve la ruta */
    /* Copias de seguridad en Descargas/Prometeo. overwrite=true reescribe la copia automática */
    saveDownload: function (name, text, overwrite) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'saveDownload', [name, text, !!overwrite]); }); },
    listDownloads: function () { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'listDownloads', []); }); },
    readDownload: function (id) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'readDownload', [id]); }); },
    deleteDownload: function (id) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'deleteDownload', [id]); }); },
    /* Ecualizador de la radio y la TV: {on, bands:[5 dB], bass:0..100, virt:0..100} */
    audioFx: function (o) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'audioFx', [o]); }); },
    /** TV en pequeño dentro de la app: rect = {x, y, w, h, vw} del recuadro en la página */
    tvInline: function (url, rect) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'tvInline', [url, rect]); }); },
    /** mueve u oculta ({show:false}) el recuadro del TV pequeño */
    tvInlineRect: function (rect) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'tvInlineRect', [rect]); }); },
    /** 'play' | 'pause' | 'toggle' */
    tvInlineCmd: function (cmd) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'tvInlineCmd', [cmd]); }); },
    tvClose: function () { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'tvClose', []); }); },
    /** avisos del reproductor de TV: fn({type:'fav'|'watch'|'audio'|'settings'|'closed', ...}) */
    onTv: function (fn) {
        exec(function (ev) { if (ev && fn) fn(ev); }, function () {}, 'PrometeoMusic', 'tvEvents', []);
    },
    /** descarga el texto de una lista M3U */
    fetchText: function (url) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'fetchText', [url]); }); },
    /* ---- Agregar emisora fácil ---- */
    /** lee una página (o JSON) del link que el usuario pegó: {type:'text'|'audio', url, ctype, text} */
    fetchPage: function (url, asBrowser) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'fetchPage', [url, asBrowser !== false]); }); },
    /** servidores de Radio Browser (buscados por DNS, como pide el proyecto) */
    rbServers: function () { return call('rbServers'); },
    /* ---- Audios privados ---- */
    /** true si el teléfono tiene bloqueo de pantalla (huella, PIN, patrón o contraseña) */
    lockStatus: function () { return call('lockStatus').then(function (r) { return r === 1; }); },
    /** pide la huella, el PIN o el patrón del teléfono; se cumple si es correcto, falla con 'cancel' | 'nolock' | 'fail' */
    authenticate: function (title, subtitle) {
        return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'authenticate', [title || '', subtitle || '']); });
    },
    /** abre los ajustes de seguridad de Android para poner un bloqueo de pantalla */
    openLockSettings: function () { return call('openLockSettings'); },
    /** botones del volante / del carro: fn({action:'play'|'pause'|'next'|'prev'|'seek', pos}) */
    onControl: function (fn) {
        exec(function (ev) { if (ev && fn) fn(ev); }, function () {}, 'PrometeoMusic', 'controls', []);
    }
};
