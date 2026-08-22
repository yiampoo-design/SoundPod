package com.github.soundpod.ui.screens.search

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.github.core.ui.ColorPalette
import com.github.core.ui.LocalAppearance
import com.github.innertube.Innertube
import com.github.soundpod.LocalPlayerServiceBinder
import com.github.soundpod.R
import com.github.soundpod.models.LocalMenuState
import com.github.soundpod.ui.appearance.LoadingAnimation
import com.github.soundpod.ui.components.NonQueuedMediaItemMenu
import com.github.soundpod.ui.components.SettingsCard
import com.github.soundpod.ui.items.AlbumItem
import com.github.soundpod.ui.items.ArtistItem
import com.github.soundpod.ui.items.PlaylistItem
import com.github.soundpod.ui.items.SongItem
import com.github.soundpod.ui.items.VideoItem
import com.github.soundpod.utils.SearchDisplayMode
import com.github.soundpod.utils.asMediaItem
import com.github.soundpod.utils.forcePlay
import com.github.soundpod.utils.rememberPreference
import com.github.soundpod.utils.searchResultDisplayModeKey
import com.github.soundpod.utils.thumbnail

@OptIn(ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@UnstableApi
@Composable
fun OnlineSearch(
    songResults: List<Innertube.SongItem>?,
    albumResults: List<Innertube.AlbumItem>?,
    artistResults: List<Innertube.ArtistItem>?,
    videoResults: List<Innertube.VideoItem>?,
    playlistResults: List<Innertube.PlaylistItem>?,
    isLoading: Boolean,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onViewAllClick: (String) -> Unit
) {
    val (colorPalette) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current
    var displayMode by rememberPreference(
        searchResultDisplayModeKey,
        SearchDisplayMode.COMFORTABLE
    )

    LaunchedEffect(songResults) {
        songResults?.take(5)?.map { it.key }?.let { videoIds ->
            binder?.preCacheManager?.preCache(videoIds)
        }
    }

    if (isLoading) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            LoadingAnimation(modifier = Modifier.size(50.dp))
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = "Loading...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else if (displayMode == SearchDisplayMode.COMPACT) {
        CompactSearchResults(
            songResults = songResults,
            albumResults = albumResults,
            artistResults = artistResults,
            videoResults = videoResults,
            playlistResults = playlistResults,
            onAlbumClick = onAlbumClick,
            onArtistClick = onArtistClick,
            onPlaylistClick = onPlaylistClick,
            onViewAllClick = onViewAllClick,
            onToggleDisplayMode = {
                displayMode = SearchDisplayMode.COMFORTABLE
            }
        )
    } else {
        ComfortableSearchResults(
            songResults = songResults,
            albumResults = albumResults,
            artistResults = artistResults,
            videoResults = videoResults,
            playlistResults = playlistResults,
            onAlbumClick = onAlbumClick,
            onArtistClick = onArtistClick,
            onPlaylistClick = onPlaylistClick,
            onViewAllClick = onViewAllClick,
            onToggleDisplayMode = {
                displayMode = SearchDisplayMode.COMPACT
            }
        )
    }
}

