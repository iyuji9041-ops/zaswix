package com.zaswis.play.ui.screens

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.zaswis.play.data.model.StreamSource
import com.zaswis.play.extractor.CloudstreamProvider
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.zaswis.play.data.model.MediaItem
import com.zaswis.play.data.repository.MediaRepository
import com.zaswis.play.ui.components.ZaswixIcons
import com.zaswis.play.ui.theme.NetflixCardBg
import com.zaswis.play.ui.theme.NetflixDarkGrey
import com.zaswis.play.ui.theme.NetflixLightGrey
import com.zaswis.play.ui.theme.NetflixRed
import com.zaswis.play.ui.theme.NetflixWhite

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    item: MediaItem,
    onBackClick: () -> Unit,
    mediaRepository: MediaRepository = remember { MediaRepository() },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // Immersive Mode: Hide system status bar and navigation bar while watching
    DisposableEffect(Unit) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            val window = activity?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    var activeSources by remember(item.id) { mutableStateOf(item.streamSources) }
    var selectedSourceIndex by remember(item.id) { mutableIntStateOf(0) }
    var isResolving by remember(item.id) {
        mutableStateOf(item.streamSources.isEmpty() && (item.embedUrls.isNotEmpty() || item.pageUrl.isNotEmpty()))
    }
    var resolutionError by remember(item.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.id) {
        if (activeSources.isEmpty() && (item.embedUrls.isNotEmpty() || item.pageUrl.isNotEmpty())) {
            isResolving = true
            resolutionError = null
            try {
                val resolved = mediaRepository.autoResolveMediaStreams(item)
                if (resolved.isNotEmpty()) {
                    activeSources = resolved
                    selectedSourceIndex = 0
                } else {
                    resolutionError = "No original stream found on server. Please try another provider."
                }
            } catch (e: Exception) {
                resolutionError = "Failed to resolve stream: ${e.localizedMessage ?: "Network error"}"
            } finally {
                isResolving = false
            }
        } else if (activeSources.isEmpty()) {
            resolutionError = "No stream source available for this title."
        }
    }

    val currentSource = activeSources.getOrNull(selectedSourceIndex)

    var isBuffering by remember { mutableStateOf(true) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var isControlsVisible by remember { mutableStateOf(true) }

    // CloudStream Settings State
    var showSettingsDialog by remember { mutableStateOf(false) }
    var selectedAudioTrack by remember { mutableStateOf("Default / Original") }
    var selectedSubtitleTrack by remember { mutableStateOf("Off") }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var isZoomMode by remember { mutableStateOf(false) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    val httpDataSourceFactory = remember {
        OkHttpDataSource.Factory(CloudstreamProvider.client)
            .setUserAgent(CloudstreamProvider.DEFAULT_USER_AGENT)
    }

    fun prepareHeadersForSource(source: StreamSource) {
        val effectiveHeaders = mutableMapOf<String, String>()
        effectiveHeaders["User-Agent"] = CloudstreamProvider.DEFAULT_USER_AGENT
        if (source.referer.isNotEmpty()) {
            effectiveHeaders["Referer"] = source.referer
        } else if (source.headers.containsKey("Referer")) {
            effectiveHeaders["Referer"] = source.headers["Referer"].orEmpty()
        }
        effectiveHeaders.putAll(source.headers)
        httpDataSourceFactory.setDefaultRequestProperties(effectiveHeaders)
    }

    fun buildExoMediaItem(source: StreamSource): ExoMediaItem {
        val builder = ExoMediaItem.Builder()
            .setUri(source.url)

        val isHls = source.isM3u8 || source.url.contains(".m3u8", ignoreCase = true)
        val isMp4 = source.url.contains(".mp4", ignoreCase = true)

        if (isHls) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        } else if (isMp4) {
            builder.setMimeType(MimeTypes.APPLICATION_MP4)
        }
        return builder.build()
    }

    val exoPlayer = remember {
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15000, // minBufferMs
                50000, // maxBufferMs
                1000,  // bufferForPlaybackMs
                2000   // bufferForPlaybackAfterRebufferMs
            )
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
    }

    LaunchedEffect(currentSource?.url) {
        val source = currentSource
        val url = source?.url
        if (!url.isNullOrBlank()) {
            prepareHeadersForSource(source)
            try {
                val mediaItem = buildExoMediaItem(source)
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                playbackError = null
            } catch (e: Throwable) {
                playbackError = "Player setup error: ${e.localizedMessage ?: "Failed to initialize stream"}"
            }
        }
    }

    // Dynamic Quality Switcher preserving exact playback timestamp (CloudStream style)
    fun switchQuality(newIndex: Int) {
        if (newIndex == selectedSourceIndex) return
        val currentPosition = exoPlayer.currentPosition
        val wasPlaying = exoPlayer.isPlaying
        selectedSourceIndex = newIndex
        val newSource = activeSources.getOrNull(newIndex) ?: return
        prepareHeadersForSource(newSource)
        try {
            val newMediaItem = buildExoMediaItem(newSource)
            exoPlayer.setMediaItem(newMediaItem)
            exoPlayer.prepare()
            exoPlayer.seekTo(currentPosition)
            exoPlayer.playWhenReady = wasPlaying
            playbackError = null
        } catch (e: Throwable) {
            playbackError = "Stream switch error: ${e.localizedMessage ?: "Failed to switch source"}"
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = (playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_READY) {
                    playbackError = null
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                if (selectedSourceIndex + 1 < activeSources.size) {
                    // Try next working stream fallback
                    switchQuality(selectedSourceIndex + 1)
                } else {
                    playbackError = error.localizedMessage ?: "Failed to stream video from server"
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    if (activeSources.isEmpty() && isResolving) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                CircularProgressIndicator(
                    color = NetflixRed,
                    modifier = Modifier.size(56.dp),
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Resolving Original Stream...",
                    color = NetflixWhite,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Connecting to ${item.providerName} scraper (Zero dummy videos)",
                    color = NetflixLightGrey,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onBackClick,
                    colors = ButtonDefaults.buttonColors(containerColor = NetflixDarkGrey),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("Cancel", color = NetflixWhite)
                }
            }
        }
        return
    }

    if (activeSources.isEmpty() && resolutionError != null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = NetflixCardBg),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Original Stream Unavailable",
                        color = NetflixRed,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = resolutionError ?: "Could not resolve stream link",
                        color = NetflixLightGrey,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No dummy video will be played.",
                        color = NetflixWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onBackClick,
                        colors = ButtonDefaults.buttonColors(containerColor = NetflixRed),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Go Back", color = NetflixWhite, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { isControlsVisible = !isControlsVisible }
    ) {
        // In-app ExoPlayer View
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    playerViewRef = this
                }
            },
            update = { playerView ->
                playerView.player = exoPlayer
                playerView.resizeMode = if (isZoomMode) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Buffering Indicator in Center
        if (isBuffering && playbackError == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = NetflixRed,
                    modifier = Modifier.size(54.dp),
                    strokeWidth = 3.dp
                )
            }
        }

        // Playback Error Overlay with Retry and Next Server
        if (playbackError != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = NetflixCardBg.copy(alpha = 0.95f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Stream Connection Error",
                            color = NetflixRed,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = playbackError ?: "Unable to stream source",
                            color = NetflixLightGrey,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    playbackError = null
                                    exoPlayer.prepare()
                                    exoPlayer.play()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NetflixRed),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("Retry", color = NetflixWhite, fontWeight = FontWeight.Bold)
                            }
                            if (activeSources.size > 1) {
                                Button(
                                    onClick = {
                                        val nextIdx = (selectedSourceIndex + 1) % activeSources.size
                                        switchQuality(nextIdx)
                                        playbackError = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = NetflixDarkGrey),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("Next Server / Quality", color = NetflixWhite)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Top Overlay Bar (Controls)
        if (isControlsVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                ) {
                    Icon(
                        imageVector = ZaswixIcons.ArrowBack,
                        contentDescription = "Back",
                        tint = NetflixWhite
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        color = NetflixWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = "${item.providerName} • ${currentSource?.serverName ?: "Fast CDN"}",
                        color = NetflixLightGrey,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }

                // Quick Quality Selector Pill
                Surface(
                    onClick = { showSettingsDialog = true },
                    shape = RoundedCornerShape(6.dp),
                    color = NetflixRed.copy(alpha = 0.9f),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentSource?.quality ?: "1080p",
                            color = NetflixWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = ZaswixIcons.ArrowDropDown,
                            contentDescription = "Quality",
                            tint = NetflixWhite,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // CloudStream Style Full Settings Icon (Gear)
                IconButton(
                    onClick = { showSettingsDialog = true },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                ) {
                    Icon(
                        imageVector = ZaswixIcons.Settings,
                        contentDescription = "Player Settings",
                        tint = NetflixWhite,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // CloudStream Style Player Settings Modal (Quality, Audio, Subtitles, Speed, Aspect Ratio)
        if (showSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                containerColor = NetflixCardBg,
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Stream & Player Settings",
                            color = NetflixWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // 1. VIDEO QUALITY SECTION
                        Text(
                            text = "VIDEO QUALITY",
                            color = NetflixRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )

                        activeSources.forEachIndexed { index, source ->
                            val isSelected = index == selectedSourceIndex
                            Surface(
                                onClick = {
                                    switchQuality(index)
                                    showSettingsDialog = false
                                },
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSelected) NetflixRed.copy(alpha = 0.2f) else NetflixDarkGrey,
                                border = if (isSelected) BorderStroke(1.dp, NetflixRed) else null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = source.quality,
                                            color = NetflixWhite,
                                            fontSize = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Text(
                                            text = source.serverName,
                                            color = NetflixLightGrey,
                                            fontSize = 11.sp
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = ZaswixIcons.Check,
                                            contentDescription = "Selected",
                                            tint = NetflixRed,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // 2. AUDIO TRACKS SECTION
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "AUDIO LANGUAGE",
                            color = NetflixRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )

                        val audioOptions = listOf(
                            "Default / Original Audio" to null,
                            "Hindi (Dual Audio Dub)" to "hi",
                            "English (Original / Dub)" to "en",
                            "Japanese (Anime Voice)" to "ja"
                        )

                        audioOptions.forEach { (label, langCode) ->
                            val isSelected = selectedAudioTrack == label
                            Surface(
                                onClick = {
                                    selectedAudioTrack = label
                                    if (langCode != null) {
                                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                            .buildUpon()
                                            .setPreferredAudioLanguage(langCode)
                                            .build()
                                    } else {
                                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                            .buildUpon()
                                            .setPreferredAudioLanguage(null)
                                            .build()
                                    }
                                },
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSelected) NetflixRed.copy(alpha = 0.2f) else NetflixDarkGrey,
                                border = if (isSelected) BorderStroke(1.dp, NetflixRed) else null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = label,
                                        color = NetflixWhite,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
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

                        // 3. SUBTITLES SECTION
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "SUBTITLES & CAPTIONS",
                            color = NetflixRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )

                        val subOptions = listOf(
                            "Off / Disable Subtitles" to null,
                            "English Subtitles [CC]" to "en",
                            "Hindi Subtitles" to "hi",
                            "Japanese Subtitles" to "ja"
                        )

                        subOptions.forEach { (label, langCode) ->
                            val isSelected = selectedSubtitleTrack == label
                            Surface(
                                onClick = {
                                    selectedSubtitleTrack = label
                                    if (langCode != null) {
                                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                            .buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                            .setPreferredTextLanguage(langCode)
                                            .build()
                                    } else {
                                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                            .buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                            .build()
                                    }
                                },
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSelected) NetflixRed.copy(alpha = 0.2f) else NetflixDarkGrey,
                                border = if (isSelected) BorderStroke(1.dp, NetflixRed) else null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = label,
                                        color = NetflixWhite,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
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

                        // 4. PLAYBACK SPEED & ASPECT RATIO
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "SPEED & DISPLAY",
                            color = NetflixRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                val isSelected = playbackSpeed == speed
                                Surface(
                                    onClick = {
                                        playbackSpeed = speed
                                        exoPlayer.setPlaybackSpeed(speed)
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isSelected) NetflixRed else NetflixDarkGrey,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${speed}x",
                                            color = NetflixWhite,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        // Aspect Ratio Toggle
                        Surface(
                            onClick = { isZoomMode = !isZoomMode },
                            shape = RoundedCornerShape(6.dp),
                            color = NetflixDarkGrey,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isZoomMode) "Aspect Ratio: Zoom / Crop (Fill Screen)" else "Aspect Ratio: Fit 16:9 (Letterbox)",
                                    color = NetflixWhite,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = if (isZoomMode) "ZOOM" else "FIT",
                                    color = NetflixRed,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSettingsDialog = false }) {
                        Text("Done", color = NetflixRed, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}
