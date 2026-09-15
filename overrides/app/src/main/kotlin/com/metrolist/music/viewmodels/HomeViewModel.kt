/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.flow.combine
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.models.filterYoutubeShorts
import com.metrolist.innertube.pages.ArtistSection
import com.metrolist.innertube.pages.ExplorePage
import com.metrolist.innertube.pages.HomePage
import com.metrolist.innertube.utils.completed
import com.metrolist.innertube.utils.filterAllowedArtists
import com.metrolist.innertube.utils.YouTubeArtistSearchFilter
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.HideYoutubeShortsKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.QuickPicks
import com.metrolist.music.constants.QuickPicksKey
import com.metrolist.music.constants.ShowWrappedCardKey
import com.metrolist.music.constants.WrappedSeenKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.LocalItem
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SpeedDialItem
import com.metrolist.music.extensions.filterVideoSongs
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.models.SimilarRecommendation
import com.metrolist.music.ui.screens.wrapped.WrappedAudioService
import com.metrolist.music.ui.screens.wrapped.WrappedManager
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.isAllowedArtist
import com.metrolist.music.utils.isAllowedContent
import com.metrolist.music.utils.RemoteArtistGenreShelf
import com.metrolist.music.utils.RemoteHomeFeedItem
import com.metrolist.music.utils.RemoteHomeFeedPayload
import com.metrolist.music.utils.RemoteHomeFeedUpdater
import com.metrolist.music.utils.RemotePopularArtistItem
import com.metrolist.music.utils.RemotePopularSongItem
import com.metrolist.music.utils.defaultArtistGenreShelfTitle
import com.metrolist.music.utils.monthlyMetricText
import com.metrolist.music.utils.monthlyMetricValue
import com.metrolist.music.utils.resolvedArtistChannelId
import com.metrolist.music.utils.resolvedChannelId
import com.metrolist.music.utils.resolvedName
import com.metrolist.music.utils.resolvedVideoId
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import javax.inject.Inject
import kotlin.random.Random

data class DailyDiscoverItem(
    val seed: Song,
    val recommendation: YTItem,
    val relatedEndpoint: BrowseEndpoint?
)

data class CommunityPlaylistItem(
    val playlist: PlaylistItem,
    val songs: List<SongItem>
)

data class PopularHomeArtist(
    val artist: ArtistItem,
    val monthlyListenerCountText: String?,
    val monthlyListenerCount: Long?,
)

data class PopularHomeSong(
    val song: SongItem,
    val monthlyPlayCountText: String?,
    val monthlyPlayCount: Long?,
)

data class ArtistGenreHomeShelf(
    val id: String,
    val title: String,
    val artists: List<ArtistItem>,
)

private const val HOME_MAX_ARTIST_ITEMS = 20
private const val HOME_MAX_POPULAR_SONG_ITEMS = 20
private const val HOME_MAX_ARTIST_GENRE_ITEMS = 20
private const val HOME_MAX_FOLLOWED_ARTIST_ITEMS = 40
private const val HOME_MAX_REMOTE_RELEASE_ITEMS = 80

private fun com.metrolist.music.db.entities.ArtistEntity.matchesAllowedArtist(): Boolean =
    YouTubeArtistSearchFilter.matchesAllowedArtist(channelId ?: id, name)

private fun LocalItem.matchesAllowedArtist(): Boolean =
    when (this) {
        is Song -> artists.any { it.matchesAllowedArtist() }
        is Album -> artists.any { it.matchesAllowedArtist() }
        is com.metrolist.music.db.entities.Artist -> artist.matchesAllowedArtist()
        else -> false
    }

private fun YTItem.withHomeSourceArtist(sourceArtist: ArtistItem): YTItem {
    val source = Artist(
        name = sourceArtist.title,
        id = sourceArtist.channelId ?: sourceArtist.id,
    )
    val hasAllowedSource = YouTubeArtistSearchFilter.matchesAllowedArtist(source.id, source.name)
    return when (this) {
        is SongItem ->
            if (hasAllowedSource && artists.none { YouTubeArtistSearchFilter.matchesAllowedArtist(it.id, it.name) }) {
                copy(artists = listOf(source) + artists)
            } else {
                this
            }
        is AlbumItem ->
            if (hasAllowedSource && artists.orEmpty().none { YouTubeArtistSearchFilter.matchesAllowedArtist(it.id, it.name) }) {
                copy(artists = listOf(source))
            } else {
                this
            }
        is PlaylistItem -> if (hasAllowedSource) copy(author = source) else this
        is PodcastItem -> if (hasAllowedSource) copy(author = source, channelId = source.id) else this
        else -> this
    }
}

private fun String.containsAnyHomeKeyword(vararg keywords: String): Boolean {
    val normalized = lowercase(Locale.ROOT)
    return keywords.any { normalized.contains(it.lowercase(Locale.ROOT)) }
}

private fun ArtistSection.isReleaseSection(): Boolean =
    title.containsAnyHomeKeyword(
        "release",
        "releases",
        "latest",
        "new",
        "album",
        "albums",
        "single",
        "singles",
        "ep",
        "eps",
        "חדש",
        "חדשים",
        "אלבום",
        "אלבומים",
        "סינגל",
        "סינגלים",
    ) && !title.containsAnyHomeKeyword(
        "top",
        "popular",
        "מובילים",
        "פופול",
    )

private fun ArtistSection.isLiveSection(): Boolean =
    title.containsAnyHomeKeyword(
        "live",
        "concert",
        "concerts",
        "performance",
        "performances",
        "לייב",
        "הופעה חיה",
        "הופעות חיות",
        "הופעות",
    )

