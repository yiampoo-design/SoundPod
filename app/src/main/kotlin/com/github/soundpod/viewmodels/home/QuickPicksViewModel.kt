package com.github.soundpod.viewmodels.home

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.innertube.Innertube
import com.github.innertube.requests.artistPage
import com.github.innertube.requests.albumPage
import com.github.innertube.requests.charts
import com.github.innertube.requests.relatedPage
import com.github.innertube.requests.searchPage
import com.github.innertube.utils.from
import com.github.soundpod.appContext
import com.github.soundpod.db
import com.github.soundpod.enums.QuickPicksSource
import com.github.soundpod.models.Artist
import com.github.soundpod.models.SearchQuery
import com.github.soundpod.models.Song
import com.github.soundpod.utils.ScreenCache
import com.github.soundpod.utils.asMediaItem
import com.github.soundpod.utils.computeOnboardingWeight
import com.github.soundpod.utils.getOnboardingArtists
import com.github.soundpod.utils.getOnboardingDiscovery
import com.github.soundpod.utils.getOnboardingEras
import com.github.soundpod.utils.getOnboardingGenres
import com.github.soundpod.utils.isOnboardingCompleted
import com.github.soundpod.utils.isOnboardingSkipped
import com.github.soundpod.utils.onboardingVersionKey
import com.github.soundpod.utils.isScreenCacheEnabledKey
import com.github.soundpod.utils.preferences
import com.github.soundpod.utils.quickPicksCustomGenreKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.random.Random

class QuickPicksViewModel : ViewModel() {
    var relatedPageResult: Result<Innertube.RelatedPage?>? by mutableStateOf(null)
    var historySongs: List<Song> by mutableStateOf(emptyList())
    var isRefreshing: Boolean by mutableStateOf(false)
        private set
    var debugState: DebugState? by mutableStateOf(null)
        private set
    private var job: Job? = null
    private var generationNonce: Int = 0
    private var currentFingerprint: String? = null
    private var tasteObserverJob: Job? = null
    private val generationMutex = Mutex()
    private val generationToken = AtomicInteger(0)

    companion object {
        private const val TAG = "YiamTube-QuickPicks"
        private const val CACHE_EXPIRATION = 30 * 60 * 1000L
        private const val PERSISTENT_CACHE_PREFIX_V3 = "quick_picks_v3_"
        private const val RECENTLY_SHOWN_KEY = "quick_picks_recently_shown_v1"
        private const val RECENTLY_SHOWN_MAX = 60
        private const val MIN_HISTORY_PLAY_TIME_MS = 30000L
        private const val SUFFICIENT_HISTORY_THRESHOLD = 10
    }

    @Serializable
    data class DebugState(
        val fingerprint: String = "",
        val fingerprintShort: String = "",
        val generationNonce: Int = 0,
        val seedIds: List<String> = emptyList(),
        val seedCategories: List<String> = emptyList(),
        val candidatePoolSize: Int = 0,
        val recentlyShownCount: Int = 0,
        val finalResultCount: Int = 0,
        val isColdStart: Boolean = false,
        val cacheHit: Boolean = false,
        val generationSource: String = "GENERATED",
        val meaningfulPlayCount: Int = 0,
        val personalizationStrength: Double = 0.0,
        val sourceCandidateCounts: Map<String, Int> = emptyMap(),
        val providerCounts: Map<String, Int> = emptyMap(),
        val finalSourceCounts: Map<String, Int> = emptyMap(),
        val providerFailures: Map<String, Int> = emptyMap(),
        val tasteAnchors: List<String> = emptyList(),
        val personalizedPercent: Double = 0.0,
        val onboardingCompleted: Boolean = false,
        val onboardingSkipped: Boolean = false,
        val onboardingWeight: Double = 0.0,
        val onboardingGenres: List<String> = emptyList(),
        val onboardingEras: List<String> = emptyList(),
        val onboardingDiscovery: String = "BALANCED",
        val onboardingAnchorCount: Int = 0,
        val behaviorAnchorCount: Int = 0,
        val totalBehaviorPlayTimeMs: Long = 0L,
        val latestEventTs: Long = 0L
    )

    enum class CandidateSource { RECENT, FREQUENT, LIKED, FOLLOWED, SEARCH, EXPLORATION, TRENDING, RANDOM }

    enum class RecSource(val weight: Double) {
        ANCHOR_RECENT(1.00), ANCHOR_FREQUENT(0.90), ANCHOR_LIKED(0.90),
        ANCHOR_FOLLOWED(0.85), ANCHOR_SEARCH(0.50), ONBOARDING(0.80),
        SEARCH(0.50), EXPLORATION(0.20),
        ARTIST(0.00), ALBUM(0.00), RELATED(0.00)
    }

    data class TasteAnchor(
        val artistName: String,
        val category: String,
        val weight: Double
    )

    data class RecommendationCandidate(
        val song: Innertube.SongItem,
        val source: RecSource,
        val seedId: String,
        val sourceWeight: Double,
        val personalizationAffinity: Double
    )

    private data class ProviderResult(
        val candidates: List<RecommendationCandidate>,
        val failures: Map<String, Int> = emptyMap()
    )

    data class Candidate(
        val song: Song,
        val tasteScore: Double,
        val source: CandidateSource,
        val lastInteractionTime: Long?
    )

    init {
        viewModelScope.launch {
            db.history(limit = 10, minPlayTimeMs = MIN_HISTORY_PLAY_TIME_MS).collect {
                historySongs = it
            }
        }
        observeTasteChanges()
    }

