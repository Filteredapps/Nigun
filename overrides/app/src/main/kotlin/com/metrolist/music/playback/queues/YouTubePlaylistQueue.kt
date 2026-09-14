/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.utils.filterAllowedArtists
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class YouTubePlaylistQueue(
    private val playlistId: String,
    private val playlistTitle: String? = null,
    private val initialSongs: List<SongItem> = emptyList(),
    private val initialContinuation: String? = null,
    private val startIndex: Int = 0,
    override val preloadItem: MediaMetadata? = null,
) : Queue {
    private var continuation: String? = initialContinuation
    private var retryCount = 0
    private val maxRetries = 3

    override suspend fun getInitialStatus(): Queue.Status {
        return withContext(IO) {
            if (initialSongs.isNotEmpty()) {
                val allowedSongs = YouTube.resolveArtistIds(initialSongs).filterAllowedArtists()
                Queue.Status(
                    title = playlistTitle,
                    items = allowedSongs.map { it.toMediaItem() },
                    mediaItemIndex = com.metrolist.innertube.utils.selectedIndex(allowedSongs, initialSongs.getOrNull(startIndex)?.id) { it.id },
                )
            } else {
                val playlistPage = YouTube.playlist(playlistId).getOrThrow()
                continuation = playlistPage.songsContinuation
                val allowedSongs = YouTube.resolveArtistIds(playlistPage.songs).filterAllowedArtists()
                Queue.Status(
                    title = playlistPage.playlist.title,
                    items = allowedSongs.map { it.toMediaItem() },
                    mediaItemIndex = com.metrolist.innertube.utils.selectedIndex(allowedSongs, playlistPage.songs.getOrNull(startIndex)?.id) { it.id },
                )
            }
        }
    }

    override fun hasNextPage(): Boolean = continuation != null

    override suspend fun nextPage(): List<MediaItem> {
        return withContext(IO) {
            val currentContinuation = continuation ?: return@withContext emptyList()
            var lastException: Throwable? = null
            
            for (attempt in 0..maxRetries) {
                try {
                    val continuationPage = YouTube.playlistContinuation(currentContinuation).getOrThrow()
                    continuation = continuationPage.continuation
                    retryCount = 0
                    return@withContext YouTube.resolveArtistIds(continuationPage.songs)
                        .filterAllowedArtists()
                        .map { it.toMediaItem() }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastException = e
                    retryCount++
                    if (retryCount >= maxRetries) {
                        continuation = null
                    }
                }
            }
            throw lastException ?: Exception("Failed to get next page")
        }
    }
}
