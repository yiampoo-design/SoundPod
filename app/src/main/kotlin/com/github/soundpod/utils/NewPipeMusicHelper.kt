package com.github.soundpod.utils

import android.util.Log
import com.github.soundpod.NewPipeDownloader
import com.github.soundpod.appContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Locale

/**
 * Helper that uses NewPipeExtractor (already battle-tested against YouTube's
 * anti-bot) to fetch music content. The custom Innertube module in this app
 * has been returning 403s as YouTube tightened checks, so this is the
 * primary data source now.
 *
 * NewPipeExtractor is maintained by the NewPipe community and kept up to
 * date with YouTube's API changes.
 */
object NewPipeMusicHelper {
    private const val TAG = "YiamTube-NewPipe"

    // Kiosk URL for YouTube Music charts / trending.
    // bp=4gINGgt5dG1hX2NoYXJ0cw== selects the "Music" trending category.
    private const val CHARTS_URL_MUSIC = "https://www.youtube.com/feed/trending?bp=4gINGgt5dG1hX2NoYXJ0cw%3D%3D"
    private const val CHARTS_URL_GENERIC = "https://www.youtube.com/feed/trending"

    // Music songs filter for search
    private val MUSIC_SONGS_FILTER = listOf("EgKAQgIIAUICVAXgAw%3D%3D")

    init {
        // Make sure NewPipe is initialised with localisation matching the app.
        runCatching {
            NewPipe.init(
                NewPipeDownloader.getInstance(),
                Localization.fromLocale(Locale.getDefault()),
                ContentCountry(Locale.getDefault().country.ifBlank { "US" })
            )
        }.onFailure { Log.w(TAG, "NewPipe init failed: ${it.message}") }
    }

    /** YouTube service. Always registered first by NewPipeExtractor (id 0). */
    private val service: StreamingService by lazy { NewPipe.getService(0) }

    /**
     * Fetch trending songs using NewPipeExtractor's KioskInfo.
     * Returns empty list on failure (logged with full details).
     */
    suspend fun fetchTrendingSongs(limit: Int = 10): List<NewPipeSong> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching trending from: $CHARTS_URL_MUSIC")
            val info = KioskInfo.getInfo(service, CHARTS_URL_MUSIC)
            val songs = info.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .filter { it.streamType == StreamType.AUDIO_STREAM }
                .take(limit)
                .map { it.toNewPipeSong() }
            Log.d(TAG, "Got ${songs.size} trending songs from NewPipeExtractor")
            songs
        } catch (e: ExtractionException) {
            Log.w(TAG, "Music charts extraction failed, trying generic: ${e.message}")
            tryFallbackTrending(limit)
        } catch (e: Exception) {
            Log.e(TAG, "Trending fetch failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun tryFallbackTrending(limit: Int): List<NewPipeSong> = try {
        val info = KioskInfo.getInfo(service, CHARTS_URL_GENERIC)
        info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .take(limit)
            .map { it.toNewPipeSong() }
    } catch (e: Exception) {
        Log.e(TAG, "Generic trending also failed: ${e.message}", e)
        emptyList()
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
                .filter { it.streamType == StreamType.AUDIO_STREAM }
                .take(limit)
                .map { it.toNewPipeSong() }
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
            val handler = service.searchQHFactory.fromQuery(query, MUSIC_SONGS_FILTER, "")
            val info = SearchInfo.getInfo(service, handler)
            info.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .take(limit)
                .map { it.toNewPipeSong() }
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for '$query': ${e.message}", e)
            emptyList()
        }
    }

    private fun StreamInfoItem.toNewPipeSong(): NewPipeSong {
        return NewPipeSong(
            id = extractVideoId(url),
            title = name,
            artist = uploaderName,
            duration = duration,
            thumbnailUrl = thumbnails.firstOrNull()?.url,
            url = url
        )
    }

    private fun extractVideoId(url: String): String {
        if (url.isBlank()) return ""
        return url.substringAfter("v=", "")
            .substringBefore("&")
            .substringBefore("?")
            .ifBlank { url.substringAfter("youtu.be/").substringBefore("?") }
            .ifBlank { url.hashCode().toString() }
    }
}

data class NewPipeSong(
    val id: String,
    val title: String,
    val artist: String?,
    val duration: Long,
    val thumbnailUrl: String?,
    val url: String
)
