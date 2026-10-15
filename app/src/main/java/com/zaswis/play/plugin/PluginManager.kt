package com.zaswis.play.plugin

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.zaswis.play.data.model.PluginInfo
import com.zaswis.play.data.model.PluginRepositoryManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * CloudStream-style Extension & Provider Repository Manager
 * Allows dynamic syncing of provider repositories (e.g., Megix CSX, Hindi Providers)
 * and 1-click install/uninstall/toggle of individual provider scrapers.
 */
class PluginManager(private val context: Context) {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val sharedPrefs = context.getSharedPreferences("zaswix_plugins_v4", Context.MODE_PRIVATE)

    // Default pre-configured Zaswix Providers Repository
    val defaultRepoUrl = "https://raw.githubusercontent.com/zaswix/plugins/main/repo.json"

    private val _repoUrls = MutableStateFlow<List<String>>(
        sharedPrefs.getStringSet("repo_urls", setOf(defaultRepoUrl))?.toList() ?: listOf(defaultRepoUrl)
    )
    val repoUrls: StateFlow<List<String>> = _repoUrls.asStateFlow()

    private val _installedPluginIds = MutableStateFlow<Set<String>>(
        sharedPrefs.getStringSet("installed_plugins", setOf("bollyflix", "vegamovies", "toonstream", "cinestream")) ?: setOf("bollyflix", "vegamovies", "toonstream", "cinestream")
    )

    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    private val _availablePlugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val availablePlugins: StateFlow<List<PluginInfo>> = _availablePlugins.asStateFlow()

    private val _selectedPluginId = MutableStateFlow(
        sharedPrefs.getString("active_plugin_id", "bollyflix") ?: "bollyflix"
    )
    val selectedPluginId: StateFlow<String> = _selectedPluginId.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    init {
        loadInitialPlugins()
    }

