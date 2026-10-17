package com.zaswis.play.plugin

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zaswis.play.data.model.PluginInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class PluginManifestItem(
    val name: String,
    val className: String,
    val url: String,
    val version: String = "1.0.0",
    val description: String = ""
)

/**
 * Remote Repository Manager for Zaswix Plugins.
 * Manages repository endpoints, fetches plugins.json catalogs with Cloudflare DoH & normal DNS fallback,
 * persists user repositories into "zaswix_prefs", and downloads plugin APKs.
 */
object RepositoryManager {

    private const val PREFS_NAME = "zaswix_prefs"
    private const val KEY_REPO_URLS = "saved_repo_urls"
    private const val KEY_CACHED_PLUGINS = "cached_available_plugins"

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val gson = Gson()

    // 1. Client with Cloudflare DNS
    val clientWithDns: OkHttpClient = OkHttpClient.Builder()
        .dns(com.zaswis.play.network.CloudflareDns.dns)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .build()
            chain.proceed(request)
        }
        .build()

    // 2. Normal Client (fallback without custom DNS to prevent connection aborts on GitHub CDN)
    val normalClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .build()
            chain.proceed(request)
        }
        .build()

    private val _availablePlugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val availablePlugins: StateFlow<List<PluginInfo>> = _availablePlugins.asStateFlow()

    private val _savedRepoUrls = MutableStateFlow<List<String>>(emptyList())
    val savedRepoUrls: StateFlow<List<String>> = _savedRepoUrls.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private var isInitialized = false

    /**
     * Initializes RepositoryManager with persistent storage and auto-syncs saved repositories on startup.
     */
    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        val urls = getSavedRepoUrls(context)
        _savedRepoUrls.value = urls
        _availablePlugins.value = getCachedPlugins(context)

        // Automatically sync all repositories on launch
        if (urls.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                syncAll(context)
            }
        }
    }

    fun getSavedRepoUrls(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val legacyPrefs = context.getSharedPreferences("zaswix_plugins_v6", Context.MODE_PRIVATE)

        val set = prefs.getStringSet(KEY_REPO_URLS, null)
            ?: legacyPrefs.getStringSet("repo_urls", emptySet())
            ?: emptySet()

        return set.filter {
            it.isNotBlank() && !it.contains("raw.githubusercontent.com/zaswix/plugins")
        }
    }

    fun getCachedPlugins(context: Context): List<PluginInfo> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CACHED_PLUGINS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<PluginInfo>>() {}.type
            val list: List<PluginInfo> = gson.fromJson(json, type) ?: emptyList()
            list.filter {
                !it.downloadUrl.contains("raw.githubusercontent.com/zaswix/plugins") &&
                it.id !in setOf("toonstream", "multimovies", "bollyflix", "vegamovies")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedPlugins(context: Context, plugins: List<PluginInfo>) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_CACHED_PLUGINS, gson.toJson(plugins)).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Adds a repository URL, saves permanently into "zaswix_prefs", and fetches extensions.
     */
    suspend fun addRepository(context: Context, url: String): Result<Int> = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Repository URL cannot be blank"))
        }
        if (!cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext Result.failure(IllegalArgumentException("URL must start with http:// or https://"))
        }

        _isSyncing.value = true
        try {
            val fetched = fetchRepositoryPlugins(cleanUrl)
            if (fetched.isEmpty()) {
                return@withContext Result.failure(Exception("No valid plugins found at repository URL or connection failed."))
            }

            val current = getSavedRepoUrls(context).toMutableList()
            if (!current.contains(cleanUrl)) {
                current.add(cleanUrl)
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putStringSet(KEY_REPO_URLS, current.toSet()).apply()
                _savedRepoUrls.value = current
            }

            val merged = _availablePlugins.value.toMutableList()
            for (p in fetched) {
                merged.removeAll { it.id == p.id }
                merged.add(p)
            }
            _availablePlugins.value = merged
            saveCachedPlugins(context, merged)

            Result.success(fetched.size)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Removes a repository URL permanently from "zaswix_prefs".
     */
    fun removeRepository(context: Context, url: String) {
        val cleanUrl = url.trim()
        val current = getSavedRepoUrls(context).filter { it != cleanUrl }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_REPO_URLS, current.toSet()).apply()
        _savedRepoUrls.value = current

        if (current.isEmpty()) {
            _availablePlugins.value = emptyList()
            saveCachedPlugins(context, emptyList())
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                syncAll(context)
            }
        }
    }

    /**
     * Automatically reads saved repository URLs from "zaswix_prefs" and syncs all provider extensions.
     */
    suspend fun syncAll(context: Context): List<PluginInfo> = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        try {
            val urls = getSavedRepoUrls(context)
            _savedRepoUrls.value = urls
            if (urls.isEmpty()) {
                _availablePlugins.value = emptyList()
                saveCachedPlugins(context, emptyList())
                return@withContext emptyList()
            }

            val allFetched = mutableListOf<PluginInfo>()
            for (url in urls) {
                val plugins = fetchRepositoryPlugins(url)
                for (p in plugins) {
                    allFetched.removeAll { it.id == p.id }
                    allFetched.add(p)
                }
            }
            _availablePlugins.value = allFetched
            saveCachedPlugins(context, allFetched)
            allFetched
        } catch (e: Exception) {
            e.printStackTrace()
            _availablePlugins.value
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Fetches plugins manifest from a repository URL and parses into PluginInfo objects.
     * Retries with normal client if Cloudflare DoH aborts on GitHub.
     */
    suspend fun fetchRepositoryPlugins(repoUrl: String): List<PluginInfo> = withContext(Dispatchers.IO) {
        val cleanUrl = repoUrl.trim()
        if (cleanUrl.isBlank()) return@withContext emptyList()

        val jsonContent = fetchJsonWithDnsFallback(cleanUrl)
        if (jsonContent.isNullOrBlank()) return@withContext emptyList()

        parsePluginsManifest(jsonContent)
    }

    private fun fetchJsonWithDnsFallback(url: String): String? {
        val isGitHub = url.contains("githubusercontent.com", ignoreCase = true) || url.contains("github.com", ignoreCase = true)

        // 1. Try with Cloudflare DNS first unless known GitHub CDN issue
        if (!isGitHub) {
            try {
                val res = executeGetJson(clientWithDns, url)
                if (!res.isNullOrBlank()) return res
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Fallback to normal client without custom DNS
        return try {
            executeGetJson(normalClient, url)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun executeGetJson(client: OkHttpClient, url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null
            else response.body?.string()
        }
    }

    private fun parsePluginsManifest(jsonString: String): List<PluginInfo> {
        val list = mutableListOf<PluginInfo>()
        val trimmed = jsonString.trim()
        val jsonArray = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val root = JSONObject(trimmed)
            root.optJSONArray("plugins") ?: JSONArray()
        }

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            val name = obj.optString("name", "").trim()
            val className = obj.optString("className", "").trim()
            val downloadUrl = (obj.optString("downloadUrl", "").ifBlank { obj.optString("url", "") }).trim()
            val id = obj.optString("id", "").ifBlank {
                name.lowercase().replace("[^a-z0-9]".toRegex(), "")
            }.trim()
            val version = obj.optString("version", "1.0.0").trim()
            val description = obj.optString("description", "").trim()
            val iconUrl = obj.optString("iconUrl", "").trim()
            val category = obj.optString("category", "General").trim()
            val siteUrl = obj.optString("siteUrl", "").trim()

            if (name.isNotEmpty() && className.isNotEmpty() && downloadUrl.isNotEmpty()) {
                list.add(
                    PluginInfo(
                        id = id,
                        name = name,
                        version = version,
                        siteUrl = siteUrl,
                        description = description,
                        iconUrl = iconUrl,
                        category = category,
                        downloadUrl = downloadUrl,
                        className = className,
                        isEnabled = true,
                        isInstalled = false
                    )
                )
            }
        }
        return list
    }

    /**
     * Downloads an APK to app's private "plugins" directory using automatic DNS fallback.
     * Enforces Android 14+ read-only requirement on the resulting file.
     */
    suspend fun downloadPluginApk(context: Context, downloadUrl: String, className: String): File? = withContext(Dispatchers.IO) {
        val safeName = className.replace(".", "_") + ".apk"
        val pluginsDir = context.getDir("plugins", Context.MODE_PRIVATE)
        val targetFile = File(pluginsDir, safeName)
        val tempFile = File(pluginsDir, "$safeName.tmp")

        val success = downloadWithDnsFallback(downloadUrl, tempFile)
        if (success && tempFile.exists() && tempFile.length() > 0) {
            if (targetFile.exists()) {
                targetFile.setWritable(true)
                targetFile.delete()
            }
            if (tempFile.renameTo(targetFile)) {
                // Enforce Android 14+ requirement: loaded dex files must be read-only
                targetFile.setReadOnly()
                return@withContext targetFile
            }
        }
        tempFile.delete()
        null
    }

    /**
     * Downloads an APK using Cloudflare DoH, automatically falling back to normal OkHttpClient
     * if the connection aborts on GitHub CDN.
     */
    suspend fun downloadWithDnsFallback(url: String, destination: File): Boolean = withContext(Dispatchers.IO) {
        val isGitHub = url.contains("githubusercontent.com", ignoreCase = true) || url.contains("github.com", ignoreCase = true)
        var downloaded = false

        // 1. Try with Cloudflare DNS first unless it's GitHub CDN
        if (!isGitHub) {
            try {
                downloaded = executeDownload(clientWithDns, url, destination)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Fallback to normal OkHttpClient (without custom DNS)
        if (!downloaded) {
            try {
                downloaded = executeDownload(normalClient, url, destination)
            } catch (e: Exception) {
                e.printStackTrace()
                // Retry once more with short delay on transient network aborts
                try {
                    kotlinx.coroutines.delay(1000)
                    downloaded = executeDownload(normalClient, url, destination)
                } catch (retryEx: Exception) {
                    retryEx.printStackTrace()
                }
            }
        }
        downloaded
    }

    private fun executeDownload(client: OkHttpClient, url: String, destination: File): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false

            destination.parentFile?.mkdirs()
            if (destination.exists()) {
                destination.setWritable(true)
                destination.delete()
            }

            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }
            return destination.exists() && destination.length() > 0
        }
    }

    /**
     * Fetches remote plugins.json, downloads each APK to context.getDir("plugins"),
     * and passes each to PluginManager.loadExtension().
     */
    suspend fun fetchAndInstallPlugins(context: Context, repoUrl: String): List<ZaswixPlugin> = withContext(Dispatchers.IO) {
        val loadedList = mutableListOf<ZaswixPlugin>()
        try {
            val items = fetchRepositoryPlugins(repoUrl)

            for (item in items) {
                if (item.downloadUrl.isNotBlank() && item.className.isNotBlank()) {
                    val downloadedApk = downloadPluginApk(context, item.downloadUrl, item.className)
                    if (downloadedApk != null && downloadedApk.exists()) {
                        val metaFile = File(downloadedApk.parentFile, downloadedApk.nameWithoutExtension + ".json")
                        val metaObj = JSONObject().apply {
                            put("id", item.id)
                            put("name", item.name)
                            put("className", item.className)
                            put("version", item.version)
                            put("downloadUrl", item.downloadUrl)
                        }
                        metaFile.writeText(metaObj.toString())

                        val plugin = PluginManager.loadExtension(context, downloadedApk.absolutePath, item.className)
                        if (plugin != null) {
                            loadedList.add(plugin)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        loadedList
    }

    /**
     * Downloads and installs a single plugin APK from a direct URL.
     */
    suspend fun downloadAndInstallSinglePlugin(
        context: Context,
        name: String,
        className: String,
        apkUrl: String
    ): ZaswixPlugin? = withContext(Dispatchers.IO) {
        try {
            val apkFile = downloadPluginApk(context, apkUrl, className) ?: return@withContext null
            val metaFile = File(apkFile.parentFile, apkFile.nameWithoutExtension + ".json")
            val metaObj = JSONObject().apply {
                put("name", name)
                put("className", className)
            }
            metaFile.writeText(metaObj.toString())

            PluginManager.loadExtension(context, apkFile.absolutePath, className)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
