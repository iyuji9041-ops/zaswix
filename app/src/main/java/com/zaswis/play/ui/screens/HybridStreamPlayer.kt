package com.zaswis.play.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.zaswis.play.extractor.ResolvedStream
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream

// Ad domains found in ToonStream & MultiMovies HTML source code
private val AD_DOMAINS = listOf(
    "paviliongiddy.com", "bondeddarkenedswerve.com", "crowbarrinds.com",
    "dinomysstorm.com", "21wiz.com", "acscdn.com", "superficial-disaster.com",
    "hilltopads.com", "monetag.com", "a-ads.com", "displayvertising.com",
    "cloudfront.net", "ptekuwiny.pro", "loyal-product.com"
)

@OptIn(UnstableApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HybridStreamPlayer(
    stream: ResolvedStream,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (stream.isDirectStream) {
            // 1. NATIVE EXOPLAYER (For .m3u8 & .mp4 streams)
            val exoPlayer = remember(stream.url) {
                val okHttpClient = OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()

                val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                    .setDefaultRequestProperties(stream.headers)

                ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                    .build()
                    .apply {
                        setMediaItem(MediaItem.fromUri(Uri.parse(stream.url)))
                        prepare()
                        playWhenReady = true
                    }
            }

            DisposableEffect(stream.url) {
                onDispose { exoPlayer.release() }
            }

            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // 2. AD-BLOCKED EMBED PLAYER (For ModiPlay, Vidrift, VIDOUT, Cloudy, AbyssPlayer)
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            userAgentString = stream.headers["User-Agent"]
                            // Disable popups & new windows completely
                            javaScriptCanOpenWindowsAutomatically = false
                            setSupportMultipleWindows(false)
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString()?.lowercase().orEmpty()
                                // Block all ad scripts and popunder networks
                                if (AD_DOMAINS.any { reqUrl.contains(it) }) {
                                    return WebResourceResponse(
                                        "text/plain",
                                        "UTF-8",
                                        ByteArrayInputStream(ByteArray(0))
                                    )
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val targetUrl = request?.url?.toString().orEmpty()
                                // Prevent ad redirects from leaving the video embed domain
                                val embedHost = Uri.parse(stream.url).host.orEmpty()
                                return !targetUrl.contains(embedHost)
                            }
                        }

                        loadUrl(stream.url, stream.headers)
                    }
                },
                update = { webView ->
                    if (webView.url != stream.url) {
                        webView.loadUrl(stream.url, stream.headers)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
