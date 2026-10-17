# ToonStream Standalone Plugin Template (Headless Android APK)

This is a complete, self-contained template for building the **ToonStream** scraper as a standalone, headless Android APK for **Zaswix**.

---

## 🏗️ Architecture

- **Empty Shell Main App**: The main Zaswix app contains **zero** hardcoded scrapers or website-specific logic.
- **Dynamic Class Loading**: The main app downloads this plugin APK and uses Android's `DexClassLoader` to instantiate `com.zaswis.plugin.toonstream.ToonStreamPlugin` implementing `ZaswixPlugin`.
- **Headless APK**: Applies `com.android.application` to compile into an `.apk` with Dex bytecode, but defines **no Activities or UI components** in `AndroidManifest.xml`.

---

## 📁 Project Structure

```
plugin-toonstream/
├── build.gradle.kts                   # Android application module configuration
├── src/
│   └── main/
│       ├── AndroidManifest.xml        # Headless manifest with INTERNET permission
│       └── java/
│           ├── com/zaswis/play/plugin/
│           │   └── ZaswixPlugin.kt    # Standard plugin interface (getHomePage, search, getDetails)
│           ├── com/zaswis/play/extractor/
│           │   └── ExtractorModels.kt # Unified models (HomePagePayload, ServerOption, etc.)
│           └── com/zaswis/plugin/toonstream/
│               └── ToonStreamPlugin.kt # Full ToonStream scraper implementation
└── README.md
```

---

## 🚀 How to Build the Standalone APK

1. In your separate plugin repository or multi-project build:
   ```bash
   ./gradlew :plugin-toonstream:assembleRelease
   ```
   *(or `./gradlew :plugin-toonstream:assembleDebug`)*

2. The compiled headless APK will be produced at:
   ```
   plugin-toonstream/build/outputs/apk/release/plugin-toonstream-release.apk
   ```

---

## 🌐 How to Host on GitHub Releases & Wire to Repository

1. Create a GitHub Release in your repository (e.g. `https://github.com/your-username/zaswix-plugins`):
   - Tag: `v1.2.0`
   - Upload asset: `plugin-toonstream.apk`

2. Add the plugin entry to your `plugins.json` manifest:
   ```json
   {
     "repositoryName": "Official Zaswix Extensions",
     "plugins": [
       {
         "id": "toonstream",
         "name": "ToonStream",
         "version": "v1.2.0",
         "siteUrl": "https://toonstream.us",
         "description": "Anime & Cartoons with multi-audio servers",
         "category": "Anime & Animation",
         "iconUrl": "https://toonstream.us/wp-content/uploads/2023/12/logo-192x192.png",
         "downloadUrl": "https://github.com/your-username/zaswix-plugins/releases/download/v1.2.0/plugin-toonstream.apk",
         "className": "com.zaswis.plugin.toonstream.ToonStreamPlugin"
       }
     ]
   }
   ```

3. When users click **Install** in the Zaswix "Extensions & Providers" screen:
   - Zaswix downloads `plugin-toonstream.apk` to private app storage (`context.getDir("plugins", Context.MODE_PRIVATE)`).
   - Instantiates `com.zaswis.plugin.toonstream.ToonStreamPlugin` via `DexClassLoader`.
   - The plugin is immediately active and populates Home, Search, and Details!
