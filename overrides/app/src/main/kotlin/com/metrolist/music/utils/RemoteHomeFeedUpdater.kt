/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.metrolist.innertube.utils.parseCompactCount
import com.metrolist.innertube.utils.YouTubeArtistSearchFilter
import com.metrolist.music.BuildConfig
import com.metrolist.music.constants.RemoteHomeFeedCacheKey
import com.metrolist.music.constants.RemoteHomeFeedLastUpdatedKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class RemoteHomeFeed(
    val version: Int = 2,
    val generatedAt: String? = null,
    val allowedArtistsCount: Int? = null,
    val itemsCount: Int? = null,
    val items: List<RemoteHomeFeedItem> = emptyList(),
    val popularArtists: List<RemotePopularArtistItem> = emptyList(),
    val topArtists: List<RemotePopularArtistItem> = emptyList(),
    @SerialName("popular_artists") val popularArtistsSnake: List<RemotePopularArtistItem> = emptyList(),
    @SerialName("top_artists") val topArtistsSnake: List<RemotePopularArtistItem> = emptyList(),
    val popularSongs: List<RemotePopularSongItem> = emptyList(),
    val topSongs: List<RemotePopularSongItem> = emptyList(),
    @SerialName("popular_songs") val popularSongsSnake: List<RemotePopularSongItem> = emptyList(),
    @SerialName("top_songs") val topSongsSnake: List<RemotePopularSongItem> = emptyList(),
    val artistGenreShelves: List<RemoteArtistGenreShelf> = emptyList(),
    val genreShelves: List<RemoteArtistGenreShelf> = emptyList(),
    @SerialName("artist_genre_shelves") val artistGenreShelvesSnake: List<RemoteArtistGenreShelf> = emptyList(),
    @SerialName("genre_shelves") val genreShelvesSnake: List<RemoteArtistGenreShelf> = emptyList(),
)

data class RemoteHomeFeedPayload(
    val items: List<RemoteHomeFeedItem> = emptyList(),
    val popularArtists: List<RemotePopularArtistItem> = emptyList(),
    val popularSongs: List<RemotePopularSongItem> = emptyList(),
    val artistGenreShelves: List<RemoteArtistGenreShelf> = emptyList(),
) {
    val isEmpty: Boolean
        get() = items.isEmpty() && popularArtists.isEmpty() && popularSongs.isEmpty() && artistGenreShelves.isEmpty()
}

@Serializable
data class RemoteHomeFeedItem(
    val type: String = "",
    val title: String = "",
    val youtubeTitle: String? = null,
    val originalTitle: String? = null,
    val artistName: String = "",
    val youtubeArtistName: String? = null,
    val originalArtistName: String? = null,
    val artistChannelId: String = "",
    val youtubeId: String? = null,
    val youtubeBrowseId: String? = null,
    val youtubeVideoId: String? = null,
    val youtubePlaylistId: String? = null,
    val releaseDate: String? = null,
    val releaseDatePrecision: String? = null,
    val artworkUrl: String? = null,
    val thumbnailUrl: String? = null,
    val source: String? = null,
    val confidence: String? = null,
    val matchScore: Int? = null,
    val totalTracks: Int? = null,
    val externalUrl: String? = null,
)

@Serializable
data class RemotePopularArtistItem(
    val subscriberCount: Long? = null,
    val subscriberCountText: String? = null,
    val name: String = "",
    val title: String? = null,
    val youtubeName: String? = null,
    val originalName: String? = null,
    val channelId: String = "",
    val artistChannelId: String? = null,
    val youtubeId: String? = null,
    val thumbnailUrl: String? = null,
    val artworkUrl: String? = null,
    val monthlyListenersText: String? = null,
    val monthlyListenerCountText: String? = null,
    val monthlyPlaysText: String? = null,
    val playCountText: String? = null,
    val monthlyListeners: Long? = null,
    val monthlyListenerCount: Long? = null,
    val monthlyPlays: Long? = null,
    val playCount: Long? = null,
    val source: String? = null,
    val confidence: String? = null,
)

@Serializable
data class RemotePopularSongItem(
    val title: String = "",
    val youtubeTitle: String? = null,
    val originalTitle: String? = null,
    val artistName: String = "",
    val youtubeArtistName: String? = null,
    val originalArtistName: String? = null,
    val artistChannelId: String = "",
    val channelId: String? = null,
    val youtubeId: String? = null,
    val youtubeVideoId: String? = null,
    val videoId: String? = null,
    val artworkUrl: String? = null,
    val thumbnailUrl: String? = null,
    val monthlyPlaysText: String? = null,
    val monthlyListenersText: String? = null,
    val monthlyListenerCountText: String? = null,
    val playCountText: String? = null,
    val monthlyPlays: Long? = null,
    val monthlyPlayCount: Long? = null,
    val monthlyListeners: Long? = null,
    val monthlyListenerCount: Long? = null,
    val playCount: Long? = null,
    val source: String? = null,
    val confidence: String? = null,
)
@Serializable
data class RemoteArtistGenreShelf(
    val id: String = "",
    val title: String = "",
    val artists: List<RemoteGenreArtistItem> = emptyList(),
)

