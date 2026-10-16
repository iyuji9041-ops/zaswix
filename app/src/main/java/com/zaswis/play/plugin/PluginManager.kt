package com.zaswis.play.plugin

import android.content.Context
import com.google.gson.Gson
import com.zaswis.play.data.model.PluginInfo
import com.zaswis.play.extractor.HomePagePayload
import com.zaswis.play.extractor.ZaswixDetailsExtractor
import com.zaswis.play.extractor.ZaswixMultiScraper
import dalvik.system.DexClassLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cloudstream-style Plugin Manager with Dynamic DexClassLoader Extension Support.
 * Dynamically loads standalone extension APKs at runtime into loadedPlugins.
 */
class PluginManager(private val context: Context) {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val sharedPrefs = context.getSharedPreferences("zaswix_plugins_v5", Context.MODE_PRIVATE)

    val defaultRepoUrl = "https://raw.githubusercontent.com/zaswix/plugins/main/plugins.json"

    private val _repoUrls = MutableStateFlow<List<String>>(
        sharedPrefs.getStringSet("repo_urls", setOf(defaultRepoUrl))?.toList() ?: listOf(defaultRepoUrl)
    )
    val repoUrls: StateFlow<List<String>> = _repoUrls.asStateFlow()

    private val _installedPluginIds = MutableStateFlow<Set<String>>(
        sharedPrefs.getStringSet("installed_plugins", setOf("toonstream", "multimovies", "bollyflix", "vegamovies"))
            ?: setOf("toonstream", "multimovies", "bollyflix", "vegamovies")
    )

    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    private val _availablePlugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val availablePlugins: StateFlow<List<PluginInfo>> = _availablePlugins.asStateFlow()

    private val _selectedPluginId = MutableStateFlow(
        sharedPrefs.getString("active_plugin_id", "toonstream") ?: "toonstream"
    )
    val selectedPluginId: StateFlow<String> = _selectedPluginId.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    init {
        setApplicationContext(context)
        loadInstalledApkExtensions()
        refreshPluginCatalog()
    }

