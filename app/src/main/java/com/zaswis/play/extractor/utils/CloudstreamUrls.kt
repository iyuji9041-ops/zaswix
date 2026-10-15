package com.zaswis.play.extractor.utils

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Dynamically resolves provider mirror domains using Zaswix dynamic URLs repository
 */
object CloudstreamUrls {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // Default mirror URLs from Cloudstream CSX
    private val defaultUrls = mapOf(
        "vegamovies" to "https://vegamovies.gallery",
        "bollyflix" to "https://bollyflix.photo",
        "gdflix" to "https://new5.gdflix.io",
        "hubcloud" to "https://hubcloud.ist",
        "vcloud" to "https://vcloud.beer",
        "multimovies" to "https://multimovies.garden",
        "toonstream" to "https://toonstream.us",
        "hdhub4u" to "https://new7.hdhub4u.cl",
        "moviesmod" to "https://moviesmod.ai.in",
        "uhdmovies" to "https://uhdmovies.my"
    )

    private val cachedUrls = ConcurrentHashMap<String, String>(defaultUrls)
    private var hasFetched = false

    suspend fun getUrl(key: String): String = withContext(Dispatchers.IO) {
        if (!hasFetched) {
            fetchDynamicUrls()
        }
        cachedUrls[key] ?: defaultUrls[key] ?: ""
    }

    private fun fetchDynamicUrls() {
        try {
            val req = Request.Builder()
                .url("https://raw.githubusercontent.com/zaswix/plugins/main/urls.json")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val json = resp.body?.string().orEmpty()
                val type = object : TypeToken<Map<String, String>>() {}.type
                val map: Map<String, String> = gson.fromJson(json, type)
                cachedUrls.putAll(map)
                hasFetched = true
            }
        } catch (e: Exception) {
            // Keep using cached defaults if network unavailable
        }
    }
}
