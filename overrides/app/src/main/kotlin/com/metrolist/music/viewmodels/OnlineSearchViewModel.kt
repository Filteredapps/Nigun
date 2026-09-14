/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.models.filterYoutubeShorts
import com.metrolist.innertube.pages.SearchSummaryPage
import com.metrolist.innertube.utils.filterAllowedArtists
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.HideYoutubeShortsKey
import com.metrolist.music.models.ItemsPage
import com.metrolist.music.utils.SearchRoutes
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnlineSearchViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val query = SearchRoutes.decodeQuery(savedStateHandle.get<String>("query").orEmpty())
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()
    val paginationPaused = mutableStateMapOf<String, Boolean>()
    private val loadingFilters = mutableSetOf<String>()

    private suspend fun loadSummaryPage() {
        if (summaryPage == null) {
            YouTube
                .searchSummary(query)
                .onSuccess { page ->
                    val resolvedSummaries = page.summaries.map { summary ->
                        summary.copy(items = YouTube.resolveArtistIds(summary.items))
                    }
                    val resolvedPage = page.copy(summaries = resolvedSummaries)
                    val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                    val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, true)
                    val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, true)
                    summaryPage =
                        resolvedPage
                            .filterAllowedArtists()
                            .filterExplicit(hideExplicit)
                            .filterVideoSongs(hideVideoSongs)
                            .filterYoutubeShorts(hideYoutubeShorts)
                }.onFailure {
                    reportException(it)
                }
        }
    }

    init {
        viewModelScope.launch {
            filter.collect { filter ->
                if (filter == null) {
                    loadSummaryPage()
                } else if (filter == YouTube.SearchFilter.FILTER_EPISODE) {
                    // The FILTER_EPISODE API returns episodes in a format that differs from the
                    // summary search: playlistItemData is absent and the subtitle structure is
                    // different, making reliable isEpisode detection fail for many items.
                    // Reuse the "Episodes" section from the summary page instead — it is already
                    // parsed correctly by fromMusicResponsiveListItemRenderer and guaranteed to
                    // show the same results as the episodes section in the "All" filter.
                    if (viewStateMap[filter.value] == null) {
                        loadSummaryPage()
                        summaryPage?.let { page ->
                            val episodes = page.summaries
                                .firstOrNull { it.title == "Episodes" }
                                ?.items
                                .orEmpty()
                            viewStateMap[filter.value] = ItemsPage(episodes, null)
                        }
                    }
                } else {
                    if (viewStateMap[filter.value] == null) {
                        YouTube
                            .search(query, filter)
                            .onSuccess { result ->
                                val resolvedItems = YouTube.resolveArtistIds(result.items)
                                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                                val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, true)
                                val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, true)
                                viewStateMap[filter.value] =
                                    ItemsPage(
                                        resolvedItems
                                            .distinctBy { it.id }
                                            .filterAllowedArtists()
                                            .filterExplicit(hideExplicit)
                                            .filterVideoSongs(hideVideoSongs)
                                            .filterYoutubeShorts(hideYoutubeShorts),
                                        result.continuation,
                                    )
                            }.onFailure {
                                reportException(it)
                            }
                    }
                }
            }
        }
    }

    fun loadMore(manual: Boolean = false) {
        val key = filter.value?.value ?: return
        if (key in loadingFilters || (!manual && paginationPaused[key] == true)) return
        if (viewStateMap[key]?.continuation == null) return
        loadingFilters.add(key)
        paginationPaused[key] = false
        viewModelScope.launch {
            try {
                kotlinx.coroutines.withTimeout(30_000L) {
                    val seen = mutableSetOf<String>()
                    repeat(5) {
                        val current = viewStateMap[key] ?: return@withTimeout
                        val token = current.continuation ?: return@withTimeout
                        if (!seen.add(token)) {
                            viewStateMap[key] = current.copy(continuation = null)
                            return@withTimeout
                        }
                        val result = YouTube.searchContinuation(token).getOrThrow()
                        val resolved = YouTube.resolveArtistIds(result.items)
                        val newItems = resolved
                            .filterAllowedArtists()
                            .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                            .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, true))
                            .filterYoutubeShorts(context.dataStore.get(HideYoutubeShortsKey, true))
                        val items = (current.items + newItems).distinctBy { it.id }
                        viewStateMap[key] = ItemsPage(
                            items,
                            result.continuation?.takeUnless { it in seen },
                        )
                        if (items.size > current.items.size || viewStateMap[key]?.continuation == null) {
                            return@withTimeout
                        }
                    }
                    // Let the user continue a heavily filtered search without unbounded requests.
                    paginationPaused[key] = true
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                paginationPaused[key] = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                paginationPaused[key] = true
            } finally {
                loadingFilters.remove(key)
            }
        }
    }
}