@Serializable
data class RemoteGenreArtistItem(
    val subscriberCount: Long? = null,
    val subscriberCountText: String? = null,
    val name: String = "",
    val title: String? = null,
    val youtubeName: String? = null,
    val originalName: String? = null,
    val channelId: String = "",
    val artistChannelId: String? = null,
    val youtubeId: String? = null,
    val thumbnailUrl: String? = null,
    val artworkUrl: String? = null,
    val monthlyListenersText: String? = null,
    val monthlyListenerCountText: String? = null,
    val monthlyPlaysText: String? = null,
    val playCountText: String? = null,
    val monthlyListeners: Long? = null,
    val monthlyListenerCount: Long? = null,
    val monthlyPlays: Long? = null,
    val playCount: Long? = null,
    val source: String? = null,
    val confidence: String? = null,
)


object RemoteHomeFeedUpdater {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val channelIdRegex = Regex("^UC[A-Za-z0-9_-]{20,}$")
    private val releaseDateRegex = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    suspend fun loadCachedFeed(context: Context): RemoteHomeFeedPayload =
        withContext(Dispatchers.IO) {
            val cachedRaw = context.dataStore.data.first()[RemoteHomeFeedCacheKey].orEmpty()
            parseHomeFeed(cachedRaw)
        }

    suspend fun refreshFromRemote(context: Context): RemoteHomeFeedPayload =
        withContext(Dispatchers.IO) {
            val url = BuildConfig.REMOTE_HOME_FEED_URL.trim()
            if (url.isBlank()) return@withContext RemoteHomeFeedPayload()

            try {
                val raw = download(url)
                val payload = parseHomeFeed(raw)
                if (payload.isEmpty) {
                    Timber.w("Remote home feed file was downloaded but no valid items were found")
                    return@withContext RemoteHomeFeedPayload()
                }

                context.dataStore.edit { settings ->
                    settings[RemoteHomeFeedCacheKey] = raw
                    settings[RemoteHomeFeedLastUpdatedKey] = System.currentTimeMillis()
                }
                payload
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh remote home feed")
                reportException(e)
                RemoteHomeFeedPayload()
            }
        }

    fun parseHomeFeed(raw: String): RemoteHomeFeedPayload {
        val text = raw.trim()
        if (text.isBlank()) return RemoteHomeFeedPayload()

        val feed = runCatching {
            json.decodeFromString<RemoteHomeFeed>(text)
        }.getOrElse {
            Timber.e(it, "Failed to parse remote home feed JSON")
            return RemoteHomeFeedPayload()
        }

        val releaseItems =
            feed.items
                .mapNotNull(::validateReleaseItem)
                .distinctBy { item ->
                    listOf(
                        item.artistChannelId,
                        item.type.uppercase(),
                        item.youtubeVideoId.orEmpty(),
                        item.youtubeBrowseId.orEmpty(),
                        item.youtubeId.orEmpty(),
                        item.title.trim().lowercase(),
                    ).joinToString("|")
                }
                .sortedByDescending { it.releaseDate.orEmpty() }

        val popularArtists =
            (feed.popularArtists + feed.topArtists + feed.popularArtistsSnake + feed.topArtistsSnake)
                .mapNotNull(::validatePopularArtist)
                .distinctBy { it.resolvedChannelId }
                .sortedWith(
                    compareByDescending<RemotePopularArtistItem> { it.monthlyMetricValue() ?: Long.MIN_VALUE }
                        .thenBy { it.resolvedName }
                )

        val popularSongs =
            (feed.popularSongs + feed.topSongs + feed.popularSongsSnake + feed.topSongsSnake)
                .mapNotNull(::validatePopularSong)
                .distinctBy { it.resolvedVideoId }
                .sortedWith(
                    compareByDescending<RemotePopularSongItem> { it.monthlyMetricValue() ?: Long.MIN_VALUE }
                        .thenBy { it.displayTitle }
                )

        val artistGenreShelves =
            (feed.artistGenreShelves + feed.genreShelves + feed.artistGenreShelvesSnake + feed.genreShelvesSnake)
                .mapNotNull(::validateArtistGenreShelf)
                .distinctBy { it.id }

        return RemoteHomeFeedPayload(
            items = releaseItems,
            popularArtists = popularArtists,
            popularSongs = popularSongs,
            artistGenreShelves = artistGenreShelves,
        )
    }

