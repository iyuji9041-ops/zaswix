package com.zaswis.play.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.zaswis.play.data.model.MediaItem
import com.zaswis.play.data.repository.MediaRepository
import com.zaswis.play.plugin.PluginManager
import com.zaswis.play.ui.components.MediaCard
import com.zaswis.play.ui.components.ZaswixIcons
import com.zaswis.play.ui.theme.NetflixBlack
import com.zaswis.play.ui.theme.NetflixCardBg
import com.zaswis.play.ui.theme.NetflixDarkGrey
import com.zaswis.play.ui.theme.NetflixLightGrey
import com.zaswis.play.ui.theme.NetflixRed
import com.zaswis.play.ui.theme.NetflixWhite
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun SearchScreen(
    mediaRepository: MediaRepository,
    pluginManager: PluginManager? = null,
    onMediaClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    val allItems by mediaRepository.getAllSearchItems().collectAsState(initial = emptyList())
    var liveResults by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchRefreshTrigger by remember { mutableIntStateOf(0) }

    // Plugins state from PluginManager
    val plugins by (pluginManager?.plugins ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    val selectedPluginId by (pluginManager?.selectedPluginId ?: remember { MutableStateFlow("") }).collectAsState()
    val activePlugin = remember(plugins, selectedPluginId) {
        plugins.find { it.id == selectedPluginId } ?: plugins.firstOrNull()
    }
    val otherPlugins = remember(plugins, activePlugin) {
        plugins.filter { it.id != activePlugin?.id }
    }

    LaunchedEffect(searchQuery, searchRefreshTrigger, selectedPluginId) {
        val query = searchQuery.trim()
        if (query.length >= 2) {
            isSearching = true
            val live = try {
                mediaRepository.searchLivePlugins(query)
            } catch (e: Exception) {
                emptyList()
            }
            liveResults = live
            isSearching = false
        } else {
            liveResults = emptyList()
            isSearching = false
        }
    }

    val filteredItems = remember(searchQuery, allItems, liveResults, selectedPluginId) {
        if (searchQuery.isBlank()) {
            val providerFiltered = if (activePlugin != null) {
                allItems.filter { it.providerName.contains(activePlugin.name, ignoreCase = true) || it.providerName.contains(activePlugin.id, ignoreCase = true) }
                    .ifEmpty { allItems }
            } else {
                allItems
            }
            providerFiltered
        } else {
            val local = allItems.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.genres.any { g -> g.contains(searchQuery, ignoreCase = true) }
            }
            (liveResults + local).distinctBy { it.title.lowercase() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NetflixBlack)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Search Input Box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = if (activePlugin != null) "Search in ${activePlugin.name}..." else "Search movies, series, anime, genres...",
                        color = NetflixLightGrey
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = ZaswixIcons.Search,
                        contentDescription = "Search",
                        tint = NetflixLightGrey
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSearching) {
                            CircularProgressIndicator(
                                color = NetflixRed,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = ZaswixIcons.Clear,
                                    contentDescription = "Clear",
                                    tint = NetflixLightGrey
                                )
                            }
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = NetflixWhite,
                    unfocusedTextColor = NetflixWhite,
                    focusedBorderColor = NetflixRed,
                    unfocusedBorderColor = NetflixDarkGrey,
                    cursorColor = NetflixRed,
                    focusedContainerColor = NetflixDarkGrey,
                    unfocusedContainerColor = NetflixDarkGrey
                ),
                shape = RoundedCornerShape(8.dp)
            )

            // 2. Active Provider Pills Selector
            if (plugins.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(plugins) { plugin ->
                        val isSelected = plugin.id == selectedPluginId
                        Surface(
                            onClick = {
                                pluginManager?.selectPlugin(plugin.id)
                                searchRefreshTrigger++
                            },
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) NetflixRed else NetflixDarkGrey,
                            border = if (isSelected) null else BorderStroke(1.dp, Color(0xFF333333))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = ZaswixIcons.Check,
                                        contentDescription = "Active Provider",
                                        tint = NetflixWhite,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                Text(
                                    text = plugin.name,
                                    color = NetflixWhite,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Search Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (searchQuery.isEmpty()) {
                        "Popular & Catalog in ${activePlugin?.name ?: "All"}"
                    } else {
                        "Search Results (${filteredItems.size})"
                    },
                    color = NetflixWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                if (activePlugin != null) {
                    Box(
                        modifier = Modifier
                            .background(NetflixCardBg, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = activePlugin.category,
                            color = NetflixLightGrey,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4. Content Grid or Smart Fallback Card
            if (filteredItems.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(bottom = 80.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        MediaCard(
                            item = item,
                            onClick = onMediaClick
                        )
                    }
                }
            } else if (searchQuery.isNotBlank() && !isSearching) {
                // 5. Smart Search Extension Fallback UI
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 16.dp, bottom = 80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(NetflixDarkGrey, RoundedCornerShape(50))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = ZaswixIcons.Search,
                            contentDescription = "No Results",
                            tint = NetflixLightGrey,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "No results found in ${activePlugin?.name ?: "Current Provider"}",
                        color = NetflixWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "We couldn't find \"$searchQuery\" in ${activePlugin?.name ?: "this provider"}. Try searching instead with our other extensions:",
                        color = NetflixLightGrey,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Fallback Buttons for Other Installed Extensions
                    if (otherPlugins.isNotEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            otherPlugins.forEach { otherPlugin ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            pluginManager?.selectPlugin(otherPlugin.id)
                                            searchRefreshTrigger++
                                            Toast.makeText(
                                                context,
                                                "Switched to ${otherPlugin.name}. Searching...",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                    colors = CardDefaults.cardColors(containerColor = NetflixDarkGrey),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .background(NetflixRed.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = ZaswixIcons.Search,
                                                    contentDescription = "Search",
                                                    tint = NetflixRed,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }

                                            Column {
                                                Text(
                                                    text = "Search instead in ${otherPlugin.name}",
                                                    color = NetflixWhite,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "${otherPlugin.category} • ${otherPlugin.description.take(45)}...",
                                                    color = NetflixLightGrey,
                                                    fontSize = 11.sp,
                                                    maxLines = 1
                                                )
                                            }
                                        }

                                        Box(
                                            modifier = Modifier
                                                .background(NetflixRed, RoundedCornerShape(6.dp))
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = "Search",
                                                color = NetflixWhite,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