    @OptIn(FlowPreview::class)
    private fun observeTasteChanges() {
        tasteObserverJob?.cancel()
        tasteObserverJob = viewModelScope.launch(Dispatchers.IO) {
            // Reactive taste fingerprint flow: any meaningful DB change triggers recompute
            val fingerprintFlow = combine(
                combine(db.maxEventTimestamp(), db.eventsCount()) { a, b -> Pair(a, b) },
                combine(db.totalEventPlayTimeFlow(), db.favorites()) { a, b -> Pair(a, b) },
                combine(db.queries("%"), db.followedArtists()) { a, b -> Pair(a, b) }
            ) { p1, p2, p3 ->
                val maxTs = p1.first
                val count = p1.second
                val totalPlay = p2.first
                val favs = p2.second
                val queries = p3.first
                val followed = p3.second
                // Also include source/genre via preferences flow? For now compute for Default source; Custom handled via manual genre change
                // Use aggregates to build stable fingerprint; actual source-specific part computed in loadQuickPicks
                // Here we just emit a combined hash for change detection
                val likedMax = favs.maxOfOrNull { it.likedAt ?: 0L } ?: 0L
                val likedHash = favs.map { it.id }.sorted().joinToString(",").hashCode()
                val searchMaxId = queries.maxOfOrNull { it.id } ?: 0
                val followedHash = followed.map { it.id }.sorted().joinToString(",").hashCode()
                // Use event aggregates as primary detector
                "evt_${maxTs}_${count}_${totalPlay}_l${likedMax}_${likedHash}_s${searchMaxId}_f${followedHash}"
            }.debounce(700).distinctUntilChanged()

            fingerprintFlow.collect { _ ->
                // Recompute full fingerprint for Default source (most common Home)
                val newFp = try { buildFingerprint(QuickPicksSource.Default) } catch (_: Exception) { null } ?: return@collect
                if (currentFingerprint != null && newFp != currentFingerprint) {
                    Log.d(TAG, "Taste fingerprint changed ${currentFingerprint?.take(6)} -> ${newFp.take(6)} - regenerating Quick Picks")
                    // Bypass old cache and regenerate
                    loadQuickPicks(QuickPicksSource.Default, forceRefresh = false)
                }
            }
        }
    }

    private fun getSeedSongsFlow(source: QuickPicksSource, limit: Int): Flow<List<Song>> = when (source) {
        QuickPicksSource.Default -> db.trending(limit)
        QuickPicksSource.Custom -> db.randomSongs(limit)
    }

    private fun getCachedV3(source: QuickPicksSource, fingerprint: String): Innertube.RelatedPage? {
        return ScreenCache.load(PERSISTENT_CACHE_PREFIX_V3 + source.name + "_" + fingerprint)
    }

    private fun saveToCacheV3(source: QuickPicksSource, fingerprint: String, page: Innertube.RelatedPage) {
        ScreenCache.save(PERSISTENT_CACHE_PREFIX_V3 + source.name + "_" + fingerprint, page)
    }

    // Wrapper to store debug metadata alongside cached page for accurate cache-hit debug
    @Serializable
    private data class CachedWrapper(
        val page: Innertube.RelatedPage,
        val debug: DebugState
    )

    private fun getCachedWrapper(source: QuickPicksSource, fingerprint: String): CachedWrapper? {
        return try { ScreenCache.load<CachedWrapper>(PERSISTENT_CACHE_PREFIX_V3 + source.name + "_" + fingerprint + "_meta") } catch (_: Exception) { null }
    }

    private fun saveCachedWrapper(source: QuickPicksSource, fingerprint: String, page: Innertube.RelatedPage, debug: DebugState) {
        try { ScreenCache.save(PERSISTENT_CACHE_PREFIX_V3 + source.name + "_" + fingerprint + "_meta", CachedWrapper(page, debug)) } catch (_: Exception) {}
        saveToCacheV3(source, fingerprint, page)
    }

