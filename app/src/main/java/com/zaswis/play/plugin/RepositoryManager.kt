package com.zaswis.play.plugin

import android.content.Context
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
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Fetches remote plugins.json, downloads each APK to context.filesDir/plugins,
     * and passes each to PluginManager.loadExtension().
     */
    suspend fun fetchAndInstallPlugins(context: Context, repoUrl: String): List<ZaswixPlugin> = withContext(Dispatchers.IO) {
        val loadedList = mutableListOf<ZaswixPlugin>()
        try {
            val request = Request.Builder()
                .url(repoUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val jsonContent = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                response.body?.string().orEmpty()
            }

            if (jsonContent.isBlank()) return@withContext emptyList()

            val items = parsePluginsJson(jsonContent)

            for (item in items) {
                if (item.url.isNotBlank() && item.className.isNotBlank()) {
                    val downloadedApk = downloadPluginApk(context, item.url, item.className)
                    if (downloadedApk != null && downloadedApk.exists()) {
                        // Save metadata alongside the APK for auto-reloading across cold starts
                        val metaFile = File(downloadedApk.parentFile, downloadedApk.nameWithoutExtension + ".json")
                        val metaObj = JSONObject().apply {
                            put("name", item.name)
                            put("className", item.className)
                            put("version", item.version)
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
        val pluginsDir = File(context.filesDir, "plugins").apply { mkdirs() }
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
