# Zaswix Play 🎬

**Zaswix Play** is a modern, high-performance streaming application for Android built with Jetpack Compose, ExoPlayer (Media3), and a CloudStream-compatible multi-provider scraper engine.

---

## ⚡ Features

- **Jetpack Compose UI:** Premium cinematic Netflix-inspired dark UI with animated carousels, responsive catalog rows, and immersive details pages.
- **ExoPlayer Media3:** Built-in HLS, DASH, MKV, and MP4 streaming engine with dynamic quality switching (1080p, 720p, 480p, Auto), audio track selection, and subtitles.
- **Provider & Extension Engine:** CloudStream & Vega compatible repository architecture. Supports 1-click install, toggle, and dynamic mirror synchronization.
- **Live Scraper Integrations:**
  - BollyFlix
  - VegaMovies (V-Cloud & HubCloud extractors)
  - ToonStream
  - MultiMovies
  - NetMirror
  - MultiNime
  - HDHub4u
  - MoviesMod
  - UHDMovies
  - Plex Stream
- **Zero Dummy Videos:** Strictly original media link resolving directly from provider CDN endpoints.

---

## 🛠️ Tech Stack

- **UI:** Jetpack Compose, Material 3, Coil
- **Media Playback:** AndroidX Media3 ExoPlayer (`media3-exoplayer`, `media3-exoplayer-hls`, `media3-exoplayer-dash`, `media3-ui`)
- **Networking:** OkHttp 4, Gson, Kotlin Coroutines, Kotlin Flow
- **Architecture:** MVVM, Clean Architecture, Repository Pattern

---

## 🚀 Build Instructions

### Prerequisites
- Android SDK (API 35 / 36)
- Java 17+
- Gradle 8.14+

### Building Debug APK
```bash
gradle assembleDebug
```
Output APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 License
This project is open source and available under the [MIT License](LICENSE).
