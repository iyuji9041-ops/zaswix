package com.zaswis.play.plugin

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zaswis.play.data.model.PluginInfo
import com.zaswis.play.extractor.HomePagePayload
import dalvik.system.DexClassLoader
import kotlin.coroutines.intrinsics.intercepted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Cloudstream-style Plugin Manager with Dynamic DexClassLoader Extension Support.
 * The host app is a Legal Empty Shell: all scraper logic is dynamically loaded from
 * external standalone APKs at runtime into loadedPlugins.
 */
class PluginManager(private val context: Context) {

    private val gson = Gson()
    private val sharedPrefs = context.getSharedPreferences("zaswix_prefs", Context.MODE_PRIVATE)

    val repoUrls: StateFlow<List<String>> = RepositoryManager.savedRepoUrls
    val isSyncing: StateFlow<Boolean> = RepositoryManager.isSyncing

    private val _installedPluginIds = MutableStateFlow<Set<String>>(
        sharedPrefs.getStringSet("installed_plugins", emptySet()) ?: emptySet()
    )

    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    private val _availablePlugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val availablePlugins: StateFlow<List<PluginInfo>> = _availablePlugins.asStateFlow()

    private val _selectedPluginId = MutableStateFlow(
        sharedPrefs.getString("active_plugin_id", "") ?: ""
    )
    val selectedPluginId: StateFlow<String> = _selectedPluginId.asStateFlow()

    init {
        setApplicationContext(context)
        RepositoryManager.init(context)

        // Preload built-in providers immediately (Instant 0-delay availability)
        synchronized(loadedPlugins) {
            val existing = loadedPlugins.value
            val combined = (builtInPluginInstances + existing).distinctBy { getSafeProviderName(it).lowercase() }
            loadedPlugins.value = combined
        }

        if (_selectedPluginId.value.isBlank()) {
            _selectedPluginId.value = "multimovies"
            sharedPrefs.edit().putString("active_plugin_id", "multimovies").apply()
        }

        loadInstalledApkExtensions()

        // Observe repository catalog updates from RepositoryManager
        CoroutineScope(Dispatchers.Main).launch {
            RepositoryManager.availablePlugins.collect {
                refreshPluginCatalog()
            }
        }
        refreshPluginCatalog()
    }

    /**
     * Loads any previously installed APK extensions from context.getDir("plugins", Context.MODE_PRIVATE).
     * Enforces Android 14+ read-only requirement on APK files before loading.
     */
    private fun loadInstalledApkExtensions() {
        val pluginsDir = context.getDir("plugins", Context.MODE_PRIVATE)
        if (pluginsDir.exists() && pluginsDir.isDirectory) {
            val apkFiles = pluginsDir.listFiles { _, name -> name.endsWith(".apk", ignoreCase = true) }
            apkFiles?.forEach { apkFile ->
                val metaFile = File(pluginsDir, apkFile.nameWithoutExtension + ".json")
                try {
                    var className = ""
                    var pluginId = apkFile.nameWithoutExtension
                    if (metaFile.exists()) {
                        val metaJson = metaFile.readText()
                        val json = JSONObject(metaJson)
                        className = json.optString("className")
                        pluginId = json.optString("id", apkFile.nameWithoutExtension)
                    }

                    if (className.isNotEmpty()) {
                        // Enforce Android 14+ read-only requirement before loading
                        try {
                            apkFile.setReadOnly()
                        } catch (_: Throwable) {}

                        val loaded = loadExtension(context, apkFile.absolutePath, className)
                        if (loaded != null) {
                            val currentSet = _installedPluginIds.value.toMutableSet()
                            currentSet.add(pluginId)
                            _installedPluginIds.value = currentSet
                            sharedPrefs.edit().putStringSet("installed_plugins", currentSet).apply()
                        } else {
                            Log.e("PluginManager", "Failed to load extension $className from ${apkFile.name}, deleting corrupted APK.")
                            deleteBadPluginApk(apkFile, metaFile, pluginId)
                        }
                    } else {
                        Log.e("PluginManager", "Missing className metadata for ${apkFile.name}, cleaning up.")
                        deleteBadPluginApk(apkFile, metaFile, pluginId)
                    }
                } catch (t: Throwable) {
                    Log.e("PluginManager", "Error loading installed APK extension ${apkFile.name}: ${t.message}", t)
                    deleteBadPluginApk(apkFile, metaFile, apkFile.nameWithoutExtension)
                }
            }
        }
    }

