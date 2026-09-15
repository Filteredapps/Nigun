/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.utils.PlaylistSongPager
import com.metrolist.innertube.utils.YouTubeArtistSearchFilter
import com.metrolist.innertube.utils.filterAllowedArtists
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import com.metrolist.music.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.metrolist.music.constants.SongSortType
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.Album
import javax.inject.Inject

@HiltViewModel
class OnlinePlaylistViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val database: MusicDatabase
) : ViewModel() {
    private val playlistId = savedStateHandle.get<String>("playlistId")!!

    // Check if this is a special podcast playlist (with or without VL prefix)
    private val normalizedPlaylistId = playlistId.removePrefix("VL")
    val isPodcastPlaylist = normalizedPlaylistId == "RDPN" || normalizedPlaylistId == "SE"

    val playlist = MutableStateFlow<PlaylistItem?>(null)
    val playlistSongs = MutableStateFlow<List<SongItem>>(emptyList())

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore = _isLoadingMore.asStateFlow()

    val dbPlaylist = database.playlistByBrowseId(playlistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    var continuation: String? = null
        private set

    private var proactiveLoadJob: Job? = null
    private var initialLoadJob: Job? = null
    val hasMore = MutableStateFlow(false)
    private var songPager: PlaylistSongPager? = null

    init {
        fetchInitialPlaylistData()
    }

    private fun fetchInitialPlaylistData() {
        initialLoadJob?.cancel()
        proactiveLoadJob?.cancel()
        initialLoadJob = viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _isLoadingMore.value = false
            _error.value = null
            continuation = null
            hasMore.value = false
            songPager = null
            try {
                if (isPodcastPlaylist) {
                    withTimeout(30_000L) { fetchPodcastPlaylist() }
                } else {
                    fetchRegularPlaylist()
                }
                if (continuation != null) startProactiveBackgroundLoading()
            } catch (e: TimeoutCancellationException) {
                _error.value = context.getString(R.string.nigun_loading_failed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                _error.value = context.getString(R.string.nigun_loading_failed)
            } finally {
                if (isActive) _isLoading.value = false
            }
        }
    }

    private suspend fun fetchPodcastPlaylist() {
        when (normalizedPlaylistId) {
            "RDPN" -> {
                YouTube.newEpisodes()
                    .onSuccess { episodes ->
                        playlist.value = PlaylistItem(
                            id = playlistId,
                            title = "New Episodes",
                            author = null,
                            songCountText = "${episodes.size} episodes",
                            thumbnail = episodes.firstOrNull()?.thumbnail ?: "",
                            playEndpoint = null,
                            shuffleEndpoint = null,
                            radioEndpoint = null,
                        )
                        playlistSongs.value = applySongFilters(episodes)
                        _isLoading.value = false
                    }.onFailure { throwable ->
                        _error.value = throwable.message ?: "Failed to load new episodes"
                        _isLoading.value = false
                        reportException(throwable)
                    }
            }
            "SE" -> {
                timber.log.Timber.d("[SE_LOCAL] Fetching SE playlist...")
                val result = YouTube.episodesForLater()
                val episodes = result.getOrNull() ?: emptyList()
                timber.log.Timber.d("[SE_LOCAL] YouTube API result: ${if (result.isSuccess) "success" else "failed"}, ${episodes.size} episodes")

                if (result.isSuccess && episodes.isNotEmpty()) {
                    // Use YouTube episodes
                    playlist.value = PlaylistItem(
                        id = playlistId,
                        title = "Episodes for Later",
                        author = null,
                        songCountText = "${episodes.size} episodes",
                        thumbnail = episodes.firstOrNull()?.thumbnail ?: "",
                        playEndpoint = null,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                    )
                    playlistSongs.value = applySongFilters(episodes)
                    _isLoading.value = false
                } else {
                    // Fall back to local saved episodes when API fails or returns empty
                    timber.log.Timber.d("[SE_LOCAL] Falling back to local saved episodes")
                    loadLocalSavedEpisodes()
                }
            }
            else -> {
                _error.value = "Unknown podcast playlist"
                _isLoading.value = false
            }
        }
    }

    private suspend fun fetchRegularPlaylist() {
        val page = withTimeout(30_000L) { YouTube.playlist(playlistId).getOrThrow() }
        val pager = PlaylistSongPager(
            filter = ::applySongFilters,
            fetchNext = { token ->
                withTimeout(30_000L) { YouTube.playlistContinuation(token).getOrThrow() }
            },
        )
        pager.start(page.songs, page.songsContinuation)
        coroutineContext.ensureActive()
        songPager = pager
        playlist.value = page.playlist
        publishSongs(pager)
    }

    private fun publishSongs(pager: PlaylistSongPager) {
        if (songPager !== pager) return
        playlistSongs.value = pager.songs
        continuation = pager.continuation
        hasMore.value = continuation != null
    }

    private suspend fun loadLocalSavedEpisodes() {
        timber.log.Timber.d("[SE_LOCAL] loadLocalSavedEpisodes called")
        val savedEpisodes = database.savedPodcastEpisodes(SongSortType.CREATE_DATE, true).firstOrNull() ?: emptyList()
        timber.log.Timber.d("[SE_LOCAL] Found ${savedEpisodes.size} saved episodes")
        savedEpisodes.forEachIndexed { index, ep ->
            timber.log.Timber.d("[SE_LOCAL] Episode $index: id=${ep.song.id}, title=${ep.song.title}, isEpisode=${ep.song.isEpisode}, inLibrary=${ep.song.inLibrary}")
        }
        if (savedEpisodes.isNotEmpty()) {
            // Convert local Song entities to SongItem format
            val songItems = savedEpisodes.map { song ->
                SongItem(
                    id = song.song.id,
                    title = song.song.title,
                    artists = song.artists.map { Artist(it.id, it.name) },
                    album = song.album?.let { com.metrolist.innertube.models.Album(it.id, it.title) },
                    duration = song.song.duration,
                    thumbnail = song.song.thumbnailUrl ?: "",
                    explicit = song.song.explicit,
                    endpoint = null,
                )
            }
            timber.log.Timber.d("[SE_LOCAL] Converted to ${songItems.size} SongItems")
            playlist.value = PlaylistItem(
                id = playlistId,
                title = "Episodes for Later",
                author = null,
                songCountText = "${songItems.size} episodes",
                thumbnail = songItems.firstOrNull()?.thumbnail ?: "",
                playEndpoint = null,
                shuffleEndpoint = null,
                radioEndpoint = null,
            )
            val filtered = applySongFilters(songItems)
            timber.log.Timber.d("[SE_LOCAL] After filter: ${filtered.size} episodes, setting playlistSongs")
            playlistSongs.value = filtered
            _isLoading.value = false
            timber.log.Timber.d("[SE_LOCAL] Done, isLoading=false")
        } else {
            timber.log.Timber.d("[SE_LOCAL] No saved episodes found")
            _error.value = "No saved episodes"
            _isLoading.value = false
        }
    }

    private fun startProactiveBackgroundLoading() {
        if (proactiveLoadJob?.isActive == true || continuation == null) return
        val pager = songPager ?: return
        proactiveLoadJob = viewModelScope.launch(Dispatchers.IO) {
            _isLoadingMore.value = true
            try {
                while (isActive && pager.continuation != null) {
                    pager.loadNext()
                    coroutineContext.ensureActive()
                    publishSongs(pager)
                }
                _error.value = null
            } catch (e: TimeoutCancellationException) {
                _error.value = context.getString(R.string.nigun_loading_failed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                _error.value = context.getString(R.string.nigun_loading_failed)
            } finally {
                if (isActive) _isLoadingMore.value = false
            }
        }
    }

    fun loadMoreSongs() {
        if (_isLoading.value) return
        startProactiveBackgroundLoading()
    }

    fun retry() {
        if (playlist.value != null && continuation != null) loadMoreSongs() else fetchInitialPlaylistData()
    }

    private suspend fun applySongFilters(songs: List<SongItem>): List<SongItem> {
        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
        val unresolved = songs.filterNot { YouTubeArtistSearchFilter.isAllowed(it) }
        val resolved = YouTube.resolveArtistIds(unresolved).associateBy { it.id }
        return songs.map { resolved[it.id] ?: it }
            .distinctBy { it.id }
            .filterAllowedArtists()
            .filterVideoSongs(hideVideoSongs)
    }

    override fun onCleared() {
        super.onCleared()
        initialLoadJob?.cancel()
        proactiveLoadJob?.cancel()
    }
}
