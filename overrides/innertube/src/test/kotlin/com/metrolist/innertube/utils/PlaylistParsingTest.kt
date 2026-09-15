package com.metrolist.innertube.utils

import com.metrolist.innertube.models.response.BrowseResponse
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.pages.PlaylistPage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalSerializationApi::class)
class PlaylistParsingTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private fun page(name: String) = PlaylistPage.songPage(
        json.decodeFromString<BrowseResponse>(
            javaClass.getResource("/" + name)!!.readText(),
        ),
    )

    @Test fun bothLayoutsKeepTracksAfterAHeaderWithoutRequiringAnEditIdOrArtwork() {
        for (file in listOf("playlist-two-column.json", "playlist-single-column.json")) {
            val result = page(file)
            assertEquals(listOf("abcdefghijk", "second12345"), result.songs.map { it.id })
            assertEquals("page2", result.continuation)
            assertNull(result.songs.first().setVideoId)
            assertEquals("", result.songs.first().thumbnail)
            assertEquals("editable-key", result.songs.last().setVideoId)
            assertTrue(result.songs.first().artists.any { it.id == "UCaaaaaaaaaaaaaaaaaaaaaa" })
            assertTrue(result.songs.first().artists.any { it.id == "UCbbbbbbbbbbbbbbbbbbbbbb" })
        }
    }

    @Test fun anUnparseableOrEmptyPageDoesNotDiscardItsContinuation() {
        val result = page("playlist-empty-continuation.json")
        assertTrue(result.songs.isEmpty())
        assertEquals("later", result.continuation)
    }

    @Test fun continuationActionsAreNotLimitedToTheFirstAction() {
        val result = page("playlist-appended-continuation.json")
        assertEquals(listOf("abcdefghijk"), result.songs.map { it.id })
        assertEquals("more", result.continuation)
    }

    @Test fun browseIdsAreNormalizedOnlyOnce() {
        assertEquals("PLabc", PlaylistPage.normalizeId("VLPLabc"))
        assertEquals("PLabc", PlaylistPage.normalizeId("PLabc"))
    }

    // Track rows captured from a public mixed playlist on 2026-09-15.
    @Test fun realPlaylistRowsKeepApprovedAudioAndRejectOtherArtistsAndVideos() {
        val allowedIds = listOf("UCw1oCoEMF_o65tpDHItwwUQ", "UCEg5tmfi6WmJZRnezYLVHSg")
        YouTubeArtistSearchFilter.replaceAllowedArtists(allowedIds.map { AllowedArtist(it, it) })
        YouTubeBlockedSongFilter.replaceBlockedSongIds(emptyList())
        try {
            val result = page("playlist-real-mixed.json").songs.filterAllowedArtists().filterVideoSongs(true)
            assertEquals(listOf("4Di8dv8wPwQ", "JfJufy7dpO0", "w-Vywfv3U_M", "2MtGkvRaWlw"), result.map { it.id })
            YouTubeBlockedSongFilter.replaceBlockedSongIds(listOf(result.first().id))
            val blocked = page("playlist-real-mixed.json").songs.filterAllowedArtists().filterVideoSongs(true)
            assertEquals(result.drop(1).map { it.id }, blocked.map { it.id })
        } finally {
            YouTubeArtistSearchFilter.restoreBundledAllowedArtists()
            YouTubeBlockedSongFilter.replaceBlockedSongIds(emptyList())
        }
    }
}