    private fun deleteBadPluginApk(apkFile: File, metaFile: File?, pluginId: String) {
        try {
            Log.e("PluginManager", "Deleting defective plugin APK: ${apkFile.absolutePath} (id: $pluginId)")
            try {
                apkFile.setWritable(true)
                apkFile.delete()
            } catch (t: Throwable) {
                Log.e("PluginManager", "Could not delete APK file: ${apkFile.name}", t)
            }
            try {
                metaFile?.delete()
            } catch (t: Throwable) {
                Log.e("PluginManager", "Could not delete meta file: ${metaFile?.name}", t)
            }

            val currentSet = _installedPluginIds.value.toMutableSet()
            currentSet.remove(pluginId)
            _installedPluginIds.value = currentSet
            sharedPrefs.edit().putStringSet("installed_plugins", currentSet).apply()

            val list = getInstalledPluginMetadata().filter { it.id != pluginId }
            sharedPrefs.edit().putString("installed_plugins_metadata", gson.toJson(list)).apply()

            synchronized(loadedPlugins) {
                val updated = loadedPlugins.value.filter {
                    val pName = getSafeProviderName(it)
                    !pName.equals(pluginId, ignoreCase = true) &&
                    pName.lowercase().replace("[^a-z0-9]".toRegex(), "") != pluginId.lowercase()
                }
                loadedPlugins.value = updated
            }
        } catch (t: Throwable) {
            Log.e("PluginManager", "Failed while cleaning up defective plugin: ${t.message}", t)
        }
    }

