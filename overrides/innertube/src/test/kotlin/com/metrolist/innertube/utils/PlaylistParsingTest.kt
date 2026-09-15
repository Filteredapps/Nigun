package com.metrolist.innertube.utils

import com.metrolist.innertube.models.response.BrowseResponse
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
}
