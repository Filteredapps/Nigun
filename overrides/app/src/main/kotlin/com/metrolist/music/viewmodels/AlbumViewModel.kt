/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.utils.YouTubeArtistSearchFilter
import com.metrolist.innertube.utils.filterAllowedArtists
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import com.metrolist.music.R
import javax.inject.Inject
import com.metrolist.music.utils.isAllowedContent

@HiltViewModel
class AlbumViewModel
@Inject
constructor(
    private val database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val albumId = savedStateHandle.get<String>("albumId")!!
    val playlistId = MutableStateFlow("")
    val albumWithSongs =
        database
            .albumWithSongs(albumId)
            .map { album -> album?.takeIf { it.isAllowedContent() }?.copy(songs = album.songs.filter { it.isAllowedContent() }) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    var otherVersions = MutableStateFlow<List<AlbumItem>>(emptyList())

    val isLoading = MutableStateFlow(true)
    val error = MutableStateFlow<Int?>(null)
    private var loadJob: Job? = null

    init { retry() }

    fun retry() {
        if (loadJob?.isActive == true) return
        isLoading.value = true
        error.value = null
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withTimeout(30_000L) {
                    val existing = database.album(albumId).first()
                    val page = YouTube.album(albumId).getOrThrow()
                    val filteredPage = page.filterAllowedArtists()
                    if (!YouTubeArtistSearchFilter.isAllowed(page.album) || filteredPage.songs.isEmpty()) {
                        playlistId.value = ""
                        otherVersions.value = emptyList()
                        error.value = R.string.nigun_no_allowed_songs
                    } else {
                        playlistId.value = filteredPage.album.playlistId
                        otherVersions.value = filteredPage.otherVersions
                        database.transaction {
                            if (existing == null) insert(filteredPage)
                            else update(existing.album, filteredPage, existing.artists)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                error.value = R.string.nigun_loading_failed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                error.value = R.string.nigun_loading_failed
            } finally {
                isLoading.value = false
            }
        }
    }
}
