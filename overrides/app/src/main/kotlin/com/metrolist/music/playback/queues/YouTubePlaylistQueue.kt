/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.utils.PlaylistSongPager
import com.metrolist.innertube.utils.YouTubeArtistSearchFilter
import com.metrolist.innertube.utils.filterAllowedArtists
import com.metrolist.innertube.utils.selectedIndex
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class YouTubePlaylistQueue(
    private val playlistId: String,
    private val playlistTitle: String? = null,
    private val initialSongs: List<SongItem> = emptyList(),
    private val initialContinuation: String? = null,
    private val startIndex: Int = 0,
    override val preloadItem: MediaMetadata? = null,
) : Queue {
    private val pager = PlaylistSongPager(
        filter = { songs ->
            val unresolved = songs.filterNot { YouTubeArtistSearchFilter.isAllowed(it) }
            val resolved = YouTube.resolveArtistIds(unresolved).associateBy { it.id }
            songs.map { resolved[it.id] ?: it }.filterAllowedArtists().filterVideoSongs(true)
        },
        fetchNext = { token ->
            withTimeout(30_000L) { YouTube.playlistContinuation(token).getOrThrow() }
        },
    )
    private var deliveredIds = emptySet<String>()

    override suspend fun getInitialStatus(): Queue.Status = withContext(IO) {
        val title: String?
        val selectedId: String?
        if (initialSongs.isNotEmpty()) {
            title = playlistTitle
            selectedId = initialSongs.getOrNull(startIndex)?.id
            pager.start(initialSongs, initialContinuation)
        } else {
            val page = withTimeout(30_000L) { YouTube.playlist(playlistId).getOrThrow() }
            title = page.playlist.title
            selectedId = page.songs.getOrNull(startIndex)?.id
            pager.start(page.songs, page.songsContinuation)
        }
        while (pager.songs.isEmpty() && pager.continuation != null) pager.loadNext()
        deliveredIds = pager.songs.map { it.id }.toSet()
        Queue.Status(
            title = title,
            items = pager.songs.map { it.toMediaItem() },
            mediaItemIndex = selectedIndex(pager.songs, selectedId) { it.id },
        )
    }

    override fun hasNextPage(): Boolean = pager.continuation != null

    override suspend fun nextPage(): List<MediaItem> = withContext(IO) {
        var added = pager.songs.filterNot { it.id in deliveredIds }
        while (added.isEmpty() && pager.continuation != null) {
            pager.loadNext()
            added = pager.songs.filterNot { it.id in deliveredIds }
        }
        deliveredIds = deliveredIds + added.map { it.id }
        added.map { it.toMediaItem() }
    }
}
