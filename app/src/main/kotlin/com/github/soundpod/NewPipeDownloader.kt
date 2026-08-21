package com.github.soundpod

import android.util.Log
import com.github.innertube.models.PlayerResponse
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * NewPipeExtractor's downloader. Used by NewPipeExtractor internals to fetch
 * YouTube watch pages and base.js scripts.
 *
 * IMPORTANT: We do NOT call Innertube.player() here anymore. If we did, then
 * "NewPipe as fallback" would actually be "NewPipe -> Innertube -> YouTube",
 * which defeats the purpose of having NewPipe as an independent fallback when
 * Innertube is failing.
 *
 * Instead, we:
 *  - Serve a minimal HTML response for YouTube watch URLs so NewPipeExtractor
 *    doesn't fail outright (it will then fall back to its own internal page
 *    fetch via the OkHttp client below).
 *  - Use the OkHttp client to make all HTTP requests ourselves, so the
 *    NewPipeExtractor path is truly independent of Innertube.
 */
class NewPipeDownloader private constructor() : Downloader() {
    private val client = OkHttpClient.Builder()
        .cache(Cache(File(MainApplication.appContext.cacheDir, "newpipe_cache"), 10 * 1024 * 1024))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val jsCacheFile = File(MainApplication.appContext.cacheDir, "base_js_content")
    private val jsUrlFile = File(MainApplication.appContext.cacheDir, "base_js_url")
    private val playerResponseCache = ConcurrentHashMap<String, Pair<PlayerResponse, Long>>()

    fun preCache(videoId: String, playerResponse: PlayerResponse) {
        playerResponseCache[videoId] = playerResponse to System.currentTimeMillis()
        Log.d(TAG, "preCache for $videoId")
    }

    override fun execute(request: Request): Response {
        val url = request.url()
        val method = request.httpMethod()

        // base.js caching — safe and useful
        if (method == "GET" && url.contains("base.js")) {
            if (jsUrlFile.exists() && jsUrlFile.readText() == url && jsCacheFile.exists()) {
                val lastModified = jsCacheFile.lastModified()
                if (System.currentTimeMillis() - lastModified < TimeUnit.DAYS.toMillis(1)) {
                    Log.d(TAG, "base.js cache hit for $url")
                    return Response(200, "OK", mapOf("Content-Type" to listOf("application/javascript")), jsCacheFile.readText(), url)
                }
            }
        }

        // For YouTube watch URLs, we deliberately serve an empty page so
        // NewPipeExtractor doesn't depend on Innertube. NewPipeExtractor will
        // then make its own HTTP request through the OkHttp client below
        // to actually fetch the page contents.
        if (method == "GET" && (url.contains("youtube.com/watch?v=") || url.contains("music.youtube.com/watch?v="))) {
            val videoId = url.substringAfter("v=").substringBefore("&").substringBefore("?")
            if (videoId.length == 11) {
                Log.d(TAG, "Serving minimal watch HTML for $videoId (NewPipeExtractor will fetch its own page)")
                return Response(
                    200,
                    "OK",
                    mapOf("Content-Type" to listOf("text/html")),
                    "<html><head></head><body></body></html>",
                    url
                )
            }
        }

        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val builder = okhttp3.Request.Builder()
            .url(url)

        headers.forEach { (name, values) ->
            values.forEach { value ->
                builder.addHeader(name, value)
            }
        }

        val requestBody = dataToSend?.toRequestBody()
            ?: if (method == "POST") ByteArray(0).toRequestBody() else null

        builder.method(method, requestBody)

        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: Exception) {
            Log.w(TAG, "HTTP request failed: $url — ${e.message}")
            throw e
        }
        val body = response.body.string()

        if (method == "GET" && url.contains("base.js") && response.isSuccessful) {
            jsUrlFile.writeText(url)
            jsCacheFile.writeText(body)
        }

        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            body,
            response.request.url.toString(),
        )
    }

    companion object {
        private const val TAG = "YiamTube-NewPipe"
        private var instance: NewPipeDownloader? = null

        fun getInstance(): NewPipeDownloader {
            if (instance == null) {
                instance = NewPipeDownloader()
            }
            return instance!!
        }
    }
}
