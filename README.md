<div align="center">

<img src="res/icon/android/legacy-xxxhdpi.png" alt="Ícono de Prometeo Player" width="128">

# 🔥 Prometeo Player V41

**Reproductor de música, radio y TV para Android. Sin anuncios y sin recopilar datos.**

![Versión](https://img.shields.io/badge/versión-V41-00e5ff?style=for-the-badge)
![Android](https://img.shields.io/badge/Android-7%2B-3ddc84?style=for-the-badge&logo=android&logoColor=white)
![Privacidad](https://img.shields.io/badge/sin-recopilar%20datos-0a0a0a?style=for-the-badge)

[**⬇️ Descargar APK V41**](https://github.com/lanchalex-stack/prometeo-player/releases/tag/v41) · [**🌐 Probar la versión web**](https://lanchalex-stack.github.io/prometeo-player/)

</div>

---

## ✨ Novedades de la V41

- 🎨 **5 skins**: Neón (el de siempre) y 4 nuevos: Fuego, Pink, Amatista y Claro.
- 🌍 **4 idiomas**: español, inglés, portugués y francés. Por defecto usa el idioma del teléfono.
- ℹ️ **Acerca de**, con los créditos y las licencias de todo lo que usa la app.
- 💾 **Sin copias de tus canciones**: Prometeo lee la música directamente del teléfono, con el botón **🔍 Buscar música en el teléfono**. Al actualizar desde la V40 se libera el espacio de las copias viejas.
- 🖼️ **Portada del álbum** al lado del visualizador, sacada del propio archivo (MP3, FLAC, OGG/Opus, M4A).
- 🚗 **Pantalla del carro por Bluetooth**: canción, artista, álbum y portada, con los botones del volante.
  - Se pausa sola al apagar el carro o desconectar el Bluetooth.
  - No arranca sola al conectar el carro.
- 📻 **Radio por internet**: agregas los links de tus emisoras y Prometeo solo se conecta mientras suenan.
  - Sigue sonando en segundo plano y con la pantalla apagada.
  - Si se pierde la señal, se reconecta sola.
  - Muestra la canción que está sonando, si la emisora la envía.
- 📺 **Canales de TV / IPTV** con listas **M3U** (link o archivo del teléfono), en pantalla completa con **ExoPlayer**.
- 🆕 **Ícono nuevo**.

## 🎵 También incluye (desde la V40)

- 📂 Playlists: crea, renombra, borra y organiza tus listas.
- 🔀 Aleatorio y repetir (toda la cola o una canción).
- 🌈 4 visualizadores: Barras, Onda, Círculo y Fuego.
- 🔍 Buscador de canciones.
- 🎧 Mini reproductor en todas las pestañas y controles en la pantalla de bloqueo.
- 💾 Recuerda la última canción, la posición y tus ajustes.

## 🔒 Privacidad

- **No recopila datos**: sin anuncios, sin estadísticas y sin cuentas.
- **Tu música se reproduce sin internet.**
- **Internet solo para lo que tú agregas**: Prometeo no trae emisoras ni listas. Solo se conecta a las radios y canales que agregas, y solo mientras los escuchas o los ves.
- **Permisos que pide**:
  - "Música y audio" (o "Almacenamiento" en Android 12 o menos), para encontrar tus canciones.
  - Internet, solo para la radio y la TV.
  - Reproducción en segundo plano, para que la radio siga sonando.
- No pide ubicación, contactos, cámara ni micrófono.

## 📲 Instalación

1. Descarga **`Prometeo-v41.apk`** desde [Releases](https://github.com/lanchalex-stack/prometeo-player/releases/tag/v41).
2. Ábrelo y permite **"Instalar apps desconocidas"** si Android lo pide.
3. Si tienes la V40, toca **Actualizar**. **No la desinstales**, así conservas tus playlists y se libera el espacio de las copias.
4. Acepta el permiso de **Música y audio**. Prometeo encontrará tu música.

## 🛠️ Compilar desde el código (Termux + Cordova)

| Carpeta | Qué contiene |
|---|---|
| [`www/index.html`](www/index.html) | Todo el reproductor (HTML + CSS + JavaScript) en un solo archivo |
| [`prometeo-music/`](prometeo-music) | Plugin propio en Java: música sin copias, carro por Bluetooth, radio y TV con ExoPlayer |
| [`res/icon/android/`](res/icon/android) | Íconos de la app |
| [`docs/index.html`](docs/index.html) | Versión web, publicada con GitHub Pages |

```bash
cd ~/prometeo
cordova plugin add ./prometeo-music
cordova build android
```

La primera compilación descarga ExoPlayer y las librerías de AndroidX, así que necesita internet.

## 🙏 Créditos

**Prometeo Player** fue desarrollado por **Lanchalex**, con asistencia de **Meta AI** y **Claude (Anthropic)**.
El ícono fue generado con **Meta AI** por Lanchalex.

Prometeo existe gracias a estos proyectos de código abierto. Todos usan la **Apache License 2.0**:

| Proyecto | Autor | Para qué lo usa Prometeo |
|---|---|---|
| [Apache Cordova](https://github.com/apache/cordova-android) (cordova-android 15.1.0) | The Apache Software Foundation | Convertir Prometeo en app de Android |
| [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) 1.9.4 | The Android Open Source Project | Reproducir radio y canales de TV/IPTV |
| [AndroidX](https://android.googlesource.com/platform/frameworks/support) (AppCompat, Core, WebKit, Lifecycle…) | The Android Open Source Project | Componentes de Android que usan Cordova y Media3 |
| [Guava](https://github.com/google/guava) 33.3.1-android | The Guava Authors | Herramientas internas de Media3 |
| [Kotlin Standard Library](https://github.com/JetBrains/kotlin) 2.1.21 | JetBrains s.r.o. y colaboradores de Kotlin | Lenguaje interno de las librerías de AndroidX |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) 1.6.4 | JetBrains s.r.o. y colaboradores de Kotlin | Tareas internas de AndroidX |
| [JetBrains Annotations](https://github.com/JetBrains/java-annotations) 13.0 | JetBrains s.r.o. | Anotaciones de código |
| [JSpecify](https://github.com/jspecify/jspecify) 1.0.0 | The JSpecify Authors | Anotaciones de código |

La lista completa, con cada librería y su versión, está en **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)**. El texto completo de la licencia está en **[LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt)**. También se pueden ver dentro de la app, en **ⓘ Acerca de → Licencias**.

Prometeo Player es un proyecto independiente. No está afiliado ni respaldado por Google, The Apache Software Foundation, JetBrains, Meta ni Anthropic. Android es una marca de Google LLC. Los demás nombres mencionados son marcas de sus respectivos dueños.

## ⚖️ Licencia

**Prometeo Player © 2026 Lanchalex. Todos los derechos reservados.** Lee [LICENSE](LICENSE).

El código está publicado para que se pueda ver, pero no se puede copiar, modificar, redistribuir ni vender sin permiso del autor. Las librerías de terceros conservan su propia licencia (Apache 2.0).

---

<div align="center">

Hecho con cariño para la familia, desde Termux 📱

</div>