@Composable
fun SearchSectionHeader(
    title: String,
    onViewAll: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(end = 88.dp)
        )

        if (onViewAll != null) {
            TextButton(
                onClick = onViewAll,
                modifier = Modifier.align(Alignment.CenterEnd),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    text = stringResource(R.string.view_all),
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun DisplayModeToggle(
    isCompact: Boolean,
    onToggle: () -> Unit,
    colorPalette: ColorPalette
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (isCompact)
                    Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                contentDescription = if (isCompact)
                    "Comfortable view" else "Compact view",
                tint = colorPalette.text.copy(alpha = 0.6f)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@UnstableApi
@Composable
private fun ComfortableSearchResults(
    songResults: List<Innertube.SongItem>?,
    albumResults: List<Innertube.AlbumItem>?,
    artistResults: List<Innertube.ArtistItem>?,
    videoResults: List<Innertube.VideoItem>?,
    playlistResults: List<Innertube.PlaylistItem>?,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onViewAllClick: (String) -> Unit,
    onToggleDisplayMode: () -> Unit
) {
    val (colorPalette) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current

    val songsLabel = stringResource(R.string.songs)
    val videosLabel = stringResource(R.string.videos)
    val albumsLabel = stringResource(R.string.albums)
    val artistsLabel = stringResource(R.string.artists)
    val playlistsLabel = stringResource(R.string.playlists)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            DisplayModeToggle(
                isCompact = false,
                onToggle = onToggleDisplayMode,
                colorPalette = colorPalette
            )
        }

        if (songResults?.isNotEmpty() == true) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SearchSectionHeader(
                    title = songsLabel,
                    onViewAll = { onViewAllClick(songsLabel) }
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SettingsCard {
                    Column {
                        songResults.forEach { song ->
                            SongItem(
                                song = song,
                                onClick = {
                                    binder?.stopRadio()
                                    binder?.player?.forcePlay(song.asMediaItem)
                                    binder?.setupRadio(song.info?.endpoint)
                                },
                                onLongClick = {
                                    menuState.display {
                                        NonQueuedMediaItemMenu(
                                            onDismiss = menuState::hide,
                                            mediaItem = song.asMediaItem,
                                            onGoToAlbum = onAlbumClick,
                                            onGoToArtist = onArtistClick
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        if (videoResults?.isNotEmpty() == true) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SearchSectionHeader(
                    title = videosLabel,
                    onViewAll = { onViewAllClick(videosLabel) }
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SettingsCard {
                    Column {
                        videoResults.forEach { video ->
                            VideoItem(
                                video = video,
                                onClick = {
                                    binder?.stopRadio()
                                    binder?.player?.forcePlay(video.asMediaItem)
                                    binder?.setupRadio(video.info?.endpoint)
                                },
                                onLongClick = {
                                    menuState.display {
                                        NonQueuedMediaItemMenu(
                                            onDismiss = menuState::hide,
                                            mediaItem = video.asMediaItem,
                                            onGoToAlbum = onAlbumClick,
                                            onGoToArtist = onArtistClick
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        if (albumResults?.isNotEmpty() == true) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SearchSectionHeader(
                    title = albumsLabel,
                    onViewAll = { onViewAllClick(albumsLabel) }
                )
            }
            items(albumResults.take(6)) { album ->
                AlbumItem(
                    album = album,
                    onClick = { onAlbumClick(album.key) }
                )
            }
        }

        if (artistResults?.isNotEmpty() == true) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SearchSectionHeader(
                    title = artistsLabel,
                    onViewAll = { onViewAllClick(artistsLabel) }
                )
            }
            items(artistResults.take(6)) { artist ->
                ArtistItem(
                    artist = artist,
                    onClick = { onArtistClick(artist.key) }
                )
            }
        }

        if (playlistResults?.isNotEmpty() == true) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SearchSectionHeader(
                    title = playlistsLabel,
                    onViewAll = { onViewAllClick(playlistsLabel) }
                )
            }
            items(playlistResults.take(6)) { playlist ->
                PlaylistItem(
                    playlist = playlist,
                    onClick = { onPlaylistClick(playlist.key) }
                )
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@UnstableApi
@Composable
private fun CompactSearchResults(
    songResults: List<Innertube.SongItem>?,
    albumResults: List<Innertube.AlbumItem>?,
    artistResults: List<Innertube.ArtistItem>?,
    videoResults: List<Innertube.VideoItem>?,
    playlistResults: List<Innertube.PlaylistItem>?,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onViewAllClick: (String) -> Unit,
    onToggleDisplayMode: () -> Unit
) {
    val (colorPalette) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current

    val songsLabel = stringResource(R.string.songs)
    val videosLabel = stringResource(R.string.videos)
    val albumsLabel = stringResource(R.string.albums)
    val artistsLabel = stringResource(R.string.artists)
    val playlistsLabel = stringResource(R.string.playlists)

    LazyColumn(
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            DisplayModeToggle(
                isCompact = true,
                onToggle = onToggleDisplayMode,
                colorPalette = colorPalette
            )
        }

        if (songResults?.isNotEmpty() == true) {
            item {
                SearchSectionHeader(
                    title = songsLabel,
                    onViewAll = { onViewAllClick(songsLabel) }
                )
            }
            items(songResults.take(8)) { song ->
                CompactSongRow(
                    song = song,
                    onClick = {
                        binder?.stopRadio()
                        binder?.player?.forcePlay(song.asMediaItem)
                        binder?.setupRadio(song.info?.endpoint)
                    },
                    onMenuClick = {
                        menuState.display {
                            NonQueuedMediaItemMenu(
                                onDismiss = menuState::hide,
                                mediaItem = song.asMediaItem,
                                onGoToAlbum = onAlbumClick,
                                onGoToArtist = onArtistClick
                            )
                        }
                    }
                )
            }
        }

        if (videoResults?.isNotEmpty() == true) {
            item {
                SearchSectionHeader(
                    title = videosLabel,
                    onViewAll = { onViewAllClick(videosLabel) }
                )
            }
            items(videoResults.take(6)) { video ->
                CompactVideoRow(
                    video = video,
                    onClick = {
                        binder?.stopRadio()
                        binder?.player?.forcePlay(video.asMediaItem)
                        binder?.setupRadio(video.info?.endpoint)
                    },
                    onMenuClick = {
                        menuState.display {
                            NonQueuedMediaItemMenu(
                                onDismiss = menuState::hide,
                                mediaItem = video.asMediaItem,
                                onGoToAlbum = onAlbumClick,
                                onGoToArtist = onArtistClick
                            )
                        }
                    }
                )
            }
        }

        if (albumResults?.isNotEmpty() == true) {
            item {
                SearchSectionHeader(
                    title = albumsLabel,
                    onViewAll = { onViewAllClick(albumsLabel) }
                )
            }
            items(albumResults.take(8)) { album ->
                CompactAlbumRow(
                    album = album,
                    onClick = { onAlbumClick(album.key) }
                )
            }
        }

        if (artistResults?.isNotEmpty() == true) {
            item {
                SearchSectionHeader(
                    title = artistsLabel,
                    onViewAll = { onViewAllClick(artistsLabel) }
                )
            }
            items(artistResults.take(8)) { artist ->
                CompactArtistRow(
                    artist = artist,
                    onClick = { onArtistClick(artist.key) }
                )
            }
        }

        if (playlistResults?.isNotEmpty() == true) {
            item {
                SearchSectionHeader(
                    title = playlistsLabel,
                    onViewAll = { onViewAllClick(playlistsLabel) }
                )
            }
            items(playlistResults.take(8)) { playlist ->
                CompactPlaylistRow(
                    playlist = playlist,
                    onClick = { onPlaylistClick(playlist.key) }
                )
            }
        }
    }
}

@Composable
private fun CompactSongRow(
    song: Innertube.SongItem,
    onClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = song.info?.name ?: "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = song.authors?.mapNotNull { it.name }?.joinToString(" . ") ?: "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = song.thumbnail?.size(44),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onMenuClick, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "More options",
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun CompactVideoRow(
    video: Innertube.VideoItem,
    onClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = video.info?.name ?: "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = if (video.authors.isNullOrEmpty()) {
                    video.viewsText ?: ""
                } else {
                    "${video.authors?.joinToString(separator = "") { it.name ?: "" }} . ${video.viewsText}"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = video.thumbnail?.url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onMenuClick, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "More options",
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun CompactAlbumRow(
    album: Innertube.AlbumItem,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = album.info?.name ?: "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = listOfNotNull(
                    album.authors?.joinToString(separator = ", ") { it.name ?: "" },
                    album.year
                ).joinToString(separator = " . "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = album.thumbnail?.url?.thumbnail(48),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun CompactArtistRow(
    artist: Innertube.ArtistItem,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = artist.info?.name ?: "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            artist.subscribersCountText?.let { text ->
                Text(
                    text = text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = artist.thumbnail?.url?.thumbnail(48),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun CompactPlaylistRow(
    playlist: Innertube.PlaylistItem,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = playlist.info?.name ?: "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = listOfNotNull(
                    playlist.channel?.name,
                    playlist.songCount?.let {
                        pluralStringResource(id = R.plurals.number_of_songs, count = it, it)
                    }
                ).joinToString(separator = " . "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = playlist.thumbnail?.url?.thumbnail(48),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