    private fun deletePluginFilesForInstance(rawPlugin: Any) {
        try {
            if (rawPlugin.javaClass.name.startsWith("com.zaswis.play.provider.builtin")) return
            val pluginsDir = context.getDir("plugins", Context.MODE_PRIVATE)
            if (!pluginsDir.exists() || !pluginsDir.isDirectory) return

            val targetClassName = rawPlugin.javaClass.name
            val metaFiles = pluginsDir.listFiles { _, name -> name.endsWith(".json", ignoreCase = true) }
            var found = false
            metaFiles?.forEach { metaFile ->
                try {
                    val metaJson = metaFile.readText()
                    val json = JSONObject(metaJson)
                    val className = json.optString("className")
                    val id = json.optString("id", metaFile.nameWithoutExtension)
                    if (className == targetClassName) {
                        found = true
                        val apkFile = File(pluginsDir, metaFile.nameWithoutExtension + ".apk")
                        deleteBadPluginApk(apkFile, metaFile, id)
                    }
                } catch (t: Throwable) {
                    Log.e("PluginManager", "Error reading metadata: ${metaFile.name}", t)
                }
            }

            if (!found) {
                val simpleName = rawPlugin.javaClass.simpleName.lowercase()
                val apkFiles = pluginsDir.listFiles { _, name -> name.endsWith(".apk", ignoreCase = true) }
                apkFiles?.forEach { apkFile ->
                    if (apkFile.nameWithoutExtension.lowercase().contains(simpleName) ||
                        simpleName.contains(apkFile.nameWithoutExtension.lowercase())) {
                        val metaFile = File(pluginsDir, apkFile.nameWithoutExtension + ".json")
                        deleteBadPluginApk(apkFile, metaFile, apkFile.nameWithoutExtension)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("PluginManager", "Failed in deletePluginFilesForInstance: ${t.message}", t)
        }
    }

    fun selectPlugin(pluginId: String) {
        _selectedPluginId.value = pluginId
        sharedPrefs.edit().putString("active_plugin_id", pluginId).apply()
    }

    /**
     * Downloads an APK from release URL with automatic DNS fallback and dynamically loads it via DexClassLoader.
     * Enforces Android 14+ read-only mode on the APK before loading.
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
        val tempFile = File(pluginsDir, "${pluginInfo.id}.apk.tmp")
        val metaFile = File(pluginsDir, "${pluginInfo.id}.json")

        try {
            // Download with automatic DNS fallback for GitHub CDN
            val downloaded = RepositoryManager.downloadWithDnsFallback(downloadUrl, tempFile)
            if (!downloaded || !tempFile.exists() || tempFile.length() == 0L) {
                tempFile.delete()
                return@withContext Result.failure(Exception("Failed to download APK: Network connection failed or timed out"))
            }

            if (apkFile.exists()) {
                apkFile.setWritable(true)
                apkFile.delete()
            }
            if (!tempFile.renameTo(apkFile)) {
                tempFile.delete()
                return@withContext Result.failure(Exception("Failed to save downloaded plugin APK file"))
            }

            // Android 14+ Requirement: dynamically loaded code files must be read-only
            apkFile.setReadOnly()

            // Save metadata
            val metaObj = JSONObject().apply {
                put("id", pluginInfo.id)
                put("name", pluginInfo.name)
                put("className", className)
                put("version", pluginInfo.version)
                put("downloadUrl", downloadUrl)
            }
            metaFile.writeText(metaObj.toString())

            // Persist to SharedPreferences
            saveInstalledPluginMetadata(pluginInfo)

            val instance = loadExtension(context, apkFile.absolutePath, className)
                ?: return@withContext Result.failure(Exception("DexClassLoader failed to instantiate $className"))

            val currentSet = _installedPluginIds.value.toMutableSet()
            currentSet.add(pluginInfo.id)
            _installedPluginIds.value = currentSet
            sharedPrefs.edit().putStringSet("installed_plugins", currentSet).apply()

            if (_selectedPluginId.value.isBlank()) {
                selectPlugin(pluginInfo.id)
            }

            withContext(Dispatchers.Main) {
                refreshPluginCatalog()
            }
            Result.success(instance)
        } catch (t: Throwable) {
            Log.e("PluginManager", "Failed to download and install plugin ${pluginInfo.name}: ${t.message}", t)
            try {
                apkFile.setWritable(true)
                apkFile.delete()
            } catch (_: Throwable) {}
            tempFile.delete()
            metaFile.delete()
            Result.failure(if (t is Exception) t else Exception(t.message, t))
        }
    }

    private fun saveInstalledPluginMetadata(info: PluginInfo) {
        try {
            val list = getInstalledPluginMetadata().toMutableList()
            list.removeAll { it.id == info.id }
            list.add(info)
            sharedPrefs.edit().putString("installed_plugins_metadata", gson.toJson(list)).apply()
        } catch (t: Throwable) {
            Log.e("PluginManager", "Error saving plugin metadata: ${t.message}", t)
        }
    }

    private fun getInstalledPluginMetadata(): List<PluginInfo> {
        val json = sharedPrefs.getString("installed_plugins_metadata", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<PluginInfo>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun uninstallPlugin(pluginId: String) {
        val pluginsDir = context.getDir("plugins", Context.MODE_PRIVATE)
        val apkFile = File(pluginsDir, "$pluginId.apk")
        val metaFile = File(pluginsDir, "$pluginId.json")

        try {
            apkFile.setWritable(true)
            apkFile.delete()
        } catch (_: Throwable) {}
        metaFile.delete()

        // Update SharedPreferences metadata
        try {
            val list = getInstalledPluginMetadata().filter { it.id != pluginId }
            sharedPrefs.edit().putString("installed_plugins_metadata", gson.toJson(list)).apply()
        } catch (_: Throwable) {}

        synchronized(loadedPlugins) {
            val list = loadedPlugins.value.toMutableList()
            list.removeAll {
                val pName = getSafeProviderName(it)
                pName.equals(pluginId, ignoreCase = true) ||
                pName.lowercase().replace("\\s+".toRegex(), "") == pluginId.lowercase()
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
        val res = RepositoryManager.addRepository(context, url)
        if (res.isSuccess) {
            withContext(Dispatchers.Main) {
                refreshPluginCatalog()
            }
        }
        res
    }

    fun removeRepositoryUrl(url: String) {
        RepositoryManager.removeRepository(context, url)
        refreshPluginCatalog()
    }

    suspend fun syncRepositories(): Result<Int> = withContext(Dispatchers.IO) {
        val synced = RepositoryManager.syncAll(context)
        withContext(Dispatchers.Main) {
            refreshPluginCatalog()
        }
        Result.success(synced.size)
    }

    fun refreshPluginCatalog() {
        try {
            val installedIds = _installedPluginIds.value
            val installedList = mutableListOf<PluginInfo>()
            val availableList = mutableListOf<PluginInfo>()

            // 1. Plugins provided by dynamically fetched repositories from RepositoryManager
            val repoPlugins = RepositoryManager.availablePlugins.value
            for (repoPlugin in repoPlugins) {
                val isInstalled = installedIds.contains(repoPlugin.id)
                val updated = repoPlugin.copy(isInstalled = isInstalled)
                availableList.add(updated)
                if (isInstalled) {
                    installedList.add(updated)
                }
            }

            // 2. Also check any plugins present in local metadata
            val savedMeta = getInstalledPluginMetadata()
            for (meta in savedMeta) {
                if (installedIds.contains(meta.id) && installedList.none { it.id == meta.id }) {
                    val copy = meta.copy(isInstalled = true)
                    installedList.add(copy)
                    if (availableList.none { it.id == meta.id }) {
                        availableList.add(copy)
                    }
                }
            }

            // 3. Dynamically loaded plugins directly present in loadedPlugins
            synchronized(loadedPlugins) {
                val validPlugins = mutableListOf<ZaswixPlugin>()
                val pluginsToDelete = mutableListOf<Pair<ZaswixPlugin, Throwable>>()

                for (rawPlugin in loadedPlugins.value) {
                    try {
                        // Check if rawPlugin throws AbstractMethodError on providerName; if so, wrap with ReflectionPluginAdapter
                        val plugin: ZaswixPlugin = try {
                            rawPlugin.providerName
                            rawPlugin
                        } catch (t: Throwable) {
                            ReflectionPluginAdapter(rawPlugin)
                        }

                        val providerName = getSafeProviderName(plugin)
                        val id = providerName.lowercase().replace("[^a-z0-9]".toRegex(), "")
                        val existing = availableList.find { it.id == id }
                        if (existing != null) {
                            existing.isInstalled = true
                            if (installedList.none { it.id == id }) {
                                installedList.add(existing)
                            }
                        } else {
                            val versionStr = try { plugin.version } catch (_: Throwable) { "1.0.0" }
                            val isBuiltin = plugin.javaClass.name.startsWith("com.zaswis.play.provider.builtin")
                            val info = PluginInfo(
                                id = id,
                                name = providerName,
                                version = versionStr,
                                siteUrl = "",
                                description = if (isBuiltin) "Built-in Official Provider (Instant Zero-Delay)" else "Dynamically loaded Dex extension provider",
                                category = if (isBuiltin) "Built-in Provider" else "External Extension",
                                iconUrl = "",
                                downloadUrl = "",
                                className = plugin.javaClass.name,
                                isEnabled = true,
                                isInstalled = true
                            )
                            installedList.add(info)
                            if (availableList.none { it.id == id }) {
                                availableList.add(info)
                            }
                        }
                        validPlugins.add(plugin)
                    } catch (t: Throwable) {
                        Log.e("PluginManager", "Error processing plugin in refreshPluginCatalog: ${t.message}", t)
                        pluginsToDelete.add(rawPlugin to t)
                    }
                }

                loadedPlugins.value = validPlugins

                // Clean up bad plugin files for any plugins that threw fatal errors
                for ((badPlugin, error) in pluginsToDelete) {
                    Log.e("PluginManager", "Deleting defective plugin APK due to error: ${error.message}")
                    deletePluginFilesForInstance(badPlugin)
                }
            }

            _plugins.value = installedList
            _availablePlugins.value = availableList
        } catch (t: Throwable) {
            Log.e("PluginManager", "Fatal error prevented in refreshPluginCatalog: ${t.message}", t)
        }
    }

    companion object {
        val builtInPluginInstances: List<ZaswixPlugin> by lazy {
            listOf(
                com.zaswis.play.provider.builtin.MultiMoviesProvider(),
                com.zaswis.play.provider.builtin.ToonStreamProvider(),
                com.zaswis.play.provider.builtin.VegaMoviesProvider(),
                com.zaswis.play.provider.builtin.HiAnimeProvider()
            )
        }

        val loadedPlugins = MutableStateFlow<List<ZaswixPlugin>>(emptyList())
        private var appContext: Context? = null

        fun setApplicationContext(context: Context) {
            appContext = context.applicationContext
        }

        fun getSafeProviderName(plugin: ZaswixPlugin): String {
            return try {
                plugin.providerName
            } catch (t: Throwable) {
                try {
                    val cls = plugin.javaClass
                    var name: String? = null
                    try {
                        val m = cls.methods.firstOrNull { it.name == "getProviderName" && it.parameterCount == 0 }
                        name = m?.invoke(plugin) as? String
                    } catch (_: Throwable) {}

                    if (name.isNullOrBlank()) {
                        try {
                            val m = cls.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                            name = m?.invoke(plugin) as? String
                        } catch (_: Throwable) {}
                    }

                    if (name.isNullOrBlank()) {
                        try {
                            name = (cls.fields.firstOrNull { it.name == "providerName" }?.get(plugin) as? String)
                                ?: (cls.fields.firstOrNull { it.name == "name" }?.get(plugin) as? String)
                        } catch (_: Throwable) {}
                    }

                    name?.takeIf { it.isNotBlank() } ?: cls.simpleName
                } catch (_: Throwable) {
                    plugin.javaClass.simpleName
                }
            }
        }

        /**
         * Loads a standalone extension APK dynamically using DexClassLoader.
         * Enforces Android 14+ read-only requirement on the APK file.
         * Uses ReflectionPluginAdapter if the loaded instance belongs to a different classloader
         * or lacks getProviderName(), completely avoiding ClassCastException and AbstractMethodError.
         */
        fun loadExtension(apkPath: String, className: String): ZaswixPlugin? {
            val file = File(apkPath)
            if (!file.exists()) return null

            // Android 14+ (API 34+) enforces that dynamically loaded code must be read-only
            try {
                file.setReadOnly()
            } catch (t: Throwable) {
                t.printStackTrace()
            }

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
                val rawInstance = try {
                    pluginClass.getDeclaredConstructor().newInstance()
                } catch (e: NoSuchMethodException) {
                    if (context != null) {
                        pluginClass.getDeclaredConstructor(Context::class.java).newInstance(context)
                    } else {
                        throw e
                    }
                }

                // Adapter pattern to eliminate ClassCastException and AbstractMethodError across different ClassLoaders
                val instance: ZaswixPlugin = try {
                    if (rawInstance is ZaswixPlugin) {
                        try {
                            rawInstance.providerName
                            rawInstance
                        } catch (t: Throwable) {
                            ReflectionPluginAdapter(rawInstance)
                        }
                    } else {
                        ReflectionPluginAdapter(rawInstance)
                    }
                } catch (t: Throwable) {
                    ReflectionPluginAdapter(rawInstance)
                }

                synchronized(loadedPlugins) {
                    val pName = getSafeProviderName(instance)
                    val currentList = loadedPlugins.value.filter {
                        !getSafeProviderName(it).equals(pName, ignoreCase = true)
                    }
                    loadedPlugins.value = currentList + instance
                }
                instance
            } catch (t: Throwable) {
                Log.e("PluginManager", "DexClassLoader failed to load $className from $apkPath: ${t.message}", t)
                try {
                    file.setWritable(true)
                    file.delete()
                    val metaFile = File(file.parentFile, file.nameWithoutExtension + ".json")
                    metaFile.delete()
                } catch (_: Throwable) {}
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
 * Dynamic reflection adapter for plugins loaded via DexClassLoader that might have been
 * compiled against their own version of ZaswixPlugin or from a different classloader.
 * Guarantees zero ClassCastException and handles both suspend and blocking method invocations.
 * Seamlessly resolves either getProviderName() or getName() via reflection fallback.
 */
class ReflectionPluginAdapter(private val target: Any) : ZaswixPlugin {

    private val targetClass = target.javaClass
    private val gson = Gson()

    override val name: String
        get() = providerName

    override val providerName: String
        get() = try {
            var name: String? = null

            // 1. Try getProviderName() via reflection
            try {
                val m = targetClass.methods.firstOrNull { it.name == "getProviderName" && it.parameterCount == 0 }
                name = m?.invoke(target) as? String
            } catch (_: Throwable) {}

            // 2. Fallback to getName() via reflection
            if (name.isNullOrBlank()) {
                try {
                    val m = targetClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                    name = m?.invoke(target) as? String
                } catch (_: Throwable) {}
            }

            // 3. Fallback to field providerName
            if (name.isNullOrBlank()) {
                try {
                    val f = targetClass.fields.firstOrNull { it.name == "providerName" }
                    name = f?.get(target) as? String
                } catch (_: Throwable) {}
            }

            // 4. Fallback to field name
            if (name.isNullOrBlank()) {
                try {
                    val f = targetClass.fields.firstOrNull { it.name == "name" }
                    name = f?.get(target) as? String
                } catch (_: Throwable) {}
            }

            name?.takeIf { it.isNotBlank() } ?: targetClass.simpleName
        } catch (t: Throwable) {
            targetClass.simpleName
        }

    override val version: String
        get() = try {
            var ver: String? = null
            try {
                val m = targetClass.methods.firstOrNull { it.name == "getVersion" && it.parameterCount == 0 }
                ver = m?.invoke(target) as? String
            } catch (_: Throwable) {}

            if (ver.isNullOrBlank()) {
                try {
                    val f = targetClass.fields.firstOrNull { it.name == "version" }
                    ver = f?.get(target) as? String
                } catch (_: Throwable) {}
            }

            ver?.takeIf { it.isNotBlank() } ?: "1.0.0"
        } catch (t: Throwable) {
            "1.0.0"
        }

    override suspend fun getHomePage(): HomePagePayload = withContext(Dispatchers.IO) {
        try {
            if (target is ZaswixPlugin) {
                try {
                    val directResult = target.getHomePage()
                    if (directResult.heroBannerItems.isNotEmpty() || directResult.categoryRows.isNotEmpty()) {
                        return@withContext directResult
                    }
                } catch (t: Throwable) {
                    Log.w("ReflectionAdapter", "Direct getHomePage call failed, falling back to reflection: ${t.message}")
                }
            }

            val method = targetClass.methods.firstOrNull { it.name == "getHomePage" }
            if (method != null) {
                val rawResult = invokeMethod(target, method)
                if (rawResult is HomePagePayload) return@withContext rawResult
                if (rawResult != null) {
                    val json = gson.toJson(rawResult)
                    val parsed = gson.fromJson(json, HomePagePayload::class.java)
                    if (parsed != null && (parsed.heroBannerItems.isNotEmpty() || parsed.categoryRows.isNotEmpty())) {
                        return@withContext parsed
                    }
                }
            }

            HomePagePayload()
        } catch (t: Throwable) {
            Log.e("ReflectionAdapter", "getHomePage failed for ${targetClass.name}: ${t.message}", t)
            HomePagePayload()
        }
    }

    override suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        try {
            if (target is ZaswixPlugin) {
                try {
                    return@withContext target.search(query)
                } catch (t: Throwable) {
                    Log.w("ReflectionAdapter", "Direct search failed, falling back to reflection: ${t.message}")
                }
            }

            val method = targetClass.methods.firstOrNull {
                it.name == "search" && it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == String::class.java
            } ?: return@withContext emptyList()

            val rawResult = invokeMethod(target, method, query)
            if (rawResult is List<*>) {
                if (rawResult.isEmpty()) return@withContext emptyList()
                if (rawResult.firstOrNull() is MediaItem) {
                    @Suppress("UNCHECKED_CAST")
                    return@withContext rawResult as List<MediaItem>
                }
                val json = gson.toJson(rawResult)
                val listType = object : TypeToken<List<MediaItem>>() {}.type
                return@withContext gson.fromJson(json, listType) ?: emptyList()
            }
            emptyList()
        } catch (t: Throwable) {
            Log.e("ReflectionAdapter", "search failed for ${targetClass.name}: ${t.message}", t)
            emptyList()
        }
    }

    override suspend fun getDetails(url: String): MediaDetails = withContext(Dispatchers.IO) {
        try {
            if (target is ZaswixPlugin) {
                try {
                    return@withContext target.getDetails(url)
                } catch (t: Throwable) {
                    Log.w("ReflectionAdapter", "Direct getDetails failed, falling back to reflection: ${t.message}")
                }
            }

            val method = targetClass.methods.firstOrNull {
                it.name == "getDetails" && it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == String::class.java
            } ?: return@withContext MediaDetails(title = "", synopsis = "", rating = "")

            val rawResult = invokeMethod(target, method, url)
            if (rawResult is MediaDetails) return@withContext rawResult
            if (rawResult != null) {
                val json = gson.toJson(rawResult)
                return@withContext gson.fromJson(json, MediaDetails::class.java)
            }
            MediaDetails(title = "", synopsis = "", rating = "")
        } catch (t: Throwable) {
            Log.e("ReflectionAdapter", "getDetails failed for ${targetClass.name}: ${t.message}", t)
            MediaDetails(title = "", synopsis = "", rating = "")
        }
    }

    private suspend fun invokeMethod(target: Any, method: java.lang.reflect.Method, vararg args: Any?): Any? {
        val paramTypes = method.parameterTypes
        val isSuspend = paramTypes.isNotEmpty() && kotlin.coroutines.Continuation::class.java.isAssignableFrom(paramTypes.last())
        return if (isSuspend) {
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { continuation ->
                val fullArgs = arrayOf(*args, continuation.intercepted())
                try {
                    method.invoke(target, *fullArgs)
                } catch (ite: java.lang.reflect.InvocationTargetException) {
                    throw ite.targetException ?: ite
                }
            }
        } else {
            try {
                method.invoke(target, *args)
            } catch (ite: java.lang.reflect.InvocationTargetException) {
                throw ite.targetException ?: ite
            }
        }
    }
}
