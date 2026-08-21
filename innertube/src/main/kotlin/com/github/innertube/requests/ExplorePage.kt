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

// FEmusic_charts needs a "params" field to be accepted by current YouTube
// Music. The Metrolist-style value is "ggMGCgQIgAQ%3D" (base64 of the
// chart-specific selection params).
private const val CHARTS_PARAMS = "ggMGCgQIgAQ%3D"

/**
 * Charts endpoint (FEmusic_charts) with the proper params.
 *
 * Current YouTube Music Charts returns playlists + artists, not a flat song list.
 * This implementation extracts chart playlists and opens the first one to obtain
 * seed songs via playlistPage().
 */
suspend fun Innertube.charts(): Result<List<Innertube.SongItem>?>? = runCatchingNonCancellable {
    val ytClient = YouTubeClient.WEB_REMIX
    val gl = Locale.getDefault().country.ifBlank { "US" }
    val visitorDataPresent = !visitorData.isNullOrBlank()
    val browseId = "FEmusic_charts"
    val params = CHARTS_PARAMS

    httpLogger.fine("charts http-start client=WEB_REMIX clientId=${ytClient.clientId} version=${ytClient.clientVersion} visitorDataPresent=$visitorDataPresent browseId=$browseId gl=$gl hl=en")

    val response = try {
        client.post(BROWSE) {
            applyYouTubeMusicClient(ytClient, visitorData)
            setBody(
                BrowseBody(
                    browseId = browseId,
                    params = params,
                    context = ytClient.toContext(
                        hl = "en",
                        gl = gl,
                        visitorData = visitorData,
                    )
                )
            )
        }
    } catch (e: ResponseException) {
        val status = e.response.status.value
        val errorBody = try { e.response.bodyAsText() } catch (_: Exception) { "" }
        val sanitised = errorBody.take(200).replace("\n", " ")
        innertubeLogger.warning("charts http-fail client=WEB_REMIX version=${ytClient.clientVersion} visitorDataPresent=$visitorDataPresent status=$status type=${e.javaClass.simpleName} msg=$sanitised")
        return@runCatchingNonCancellable null
    } catch (e: Exception) {
        innertubeLogger.warning("charts http-fail client=WEB_REMIX version=${ytClient.clientVersion} visitorDataPresent=$visitorDataPresent type=${e.javaClass.simpleName} msg=${e.message?.take(200)}")
        return@runCatchingNonCancellable null
    }

    val status = response.status.value
    val rawText = try { response.bodyAsText() } catch (_: Exception) { "" }
    val rawBytes = rawText.length
    if (!response.status.isSuccess()) {
        val sanitised = rawText.take(200).replace("\n", " ")
        innertubeLogger.warning("charts http-fail client=WEB_REMIX version=${ytClient.clientVersion} visitorDataPresent=$visitorDataPresent status=$status bytes=$rawBytes body=$sanitised")
        return@runCatchingNonCancellable null
    }

    val body: BrowseResponse = try {
        response.body()
    } catch (e: Exception) {
        innertubeLogger.warning("charts parse-fail client=WEB_REMIX visitorDataPresent=$visitorDataPresent bytes=$rawBytes type=${e.javaClass.simpleName} msg=${e.message?.take(200)}")
        return@runCatchingNonCancellable null
    }

    // Collect sections from all tabs + legacy fallback
    val tabs = body.contents?.singleColumnBrowseResultsRenderer?.tabs.orEmpty()
    val sectionsFromTabs = tabs.mapNotNull { it.tabRenderer?.content?.sectionListRenderer?.contents }.flatten()
    val legacySections = body.contents?.sectionListRenderer?.contents.orEmpty()
    val sections = if (sectionsFromTabs.isNotEmpty()) sectionsFromTabs else legacySections

    innertubeLogger.info("charts transport status=$status bytes=$rawBytes")
    innertubeLogger.info("charts sections=${sections.size}")

    // TASK 1 – STOP treating artist carousel as songs (no SongItem::from on 40 artist items)
    // TASK 2 – Identify chart playlist carousel (twoRow items -> PlaylistItem)
    val allTwoRowRenderers = sections
        .mapNotNull { it.musicCarouselShelfRenderer }
        .flatMap { it.contents.orEmpty() }
        .mapNotNull { it.musicTwoRowItemRenderer }

    innertubeLogger.info("charts twoRow total=${allTwoRowRenderers.size}")

    val chartPlaylists = sections
        .mapNotNull { it.musicCarouselShelfRenderer }
        .flatMap { it.contents.orEmpty() }
        .mapNotNull { it.musicTwoRowItemRenderer }
        .mapNotNull(Innertube.PlaylistItem::from)
        .filter { !it.info?.endpoint?.browseId.isNullOrBlank() }

    innertubeLogger.info("charts chartPlaylists=${chartPlaylists.size}")

    // Diagnostics for playlist candidates
    chartPlaylists.take(3).forEachIndexed { idx, pl ->
        val title = pl.info?.name ?: ""
        val bid = pl.info?.endpoint?.browseId ?: ""
        innertubeLogger.info("charts playlist[$idx] title=\"$title\" browseId=\"$bid\"")
    }

    // TASK 6 – if PlaylistItem::from returns zero but twoRow=3, instrument the 3 items
    if (chartPlaylists.isEmpty() && allTwoRowRenderers.isNotEmpty()) {
        allTwoRowRenderers.take(3).forEachIndexed { idx, r ->
            val title = r.title?.runs?.firstOrNull()?.text ?: ""
            val topBid = r.navigationEndpoint?.browseEndpoint?.browseId
            val titleRunBid = r.title?.runs?.firstOrNull()?.navigationEndpoint?.browseEndpoint?.browseId
            val subtitle = r.subtitle?.runs?.joinToString("") { it.text ?: "" } ?: ""
            val thumb = r.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail?.thumbnails?.isNotEmpty() == true
            innertubeLogger.info("charts twoRow[$idx] title=\"$title\" topBrowseId=$topBid titleRunBrowseId=$titleRunBid subtitle=\"$subtitle\" thumbnail=$thumb")
        }
        innertubeLogger.warning("charts parse-ok-but-empty chartPlaylists=0 status=$status bytes=$rawBytes")
        return@runCatchingNonCancellable emptyList()
    }

    if (chartPlaylists.isEmpty()) {
        innertubeLogger.warning("charts parse-ok-but-empty chartPlaylists=0 status=$status bytes=$rawBytes")
        return@runCatchingNonCancellable emptyList()
    }

    // TASK 3 – Open first valid chart playlist
    val selected = chartPlaylists.firstOrNull()
    val browseIdSelected = selected?.info?.endpoint?.browseId
    innertubeLogger.info("charts selectedPlaylist browseId=\"$browseIdSelected\"")

    if (browseIdSelected.isNullOrBlank()) {
        innertubeLogger.warning("charts selectedPlaylist blank browseId")
        return@runCatchingNonCancellable emptyList()
    }

    val pageResult = try {
        playlistPage(browseIdSelected)
    } catch (e: Exception) {
        innertubeLogger.warning("charts playlistPage exception browseId=\"$browseIdSelected\" msg=${e.message?.take(100)}")
        null
    }

    val success = pageResult?.isSuccess == true && pageResult.getOrNull() != null
    innertubeLogger.info("charts playlistPage success=$success")

    // TASK 7 – instrument PlaylistPage if songs empty (light diagnostics, don't rewrite yet)
    val page = pageResult?.getOrNull()
    if (!success || page == null) {
        innertubeLogger.warning("charts playlistPage failed browseId=\"$browseIdSelected\"")
        return@runCatchingNonCancellable emptyList()
    }

    val rawTrackCount = page.songsPage?.items?.size ?: 0
    innertubeLogger.info("charts rawPlaylistTracks=$rawTrackCount")

    // TASK 4 – return playlist songs as charts() result
    val songs = page.songsPage?.items.orEmpty()
        .filter { it.key.isNotBlank() }
        .distinctBy { it.key }

    innertubeLogger.info("charts playlistSongs=${songs.size}")
    innertubeLogger.info("charts parsed sections=${sections.size} songs=${songs.size}")
    innertubeLogger.info("charts status=$status bytes=$rawBytes")

    if (songs.isEmpty()) {
        // Provide PlaylistPage layout diagnostics if zero songs
        innertubeLogger.warning("charts parse-ok-but-empty playlistSongs=0 browseId=\"$browseIdSelected\" status=$status bytes=$rawBytes")
        return@runCatchingNonCancellable emptyList()
    } else {
        innertubeLogger.info("charts parse-ok client=WEB_REMIX visitorDataPresent=$visitorDataPresent status=$status bytes=$rawBytes items=${songs.size}")
    }
    songs
}

