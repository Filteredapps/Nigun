package com.metrolist.innertube.utils

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NigunContentTest {
    private val allowed = Artist("Allowed", "UCaaaaaaaaaaaaaaaaaaaaaa")
    private val foreign = Artist("Other", "UCbbbbbbbbbbbbbbbbbbbbbb")
    private fun song(artists: List<Artist>) =
        SongItem(id = "abcdefghijk", title = "Song", artists = artists, thumbnail = "")

    @Before fun setup() {
        YouTubeArtistSearchFilter.replaceAllowedArtists(listOf(AllowedArtist(allowed.name, allowed.id!!)))
        YouTubeBlockedSongFilter.replaceBlockedSongIds(emptyList())
    }

    @After fun cleanup() {
        YouTubeArtistSearchFilter.restoreBundledAllowedArtists()
        YouTubeBlockedSongFilter.replaceBlockedSongIds(emptyList())
    }

    @Test fun allowedCollaboratorCanAppearInAnyPosition() {
        assertTrue(YouTubeArtistSearchFilter.isAllowed(song(listOf(foreign, allowed))))
        assertTrue(YouTubeArtistSearchFilter.isAllowed(song(listOf(allowed, foreign))))
        assertFalse(YouTubeArtistSearchFilter.isAllowed(song(listOf(foreign))))
    }

    @Test fun blacklistOverridesAllowedCollaborator() {
        YouTubeBlockedSongFilter.replaceBlockedSongIds(listOf("abcdefghijk"))
        assertFalse(YouTubeArtistSearchFilter.isAllowed(song(listOf(foreign, allowed))))
    }

    @Test fun artistContextDoesNotOverwriteKnownForeignCredits() {
        val original = song(listOf(foreign))
        val contextual = YouTubeArtistSearchFilter.withSourceArtist(original, allowed.id, allowed.name)
        assertEquals(original, contextual)
        assertFalse(YouTubeArtistSearchFilter.isAllowed(contextual))
    }

    @Test fun playlistOwnerDoesNotBlockItsAllowedTracks() {
        val playlist = PlaylistItem("PLtest", "Playlist", foreign, null, null, null, null, null)
        assertTrue(YouTubeArtistSearchFilter.isAllowed(playlist))
        assertEquals(listOf(song(listOf(allowed))), listOf(song(listOf(allowed)), song(listOf(foreign))).filterAllowedArtists())
    }

    @Test fun selectedSongSurvivesRemovalOfEarlierTracks() {
        assertEquals(1, selectedIndex(listOf("first", "selected"), "selected") { it })
        assertEquals(0, selectedIndex(listOf("selected"), "selected") { it })
        assertEquals(0, selectedIndex(emptyList<String>(), "selected") { it })
    }

    @Test fun successfulArtistLookupsAreReusedAndKnownIdsPreserved() = runBlocking {
        var calls = 0
        val resolver = ArtistIdResolver { calls++; allowed.id }
        val missing = song(listOf(Artist("Allowed", null), foreign))
        repeat(2) {
            val result = resolver.resolve(listOf(missing)).single()
            assertEquals(listOf(allowed, foreign), result.artists)
        }
        assertEquals(1, calls)
    }

    @Test fun temporaryArtistLookupFailureCanBeRetried() = runBlocking {
        var calls = 0
        val resolver = ArtistIdResolver { if (++calls == 1) null else allowed.id }
        val missing = song(listOf(Artist("Allowed", null)))
        assertNull(resolver.resolve(listOf(missing)).single().artists.single().id)
        assertEquals(allowed.id, resolver.resolve(listOf(missing)).single().artists.single().id)
    }

    @Test fun slowArtistLookupReturnsWithoutAnEndlessLoadingState() = runBlocking {
        val resolver = ArtistIdResolver(timeoutMillis = 20L) { delay(5_000L); allowed.id }
        val missing = song(listOf(Artist("Allowed", null)))
        assertEquals(listOf(missing), resolver.resolve(listOf(missing)))
    }
}
