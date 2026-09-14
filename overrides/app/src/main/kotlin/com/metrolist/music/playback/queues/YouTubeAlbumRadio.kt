/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.utils.filterAllowedArtists
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class YouTubeAlbumRadio(
    private var playlistId: String,
) : Queue {
    override val preloadItem: MediaMetadata? = null

    private val endpoint: WatchEndpoint
        get() = WatchEndpoint(
            playlistId = playlistId
        )

    private var albumSongIds = emptySet<String>()
    private var continuation: String? = null
    private var firstTimeLoaded: Boolean = false

    override suspend fun getInitialStatus(): Queue.Status = withContext(IO) {
        val albumSongs = YouTube.albumSongs(playlistId).getOrThrow()
        albumSongIds = albumSongs.map { it.id }.toSet()
        val allowedAlbumSongs = YouTube.resolveArtistIds(albumSongs).filterAllowedArtists()
        Queue.Status(
            title = albumSongs.firstOrNull()?.album?.name.orEmpty(),
            items = allowedAlbumSongs.map { it.toMediaItem() },
            mediaItemIndex = 0
        )
    }

    override fun hasNextPage(): Boolean = !firstTimeLoaded || continuation != null

    override suspend fun nextPage(): List<MediaItem> = withContext(IO) {
        val nextResult = YouTube.next(endpoint, continuation).getOrThrow()
        continuation = nextResult.continuation
        if (!firstTimeLoaded) {
            firstTimeLoaded = true
            nextResult.items.filterNot { it.id in albumSongIds }.filterAllowedArtists().map { it.toMediaItem() }
        } else {
            nextResult.items.filterAllowedArtists().map { it.toMediaItem() }
        }
    }
}
