package com.zaswis.play.data.model

data class StreamSource(
    val serverName: String = "Server 1",
    val quality: String = "1080p",
    val url: String,
    val isM3u8: Boolean = url.contains(".m3u8"),
    val headers: Map<String, String> = emptyMap()
)
