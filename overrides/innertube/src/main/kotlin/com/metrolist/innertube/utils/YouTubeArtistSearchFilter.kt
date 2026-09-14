/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.innertube.utils

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.pages.AlbumPage
import com.metrolist.innertube.pages.ArtistPage
import com.metrolist.innertube.pages.BrowseResult
import com.metrolist.innertube.pages.ChartsPage
import com.metrolist.innertube.pages.PlaylistPage
import com.metrolist.innertube.pages.RelatedPage
import com.metrolist.innertube.pages.SearchSummaryPage

data class AllowedArtist(
    val name: String,
    val channelId: String,
)

object YouTubeArtistSearchFilter {
    /**
     * Add or remove allowed YouTube Music artists here only.
     * Any artist that is not listed here is filtered out from online search, home sections,
     * artist pages, playlist pages, recommendations, radio/automix and every guarded route.
     */
    private val bundledAllowedArtists =
        listOf(
            AllowedArtist(name = "שמוליק סוכות", channelId = "UC_30I6RrK_UZdmuHnI6d87g"),
            AllowedArtist(name = "משה קליין", channelId = "UCpHeJBXBqnyvxF_eK-BiwnQ"),
            AllowedArtist(name = "קובי ברומר", channelId = "UC6Clmvqni_n6xpxNy_jVUKg"),
            AllowedArtist(name = "עקיבא", channelId = "UCjgCYlDrW4KfazEah3uT5cA"),
        )

    @Volatile
    private var activeAllowedArtists: List<AllowedArtist> = bundledAllowedArtists

    @Volatile
    private var activeAllowedChannelIds: Set<String> = bundledAllowedArtists.map { it.channelId }.toSet()

    val allowedArtistChannelIds: List<String>
        get() = activeAllowedArtists.map { it.channelId }

    val activeAllowedArtistCount: Int
        get() = activeAllowedChannelIds.size

