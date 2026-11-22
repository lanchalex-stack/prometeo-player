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
    /** lista de canciones: [{id,name,title,artist,duration,size,mime,folder,rec,vol,date}] */
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
    /* ---- v42: Agregar música, memorias (SD / USB) y ordenar ---- */
    /** memorias del teléfono: [{name, label, removable, primary, mounted, free, total, indexed, songs}] */
    volumes: function () { return call('volumes'); },
    /** aviso cuando se conecta o se saca una tarjeta / memoria USB: fn({type:'volumes'}) */
    onVolumes: function (fn) { exec(function (ev) { if (ev && fn) fn(ev); }, function () {}, 'PrometeoMusic', 'volEvents', []); },
    /** le pide a Android que vuelva a revisar carpetas: dirs = [{vol, rel}] */
    rescan: function (dirs) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'rescan', [dirs || []]); }); },
    /** selector de archivos de Android (varias canciones): [{uri, name, size, mime, mid}] */
    pickAudio: function () { return call('pickAudio'); },
    /** permitir una carpeta o memoria USB (vol = nombre de la memoria): devuelve el permiso (tree) */
    pickTree: function (vol) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'pickTree', [vol || '']); }); },
    /** canciones dentro de una carpeta permitida: [{uri, name, size, mime, folder}] */
    listTree: function (tree) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'listTree', [tree]); }); },
    releaseUri: function (uri) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'releaseUri', [uri]); }); },
    /** dirección para reproducir una canción elegida con el selector */
    uriUrl: function (uri) { var b = btoa(unescape(encodeURIComponent(uri))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''); return '/__prometeo_uri__/' + b + '/a'; },
    /** mover canciones a Música/Prometeo: job = {items:[{mid, vol, rel}], dels:[mid]}; onProgress({done,total,name}) */
    organize: function (job, onProgress) {
        return new Promise(function (ok, fail) {
            exec(function (ev) { if (ev && ev.type === 'progress') { if (onProgress) onProgress(ev); } else ok(ev); }, fail, 'PrometeoMusic', 'organize', [job]);
        });
    },
    /* ---- v42: links de canales y emisoras ---- */
    copyText: function (text) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'copyText', [text]); }); },
    shareText: function (text, subject) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'shareText', [text, subject || '']); }); },
    /** prueba un link (solo se conecta y se cierra): {ok, code, ctype, why} */
    probeUrl: function (url) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'probeUrl', [url]); }); },
    /* ---- v43: 🎬 videos del teléfono ---- */
    /** 1 = todos los videos, 2 = acceso limitado (solo los elegidos), 0 = sin permiso */
    hasVideoPermission: function () { return call('hasVideoPermission').then(function (r) { return +r || 0; }); },
    requestVideoPermission: function () { return call('requestVideoPermission').then(function (r) { return +r || 0; }); },
    /** v43b: elegir videos sueltos con el selector de Android: [{uri, name, size, mime, mid}] */
    pickVideo: function () { return call('pickVideo'); },
    /** v43b: duración (ms) de videos de memoria USB: {uri: ms} */
    videoDurations: function (uris) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'videoDurations', [uris || []]); }); },
    /** [{id, name, title, duration, size, mime, folder, vol, date, w, h}] */
    scanVideos: function () { return call('scanVideos'); },
    /** "Seguir viendo": {id:{p, d, t}} */
    videoState: function () { return call('videoState'); },
    videoMovePos: function (from, to) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'videoMovePos', [from, to]); }); },
    /** avisos del reproductor de video: fn({type:'watch'|'closed'|'cast'|'error', id, pos}) */
    onVideoEvents: function (fn) { exec(function (ev) { if (ev && fn) fn(ev); }, function () {}, 'PrometeoMusic', 'videoEvents', []); },
    /** abre el reproductor: job = {items:[{id, n}], i, repeat, s:{textos}} */
    playLocal: function (job) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'playLocal', [job]); }); },
    /** 📸 captura del canal en pantalla pequeña: {ok, black} */
    tvShot: function (name) { return new Promise(function (ok, fail) { exec(ok, fail, 'PrometeoMusic', 'tvShot', [name || 'TV']); }); },
    /** botones del volante / del carro: fn({action:'play'|'pause'|'next'|'prev'|'seek', pos}) */
    onControl: function (fn) {
        exec(function (ev) { if (ev && fn) fn(ev); }, function () {}, 'PrometeoMusic', 'controls', []);
    }
};
