content = r'''package com.github.innertube.requests

import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import com.github.innertube.Innertube
import com.github.innertube.applyYouTubeMusicClient
import com.github.innertube.models.BrowseResponse
import com.github.innertube.models.MusicCarouselShelfRenderer
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
    val client = YouTubeClient.WEB_REMIX
    val gl = Locale.getDefault().country.ifBlank { "US" }
    val visitorDataPresent = !visitorData.isNullOrBlank()
    val browseId = "FEmusic_charts"
    val params = CHARTS_PARAMS
    val bodyBytes = try {
        kotlinx.serialization.json.Json.encodeToString(
            com.github.innertube.models.bodies.BrowseBody.serializer(),
            BrowseBody(
                browseId = browseId,
                params = params,
                context = client.toContext(
                    hl = "en",
                    gl = gl,
                    visitorData = visitorData,
                )
            )
        ).length
    } catch (_: Exception) { 0 }

    httpLogger.fine("charts http-start client=WEB_REMIX clientId=${client.clientId} version=${client.clientVersion} visitorDataPresent=$visitorDataPresent browseId=$browseId gl=$gl hl=en")

    val response = try {
        client.post(BROWSE) {
            applyYouTubeMusicClient(client, visitorData)
            setBody(
                BrowseBody(
                    browseId = browseId,
                    params = params,
                    context = client.toContext(
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
        innertubeLogger.warning("charts http-fail client=WEB_REMIX version=${client.clientVersion} visitorDataPresent=$visitorDataPresent status=$status type=${e.javaClass.simpleName} msg=$sanitised")
        return@runCatchingNonCancellable null
    } catch (e: Exception) {
        innertubeLogger.warning("charts http-fail client=WEB_REMIX version=${client.clientVersion} visitorDataPresent=$visitorDataPresent type=${e.javaClass.simpleName} msg=${e.message?.take(200)}")
        return@runCatchingNonCancellable null
    }

    val status = response.status.value
    val rawText = try { response.bodyAsText() } catch (_: Exception) { "" }
    val rawBytes = rawText.length
    if (!response.status.isSuccess()) {
        val sanitised = rawText.take(200).replace("\n", " ")
        innertubeLogger.warning("charts http-fail client=WEB_REMIX version=${client.clientVersion} visitorDataPresent=$visitorDataPresent status=$status bytes=$rawBytes body=$sanitised")
        return@runCatchingNonCancellable null
    }

    val body: BrowseResponse = try {
        response.body()
    } catch (e: Exception) {
        innertubeLogger.warning("charts parse-fail client=WEB_REMIX visitorDataPresent=$visitorDataPresent bytes=$rawBytes type=${e.javaClass.simpleName} msg=${e.message?.take(200)}")
        return@runCatchingNonCancellable null
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
        innertubeLogger.warning("charts parse-ok-but-empty client=WEB_REMIX visitorDataPresent=$visitorDataPresent status=$status bytes=$rawBytes")
    } else {
        innertubeLogger.fine("charts parse-ok client=WEB_REMIX visitorDataPresent=$visitorDataPresent status=$status bytes=$rawBytes items=${items.size}")
    }
    items
}

/**
 * Home endpoint (FEmusic_home). Used for general home page content.
 * Different response structure from Charts.
 */
suspend fun Innertube.home(): Result<BrowseResponse?>? = runCatchingNonCancellable {
    val client = YouTubeClient.WEB_REMIX
    val gl = Locale.getDefault().country.ifBlank { "US" }
    val visitorDataPresent = !visitorData.isNullOrBlank()
    val browseId = "FEmusic_home"

    httpLogger.fine("home http-start client=WEB_REMIX clientId=${client.clientId} version=${client.clientVersion} visitorDataPresent=$visitorDataPresent browseId=$browseId gl=$gl hl=en")

    val response = try {
        client.post(BROWSE) {
            applyYouTubeMusicClient(client, visitorData)
            setBody(
                BrowseBody(
                    browseId = browseId,
                    context = client.toContext(
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
    val client = YouTubeClient.WEB_REMIX
    val response = client.post(BROWSE) {
        applyYouTubeMusicClient(client, visitorData)
        setBody(
            BrowseBody(
                browseId = "FEmusic_new_releases",
                context = client.toContext(
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
'''
path = r'C:/Users/Yiam/.minimax/workspace/SoundPod/innertube/src/main/kotlin/com/github/innertube/requests/ExplorePage.kt'
with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
print('Wrote ExplorePage.kt')
