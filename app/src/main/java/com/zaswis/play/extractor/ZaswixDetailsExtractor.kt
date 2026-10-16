package com.zaswis.play.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class EpisodeItem(
    val season: Int,
    val episode: Int,
    val title: String,
    val url: String,
    val thumbnailUrl: String = "",
    val runtime: String = "",
    val description: String = ""
)

data class ServerOption(
    val id: String,
    val serverName: String,
    val embedUrl: String,
    val quality: String = "HD",
    val referer: String
)

data class MediaDetailsPayload(
    val title: String,
    val posterUrl: String,
    val backdropUrl: String,
    val synopsis: String,
    val rating: String,
    val year: String,
    val genres: List<String>,
    val servers: List<ServerOption>,
    val episodes: List<EpisodeItem>,
    val downloadLinks: List<ServerOption> = emptyList()
)

data class ResolvedStream(
    val url: String,
    val headers: Map<String, String>,
    val isDirectStream: Boolean // true = ExoPlayer (.m3u8/.mp4), false = AdBlocked WebView (Embed)
)

object ZaswixDetailsExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private suspend fun fetchDoc(url: String, referer: String = url): Document? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: return@withContext null
                Jsoup.parse(body, url)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ========================================================================
    // 1. MULTIMOVIES DETAILS & SERVERS PARSER (multimovies_garden (1).html)
    // ========================================================================
    suspend fun loadMultiMoviesDetails(pageUrl: String): MediaDetailsPayload? {
        val doc = fetchDoc(pageUrl) ?: return null
        return parseMultiMoviesDetails(doc, pageUrl)
    }

    fun parseMultiMoviesDetails(doc: Document, pageUrl: String): MediaDetailsPayload {
        val title = doc.selectFirst("h1.cinejoy-detail-title")?.text()?.trim().orEmpty()
        val backdrop = doc.selectFirst("#watchAmbientImage")?.attr("src").orEmpty()
        val synopsis = doc.selectFirst("#cinejoyOverview")?.text()?.trim().orEmpty()
        val rating = doc.selectFirst(".cinejoy-score-pill")?.text()?.replace("★", "")?.trim().orEmpty()
        val year = doc.selectFirst(".cinejoy-detail-meta .cinejoy-meta-pill")?.text()?.trim().orEmpty()
        val genres = doc.selectFirst(".cinejoy-detail-genres")?.text()
            ?.split("•")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

        val servers = mutableListOf<ServerOption>()

        // METHOD A: Extract from #playerServerGrid > button.server-chip[data-url]
        doc.select("#playerServerGrid > button.server-chip").forEachIndexed { idx, btn ->
            val id = btn.attr("data-id").ifEmpty { "srv_$idx" }
            val rawUrl = btn.attr("data-url").trim()
            val name = btn.selectFirst(".server-name")?.text()?.trim()?.ifEmpty { "Server ${idx + 1}" } ?: "Server ${idx + 1}"
            val badge = btn.selectFirst(".server-badge")?.text()?.trim()?.ifEmpty { "HD" } ?: "HD"

            if (rawUrl.isNotEmpty() && rawUrl != "about:blank") {
                servers.add(ServerOption(id, name, rawUrl, badge, pageUrl))
            }
        }

        // METHOD B (Fallback): Extract from const watchConfig = {...} inside <script>
        if (servers.isEmpty()) {
            val scriptRegex = Pattern.compile("""const\s+watchConfig\s*=\s*(\{.*?\});""")
            for (script in doc.select("script")) {
                val matcher = scriptRegex.matcher(script.data())
                if (matcher.find()) {
                    try {
                        val json = JSONObject(matcher.group(1)!!)
                        val arr = json.optJSONArray("initialServers")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val srv = arr.getJSONObject(i)
                                servers.add(
                                    ServerOption(
                                        id = srv.optString("id", "srv_$i"),
                                        serverName = srv.optString("name", "Server ${i + 1}"),
                                        embedUrl = srv.optString("url"),
                                        quality = srv.optString("quality", "HD"),
                                        referer = pageUrl
                                    )
                                )
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // Extract Episodes: .cinejoy-season-grid > .cinejoy-ep-card
        val episodes = doc.select(".cinejoy-season-grid > .cinejoy-ep-card").mapIndexedNotNull { idx, card ->
            val epLink = card.selectFirst("a.cinejoy-ep-thumb-link")?.attr("abs:href").orEmpty()
            val epName = card.selectFirst("a.cinejoy-ep-name")?.text()?.trim().orEmpty()
            val epDesc = card.selectFirst("p.cinejoy-ep-desc")?.text()?.trim().orEmpty()
            val thumb = card.selectFirst("img.cinejoy-ep-img")?.attr("src").orEmpty()
            val runtime = card.selectFirst("span.cinejoy-ep-badge-time")?.text()?.trim().orEmpty()
            val epNumText = card.selectFirst("span.cinejoy-ep-badge-num")?.text()?.replace("E", "")?.trim()
            val epNum = epNumText?.toIntOrNull() ?: (idx + 1)
            val seasonNum = card.selectFirst(".cinejoy-ep-eye-btn")?.attr("data-season")?.toIntOrNull() ?: 1

            if (epLink.isEmpty()) null else EpisodeItem(
                season = seasonNum,
                episode = epNum,
                title = epName.ifEmpty { "Episode $epNum" },
                url = epLink,
                thumbnailUrl = thumb,
                runtime = runtime,
                description = epDesc
            )
        }

        return MediaDetailsPayload(
            title = title,
            posterUrl = backdrop.replace("/original/", "/w500/"),
            backdropUrl = backdrop,
            synopsis = synopsis,
            rating = rating,
            year = year,
            genres = genres,
            servers = servers,
            episodes = episodes
        )
    }

    // ========================================================================
    // 2. TOONSTREAM DETAILS & SERVERS PARSER (toonstream_us (1).html)
    // ========================================================================
    suspend fun loadToonStreamDetails(pageUrl: String): MediaDetailsPayload? {
        val doc = fetchDoc(pageUrl) ?: return null
        val baseUrl = "https://${java.net.URI(pageUrl).host}"
        val serverDomainMap = fetchToonStreamServerMap(baseUrl)
        return parseToonStreamDetails(doc, pageUrl, serverDomainMap)
    }

    // ToonStream dynamically replaces dead server domains via /public/servers JSON
    private suspend fun fetchToonStreamServerMap(baseUrl: String): Map<String, Pair<String, String>> = withContext(Dispatchers.IO) {
        val map = mutableMapOf<String, Pair<String, String>>() // normalizedName -> Pair(oldUrl, newUrl)
        try {
            val req = Request.Builder().url("$baseUrl/public/servers").header("User-Agent", USER_AGENT).build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: return@withContext emptyMap()
                val dataArr = JSONObject(body).optJSONArray("data") ?: return@withContext emptyMap()
                for (i in 0 until dataArr.length()) {
                    val obj = dataArr.getJSONObject(i)
                    if (obj.optBoolean("enabled", true)) {
                        val key = (obj.optString("nickname").ifEmpty { obj.optString("name") })
                            .lowercase().replace("\\s+".toRegex(), "").replace("stream", "")
                        val oldUrl = obj.optString("oldUrl")
                        val newUrl = obj.optString("url")
                        map[key] = Pair(oldUrl, newUrl)
                    }
                }
            }
        } catch (_: Exception) {}
        map
    }

    fun parseToonStreamDetails(
        doc: Document,
        pageUrl: String,
        serverDomainMap: Map<String, Pair<String, String>> = emptyMap()
    ): MediaDetailsPayload {
        val article = doc.selectFirst("article.post.single")
        val title = article?.selectFirst("h1.entry-title")?.text()?.replace("\\s+".toRegex(), " ")?.trim().orEmpty()
        val poster = article?.selectFirst(".post-thumbnail figure img")?.attr("src").orEmpty()
        val backdrop = doc.selectFirst(".bghd img.TPostBg")?.attr("src")?.ifEmpty { poster } ?: poster
        val synopsis = article?.selectFirst(".description")?.text()?.trim().orEmpty()
        val rating = article?.selectFirst(".vote-cn .vote .num")?.text()?.trim().orEmpty()
        val year = article?.selectFirst(".entry-meta span.year")?.text()?.trim().orEmpty()
        val genres = article?.select(".entry-meta span.genres a")?.map { it.text().trim() }.orEmpty()

        // A. Map Server Names from .aa-tbs-video li a.btn
        val serverNameMap = mutableMapOf<String, String>() // "options-0" -> "Ruby"
        doc.select(".aa-tbs-video li").forEach { li ->
            val targetId = li.selectFirst("a.btn")?.attr("href")?.removePrefix("#").orEmpty()
            val srvName = li.selectFirst("span.server")?.text()?.trim().orEmpty()
            if (targetId.isNotEmpty() && srvName.isNotEmpty()) {
                serverNameMap[targetId] = srvName
            }
        }

        // B. Extract Video Iframes from #aa-options > div.video (Checking BOTH data-src AND src!)
        val servers = mutableListOf<ServerOption>()
        doc.select("#aa-options > div.video").forEachIndexed { idx, videoDiv ->
            val divId = videoDiv.id() // options-0, options-1, etc.
            val iframe = videoDiv.selectFirst("iframe") ?: return@forEachIndexed
            var iframeUrl = iframe.attr("data-src").ifEmpty { iframe.attr("src") }.trim()

            if (iframeUrl.isNotEmpty() && iframeUrl != "about:blank") {
                val srvName = serverNameMap[divId] ?: "Server ${idx + 1}"
                val normKey = srvName.lowercase().replace("\\s+".toRegex(), "").replace("stream", "")

                // Apply ToonStream /public/servers domain replacement if applicable
                serverDomainMap[normKey]?.let { (oldDomain, newDomain) ->
                    if (oldDomain.isNotEmpty() && newDomain.isNotEmpty() && iframeUrl.contains(oldDomain)) {
                        iframeUrl = iframeUrl.replace(oldDomain, newDomain)
                    }
                }

                servers.add(
                    ServerOption(
                        id = divId,
                        serverName = srvName,
                        embedUrl = iframeUrl,
                        quality = "Multi Audio HD",
                        referer = pageUrl
                    )
                )
            }
        }

        // C. Extract Direct Download Links from #cyberModal .links-list .link-row
        val downloads = doc.select("#cyberModal .links-list .link-row").mapIndexedNotNull { idx, row ->
            val sName = row.selectFirst(".server-name")?.text()?.trim().orEmpty()
            val dlHref = row.selectFirst("a.download-btn")?.attr("href")?.trim().orEmpty()
            if (dlHref.isEmpty()) null else ServerOption("dl_$idx", sName, dlHref, "Download", pageUrl)
        }

        // D. Extract Episodes from ul#episode_by_temp > li > article.post.episodes
        val episodes = doc.select("ul#episode_by_temp > li > article.post.episodes").mapIndexedNotNull { idx, epArt ->
            val epLink = epArt.selectFirst("a.lnk-blk")?.attr("abs:href").orEmpty()
            val thumb = epArt.selectFirst(".post-thumbnail2 img")?.attr("src").orEmpty()
            val numEpi = epArt.selectFirst("span.num-epi")?.text()?.trim().orEmpty() // e.g., "1x1"
            val epTitle = epArt.selectFirst("h5.entry-title1")?.text()?.trim().orEmpty()
            val seasonNum = numEpi.substringBefore("x").toIntOrNull() ?: 1
            val epNum = numEpi.substringAfter("x").toIntOrNull() ?: (idx + 1)

            if (epLink.isEmpty()) null else EpisodeItem(
                season = seasonNum,
                episode = epNum,
                title = epTitle.ifEmpty { "Episode $numEpi" },
                url = epLink,
                thumbnailUrl = thumb
            )
        }

        return MediaDetailsPayload(
            title = title,
            posterUrl = poster,
            backdropUrl = backdrop,
            synopsis = synopsis,
            rating = rating,
            year = year,
            genres = genres,
            servers = servers,
            episodes = episodes,
            downloadLinks = downloads
        )
    }

    // ========================================================================
    // 3. UNIVERSAL STREAM RESOLVER (Unpacks Ruby/Vidstream or falls back to WebView)
    // ========================================================================
    suspend fun resolveServerToStream(server: ServerOption): ResolvedStream = withContext(Dispatchers.IO) {
        val defaultHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to server.embedUrl
        )

        try {
            val req = Request.Builder()
                .url(server.embedUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", server.referer)
                .build()

            val html = client.newCall(req).execute().use { it.body?.string().orEmpty() }

            // 1. Check for direct .m3u8 or .mp4 in HTML (Works on Vidstream, Filemoon, RubyStream)
            val m3u8Regex = Pattern.compile("""["'](https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)["']""")
            val matcher = m3u8Regex.matcher(html)
            if (matcher.find()) {
                return@withContext ResolvedStream(
                    url = matcher.group(1)!!,
                    headers = defaultHeaders,
                    isDirectStream = true // Play in ExoPlayer!
                )
            }

            // 2. Check for packed JS: eval(function(p,a,c,k,e,d)...) used by RubyStream (rubystm.com)
            val unpacked = unpackJs(html)
            if (unpacked != null) {
                val unpackedMatcher = m3u8Regex.matcher(unpacked)
                if (unpackedMatcher.find()) {
                    return@withContext ResolvedStream(
                        url = unpackedMatcher.group(1)!!,
                        headers = defaultHeaders,
                        isDirectStream = true // Play in ExoPlayer!
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Fallback: Return the Embed URL itself for Ad-Blocked WebView Player
        // (Guarantees ModiPlay, Vidrift, VIDOUT, Cloudy, AbyssPlayer 100% play!)
        ResolvedStream(
            url = server.embedUrl,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to server.referer),
            isDirectStream = false
        )
    }

    // Unpacker for Dean Edwards p,a,c,k,e,d JavaScript obfuscation (Used by RubyStream / Streamwish)
    private fun unpackJs(html: String): String? {
        val packedRegex = Pattern.compile(
            """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
            Pattern.DOTALL
        )
        val matcher = packedRegex.matcher(html)
        if (!matcher.find()) return null

        var payload = matcher.group(1) ?: return null
        val radix = matcher.group(2)?.toIntOrNull() ?: 36
        val symtab = matcher.group(4)?.split("|") ?: return null

        val wordRegex = Pattern.compile("""\b\w+\b""")
        val wordMatcher = wordRegex.matcher(payload)
        val sb = StringBuffer()

        while (wordMatcher.find()) {
            val word = wordMatcher.group(0)!!
            val index = word.toIntOrNull(radix)
            val replacement = if (index != null && index < symtab.size && symtab[index].isNotEmpty()) {
                symtab[index]
            } else {
                word
            }
            wordMatcher.appendReplacement(sb, MatcherQuote(replacement))
        }
        wordMatcher.appendTail(sb)
        return sb.toString()
    }

    private fun MatcherQuote(s: String): String = java.util.regex.Matcher.quoteReplacement(s)
}
