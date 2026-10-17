package com.zaswis.play.plugin

import android.content.Context
import com.zaswis.play.data.model.PluginInfo
import kotlinx.coroutines.Dispatchers
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
 * Remote Repository Fetcher for Zaswix Plugins.
 * Fetches plugins.json, downloads extension APKs to app filesDir,
 * and dynamically activates them via PluginManager.loadExtension().
 */
object RepositoryManager {

    private val client = OkHttpClient.Builder()
        .dns(com.zaswis.play.network.CloudflareDns.dns)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Fetches plugins manifest from a repository URL and parses into PluginInfo objects.
     */
    suspend fun fetchRepositoryPlugins(repoUrl: String): List<PluginInfo> = withContext(Dispatchers.IO) {
        val list = mutableListOf<PluginInfo>()
        try {
            val cleanUrl = repoUrl.trim()
            if (cleanUrl.isBlank()) return@withContext emptyList()

            val request = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val jsonContent = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                response.body?.string().orEmpty()
            }

            if (jsonContent.isBlank()) return@withContext emptyList()

            val trimmed = jsonContent.trim()
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    /**
     * Fetches remote plugins.json, downloads each APK to context.filesDir/plugins,
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
                        // Save metadata alongside the APK for auto-reloading across cold starts
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

    private fun parsePluginsJson(jsonString: String): List<PluginManifestItem> {
        val list = mutableListOf<PluginManifestItem>()
        val trimmed = jsonString.trim()
        val jsonArray = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONObject(trimmed).optJSONArray("plugins") ?: JSONArray()
        }

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val name = obj.optString("name")
            val className = obj.optString("className")
            val url = obj.optString("url")
            val version = obj.optString("version", "1.0.0")
            val description = obj.optString("description", "")

            if (url.isNotEmpty() && className.isNotEmpty()) {
                list.add(PluginManifestItem(name, className, url, version, description))
            }
        }
        return list
    }

    private fun downloadPluginApk(context: Context, downloadUrl: String, className: String): File? {
        val safeName = className.replace(".", "_") + ".apk"
        val pluginsDir = context.getDir("plugins", Context.MODE_PRIVATE)
        val targetFile = File(pluginsDir, safeName)

        val request = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                FileOutputStream(targetFile).use { fos ->
                    body.byteStream().copyTo(fos)
                }
            }
            targetFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
