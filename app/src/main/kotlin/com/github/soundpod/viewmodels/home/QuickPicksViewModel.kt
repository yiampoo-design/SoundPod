package com.github.soundpod.viewmodels.home

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.innertube.Innertube
import com.github.soundpod.db
import com.github.soundpod.enums.QuickPicksSource
import com.github.soundpod.models.Song
import com.github.soundpod.utils.NewPipeMusicHelper
import com.github.soundpod.utils.NewPipeSong
import com.github.soundpod.utils.ScreenCache
import com.github.soundpod.utils.asMediaItem
import com.github.soundpod.utils.isScreenCacheEnabledKey
import com.github.soundpod.utils.preferences
import com.github.soundpod.utils.quickPicksCustomGenreKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class QuickPicksViewModel : ViewModel() {
    var relatedPageResult: Result<Innertube.RelatedPage?>? by mutableStateOf(null)
    var historySongs: List<Song> by mutableStateOf(emptyList())
    private var job: Job? = null

    companion object {
        private const val TAG = "YiamTube-QuickPicks"
        private const val CACHE_EXPIRATION = 30 * 60 * 1000L
        private const val PERSISTENT_CACHE_PREFIX = "quick_picks_cache_v2_"
        private const val MIN_HISTORY_PLAY_TIME_MS = 30000L

        // Global fallback video IDs (used only as a last resort)
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

    fun loadQuickPicks(quickPicksSource: QuickPicksSource, forceRefresh: Boolean = false) {
        val isScreenCacheEnabled = com.github.soundpod.appContext.preferences.getBoolean(isScreenCacheEnabledKey, true)
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

            try {
                // Use NewPipeExtractor (community-maintained, kept up to date with YouTube).
                val songs = loadViaNewPipeExtractor(quickPicksSource)
                if (songs.isNotEmpty()) {
                    val page = Innertube.RelatedPage(
                        songs = songs.map { it.toSongItem() },
                        playlists = emptyList(),
                        albums = emptyList(),
                        artists = emptyList()
                    )
                    Log.d(TAG, "NewPipeExtractor returned ${songs.size} songs")
                    if (isScreenCacheEnabled) {
                        saveToCache(quickPicksSource, page)
                    }
                    relatedPageResult = Result.success(page)
                } else {
                    val err = Exception("NewPipeExtractor returned no songs. Check logcat tag $TAG for details.")
                    Log.e(TAG, "No songs returned", err)
                    relatedPageResult = Result.failure(err)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Quick Picks: ${e.message}", e)
                relatedPageResult = Result.failure(e)
            }
        }
    }

    /**
     * Uses NewPipeExtractor to fetch charts and related videos.
     * NewPipeExtractor is maintained by the NewPipe community and uses its own
     * internal request handling that has been kept up to date with YouTube's
     * anti-bot changes.
     */
    private suspend fun loadViaNewPipeExtractor(source: QuickPicksSource): List<NewPipeSong> {
        return try {
            val songs = mutableListOf<NewPipeSong>()

            // 1. Get trending charts from NewPipe
            val trending = NewPipeMusicHelper.fetchTrendingSongs(limit = 10)
            songs.addAll(trending)
            Log.d(TAG, "NewPipe trending: ${trending.size} songs")

            // 2. If we have seed songs from history, use them for related
            if (songs.size < 5) {
                val historySeeds = try {
                    db.history(limit = 3, minPlayTimeMs = MIN_HISTORY_PLAY_TIME_MS).first()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read history: ${e.message}")
                    emptyList()
                }

                historySeeds.forEach { seed ->
                    val related = NewPipeMusicHelper.fetchRelated(seed.id, limit = 5)
                    songs.addAll(related)
                    if (songs.size >= 15) return@forEach
                }
            }

            // 3. If still not enough, try a search
            if (songs.size < 5 && source == QuickPicksSource.Custom) {
                val customGenre = com.github.soundpod.appContext.preferences.getString(quickPicksCustomGenreKey, "ROCK") ?: "ROCK"
                val search = NewPipeMusicHelper.search(customGenre, limit = 10)
                songs.addAll(search)
            }

            // 4. Last resort: try global fallbacks
            if (songs.size < 3) {
                for (id in GLOBAL_FALLBACKS) {
                    val related = NewPipeMusicHelper.fetchRelated(id, limit = 3)
                    songs.addAll(related)
                    if (songs.size >= 10) break
                }
            }

            songs.distinctBy { it.id }
        } catch (e: Exception) {
            Log.e(TAG, "NewPipe approach failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun NewPipeSong.toSongItem(): Innertube.SongItem {
        return Innertube.SongItem(
            info = Innertube.Info(
                name = title,
                endpoint = com.github.innertube.models.NavigationEndpoint.Endpoint.Watch(videoId = id)
            ),
            authors = if (artist != null) listOf(
                Innertube.Info(
                    name = artist,
                    endpoint = com.github.innertube.models.NavigationEndpoint.Endpoint.Browse(browseId = "UC$artist")
                )
            ) else null,
            album = null,
            durationText = null,
            thumbnail = thumbnailUrl?.let {
                com.github.innertube.models.Thumbnail(
                    url = it,
                    width = 320,
                    height = 320
                )
            }
        )
    }
}