    private fun getCatalogBase(): List<PluginInfo> {
        return listOf(
            PluginInfo(
                id = "bollyflix",
                name = "BollyFlix",
                version = "v3.3.0",
                siteUrl = "https://bollyflix.photo",
                description = "Download & Stream Latest Bollywood, Hollywood Dual Audio, 480p, 720p & 1080p Movies",
                category = "Bollywood & Dual Audio",
                iconUrl = "https://bollyflix.photo/wp-content/uploads/2023/04/cropped-bollyflix-logo-192x192.png",
                isEnabled = true,
                isInstalled = _installedPluginIds.value.contains("bollyflix")
            ),
            PluginInfo(
                id = "vegamovies",
                name = "VegaMovies",
                version = "v2.5.1",
                siteUrl = "https://vegamovies.gallery",
                description = "Bollywood, Hollywood Hindi Dubbed movies & 4K web series with direct V-Cloud / HubCloud links",
                category = "Movies & 4K Series",
                iconUrl = "https://vegamovies.gallery/images/favicon-32x32.png",
                isEnabled = true,
                isInstalled = _installedPluginIds.value.contains("vegamovies")
            ),
            PluginInfo(
                id = "cinestream",
                name = "CineStream",
                version = "v4.8.7",
                siteUrl = "https://cinemeta-catalogs.strem.io",
                description = "One-stop super provider for Movies, Series, Anime, Dual Audio, and HD Streamers",
                category = "All-In-One Streamer",
                iconUrl = "https://image.tmdb.org/t/p/w500/wwemzKWzjKYJFfCeiB57q3r4Bcm.png",
                isEnabled = true,
                isInstalled = _installedPluginIds.value.contains("cinestream")
            ),
            PluginInfo(
                id = "toonstream",
                name = "ToonStream",
                version = "v1.4.0",
                siteUrl = "https://toonstream.us",
                description = "Watch Cartoons, Anime & Animation with fast CDN streams & multi-episodes",
                category = "Cartoons & Anime",
                iconUrl = "https://toonstream.us/public/images/favicon.ico",
                isEnabled = true,
                isInstalled = _installedPluginIds.value.contains("toonstream")
            ),
            PluginInfo(
                id = "moviesmod",
                name = "MoviesMod",
                version = "v1.8.0",
                siteUrl = "https://moviesmod.ai.in",
                description = "Fast download & direct stream provider for Hindi Dubbed and Web Series",
                category = "Dual Audio & Series",
                iconUrl = "https://moviesmod.ai.in/favicon.ico",
                isEnabled = true,
                isInstalled = _installedPluginIds.value.contains("moviesmod")
            ),
            PluginInfo(
                id = "multimovies",
                name = "MultiMovies",
                version = "v1.2.0",
                siteUrl = "https://multimovies.garden",
                description = "Online Movies, TV Series and Anime with HD multi-servers and fast players",
                category = "Multi-Source",
                iconUrl = "https://multimovies.garden/assets/img/logo.png",
                isEnabled = true,
                isInstalled = _installedPluginIds.value.contains("multimovies")
            ),
            PluginInfo(
                id = "uhdmovies",
                name = "UHD Movies",
                version = "v1.1.0",
                siteUrl = "https://uhdmovies.my",
                description = "Ultra High Definition 4K HDR & 1080p movies and series scraper",
                category = "4K UHD Content",
                iconUrl = "https://uhdmovies.my/favicon.ico",
                isEnabled = true,
                isInstalled = _installedPluginIds.value.contains("uhdmovies")
            ),
            PluginInfo(
                id = "hdhub4u",
                name = "HDHub4u",
                version = "v1.5.0",
                siteUrl = "https://new7.hdhub4u.cl",
                description = "Latest Indian cinema, South Hindi Dubbed, and Hollywood blockbusters",
                category = "Indian Cinema",
                iconUrl = "https://new7.hdhub4u.cl/favicon.ico",
                isEnabled = true,
                isInstalled = _installedPluginIds.value.contains("hdhub4u")
            ),
            PluginInfo(
                id = "netmirror",
                name = "NetMirror",
                version = "v1.0.5",
                siteUrl = "https://netmirror.global",
                description = "Explore movies and TV shows from OTT platforms on Android",
                category = "OTT Streaming",
                iconUrl = "https://netmirror.global/favicon-32x32.png",
                isEnabled = true,
                isInstalled = _installedPluginIds.value.contains("netmirror")
            ),
            PluginInfo(
                id = "plex",
                name = "Plex Stream",
                version = "v1.0.0",
                siteUrl = "https://watch.plex.tv",
                description = "Free on-demand movies, shows and entertainment channels",
                category = "Free TV",
                iconUrl = "https://watch.plex.tv/favicon.ico",
                isEnabled = true,
                isInstalled = _installedPluginIds.value.contains("plex")
            )
        )
    }

    private fun loadInitialPlugins() {
        val base = getCatalogBase()
        _availablePlugins.value = base
        _plugins.value = base.filter { it.isInstalled }
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

        // Update in lists
        _availablePlugins.value = _availablePlugins.value.map {
            if (it.id == pluginId) it.copy(isInstalled = true, isEnabled = true) else it
        }
        _plugins.value = _availablePlugins.value.filter { it.isInstalled }
    }

    fun uninstallPlugin(pluginId: String) {
        val currentSet = _installedPluginIds.value.toMutableSet()
        currentSet.remove(pluginId)
        _installedPluginIds.value = currentSet
        sharedPrefs.edit().putStringSet("installed_plugins", currentSet).apply()

        // Update in lists
        _availablePlugins.value = _availablePlugins.value.map {
            if (it.id == pluginId) it.copy(isInstalled = false) else it
        }
        _plugins.value = _availablePlugins.value.filter { it.isInstalled }

        // If active plugin was uninstalled, fallback
        if (_selectedPluginId.value == pluginId) {
            val next = _plugins.value.firstOrNull()?.id ?: "bollyflix"
            selectPlugin(next)
        }
    }

    fun togglePlugin(pluginId: String) {
        val updated = _plugins.value.map {
            if (it.id == pluginId) it.copy(isEnabled = !it.isEnabled) else it
        }
        _plugins.value = updated
    }

