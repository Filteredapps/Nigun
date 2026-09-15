package com.metrolist.innertube.utils

import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.PlaylistContinuationPage
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** One ordered, deduplicated playlist, retaining its cursor when a request fails. */
class PlaylistSongPager(
    private val filter: suspend (List<SongItem>) -> List<SongItem>,
    private val fetchNext: suspend (String) -> PlaylistContinuationPage,
) {
    var songs: List<SongItem> = emptyList()
        private set
    var continuation: String? = null
        private set
    private val seen = mutableSetOf<String>()

    suspend fun start(items: List<SongItem>, next: String?) {
        val allowed = filter(items).distinctBy { it.id }
        coroutineContext.ensureActive()
        songs = allowed
        continuation = next?.takeIf { it.isNotBlank() }
        seen.clear()
    }

    suspend fun loadNext(): Boolean {
        val token = continuation ?: return false
        if (token in seen) {
            continuation = null
            return false
        }
        val page = fetchNext(token)
        val allowed = filter(page.songs)
        coroutineContext.ensureActive()
        seen.add(token)
        songs = (songs + allowed).distinctBy { it.id }
        continuation = page.continuation?.takeIf { it.isNotBlank() && it !in seen }
        return true
    }
}