private suspend fun ArtistSection.expandedItemsOrShelfItems(): List<YTItem> =
    moreEndpoint
        ?.let { endpoint -> YouTube.artistItems(endpoint).getOrNull()?.items }
        ?.takeIf { it.isNotEmpty() }
        ?: items

private suspend fun List<ArtistSection>.expandedHomeItems(sourceArtist: ArtistItem): List<YTItem> =
    flatMap { section ->
        section.expandedItemsOrShelfItems().map { it.withHomeSourceArtist(sourceArtist) }
    }

private fun List<YTItem>.sourceOrderedForHome(): List<YTItem> =
    distinctBy { it.id }

private fun RemoteHomeFeedItem.toYTItem(): YTItem? {
    val displayArtistName = YouTubeArtistSearchFilter.preferredArtistName(artistChannelId, artistName)
    val artist = Artist(name = displayArtistName, id = artistChannelId)
    val thumbnail = artworkUrl ?: thumbnailUrl.orEmpty()
    return when (type.uppercase(Locale.ROOT)) {
        "ALBUM" -> {
            val browseId = youtubeBrowseId ?: youtubeId ?: return null
            AlbumItem(
                browseId = browseId,
                playlistId = youtubePlaylistId ?: youtubeId ?: browseId,
                title = title,
                artists = listOf(artist),
                year = releaseDate?.take(4)?.toIntOrNull(),
                thumbnail = thumbnail,
                explicit = false,
            )
        }
        "SINGLE", "SONG" -> {
            val videoId = youtubeVideoId ?: youtubeId ?: return null
            SongItem(
                id = videoId,
                title = title,
                artists = listOf(artist),
                thumbnail = thumbnail,
                explicit = false,
                endpoint = WatchEndpoint(videoId = videoId),
            )
        }
        else -> null
    }
}

private fun RemotePopularArtistItem.toPopularHomeArtist(): PopularHomeArtist? {
    val channelId = resolvedChannelId.takeIf { YouTubeArtistSearchFilter.isAllowedArtistId(it) } ?: return null
    return PopularHomeArtist(
        artist = ArtistItem(
            id = channelId,
            title = YouTubeArtistSearchFilter.preferredArtistName(channelId, resolvedName),
            thumbnail = thumbnailUrl ?: artworkUrl,
            channelId = channelId,
            shuffleEndpoint = null,
            radioEndpoint = null,
        ),
        monthlyListenerCountText = monthlyMetricText(),
        monthlyListenerCount = monthlyMetricValue(),
    )
}

private fun RemotePopularSongItem.toPopularHomeSong(): PopularHomeSong? {
    val channelId = resolvedArtistChannelId.takeIf { YouTubeArtistSearchFilter.isAllowedArtistId(it) } ?: return null
    val videoId = resolvedVideoId.takeIf { it.isNotBlank() } ?: return null
    return PopularHomeSong(
        song = SongItem(
            id = videoId,
            title = title,
            artists = listOf(Artist(name = YouTubeArtistSearchFilter.preferredArtistName(channelId, artistName), id = channelId)),
            thumbnail = artworkUrl ?: thumbnailUrl.orEmpty(),
            explicit = false,
            endpoint = WatchEndpoint(videoId = videoId),
        ),
        monthlyPlayCountText = monthlyMetricText(),
        monthlyPlayCount = monthlyMetricValue(),
    )
}

private fun RemoteArtistGenreShelf.toArtistGenreHomeShelf(): ArtistGenreHomeShelf? {
    val shelfId = id.trim()
    if (shelfId.isBlank()) return null

    val artistItems = artists
        .sortedWith(
            compareByDescending<com.metrolist.music.utils.RemoteGenreArtistItem> { it.monthlyMetricValue() ?: Long.MIN_VALUE }
                .thenBy { it.resolvedName }
        )
        .mapNotNull { item ->
            val channelId = item.resolvedChannelId.takeIf { YouTubeArtistSearchFilter.isAllowedArtistId(it) } ?: return@mapNotNull null
            ArtistItem(
                id = channelId,
                title = YouTubeArtistSearchFilter.preferredArtistName(channelId, item.resolvedName),
                thumbnail = item.thumbnailUrl ?: item.artworkUrl,
                channelId = channelId,
                shuffleEndpoint = null,
                radioEndpoint = null,
            )
        }
        .distinctBy { it.channelId ?: it.id }
        .take(HOME_MAX_ARTIST_GENRE_ITEMS)

    if (artistItems.isEmpty()) return null

    return ArtistGenreHomeShelf(
        id = shelfId,
        title = title.trim().let { rawTitle ->
            if (rawTitle.isBlank() || rawTitle.equals(shelfId, ignoreCase = true)) {
                defaultArtistGenreShelfTitle(shelfId)
            } else {
                rawTitle
            }
        },
        artists = artistItems,
    )
}

