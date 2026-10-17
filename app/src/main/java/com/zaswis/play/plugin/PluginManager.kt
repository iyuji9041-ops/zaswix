package com.zaswis.play.plugin

import android.content.Context
import com.google.gson.Gson
import com.zaswis.play.data.model.PluginInfo
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Cloudstream-style Plugin Manager with Dynamic DexClassLoader Extension Support.
 * The host app is a Legal Empty Shell: all scraper logic is dynamically loaded from
 * external standalone APKs at runtime into loadedPlugins.
 */
class PluginManager(private val context: Context) {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .dns(com.zaswis.play.network.CloudflareDns.dns)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val sharedPrefs = context.getSharedPreferences("zaswix_plugins_v6", Context.MODE_PRIVATE)

    private val _repoUrls = MutableStateFlow<List<String>>(loadStoredRepoUrls())
    val repoUrls: StateFlow<List<String>> = _repoUrls.asStateFlow()

    private val _installedPluginIds = MutableStateFlow<Set<String>>(
        sharedPrefs.getStringSet("installed_plugins", emptySet()) ?: emptySet()
    )

    private val _repoPlugins = MutableStateFlow<List<PluginInfo>>(loadCachedRepoPlugins())

    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    private val _availablePlugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val availablePlugins: StateFlow<List<PluginInfo>> = _availablePlugins.asStateFlow()

    private val _selectedPluginId = MutableStateFlow(
        sharedPrefs.getString("active_plugin_id", "") ?: ""
    )
    val selectedPluginId: StateFlow<String> = _selectedPluginId.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private fun loadStoredRepoUrls(): List<String> {
        val stored = sharedPrefs.getStringSet("repo_urls", emptySet()) ?: emptySet()
        val cleaned = stored.filter {
            it.isNotBlank() && !it.contains("raw.githubusercontent.com/zaswix/plugins")
        }
        if (stored.size != cleaned.size) {
            sharedPrefs.edit().putStringSet("repo_urls", cleaned.toSet()).apply()
        }
        return cleaned
    }