    fun addRepository(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        val current = _repoUrls.value.toMutableList()
        if (!current.contains(trimmed)) {
            current.add(trimmed)
            _repoUrls.value = current
            sharedPrefs.edit().putStringSet("repo_urls", current.toSet()).apply()
        }
    }

    fun removeRepository(url: String) {
        val current = _repoUrls.value.toMutableList()
        current.remove(url)
        _repoUrls.value = current
        sharedPrefs.edit().putStringSet("repo_urls", current.toSet()).apply()
    }

    /**
     * Syncs all repositories (CloudStream CS.json / plugins.json or custom repo manifests)
     */
    suspend fun syncRepositories(): Result<Int> = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        var countFound = 0
        val discoveredPlugins = mutableListOf<PluginInfo>()

        try {
            for (repoUrl in _repoUrls.value) {
                try {
                    val req = Request.Builder()
                        .url(repoUrl)
                        .header("User-Agent", "Zaswix/1.0.0 (Android)")
                        .build()

                    val resp = client.newCall(req).execute()
                    if (!resp.isSuccessful) continue
                    val body = resp.body?.string().orEmpty()

                    // Case 1: Cloudstream CS.json format (has "pluginLists")
                    if (body.contains("pluginLists")) {
                        val obj = gson.fromJson(body, JsonObject::class.java)
                        val pluginLists = obj.getAsJsonArray("pluginLists")
                        pluginLists?.forEach { plUrlElem ->
                            val plUrl = plUrlElem.asString
                            try {
                                val plReq = Request.Builder().url(plUrl).header("User-Agent", "Zaswix/1.0.0").build()
                                val plResp = client.newCall(plReq).execute()
                                if (plResp.isSuccessful) {
                                    val plBody = plResp.body?.string().orEmpty()
                                    parseCloudstreamPluginsJson(plBody, discoveredPlugins)
                                }
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }
                    } else if (body.trim().startsWith("[")) {
                        // Case 2: Direct plugins.json array
                        parseCloudstreamPluginsJson(body, discoveredPlugins)
                    } else {
                        // Case 3: Standard Zaswix PluginRepositoryManifest
                        val manifest = gson.fromJson(body, PluginRepositoryManifest::class.java)
                        manifest?.plugins?.forEach {
                            discoveredPlugins.add(it)
                        }
                    }
                } catch (e: Exception) {
                    // Continue with other repos
                }
            }

            // Merge with local base catalogue
            val baseList = getCatalogBase()
            val merged = (discoveredPlugins + baseList).distinctBy { it.id.lowercase() }.map { plugin ->
                plugin.copy(isInstalled = _installedPluginIds.value.contains(plugin.id))
            }

            _availablePlugins.value = merged
            _plugins.value = merged.filter { it.isInstalled }
            countFound = merged.size
            _isSyncing.value = false
            Result.success(countFound)
        } catch (e: Exception) {
            _isSyncing.value = false
            Result.failure(e)
        }
    }

    private fun parseCloudstreamPluginsJson(json: String, outList: MutableList<PluginInfo>) {
        try {
            val array = gson.fromJson(json, JsonArray::class.java)
            array.forEach { elem ->
                val obj = elem.asJsonObject
                val name = obj.get("name")?.asString ?: ""
                val internal = obj.get("internalName")?.asString ?: name.lowercase()
                val id = internal.lowercase().replace(" ", "")
                val desc = obj.get("description")?.asString ?: "CloudStream Verified Provider"
                val version = "v" + (obj.get("version")?.asString ?: "1.0.0")
                val icon = obj.get("iconUrl")?.asString ?: ""
                val repo = obj.get("repositoryUrl")?.asString ?: ""
                val lang = obj.get("language")?.asString ?: "multi"

                outList.add(
                    PluginInfo(
                        id = id,
                        name = name,
                        version = version,
                        siteUrl = repo,
                        description = "[$lang] $desc",
                        category = "CloudStream Extension",
                        iconUrl = icon,
                        isEnabled = true,
                        isInstalled = _installedPluginIds.value.contains(id)
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
}
