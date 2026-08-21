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
 * Per current maintained YouTube Music clients (Metrolist-style) the
 * FEmusic_charts browse requires a non-empty `params` field. The Home and
 * Explore endpoints use a *different* response structure and must NOT be
 * parsed with the chart-specific logic.
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

    // TASK 1 – read from current response path: contents -> singleColumnBrowseResultsRenderer -> tabs[0] -> tabRenderer -> content -> sectionListRenderer
    val sectionListRenderer = body.contents
        ?.singleColumnBrowseResultsRenderer
        ?.tabs
        ?.firstOrNull()
        ?.tabRenderer
        ?.content
        ?.sectionListRenderer
        ?: body.contents?.sectionListRenderer

    val sections = sectionListRenderer?.contents.orEmpty()

    // TASK 4 – diagnostics per section
    sections.forEachIndexed { index, content ->
        val isCarousel = content.musicCarouselShelfRenderer != null
        val isGrid = content.gridRenderer != null
        val isShelf = content.musicShelfRenderer != null
        innertubeLogger.info("charts section[$index] carousel=$isCarousel grid=$isGrid shelf=$isShelf")
        if (isCarousel) {
            val carousel = content.musicCarouselShelfRenderer
            val itemCount = carousel?.contents?.size ?: 0
            val responsive = carousel?.contents?.count { it.musicResponsiveListItemRenderer != null } ?: 0
            val twoRow = carousel?.contents?.count { it.musicTwoRowItemRenderer != null } ?: 0
            innertubeLogger.info("charts carousel[$index] items=$itemCount responsive=$responsive twoRow=$twoRow")
        }
        if (isGrid) {
            val itemCount = content.gridRenderer?.items?.size ?: 0
            innertubeLogger.info("charts grid[$index] items=$itemCount")
        }
    }

    // Collect responsive renderers structurally (no title dependency)
    val responsiveRenderers: List<MusicResponsiveListItemRenderer> = sections
        .mapNotNull { it.musicCarouselShelfRenderer }
        .flatMap { it.contents.orEmpty() }
        .mapNotNull { it.musicResponsiveListItemRenderer }

    innertubeLogger.info("charts responsiveItems=${responsiveRenderers.size}")

    // TASK 3 – temporary diagnostics for first 2 items: which videoId source is present
    responsiveRenderers.take(2).forEachIndexed { idx, renderer ->
        val playlistVid = renderer.playlistItemData?.videoId
        val titleRunVid = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.navigationEndpoint?.watchEndpoint?.videoId
        val overlayVid = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint?.videoId
        val resolved = renderer.videoId
        val titleText = renderer.flexColumns.getOrNull(0)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text ?: ""
        innertubeLogger.info("charts item[$idx] playlistVideoId=${playlistVid != null} titleRunVideoId=${titleRunVid != null} overlayVideoId=${overlayVid != null} resolvedVideoId=${resolved != null} title=\"$titleText\"")
    }

    val withVideoId = responsiveRenderers.count { it.videoId != null }
    innertubeLogger.info("charts responsiveWithVideoId=$withVideoId")

    // TASK 4/5 – chart-specific converter (does not modify global SongItem::from)
    var songs: List<Innertube.SongItem> = responsiveRenderers
        .mapNotNull { it.toChartSongItem() }
        .filter { it.key.isNotBlank() }
        .distinctBy { it.key }

    innertubeLogger.info("charts convertedSongs=${songs.size}")

    // Fallback for twoRow/grid only if responsive still empty (keep previous behavior for coverage)
    if (songs.isEmpty()) {
        val twoRowCandidates = sections
            .mapNotNull { it.musicCarouselShelfRenderer }
            .flatMap { it.contents.orEmpty() }
            .mapNotNull { it.musicTwoRowItemRenderer }
        if (twoRowCandidates.isNotEmpty()) {
            val twoRowSongs = twoRowCandidates.mapNotNull { renderer ->
                val videoId = renderer.navigationEndpoint?.watchEndpoint?.videoId ?: return@mapNotNull null
                if (videoId.isBlank()) return@mapNotNull null
                try {
                    val info = renderer.title?.runs?.firstOrNull()?.let { Innertube.Info<NavigationEndpoint.Endpoint.Watch>(it) }
                    val authors = renderer.subtitle?.runs
                        ?.mapNotNull { run ->
                            if (run.navigationEndpoint?.browseEndpoint != null) {
                                Innertube.Info<NavigationEndpoint.Endpoint.Browse>(run)
                            } else null
                        }?.takeIf { it.isNotEmpty() }
                    Innertube.SongItem(
                        info = info,
                        authors = authors,
                        album = null,
                        durationText = null,
                        thumbnail = renderer.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()
                    ).takeIf { it.key.isNotBlank() }
                } catch (_: Exception) { null }
            }.filter { it.key.isNotBlank() }.distinctBy { it.key }
            if (twoRowSongs.isNotEmpty()) {
                innertubeLogger.info("charts twoRow fallback parsed songs=${twoRowSongs.size}")
                songs = twoRowSongs
            }
        }
    }

    if (songs.isEmpty()) {
        val gridTwoRowSongs = sections
            .mapNotNull { it.gridRenderer }
            .flatMap { it.items.orEmpty() }
            .mapNotNull { it.musicTwoRowItemRenderer }
            .mapNotNull { renderer ->
                val videoId = renderer.navigationEndpoint?.watchEndpoint?.videoId ?: return@mapNotNull null
                if (videoId.isBlank()) return@mapNotNull null
                try {
                    val info = renderer.title?.runs?.firstOrNull()?.let { Innertube.Info<NavigationEndpoint.Endpoint.Watch>(it) }
                    Innertube.SongItem(
                        info = info,
                        authors = null,
                        album = null,
                        durationText = null,
                        thumbnail = renderer.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()
                    ).takeIf { it.key.isNotBlank() }
                } catch (_: Exception) { null }
            }.filter { it.key.isNotBlank() }.distinctBy { it.key }
        if (gridTwoRowSongs.isNotEmpty()) {
            innertubeLogger.info("charts grid fallback parsed songs=${gridTwoRowSongs.size}")
            songs = gridTwoRowSongs
        }
    }

    // TASK 5/6 – final parser counts
    innertubeLogger.info("charts parsed sections=${sections.size} songs=${songs.size}")
    innertubeLogger.info("charts status=$status bytes=$rawBytes")

    if (songs.isEmpty()) {
        innertubeLogger.warning("charts parse-ok-but-empty client=WEB_REMIX visitorDataPresent=$visitorDataPresent status=$status bytes=$rawBytes")
        return@runCatchingNonCancellable emptyList()
    } else {
        innertubeLogger.info("charts parse-ok client=WEB_REMIX visitorDataPresent=$visitorDataPresent status=$status bytes=$rawBytes items=${songs.size}")
    }
    songs
}