/**
 * Chart-specific SongItem converter kept for reference but not used in current
 * playlist-as-seeds flow. Preserved to avoid breaking other converters.
 */
private fun MusicResponsiveListItemRenderer.toChartSongItem(): Innertube.SongItem? {
    val resolvedVideoId = videoId ?: return null
    val titleRun = flexColumns
        .getOrNull(0)
        ?.musicResponsiveListItemFlexColumnRenderer
        ?.text
        ?.runs
        ?.firstOrNull()
        ?: return null
    val title = titleRun.text ?: return null
    if (title.isBlank()) return null
    val info = Innertube.Info(
        name = title,
        endpoint = NavigationEndpoint.Endpoint.Watch(videoId = resolvedVideoId)
    )
    val authors = flexColumns
        .getOrNull(1)
        ?.musicResponsiveListItemFlexColumnRenderer
        ?.text
        ?.runs
        ?.mapNotNull { run ->
            run.navigationEndpoint?.browseEndpoint?.let {
                Innertube.Info<NavigationEndpoint.Endpoint.Browse>(name = run.text ?: "", endpoint = it)
            }
        }?.takeIf { it.isNotEmpty() }
    val albumEndpoint = flexColumns.getOrNull(2)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.navigationEndpoint?.browseEndpoint
    val album = albumEndpoint?.let { endpoint ->
        val albumName = flexColumns.getOrNull(2)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text ?: ""
        Innertube.Info(name = albumName, endpoint = endpoint)
    }
    val thumbnail = thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()
    return Innertube.SongItem(info = info, authors = authors, album = album, durationText = null, thumbnail = thumbnail).takeIf { it.key.isNotBlank() }
}

