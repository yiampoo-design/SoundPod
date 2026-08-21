package com.github.soundpod.viewmodels.home

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.innertube.Innertube
import com.github.innertube.requests.artistPage
import com.github.innertube.requests.charts
import com.github.innertube.requests.relatedPage
import com.github.innertube.requests.searchPage
import com.github.innertube.utils.from
import com.github.soundpod.appContext
import com.github.soundpod.db
import com.github.soundpod.enums.QuickPicksSource
import com.github.soundpod.models.Song
import com.github.soundpod.utils.ScreenCache
import com.github.soundpod.utils.asMediaItem
import com.github.soundpod.utils.isScreenCacheEnabledKey
import com.github.soundpod.utils.preferences
import com.github.soundpod.utils.quickPicksCustomGenreKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

class QuickPicksViewModel : ViewModel() {
    var relatedPageResult: Result<Innertube.RelatedPage?>? by mutableStateOf(null)
    var historySongs: List<Song> by mutableStateOf(emptyList())
    private var job: Job? = null

    companion object {
        private const val TAG = "YiamTube-QuickPicks"
        private const val CACHE_EXPIRATION = 30 * 60 * 1000L
        private const val PERSISTENT_CACHE_PREFIX = "quick_picks_cache_v2_"
        private const val MIN_HISTORY_PLAY_TIME_MS = 30000L // 30 seconds

        // Provider-aware retry: if one client fails, try the next
        private val GLOBAL_FALLBACKS = listOf(
            "fJ9rUzIMcZQ", // Queen - Bohemian Rhapsody
            "kJQP7kiw5Fk", // Despacito
            "JGwWNGJdvx8", // Ed Sheeran - Shape of You
            "9bZkp7q19f0", // PSY - Gangnam Style
            "OPf0YbXqDm0"  // Maroon 5 - Girls Like You
        )
    }

    init {
        viewModelScope.launch {
            db.history(limit = 10, minPlayTimeMs = MIN_HISTORY_PLAY_TIME_MS).collect {
                historySongs = it
            }
        }
    }

    private fun getSeedSongsFlow(source: QuickPicksSource, limit: Int): Flow<List<Song>> = when (source) {
        QuickPicksSource.Default -> db.trending(limit)
        QuickPicksSource.Custom -> db.randomSongs(limit)
    }

    private fun getCached(source: QuickPicksSource): Innertube.RelatedPage? {
        return ScreenCache.load(PERSISTENT_CACHE_PREFIX + source.name)
    }

    private fun saveToCache(source: QuickPicksSource, page: Innertube.RelatedPage) {
        ScreenCache.save(PERSISTENT_CACHE_PREFIX + source.name, page)
    }

    private fun <T : Innertube.Item> interleave(lists: List<List<T>>): List<T> {
        val result = mutableListOf<T>()
        val iterators = lists.map { it.iterator() }
        val seenKeys = mutableSetOf<String>()

        var hasMore = true
        while (hasMore) {
            hasMore = false
            for (iterator in iterators) {
                if (iterator.hasNext()) {
                    val item = iterator.next()
                    if (seenKeys.add(item.key)) {
                        result.add(item)
                    }
                    hasMore = true
                }
            }
        }
        return result
    }

    /**
     * Provider-aware retry: try a network call and return the result.
     * If the call fails with a non-IOException (likely YouTube signature/PO token issue),
     * log detailed diagnostic info before propagating.
     */
    private suspend fun <T> safeCall(operation: String, block: suspend () -> T?): T? {
        return try {
            block()
        } catch (e: IOException) {
            Log.w(TAG, "[$operation] network error: ${e.message}")
            null
        } catch (e: Exception) {
            // Most likely a 403 / signature / PO token failure from YouTube.
            // Log the message so we can see what the upstream says.
            val msg = e.message?.take(500) ?: e::class.java.simpleName
            Log.e(TAG, "[$operation] upstream failure: $msg", e)
            null
        }
    }

