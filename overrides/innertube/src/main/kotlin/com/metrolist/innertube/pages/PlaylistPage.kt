package com.metrolist.innertube.pages

import com.metrolist.innertube.models.response.BrowseResponse
import com.metrolist.innertube.models.SectionListRenderer
import com.metrolist.innertube.models.MusicShelfRenderer
import com.metrolist.innertube.models.getContinuation
import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.oddElements
import com.metrolist.innertube.models.splitBySeparator
import com.metrolist.innertube.utils.parseTime

data class PlaylistPage(
    val playlist: PlaylistItem,
    val songs: List<SongItem>,
    val songsContinuation: String?,
    val continuation: String?,
) {
    companion object {
        fun normalizeId(id: String): String = id.trim().removePrefix("VL")

        fun sections(response: BrowseResponse): List<SectionListRenderer.Content> =
            response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer?.contents.orEmpty() +
                response.contents?.twoColumnBrowseResultsRenderer?.tabs.orEmpty().flatMap {
                    it?.tabRenderer?.content?.sectionListRenderer?.contents.orEmpty()
                } +
                response.contents?.singleColumnBrowseResultsRenderer?.tabs.orEmpty().flatMap {
                    it.tabRenderer.content?.sectionListRenderer?.contents.orEmpty()
                } +
                response.contents?.sectionListRenderer?.contents.orEmpty() +
                response.continuationContents?.sectionListContinuation?.contents.orEmpty()

        /** Parse every track shelf; headers and recommendation carousels are not tracks. */
        fun songPage(response: BrowseResponse): PlaylistContinuationPage {
            val sections = sections(response)
            val contents = sections.flatMap {
                it.musicPlaylistShelfRenderer?.contents ?: it.musicShelfRenderer?.contents.orEmpty()
            } + response.continuationContents?.musicPlaylistShelfContinuation?.contents.orEmpty() +
                response.continuationContents?.musicShelfContinuation?.contents.orEmpty() +
                response.onResponseReceivedActions.orEmpty().flatMap {
                    it.appendContinuationItemsAction?.continuationItems.orEmpty()
                }
            val continuation = contents.getContinuation()
                ?: sections.firstNotNullOfOrNull {
                    it.musicPlaylistShelfRenderer?.continuations?.getContinuation()
                        ?: it.musicShelfRenderer?.continuations?.getContinuation()
                }
                ?: response.continuationContents?.musicPlaylistShelfContinuation?.continuations?.getContinuation()
                ?: response.continuationContents?.musicShelfContinuation?.continuations?.getContinuation()
                ?: response.continuationContents?.sectionListContinuation?.continuations?.getContinuation()
            return PlaylistContinuationPage(
                songs = contents.mapNotNull { it.musicResponsiveListItemRenderer }
                    .mapNotNull(::fromMusicResponsiveListItemRenderer).distinctBy { it.id },
                // Empty or filtered pages can still have a valid next page.
                continuation = continuation,
            )
        }

        fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer): SongItem? {
            // Extract library tokens using the new method that properly handles multiple toggle items
            val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)

            // Split the secondary line by bullet separator to separate artists from other metadata (like views)
            val secondaryLineRuns = renderer.flexColumns
                .getOrNull(1)
                ?.musicResponsiveListItemFlexColumnRenderer
                ?.text
                ?.runs
                ?.splitBySeparator()

            val linkedArtists = renderer.flexColumns.drop(1).flatMap {
                it.musicResponsiveListItemFlexColumnRenderer.text?.runs.orEmpty()
            }.filter { it.navigationEndpoint?.browseEndpoint?.browseId?.startsWith("UC") == true }
            val artistRuns = secondaryLineRuns?.firstOrNull()?.oddElements().orEmpty() + linkedArtists

            return SongItem(
                id = renderer.videoId ?: return null,
                title = renderer.flexColumns.firstOrNull()
                    ?.musicResponsiveListItemFlexColumnRenderer?.text
                    ?.runs?.firstOrNull()?.text ?: return null,
                artists = artistRuns.filter {
                    val id = it.navigationEndpoint?.browseEndpoint?.browseId
                    id == null || id.startsWith("UC")
                }.map {
                    Artist(
                        name = it.text,
                        id = it.navigationEndpoint?.browseEndpoint?.browseId,
                    )
                }.distinctBy { it.id ?: it.name },
                album = renderer.flexColumns.getOrNull(2)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.let {
                    Album(
                        name = it.text,
                        id = it.navigationEndpoint?.browseEndpoint?.browseId ?: return@let null
                    )
                },
                duration = renderer.fixedColumns?.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text?.parseTime(),
                musicVideoType = renderer.musicVideoType,
                thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl().orEmpty(),
                explicit = renderer.badges?.find {
                    it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                } != null,
                endpoint = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint
                    ?: renderer.navigationEndpoint?.watchEndpoint,
                setVideoId = renderer.playlistSetVideoId,
                libraryAddToken = libraryTokens.addToken,
                libraryRemoveToken = libraryTokens.removeToken,
                isEpisode = renderer.isEpisode
            )
        }
    }
}