    /**
     * Loads any previously downloaded APK extensions from app's filesDir/plugins
     */
    private fun loadInstalledApkExtensions() {
        val pluginsDir = File(context.filesDir, "plugins")
        if (pluginsDir.exists() && pluginsDir.isDirectory) {
            val apkFiles = pluginsDir.listFiles { _, name -> name.endsWith(".apk", ignoreCase = true) }
            apkFiles?.forEach { apkFile ->
                val manifestFile = File(pluginsDir, apkFile.nameWithoutExtension + ".json")
                if (manifestFile.exists()) {
                    try {
                        val manifestJson = manifestFile.readText()
                        val className = org.json.JSONObject(manifestJson).optString("className")
                        if (className.isNotEmpty()) {
                            loadExtension(apkFile.absolutePath, className)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun selectPlugin(pluginId: String) {
        _selectedPluginId.value = pluginId
        sharedPrefs.edit().putString("active_plugin_id", pluginId).apply()
    }

    fun installPlugin(pluginId: String) {
        val currentSet = _installedPluginIds.value.toMutableSet()
        currentSet.add(pluginId)
        _installedPluginIds.value = currentSet
        sharedPrefs.edit().putStringSet("installed_plugins", currentSet).apply()
        refreshPluginCatalog()
    }

    fun uninstallPlugin(pluginId: String) {
        val currentSet = _installedPluginIds.value.toMutableSet()
        currentSet.remove(pluginId)
        _installedPluginIds.value = currentSet
        sharedPrefs.edit().putStringSet("installed_plugins", currentSet).apply()
        refreshPluginCatalog()
    }

    fun togglePlugin(pluginId: String) {
        val currentSet = _installedPluginIds.value.toMutableSet()
        if (currentSet.contains(pluginId)) {
            currentSet.remove(pluginId)
        } else {
            currentSet.add(pluginId)
        }
        _installedPluginIds.value = currentSet
        sharedPrefs.edit().putStringSet("installed_plugins", currentSet).apply()
        refreshPluginCatalog()
    }

    fun addRepository(url: String) {
        addRepositoryUrl(url)
    }

    fun addRepositoryUrl(url: String) {
        if (url.isBlank() || _repoUrls.value.contains(url.trim())) return
        val updated = _repoUrls.value + url.trim()
        _repoUrls.value = updated
        sharedPrefs.edit().putStringSet("repo_urls", updated.toSet()).apply()
    }

    fun removeRepositoryUrl(url: String) {
        val updated = _repoUrls.value.filter { it != url }
        _repoUrls.value = updated
        sharedPrefs.edit().putStringSet("repo_urls", updated.toSet()).apply()
    }

    suspend fun syncRepositories(): Result<Int> {
        _isSyncing.value = true
        var count = 0
        return try {
            for (repoUrl in _repoUrls.value) {
                val newlyLoaded = RepositoryManager.fetchAndInstallPlugins(context, repoUrl)
                count += newlyLoaded.size
            }
            refreshPluginCatalog()
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isSyncing.value = false
        }
    }

    private fun refreshPluginCatalog() {
        val allCatalog = getCatalogBase()
        _plugins.value = allCatalog.filter { _installedPluginIds.value.contains(it.id) }
        _availablePlugins.value = allCatalog
    }

    private fun getCatalogBase(): List<PluginInfo> {
        val list = mutableListOf(
            PluginInfo(
                id = "toonstream",
                name = "ToonStream",
                version = "v1.2.0",
                siteUrl = "https://toonstream.us",
                description = "Anime & Cartoons with multi-audio servers (Ruby, Cineverse, Vidrift)",
                category = "Anime & Animation",
                iconUrl = "https://toonstream.us/wp-content/uploads/2023/12/logo-192x192.png",
                isEnabled = true,
                isInstalled = _installedPluginIds.value.contains("toonstream")
            ),
            PluginInfo(
                id = "multimovies",
                name = "MultiMovies",
                version = "v1.1.0",
                siteUrl = "https://multimovies.tv",
                description = "Bollywood, Hollywood & South Indian Dual Audio movies & series",
                category = "Dual Audio & Bollywood",
                iconUrl = "https://multimovies.tv/favicon.ico",
                isEnabled = true,
                isInstalled = _installedPluginIds.value.contains("multimovies")
            ),
            PluginInfo(
                id = "bollyflix",
                name = "BollyFlix",
                version = "v3.3.0",
                siteUrl = "https://bollyflix.photo",
                description = "Bollywood & Hollywood movies with high-speed direct downloads",
                category = "Bollywood",
                iconUrl = "https://bollyflix.photo/wp-content/uploads/2023/04/cropped-bollyflix-logo-192x192.png",
                isEnabled = true,
                isInstalled = _installedPluginIds.value.contains("bollyflix")
            ),
            PluginInfo(
                id = "vegamovies",
                name = "VegaMovies",
                version = "v2.5.1",
                siteUrl = "https://vegamovies.gallery",
                description = "4K Hindi Dubbed Movies & Web Series with V-Cloud links",
                category = "4K Web Series",
                iconUrl = "https://vegamovies.gallery/images/favicon-32x32.png",
                isEnabled = true,
                isInstalled = _installedPluginIds.value.contains("vegamovies")
            )
        )

        // Add dynamically loaded plugins from external APKs to catalog
        synchronized(loadedPlugins) {
            loadedPlugins.forEach { plugin ->
                val id = plugin.providerName.lowercase().replace("\\s+".toRegex(), "")
                if (list.none { it.id == id }) {
                    list.add(
                        PluginInfo(
                            id = id,
                            name = plugin.providerName,
                            version = plugin.version,
                            siteUrl = "",
                            description = "Dynamically loaded Dex extension provider",
                            category = "External Extension",
                            iconUrl = "",
                            isEnabled = true,
                            isInstalled = true
                        )
                    )
                }
            }
        }
        return list
    }

    companion object {
        val loadedPlugins = mutableListOf<ZaswixPlugin>()
        private var appContext: Context? = null

        init {
            // Register built-in default providers so the app is 100% operational out of the box
            synchronized(loadedPlugins) {
                if (loadedPlugins.isEmpty()) {
                    loadedPlugins.add(BuiltInToonStreamPlugin())
                    loadedPlugins.add(BuiltInMultiMoviesPlugin())
                }
            }
        }

        fun setApplicationContext(context: Context) {
            appContext = context.applicationContext
        }

        /**
         * Loads a standalone extension APK dynamically using DexClassLoader.
         * Instantiates className using reflection and registers it in loadedPlugins.
         */
        fun loadExtension(apkPath: String, className: String): ZaswixPlugin? {
            val file = File(apkPath)
            if (!file.exists()) return null

            val context = appContext
            val optimizedDexDir = if (context != null) {
                File(context.codeCacheDir, "dex").apply { mkdirs() }
            } else {
                File(file.parentFile, "code_cache").apply { mkdirs() }
            }

            return try {
                val parentLoader = context?.classLoader ?: ClassLoader.getSystemClassLoader()
                val dexClassLoader = DexClassLoader(
                    file.absolutePath,
                    optimizedDexDir.absolutePath,
                    null,
                    parentLoader
                )

                val pluginClass = dexClassLoader.loadClass(className)
                val instance = pluginClass.getDeclaredConstructor().newInstance() as ZaswixPlugin

                synchronized(loadedPlugins) {
                    loadedPlugins.removeAll { it.providerName.equals(instance.providerName, ignoreCase = true) }
                    loadedPlugins.add(instance)
                }
                instance
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        fun loadExtension(context: Context, apkPath: String, className: String): ZaswixPlugin? {
            setApplicationContext(context)
            return loadExtension(apkPath, className)
        }
    }
}

/**
 * Built-in ToonStream plugin provider implementation
 */
class BuiltInToonStreamPlugin : ZaswixPlugin {
    override val providerName: String = "ToonStream"
    override val version: String = "1.0.0"

    override suspend fun getHomePage(): HomePagePayload {
        return ZaswixMultiScraper.scrapeToonStream()
    }

    override suspend fun search(query: String): List<MediaItem> {
        val payload = ZaswixMultiScraper.scrapeToonStream()
        val allItems = payload.categoryRows.flatMap { it.items } + payload.heroBannerItems
        return allItems
            .filter { it.title.contains(query, ignoreCase = true) }
            .distinctBy { it.url }
            .map { MediaItem(it.title, it.posterUrl, it.url, it.type) }
    }

    override suspend fun getDetails(url: String): MediaDetails {
        val details = ZaswixDetailsExtractor.loadToonStreamDetails(url)
        return MediaDetails(
            title = details?.title.orEmpty(),
            synopsis = details?.synopsis.orEmpty(),
            rating = details?.rating.orEmpty(),
            servers = details?.servers.orEmpty(),
            episodes = details?.episodes.orEmpty(),
            genres = details?.genres.orEmpty()
        )
    }
}

/**
 * Built-in MultiMovies plugin provider implementation
 */
class BuiltInMultiMoviesPlugin : ZaswixPlugin {
    override val providerName: String = "MultiMovies"
    override val version: String = "1.0.0"

    override suspend fun getHomePage(): HomePagePayload {
        return ZaswixMultiScraper.scrapeMultiMovies()
    }

    override suspend fun search(query: String): List<MediaItem> {
        val payload = ZaswixMultiScraper.scrapeMultiMovies()
        val allItems = payload.categoryRows.flatMap { it.items } + payload.heroBannerItems
        return allItems
            .filter { it.title.contains(query, ignoreCase = true) }
            .distinctBy { it.url }
            .map { MediaItem(it.title, it.posterUrl, it.url, it.type) }
    }

    override suspend fun getDetails(url: String): MediaDetails {
        val details = ZaswixDetailsExtractor.loadMultiMoviesDetails(url)
        return MediaDetails(
            title = details?.title.orEmpty(),
            synopsis = details?.synopsis.orEmpty(),
            rating = details?.rating.orEmpty(),
            servers = details?.servers.orEmpty(),
            episodes = details?.episodes.orEmpty(),
            genres = details?.genres.orEmpty()
        )
    }
}
