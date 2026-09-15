package com.metrolist.innertube.utils

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.PlaylistContinuationPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlaylistSongPagerTest {
    private val allowed = Artist("Allowed", "UCaaaaaaaaaaaaaaaaaaaaaa")
    private val foreign = Artist("Other", "UCbbbbbbbbbbbbbbbbbbbbbb")
    private fun song(id: String, artists: List<Artist>) =
        SongItem(id = id, title = id, artists = artists, thumbnail = "")

    @Before fun setup() {
        YouTubeArtistSearchFilter.replaceAllowedArtists(listOf(AllowedArtist(allowed.name, allowed.id!!)))
        YouTubeBlockedSongFilter.replaceBlockedSongIds(listOf("blocked1234"))
    }
    @After fun cleanup() {
        YouTubeArtistSearchFilter.restoreBundledAllowedArtists()
        YouTubeBlockedSongFilter.replaceBlockedSongIds(emptyList())
    }

    @Test fun allPagesLoadEvenAfterManyEmptyOrBlockedPagesAndMixedCreditsAreAllowed() = runBlocking {
        var calls = 0
        val mixed = song("abcdefghijk", listOf(foreign, allowed))
        val blocked = song("blocked1234", listOf(allowed))
        val other = song("foreign1234", listOf(foreign))
        val last = song("lastsong123", listOf(allowed))
        val pager = PlaylistSongPager(
            filter = { it.filterAllowedArtists() },
            fetchNext = { token ->
                calls++
                val index = token.toInt()
                PlaylistContinuationPage(
                    if (index < 7) listOf(other, blocked) else if (index == 7) listOf(mixed) else listOf(mixed, last),
                    if (index < 8) (index + 1).toString() else null,
                )
            },
        )
        pager.start(listOf(other), "1")
        while (pager.continuation != null) pager.loadNext()
        assertEquals(8, calls)
        assertEquals(listOf(mixed, last), pager.songs)
        assertNull(pager.continuation)
    }

    @Test fun failurePreservesLoadedSongsAndCursorForRetry() = runBlocking {
        var attempts = 0
        val initial = song("abcdefghijk", listOf(allowed))
        val next = song("lastsong123", listOf(allowed))
        val pager = PlaylistSongPager(
            filter = { it.filterAllowedArtists() },
            fetchNext = {
                if (++attempts == 1) throw java.io.IOException("temporary")
                PlaylistContinuationPage(listOf(next), null)
            },
        )
        pager.start(listOf(initial), "next")
        try { pager.loadNext(); fail("Expected failure") } catch (_: java.io.IOException) { }
        assertEquals(listOf(initial), pager.songs)
        assertEquals("next", pager.continuation)
        pager.loadNext()
        assertEquals(listOf(initial, next), pager.songs)
    }

    @Test fun repeatedCursorStopsAndDuplicateTracksAreNotAdded() = runBlocking {
        val track = song("abcdefghijk", listOf(allowed))
        val pager = PlaylistSongPager({ it }, { PlaylistContinuationPage(listOf(track), "same") })
        pager.start(listOf(track), "same")
        pager.loadNext()
        assertEquals(listOf(track), pager.songs)
        assertNull(pager.continuation)
        assertFalse(pager.loadNext())
    }

    @Test fun cancellationDoesNotConsumeACursor() = runBlocking {
        val pager = PlaylistSongPager({ it }, { throw CancellationException("stop") })
        pager.start(emptyList(), "next")
        try { pager.loadNext(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals("next", pager.continuation)
        assertTrue(pager.songs.isEmpty())
    }
}
