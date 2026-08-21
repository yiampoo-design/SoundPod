package com.github.innertube.requests

import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import com.github.innertube.Innertube
import com.github.innertube.Innertube.applyYouTubeMusicClient
import com.github.innertube.models.BrowseResponse
import com.github.innertube.models.MusicCarouselShelfRenderer
import com.github.innertube.models.MusicResponsiveListItemRenderer
import com.github.innertube.models.NavigationEndpoint
import com.github.innertube.models.YouTubeClient
import com.github.innertube.models.bodies.BrowseBody
import com.github.innertube.utils.findSectionByTitle
import com.github.innertube.utils.from
import com.github.innertube.utils.runCatchingNonCancellable
import java.util.Locale
import java.util.logging.Logger

private val httpLogger = Logger.getLogger("YiamTube-HTTP")
private val innertubeLogger = Logger.getLogger("YiamTube-Innertube")

private const val CHARTS_PARAMS = "ggMGCgQIgAQ%3D"

suspend fun Innertube.charts(): Result<List<Innertube.SongItem>?>? = runCatchingNonCancellable {
    val ytClient = YouTubeClient.WEB_REMIX
    val gl = Locale.getDefault().country.ifBlank { "US" }
    val visitorDataPresent = !visitorData.isNullOrBlank()
    val browseId = "FEmusic_charts"
    val params = CHARTS_PARAMS

    val response = try {
        client.post(BROWSE) {
            applyYouTubeMusicClient(ytClient, visitorData)
            setBody(BrowseBody(browseId = browseId, params = params, context = ytClient.toContext(hl = "en", gl = gl, visitorData = visitorData)))
        }
    } catch (e: ResponseException) {
        val status = e.response.status.value
        innertubeLogger.warning("charts failure status=$status visitorDataPresent=$visitorDataPresent msg=${e.message?.take(100)}")
        return@runCatchingNonCancellable null
    } catch (e: Exception) {
        innertubeLogger.warning("charts failure visitorDataPresent=$visitorDataPresent msg=${e.message?.take(100)}")
        return@runCatchingNonCancellable null
    }

    val status = response.status.value
    val rawText = try { response.bodyAsText() } catch (_: Exception) { "" }
    val rawBytes = rawText.length
    if (!response.status.isSuccess()) {
        innertubeLogger.warning("charts failure status=$status bytes=$rawBytes")
        return@runCatchingNonCancellable null
    }

    val body: BrowseResponse = try {
        response.body()
    } catch (e: Exception) {
        innertubeLogger.warning("charts failure parse bytes=$rawBytes msg=${e.message?.take(100)}")
        return@runCatchingNonCancellable null
    }

    val tabs = body.contents?.singleColumnBrowseResultsRenderer?.tabs.orEmpty()
    val sectionsFromTabs = tabs.mapNotNull { it.tabRenderer?.content?.sectionListRenderer?.contents }.flatten()
    val legacySections = body.contents?.sectionListRenderer?.contents.orEmpty()
    val sections = if (sectionsFromTabs.isNotEmpty()) sectionsFromTabs else legacySections

    val allTwoRowRenderers = sections.mapNotNull { it.musicCarouselShelfRenderer }.flatMap { it.contents.orEmpty() }.mapNotNull { it.musicTwoRowItemRenderer }

    val chartPlaylists = sections.mapNotNull { it.musicCarouselShelfRenderer }.flatMap { it.contents.orEmpty() }.mapNotNull { it.musicTwoRowItemRenderer }.mapNotNull(Innertube.PlaylistItem::from).filter { !it.info?.endpoint?.browseId.isNullOrBlank() }

    if (chartPlaylists.isEmpty()) {
        innertubeLogger.warning("charts failure chartPlaylists=0 status=$status bytes=$rawBytes")
        return@runCatchingNonCancellable emptyList()
    }

    val browseIdSelected = chartPlaylists.firstOrNull()?.info?.endpoint?.browseId
    if (browseIdSelected.isNullOrBlank()) {
        innertubeLogger.warning("charts failure blank browseId")
        return@runCatchingNonCancellable emptyList()
    }

    val pageResult = try { playlistPage(browseIdSelected) } catch (e: Exception) {
        innertubeLogger.warning("charts failure playlistPage exception browseId=\"$browseIdSelected\" msg=${e.message?.take(100)}")
        null
    }

    val page = pageResult?.getOrNull()
    if (page == null) {
        innertubeLogger.warning("charts failure playlistPage failed browseId=\"$browseIdSelected\"")
        return@runCatchingNonCancellable emptyList()
    }

    val songs = page.songsPage?.items.orEmpty().filter { it.key.isNotBlank() }.distinctBy { it.key }

    if (songs.isEmpty()) {
        innertubeLogger.warning("charts failure playlistSongs=0 browseId=\"$browseIdSelected\"")
        return@runCatchingNonCancellable emptyList()
    }

    innertubeLogger.info("charts success items=${songs.size}")
    songs
}