/**
 * Home endpoint (FEmusic_home). Used for general home page content.
 * Different response structure from Charts.
 */
suspend fun Innertube.home(): Result<BrowseResponse?>? = runCatchingNonCancellable {
    val ytClient = YouTubeClient.WEB_REMIX
    val gl = Locale.getDefault().country.ifBlank { "US" }
    val visitorDataPresent = !visitorData.isNullOrBlank()
    val browseId = "FEmusic_home"
    httpLogger.fine("home http-start client=WEB_REMIX clientId=${ytClient.clientId} version=${ytClient.clientVersion} visitorDataPresent=$visitorDataPresent browseId=$browseId gl=$gl hl=en")
    val response = try {
        client.post(BROWSE) {
            applyYouTubeMusicClient(ytClient, visitorData)
            setBody(BrowseBody(browseId = browseId, context = ytClient.toContext(hl = "en", gl = gl, visitorData = visitorData)))
        }
    } catch (e: ResponseException) {
        val status = e.response.status.value
        val errorBody = try { e.response.bodyAsText() } catch (_: Exception) { "" }
        val sanitised = errorBody.take(200).replace("\n", " ")
        innertubeLogger.warning("home http-fail client=WEB_REMIX visitorDataPresent=$visitorDataPresent status=$status type=${e.javaClass.simpleName} msg=$sanitised")
        return@runCatchingNonCancellable null
    } catch (e: Exception) {
        innertubeLogger.warning("home http-fail client=WEB_REMIX visitorDataPresent=$visitorDataPresent type=${e.javaClass.simpleName} msg=${e.message?.take(200)}")
        return@runCatchingNonCancellable null
    }
    val status = response.status.value
    val rawText = try { response.bodyAsText() } catch (_: Exception) { "" }
    if (!response.status.isSuccess()) {
        val sanitised = rawText.take(200).replace("\n", " ")
        innertubeLogger.warning("home http-fail client=WEB_REMIX visitorDataPresent=$visitorDataPresent status=$status body=$sanitised")
        return@runCatchingNonCancellable null
    }
    val body: BrowseResponse = try { response.body() } catch (e: Exception) {
        innertubeLogger.warning("home parse-fail client=WEB_REMIX visitorDataPresent=$visitorDataPresent type=${e.javaClass.simpleName} msg=${e.message?.take(200)}")
        return@runCatchingNonCancellable null
    }
    val sectionCount = body.contents?.sectionListRenderer?.contents?.size ?: 0
    innertubeLogger.fine("home parse-ok client=WEB_REMIX visitorDataPresent=$visitorDataPresent status=$status bytes=${rawText.length} sections=$sectionCount")
    body
}

suspend fun Innertube.newReleases(): Result<List<Innertube.AlbumItem>?>? = runCatchingNonCancellable {
    val ytClient = YouTubeClient.WEB_REMIX
    val response = client.post(BROWSE) {
        applyYouTubeMusicClient(ytClient, visitorData)
        setBody(BrowseBody(browseId = "FEmusic_new_releases", context = ytClient.toContext(hl = "en", gl = Locale.getDefault().country.ifBlank { "US" })))
    }.body<BrowseResponse>()
    val sectionListRenderer = response.contents?.sectionListRenderer
    (sectionListRenderer?.findSectionByTitle("New albums & singles") ?: sectionListRenderer?.contents?.firstOrNull { it.musicCarouselShelfRenderer != null })
        ?.musicCarouselShelfRenderer?.contents?.mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)?.mapNotNull(Innertube.AlbumItem::from)?.takeIf { it.isNotEmpty() }
}
