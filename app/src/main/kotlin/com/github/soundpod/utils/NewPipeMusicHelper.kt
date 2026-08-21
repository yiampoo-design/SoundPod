package com.github.soundpod.utils

import android.util.Log
import com.github.innertube.Innertube
import com.github.soundpod.appContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Locale

/**
 * Helper that uses NewPipeExtractor (already battle-tested against YouTube's
 * anti-bot) to fetch music content. The custom Innertube module in this app
 * has been returning 403s as YouTube tightened checks, so this is a
 * fallback that uses a different code path.
 *
 * NewPipeExtractor was maintained by the NewPipe community and kept up to
 * date with YouTube's API changes.
 */
object NewPipeMusicHelper {
    private const val TAG = "YiamTube-NewPipe"

    // Kiosk IDs for YouTube Music charts / trending.
    // NewPipeExtractor exposes them via the KioskList, but the URL is stable.
    private const val CHARTS_URL = "https://www.youtube.com/feed/trending?bp=4gINGgt5dG1hX2NoYXJ0cw%3D%3D" // Music trending
    private const val CHARTS_URL_ALT = "https://www.youtube.com/feed/trending" // generic trending

    init {
        // Make sure NewPipe is initialised with localisation matching the app.
        runCatching {
            NewPipe.init(
                com.github.soundpod.NewPipeDownloader.getInstance(),
                Localization.fromLocale(Locale.getDefault()),
                ContentCountry(Locale.getDefault().country.ifBlank { "US" })
            )
        }.onFailure { Log.w(TAG, "NewPipe init failed: ${it.message}") }
    }

    private val service: StreamingService
        get() = NewPipe.getService(StreamingService.LinkHandlerFactory::class.java.let {
            try { ServiceHelper.service } catch (e: Throwable) { NewPipe.getService(0) }
        })

    /**
     * Fetch trending songs using NewPipeExtractor's KioskInfo.
     * Returns null on failure (logged with full details).
     */
    suspend fun fetchTrendingSongs(limit: Int = 10): List<NewPipeSong> = withContext(Dispatchers.IO) {
        try {
            val youtubeServiceId = NewPipe.getIdOfService("YouTube")
            val listUrl = CHARTS_URL
            Log.d(TAG, "Fetching trending from: $listUrl")

            val info = KioskInfo.getInfo(youtubeServiceId, listUrl)
            val songs = info.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .filter { it.streamType == StreamInfoItem.StreamType.AUDIO_STREAM }
                .take(limit)
                .map { item ->
                    NewPipeSong(
                        id = extractVideoId(item.url),
                        title = item.name,
                        artist = item.uploaderName,
                        duration = item.duration,
                        thumbnailUrl = item.thumbnails.firstOrNull()?.url,
                        url = item.url
                    )
                }
            Log.d(TAG, "Got ${songs.size} trending songs from NewPipeExtractor")
            songs
        } catch (e: ExtractionException) {
            Log.e(TAG, "NewPipe extraction failed: ${e.message}", e)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Trending fetch failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Fetch related videos for a given video using NewPipeExtractor.
     */
    suspend fun fetchRelated(videoId: String, limit: Int = 15): List<NewPipeSong> = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            val info = StreamInfo.getInfo(url)
            val related = info.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .filter { it.streamType == StreamInfoItem.StreamType.AUDIO_STREAM }
                .take(limit)
                .map { item ->
                    NewPipeSong(
                        id = extractVideoId(item.url),
                        title = item.name,
                        artist = item.uploaderName,
                        duration = item.duration,
                        thumbnailUrl = item.thumbnails.firstOrNull()?.url,
                        url = item.url
                    )
                }
            Log.d(TAG, "Got ${related.size} related songs for $videoId")
            related
        } catch (e: Exception) {
            Log.e(TAG, "Related fetch failed for $videoId: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Fetch search results using NewPipeExtractor.
     */
    suspend fun search(query: String, limit: Int = 10): List<NewPipeSong> = withContext(Dispatchers.IO) {
        try {
            val youtubeServiceId = NewPipe.getIdOfService("YouTube")
            val info = SearchInfo.getInfo(
                youtubeServiceId,
                "https://www.youtube.com/results?search_query=${java.net.URLEncoder.encode(query, "UTF-8")}&sp=EgKAQgIIAUICVAXgAw%3D%3D"
            )
            info.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .take(limit)
                .map { item ->
                    NewPipeSong(
                        id = extractVideoId(item.url),
                        title = item.name,
                        artist = item.uploaderName,
                        duration = item.duration,
                        thumbnailUrl = item.thumbnails.firstOrNull()?.url,
                        url = item.url
                    )
                }
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for '$query': ${e.message}", e)
            emptyList()
        }
    }

    private fun extractVideoId(url: String): String =
        url.substringAfter("v=", "").substringBefore("&").substringBefore("?")
            .ifBlank { url.substringAfter("youtu.be/").substringBefore("?") }
            .ifBlank { url.hashCode().toString() }
}

data class NewPipeSong(
    val id: String,
    val title: String,
    val artist: String?,
    val duration: Long,
    val thumbnailUrl: String?,
    val url: String
)

/** Simple helper to get the YouTube service id without importing internal classes. */
private object ServiceHelper {
    val service: Int get() = NewPipe.getIdOfService("YouTube")
}