/**
 * Chart-specific SongItem converter that recovers videoId from alternate fields
 * (playlistItemData, overlay) and constructs endpoint manually.
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
        endpoint = NavigationEndpoint.Endpoint.Watch(
            videoId = resolvedVideoId
        )
    )

    val authors = flexColumns
        .getOrNull(1)
        ?.musicResponsiveListItemFlexColumnRenderer
        ?.text
        ?.runs
        ?.mapNotNull { run ->
            run.navigationEndpoint
                ?.browseEndpoint
                ?.let {
                    Innertube.Info<NavigationEndpoint.Endpoint.Browse>(
                        name = run.text,
                        endpoint = it
                    )
                }
        }
        ?.takeIf { it.isNotEmpty() }

    val albumEndpoint = flexColumns
        .getOrNull(2)
        ?.musicResponsiveListItemFlexColumnRenderer
        ?.text
        ?.runs
        ?.firstOrNull()
        ?.navigationEndpoint
        ?.browseEndpoint

    val album = albumEndpoint?.let { endpoint ->
        val albumName = flexColumns
            .getOrNull(2)
            ?.musicResponsiveListItemFlexColumnRenderer
            ?.text
            ?.runs
            ?.firstOrNull()
            ?.text ?: ""
        Innertube.Info(
            name = albumName,
            endpoint = endpoint
        )
    }

    val thumbnail = thumbnail
        ?.musicThumbnailRenderer
        ?.thumbnail
        ?.thumbnails
        ?.lastOrNull()

    return Innertube.SongItem(
        info = info,
        authors = authors,
        album = album,
        durationText = null,
        thumbnail = thumbnail
    ).takeIf { it.key.isNotBlank() }
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
            setBody(
                BrowseBody(
                    browseId = browseId,
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

    val body: BrowseResponse = try {
        response.body()
    } catch (e: Exception) {
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
        setBody(
            BrowseBody(
                browseId = "FEmusic_new_releases",
                context = ytClient.toContext(
                    hl = "en",
                    gl = Locale.getDefault().country.ifBlank { "US" },
                )
            )
        )
    }.body<BrowseResponse>()

    val sectionListRenderer = response
        .contents
        ?.sectionListRenderer

    (sectionListRenderer?.findSectionByTitle("New albums & singles")
        ?: sectionListRenderer?.contents?.firstOrNull { it.musicCarouselShelfRenderer != null })
        ?.musicCarouselShelfRenderer
        ?.contents
        ?.mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)
        ?.mapNotNull(Innertube.AlbumItem::from)
        ?.takeIf { it.isNotEmpty() }
}
