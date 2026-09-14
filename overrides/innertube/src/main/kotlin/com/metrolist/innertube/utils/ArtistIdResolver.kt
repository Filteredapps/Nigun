package com.metrolist.innertube.utils

import com.metrolist.innertube.models.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/** Bounded, cancellable metadata enrichment; existing IDs are never replaced. */
class ArtistIdResolver(
    private val timeoutMillis: Long = 8_000L,
    private val lookup: suspend (String) -> String?,
) {
    private data class CachedId(val id: String, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, CachedId>()
    private val requests = Semaphore(4)

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : YTItem> resolve(items: List<T>): List<T> {
        fun artists(item: YTItem): List<Artist> = when (item) {
            is SongItem -> item.artists
            is AlbumItem -> item.artists.orEmpty()
            is PlaylistItem -> listOfNotNull(item.author)
            is PodcastItem -> listOfNotNull(item.author)
            is EpisodeItem -> listOfNotNull(item.author)
            else -> emptyList()
        }
        val names = items.flatMap(::artists)
            .filter { it.id.isNullOrBlank() }.map { it.name.trim() }
            .filter(String::isNotEmpty).distinct()
        if (names.isEmpty()) return items
        val now = System.currentTimeMillis()
        cache.entries.removeIf { it.value.expiresAt <= now }
        if (cache.size > 2_048) cache.clear()
        // Successful results survive a slow sibling request; errors are never cached as missing artists.
        withTimeoutOrNull(timeoutMillis) {
            coroutineScope {
                names.filter { cache[it] == null }.map { name ->
                    async {
                        requests.withPermit {
                            val id = lookup(name)
                            coroutineContext.ensureActive()
                            if (!id.isNullOrBlank()) {
                                cache[name] = CachedId(id, System.currentTimeMillis() + 6 * 60 * 60 * 1_000L)
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        coroutineContext.ensureActive()
        fun Artist.resolved() = if (id.isNullOrBlank()) {
            cache[name.trim()]?.let { copy(id = it.id) } ?: this
        } else this
        return items.map { item ->
            when (item) {
                is SongItem -> item.copy(artists = item.artists.map { it.resolved() })
                is AlbumItem -> item.copy(artists = item.artists?.map { it.resolved() })
                is PlaylistItem -> item.copy(author = item.author?.resolved())
                is PodcastItem -> item.copy(author = item.author?.resolved())
                is EpisodeItem -> item.copy(author = item.author?.resolved())
                else -> item
            } as T
        }
    }
}