private fun MusicResponsiveListItemRenderer.toChartSongItem(): Innertube.SongItem? {
    val resolvedVideoId = videoId ?: return null
    val titleRun = flexColumns.getOrNull(0)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull() ?: return null
    val title = titleRun.text ?: return null
    if (title.isBlank()) return null
    val info = Innertube.Info(name = title, endpoint = NavigationEndpoint.Endpoint.Watch(videoId = resolvedVideoId))
    val authors = flexColumns.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.mapNotNull { run -> run.navigationEndpoint?.browseEndpoint?.let { Innertube.Info<NavigationEndpoint.Endpoint.Browse>(name = run.text ?: "", endpoint = it) } }?.takeIf { it.isNotEmpty() }
    val albumEndpoint = flexColumns.getOrNull(2)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.navigationEndpoint?.browseEndpoint
    val album = albumEndpoint?.let { endpoint -> val albumName = flexColumns.getOrNull(2)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text ?: ""; Innertube.Info(name = albumName, endpoint = endpoint) }
    val thumbnail = thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()
    return Innertube.SongItem(info = info, authors = authors, album = album, durationText = null, thumbnail = thumbnail).takeIf { it.key.isNotBlank() }
}

suspend fun Innertube.home(): Result<BrowseResponse?>? = runCatchingNonCancellable {
    val ytClient = YouTubeClient.WEB_REMIX
    val gl = Locale.getDefault().country.ifBlank { "US" }
    val visitorDataPresent = !visitorData.isNullOrBlank()
    val browseId = "FEmusic_home"
    val response = try {
        client.post(BROWSE) { applyYouTubeMusicClient(ytClient, visitorData); setBody(BrowseBody(browseId = browseId, context = ytClient.toContext(hl = "en", gl = gl, visitorData = visitorData))) }
    } catch (e: ResponseException) {
        innertubeLogger.warning("home failure status=${e.response.status.value}")
        return@runCatchingNonCancellable null
    } catch (e: Exception) {
        innertubeLogger.warning("home failure msg=${e.message?.take(100)}")
        return@runCatchingNonCancellable null
    }
    val status = response.status.value
    val rawText = try { response.bodyAsText() } catch (_: Exception) { "" }
    if (!response.status.isSuccess()) {
        innertubeLogger.warning("home failure status=$status")
        return@runCatchingNonCancellable null
    }
    val body: BrowseResponse = try { response.body() } catch (e: Exception) {
        innertubeLogger.warning("home failure parse msg=${e.message?.take(100)}")
        return@runCatchingNonCancellable null
    }
    body
}

suspend fun Innertube.newReleases(): Result<List<Innertube.AlbumItem>?>? = runCatchingNonCancellable {
    val ytClient = YouTubeClient.WEB_REMIX
    val response = client.post(BROWSE) { applyYouTubeMusicClient(ytClient, visitorData); setBody(BrowseBody(browseId = "FEmusic_new_releases", context = ytClient.toContext(hl = "en", gl = Locale.getDefault().country.ifBlank { "US" }))) }.body<BrowseResponse>()
    val sectionListRenderer = response.contents?.sectionListRenderer
    (sectionListRenderer?.findSectionByTitle("New albums & singles") ?: sectionListRenderer?.contents?.firstOrNull { it.musicCarouselShelfRenderer != null })?.musicCarouselShelfRenderer?.contents?.mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)?.mapNotNull(Innertube.AlbumItem::from)?.takeIf { it.isNotEmpty() }
}