    private fun loadCachedRepoPlugins(): List<PluginInfo> {
        val json = sharedPrefs.getString("cached_repo_plugins", null) ?: return emptyList()
        return try {
            val listType = object : com.google.gson.reflect.TypeToken<List<PluginInfo>>() {}.type
            val list: List<PluginInfo> = gson.fromJson(json, listType) ?: emptyList()
            list.filter {
                !it.downloadUrl.contains("raw.githubusercontent.com/zaswix/plugins") &&
                it.id !in setOf("toonstream", "multimovies", "bollyflix", "vegamovies")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveCachedRepoPlugins(plugins: List<PluginInfo>) {
        try {
            val json = gson.toJson(plugins)
            sharedPrefs.edit().putString("cached_repo_plugins", json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    init {
        setApplicationContext(context)
        loadInstalledApkExtensions()
        refreshPluginCatalog()
    }

    /**
     * Loads any previously installed APK extensions from context.getDir("plugins", Context.MODE_PRIVATE)
     */
    private fun loadInstalledApkExtensions() {
        val pluginsDir = context.getDir("plugins", Context.MODE_PRIVATE)
        if (pluginsDir.exists() && pluginsDir.isDirectory) {
            val apkFiles = pluginsDir.listFiles { _, name -> name.endsWith(".apk", ignoreCase = true) }
            apkFiles?.forEach { apkFile ->
                val metaFile = File(pluginsDir, apkFile.nameWithoutExtension + ".json")
                if (metaFile.exists()) {
                    try {
                        val metaJson = metaFile.readText()
                        val json = JSONObject(metaJson)
                        val className = json.optString("className")
                        val pluginId = json.optString("id", apkFile.nameWithoutExtension)
                        if (className.isNotEmpty()) {
                            val loaded = loadExtension(apkFile.absolutePath, className)
                            if (loaded != null) {
                                val currentSet = _installedPluginIds.value.toMutableSet()
                                currentSet.add(pluginId)
                                _installedPluginIds.value = currentSet
                                sharedPrefs.edit().putStringSet("installed_plugins", currentSet).apply()
                            }
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

    /**
     * Downloads an APK from GitHub release URL and dynamically loads it via DexClassLoader.
     */
    suspend fun downloadAndInstallPlugin(
        pluginInfo: PluginInfo,
        onProgress: (Float) -> Unit = {}
    ): Result<ZaswixPlugin> = withContext(Dispatchers.IO) {
        val downloadUrl = pluginInfo.downloadUrl
        val className = pluginInfo.className
        if (downloadUrl.isBlank() || className.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Plugin download URL or className is missing"))
        }

        val pluginsDir = context.getDir("plugins", Context.MODE_PRIVATE)
        val apkFile = File(pluginsDir, "${pluginInfo.id}.apk")
        val metaFile = File(pluginsDir, "${pluginInfo.id}.json")

        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Failed to download APK: HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty response body"))
            FileOutputStream(apkFile).use { fos ->
                body.byteStream().copyTo(fos)
            }

            val metaObj = JSONObject().apply {
                put("id", pluginInfo.id)
                put("name", pluginInfo.name)
                put("className", className)
                put("version", pluginInfo.version)
                put("downloadUrl", downloadUrl)
            }
            metaFile.writeText(metaObj.toString())

            val instance = loadExtension(apkFile.absolutePath, className)
                ?: return@withContext Result.failure(Exception("DexClassLoader failed to instantiate $className"))

            val currentSet = _installedPluginIds.value.toMutableSet()
            currentSet.add(pluginInfo.id)
            _installedPluginIds.value = currentSet
            sharedPrefs.edit().putStringSet("installed_plugins", currentSet).apply()

            if (_selectedPluginId.value.isBlank()) {
                selectPlugin(pluginInfo.id)
            }

            refreshPluginCatalog()
            Result.success(instance)
        } catch (e: Exception) {
            e.printStackTrace()
            apkFile.delete()
            metaFile.delete()
            Result.failure(e)
        }
    }

    fun uninstallPlugin(pluginId: String) {
        val pluginsDir = context.getDir("plugins", Context.MODE_PRIVATE)
        val apkFile = File(pluginsDir, "$pluginId.apk")
        val metaFile = File(pluginsDir, "$pluginId.json")

        apkFile.delete()
        metaFile.delete()

        synchronized(loadedPlugins) {
            val list = loadedPlugins.value.toMutableList()
            list.removeAll {
                it.providerName.equals(pluginId, ignoreCase = true) ||
                it.providerName.lowercase().replace("\\s+".toRegex(), "") == pluginId.lowercase()
            }
            loadedPlugins.value = list
        }

        val currentSet = _installedPluginIds.value.toMutableSet()
        currentSet.remove(pluginId)
        _installedPluginIds.value = currentSet
        sharedPrefs.edit().putStringSet("installed_plugins", currentSet).apply()

        if (_selectedPluginId.value == pluginId) {
            _selectedPluginId.value = currentSet.firstOrNull() ?: ""
            sharedPrefs.edit().putString("active_plugin_id", _selectedPluginId.value).apply()
        }

        refreshPluginCatalog()
    }

    fun togglePlugin(pluginId: String) {
        if (_installedPluginIds.value.contains(pluginId)) {
            uninstallPlugin(pluginId)
        }
    }

    suspend fun addRepository(url: String): Result<Int> = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Repository URL cannot be empty"))
        }
        if (!cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid URL: must start with https:// or http://"))
        }

        _isSyncing.value = true
        try {
            val fetchedPlugins = RepositoryManager.fetchRepositoryPlugins(cleanUrl)
            if (fetchedPlugins.isEmpty()) {
                return@withContext Result.failure(Exception("No valid provider plugins found at repository URL"))
            }

            val currentUrls = _repoUrls.value.toMutableList()
            if (!currentUrls.contains(cleanUrl)) {
                currentUrls.add(cleanUrl)
                _repoUrls.value = currentUrls
                sharedPrefs.edit().putStringSet("repo_urls", currentUrls.toSet()).apply()
            }

            val currentRepoPlugins = _repoPlugins.value.toMutableList()
            for (newP in fetchedPlugins) {
                currentRepoPlugins.removeAll { it.id == newP.id }
                currentRepoPlugins.add(newP)
            }
            _repoPlugins.value = currentRepoPlugins
            saveCachedRepoPlugins(currentRepoPlugins)

            withContext(Dispatchers.Main) {
                refreshPluginCatalog()
            }
            Result.success(fetchedPlugins.size)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isSyncing.value = false
        }
    }

    fun removeRepositoryUrl(url: String) {
        val updated = _repoUrls.value.filter { it != url }
        _repoUrls.value = updated
        sharedPrefs.edit().putStringSet("repo_urls", updated.toSet()).apply()

        if (updated.isEmpty()) {
            _repoPlugins.value = emptyList()
            saveCachedRepoPlugins(emptyList())
            refreshPluginCatalog()
        }
    }

    suspend fun syncRepositories(): Result<Int> = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        try {
            if (_repoUrls.value.isEmpty()) {
                _repoPlugins.value = emptyList()
                saveCachedRepoPlugins(emptyList())
                withContext(Dispatchers.Main) {
                    refreshPluginCatalog()
                }
                return@withContext Result.success(0)
            }

            val allFetched = mutableListOf<PluginInfo>()
            for (repoUrl in _repoUrls.value) {
                val plugins = RepositoryManager.fetchRepositoryPlugins(repoUrl)
                for (p in plugins) {
                    allFetched.removeAll { it.id == p.id }
                    allFetched.add(p)
                }
            }
            _repoPlugins.value = allFetched
            saveCachedRepoPlugins(allFetched)
            withContext(Dispatchers.Main) {
                refreshPluginCatalog()
            }
            Result.success(allFetched.size)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isSyncing.value = false
        }
    }

    fun refreshPluginCatalog() {
        val installedIds = _installedPluginIds.value
        val installedList = mutableListOf<PluginInfo>()
        val availableList = mutableListOf<PluginInfo>()

        // 1. Plugins provided by manually added plugins.json repositories
        for (repoPlugin in _repoPlugins.value) {
            val isInstalled = installedIds.contains(repoPlugin.id)
            val updated = repoPlugin.copy(isInstalled = isInstalled)
            availableList.add(updated)
            if (isInstalled) {
                installedList.add(updated)
            }
        }

        // 2. Dynamically loaded plugins directly present in loadedPlugins
        synchronized(loadedPlugins) {
            loadedPlugins.value.forEach { plugin ->
                val id = plugin.providerName.lowercase().replace("[^a-z0-9]".toRegex(), "")
                val existing = availableList.find { it.id == id }
                if (existing != null) {
                    existing.isInstalled = true
                    if (installedList.none { it.id == id }) {
                        installedList.add(existing)
                    }
                } else {
                    val info = PluginInfo(
                        id = id,
                        name = plugin.providerName,
                        version = plugin.version,
                        siteUrl = "",
                        description = "Dynamically loaded Dex extension provider",
                        category = "External Extension",
                        iconUrl = "",
                        downloadUrl = "",
                        className = plugin.javaClass.name,
                        isEnabled = true,
                        isInstalled = true
                    )
                    installedList.add(info)
                }
            }
        }

        _plugins.value = installedList
        _availablePlugins.value = availableList
    }

    companion object {
        val loadedPlugins = MutableStateFlow<List<ZaswixPlugin>>(emptyList())
        private var appContext: Context? = null

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
            val dexOptDir = if (context != null) {
                File(context.codeCacheDir, "dex").apply { mkdirs() }
            } else {
                File(file.parentFile, "code_cache").apply { mkdirs() }
            }

            return try {
                val parentLoader = context?.classLoader ?: ClassLoader.getSystemClassLoader()
                val dexClassLoader = DexClassLoader(
                    file.absolutePath,
                    dexOptDir.absolutePath,
                    null,
                    parentLoader
                )

                val pluginClass = dexClassLoader.loadClass(className)
                val instance = pluginClass.getDeclaredConstructor().newInstance() as ZaswixPlugin

                synchronized(loadedPlugins) {
                    val currentList = loadedPlugins.value.filter {
                        !it.providerName.equals(instance.providerName, ignoreCase = true)
                    }
                    loadedPlugins.value = currentList + instance
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
