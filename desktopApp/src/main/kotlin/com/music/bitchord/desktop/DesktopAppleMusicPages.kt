package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.sharedui.resources.Res as SharedRes
import com.music.bitchord.sharedui.resources.spotify_logo
import com.music.bitchord.ui.icons.ChordWaveIcons
import com.music.bitchord.ui.replay.ReplayCreditCard
import com.music.bitchord.ui.screens.MOOD_CARD_ASPECT
import com.music.bitchord.ui.screens.MoodGenreSleeve
import org.jetbrains.compose.resources.painterResource

/**
 * The Apple Music-style library pages: Radio, Recently Added, Artists, Albums, Songs (table),
 * Made for You and All Playlists. Every page reads real library data — the account's library,
 * local files and downloads — and nothing else.
 */

/** The account's library, local files and downloads as one de-duplicated song list. */
internal fun desktopLibraryUnion(
    page: LibraryPage?,
    localSongs: List<Song>,
    downloads: List<Song>,
): List<Song> {
    val seen = HashSet<String>()
    val out = ArrayList<Song>()
    fun add(song: Song) {
        if (seen.add(song.videoId)) out.add(song)
    }
    page?.librarySongs?.forEach(::add)
    page?.likedSongs?.forEach(::add)
    localSongs.forEach(::add)
    downloads.forEach(::add)
    return out
}

/** One album in the library, as the grids group it. */
internal data class DesktopAlbumGroup(
    val key: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val songs: List<Song>,
    /** The newest date-added among its tracks, 0 when no track carries one. */
    val latestAddedSeconds: Long,
)

internal fun List<Song>.desktopAlbumGroups(): List<DesktopAlbumGroup> {
    return groupBy { song ->
        song.albumId ?: ("name:" + (song.albumName?.takeIf(String::isNotBlank) ?: "Unknown Album"))
    }.map { (key, songs) ->
        val artists = songs.map { it.artist }.filter(String::isNotBlank).distinct()
        DesktopAlbumGroup(
            key = key,
            title = songs.firstNotNullOfOrNull { it.albumName?.takeIf(String::isNotBlank) } ?: "Unknown Album",
            artist = when (artists.size) {
                0 -> "Unknown Artist"
                1 -> artists.single()
                else -> "Various Artists"
            },
            thumbnailUrl = songs.firstOrNull()?.thumbnailUrl,
            songs = songs,
            latestAddedSeconds = songs.mapNotNull { it.localDateAddedSeconds }.maxOrNull() ?: 0L,
        )
    }
}

/** One artist in the library, as the Artists page groups it. */
internal data class DesktopArtistGroup(
    val name: String,
    val artistId: String?,
    val thumbnailUrl: String?,
    val songs: List<Song>,
)

internal fun List<Song>.desktopArtistGroups(): List<DesktopArtistGroup> {
    return groupBy { it.artist.ifBlank { "Unknown Artist" } }
        .map { (name, songs) ->
            DesktopArtistGroup(
                name = name,
                artistId = songs.firstNotNullOfOrNull { it.artistId },
                thumbnailUrl = songs.firstOrNull()?.thumbnailUrl,
                songs = songs,
            )
        }
        .sortedBy { it.name.lowercase() }
}

/** A page's big title, Apple Music style. */
@Composable
private fun DesktopPageTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.displayLarge,
        color = DesktopOnBackground,
        modifier = modifier.padding(
            start = DesktopPageGutter,
            end = DesktopPageGutter,
            top = 8.dp,
            bottom = 14.dp,
        ),
    )
}