    fun allowedArtistNameForId(id: String?): String? {
        val channelId = id?.trim().orEmpty()
        if (channelId.isBlank()) return null

        return activeAllowedArtists
            .firstOrNull { it.channelId == channelId }
            ?.name
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun preferredArtistName(id: String?, fallback: String?): String {
        val channelId = id?.trim().orEmpty()
        return allowedArtistNameForId(channelId)
            ?: fallback.orEmpty().trim().ifBlank { channelId }
    }

    fun allowedSourceArtistId(item: ArtistItem?, fallbackId: String? = null): String? =
        listOf(item?.channelId, item?.id, fallbackId)
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull(::isAllowedArtistId)

    fun replaceAllowedArtists(artists: List<AllowedArtist>): Boolean {
        val cleanedArtists =
            artists
                .asSequence()
                .mapNotNull { artist ->
                    val channelId = artist.channelId.trim()
                    if (channelId.isBlank()) {
                        null
                    } else {
                        artist.copy(
                            name = artist.name.trim().ifBlank { channelId },
                            channelId = channelId,
                        )
                    }
                }
                .distinctBy { it.channelId }
                .toList()

        if (cleanedArtists.isEmpty()) return false

        activeAllowedArtists = cleanedArtists
        activeAllowedChannelIds = cleanedArtists.map { it.channelId }.toSet()
        return true
    }

    fun replaceAllowedArtists(artists: Iterable<Pair<String, String>>): Boolean =
        replaceAllowedArtists(
            artists.map { (name, channelId) ->
                AllowedArtist(name = name, channelId = channelId)
            },
        )

    fun restoreBundledAllowedArtists() {
        activeAllowedArtists = bundledAllowedArtists
        activeAllowedChannelIds = bundledAllowedArtists.map { it.channelId }.toSet()
    }

    /**
     * Strict channel-id matching only.
     * The artist name is accepted in the signature for existing call sites, but it is intentionally
     * not used so results cannot pass the allow-list by name similarity.
     */
    @Suppress("UNUSED_PARAMETER")
    fun matchesAllowedArtist(id: String?, name: String?): Boolean = isAllowedArtistId(id)

    fun isAllowedArtistId(id: String?): Boolean = id != null && id in activeAllowedChannelIds

    fun hasAllowedArtist(artists: List<Artist>?): Boolean =
        artists.orEmpty().any { isAllowedArtistId(it.id) }

    fun onlyAllowedArtists(artists: List<Artist>?): List<Artist> =
        artists.orEmpty().filter { isAllowedArtistId(it.id) }

    fun isAllowed(item: YTItem): Boolean =
        when (item) {
            is SongItem -> !item.isEpisode && hasAllowedArtist(item.artists) && !YouTubeBlockedSongFilter.isBlockedSongId(item.id)
            is AlbumItem -> hasAllowedArtist(item.artists)
            is ArtistItem -> isAllowedArtistId(item.channelId) || isAllowedArtistId(item.id)
            is PlaylistItem -> !item.isPodcast && !item.id.startsWith("SS")
            is PodcastItem -> isAllowedArtistId(item.channelId ?: item.author?.id)
            is EpisodeItem -> false
        }

    fun withSourceArtist(item: YTItem, sourceArtistId: String?, sourceArtistName: String?): YTItem {
        val sourceId = sourceArtistId?.trim().orEmpty()
        if (!isAllowedArtistId(sourceId)) return item

        val source = Artist(
            name = preferredArtistName(sourceId, sourceArtistName),
            id = sourceId,
        )

        return when (item) {
            is SongItem ->
                if (item.artists.any { !it.id.isNullOrBlank() }) {
                    item
                } else {
                    item.copy(artists = listOf(source) + item.artists)
                }
            is AlbumItem ->
                if (item.artists.orEmpty().any { !it.id.isNullOrBlank() }) {
                    item
                } else {
                    item.copy(artists = listOf(source) + item.artists.orEmpty())
                }
            is PlaylistItem, is PodcastItem -> item
            is ArtistItem, is EpisodeItem -> item
        }
    }
}

fun <T : YTItem> List<T>.filterAllowedArtists(): List<T> = filter(YouTubeArtistSearchFilter::isAllowed)

@Suppress("UNCHECKED_CAST")
fun <T : YTItem> List<T>.filterAllowedArtistsFromSourceArtist(
    sourceArtistId: String?,
    sourceArtistName: String? = null,
): List<T> =
    map { item -> YouTubeArtistSearchFilter.withSourceArtist(item, sourceArtistId, sourceArtistName) as T }
        .filterAllowedArtists()

fun SearchSummaryPage.filterAllowedArtists(): SearchSummaryPage =
    copy(
        summaries = summaries.mapNotNull { summary ->
            val items = summary.items.filterAllowedArtists()
            if (items.isEmpty()) null else summary.copy(items = items)
        },
    )

fun BrowseResult.filterAllowedArtists(): BrowseResult =
    copy(
        items = items.mapNotNull { section ->
            val items = section.items.filterAllowedArtists()
            if (items.isEmpty()) null else section.copy(items = items)
        },
    )

fun ArtistPage.filterAllowedArtists(): ArtistPage {
    val sourceArtistId = YouTubeArtistSearchFilter.allowedSourceArtistId(artist)
    return copy(
        sections = sections.mapNotNull { section ->
            val items = section.items.filterAllowedArtistsFromSourceArtist(
                sourceArtistId = sourceArtistId,
                sourceArtistName = artist.title,
            )
            if (items.isEmpty()) null else section.copy(items = items)
        },
    )
}

fun AlbumPage.filterAllowedArtists(): AlbumPage =
    copy(
        songs = songs.filter { YouTubeArtistSearchFilter.hasAllowedArtist(it.artists) && !YouTubeBlockedSongFilter.isBlockedSongId(it.id) },
        otherVersions = otherVersions.filter { YouTubeArtistSearchFilter.hasAllowedArtist(it.artists) },
    )

/** A playlist owner does not determine which of its tracks may play. */
fun PlaylistPage.filterAllowedArtists(): PlaylistPage = copy(songs = songs.filterAllowedArtists())

fun RelatedPage.filterAllowedArtists(): RelatedPage =
    copy(
        songs = songs.filter { YouTubeArtistSearchFilter.hasAllowedArtist(it.artists) && !YouTubeBlockedSongFilter.isBlockedSongId(it.id) },
        albums = albums.filter { YouTubeArtistSearchFilter.hasAllowedArtist(it.artists) },
        artists = artists.filter { YouTubeArtistSearchFilter.isAllowedArtistId(it.channelId ?: it.id) },
        playlists = playlists.filterAllowedArtists(),
    )

fun ChartsPage.filterAllowedArtists(): ChartsPage =
    copy(
        sections = sections.mapNotNull { section ->
            val items = section.items.filterAllowedArtists()
            if (items.isEmpty()) null else section.copy(items = items)
        },
    )
