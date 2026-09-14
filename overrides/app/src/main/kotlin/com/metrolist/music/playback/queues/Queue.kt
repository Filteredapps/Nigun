/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.music.extensions.metadata
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.filterAllowedMediaItems

interface Queue {
    val preloadItem: MediaMetadata?

    suspend fun getInitialStatus(): Status

    fun hasNextPage(): Boolean

    suspend fun nextPage(): List<MediaItem>

    data class Status(
        val title: String?,
        val items: List<MediaItem>,
        val mediaItemIndex: Int,
        val position: Long = 0L,
    ) {
        private fun withItems(filtered: List<MediaItem>): Status {
            val selectedId = items.getOrNull(mediaItemIndex)?.mediaId
            val index = com.metrolist.innertube.utils.selectedIndex(filtered, selectedId) { it.mediaId }
            return copy(
                items = filtered,
                mediaItemIndex = index,
                position = if (filtered.getOrNull(index)?.mediaId == selectedId) position else 0L,
            )
        }

        fun filterExplicit(enabled: Boolean = true): Status =
            withItems(items.filterExplicit(enabled))

        fun filterVideoSongs(disableVideos: Boolean = false): Status =
            withItems(items.filterVideoSongs(disableVideos))

        fun filterAllowedContent(): Status = withItems(items.filterAllowedMediaItems())

    }
}

fun List<MediaItem>.filterExplicit(enabled: Boolean = true) =
    if (enabled) {
        filterNot {
            it.metadata?.explicit == true
        }
    } else {
        this
    }

fun List<MediaItem>.filterVideoSongs(disableVideos: Boolean = false) =
    if (disableVideos) {
        filterNot { it.metadata?.isVideoSong == true }
    } else {
        this
    }