    private fun validateReleaseItem(item: RemoteHomeFeedItem): RemoteHomeFeedItem? {
        val type = item.type.trim().uppercase()
        if (type !in setOf("ALBUM", "SINGLE", "SONG")) return null

        val title = item.displayTitle
        if (title.isBlank()) return null

        val channelId = item.artistChannelId.trim()
        if (!channelIdRegex.matches(channelId)) return null
        if (!YouTubeArtistSearchFilter.isAllowedArtistId(channelId)) return null

        val releaseDate = item.releaseDate?.trim()
        if (releaseDate.isNullOrBlank() || !releaseDateRegex.matches(releaseDate)) return null

        val confidence = item.confidence?.trim()?.lowercase()
        if (confidence == "low") return null

        val youtubeId = item.youtubeId?.trim().orEmpty()
        val browseId = item.youtubeBrowseId?.trim().orEmpty()
        val videoId = item.youtubeVideoId?.trim().orEmpty()
        if (youtubeId.isBlank() && browseId.isBlank() && videoId.isBlank()) return null

        val artistName = YouTubeArtistSearchFilter.preferredArtistName(channelId, item.displayArtistName)

        return item.copy(
            type = if (type == "SONG") "SINGLE" else type,
            title = title,
            artistName = artistName,
            youtubeArtistName = artistName,
            originalArtistName = artistName,
            artistChannelId = channelId,
            youtubeId = youtubeId.ifBlank { null },
            youtubeBrowseId = browseId.ifBlank { null },
            youtubeVideoId = videoId.ifBlank { null },
            youtubePlaylistId = item.youtubePlaylistId?.trim()?.ifBlank { null },
            releaseDate = releaseDate,
            artworkUrl = item.artworkUrl?.trim()?.ifBlank { null },
            thumbnailUrl = item.thumbnailUrl?.trim()?.ifBlank { null },
            confidence = confidence,
        )
    }

    private fun validatePopularArtist(item: RemotePopularArtistItem): RemotePopularArtistItem? {
        val channelId = item.resolvedChannelId
        if (!channelIdRegex.matches(channelId)) return null
        if (!YouTubeArtistSearchFilter.isAllowedArtistId(channelId)) return null

        val name = YouTubeArtistSearchFilter.preferredArtistName(channelId, item.resolvedName)
        if (name.isBlank()) return null

        val confidence = item.confidence?.trim()?.lowercase()
        if (confidence == "low") return null

        return item.copy(
            name = name,
            youtubeName = name,
            originalName = name,
            channelId = channelId,
            thumbnailUrl = item.thumbnailUrl?.trim()?.ifBlank { null },
            artworkUrl = item.artworkUrl?.trim()?.ifBlank { null },
            confidence = confidence,
        )
    }

    private fun validatePopularSong(item: RemotePopularSongItem): RemotePopularSongItem? {
        val title = item.displayTitle
        if (title.isBlank()) return null

        val channelId = item.resolvedArtistChannelId
        if (!channelIdRegex.matches(channelId)) return null
        if (!YouTubeArtistSearchFilter.isAllowedArtistId(channelId)) return null

        val videoId = item.resolvedVideoId
        if (videoId.isBlank()) return null

        val confidence = item.confidence?.trim()?.lowercase()
        if (confidence == "low") return null

        val artistName = YouTubeArtistSearchFilter.preferredArtistName(channelId, item.displayArtistName)

        return item.copy(
            title = title,
            artistName = artistName,
            youtubeArtistName = artistName,
            originalArtistName = artistName,
            artistChannelId = channelId,
            youtubeVideoId = videoId,
            artworkUrl = item.artworkUrl?.trim()?.ifBlank { null },
            thumbnailUrl = item.thumbnailUrl?.trim()?.ifBlank { null },
            confidence = confidence,
        )
    }

    private fun validateArtistGenreShelf(shelf: RemoteArtistGenreShelf): RemoteArtistGenreShelf? {
        val id = shelf.id.trim()
        if (id.isBlank()) return null

        val rawTitle = shelf.title.trim()
        val title = if (rawTitle.isBlank() || rawTitle.equals(id, ignoreCase = true)) {
            defaultArtistGenreShelfTitle(id)
        } else {
            rawTitle
        }
        val artists =
            shelf.artists
                .mapNotNull(::validateGenreArtist)
                .distinctBy { it.resolvedChannelId }
                .sortedWith(
                    compareByDescending<RemoteGenreArtistItem> { it.monthlyMetricValue() ?: Long.MIN_VALUE }
                        .thenBy { it.resolvedName }
                )

        if (artists.isEmpty()) return null

        return shelf.copy(
            id = id,
            title = title,
            artists = artists,
        )
    }

