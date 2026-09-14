/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import androidx.media3.common.MediaItem
import com.metrolist.innertube.utils.YouTubeArtistSearchFilter
import com.metrolist.innertube.utils.YouTubeBlockedSongFilter
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.AlbumWithSongs
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.LocalItem
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PodcastEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.metadata
import com.metrolist.music.models.MediaMetadata

fun ArtistEntity.isAllowedArtist(): Boolean =
    YouTubeArtistSearchFilter.isAllowedArtistId(channelId) || YouTubeArtistSearchFilter.isAllowedArtistId(id)

fun Artist.isAllowedArtist(): Boolean = artist.isAllowedArtist()

fun PodcastEntity.isAllowedArtist(): Boolean =
    YouTubeArtistSearchFilter.isAllowedArtistId(channelId)

fun Iterable<ArtistEntity>.hasAllowedArtist(): Boolean = any { it.isAllowedArtist() }

fun Song.isAllowedContent(): Boolean = orderedArtists.hasAllowedArtist() && !YouTubeBlockedSongFilter.isBlockedSongId(song.id)

fun Album.isAllowedContent(): Boolean = artists.hasAllowedArtist()

fun AlbumWithSongs.isAllowedContent(): Boolean = artists.hasAllowedArtist()

fun Playlist.isAllowedContent(): Boolean = true

fun LocalItem.isAllowedContent(): Boolean =
    when (this) {
        is Song -> isAllowedContent()
        is Album -> isAllowedContent()
        is Artist -> isAllowedArtist()
        is Playlist -> isAllowedContent()
        else -> true
    }

fun MediaMetadata.isAllowedContent(): Boolean =
    !isEpisode &&
        !YouTubeBlockedSongFilter.isBlockedSongId(id) &&
        artists.any { YouTubeArtistSearchFilter.isAllowedArtistId(it.id) }

fun MediaMetadata.withOnlyAllowedArtists(): MediaMetadata =
    copy(artists = artists.filter { YouTubeArtistSearchFilter.isAllowedArtistId(it.id) })

fun MediaItem.isAllowedContent(): Boolean = metadata?.isAllowedContent() == true

fun List<MediaItem>.filterAllowedMediaItems(): List<MediaItem> = filter { it.isAllowedContent() }