/** A round avatar: the artwork, or an initial on a colour drawn from the name. */
@Composable
internal fun DesktopInitialAvatar(name: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "♪"
    val color = remember(name) {
        when ((name.hashCode() and Int.MAX_VALUE) % 8) {
            0 -> Color(0xFFCC6A55)
            1 -> Color(0xFFC07A92)
            2 -> Color(0xFF9C8AC0)
            3 -> Color(0xFF8090C8)
            4 -> Color(0xFFD0A060)
            5 -> Color(0xFF6A88B0)
            6 -> Color(0xFF7AAED0)
            else -> Color(0xFF86B890)
        }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun DesktopArtistAvatar(thumbnailUrl: String?, name: String, size: androidx.compose.ui.unit.Dp) {
    if (thumbnailUrl != null) {
        DesktopArtwork(
            thumbnailUrl,
            Modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop,
            px = ROW_ART_PX,
        )
    } else {
        DesktopInitialAvatar(name, size)
    }
}

/**
 * Radio, built from the mood/genre feed: there is no live-radio source, so the moods and genres
 * are the stations — the wide cards up top, the full grid below. Tapping one opens its shelves.
 */
@Composable
internal fun DesktopRadioPage(
    state: UiState<List<MoodGenreSection>>,
    onCategoryClick: (MoodGenre) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
) {
    DesktopPageScaffold(contentPadding) {
        when (state) {
            is UiState.Loading -> DesktopLoadingPage("Loading stations…")
            is UiState.Error -> DesktopErrorPage(state.message, onRetry)
            is UiState.Success -> {
                val categories = remember(state.data) {
                    state.data.flatMap(MoodGenreSection::items)
                        .distinctBy { it.browseId to it.params }
                }
                if (categories.isEmpty()) {
                    DesktopEmptyPage(
                        ChordWaveIcons.MusicNote,
                        "No stations yet",
                        "Moods and genres will appear here once Explore loads.",
                    )
                    return@DesktopPageScaffold
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val columns = ((maxWidth - DesktopPageGutter * 2 + 12.dp) / (220.dp + 12.dp))
                        .toInt().coerceIn(2, 6)
                    val wide = categories.take(3)
                    val rest = categories.drop(3).chunked(columns)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = pagePadding(bottom = 32.dp),
                    ) {
                        item { DesktopPageTitle("Radio") }
                        item {
                            SectionTitle("Hear Nonstop Music")
                            Spacer(Modifier.height(10.dp))
                        }
                        item {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(horizontal = DesktopPageGutter),
                            ) {
                                wide.forEach { item ->
                                    MoodGenreSleeve(
                                        item = item,
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1.9f)
                                            .clip(RoundedCornerShape(18.dp))
                                            .clickable { onCategoryClick(item) },
                                    )
                                }
                                repeat(3 - wide.size) { Spacer(Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(22.dp))
                        }
                        item {
                            SectionTitle("Stations")
                            Spacer(Modifier.height(10.dp))
                        }
                        items(rest, key = { row -> row.first().let { "${it.browseId}|${it.params}" } }) { row ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .padding(horizontal = DesktopPageGutter)
                                    .padding(bottom = 12.dp),
                            ) {
                                row.forEach { item ->
                                    MoodGenreSleeve(
                                        item = item,
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(MOOD_CARD_ASPECT)
                                            .clip(RoundedCornerShape(18.dp))
                                            .clickable { onCategoryClick(item) },
                                    )
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Recently Added: the library's albums, newest first — dated tracks by date, the rest in library order. */
@Composable
internal fun DesktopRecentlyAddedPage(
    songs: List<Song>,
    signedIn: Boolean,
    onSignIn: () -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    contentPadding: PaddingValues,
) {
    DesktopPageScaffold(contentPadding) {
        if (songs.isEmpty()) {
            Column(Modifier.fillMaxSize()) {
                DesktopPageTitle("Recently Added")
                if (signedIn) {
                    DesktopEmptyPage(
                        ChordWaveIcons.MusicNote,
                        "Nothing added yet",
                        "Songs you like or save will appear here.",
                    )
                } else {
                    DesktopEmptyPage(
                        ChordWaveIcons.MusicNote,
                        "Nothing added yet",
                        "Sign in to see the music in your library, or add local files.",
                        onSignIn,
                        "Sign in",
                    )
                }
            }
            return@DesktopPageScaffold
        }
        val albums = remember(songs) {
            songs.desktopAlbumGroups().sortedWith(
                compareByDescending<DesktopAlbumGroup> { it.latestAddedSeconds }
                    .thenBy { it.title.lowercase() },
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 170.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = pagePadding(
                start = DesktopPageGutter,
                end = DesktopPageGutter,
                top = 8.dp,
                bottom = 32.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Recently Added",
                    style = MaterialTheme.typography.displayLarge,
                    color = DesktopOnBackground,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            gridItems(albums, key = { it.key }) { album ->
                DesktopAlbumGridCard(
                    album = album,
                    onClick = { onPlaySongs(album.songs, 0) },
                )
            }
        }
    }
}

@Composable
private fun DesktopAlbumGridCard(album: DesktopAlbumGroup, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center,
        ) {
            if (album.thumbnailUrl != null) {
                DesktopArtwork(album.thumbnailUrl, Modifier.fillMaxSize(), px = ROW_ART_PX)
            } else {
                Icon(
                    Icons.Rounded.Album,
                    null,
                    tint = DesktopSecondary,
                    modifier = Modifier.fillMaxSize(0.4f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            album.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium,
            color = DesktopOnBackground,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            album.artist,
            color = DesktopSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Artists: the master-detail view — a list on the left, the selected artist on the right. */
@Composable
internal fun DesktopArtistsPage(
    songs: List<Song>,
    likedIds: Set<String>,
    onToggleLike: (Song) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onOpenArtist: (browseId: String?, name: String) -> Unit,
    contentPadding: PaddingValues,
) {
    val artists = remember(songs) { songs.desktopArtistGroups() }
    var selected by remember { mutableStateOf<String?>(null) }
    DesktopPageScaffold(contentPadding) {
        if (artists.isEmpty()) {
            Column(Modifier.fillMaxSize()) {
                DesktopPageTitle("Artists")
                DesktopEmptyPage(
                    Icons.Rounded.Mic,
                    "No artists yet",
                    "Artists from the music you like or save will appear here.",
                )
            }
            return@DesktopPageScaffold
        }
        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .padding(end = 12.dp),
                contentPadding = pagePadding(top = 0.dp, bottom = 32.dp),
            ) {
                item {
                    Text(
                        text = "Artists",
                        style = MaterialTheme.typography.displayLarge,
                        color = DesktopOnBackground,
                        modifier = Modifier.padding(
                            start = DesktopPageGutter,
                            top = 8.dp,
                            bottom = 14.dp,
                        ),
                    )
                }
                item {
                    DesktopArtistListRow(
                        name = "All Artists",
                        countText = "${grouped(artists.size.toLong())} artists",
                        artworkUrl = null,
                        selected = selected == ALL_ARTISTS_KEY,
                        onClick = { selected = ALL_ARTISTS_KEY },
                    )
                }
                items(artists, key = { it.name }) { artist ->
                    DesktopArtistListRow(
                        name = artist.name,
                        countText = "${grouped(artist.songs.size.toLong())} songs",
                        artworkUrl = artist.thumbnailUrl,
                        selected = selected == artist.name,
                        onClick = { selected = artist.name },
                    )
                }
            }
            VerticalDivider(
                modifier = Modifier.fillMaxHeight().padding(vertical = 12.dp),
                thickness = 0.5.dp,
                color = DesktopDivider,
            )
            when (val key = selected) {
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.Mic,
                            null,
                            tint = DesktopSecondary,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Select an Artist",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = DesktopOnBackground,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Pick an artist to see their songs.",
                            color = DesktopSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                ALL_ARTISTS_KEY -> DesktopArtistDetail(
                    name = "All Artists",
                    thumbnailUrl = null,
                    songs = songs,
                    artistId = null,
                    likedIds = likedIds,
                    onToggleLike = onToggleLike,
                    onPlaySongs = onPlaySongs,
                    onOpenArtist = onOpenArtist,
                )

                else -> {
                    val artist = artists.firstOrNull { it.name == key }
                    if (artist == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Select an Artist", color = DesktopSecondary)
                        }
                    } else {
                        DesktopArtistDetail(
                            name = artist.name,
                            thumbnailUrl = artist.thumbnailUrl,
                            songs = artist.songs,
                            artistId = artist.artistId,
                            likedIds = likedIds,
                            onToggleLike = onToggleLike,
                            onPlaySongs = onPlaySongs,
                            onOpenArtist = onOpenArtist,
                        )
                    }
                }
            }
        }
    }
}

private const val ALL_ARTISTS_KEY = "\u0000all-artists"

@Composable
private fun DesktopArtistListRow(
    name: String,
    countText: String,
    artworkUrl: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DesktopPageGutter, end = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color.Black.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (artworkUrl != null) {
            DesktopArtistAvatar(artworkUrl, name, 42.dp)
        } else {
            DesktopInitialAvatar(name, 42.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) DesktopAccent else DesktopOnBackground,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(countText, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DesktopArtistDetail(
    name: String,
    thumbnailUrl: String?,
    songs: List<Song>,
    artistId: String?,
    likedIds: Set<String>,
    onToggleLike: (Song) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onOpenArtist: (browseId: String?, name: String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = pagePadding(bottom = 32.dp),
    ) {
        item(key = "detail-header") {
            Column(Modifier.padding(start = DesktopPageGutter, end = DesktopPageGutter, top = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (thumbnailUrl != null) {
                        DesktopArtistAvatar(thumbnailUrl, name, 76.dp)
                    } else {
                        DesktopInitialAvatar(name, 76.dp)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            name,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = DesktopOnBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${grouped(songs.size.toLong())} songs",
                            color = DesktopSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DesktopActionButton(
                        label = "Play",
                        icon = Icons.Rounded.PlayArrow,
                        onClick = { if (songs.isNotEmpty()) onPlaySongs(songs, 0) },
                    )
                    DesktopActionButton(
                        label = "Shuffle",
                        icon = ChordWaveIcons.Shuffle,
                        onClick = {
                            if (songs.isNotEmpty()) onPlaySongs(songs.shuffled(), 0)
                        },
                    )
                    if (artistId != null) {
                        TextButton(onClick = { onOpenArtist(artistId, name) }) {
                            Text("Open Artist Page", color = DesktopAccent)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        itemsIndexed(songs, key = { _, song -> song.videoId }) { index, song ->
            DesktopSongRow(
                song = song,
                isCurrent = null,
                liked = song.videoId in likedIds,
                onClick = { onPlaySongs(songs, index) },
                onToggleLike = onToggleLike,
            )
        }
    }
}

/** Albums: a cover grid that opens the album's songs in place. */
@Composable
internal fun DesktopAlbumsPage(
    songs: List<Song>,
    likedIds: Set<String>,
    onToggleLike: (Song) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onOpenAlbum: (albumId: String) -> Unit,
    contentPadding: PaddingValues,
) {
    val albums = remember(songs) {
        songs.desktopAlbumGroups().sortedBy { it.title.lowercase() }
    }
    var openedKey by remember { mutableStateOf<String?>(null) }
    val opened = openedKey?.let { key -> albums.firstOrNull { it.key == key } }
    DesktopPageScaffold(contentPadding) {
        if (albums.isEmpty()) {
            Column(Modifier.fillMaxSize()) {
                DesktopPageTitle("Albums")
                DesktopEmptyPage(
                    Icons.Rounded.Album,
                    "No albums yet",
                    "Albums from the music you like or save will appear here.",
                )
            }
            return@DesktopPageScaffold
        }
        if (opened == null) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 170.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = pagePadding(
                    start = DesktopPageGutter,
                    end = DesktopPageGutter,
                    top = 8.dp,
                    bottom = 32.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "Albums",
                        style = MaterialTheme.typography.displayLarge,
                        color = DesktopOnBackground,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                gridItems(albums, key = { it.key }) { album ->
                    DesktopAlbumGridCard(album = album, onClick = { openedKey = album.key })
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = pagePadding(bottom = 32.dp),
            ) {
                item(key = "album-header") {
                    Column(Modifier.padding(horizontal = DesktopPageGutter, vertical = 12.dp)) {
                        TextButton(onClick = { openedKey = null }) {
                            Text("‹ All Albums", color = DesktopAccent)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(120.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black.copy(alpha = 0.05f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (opened.thumbnailUrl != null) {
                                    DesktopArtwork(opened.thumbnailUrl, Modifier.fillMaxSize(), px = ROW_ART_PX)
                                } else {
                                    Icon(
                                        Icons.Rounded.Album,
                                        null,
                                        tint = DesktopSecondary,
                                        modifier = Modifier.fillMaxSize(0.4f),
                                    )
                                }
                            }
                            Spacer(Modifier.width(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    opened.title,
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = DesktopOnBackground,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(opened.artist, color = DesktopSecondary)
                                Text(
                                    "${grouped(opened.songs.size.toLong())} songs",
                                    color = DesktopSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            DesktopActionButton(
                                label = "Play",
                                icon = Icons.Rounded.PlayArrow,
                                onClick = { onPlaySongs(opened.songs, 0) },
                            )
                            DesktopActionButton(
                                label = "Shuffle",
                                icon = ChordWaveIcons.Shuffle,
                                onClick = { onPlaySongs(opened.songs.shuffled(), 0) },
                            )
                            val albumId = opened.songs.firstNotNullOfOrNull { it.albumId }
                            if (albumId != null) {
                                TextButton(onClick = { onOpenAlbum(albumId) }) {
                                    Text("Open Album Page", color = DesktopAccent)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
                itemsIndexed(opened.songs, key = { _, song -> song.videoId }) { index, song ->
                    DesktopSongRow(
                        song = song,
                        isCurrent = null,
                        liked = song.videoId in likedIds,
                        onClick = { onPlaySongs(opened.songs, index) },
                        onToggleLike = onToggleLike,
                    )
                }
            }
        }
    }
}

/** Songs: the Apple Music table — sortable Title / Time / Artist / Album columns, plus a star. */
@Composable
internal fun DesktopSongsTablePage(
    songs: List<Song>,
    currentSongId: String?,
    likedIds: Set<String>,
    onToggleLike: (Song) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    menu: (@Composable (Song) -> Unit)? = null,
    contentPadding: PaddingValues,
) {
    var query by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf(DesktopSongSort.TITLE) }
    var ascending by remember { mutableStateOf(true) }
    val visible = remember(songs, query, sortBy, ascending) {
        val filtered = if (query.isBlank()) {
            songs
        } else {
            songs.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.artist.contains(query, ignoreCase = true) ||
                    (it.albumName?.contains(query, ignoreCase = true) == true)
            }
        }
        val comparator = when (sortBy) {
            DesktopSongSort.TITLE -> compareBy<Song> { it.title.lowercase() }
            DesktopSongSort.ARTIST -> compareBy<Song> { it.artist.lowercase() }
            DesktopSongSort.ALBUM -> compareBy<Song> { (it.albumName ?: "").lowercase() }
            DesktopSongSort.TIME -> compareBy<Song> { it.durationText.durationMillis() }
        }
        if (ascending) filtered.sortedWith(comparator) else filtered.sortedWith(comparator.reversed())
    }
    DesktopPageScaffold(contentPadding) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = DesktopPageGutter,
                        end = DesktopPageGutter,
                        top = 8.dp,
                        bottom = 10.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Songs",
                    style = MaterialTheme.typography.displayLarge,
                    color = DesktopOnBackground,
                )
                Spacer(Modifier.weight(1f))
                DesktopSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {},
                    modifier = Modifier.width(260.dp),
                )
            }
            if (songs.isEmpty()) {
                DesktopEmptyPage(
                    Icons.Rounded.MusicNote,
                    "No songs yet",
                    "Songs you like or save will appear here.",
                )
                return@DesktopPageScaffold
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DesktopPageGutter)
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DesktopSortHeader(
                    label = "Title",
                    active = sortBy == DesktopSongSort.TITLE,
                    ascending = ascending,
                    modifier = Modifier.weight(3f),
                    onClick = {
                        if (sortBy == DesktopSongSort.TITLE) ascending = !ascending
                        else {
                            sortBy = DesktopSongSort.TITLE
                            ascending = true
                        }
                    },
                )
                DesktopSortHeader(
                    label = "Time",
                    active = sortBy == DesktopSongSort.TIME,
                    ascending = ascending,
                    modifier = Modifier.width(72.dp),
                    onClick = {
                        if (sortBy == DesktopSongSort.TIME) ascending = !ascending
                        else {
                            sortBy = DesktopSongSort.TIME
                            ascending = true
                        }
                    },
                )
                DesktopSortHeader(
                    label = "Artist",
                    active = sortBy == DesktopSongSort.ARTIST,
                    ascending = ascending,
                    modifier = Modifier.weight(1.6f),
                    onClick = {
                        if (sortBy == DesktopSongSort.ARTIST) ascending = !ascending
                        else {
                            sortBy = DesktopSongSort.ARTIST
                            ascending = true
                        }
                    },
                )
                DesktopSortHeader(
                    label = "Album",
                    active = sortBy == DesktopSongSort.ALBUM,
                    ascending = ascending,
                    modifier = Modifier.weight(1.6f),
                    onClick = {
                        if (sortBy == DesktopSongSort.ALBUM) ascending = !ascending
                        else {
                            sortBy = DesktopSongSort.ALBUM
                            ascending = true
                        }
                    },
                )
                Spacer(Modifier.width(40.dp))
                Spacer(Modifier.width(40.dp))
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = DesktopPageGutter),
                thickness = 0.5.dp,
                color = DesktopDivider,
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = pagePadding(top = 0.dp, bottom = 32.dp),
            ) {
                itemsIndexed(visible, key = { _, song -> song.videoId }) { index, song ->
                    val isCurrent = song.videoId == currentSongId
                    val liked = song.videoId in likedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (index % 2 == 1) Color.Black.copy(alpha = 0.03f)
                                else Color.Transparent,
                            )
                            .clickable { onPlaySongs(visible, index) }
                            .padding(horizontal = DesktopPageGutter, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.weight(3f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black.copy(alpha = 0.05f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (song.thumbnailUrl != null) {
                                    DesktopArtwork(song.thumbnailUrl, Modifier.fillMaxSize(), px = ROW_ART_PX)
                                } else {
                                    Icon(
                                        Icons.Rounded.MusicNote,
                                        null,
                                        tint = DesktopSecondary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                song.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isCurrent) DesktopAccent else DesktopOnBackground,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            song.durationText ?: "",
                            color = DesktopSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(72.dp),
                        )
                        Text(
                            song.artist.ifBlank { "Unknown Artist" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = DesktopOnBackground,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1.6f),
                        )
                        Text(
                            song.albumName ?: "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = DesktopOnBackground,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1.6f),
                        )
                        IconButton(
                            onClick = { onToggleLike(song) },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                if (liked) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                null,
                                tint = if (liked) DesktopAccent else DesktopSecondary.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                            menu?.invoke(song)
                        }
                    }
                }
            }
        }
    }
}

internal enum class DesktopSongSort { TITLE, TIME, ARTIST, ALBUM }

@Composable
private fun DesktopSortHeader(
    label: String,
    active: Boolean,
    ascending: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = if (active) DesktopAccent else DesktopSecondary,
        )
        if (active) {
            Spacer(Modifier.width(2.dp))
            Icon(
                if (ascending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                null,
                tint = DesktopAccent,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** Made for You: the listening summary — Replay cards, top songs and top artists. */
@Composable
internal fun DesktopMadeForYouPage(
    summary: DesktopReplaySummary,
    holder: String,
    onOpenReplay: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onOpenArtist: (browseId: String?, name: String) -> Unit,
    contentPadding: PaddingValues,
) {
    DesktopPageScaffold(contentPadding) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = pagePadding(bottom = 32.dp),
        ) {
            item { DesktopPageTitle("Made for You") }
            if (summary.isEmpty) {
                item {
                    DesktopEmptyPage(
                        Icons.Rounded.AccountCircle,
                        "Your Replay is warming up",
                        "Listen to a few songs and your personal picks will appear here.",
                        onOpenReplay,
                        "View Replay",
                    )
                }
            } else {
                val cards = summary.heroCards()
            if (cards.isNotEmpty()) {
                item {
                    SectionTitle("Your Replay")
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = DesktopPageGutter),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(cards) { card ->
                            ReplayCreditCard(
                                label = card.label,
                                value = card.value,
                                detail = card.detail,
                                artworkUrl = card.artworkUrl,
                                holder = holder,
                                memberSince = summary.memberSince(),
                                onClick = onOpenReplay,
                                modifier = Modifier.width(300.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            val topSongs = summary.songs.take(10)
            if (topSongs.isNotEmpty()) {
                item {
                    SectionTitle("Top Songs")
                    Spacer(Modifier.height(6.dp))
                }
                itemsIndexed(topSongs, key = { _, entry -> entry.song.videoId }) { index, entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DesktopPageGutter)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPlaySong(entry.song) }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            color = DesktopSecondary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(28.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.05f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (entry.song.thumbnailUrl != null) {
                                DesktopArtwork(entry.song.thumbnailUrl, Modifier.fillMaxSize(), px = ROW_ART_PX)
                            } else {
                                Icon(
                                    Icons.Rounded.MusicNote,
                                    null,
                                    tint = DesktopSecondary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.song.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium,
                                color = DesktopOnBackground,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                entry.song.artist,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = DesktopSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            "${grouped(entry.plays.toLong())} plays",
                            color = DesktopSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
            val topArtists = summary.artists.take(12)
            if (topArtists.isNotEmpty()) {
                item {
                    SectionTitle("Top Artists")
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = DesktopPageGutter),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(topArtists, key = { it.title }) { artist ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(84.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onOpenArtist(artist.browseId, artist.title) }
                                    .padding(vertical = 4.dp),
                            ) {
                                DesktopArtistAvatar(artist.artworkUrl, artist.title, 76.dp)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    artist.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    color = DesktopOnBackground,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            }
        }
    }
}

/** All Playlists: every account playlist, every local playlist, and Spotify's library when linked. */
@Composable
internal fun DesktopPlaylistsPage(
    accountPlaylists: List<ShelfItem>,
    localPlaylists: List<DesktopPlaylist>,
    spotifyConnected: Boolean,
    onOpenAccountPlaylist: (ShelfItem) -> Unit,
    onOpenLocalPlaylist: (DesktopPlaylist) -> Unit,
    onOpenSpotifyLibrary: () -> Unit,
    onNewPlaylist: () -> Unit,
    contentPadding: PaddingValues,
) {
    DesktopPageScaffold(contentPadding) {
        if (accountPlaylists.isEmpty() && localPlaylists.isEmpty() && !spotifyConnected) {
            Column(Modifier.fillMaxSize()) {
                DesktopPageTitle("All Playlists")
                DesktopEmptyPage(
                    ChordWaveIcons.GridView,
                    "No playlists yet",
                    "Create one to collect the songs you love.",
                    onNewPlaylist,
                    "New Playlist",
                )
            }
            return@DesktopPageScaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 170.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = pagePadding(
                start = DesktopPageGutter,
                end = DesktopPageGutter,
                top = 8.dp,
                bottom = 32.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "All Playlists",
                    style = MaterialTheme.typography.displayLarge,
                    color = DesktopOnBackground,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onNewPlaylist),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = 0.05f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            null,
                            tint = DesktopAccent,
                            modifier = Modifier.fillMaxSize(0.32f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "New Playlist",
                        fontWeight = FontWeight.Medium,
                        color = DesktopOnBackground,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Create a playlist",
                        color = DesktopSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (spotifyConnected) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onOpenSpotifyLibrary),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF191414)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(SharedRes.drawable.spotify_logo),
                                null,
                                tint = Color.Unspecified,
                                modifier = Modifier.fillMaxSize(0.4f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Spotify Library",
                            fontWeight = FontWeight.Medium,
                            color = DesktopOnBackground,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Your Spotify playlists",
                            color = DesktopSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            gridItems(accountPlaylists, key = { it.browseId ?: it.title }) { item ->
                DesktopPlaylistGridCard(
                    thumbnailUrl = item.thumbnailUrl,
                    title = item.title,
                    subtitle = item.subtitle,
                    onClick = { onOpenAccountPlaylist(item) },
                )
            }
            gridItems(localPlaylists, key = { "local:${it.id}" }) { playlist ->
                DesktopPlaylistGridCard(
                    thumbnailUrl = playlist.songs.firstOrNull()?.thumbnailUrl,
                    title = playlist.title,
                    subtitle = "${grouped(playlist.songs.size.toLong())} songs",
                    onClick = { onOpenLocalPlaylist(playlist) },
                )
            }
        }
    }
}

@Composable
private fun DesktopPlaylistGridCard(
    thumbnailUrl: String?,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnailUrl != null) {
                DesktopArtwork(thumbnailUrl, Modifier.fillMaxSize(), px = ROW_ART_PX)
            } else {
                Icon(
                    Icons.Rounded.MusicNote,
                    null,
                    tint = DesktopSecondary,
                    modifier = Modifier.fillMaxSize(0.36f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium,
            color = DesktopOnBackground,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                color = DesktopSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
