package com.zaswis.play.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.zaswis.play.extractor.ResolvedStream
import com.zaswis.play.ui.components.ZaswixIcons
import com.zaswis.play.ui.theme.NetflixCardBg
import com.zaswis.play.ui.theme.NetflixLightGrey
import com.zaswis.play.ui.theme.NetflixRed
import com.zaswis.play.ui.theme.NetflixWhite
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.util.Locale

// Ad domains found in ToonStream & MultiMovies HTML source code
private val AD_DOMAINS = listOf(
    "paviliongiddy.com", "bondeddarkenedswerve.com", "crowbarrinds.com",
    "dinomysstorm.com", "21wiz.com", "acscdn.com", "superficial-disaster.com",
    "hilltopads.com", "monetag.com", "a-ads.com", "displayvertising.com",
    "cloudfront.net", "ptekuwiny.pro", "loyal-product.com"
)

data class PlayerTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val name: String,
    val language: String,
    val isSelected: Boolean
)

@OptIn(UnstableApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HybridStreamPlayer(
    stream: ResolvedStream,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

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
                        // Enable default subtitles if available (English preference)
                        trackSelectionParameters = trackSelectionParameters.buildUpon()
                            .setSelectUndeterminedTextLanguage(true)
                            .setPreferredTextLanguage("en")
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .build()
                        setMediaItem(MediaItem.fromUri(Uri.parse(stream.url)))
                        prepare()
                        playWhenReady = true
                    }
            }

            DisposableEffect(stream.url) {
                onDispose { exoPlayer.release() }
            }

            // Track state
            var audioTracks by remember { mutableStateOf<List<PlayerTrackOption>>(emptyList()) }
            var textTracks by remember { mutableStateOf<List<PlayerTrackOption>>(emptyList()) }
            var areSubtitlesOff by remember { mutableStateOf(false) }

            // Modals & Controls visibility
            var showTrackControls by remember { mutableStateOf(true) }
            var showAudioModal by remember { mutableStateOf(false) }
            var showSubtitleModal by remember { mutableStateOf(false) }

            // Gestures feedback state
            var seekFeedbackText by remember { mutableStateOf<String?>(null) }
            var seekFeedbackIsLeft by remember { mutableStateOf(false) }
            var showBrightnessHud by remember { mutableStateOf(false) }
            var currentBrightness by remember {
                val initBrightness = (context as? Activity)?.window?.attributes?.screenBrightness ?: 0.5f
                mutableFloatStateOf(if (initBrightness >= 0f) initBrightness else 0.5f)
            }

            var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

            fun refreshTracks() {
                val tracks = exoPlayer.currentTracks
                val audioList = mutableListOf<PlayerTrackOption>()
                val textList = mutableListOf<PlayerTrackOption>()
                val isTextDisabled = exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

                for (groupIndex in 0 until tracks.groups.size) {
                    val group = tracks.groups[groupIndex]
                    val type = group.type
                    for (trackIndex in 0 until group.length) {
                        val format = group.getTrackFormat(trackIndex)
                        val isSelected = group.isTrackSelected(trackIndex)
                        val lang = format.language.orEmpty()
                        val label = when {
                            !format.label.isNullOrBlank() -> format.label!!
                            lang.isNotBlank() && lang != "und" -> {
                                try {
                                    Locale(lang).getDisplayLanguage(Locale.ENGLISH).ifBlank { lang }
                                } catch (_: Exception) {
                                    lang
                                }
                            }
                            type == C.TRACK_TYPE_AUDIO -> "Audio Track ${trackIndex + 1}"
                            else -> "Subtitle Track ${trackIndex + 1}"
                        }

                        val playerTrack = PlayerTrackOption(
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                            name = label,
                            language = lang,
                            isSelected = isSelected
                        )

                        if (type == C.TRACK_TYPE_AUDIO) {
                            audioList.add(playerTrack)
                        } else if (type == C.TRACK_TYPE_TEXT) {
                            textList.add(playerTrack)
                        }
                    }
                }
                audioTracks = audioList
                textTracks = textList
                areSubtitlesOff = isTextDisabled || textList.none { it.isSelected }
            }

            DisposableEffect(exoPlayer) {
                val listener = object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        refreshTracks()
                    }
                }
                exoPlayer.addListener(listener)
                refreshTracks()
                onDispose {
                    exoPlayer.removeListener(listener)
                }
            }

            fun selectAudioTrack(track: PlayerTrackOption) {
                val currentTracks = exoPlayer.currentTracks
                if (track.groupIndex < currentTracks.groups.size) {
                    val group = currentTracks.groups[track.groupIndex]
                    val override = TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex)
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setOverrideForType(override)
                        .build()
                    refreshTracks()
                }
            }

            fun selectTextTrack(track: PlayerTrackOption?) {
                if (track == null) {
                    // Turn Subtitles OFF
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .build()
                } else {
                    // Turn Subtitles ON
                    val currentTracks = exoPlayer.currentTracks
                    if (track.groupIndex < currentTracks.groups.size) {
                        val group = currentTracks.groups[track.groupIndex]
                        val override = TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex)
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(override)
                            .build()
                    }
                }
                refreshTracks()
            }

            LaunchedEffect(seekFeedbackText) {
                if (seekFeedbackText != null) {
                    delay(800)
                    seekFeedbackText = null
                }
            }

            // Gesture Container wrapping PlayerView
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                if (offset.x < size.width / 2f) {
                                    val activity = context as? Activity
                                    val lp = activity?.window?.attributes
                                    if (lp != null) {
                                        currentBrightness = if (lp.screenBrightness >= 0f) lp.screenBrightness else 0.5f
                                        showBrightnessHud = true
                                    }
                                }
                            },
                            onDragEnd = {
                                coroutineScope.launch {
                                    delay(900)
                                    showBrightnessHud = false
                                }
                            },
                            onDragCancel = {
                                showBrightnessHud = false
                            },
                            onVerticalDrag = { change, dragAmount ->
                                if (change.position.x < size.width / 2f) {
                                    change.consume()
                                    val activity = context as? Activity
                                    val window = activity?.window
                                    val lp = window?.attributes
                                    if (lp != null) {
                                        val delta = -dragAmount / 350f
                                        currentBrightness = (currentBrightness + delta).coerceIn(0.01f, 1.0f)
                                        lp.screenBrightness = currentBrightness
                                        window.attributes = lp
                                        showBrightnessHud = true
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(exoPlayer) {
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                val isLeft = offset.x < size.width / 2f
                                val cur = exoPlayer.currentPosition
                                if (isLeft) {
                                    val target = (cur - 10_000L).coerceAtLeast(0L)
                                    exoPlayer.seekTo(target)
                                    seekFeedbackText = "-10s"
                                    seekFeedbackIsLeft = true
                                } else {
                                    val dur = exoPlayer.duration
                                    val target = if (dur > 0) (cur + 10_000L).coerceAtMost(dur) else cur + 10_000L
                                    exoPlayer.seekTo(target)
                                    seekFeedbackText = "+10s"
                                    seekFeedbackIsLeft = false
                                }
                            },
                            onTap = {
                                showTrackControls = !showTrackControls
                                if (playerViewRef?.isControllerFullyVisible == true) {
                                    playerViewRef?.hideController()
                                } else {
                                    playerViewRef?.showController()
                                }
                            }
                        )
                    }
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            useController = true
                            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                                showTrackControls = (visibility == android.view.View.VISIBLE)
                            })
                            playerViewRef = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Double Tap Seek Ripple/Badge Feedback
                AnimatedVisibility(
                    visible = seekFeedbackText != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(if (seekFeedbackIsLeft) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 48.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.75f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                            .padding(horizontal = 22.dp, vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (seekFeedbackIsLeft) "⏪" else "⏩",
                                fontSize = 24.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = seekFeedbackText.orEmpty(),
                                color = NetflixWhite,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Brightness HUD Indicator on Left Half
                AnimatedVisibility(
                    visible = showBrightnessHud,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xDD121212), RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 18.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "☀️",
                                fontSize = 20.sp
                            )
                            Text(
                                text = "${(currentBrightness * 100).toInt()}%",
                                color = NetflixWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Box(
                                modifier = Modifier
                                    .width(6.dp)
                                    .height(90.dp)
                                    .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(3.dp)),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height((90 * currentBrightness).dp)
                                        .background(Color(0xFFFFD700), RoundedCornerShape(3.dp))
                                )
                            }
                        }
                    }
                }

                // Glassmorphic Audio Tracks & Subtitles Buttons (Over Player)
                AnimatedVisibility(
                    visible = showTrackControls,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 64.dp, end = 16.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Glassmorphic Audio Tracks Button
                        GlassmorphicPlayerButton(
                            icon = ZaswixIcons.Audiotrack,
                            label = "Audio",
                            activeLabel = audioTracks.find { it.isSelected }?.name,
                            onClick = {
                                showAudioModal = true
                                showSubtitleModal = false
                            }
                        )

                        // Glassmorphic Subtitles Button
                        GlassmorphicPlayerButton(
                            icon = ZaswixIcons.Subtitles,
                            label = "Subtitles",
                            activeLabel = if (areSubtitlesOff) "Off" else (textTracks.find { it.isSelected }?.name ?: "On"),
                            isActive = !areSubtitlesOff,
                            onClick = {
                                showSubtitleModal = true
                                showAudioModal = false
                            }
                        )
                    }
                }

                // Glassmorphic Audio Tracks Selection Modal
                if (showAudioModal) {
                    GlassmorphicTrackModal(
                        title = "Audio Tracks",
                        icon = ZaswixIcons.Audiotrack,
                        onDismiss = { showAudioModal = false }
                    ) {
                        if (audioTracks.isEmpty()) {
                            Text(
                                text = "Default Audio (Embedded)",
                                color = NetflixLightGrey,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                audioTracks.forEach { track ->
                                    TrackItemRow(
                                        title = track.name,
                                        subText = if (track.language.isNotBlank() && track.language != "und") track.language.uppercase() else null,
                                        isSelected = track.isSelected,
                                        onClick = {
                                            selectAudioTrack(track)
                                            showAudioModal = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Glassmorphic Subtitles Selection Modal
                if (showSubtitleModal) {
                    GlassmorphicTrackModal(
                        title = "Subtitles & Captions",
                        icon = ZaswixIcons.Subtitles,
                        onDismiss = { showSubtitleModal = false }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Off option
                            TrackItemRow(
                                title = "Off",
                                subText = null,
                                isSelected = areSubtitlesOff,
                                onClick = {
                                    selectTextTrack(null)
                                    showSubtitleModal = false
                                }
                            )

                            if (textTracks.isEmpty()) {
                                Text(
                                    text = "No subtitle tracks available for this stream",
                                    color = NetflixLightGrey,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            } else {
                                textTracks.forEach { track ->
                                    TrackItemRow(
                                        title = track.name,
                                        subText = if (track.language.isNotBlank() && track.language != "und") track.language.uppercase() else null,
                                        isSelected = !areSubtitlesOff && track.isSelected,
                                        onClick = {
                                            selectTextTrack(track)
                                            showSubtitleModal = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
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

@Composable
private fun GlassmorphicPlayerButton(
    icon: ImageVector,
    label: String,
    activeLabel: String? = null,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = Color(0xAA141414),
        border = BorderStroke(
            1.dp,
            if (isActive) NetflixRed.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.2f)
        ),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) NetflixRed else NetflixWhite,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                color = NetflixWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (!activeLabel.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .background(
                            if (isActive) NetflixRed.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.15f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = activeLabel,
                        color = if (isActive) NetflixRed else NetflixLightGrey,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassmorphicTrackModal(
    title: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth(0.85f)
                .clickable(enabled = false, onClick = {})
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xF0181818)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = NetflixRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = title,
                            color = NetflixWhite,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = ZaswixIcons.Clear,
                            contentDescription = "Close",
                            tint = NetflixLightGrey,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                content()
            }
        }
    }
}

@Composable
private fun TrackItemRow(
    title: String,
    subText: String? = null,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) NetflixRed.copy(alpha = 0.2f) else NetflixCardBg,
        border = if (isSelected) BorderStroke(1.dp, NetflixRed) else BorderStroke(0.5.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    color = if (isSelected) NetflixWhite else NetflixLightGrey,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
                if (!subText.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = subText,
                            color = NetflixLightGrey,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = ZaswixIcons.Check,
                    contentDescription = "Selected",
                    tint = NetflixRed,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
