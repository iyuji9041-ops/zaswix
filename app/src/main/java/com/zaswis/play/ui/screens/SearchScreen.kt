package com.zaswis.play.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import com.zaswis.play.ui.components.ZaswixIcons
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaswis.play.data.model.MediaItem
import com.zaswis.play.data.repository.MediaRepository
import com.zaswis.play.ui.components.MediaCard
import com.zaswis.play.ui.theme.NetflixBlack
import com.zaswis.play.ui.theme.NetflixDarkGrey
import com.zaswis.play.ui.theme.NetflixLightGrey
import com.zaswis.play.ui.theme.NetflixRed
import com.zaswis.play.ui.theme.NetflixWhite

import androidx.compose.runtime.LaunchedEffect

@Composable
fun SearchScreen(
    mediaRepository: MediaRepository,
    onMediaClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    val allItems by mediaRepository.getAllSearchItems().collectAsState(initial = emptyList())
    var liveResults by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

    LaunchedEffect(searchQuery) {
        if (searchQuery.trim().length >= 3) {
            val live = mediaRepository.searchLivePlugins(searchQuery.trim())
            liveResults = live
        } else {
            liveResults = emptyList()
        }
    }

    val filteredItems = remember(searchQuery, allItems, liveResults) {
        if (searchQuery.isBlank()) {
            allItems
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
            // Search Input Box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text("Search movies, series, anime, genres...", color = NetflixLightGrey)
                },
                leadingIcon = {
                    Icon(
                        imageVector = ZaswixIcons.Search,
                        contentDescription = "Search",
                        tint = NetflixLightGrey
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = ZaswixIcons.Clear,
                                contentDescription = "Clear",
                                tint = NetflixLightGrey
                            )
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
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

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (searchQuery.isEmpty()) "Top Searches & Popular" else "Search Results (${filteredItems.size})",
                color = NetflixWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

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
        }
    }
}