    private fun validateGenreArtist(item: RemoteGenreArtistItem): RemoteGenreArtistItem? {
        val channelId = item.resolvedChannelId
        if (!channelIdRegex.matches(channelId)) return null
        if (!YouTubeArtistSearchFilter.isAllowedArtistId(channelId)) return null

        val name = YouTubeArtistSearchFilter.preferredArtistName(channelId, item.resolvedName)
        if (name.isBlank()) return null

        val confidence = item.confidence?.trim()?.lowercase()
        if (confidence == "low") return null

        return item.copy(
            name = name,
            youtubeName = name,
            originalName = name,
            channelId = channelId,
            thumbnailUrl = item.thumbnailUrl?.trim()?.ifBlank { null },
            artworkUrl = item.artworkUrl?.trim()?.ifBlank { null },
            confidence = confidence,
        )
    }

    private fun download(url: String): String {
        val request =
            Request
                .Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Remote home feed request failed: HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }
}

val RemotePopularArtistItem.resolvedChannelId: String
    get() = (channelId.ifBlank { artistChannelId.orEmpty().ifBlank { youtubeId.orEmpty() } }).trim()

val RemotePopularArtistItem.resolvedName: String
    get() = youtubeName.orEmpty().trim()
        .ifBlank { originalName.orEmpty().trim() }
        .ifBlank { name.trim() }
        .ifBlank { title.orEmpty().trim() }
        .ifBlank { resolvedChannelId }

val RemoteHomeFeedItem.displayTitle: String
    get() = youtubeTitle.orEmpty().trim()
        .ifBlank { originalTitle.orEmpty().trim() }
        .ifBlank { title.trim() }

val RemoteHomeFeedItem.displayArtistName: String
    get() = youtubeArtistName.orEmpty().trim()
        .ifBlank { originalArtistName.orEmpty().trim() }
        .ifBlank { artistName.trim() }

fun RemotePopularArtistItem.monthlyMetricValue(): Long? =
    subscriberCount?.takeIf { it >= 0 }
        ?: parseCompactCount(subscriberCountText ?: monthlyListenersText ?: monthlyListenerCountText)
        ?: monthlyListeners ?: monthlyListenerCount

fun RemotePopularArtistItem.monthlyMetricText(): String? =
    subscriberCountText ?: monthlyListenersText ?: monthlyListenerCountText

val RemotePopularSongItem.resolvedArtistChannelId: String
    get() = artistChannelId.ifBlank { channelId.orEmpty() }.trim()

val RemotePopularSongItem.resolvedVideoId: String
    get() = (youtubeVideoId ?: videoId ?: youtubeId).orEmpty().trim()

val RemotePopularSongItem.displayTitle: String
    get() = youtubeTitle.orEmpty().trim()
        .ifBlank { originalTitle.orEmpty().trim() }
        .ifBlank { title.trim() }

val RemotePopularSongItem.displayArtistName: String
    get() = youtubeArtistName.orEmpty().trim()
        .ifBlank { originalArtistName.orEmpty().trim() }
        .ifBlank { artistName.trim() }

val RemoteGenreArtistItem.resolvedChannelId: String
    get() = (channelId.ifBlank { artistChannelId.orEmpty().ifBlank { youtubeId.orEmpty() } }).trim()

val RemoteGenreArtistItem.resolvedName: String
    get() = youtubeName.orEmpty().trim()
        .ifBlank { originalName.orEmpty().trim() }
        .ifBlank { name.trim() }
        .ifBlank { title.orEmpty().trim() }
        .ifBlank { resolvedChannelId }

fun RemotePopularSongItem.monthlyMetricValue(): Long? =
    monthlyPlays ?: monthlyPlayCount ?: monthlyListeners ?: monthlyListenerCount ?: playCount

fun RemotePopularSongItem.monthlyMetricText(): String? =
    monthlyPlaysText ?: monthlyListenersText ?: monthlyListenerCountText ?: playCountText

fun RemoteGenreArtistItem.monthlyMetricValue(): Long? =
    subscriberCount?.takeIf { it >= 0 }
        ?: parseCompactCount(subscriberCountText ?: monthlyListenersText ?: monthlyListenerCountText)
        ?: monthlyListeners ?: monthlyListenerCount

fun RemoteGenreArtistItem.monthlyMetricText(): String? =
    subscriberCountText ?: monthlyListenersText ?: monthlyListenerCountText

fun defaultArtistGenreShelfTitle(id: String): String =
    when (id.trim().lowercase()) {
        "hasidic" -> "מוזיקה חסידית"
        "israeli" -> "מוזיקה ישראלית"
        "mizrahi" -> "מוזיקה מזרחית - ים תיכונית"
        "pop" -> "מוזיקת פופ"
        "alternative" -> "מוזיקה אלטרנטיבית"
        "american_pop", "american-pop", "americanpop" -> "מוזיקת פופ אמריקאי"
        else -> id.trim()
    }
