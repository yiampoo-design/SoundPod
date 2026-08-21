package com.github.innertube.requests

import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import com.github.innertube.Innertube
import com.github.innertube.models.BrowseResponse
import com.github.innertube.models.MusicCarouselShelfRenderer
import com.github.innertube.models.YouTubeClient
import com.github.innertube.models.bodies.BrowseBody
import com.github.innertube.utils.findSectionByTitle
import com.github.innertube.utils.from
import com.github.innertube.utils.runCatchingNonCancellable
import java.util.Locale
import java.util.logging.Logger

private val chartsLogger = Logger.getLogger("YiamTube-Innertube")

// Candidate order is FEmusic_charts → FEmusic_home → FEmusic_explore.
// FEcharts is intentionally NOT first because it currently returns HTTP 400
// ("Request contains an invalid argument") from YouTube and would block the
// rest of the candidates if used as the primary. We re-introduce it only if
// upstream evidence proves it is still valid.
private val chartsBrowseIds = listOf(
    "FEmusic_charts",
    "FEmusic_home",
    "FEmusic_explore",
)

suspend fun Innertube.charts(): Result<List<Innertube.SongItem>?>? = runCatchingNonCancellable {
    if (!hasRequiredTokens) {
        waitForSession(timeoutMs = 10000)
    }

    val visitorDataPresent = !visitorData.isNullOrBlank()
    chartsLogger.fine("charts() start visitorDataPresent=$visitorDataPresent clientName=WEB_REMIX clientVersion=${YouTubeClient.WEB_REMIX.clientVersion} gl=${Locale.getDefault().country.ifBlank { "US" }} hl=en")

    val firstValid = chartsBrowseIds.firstNotNullOfOrNull { browseId ->
        fetchCharts(browseId, visitorDataPresent)
    }

    if (firstValid == null) {
        chartsLogger.warning("charts() all candidates failed: ${chartsBrowseIds.joinToString(",")}")
    }
    firstValid
}

private suspend fun Innertube.fetchCharts(
    browseId: String,
    visitorDataPresent: Boolean
): List<Innertube.SongItem>? {
    chartsLogger.fine("charts($browseId) start visitorDataPresent=$visitorDataPresent")
    val response: HttpResponse = try {
        client.post(BROWSE) {
            setBody(
                BrowseBody(
                    browseId = browseId,
                    context = YouTubeClient.WEB_REMIX.toContext(
                        hl = "en",
                        gl = Locale.getDefault().country.ifBlank { "US" },
                        visitorData = visitorData,
                    )
                )
            )
        }
    } catch (e: ResponseException) {
        val status = e.response.status.value
        val errorBody = try { e.response.bodyAsText() } catch (_: Exception) { "" }
        val sanitised = errorBody.take(200).replace("\n", " ")
        chartsLogger.warning("charts($browseId) failed clientName=WEB_REMIX clientVersion=${YouTubeClient.WEB_REMIX.clientVersion} visitorDataPresent=$visitorDataPresent status=$status type=${e.javaClass.simpleName} msg=$sanitised")
        return null
    } catch (e: Exception) {
        chartsLogger.warning("charts($browseId) failed clientName=WEB_REMIX clientVersion=${YouTubeClient.WEB_REMIX.clientVersion} visitorDataPresent=$visitorDataPresent type=${e.javaClass.simpleName} msg=${e.message?.take(200)}")
        return null
    }

    val status = response.status.value
    if (!response.status.isSuccess()) {
        val errorBody = try { response.bodyAsText() } catch (_: Exception) { "" }
        val sanitised = errorBody.take(200).replace("\n", " ")
        chartsLogger.warning("charts($browseId) failed clientName=WEB_REMIX clientVersion=${YouTubeClient.WEB_REMIX.clientVersion} visitorDataPresent=$visitorDataPresent status=$status msg=$sanitised")
        return null
    }

    val body: BrowseResponse = try {
        response.body()
    } catch (e: Exception) {
        chartsLogger.warning("charts($browseId) parse failed visitorDataPresent=$visitorDataPresent type=${e.javaClass.simpleName} msg=${e.message?.take(200)}")
        return null
    }

    val sectionListRenderer = body.contents?.sectionListRenderer
    val items = (sectionListRenderer?.findSectionByTitle("Top songs")
        ?: sectionListRenderer?.findSectionByTitle("Top music videos")
        ?: sectionListRenderer?.findSectionByTitle("Trending")
        ?: sectionListRenderer?.contents?.firstOrNull { it.musicCarouselShelfRenderer != null })
        ?.musicCarouselShelfRenderer
        ?.contents
        ?.mapNotNull(MusicCarouselShelfRenderer.Content::musicResponsiveListItemRenderer)
        ?.mapNotNull(Innertube.SongItem::from)
        ?.takeIf { it.isNotEmpty() }
    if (items == null) {
        chartsLogger.fine("charts($browseId) status=$status items=0 (empty response, continuing)")
    } else {
        chartsLogger.fine("charts($browseId) status=$status items=${items.size}")
    }
    return items
}

suspend fun Innertube.newReleases(): Result<List<Innertube.AlbumItem>?>? = runCatchingNonCancellable {
    if (!hasRequiredTokens) {
        waitForSession(timeoutMs = 10000)
    }

    val response = client.post(BROWSE) {
        setBody(
            BrowseBody(
                browseId = "FEmusic_new_releases",
                context = YouTubeClient.WEB_REMIX.toContext(
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
