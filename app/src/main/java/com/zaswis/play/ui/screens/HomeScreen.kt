package com.zaswis.play.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaswis.play.data.model.MediaCategory
import com.zaswis.play.data.model.MediaItem
import com.zaswis.play.data.repository.MediaRepository
import com.zaswis.play.plugin.PluginManager
import com.zaswis.play.ui.components.HeroBanner
import com.zaswis.play.ui.components.MediaRow
import com.zaswis.play.ui.components.ZaswixIcons
import com.zaswis.play.ui.theme.NetflixBlack
import com.zaswis.play.ui.theme.NetflixCardBg
import com.zaswis.play.ui.theme.NetflixDarkGrey
import com.zaswis.play.ui.theme.NetflixLightGrey
import com.zaswis.play.ui.theme.NetflixRed
import com.zaswis.play.ui.theme.NetflixWhite

@Composable
fun HomeScreen(
    mediaRepository: MediaRepository,
    pluginManager: PluginManager,
    onMediaClick: (MediaItem) -> Unit,
    onPlayClick: (MediaItem) -> Unit,
    onSearchClick: () -> Unit,
    onPluginsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val plugins by pluginManager.plugins.collectAsState()
    val selectedPluginId by pluginManager.selectedPluginId.collectAsState()
    val categoriesFlow = remember(selectedPluginId) { mediaRepository.getHomeCategories(selectedPluginId) }
    val categories by categoriesFlow.collectAsState(initial = emptyList())
    val heroBannerItems by mediaRepository.heroBannerItems.collectAsState()
    var showProviderDialog by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf("All") }

    val activePlugin = remember(plugins, selectedPluginId) {
        plugins.find { it.id == selectedPluginId } ?: plugins.firstOrNull()
    }

    val filterTabs = remember(categories) {
        val base = mutableListOf("All")
        val allTitles = categories.map { it.title.lowercase() }
        if (allTitles.any { it.contains("movie") || it.contains("film") }) base.add("Movies")
        if (allTitles.any { it.contains("series") || it.contains("show") || it.contains("season") }) base.add("Series")
        if (allTitles.any { it.contains("anime") || it.contains("toon") || it.contains("animation") }) base.add("Anime")
        if (allTitles.any { it.contains("trend") || it.contains("featured") || it.contains("top") || it.contains("fresh") }) base.add("Trending")
        if (allTitles.any { it.contains("hindi") || it.contains("dub") }) base.add("Hindi Dubbed")
        base.distinct()
    }

    val displayedCategories = remember(categories, selectedFilter) {
        if (selectedFilter == "All") {
            categories
        } else {
            categories.filter { cat ->
                val t = cat.title.lowercase()
                when (selectedFilter) {
                    "Movies" -> t.contains("movie") || t.contains("film")
                    "Series" -> t.contains("series") || t.contains("show") || t.contains("season")
                    "Anime" -> t.contains("anime") || t.contains("toon") || t.contains("animation")
                    "Trending" -> t.contains("trend") || t.contains("featured") || t.contains("top") || t.contains("fresh")
                    "Hindi Dubbed" -> t.contains("hindi") || t.contains("dub")
                    else -> t.contains(selectedFilter.lowercase())
                }
            }.ifEmpty { categories }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NetflixBlack)
    ) {
        if (plugins.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = ZaswixIcons.Extension,
                        contentDescription = "Extensions",
                        tint = NetflixRed,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Extensions Installed",
                        color = NetflixWhite,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No extensions installed. Please install an extension from the Plugins tab.",
                        color = NetflixLightGrey,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    androidx.compose.material3.Button(
                        onClick = onPluginsClick,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = NetflixRed),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = ZaswixIcons.Download, contentDescription = "Install Extensions", tint = NetflixWhite, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Install Extensions", color = NetflixWhite, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = if (heroBannerItems.isEmpty()) 72.dp else 0.dp, bottom = 80.dp)
            ) {
                // Hero Banner at top (Netflix HorizontalPager)
                if (heroBannerItems.isNotEmpty()) {
                    item {
                        HeroBanner(
                            items = heroBannerItems,
                            onPlayClick = onPlayClick,
                            onDetailsClick = onMediaClick
                        )
                    }
                }

                // Category Filter Bar (Netflix-style horizontal pill chips)
                if (categories.isNotEmpty() && filterTabs.size > 1) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            filterTabs.forEach { tab ->
                                val isSelected = selectedFilter == tab
                                Surface(
                                    modifier = Modifier.clickable { selectedFilter = tab },
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (isSelected) NetflixWhite else Color(0x22FFFFFF),
                                    border = if (isSelected) null else BorderStroke(1.dp, Color(0x33FFFFFF))
                                ) {
                                    Text(
                                        text = tab,
                                        color = if (isSelected) Color.Black else NetflixWhite,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Loading indicator if categories are still fetching
                if (categories.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    color = NetflixRed,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Loading ${activePlugin?.name ?: "Media"}...",
                                    color = NetflixLightGrey,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }

                // Categories horizontal carousels
                items(displayedCategories) { category ->
                    MediaRow(
                        category = category,
                        onItemClick = onMediaClick
                    )
                }
            }
        }

        // Netflix Floating Glass Header Bar
        TopNetflixHeader(
            activePluginName = activePlugin?.name ?: "Extensions",
            onProviderClick = { showProviderDialog = true },
            onSearchClick = onSearchClick,
            onPluginsClick = onPluginsClick,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        )

        // Provider Selector Dialog
        if (showProviderDialog) {
            AlertDialog(
                onDismissRequest = { showProviderDialog = false },
                containerColor = NetflixCardBg,
                title = {
                    Column {
                        Text(
                            text = "Select Streaming Source",
                            color = NetflixWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Single-Plugin Mode: only 1 active provider at a time",
                            color = NetflixLightGrey,
                            fontSize = 12.sp
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        plugins.forEach { plugin ->
                            val isSelected = plugin.id == selectedPluginId
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pluginManager.selectPlugin(plugin.id)
                                        showProviderDialog = false
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) NetflixRed.copy(alpha = 0.2f) else NetflixDarkGrey
                                ),
                                border = if (isSelected) BorderStroke(1.5.dp, NetflixRed) else null,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = plugin.name,
                                            color = NetflixWhite,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = plugin.category,
                                            color = if (isSelected) NetflixRed else NetflixLightGrey,
                                            fontSize = 12.sp
                                        )
                                    }
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .background(NetflixRed, RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "ACTIVE",
                                                color = NetflixWhite,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showProviderDialog = false }) {
                        Text("Close", color = NetflixRed, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}

@Composable
fun TopNetflixHeader(
    activePluginName: String,
    onProviderClick: () -> Unit,
    onSearchClick: () -> Unit,
    onPluginsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Zaswix Logo
            Text(
                text = "ZASWIX",
                color = NetflixRed,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )

            // Center: Active Provider Switcher Pill
            Surface(
                onClick = onProviderClick,
                shape = RoundedCornerShape(16.dp),
                color = NetflixDarkGrey.copy(alpha = 0.9f),
                border = BorderStroke(1.dp, NetflixRed.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "🌐",
                        fontSize = 12.sp
                    )
                    Text(
                        text = activePluginName,
                        color = NetflixWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = ZaswixIcons.ArrowDropDown,
                        contentDescription = "Switch Provider",
                        tint = NetflixWhite,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Right: Action Icons (Search & Plugins)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onSearchClick) {
                    Icon(
                        imageVector = ZaswixIcons.Search,
                        contentDescription = "Search",
                        tint = NetflixWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = onPluginsClick) {
                    Icon(
                        imageVector = ZaswixIcons.Extension,
                        contentDescription = "Plugins",
                        tint = NetflixRed,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