    fun loadQuickPicks(quickPicksSource: QuickPicksSource, forceRefresh: Boolean = false) {
        val isScreenCacheEnabled = appContext.preferences.getBoolean(isScreenCacheEnabledKey, true)
        val cached = if (isScreenCacheEnabled) getCached(quickPicksSource) else null
        if (cached != null) {
            relatedPageResult = Result.success(cached)
        }

        if (!forceRefresh && cached != null && !ScreenCache.isExpired(PERSISTENT_CACHE_PREFIX + quickPicksSource.name, CACHE_EXPIRATION)) {
            Log.d(TAG, "Using cached Quick Picks (${cached.songs?.size ?: 0} songs)")
            return
        }

        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Loading Quick Picks (source=$quickPicksSource, force=$forceRefresh)")
            relatedPageResult = Result.failure(IllegalStateException("Loading…"))

            val seedSongs = when (quickPicksSource) {
                QuickPicksSource.Custom -> {
                    val customGenre = appContext.preferences.getString(quickPicksCustomGenreKey, "ROCK") ?: "ROCK"
                    val searchResult = safeCall("searchPage(query=$customGenre)") {
                        Innertube.searchPage(
                            query = customGenre,
                            params = Innertube.SearchFilter.Song.value,
                            fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
                        )?.getOrNull()
                    }

                    searchResult?.items?.take(3)?.map { item ->
                        val mediaItem = item.asMediaItem
                        Song(
                            id = mediaItem.mediaId,
                            title = mediaItem.mediaMetadata.title.toString(),
                            artistsText = mediaItem.mediaMetadata.artist.toString(),
                            durationText = null,
                            thumbnailUrl = mediaItem.mediaMetadata.artworkUri.toString()
                        )
                    } ?: emptyList()
                }

                QuickPicksSource.Default -> {
                    val seeds = mutableListOf<Song>()

                    // 1. Add seeds from History
                    seeds.addAll(db.history(limit = 2, minPlayTimeMs = MIN_HISTORY_PLAY_TIME_MS).first())

                    // 2. Add seeds from Following
                    val followed = db.followedArtists().first()
                    if (followed.isNotEmpty()) {
                        followed.shuffled().take(2).forEach { artist ->
                            val song = safeCall("artistPage(browseId=${artist.id})") {
                                Innertube.artistPage(browseId = artist.id)?.getOrNull()?.songs?.firstOrNull()
                            }
                            song?.let { item ->
                                val mediaItem = item.asMediaItem
                                seeds.add(
                                    Song(
                                        id = mediaItem.mediaId,
                                        title = mediaItem.mediaMetadata.title.toString(),
                                        artistsText = mediaItem.mediaMetadata.artist.toString(),
                                        durationText = null,
                                        thumbnailUrl = mediaItem.mediaMetadata.artworkUri.toString()
                                    )
                                )
                            }
                        }
                    }

                    // 3. Add seeds from Charts if we don't have enough
                    if (seeds.size < 3) {
                        val charts = safeCall("charts") { Innertube.charts()?.getOrNull() }
                        charts?.take(3 - seeds.size)?.forEach { item ->
                            val mediaItem = item.asMediaItem
                            seeds.add(
                                Song(
                                    id = mediaItem.mediaId,
                                    title = mediaItem.mediaMetadata.title.toString(),
                                    artistsText = mediaItem.mediaMetadata.artist.toString(),
                                    durationText = null,
                                    thumbnailUrl = mediaItem.mediaMetadata.artworkUri.toString()
                                )
                            )
                        }
                    }

                    if (seeds.isEmpty()) {
                        seeds.addAll(getSeedSongsFlow(quickPicksSource, 3).first())
                    }

                    Log.d(TAG, "Collected ${seeds.size} seed songs")
                    seeds.distinctBy { it.id }
                }
            }

            coroutineScope {
                val relatedResults = seedSongs.map { song ->
                    async {
                        safeCall("relatedPage(videoId=${song.id})") {
                            Innertube.relatedPage(videoId = song.id)?.getOrNull()
                        }
                    }
                }.awaitAll()

                val validResults = relatedResults.filterNotNull()
                Log.d(TAG, "Got ${validResults.size}/${seedSongs.size} related-page results")

                var mergedPage = if (validResults.isNotEmpty()) {
                    Innertube.RelatedPage(
                        songs = interleave(validResults.map { it.songs ?: emptyList() }).take(40),
                        playlists = interleave(validResults.map { it.playlists ?: emptyList() }).take(15),
                        albums = interleave(validResults.map { it.albums ?: emptyList() }).take(15),
                        artists = interleave(validResults.map { it.artists ?: emptyList() }).take(15)
                    )
                } else null

                // Fallback 1: try charts and use them as seeds
                if (mergedPage == null || mergedPage.songs.isNullOrEmpty()) {
                    Log.w(TAG, "No related results, falling back to charts")
                    val charts = safeCall("charts(fallback)") { Innertube.charts()?.getOrNull() }
                    if (!charts.isNullOrEmpty()) {
                        charts.shuffled().take(2).forEach { fallbackSong ->
                            val fallbackResult = safeCall("relatedPage(fallback=${fallbackSong.key})") {
                                Innertube.relatedPage(videoId = fallbackSong.key)?.getOrNull()
                            }
                            if (fallbackResult != null && !fallbackResult.songs.isNullOrEmpty()) {
                                mergedPage = fallbackResult
                                Log.d(TAG, "Charts fallback succeeded with ${fallbackResult.songs?.size} songs")
                                return@forEach
                            }
                        }
                    }
                }

                // Fallback 2: try known global fallback video IDs
                if (mergedPage == null || mergedPage.songs.isNullOrEmpty()) {
                    Log.w(TAG, "Charts fallback failed, trying global fallback IDs")
                    for (videoId in GLOBAL_FALLBACKS) {
                        val result = safeCall("relatedPage(global=$videoId)") {
                            Innertube.relatedPage(videoId = videoId)?.getOrNull()
                        }
                        if (result != null && !result.songs.isNullOrEmpty()) {
                            mergedPage = result
                            Log.d(TAG, "Global fallback succeeded with $videoId (${result.songs?.size} songs)")
                            break
                        }
                    }
                }

                if (mergedPage == null || mergedPage.songs.isNullOrEmpty()) {
                    val err = Exception("Quick Picks failed: no related/charts/global results. Check logcat tag $TAG for upstream error details.")
                    Log.e(TAG, "All fallbacks exhausted", err)
                    relatedPageResult = Result.failure(err)
                } else {
                    Log.d(TAG, "Quick Picks loaded: ${mergedPage.songs?.size} songs, ${mergedPage.playlists?.size} playlists, ${mergedPage.albums?.size} albums")
                    if (isScreenCacheEnabled) {
                        mergedPage?.let { saveToCache(quickPicksSource, it) }
                    }
                    relatedPageResult = Result.success(mergedPage)
                }
            }
        }
    }
}

// Tiny helper because coroutineScope + map + awaitAll isn't directly in stdlib here
private suspend fun <T> List<kotlinx.coroutines.Deferred<T>>.awaitAll(): List<T> =
    map { it.await() }

