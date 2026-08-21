package com.github.innertube.requests

import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import com.github.innertube.Innertube
import com.github.innertube.models.ContinuationResponse
import com.github.innertube.models.MusicShelfRenderer
import com.github.innertube.models.SearchResponse
import com.github.innertube.models.YouTubeClient
import com.github.innertube.models.bodies.ContinuationBody
import com.github.innertube.models.bodies.SearchBody
import com.github.innertube.utils.runCatchingNonCancellable
import java.util.Locale
import java.util.logging.Logger

private val searchLogger = Logger.getLogger("YiamTube-Innertube")

suspend fun <T : Innertube.Item> Innertube.searchPage(
    query: String,
    params: String,
    fromMusicShelfRendererContent: (MusicShelfRenderer.Content) -> T?
) = runCatchingNonCancellable {
    val ytClient = YouTubeClient.MAC_SAFARI_WEB_REMIX
    val visitorDataPresent = !visitorData.isNullOrBlank()
    val gl = Locale.getDefault().country.ifBlank { "US" }
    val hl = "en"
    searchLogger.fine("search(query=$query) start client=${ytClient.clientName} version=${ytClient.clientVersion} visitorDataPresent=$visitorDataPresent gl=$gl hl=$hl")

    val response = try {
        client.post(SEARCH) {
            setBody(
                SearchBody(
                    context = ytClient.toContext(
                        hl = hl,
                        gl = gl,
                        visitorData = visitorData,
                    ),
                    query = query,
                    params = params,
                )
            )
            mask("contents.tabbedSearchResultsRenderer.tabs.tabRenderer.content.sectionListRenderer.contents.musicShelfRenderer(continuations,contents.$MUSIC_RESPONSIVE_LIST_ITEM_RENDERER_MASK)")
        }
    } catch (e: ResponseException) {
        val status = e.response.status.value
        val errorBody = try { e.response.bodyAsText() } catch (_: Exception) { "" }
        val sanitised = errorBody.take(200).replace("\n", " ")
        searchLogger.warning("search(query=$query) failed client=${ytClient.clientName} version=${ytClient.clientVersion} visitorDataPresent=$visitorDataPresent status=$status type=${e.javaClass.simpleName} msg=$sanitised")
        return@runCatchingNonCancellable null
    } catch (e: Exception) {
        searchLogger.warning("search(query=$query) failed client=${ytClient.clientName} version=${ytClient.clientVersion} visitorDataPresent=$visitorDataPresent type=${e.javaClass.simpleName} msg=${e.message?.take(200)}")
        return@runCatchingNonCancellable null
    }

    val status = response.status.value
    if (!response.status.isSuccess()) {
        val errorBody = try { response.bodyAsText() } catch (_: Exception) { "" }
        val sanitised = errorBody.take(200).replace("\n", " ")
        searchLogger.warning("search(query=$query) failed client=${ytClient.clientName} version=${ytClient.clientVersion} visitorDataPresent=$visitorDataPresent status=$status msg=$sanitised")
        return@runCatchingNonCancellable null
    }

    val body: SearchResponse = try {
        response.body()
    } catch (e: Exception) {
        searchLogger.warning("search(query=$query) parse failed visitorDataPresent=$visitorDataPresent type=${e.javaClass.simpleName} msg=${e.message?.take(200)}")
        return@runCatchingNonCancellable null
    }

    val sectionListRenderer = body
        .contents
        ?.tabbedSearchResultsRenderer
        ?.tabs
        ?.firstOrNull()
        ?.tabRenderer
        ?.content
        ?.sectionListRenderer

    val allItems = mutableListOf<T>()
    var continuation: String? = sectionListRenderer?.continuations?.firstOrNull()?.nextContinuationData?.continuation

    sectionListRenderer?.contents?.forEach { content ->
        content.musicShelfRenderer?.toItemsPage(fromMusicShelfRendererContent)?.let { page ->
            page.items?.let { allItems.addAll(it) }
            if (continuation == null) continuation = page.continuation
        }
    }

    searchLogger.fine("search(query=$query) status=$status items=${allItems.size} hasContinuation=${continuation != null}")
    Innertube.ItemsPage(
        items = allItems.takeIf { it.isNotEmpty() },
        continuation = continuation
    )
}

suspend fun <T : Innertube.Item> Innertube.searchPage(
    continuation: String,
    fromMusicShelfRendererContent: (MusicShelfRenderer.Content) -> T?
) = runCatchingNonCancellable {
    val response = client.post(SEARCH) {
        setBody(ContinuationBody(continuation = continuation))
        mask("continuationContents.musicShelfContinuation(continuations,contents.$MUSIC_RESPONSIVE_LIST_ITEM_RENDERER_MASK),continuationContents.sectionListContinuation")
    }.body<ContinuationResponse>()

    if (response.continuationContents?.musicShelfContinuation != null) {
        return@runCatchingNonCancellable response.continuationContents.musicShelfContinuation.toItemsPage(fromMusicShelfRendererContent)
    }

    val sectionListRenderer = response.continuationContents?.sectionListContinuation
    val allItems = mutableListOf<T>()
    var nextContinuation: String? = sectionListRenderer?.continuations?.firstOrNull()?.nextContinuationData?.continuation

    sectionListRenderer?.contents?.forEach { content ->
        content.musicShelfRenderer?.toItemsPage(fromMusicShelfRendererContent)?.let { page ->
            page.items?.let { allItems.addAll(it) }
            if (nextContinuation == null) nextContinuation = page.continuation
        }
    }

    Innertube.ItemsPage(
        items = allItems.takeIf { it.isNotEmpty() },
        continuation = nextContinuation
    )
}

private fun <T : Innertube.Item> MusicShelfRenderer?.toItemsPage(mapper: (MusicShelfRenderer.Content) -> T?) =
    Innertube.ItemsPage(
        items = this
            ?.contents
            ?.mapNotNull(mapper),
        continuation = this
            ?.continuations
            ?.firstOrNull()
            ?.nextContinuationData
            ?.continuation
    )
