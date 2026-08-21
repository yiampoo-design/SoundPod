package com.github.soundpod.service

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import com.github.innertube.Innertube
import com.github.innertube.requests.player
import com.github.soundpod.utils.pauseSongCacheKey
import com.github.soundpod.utils.preferences
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.ServiceList
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Multi-provider playback resolver.
 *
 * Provider order:
 *  1. Innertube (ANDROID_VR primary, then TVHTML5_SIMPLY_EMBEDDED_PLAYER fallback)
 *     — controlled by Innertube.player() in innertube/.../Player.kt
 *  2. NewPipeExtractor (truly independent — does its own HTTP via NewPipeDownloader)
 *
 * Each cache entry records which provider generated it so that on a 403 the
 * resolver can:
 *   - Evict the failing URL
 *   - Mark that provider as "failed for this attempt" so the next call
 *     doesn't re-use the same cached URL
 *   - Try the next provider
 *
 * Sanitised diagnostic logging under YiamTube-Resolver, YiamTube-Innertube,
 * YiamTube-NewPipe and YiamTube-DataSource tags.
 */
/**
 * Cache entry: URL + the provider that produced it + the timestamp.
 */
private data class CacheEntry(val uri: Uri, val provider: String, val timestamp: Long)

@UnstableApi
class PlayerMediaSourceProvider(
    private val context: Context,
    private val cacheManager: PlayerCacheManager
) {
    private val urlCache = ConcurrentHashMap<String, CacheEntry>()
    private val failedProvidersForAttempt = ConcurrentHashMap<String, MutableSet<String>>()
    private val resolutionLocks = ConcurrentHashMap<String, ReentrantLock>()

    fun injectUrl(videoId: String, uri: Uri, provider: String = "manual") {
        urlCache[videoId] = CacheEntry(uri, provider, System.currentTimeMillis())
        Log.d(TAG_DATA, "injectUrl($videoId) provider=$provider")
    }

    companion object {
        private const val TAG_RESOLVER = "YiamTube-Resolver"
        private const val TAG_INNERTUBE = "YiamTube-Innertube"
        private const val TAG_NEWPIPE = "YiamTube-NewPipe"
        private const val TAG_DATA = "YiamTube-DataSource"
        private const val CACHE_EXPIRATION_MS = 4 * 3600000L
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // Provider identifiers
        private const val PROVIDER_INNERTUBE = "innertube"
        private const val PROVIDER_NEWPIPE = "newpipe"
    }

    fun createMediaSourceFactory(): MediaSource.Factory {
        return DefaultMediaSourceFactory(createDataSourceFactory(), DefaultExtractorsFactory())
            .setLoadErrorHandlingPolicy(YouTube403ErrorPolicy(urlCache, failedProvidersForAttempt))
    }

    private fun createDataSourceFactory(): DataSource.Factory {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(30000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(DEFAULT_USER_AGENT)

        val upstreamFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)

        val resolvingUpstreamFactory = ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
            val videoId = dataSpec.key ?: throw java.io.IOException("A key must be set")
            Log.d(TAG_DATA, "Resolving URI for key=$videoId")
            if (videoId.startsWith("http") || videoId.startsWith("content://") || videoId.startsWith("file://")) {
                dataSpec
            } else {
                val uri = resolveUrl(videoId)
                dataSpec.withUri(uri)
            }
        }

        return DataSource.Factory {
            val pauseSongCache = context.preferences.getBoolean(pauseSongCacheKey, false)

            val cacheDataSource = CacheDataSource.Factory()
                .setCache(cacheManager.cache)
                .setUpstreamDataSourceFactory(resolvingUpstreamFactory)
                .apply {
                    if (pauseSongCache) {
                        setCacheWriteDataSinkFactory(null)
                    } else {
                        setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cacheManager.cache))
                    }
                }
                .createDataSource()

            cacheDataSource
        }
    }

    fun resolveUrl(videoId: String): Uri {
        if (videoId.startsWith("http") || videoId.startsWith("content://") || videoId.startsWith("file://")) {
            return videoId.toUri()
        }

        urlCache[videoId]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < CACHE_EXPIRATION_MS) {
                Log.d(TAG_DATA, "URL cache hit for $videoId provider=${entry.provider}")
                return entry.uri
            }
        }

        val lock = resolutionLocks.getOrPut(videoId) { ReentrantLock() }
        return lock.withLock {
            urlCache[videoId]?.let { entry ->
                if (System.currentTimeMillis() - entry.timestamp < CACHE_EXPIRATION_MS) {
                    return@withLock entry.uri
                }
            }

            // Reset the failed-providers set for this attempt
            val failed = failedProvidersForAttempt.getOrPut(videoId) { mutableSetOf() }
            failed.clear()

            // 1) Try Innertube
            if (PROVIDER_INNERTUBE !in failed) {
                val innertubeUri = tryInnertube(videoId)
                if (innertubeUri != null) {
                    urlCache[videoId] = CacheEntry(innertubeUri, PROVIDER_INNERTUBE, System.currentTimeMillis())
                    return@withLock innertubeUri
                }
                failed.add(PROVIDER_INNERTUBE)
            }

            // 2) Try NewPipeExtractor (truly independent)
            if (PROVIDER_NEWPIPE !in failed) {
                val newpipeUri = tryNewPipe(videoId)
                if (newpipeUri != null) {
                    urlCache[videoId] = CacheEntry(newpipeUri, PROVIDER_NEWPIPE, System.currentTimeMillis())
                    return@withLock newpipeUri
                }
                failed.add(PROVIDER_NEWPIPE)
            }

            // Both providers failed. Throw so Media3 surfaces the error to the user.
            val msg = "No playable URL found for $videoId (Innertube + NewPipe both failed)"
            Log.e(TAG_RESOLVER, msg)
            throw java.io.IOException(msg)
        }
    }

    private fun tryInnertube(videoId: String): Uri? {
        return try {
            val response = runBlocking { Innertube.player(videoId) }?.getOrNull()
            if (response == null) {
                Log.w(TAG_INNERTUBE, "player($videoId) returned null")
                return null
            }
            val status = response.playabilityStatus?.status
            val hasStreamingData = response.streamingData != null
            val formatCount = (response.streamingData?.adaptiveFormats?.size ?: 0) +
                              (response.streamingData?.formats?.size ?: 0)
            Log.d(TAG_INNERTUBE, "player($videoId) playability=$status streamingData=$hasStreamingData formats=$formatCount")

            val uri = response.streamingData?.highestQualityFormat?.url?.toUri()
            if (uri == null) {
                Log.w(TAG_INNERTUBE, "player($videoId) no URL in streamingData")
                return null
            }
            Log.d(TAG_RESOLVER, "Resolved $videoId via Innertube")
            uri
        } catch (e: Exception) {
            Log.w(TAG_INNERTUBE, "player($videoId) failed: ${e.message?.take(200)}")
            null
        }
    }

    private fun tryNewPipe(videoId: String): Uri? {
        return try {
            val streamExtractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            streamExtractor.fetchPage()

            val playability = streamExtractor.streamType.name
            val audioStreams = streamExtractor.audioStreams
            val videoStreams = streamExtractor.videoStreams

            Log.d(TAG_NEWPIPE, "fetchPage($videoId) streamType=$playability audio=${audioStreams.size} video=${videoStreams.size}")

            val bestAudio = audioStreams
                .filter { it.codec?.lowercase(Locale.ROOT) == "opus" }
                .maxByOrNull { it.averageBitrate }
                ?: audioStreams.maxByOrNull { it.averageBitrate }
                ?: videoStreams.maxByOrNull { it.bitrate }

            if (bestAudio == null) {
                Log.w(TAG_NEWPIPE, "fetchPage($videoId) no playable streams")
                return null
            }
            val uri = bestAudio.content.toUri()
            val codec = (bestAudio as? org.schabi.newpipe.extractor.stream.AudioStream)?.codec
            val bitrate = (bestAudio as? org.schabi.newpipe.extractor.stream.AudioStream)?.averageBitrate
                ?: (bestAudio as? org.schabi.newpipe.extractor.stream.VideoStream)?.bitrate
            Log.d(TAG_RESOLVER, "Resolved $videoId via NewPipe (codec=$codec bitrate=$bitrate)")
            uri
        } catch (e: Exception) {
            Log.w(TAG_NEWPIPE, "fetchPage($videoId) failed: ${e.message?.take(200)}")
            null
        }
    }
}

@UnstableApi
private class YouTube403ErrorPolicy(
    private val urlCache: ConcurrentHashMap<String, CacheEntry>,
    private val failedProvidersForAttempt: ConcurrentHashMap<String, MutableSet<String>>
) : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val exception = loadErrorInfo.exception
        if (exception is HttpDataSource.InvalidResponseCodeException && exception.responseCode == 403) {
            val videoId = loadErrorInfo.loadEventInfo.dataSpec.key
            val evictedEntry = if (videoId != null) urlCache.remove(videoId) else { urlCache.clear(); null }
            if (evictedEntry != null) {
                val failed = failedProvidersForAttempt.getOrPut(videoId!!) { mutableSetOf() }
                failed.add(evictedEntry.provider)
                android.util.Log.w(
                    "YiamTube-Resolver",
                    "403 for $videoId — evicting ${evictedEntry.provider} URL, will try next provider on next resolve"
                )
            } else {
                android.util.Log.w("YiamTube-Resolver", "403 for ${videoId ?: "<unknown>"} — no cached entry to evict")
            }
            return 1000L
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
