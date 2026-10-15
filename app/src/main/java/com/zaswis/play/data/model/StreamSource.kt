package com.zaswis.play.data.model

data class StreamSource(
    val serverName: String = "Server 1",
    val quality: String = "1080p",
    val url: String,
    val referer: String = "",
    val headers: Map<String, String> = emptyMap(),
    val isM3u8: Boolean = url.contains(".m3u8", ignoreCase = true)
) {
    fun getEffectiveHeaders(): Map<String, String> {
        val map = headers.toMutableMap()
        if (referer.isNotEmpty() && !map.containsKey("Referer")) {
            map["Referer"] = referer
        }
        return map
    }
}