private fun RemoteHomeFeedPayload.followedReleaseItems(followedArtistIds: Set<String>): List<YTItem> =
    items
        .filter { it.artistChannelId in followedArtistIds }
        .sortedByDescending { it.releaseDate.orEmpty() }
        .mapNotNull { it.toYTItem() }
        .filterAllowedArtists()
        .distinctBy { it.id }
        .take(HOME_MAX_FOLLOWED_ARTIST_ITEMS)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    val syncUtils: SyncUtils,
    val wrappedManager: WrappedManager,
    private val wrappedAudioService: WrappedAudioService,
) : ViewModel() {
    val isRefreshing = MutableStateFlow(false)
    val isLoading = MutableStateFlow(false)
    val isRandomizing = MutableStateFlow(false)

    private val quickPicksEnum = context.dataStore.data.map {
        it[QuickPicksKey].toEnum(QuickPicks.QUICK_PICKS)
    }.distinctUntilChanged()

    val quickPicks = MutableStateFlow<List<Song>?>(null)
    val dailyDiscover = MutableStateFlow<List<DailyDiscoverItem>?>(null)
    val forgottenFavorites = MutableStateFlow<List<Song>?>(null)
    val keepListening = MutableStateFlow<List<LocalItem>?>(null)
    val recentlyPlayedSongs = MutableStateFlow<List<Song>?>(null)
    val popularArtists = MutableStateFlow<List<PopularHomeArtist>?>(null)
    val popularSongs = MutableStateFlow<List<PopularHomeSong>?>(null)
    val artistGenreShelves = MutableStateFlow<List<ArtistGenreHomeShelf>?>(null)
    val allowedArtistReleases = MutableStateFlow<List<YTItem>?>(null)
    val followedArtistReleases = MutableStateFlow<List<YTItem>?>(null)
    private val remoteHomeFeedPayload = MutableStateFlow<RemoteHomeFeedPayload?>(null)
    val favorites = MutableStateFlow<List<LocalItem>?>(null)
    val similarRecommendations = MutableStateFlow<List<SimilarRecommendation>?>(null)
    val accountPlaylists = MutableStateFlow<List<PlaylistItem>?>(null)
    val homePage = MutableStateFlow<HomePage?>(null)
    val explorePage = MutableStateFlow<ExplorePage?>(null)
    val communityPlaylists = MutableStateFlow<List<CommunityPlaylistItem>?>(null)
    val selectedChip = MutableStateFlow<HomePage.Chip?>(null)
    private val previousHomePage = MutableStateFlow<HomePage?>(null)

    // Official API data for podcast sections
    val savedPodcastShows = MutableStateFlow<List<com.metrolist.innertube.models.PodcastItem>>(emptyList())
    val episodesForLater = MutableStateFlow<List<SongItem>>(emptyList())

    val allLocalItems = MutableStateFlow<List<LocalItem>>(emptyList())
    val allYtItems = MutableStateFlow<List<YTItem>>(emptyList())


    val pinnedSpeedDialItems: StateFlow<List<SpeedDialItem>> =
        database.speedDialDao.getAll()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val speedDialItems: StateFlow<List<YTItem>> =
        combine(
            database.speedDialDao.getAll(),
            keepListening,
            quickPicks
        ) { pinned, keepListening, quick ->
            val pinnedItems = pinned.map { it.toYTItem() }
            val filled = pinnedItems.toMutableList()
            val targetSize = 27

            if (filled.size < targetSize) {
                // Keep Listening (History/Heavy Rotation)
                keepListening?.let { k ->
                    val needed = targetSize - filled.size
                    val available = k.filter { item ->
                        filled.none { p -> p.id == item.id }
                    }.mapNotNull { item ->
                        when (item) {
                            is Song -> SongItem(
                                id = item.id,
                                title = item.title,
                                artists = item.artists.map { Artist(name = it.name, id = it.id) },
                                thumbnail = item.thumbnailUrl ?: "",
                                explicit = false
                            )
                            is Album -> AlbumItem(
                                browseId = item.id,
                                playlistId = item.album.playlistId ?: "",
                                title = item.title,
                                artists = item.artists.map { Artist(name = it.name, id = it.id) },
                                year = item.album.year,
                                thumbnail = item.thumbnailUrl ?: ""
                            )
                            is com.metrolist.music.db.entities.Artist -> ArtistItem(
                                id = item.id,
                                title = item.title,
                                thumbnail = item.thumbnailUrl,
                                shuffleEndpoint = null,
                                radioEndpoint = null
                            )
                            is Playlist -> null
                            else -> null
                        }
                    }
                    filled.addAll(available.take(needed))
                }
            }

            if (filled.size < targetSize) {
                // Quick Picks
                quick?.let { q ->
                    val needed = targetSize - filled.size
                    val available = q.filter { song ->
                        filled.none { p -> p.id == song.id }
                    }.map { song ->
                        SongItem(
                            id = song.id,
                            title = song.title,
                            artists = song.artists.map { Artist(name = it.name, id = it.id) },
                            thumbnail = song.thumbnailUrl ?: "",
                            explicit = false
                        )
                    }
                    filled.addAll(available.take(needed))
                }
            }
            
            filled.take(targetSize)
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    suspend fun getRandomItem(): YTItem? {
        try {
            isRandomizing.value = true
            // Visual feedback for the animation
            kotlinx.coroutines.delay(1000)

            val userSongs = mutableListOf<YTItem>()
            val otherSources = mutableListOf<YTItem>()

            quickPicks.value?.let { songs ->
                userSongs.addAll(songs.map { song ->
                    SongItem(
                        id = song.id,
                        title = song.title,
                        artists = song.artists.map { Artist(name = it.name, id = it.id) },
                        thumbnail = song.thumbnailUrl ?: "",
                        explicit = false
                    )
                })
            }

            keepListening.value?.let { items ->
                items.forEach { item ->
                    when (item) {
                        is Song -> userSongs.add(SongItem(
                            id = item.id,
                            title = item.title,
                            artists = item.artists.map { Artist(name = it.name, id = it.id) },
                            thumbnail = item.thumbnailUrl ?: "",
                            explicit = false
                        ))
                        is Album -> otherSources.add(AlbumItem(
                            browseId = item.id,
                            playlistId = item.album.playlistId ?: "",
                            title = item.title,
                            artists = item.artists.map { Artist(name = it.name, id = it.id) },
                            year = item.album.year,
                            thumbnail = item.thumbnailUrl ?: ""
                        ))
                        is com.metrolist.music.db.entities.Artist -> otherSources.add(ArtistItem(
                            id = item.id,
                            title = item.title,
                            thumbnail = item.thumbnailUrl,
                            shuffleEndpoint = null,
                            radioEndpoint = null
                        ))
                        is Playlist -> {}
                        else -> {}
                    }
                }
            }

            otherSources.addAll(allYtItems.value)

            // Probability: 80% User Songs, 20% Other Sources
            val item = if (userSongs.isNotEmpty() && (otherSources.isEmpty() || Random.nextFloat() < 0.8f)) {
                userSongs.distinctBy { it.id }.shuffled().firstOrNull()
            } else {
                otherSources.distinctBy { it.id }.shuffled().firstOrNull()
            } ?: userSongs.firstOrNull() ?: otherSources.firstOrNull()

            return item
        } finally {
            isRandomizing.value = false
        }
    }

    val accountName = MutableStateFlow("Guest")
    val accountImageUrl = MutableStateFlow<String?>(null)

	val showWrappedCard: StateFlow<Boolean> = context.dataStore.data.map { prefs ->
        val showWrappedPref = prefs[ShowWrappedCardKey] ?: false
        val seen = prefs[WrappedSeenKey] ?: false
        val isBeforeDate = LocalDate.now().isBefore(LocalDate.of(2026, 2, 1))

        isBeforeDate && (!seen || showWrappedPref)
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    val wrappedSeen: StateFlow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[WrappedSeenKey] ?: false
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun togglePin(item: YTItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val speedDialItem = SpeedDialItem.fromYTItem(item)
            val isPinned = database.speedDialDao.isPinned(speedDialItem.id).first()
            if (isPinned) {
                database.speedDialDao.delete(speedDialItem.id)
            } else {
                database.speedDialDao.insert(speedDialItem)
            }
        }
    }

    fun markWrappedAsSeen() {
        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.edit {
                it[WrappedSeenKey] = true
            }
        }
    }
    // Track last processed cookie to avoid unnecessary updates
    private var lastProcessedCookie: String? = null
    // Track if we're currently processing account data
    private var isProcessingAccountData = false

    private suspend fun getDailyDiscover() {
        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
        val likedSongs = database.likedSongsByCreateDateAsc().first()
        if (likedSongs.isEmpty()) return

        val seeds = likedSongs.shuffled().distinctBy { it.id }.take(5)
        
        // Use a synchronized list to collect results safely from concurrent coroutines
        val items = java.util.Collections.synchronizedList(mutableListOf<DailyDiscoverItem>())

        kotlinx.coroutines.coroutineScope {
            seeds.map { seed ->
                launch(Dispatchers.IO) {
                    val endpoint = YouTube.next(WatchEndpoint(videoId = seed.id)).getOrNull()?.relatedEndpoint
                    if (endpoint != null) {
                        YouTube.related(endpoint).onSuccess { page ->
                            val recommendations = page.songs
                                .filterAllowedArtists()
                                .filter { item ->
                                    if (hideVideoSongs && item.isVideoSong) return@filter false
                                    if (item.explicit) return@filter false
                                    true
                                }
                                .shuffled()

                            // Simple check to avoid immediate duplicate of seed
                            val recommendation = recommendations.firstOrNull { rec ->
                                rec.id != seed.id
                            }

                            if (recommendation != null) {
                                items.add(
                                    DailyDiscoverItem(
                                        seed = seed,
                                        recommendation = recommendation,
                                        relatedEndpoint = endpoint
                                    )
                                )
                            }
                        }
                    }
                }
            }.forEach { it.join() }
        }
        
        // Final deduplication just in case multiple seeds recommended the same song
        dailyDiscover.value = items.toList().distinctBy { it.recommendation.id }.shuffled()
    }

    private suspend fun getQuickPicks() {
        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
        when (quickPicksEnum.first()) {
            QuickPicks.QUICK_PICKS -> {
                val relatedSongs = database.quickPicks().first().filter { it.isAllowedContent() }.filterVideoSongs(hideVideoSongs)
                val forgotten = database.forgottenFavorites().first().filter { it.isAllowedContent() }.filterVideoSongs(hideVideoSongs).take(8)

                // Get similar songs from YouTube based on recent listening
                val recentSong = database.events().first().firstOrNull()?.song
                val ytSimilarSongs = mutableListOf<Song>()

                if (recentSong != null) {
                    val endpoint = YouTube.next(WatchEndpoint(videoId = recentSong.id)).getOrNull()?.relatedEndpoint
                    if (endpoint != null) {
                        YouTube.related(endpoint).onSuccess { page ->
                            // Convert YouTube songs to local Song format if they exist in database
                            page.songs.filterAllowedArtists().take(10).forEach { ytSong ->
                                database.song(ytSong.id).first()?.let { localSong ->
                                    if (localSong.isAllowedContent() && (!hideVideoSongs || !localSong.song.isVideo)) {
                                        ytSimilarSongs.add(localSong)
                                    }
                                }
                            }
                        }
                    }
                }

                // Combine all sources and remove duplicates
                val combined = (relatedSongs + forgotten + ytSimilarSongs)
                    .distinctBy { it.id }
                    .shuffled()
                    .take(20)

                quickPicks.value = combined.ifEmpty { relatedSongs.filter { it.isAllowedContent() }.shuffled().take(20) }
            }
            QuickPicks.LAST_LISTEN -> {
                val song = database.events().first().firstOrNull()?.song
                if (song != null && database.hasRelatedSongs(song.id)) {
                    quickPicks.value = database.getRelatedSongs(song.id).first().filter { it.isAllowedContent() }.filterVideoSongs(hideVideoSongs).shuffled().take(20)
                }
            }
        }
    }

    private suspend fun getCommunityPlaylists() {
        val fromTimeStamp = LocalDateTime.now().minusWeeks(4)
        val artistSeeds = database.mostPlayedArtists(fromTimeStamp, limit = 10).first()
            .filter { it.artist.isYouTubeArtist }
            .filter { it.isAllowedArtist() }
            .shuffled().take(3)
        val songSeeds = database.mostPlayedSongs(fromTimeStamp, limit = 5).first()
            .filter { it.isAllowedContent() }
            .shuffled().take(2)

        val candidatePlaylists = java.util.Collections.synchronizedList(mutableListOf<PlaylistItem>())

        kotlinx.coroutines.coroutineScope {
            artistSeeds.map { seed ->
                launch(Dispatchers.IO) {
                    YouTube.artist(seed.id).onSuccess { page ->
                        page.sections.forEach { section ->
                            section.items.filterIsInstance<PlaylistItem>().filterAllowedArtists().forEach { playlist ->
                                if (playlist.author?.name != "YouTube Music" && 
                                    playlist.author?.name != "YouTube" && 
                                    playlist.author?.name != "Playlist" &&
                                    playlist.author?.name != seed.artist.name &&
                                    !playlist.id.startsWith("RD") &&
                                    !playlist.id.startsWith("OLAK")
                                ) {
                                    candidatePlaylists.add(playlist)
                                }
                            }
                        }
                    }
                }
            }
            
            songSeeds.map { seed ->
                launch(Dispatchers.IO) {
                    val endpoint = YouTube.next(WatchEndpoint(videoId = seed.id)).getOrNull()?.relatedEndpoint
                    if (endpoint != null) {
                        YouTube.related(endpoint).onSuccess { page ->
                            page.playlists.filterAllowedArtists().forEach { playlist ->
                                if (playlist.author?.name != "YouTube Music" && 
                                    playlist.author?.name != "YouTube" && 
                                    playlist.author?.name != "Playlist" &&
                                    !playlist.id.startsWith("RD") &&
                                    !playlist.id.startsWith("OLAK")
                                ) {
                                    candidatePlaylists.add(playlist)
                                }
                            }
                        }
                    }
                }
            }
        }

        val uniqueCandidates = candidatePlaylists.distinctBy { it.id }.shuffled().take(5)

        val playlists = java.util.Collections.synchronizedList(mutableListOf<CommunityPlaylistItem>())

        kotlinx.coroutines.coroutineScope {
            uniqueCandidates.map { playlist ->
                launch(Dispatchers.IO) {
                    YouTube.playlist(playlist.id).onSuccess { page ->
                        if (!YouTubeArtistSearchFilter.isAllowed(page.playlist)) return@onSuccess
                        val songs = page.songs.filterAllowedArtists().take(10)
                        if (songs.isNotEmpty()) {
                            // Use song count from the playlist page if available, otherwise use original
                            val songCountText = page.playlist.songCountText ?: playlist.songCountText
                            val updatedPlaylist = playlist.copy(songCountText = songCountText)
                            playlists.add(CommunityPlaylistItem(updatedPlaylist, songs))
                        }
                    }
                }
            }.forEach { it.join() }
        }

        communityPlaylists.value = playlists.shuffled()
    }

    private suspend fun loadRecentlyPlayedSongs(hideVideoSongs: Boolean) {
        val recentWindowStart = LocalDateTime.now().minusDays(30)
        recentlyPlayedSongs.value =
            database.events().first()
                .filter { it.event.timestamp.isAfter(recentWindowStart) || it.event.timestamp.isEqual(recentWindowStart) }
                .map { it.song }
                .filter { it.isAllowedContent() }
                .filterVideoSongs(hideVideoSongs)
                .distinctBy { it.id }
                .take(HOME_MAX_ARTIST_ITEMS)
    }

    private suspend fun applyRemoteHomeFeedPayload(payload: RemoteHomeFeedPayload) {
        val releaseItems = payload.items
            .filter { YouTubeArtistSearchFilter.isAllowedArtistId(it.artistChannelId) }
            .sortedByDescending { it.releaseDate.orEmpty() }
            .mapNotNull { it.toYTItem() }
            .filterAllowedArtists()
            .distinctBy { it.id }
            .take(HOME_MAX_REMOTE_RELEASE_ITEMS)

        allowedArtistReleases.value = releaseItems

        val followedArtistIds = database.artistsBookmarkedByCreateDateAsc()
            .first()
            .mapNotNull { it.artist.channelId ?: it.artist.id }
            .filter { YouTubeArtistSearchFilter.isAllowedArtistId(it) }
            .toSet()

        followedArtistReleases.value = payload.followedReleaseItems(followedArtistIds)

        popularArtists.value = payload.popularArtists
            .mapNotNull { it.toPopularHomeArtist() }
            .distinctBy { it.artist.channelId ?: it.artist.id }
            .sortedWith(
                compareByDescending<PopularHomeArtist> { it.monthlyListenerCount ?: Long.MIN_VALUE }
                    .thenBy { it.artist.title }
            )
            .take(HOME_MAX_ARTIST_ITEMS)

        popularSongs.value = payload.popularSongs
            .mapNotNull { it.toPopularHomeSong() }
            .filter { YouTubeArtistSearchFilter.isAllowed(it.song) }
            .distinctBy { it.song.id }
            .sortedWith(
                compareByDescending<PopularHomeSong> { it.monthlyPlayCount ?: Long.MIN_VALUE }
                    .thenBy { it.song.title }
            )
            .take(HOME_MAX_POPULAR_SONG_ITEMS)

        artistGenreShelves.value = payload.artistGenreShelves
            .mapNotNull { it.toArtistGenreHomeShelf() }
            .distinctBy { it.id }
    }

    private suspend fun loadRemoteHomeFeed() {
        val cachedPayload = RemoteHomeFeedUpdater.loadCachedFeed(context)
        if (!cachedPayload.isEmpty) {
            remoteHomeFeedPayload.value = cachedPayload
            applyRemoteHomeFeedPayload(cachedPayload)
        }

        val refreshedPayload = RemoteHomeFeedUpdater.refreshFromRemote(context)
        if (!refreshedPayload.isEmpty) {
            remoteHomeFeedPayload.value = refreshedPayload
            applyRemoteHomeFeedPayload(refreshedPayload)
        } else if (cachedPayload.isEmpty) {
            allowedArtistReleases.value = emptyList()
            followedArtistReleases.value = emptyList()
            popularArtists.value = emptyList()
            popularSongs.value = emptyList()
            artistGenreShelves.value = emptyList()
        }
    }

    private suspend fun loadFavorites() {
        val likedSongs = database.likedSongsByCreateDateAsc().first().filter { it.isAllowedContent() }
        val likedAlbums = database.albumsLikedByCreateDateAsc().first().filter { it.isAllowedContent() }

        favorites.value =
            (likedSongs + likedAlbums)
                .sortedByDescending { item ->
                    when (item) {
                        is Song -> item.song.likedDate ?: LocalDateTime.MIN
                        is Album -> item.album.likedDate ?: LocalDateTime.MIN
                        else -> LocalDateTime.MIN
                    }
                }
                .take(20)
    }

    private suspend fun load() {
        isLoading.value = true
        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
        val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
        val fromTimeStamp = LocalDateTime.now().minusWeeks(2)

        // Phase 1: Load essential sections in parallel — local DB (fast) + YouTube home page.
        // isLoading is set to false as soon as all Phase 1 tasks complete so the UI appears quickly.
        coroutineScope {
            launch(Dispatchers.IO) { getQuickPicks() }

            launch(Dispatchers.IO) { loadRecentlyPlayedSongs(hideVideoSongs) }
            launch(Dispatchers.IO) {
                forgottenFavorites.value = database.forgottenFavorites().first()
                    .filter { it.isAllowedContent() }
                    .filterVideoSongs(hideVideoSongs).shuffled().take(20)
            }

            launch(Dispatchers.IO) {
                val songs = database.mostPlayedSongs(fromTimeStamp, limit = 15, offset = 5).first()
                    .filter { it.isAllowedContent() }
                    .filterVideoSongs(hideVideoSongs).shuffled().take(10)
                val albums = database.mostPlayedAlbums(fromTimeStamp, limit = 8, offset = 2).first()
                    .filter { it.album.thumbnailUrl != null }
                    .filter { it.isAllowedContent() }
                    .shuffled().take(5)
                val artists = database.mostPlayedArtists(fromTimeStamp).first()
                    .filter { it.artist.isYouTubeArtist && it.artist.thumbnailUrl != null }
                    .filter { it.isAllowedArtist() }
                    .shuffled().take(5)
                keepListening.value = (songs + albums + artists).shuffled()
            }

            launch(Dispatchers.IO) { loadFavorites() }
            launch(Dispatchers.IO) { loadRemoteHomeFeed() }

            launch(Dispatchers.IO) {
                YouTube.home().onSuccess { page ->
                    homePage.value = page.copy(
                        sections = page.sections.mapNotNull { section ->
                            val filtered = section.items
                                .filterAllowedArtists()
                                .filterExplicit(hideExplicit)
                                .filterVideoSongs(hideVideoSongs)
                                .filterYoutubeShorts(hideYoutubeShorts)
                            if (filtered.isEmpty()) null else section.copy(items = filtered)
                        }
                    )
                }.onFailure { reportException(it) }
            }

            if (YouTube.cookie != null) {
                launch(Dispatchers.IO) { loadAccountPlaylists() }
            }
        }

        allLocalItems.value = (keepListening.value.orEmpty() + favorites.value.orEmpty() + forgottenFavorites.value.orEmpty())
            .filter { it is Song || it is Album || it is Playlist || it is com.metrolist.music.db.entities.Artist }
            .filter { item ->
                if (item is Song) item.isAllowedContent() else item.matchesAllowedArtist()
            }
        isLoading.value = false

        // Phase 2: Heavy multi-request operations — run in background without blocking the UI.
        viewModelScope.launch(Dispatchers.IO) { getDailyDiscover() }

        viewModelScope.launch(Dispatchers.IO) { getCommunityPlaylists() }

        viewModelScope.launch(Dispatchers.IO) {
            YouTube.explore().onSuccess { page ->
                explorePage.value = page.copy(
                    newReleaseAlbums = page.newReleaseAlbums.filterAllowedArtists().filterExplicit(hideExplicit)
                )
            }.onFailure { reportException(it) }
        }

        viewModelScope.launch(Dispatchers.IO) {
            val artistRecommendations = database.mostPlayedArtists(fromTimeStamp, limit = 15).first()
                .filter { it.artist.isYouTubeArtist }
                .filter { it.isAllowedArtist() }
                .shuffled().take(4)
                .mapNotNull {
                    val items = mutableListOf<YTItem>()
                    YouTube.artist(it.id).onSuccess { page ->
                        page.sections.takeLast(3).forEach { section -> items += section.items.filterAllowedArtists() }
                    }
                    SimilarRecommendation(
                        title = it,
                        items = items
                            .distinctBy { item -> item.id }
                            .filterExplicit(hideExplicit)
                            .filterVideoSongs(hideVideoSongs)
                            .shuffled().take(12)
                            .ifEmpty { return@mapNotNull null }
                    )
                }

            val songRecommendations = database.mostPlayedSongs(fromTimeStamp, limit = 15).first()
                .filter { it.album != null }
                .filter { it.isAllowedContent() }
                .shuffled().take(3)
                .mapNotNull { song ->
                    val endpoint = YouTube.next(WatchEndpoint(videoId = song.id)).getOrNull()?.relatedEndpoint
                        ?: return@mapNotNull null
                    val page = YouTube.related(endpoint).getOrNull() ?: return@mapNotNull null
                    SimilarRecommendation(
                        title = song,
                        items = (page.songs.filterAllowedArtists().shuffled().take(10) +
                                page.albums.filterAllowedArtists().shuffled().take(5) +
                                page.artists.filterAllowedArtists().shuffled().take(3) +
                                page.playlists.filterAllowedArtists().shuffled().take(3))
                            .distinctBy { it.id }
                            .filterAllowedArtists()
                            .filterExplicit(hideExplicit)
                            .filterVideoSongs(hideVideoSongs)
                            .shuffled()
                            .ifEmpty { return@mapNotNull null }
                    )
                }

            val albumRecommendations = database.mostPlayedAlbums(fromTimeStamp, limit = 10).first()
                .filter { it.album.thumbnailUrl != null }
                .filter { it.isAllowedContent() }
                .shuffled().take(2)
                .mapNotNull { album ->
                    val items = mutableListOf<YTItem>()
                    YouTube.album(album.id).onSuccess { page ->
                        page.filterAllowedArtists().otherVersions.let { items += it }
                    }
                    album.artists.firstOrNull { it.matchesAllowedArtist() }?.id?.let { artistId ->
                        YouTube.artist(artistId).onSuccess { page ->
                            page.filterAllowedArtists().sections.lastOrNull()?.items?.let { items += it }
                        }
                    }
                    SimilarRecommendation(
                        title = album,
                        items = items
                            .distinctBy { it.id }
                            .filterAllowedArtists()
                            .filterExplicit(hideExplicit)
                            .filterVideoSongs(hideVideoSongs)
                            .shuffled().take(10)
                            .ifEmpty { return@mapNotNull null }
                    )
                }

            similarRecommendations.value = (artistRecommendations + songRecommendations + albumRecommendations).shuffled()
            allYtItems.value = (allowedArtistReleases.value.orEmpty() +
                    followedArtistReleases.value.orEmpty() +
                    popularSongs.value.orEmpty().map { it.song } +
                    artistGenreShelves.value.orEmpty().flatMap { it.artists } +
                    similarRecommendations.value?.flatMap { it.items }.orEmpty() +
                    homePage.value?.sections?.flatMap { it.items }.orEmpty())
                .distinctBy { it.id }
                .filterAllowedArtists()
        }
    }

    private val _isLoadingMore = MutableStateFlow(false)
    fun loadMoreYouTubeItems(continuation: String?) {
        if (continuation == null || _isLoadingMore.value) return
        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
        val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)

        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingMore.value = true
            val nextSections = YouTube.home(continuation).getOrNull() ?: run {
                _isLoadingMore.value = false
                return@launch
            }

            homePage.value = nextSections.copy(
                chips = homePage.value?.chips,
                sections = (homePage.value?.sections.orEmpty() + nextSections.sections).mapNotNull { section ->
                    val filteredItems = section.items.filterAllowedArtists().filterExplicit(hideExplicit).filterVideoSongs(hideVideoSongs).filterYoutubeShorts(hideYoutubeShorts)
                    if (filteredItems.isEmpty()) null else section.copy(items = filteredItems)
                }
            )
            _isLoadingMore.value = false
        }
    }

    fun toggleChip(chip: HomePage.Chip?) {
        if (chip == null || chip == selectedChip.value && previousHomePage.value != null) {
            homePage.value = previousHomePage.value
            previousHomePage.value = null
            selectedChip.value = null
            return
        }

        if (selectedChip.value == null) {
            previousHomePage.value = homePage.value
        }

        viewModelScope.launch(Dispatchers.IO) {
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
            val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
            val nextSections = YouTube.home(params = chip.endpoint?.params).getOrNull() ?: return@launch

            homePage.value = nextSections.copy(
                chips = homePage.value?.chips,
                sections = nextSections.sections.map { section ->
                    section.copy(items = section.items.filterAllowedArtists().filterExplicit(hideExplicit).filterVideoSongs(hideVideoSongs).filterYoutubeShorts(hideYoutubeShorts))
                }
            )
            selectedChip.value = chip

            // Fetch podcast-specific data when podcasts chip is selected
            if (chip.title.contains("Podcast", ignoreCase = true)) {
                fetchPodcastData()
            }
        }
    }

    private suspend fun fetchPodcastData() {
        // Fetch saved podcast shows from official API
        YouTube.savedPodcastShows().onSuccess { shows ->
            savedPodcastShows.value = shows.filterAllowedArtists()
        }.onFailure {
            reportException(it)
        }

        // Fetch episodes for later from official API
        YouTube.episodesForLater().onSuccess { episodes ->
            episodesForLater.value = episodes.filterAllowedArtists()
        }.onFailure {
            reportException(it)
        }
    }

    private suspend fun loadAccountPlaylists() {
        val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
        YouTube.library("FEmusic_liked_playlists").completed().onSuccess {
            accountPlaylists.value = it.items.filterIsInstance<PlaylistItem>()
                .filterAllowedArtists()
                .filterNot { it.id == "SE" }
                .filterYoutubeShorts(hideYoutubeShorts)
        }.onFailure {
            reportException(it)
        }
    }

    fun refresh() {
        if (isRefreshing.value) return
        isRefreshing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            com.metrolist.music.utils.RemoteAllowedArtistsUpdater.refreshFromRemote(context)
            com.metrolist.music.utils.RemoteBlockedSongsUpdater.refreshFromRemote(context)
            // If a chip is selected, reload the chip's content instead of the default home
            val currentChip = selectedChip.value
            if (currentChip != null) {
                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
                val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
                val nextSections = YouTube.home(params = currentChip.endpoint?.params).getOrNull()
                if (nextSections != null) {
                    homePage.value = nextSections.copy(
                        chips = homePage.value?.chips,
                        sections = nextSections.sections.map { section ->
                            section.copy(items = section.items.filterAllowedArtists().filterExplicit(hideExplicit).filterVideoSongs(hideVideoSongs).filterYoutubeShorts(hideYoutubeShorts))
                        }
                    )
                }
            } else {
                load()
            }
            isRefreshing.value = false
        }
        // Run sync when user manually refreshes
        viewModelScope.launch(Dispatchers.IO) {
            syncUtils.tryAutoSync()
        }
    }

    override fun onCleared() {
        super.onCleared()
        wrappedManager.dispose()
    }

    init {
        // Run sync in separate coroutine with cooldown to avoid blocking UI
        viewModelScope.launch(Dispatchers.IO) {
            syncUtils.tryAutoSync()
        }

        // Rebuild "חדש מהאמנים שלכם" instantly when an artist is followed or unfollowed.
        // This uses the existing remote home-feed cache and does not block the UI with a full network refresh.
        viewModelScope.launch(Dispatchers.IO) {
            database.artistsBookmarkedByCreateDateAsc()
                .map { artists ->
                    artists
                        .mapNotNull { it.artist.channelId ?: it.artist.id }
                        .filter { YouTubeArtistSearchFilter.isAllowedArtistId(it) }
                        .toSet()
                }
                .distinctUntilChanged()
                .collect { followedArtistIds ->
                    val payload = remoteHomeFeedPayload.value ?: RemoteHomeFeedUpdater.loadCachedFeed(context)
                    if (!payload.isEmpty) {
                        remoteHomeFeedPayload.value = payload
                        followedArtistReleases.value = payload.followedReleaseItems(followedArtistIds)
                    } else {
                        followedArtistReleases.value = emptyList()
                    }
                }
        }

        // Prepare wrapped data in background
        viewModelScope.launch(Dispatchers.IO) {
            showWrappedCard.collect { shouldShow ->
                if (shouldShow && !wrappedManager.state.value.isDataReady) {
                    try {
                        wrappedManager.prepare()
                        val state = wrappedManager.state.first { it.isDataReady }
                        val trackMap = state.trackMap
                        if (trackMap.isNotEmpty()) {
                            val firstTrackId = trackMap.entries.first().value
                            wrappedAudioService.prepareTrack(firstTrackId)
                        }
                    } catch (e: Exception) {
                        reportException(e)
                    }
                }
            }
        }

        // Listen for cookie changes and reload account data
        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.data
                .map { it[InnerTubeCookieKey] }
                .collect { cookie ->
                    if (isProcessingAccountData) return@collect

                    lastProcessedCookie = cookie
                    isProcessingAccountData = true

                    try {
                        if (cookie != null && cookie.isNotEmpty()) {
                            YouTube.cookie = cookie

                            YouTube.accountInfo().onSuccess { info ->
                                accountName.value = info.name
                                accountImageUrl.value = info.thumbnailUrl
                            }.onFailure {
                                reportException(it)
                            }
                        } else {
                            accountName.value = "Guest"
                            accountImageUrl.value = null
                            accountPlaylists.value = null
                        }
                    } finally {
                        isProcessingAccountData = false
                    }
                }
        }

        // Listen for HideYoutubeShorts preference changes and reload account playlists instantly
        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.data
                .map { it[HideYoutubeShortsKey] ?: false }
                .distinctUntilChanged()
                .collect {
                    if (YouTube.cookie != null && accountPlaylists.value != null) {
                        loadAccountPlaylists()
                    }
                }
        }
    }

    private var isHomeDataLoaded = false

    fun loadHomeData() {
        if (isHomeDataLoaded) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cookie = context.dataStore.data
                    .map { it[InnerTubeCookieKey] }
                    .distinctUntilChanged()
                    .first()

                if (!cookie.isNullOrEmpty()) {
                    YouTube.cookie = cookie
                }

                isHomeDataLoaded = true
                load()
            } catch (e: Exception) {
                isHomeDataLoaded = false
                Timber.e(e, "Failed to load home data")
            }
        }
    }
}
