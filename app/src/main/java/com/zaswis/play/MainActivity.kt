package com.zaswis.play

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zaswis.play.data.model.MediaItem
import com.zaswis.play.data.repository.MediaRepository
import com.zaswis.play.extractor.ServerOption
import com.zaswis.play.plugin.PluginManager
import com.zaswis.play.ui.components.NetflixBottomBar
import com.zaswis.play.ui.components.Screen
import com.zaswis.play.ui.screens.DetailsScreen
import com.zaswis.play.ui.screens.HomeScreen
import com.zaswis.play.ui.screens.PlayerScreen
import com.zaswis.play.ui.screens.PluginsScreen
import com.zaswis.play.ui.screens.SearchScreen
import com.zaswis.play.ui.theme.NetflixBlack
import com.zaswis.play.ui.theme.ZaswixTheme

class MainActivity : ComponentActivity() {

    private lateinit var mediaRepository: MediaRepository
    private lateinit var pluginManager: PluginManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        mediaRepository = MediaRepository()
        pluginManager = PluginManager(this)

        setContent {
            ZaswixTheme {
                ZaswixApp(
                    mediaRepository = mediaRepository,
                    pluginManager = pluginManager,
                    onOrientationChange = { isLandscape ->
                        requestedOrientation = if (isLandscape) {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun ZaswixApp(
    mediaRepository: MediaRepository,
    pluginManager: PluginManager,
    onOrientationChange: (Boolean) -> Unit
) {
    var currentRoute by remember { mutableStateOf(Screen.Home.route) }
    var selectedItem by remember { mutableStateOf<MediaItem?>(null) }
    var playingItem by remember { mutableStateOf<MediaItem?>(null) }
    var playingServers by remember { mutableStateOf<List<ServerOption>>(emptyList()) }
    var playingInitialServer by remember { mutableStateOf<ServerOption?>(null) }

    // If player is open, render in-app fullscreen player
    if (playingItem != null) {
        onOrientationChange(true)
        BackHandler {
            onOrientationChange(false)
            playingItem = null
            playingServers = emptyList()
            playingInitialServer = null
        }
        PlayerScreen(
            item = playingItem!!,
            servers = playingServers,
            initialServer = playingInitialServer,
            onBackClick = {
                onOrientationChange(false)
                playingItem = null
                playingServers = emptyList()
                playingInitialServer = null
            }
        )
        return
    }

    onOrientationChange(false)

    // If details screen is open
    if (selectedItem != null) {
        BackHandler {
            selectedItem = null
        }
        DetailsScreen(
            item = selectedItem!!,
            mediaRepository = mediaRepository,
            onBackClick = { selectedItem = null },
            onPlayClick = { item, servers, initialServer ->
                playingItem = item
                playingServers = servers
                playingInitialServer = initialServer
            }
        )
        return
    }

    // Main App with Bottom Navigation Bar
    Scaffold(
        bottomBar = {
            NetflixBottomBar(
                currentRoute = currentRoute,
                onNavigate = { currentRoute = it }
            )
        },
        containerColor = NetflixBlack
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(NetflixBlack)
        ) {
            when (currentRoute) {
                Screen.Home.route -> {
                    HomeScreen(
                        mediaRepository = mediaRepository,
                        pluginManager = pluginManager,
                        onMediaClick = { selectedItem = it },
                        onPlayClick = { item ->
                            playingItem = item
                            playingServers = emptyList()
                            playingInitialServer = null
                        },
                        onSearchClick = { currentRoute = Screen.Search.route },
                        onPluginsClick = { currentRoute = Screen.Plugins.route }
                    )
                }
                Screen.Search.route -> {
                    SearchScreen(
                        mediaRepository = mediaRepository,
                        onMediaClick = { selectedItem = it }
                    )
                }
                Screen.Plugins.route -> {
                    PluginsScreen(
                        pluginManager = pluginManager
                    )
                }
                Screen.MyList.route -> {
                    SearchScreen(
                        mediaRepository = mediaRepository,
                        onMediaClick = { selectedItem = it }
                    )
                }
            }
        }
    }
}