    private fun loadRecentlyShown(): List<String> {
        return try { ScreenCache.load<List<String>>(RECENTLY_SHOWN_KEY) ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    private fun saveRecentlyShown(ids: List<String>) {
        val trimmed = ids.takeLast(RECENTLY_SHOWN_MAX)
        ScreenCache.save(RECENTLY_SHOWN_KEY, trimmed)
    }

    private fun computePersonalizationStrength(meaningfulPlayCount: Int): Double {
        // Continuous curve: 0 plays=0.00, 1=0.25, 2=0.40, 3=0.55, 5=0.70, 10+=0.90
        return when {
            meaningfulPlayCount <= 0 -> 0.00
            meaningfulPlayCount == 1 -> 0.25
            meaningfulPlayCount == 2 -> 0.40
            meaningfulPlayCount == 3 -> 0.55
            meaningfulPlayCount <= 5 -> 0.55 + (meaningfulPlayCount - 3) * 0.075  // 0.55..0.70
            meaningfulPlayCount <= 10 -> 0.70 + (meaningfulPlayCount - 5) * 0.04  // 0.70..0.90
            else -> 0.90
        }
    }

    private fun computeExplorationRatio(personalizationStrength: Double): Double {
        // lerp(0.70, 0.10, P): more plays = less exploration
        val p = personalizationStrength.coerceIn(0.0, 1.0)
        return 0.70 + (0.10 - 0.70) * p
    }

    private suspend fun buildFingerprint(source: QuickPicksSource): String {
        return try {
            // Use aggregate Event queries as primary playback detector - MUST change on new Event insert
            val eventMaxTs = db.getMaxEventTimestamp() ?: 0L
            val eventCount = db.getEventCount()
            val eventPlayTime = db.getTotalEventPlayTime()
            val latestLikedAt = db.favorites().first().maxOfOrNull { it.likedAt ?: 0L } ?: 0L
            val likedHash = db.favorites().first().map { it.id }.sorted().joinToString(",").hashCode()
            val latestSearchId = db.queries("%").first().maxOfOrNull { it.id } ?: 0
            val followedHash = db.followedArtists().first().map { it.id }.sorted().joinToString(",").hashCode()
            val customGenre = if (source == QuickPicksSource.Custom) appContext.preferences.getString(quickPicksCustomGenreKey, "ROCK") ?: "ROCK" else source.name
            val onboardingCompleted = appContext.preferences.isOnboardingCompleted()
            val onboardingArtistsHash = appContext.preferences.getOnboardingArtists().joinToString(",") { it.name + (it.browseId ?: "") }.hashCode()
            val onboardingDiscovery = appContext.preferences.getOnboardingDiscovery().name
            val onboardingVersion = appContext.preferences.getInt(onboardingVersionKey, 0)
            // Raw fingerprint source as specified
            val raw = "eventMaxTs=${eventMaxTs}_eventCount=${eventCount}_eventPlayTime=${eventPlayTime}_likedMax=${latestLikedAt}_likedHash=${likedHash}_searchMaxId=${latestSearchId}_followedHash=${followedHash}_source=${source.name}_genre=${customGenre}_onbComp=${onboardingCompleted}_onbArt=${onboardingArtistsHash}_onbDisc=${onboardingDiscovery}_onbVer=${onboardingVersion}"
            val hash = raw.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }.toString(36)
            hash
        } catch (_: Exception) {
            Random.nextInt(100000).toString(36)
        }
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

    private fun <T> weightedSampleWithoutReplacement(
        items: List<Pair<T, Double>>,
        count: Int,
        random: Random
    ): List<T> {
        if (items.isEmpty() || count <= 0) return emptyList()
        val mutable = items.toMutableList()
        val result = mutableListOf<T>()
        repeat(minOf(count, mutable.size)) {
            val totalWeight = mutable.sumOf { it.second.coerceAtLeast(0.02) }
            var r = random.nextDouble() * totalWeight
            var idx = 0
            for (i in mutable.indices) {
                r -= mutable[i].second.coerceAtLeast(0.02)
                if (r <= 0) { idx = i; break }
            }
            result.add(mutable[idx].first)
            mutable.removeAt(idx)
        }
        return result
    }

    fun loadQuickPicks(quickPicksSource: QuickPicksSource, forceRefresh: Boolean = false) {
        val isScreenCacheEnabled = appContext.preferences.getBoolean(isScreenCacheEnabledKey, true)
        if (forceRefresh) generationNonce++

        viewModelScope.launch {
            val myToken: Int
            generationMutex.withLock {
                job?.cancelAndJoin()
                myToken = generationToken.incrementAndGet()
                job = launch(Dispatchers.IO) {
                    isRefreshing = true
                    try {
                        Log.d(TAG, "Loading Quick Picks V2 (source=$quickPicksSource, force=$forceRefresh, nonce=$generationNonce) token=$myToken")
            val fingerprint = buildFingerprint(quickPicksSource)
            val fingerprintShort = fingerprint.take(6)
            val cacheKeyV3 = PERSISTENT_CACHE_PREFIX_V3 + quickPicksSource.name + "_" + fingerprint

            // Cache must never cross fingerprints: only accept when cachedFingerprint == currentFingerprint
            // Do NOT fallback to v2/static key
            if (!forceRefresh && isScreenCacheEnabled) {
                val cachedWrapper = getCachedWrapper(quickPicksSource, fingerprint)
                val cachedV3 = cachedWrapper?.page ?: getCachedV3(quickPicksSource, fingerprint)
                if (cachedV3 != null && !ScreenCache.isExpired(cacheKeyV3, CACHE_EXPIRATION)) {
                    if (myToken != generationToken.get()) {
                        Log.d(TAG, "Stale token $myToken vs ${generationToken.get()} - discarding cache hit")
                        return@launch
                    }
                    relatedPageResult = Result.success(cachedV3)
                    Log.d(TAG, "Cache HIT V2 (${cachedV3.songs?.size ?: 0} songs) fp=$fingerprintShort nonce=$generationNonce")
                    // Restore debug metadata if available, else show generationSource=CACHE with cacheHit=true
                    val restoredDebug = cachedWrapper?.debug
                    debugState = if (restoredDebug != null) {
                        restoredDebug.copy(cacheHit = true)
                    } else {
                        DebugState(
                            fingerprint = fingerprint,
                            fingerprintShort = fingerprintShort,
                            generationNonce = generationNonce,
                            seedIds = emptyList(),
                            seedCategories = emptyList(),
                            candidatePoolSize = 0,
                            recentlyShownCount = loadRecentlyShown().size,
                            finalResultCount = cachedV3.songs?.size ?: 0,
                            isColdStart = false,
                            cacheHit = true,
                            generationSource = "CACHE"
                        )
                    }
                    currentFingerprint = fingerprint
                    return@launch
                }
            }

            // If we reach here, we must do fresh generation - DO NOT shuffle cached final results
            currentFingerprint = fingerprint
                        val recentlyShownSnapshot = loadRecentlyShown().toList()
                        val recentlyShownSet = recentlyShownSnapshot.toSet()

                        coroutineScope {
                val chartsDeferred = async {
                    runCatching { Innertube.charts()?.getOrNull().orEmpty() }
                        .onFailure { Log.w(TAG, "Innertube.charts failed: ${it.message}") }
                        .getOrElse { emptyList() }
                }

                val seedSongs: List<Song>
                var candidatePoolSize = 0
                var seedCategories: List<String> = emptyList()
                var isColdStart = false
                val sourceCounts = mutableMapOf<String, Int>()
                var trendingList: List<Song> = emptyList()

                when (quickPicksSource) {
                    QuickPicksSource.Custom -> {
                        val customGenre = appContext.preferences.getString(quickPicksCustomGenreKey, "ROCK") ?: "ROCK"
                        val searchResult = Innertube.searchPage(
                            query = customGenre,
                            params = Innertube.SearchFilter.Song.value,
                            fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
                        )?.getOrNull()
                        seedSongs = searchResult?.items?.take(5)?.map { item ->
                            val mediaItem = item.asMediaItem
                            Song(id = mediaItem.mediaId, title = mediaItem.mediaMetadata.title.toString(), artistsText = mediaItem.mediaMetadata.artist.toString(), durationText = null, thumbnailUrl = mediaItem.mediaMetadata.artworkUri.toString())
                        } ?: emptyList()
                        candidatePoolSize = seedSongs.size
                        seedCategories = List(seedSongs.size) { "CUSTOM_SEARCH" }
                        isColdStart = seedSongs.isEmpty()
                    }
                    QuickPicksSource.Default -> {
                        val historyList = db.history(limit = 30, minPlayTimeMs = MIN_HISTORY_PLAY_TIME_MS).first()
                        trendingList = db.trending(limit = 30).first()
                        val favoritesList = db.favorites().first()
                        val followedArtists = db.followedArtists().first()
                        val recentSearches = db.queries("%").first().take(5)
                        val randomPool = db.randomSongs(limit = 30).first()
                        val frequentList = db.history(limit = 50, minPlayTimeMs = MIN_HISTORY_PLAY_TIME_MS).first().let { hist ->
                            (hist + trendingList).distinctBy { it.id }.sortedByDescending { it.totalPlayTimeMs }.take(30)
                        }

                        val now = System.currentTimeMillis()
                        val maxPlayTime = (historyList + trendingList + favoritesList + frequentList).maxOfOrNull { it.totalPlayTimeMs }?.coerceAtLeast(1) ?: 1L
                        val recentlyShownIds = recentlyShownSet

                        val meaningfulPlayCount = db.getEventCount()
                        isColdStart = meaningfulPlayCount < SUFFICIENT_HISTORY_THRESHOLD
                        val personalizationStrength = computePersonalizationStrength(meaningfulPlayCount)
                        val explorationRatio = computeExplorationRatio(personalizationStrength)

                        val candidates = mutableListOf<Candidate>()

                        // RECENT: last 30 meaningful plays with high recency scores
                        historyList.forEachIndexed { idx, song ->
                            val recencyScore = exp(-0.14 * idx)
                            val lastInteraction = now - idx * 86400000L
                            candidates.add(Candidate(song, recencyScore * 0.9 + 0.1, CandidateSource.RECENT, lastInteraction))
                        }
                        sourceCounts["RECENT"] = historyList.size

                        // FREQUENT: top 30 by total play time
                        frequentList.forEach { song ->
                            val repeatScore = (song.totalPlayTimeMs.toDouble() / maxPlayTime).coerceIn(0.0, 1.0)
                            candidates.add(Candidate(song, repeatScore, CandidateSource.FREQUENT, null))
                        }
                        sourceCounts["FREQUENT"] = frequentList.size

                        // LIKED
                        favoritesList.forEach { song ->
                            val daysAgo = ((now - (song.likedAt ?: now)) / 86400000.0).coerceAtLeast(0.0)
                            val likedScore = exp(-0.07 * daysAgo)
                            candidates.add(Candidate(song, likedScore, CandidateSource.LIKED, song.likedAt))
                        }
                        sourceCounts["LIKED"] = favoritesList.size

                        // FOLLOWED: top songs from up to5 followed artists
                        if (followedArtists.isNotEmpty()) {
                            var followedCount = 0
                            followedArtists.shuffled().take(5).forEach { artist ->
                                val songsFromArtist = Innertube.artistPage(browseId = artist.id)?.getOrNull()?.let { page ->
                                    (page.songs?.take(3) ?: emptyList())
                                } ?: emptyList()
                                songsFromArtist.forEach { item ->
                                    val mediaItem = item.asMediaItem
                                    val s = Song(id = mediaItem.mediaId, title = mediaItem.mediaMetadata.title.toString(), artistsText = mediaItem.mediaMetadata.artist.toString(), durationText = null, thumbnailUrl = mediaItem.mediaMetadata.artworkUri.toString())
                                    candidates.add(Candidate(s, 0.7, CandidateSource.FOLLOWED, null))
                                    followedCount++
                                }
                                if (songsFromArtist.isEmpty()) {
                                    Innertube.artistPage(browseId = artist.id)?.getOrNull()?.songs?.firstOrNull()?.let { item ->
                                        val mi = item.asMediaItem
                                        candidates.add(Candidate(Song(id = mi.mediaId, title = mi.mediaMetadata.title.toString(), artistsText = mi.mediaMetadata.artist.toString(), durationText = null, thumbnailUrl = mi.mediaMetadata.artworkUri.toString()), 0.6, CandidateSource.FOLLOWED, null))
                                        followedCount++
                                    }
                                }
                            }
                            sourceCounts["FOLLOWED"] = followedCount
                        }

                        // SEARCH: resolve top5 search queries
                        var searchCount = 0
                        if (recentSearches.isNotEmpty()) {
                            recentSearches.take(5).forEach { q ->
                                try {
                                    val searchRes = Innertube.searchPage(query = q.query, params = Innertube.SearchFilter.Song.value, fromMusicShelfRendererContent = Innertube.SongItem.Companion::from)?.getOrNull()
                                    searchRes?.items?.take(3)?.forEach { item ->
                                        val mi = item.asMediaItem
                                        val s = Song(id = mi.mediaId, title = mi.mediaMetadata.title.toString(), artistsText = mi.mediaMetadata.artist.toString(), durationText = null, thumbnailUrl = mi.mediaMetadata.artworkUri.toString())
                                        candidates.add(Candidate(s, 0.35, CandidateSource.SEARCH, null))
                                        searchCount++
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                        sourceCounts["SEARCH"] = searchCount

                        // EXPLORATION: charts + trending tail + random
                        val chartSongs = chartsDeferred.await()
                        var explorationCount = 0
                        chartSongs.take(15).forEach { item ->
                            val mi = item.asMediaItem
                            candidates.add(Candidate(Song(id = mi.mediaId, title = mi.mediaMetadata.title.toString(), artistsText = mi.mediaMetadata.artist.toString(), durationText = null, thumbnailUrl = mi.mediaMetadata.artworkUri.toString()), 0.3, CandidateSource.EXPLORATION, null))
                            explorationCount++
                        }
                        trendingList.take(15).forEach { s ->
                            candidates.add(Candidate(s, 0.25, CandidateSource.TRENDING, null))
                            explorationCount++
                        }
                        randomPool.take(15).forEach { s ->
                            candidates.add(Candidate(s, 0.2, CandidateSource.RANDOM, null))
                            explorationCount++
                        }
                        sourceCounts["EXPLORATION"] = explorationCount

                        // Dedupe: keep highest tasteScore per song ID
                        val dedupedMap = mutableMapOf<String, Candidate>()
                        candidates.forEach { c ->
                            val existing = dedupedMap[c.song.id]
                            if (existing == null || c.tasteScore > existing.tasteScore) {
                                dedupedMap[c.song.id] = c
                            }
                        }
                        val deduped = dedupedMap.values.toList()
                        candidatePoolSize = deduped.size

                        // Weighted scoring
                        val scored = deduped.map { cand ->
                            val base = cand.tasteScore
                            val weighted = when (cand.source) {
                                CandidateSource.RECENT -> base * 0.35 + 0.1
                                CandidateSource.FREQUENT -> base * 0.25 + 0.15
                                CandidateSource.LIKED -> base * 0.20 + 0.2
                                CandidateSource.FOLLOWED -> base * 0.10 + 0.3
                                CandidateSource.SEARCH -> base * 0.10
                                CandidateSource.EXPLORATION -> base * 0.08
                                CandidateSource.TRENDING -> base * 0.07
                                CandidateSource.RANDOM -> base * 0.05
                            }
                            val penalty = if (recentlyShownIds.contains(cand.song.id)) 0.45 else 0.0
                            cand to (weighted - penalty).coerceAtLeast(0.02)
                        }

                        val random = Random(System.nanoTime() xor fingerprint.hashCode().toLong() xor generationNonce.toLong())

                        // Seed selection using dynamic exploration ratio
                        val recentPool = scored.filter { it.first.source == CandidateSource.RECENT }.sortedByDescending { it.second }.take(15).map { it.first.song to it.second }
                        val frequentLikedPool = scored.filter { it.first.source == CandidateSource.LIKED || it.first.source == CandidateSource.FREQUENT }.sortedByDescending { it.second }.take(20).map { it.first.song to it.second }
                        val followedSearchPool = scored.filter { it.first.source == CandidateSource.FOLLOWED || it.first.source == CandidateSource.SEARCH }.map { it.first.song to it.second }
                        val explorationPool = scored.filter { it.first.source == CandidateSource.EXPLORATION || it.first.source == CandidateSource.TRENDING || it.first.source == CandidateSource.RANDOM }.map { it.first.song to it.second }

                        val seeds = mutableListOf<Song>()
                        val categories = mutableListOf<String>()

                        fun addSeed(pool: List<Pair<Song, Double>>, label: String, count: Int) {
                            val picked = weightedSampleWithoutReplacement(pool, count, random)
                            seeds.addAll(picked)
                            repeat(picked.size) { categories.add(label) }
                        }

                        // Dynamic seed allocation: exploration slots vs personalized slots
                        val totalSeedTarget = 5
                        val explorationSeedCount = (explorationRatio * totalSeedTarget).toInt().coerceIn(1, totalSeedTarget - 1)
                        val personalizedSeedCount = totalSeedTarget - explorationSeedCount

                        // Personalized seeds: recent first, then frequent/liked, then followed/search
                        val recentSeedCount = minOf(personalizedSeedCount, recentPool.size).coerceAtMost(3)
                        if (recentSeedCount > 0) addSeed(recentPool, "RECENT", recentSeedCount)

                        val remainingPersonalized = personalizedSeedCount - recentSeedCount
                        if (remainingPersonalized > 0) {
                            val personalPool = frequentLikedPool.ifEmpty { followedSearchPool }.ifEmpty { scored.map { it.first.song to it.second } }
                            addSeed(personalPool, "PERSONALIZED", remainingPersonalized)
                        }

                        // Exploration seeds
                        if (explorationSeedCount > 0) {
                            val explPool = explorationPool.ifEmpty { scored.take(10).map { it.first.song to 0.3 } }
                            addSeed(explPool, "EXPLORATION", explorationSeedCount)
                        }

                        // Filter out recently shown seeds (snapshot)
                        val lastGenIds = recentlyShownSnapshot.takeLast(40).toSet()
                        val filteredSeeds = seeds.filterNot { lastGenIds.contains(it.id) }
                        val finalSeeds = if (filteredSeeds.size >= 3) filteredSeeds else seeds

                        seedCategories = categories.take(finalSeeds.size)
                        val distinctSeeds = finalSeeds.distinctBy { it.id }.shuffled(random).take(5)
                        val resultSeeds = if (distinctSeeds.isNotEmpty()) distinctSeeds else {
                            val fb = mutableListOf<Song>()
                            fb.addAll(historyList.take(3))
                            if (fb.size < 3) fb.addAll(trendingList.take(3 - fb.size))
                            if (fb.isEmpty()) fb.addAll(getSeedSongsFlow(quickPicksSource, 3).first())
                            fb.distinctBy { it.id }.take(5)
                        }
                        seedSongs = resultSeeds
                        candidatePoolSize = deduped.size
                        seedCategories = seedCategories.take(seedSongs.size)
                    }
                }

                // === TASTE ANCHOR EXTRACTION ===
                val tasteAnchors = mutableListOf<TasteAnchor>()
                val recentHistory = db.history(limit = 15, minPlayTimeMs = MIN_HISTORY_PLAY_TIME_MS).first()
                val frequentHistory = db.history(limit = 50, minPlayTimeMs = MIN_HISTORY_PLAY_TIME_MS).first()
                    .sortedByDescending { it.totalPlayTimeMs }.take(15)
                val favoritesList = db.favorites().first()
                val followedArtists = db.followedArtists().first()
                val recentSearchQueries = db.queries("%").first().take(5)

                val recentArtists = recentHistory.mapNotNull { it.artistsText }
                    .flatMap { it.split(" • ").map(String::trim) }.filter { it.isNotBlank() }
                    .distinct().take(3)
                recentArtists.forEach { name ->
                    if (tasteAnchors.none { it.artistName.equals(name, ignoreCase = true) }) {
                        tasteAnchors.add(TasteAnchor(name, "RECENT", 1.00))
                    }
                }

                val frequentArtists = frequentHistory.mapNotNull { it.artistsText }
                    .flatMap { it.split(" • ").map(String::trim) }.filter { it.isNotBlank() }
                    .distinct().take(3)
                frequentArtists.forEach { name ->
                    if (tasteAnchors.none { it.artistName.equals(name, ignoreCase = true) }) {
                        tasteAnchors.add(TasteAnchor(name, "FREQUENT", 0.90))
                    }
                }

                favoritesList.mapNotNull { it.artistsText }
                    .flatMap { it.split(" • ").map(String::trim) }.filter { it.isNotBlank() }
                    .distinct().take(2).forEach { name ->
                        if (tasteAnchors.none { it.artistName.equals(name, ignoreCase = true) }) {
                            tasteAnchors.add(TasteAnchor(name, "LIKED", 0.90))
                        }
                    }

                followedArtists.mapNotNull { it.name }.take(2).forEach { name ->
                    if (tasteAnchors.none { it.artistName.equals(name, ignoreCase = true) }) {
                        tasteAnchors.add(TasteAnchor(name, "FOLLOWED", 0.85))
                    }
                }

                // Onboarding bootstrap anchors with weight decay (Event-based)
                val onboardingArtistsStored = appContext.preferences.getOnboardingArtists()
                val onboardingCompleted = appContext.preferences.isOnboardingCompleted()
                val onboardingSkipped = appContext.preferences.isOnboardingSkipped()
                val onboardingGenresStored = appContext.preferences.getOnboardingGenres()
                val onboardingErasStored = appContext.preferences.getOnboardingEras()
                val onboardingDiscoveryStored = appContext.preferences.getOnboardingDiscovery()
                val behaviorEventCount = db.getEventCount()
                val totalBehaviorPlayTimeMs = db.getTotalEventPlayTime()
                val latestEventTs = db.getMaxEventTimestamp() ?: 0L
                val rawOnboardingWeight = computeOnboardingWeight(behaviorEventCount)
                val onboardingWeight = if (onboardingSkipped || onboardingArtistsStored.isEmpty()) 0.0 else rawOnboardingWeight
                if (onboardingCompleted && !onboardingSkipped && onboardingArtistsStored.isNotEmpty()) {
                    onboardingArtistsStored.forEach { oa ->
                        if (tasteAnchors.none { it.artistName.equals(oa.name, ignoreCase = true) }) {
                            tasteAnchors.add(TasteAnchor(oa.name, "ONBOARDING", onboardingWeight))
                        }
                    }
                }
                // Sort by weight descending so decay naturally demotes onboarding, then distinct and take 5
                val sortedAnchors = tasteAnchors.sortedByDescending { it.weight }
                val finalAnchors = sortedAnchors.distinctBy { it.artistName.lowercase() }.take(5)
                val onboardingAnchorCount = finalAnchors.count { it.category == "ONBOARDING" }
                val behaviorAnchorCount = finalAnchors.size - onboardingAnchorCount
                Log.i(TAG, "V4 tasteAnchors=${finalAnchors.map { "${it.artistName}(${it.category})" }} onboardingWeight=$onboardingWeight")

                // === SEARCH-DRIVEN EXPANSION: search each anchor (isolated per-async, no shared mutation) ===
                val searchJobs = finalAnchors.map { anchor ->
                    async {
                        try {
                            val searchRes = Innertube.searchPage(
                                query = anchor.artistName,
                                params = Innertube.SearchFilter.Song.value,
                                fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
                            )?.getOrNull()
                            val items = searchRes?.items?.take(15) ?: emptyList()
                            val source = when (anchor.category) {
                                "RECENT" -> RecSource.ANCHOR_RECENT
                                "FREQUENT" -> RecSource.ANCHOR_FREQUENT
                                "LIKED" -> RecSource.ANCHOR_LIKED
                                "FOLLOWED" -> RecSource.ANCHOR_FOLLOWED
                                "ONBOARDING" -> RecSource.ONBOARDING
                                else -> RecSource.ANCHOR_SEARCH
                            }
                            val candidates = items.map { song -> RecommendationCandidate(song, source, anchor.artistName, source.weight, anchor.weight) }
                            ProviderResult(candidates, emptyMap())
                        } catch (_: Exception) {
                            ProviderResult(emptyList(), mapOf("SEARCH_FAILED_${anchor.artistName}" to 1))
                        }
                    }
                }
                val fallbackJobs = recentSearchQueries.map { q ->
                    async {
                        try {
                            val searchRes = Innertube.searchPage(
                                query = q.query,
                                params = Innertube.SearchFilter.Song.value,
                                fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
                            )?.getOrNull()
                            val items = searchRes?.items?.take(5) ?: emptyList()
                            val candidates = items.map { song -> RecommendationCandidate(song, RecSource.ANCHOR_SEARCH, q.query, RecSource.ANCHOR_SEARCH.weight, 0.3) }
                            ProviderResult(candidates, emptyMap())
                        } catch (_: Exception) {
                            ProviderResult(emptyList(), emptyMap())
                        }
                    }
                }
                val allProviderResults = (searchJobs + fallbackJobs).awaitAll()
                val searchCandidatesSnapshot = allProviderResults.flatMap { it.candidates }.toList()
                val providerFailuresSnapshot: Map<String, Int> = allProviderResults.flatMap { it.failures.entries }
                    .groupBy({ it.key }, { it.value })
                    .mapValues { it.value.sum() }

                val explorationCandidatesSnapshot: List<RecommendationCandidate> = buildList {
                    val chartSongs = chartsDeferred.await()
                    chartSongs.take(10).forEach { song ->
                        add(RecommendationCandidate(song, RecSource.EXPLORATION, "", RecSource.EXPLORATION.weight, 0.1))
                    }
                    val trendingSnapshot = trendingList.toList()
                    trendingSnapshot.take(10).forEach { s ->
                        val songItem = Innertube.SongItem(
                            info = Innertube.Info(name = s.title, endpoint = com.github.innertube.models.NavigationEndpoint.Endpoint.Watch(videoId = s.id)),
                            authors = s.artistsText?.let { listOf(Innertube.Info(name = it, endpoint = null)) },
                            album = null,
                            durationText = s.durationText,
                            thumbnail = s.thumbnailUrl?.let { com.github.innertube.models.Thumbnail(url = it, width = 480, height = 480) }
                        )
                        add(RecommendationCandidate(songItem, RecSource.EXPLORATION, "", RecSource.EXPLORATION.weight, 0.1))
                    }
                }.toList()

                val allCandidatesSnapshot: List<RecommendationCandidate> = (searchCandidatesSnapshot + explorationCandidatesSnapshot).toList()
                val providerCountsSnapshot: Map<String, Int> = mapOf(
                    "ARTIST" to 0,
                    "ALBUM" to 0,
                    "RELATED" to 0,
                    "SEARCH" to allCandidatesSnapshot.count { it.source != RecSource.EXPLORATION },
                    "EXPLORATION" to allCandidatesSnapshot.count { it.source == RecSource.EXPLORATION }
                )

                val dedupedSnapshot: Map<String, RecommendationCandidate> = buildMap {
                    val snap = allCandidatesSnapshot.toList()
                    snap.forEach { c ->
                        val key = c.song.key
                        val existing = get(key)
                        if (existing == null || c.sourceWeight > existing.sourceWeight) put(key, c)
                    }
                }
                candidatePoolSize = dedupedSnapshot.size
                val dedupedCandidates = dedupedSnapshot
                val dedupedValuesSnapshot = dedupedSnapshot.values.toList()
                Log.i(TAG, "V4 pool=$candidatePoolSize providers=$providerCountsSnapshot token=$myToken")

                // === QUOTA-BASED FINAL RANKING (Event-based) ===
                val meaningfulPlayCount = behaviorEventCount
                val personalizationStrength = computePersonalizationStrength(meaningfulPlayCount)
                var explorationRatio = computeExplorationRatio(personalizationStrength)
                // Discovery preference adjustment
                explorationRatio = when (onboardingDiscoveryStored) {
                    com.github.soundpod.utils.DiscoveryPreference.FAMILIAR -> explorationRatio - 0.10
                    com.github.soundpod.utils.DiscoveryPreference.EXPLORATORY -> explorationRatio + 0.10
                    else -> explorationRatio
                }.coerceIn(0.10, 0.70)

                val recentlyShown = recentlyShownSet
                val seedSongIds = seedSongs.map { it.id }.toSet()
                val lastPlayedIds = recentHistory.take(10).map { it.id }.toSet()
                val allSeedArtistNames = finalAnchors.map { it.artistName.lowercase() }.toSet()

                val scoredSnapshot = dedupedValuesSnapshot.map { cand ->
                    val isSeedSong = seedSongIds.contains(cand.song.key)
                    val isRecentlyPlayed = lastPlayedIds.contains(cand.song.key)
                    val isRecentlyShown = recentlyShown.contains(cand.song.key)
                    val isSeedArtist = cand.song.authors?.any { a ->
                        allSeedArtistNames.any { a.name?.lowercase()?.contains(it) == true }
                    } == true

                    val seedPenalty = if (isSeedSong) 1.0 else 0.0
                    val recentlyPlayedPenalty = if (isRecentlyPlayed) 0.8 else 0.0
                    val recentlyShownPenalty = if (isRecentlyShown) 0.5 else 0.0
                    val noveltyBonus = if (!isRecentlyPlayed && !isRecentlyShown) 0.15 else 0.0
                    val seedAffinity = if (isSeedArtist) 0.3 else 0.0

                    val finalScore = (cand.sourceWeight * 0.40 +
                        cand.personalizationAffinity * 0.25 +
                        cand.source.weight * 0.20 +
                        noveltyBonus +
                        seedAffinity -
                        recentlyShownPenalty -
                        recentlyPlayedPenalty -
                        seedPenalty).coerceAtLeast(0.01)

                    cand to finalScore
                }.sortedByDescending { it.second }.toList()

                // Quota: personalized vs exploration (immutable snapshots)
                val targetTotal = 20
                val targetExploration = (explorationRatio * targetTotal).toInt().coerceIn(2, 8)
                val targetPersonalized = targetTotal - targetExploration

                val personalizedPool = scoredSnapshot.filter { it.first.source != RecSource.EXPLORATION }.toList()
                val explorationPool = scoredSnapshot.filter { it.first.source == RecSource.EXPLORATION }.toList()

                // Artist cap: max 3 songs per artist
                val artistCounts = mutableMapOf<String, Int>()
                val selected = mutableListOf<Pair<RecommendationCandidate, Double>>()

                for ((cand, score) in personalizedPool) {
                    if (selected.size >= targetPersonalized) break
                    val artistKey = cand.song.authors?.firstOrNull()?.name ?: cand.song.key
                    val cnt = artistCounts[artistKey] ?: 0
                    if (cnt >= 3) continue
                    selected.add(cand to score)
                    artistCounts[artistKey] = cnt + 1
                }

                for ((cand, score) in explorationPool) {
                    if (selected.size >= targetTotal) break
                    val artistKey = cand.song.authors?.firstOrNull()?.name ?: cand.song.key
                    val cnt = artistCounts[artistKey] ?: 0
                    if (cnt >= 3) continue
                    selected.add(cand to score)
                    artistCounts[artistKey] = cnt + 1
                }

                // Band-shuffle within score tiers for diversity (immutable snapshot)
                val finalSongsSnapshot = selected.map { it.first.song }.chunked(8).map { band ->
                    band.shuffled(Random(generationNonce.toLong() + fingerprint.hashCode()))
                }.flatten().take(targetTotal).toList()

                val personalizedInFinal = finalSongsSnapshot.count { s ->
                    dedupedSnapshot[s.key]?.source != RecSource.EXPLORATION
                }
                val personalizedPercent = if (finalSongsSnapshot.isNotEmpty()) {
                    personalizedInFinal.toDouble() / finalSongsSnapshot.size * 100.0
                } else 0.0

                val finalSourceCounts = mutableMapOf<String, Int>()
                finalSongsSnapshot.forEach { song ->
                    val cand = dedupedSnapshot[song.key]
                    val src = cand?.source?.name ?: "UNKNOWN"
                    finalSourceCounts[src] = (finalSourceCounts[src] ?: 0) + 1
                }
                val finalSourceCountsSnapshot = finalSourceCounts.toMap()

                val mergedPage = if (finalSongsSnapshot.isNotEmpty()) {
                    Innertube.RelatedPage(
                        songs = finalSongsSnapshot,
                        playlists = emptyList(),
                        albums = emptyList(),
                        artists = emptyList()
                    )
                } else {
                    // Fallback to charts
                    val chartFallback = chartsDeferred.await().orEmpty()
                    if (chartFallback.isNotEmpty()) Innertube.RelatedPage(songs = chartFallback.take(40)) else null
                }

                val finalResult = if (mergedPage != null && !mergedPage.songs.isNullOrEmpty()) {
                    Result.success(mergedPage)
                } else {
                    Result.failure(Exception("Failed to load Quick Picks"))
                }
                Log.i(TAG, "Quick Picks final songs=${finalResult.getOrNull()?.songs?.size ?: 0} fp=$fingerprintShort")

                if (myToken != generationToken.get()) {
                    Log.d(TAG, "Stale token $myToken vs ${generationToken.get()} - discarding result before UI update")
                    return@coroutineScope
                }

                val debugForCache = DebugState(
                    fingerprint = fingerprint,
                    fingerprintShort = fingerprintShort,
                    generationNonce = generationNonce,
                    seedIds = seedSongs.map { it.id }.toList(),
                    seedCategories = seedCategories.toList(),
                    candidatePoolSize = candidatePoolSize,
                    recentlyShownCount = recentlyShownSnapshot.size,
                    finalResultCount = finalResult.getOrNull()?.songs?.size ?: 0,
                    isColdStart = isColdStart,
                    cacheHit = false,
                    generationSource = "GENERATED",
                    meaningfulPlayCount = meaningfulPlayCount,
                    personalizationStrength = computePersonalizationStrength(meaningfulPlayCount),
                    sourceCandidateCounts = sourceCounts.toMap(),
                    providerCounts = providerCountsSnapshot,
                    finalSourceCounts = finalSourceCountsSnapshot,
                    providerFailures = providerFailuresSnapshot,
                    tasteAnchors = finalAnchors.map { "${it.artistName}(${it.category})" },
                    personalizedPercent = personalizedPercent,
                    onboardingCompleted = onboardingCompleted,
                    onboardingSkipped = onboardingSkipped,
                    onboardingWeight = onboardingWeight,
                    onboardingGenres = onboardingGenresStored,
                    onboardingEras = onboardingErasStored,
                    onboardingDiscovery = onboardingDiscoveryStored.name,
                    onboardingAnchorCount = onboardingAnchorCount,
                    behaviorAnchorCount = behaviorAnchorCount,
                    totalBehaviorPlayTimeMs = totalBehaviorPlayTimeMs,
                    latestEventTs = latestEventTs
                )
                if (myToken == generationToken.get()) {
                    finalResult.getOrNull()?.let {
                        if (isScreenCacheEnabled) {
                            saveCachedWrapper(quickPicksSource, fingerprint, it, debugForCache)
                        }
                    }
                    val newList = (recentlyShownSnapshot + finalSongsSnapshot.map { it.key }.filter { it.isNotEmpty() }).distinct().takeLast(RECENTLY_SHOWN_MAX)
                    saveRecentlyShown(newList)
                    debugState = debugForCache.copy(cacheHit = false)
                    relatedPageResult = finalResult
                }
            }
                    } finally {
                        if (myToken == generationToken.get()) {
                            isRefreshing = false
                        }
                    }
                }
            }
        }
    }
}
