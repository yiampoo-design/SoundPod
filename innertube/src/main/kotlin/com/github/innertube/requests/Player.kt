package com.github.innertube.requests

import com.github.innertube.Innertube
import com.github.innertube.models.Context
import com.github.innertube.models.PlayerResponse
import com.github.innertube.models.YouTubeClient
import com.github.innertube.models.bodies.PlayerBody
import com.github.innertube.models.bodies.ServiceIntegrityDimensions
import com.github.innertube.utils.runCatchingNonCancellable
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import java.util.logging.Logger

private val playerLogger = Logger.getLogger("YiamTube-Innertube")

@Serializable
private data class AudioStream(
    val url: String,
    val bitrate: Long
)

@Serializable
private data class PipedResponse(
    val audioStreams: List<AudioStream>
)

suspend fun Innertube.player(videoId: String) = runCatchingNonCancellable {
    playerLogger.fine("player($videoId) starting with ANDROID_VR")
    val response = try {
        client.post(PLAYER) {
            setBody(
                PlayerBody(
                    context = YouTubeClient.ANDROID_VR.toContext(visitorData = visitorData),
                    videoId = videoId,
                    serviceIntegrityDimensions = poToken?.let { ServiceIntegrityDimensions(poToken = it) }
                )
            )
            mask("playabilityStatus.status,playerConfig.audioConfig,streamingData.adaptiveFormats,streamingData.formats,videoDetails.videoId")
        }.body<PlayerResponse>()
    } catch (e: Exception) {
        playerLogger.warning("player($videoId) ANDROID_VR request failed: ${e.javaClass.simpleName} ${e.message?.take(200)}")
        null
    }

    if (response != null) {
        val status = response.playabilityStatus?.status
        val hasStreamingData = response.streamingData != null
        val formatCount = (response.streamingData?.adaptiveFormats?.size ?: 0) +
                          (response.streamingData?.formats?.size ?: 0)
        playerLogger.fine("player($videoId) ANDROID_VR playability=$status streamingData=$hasStreamingData formats=$formatCount")
    }

    if (response != null && response.playabilityStatus?.status == "OK") {
        return@runCatchingNonCancellable response.applyDecipher(decipher)
    }
    else {
        playerLogger.fine("player($videoId) falling back to TVHTML5_SIMPLY_EMBEDDED_PLAYER")
        val safePlayerResponse = try {
            client.post(PLAYER) {
                setBody(
                    PlayerBody(
                        context = YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER.toContext(visitorData = visitorData).copy(
                            thirdParty = Context.ThirdParty(
                                embedUrl = "https://www.youtube.com/watch?v=$videoId"
                            )
                        ),
                        videoId = videoId
                    )
                )
                mask("playabilityStatus.status,playerConfig.audioConfig,streamingData.adaptiveFormats,streamingData.formats,videoDetails.videoId")
            }.body<PlayerResponse>()
        } catch (e: Exception) {
            playerLogger.warning("player($videoId) TVHTML5 request failed: ${e.javaClass.simpleName} ${e.message?.take(200)}")
            null
        }

        if (safePlayerResponse == null) {
            return@runCatchingNonCancellable response?.applyDecipher(decipher)
        }

        val safeStatus = safePlayerResponse.playabilityStatus?.status
        val safeHasStreamingData = safePlayerResponse.streamingData != null
        val safeFormatCount = (safePlayerResponse.streamingData?.adaptiveFormats?.size ?: 0) +
                              (safePlayerResponse.streamingData?.formats?.size ?: 0)
        playerLogger.fine("player($videoId) TVHTML5 playability=$safeStatus streamingData=$safeHasStreamingData formats=$safeFormatCount")

        if (safeStatus != "OK") {
            return@runCatchingNonCancellable response?.applyDecipher(decipher)
        }

        val audioStreams = runCatching {
            client.get("https://pipedapi.adminforge.de/streams/$videoId") {
                contentType(ContentType.Application.Json)
            }.body<PipedResponse>().audioStreams
        }.getOrNull() ?: emptyList()
        playerLogger.fine("player($videoId) Piped fallback streams=${audioStreams.size}")

        if (audioStreams.isEmpty()) {
            return@runCatchingNonCancellable safePlayerResponse.applyDecipher(decipher)
        }

        safePlayerResponse.copy(
            streamingData = safePlayerResponse.streamingData?.copy(
                adaptiveFormats = safePlayerResponse.streamingData.adaptiveFormats?.map { adaptiveFormat ->
                    adaptiveFormat.copy(
                        url = audioStreams.minByOrNull {
                            val bitrate = adaptiveFormat.bitrate ?: 0L
                            if (bitrate == 0L) Long.MAX_VALUE
                            else kotlin.math.abs(it.bitrate - bitrate)
                        }?.url
                    )
                },
                formats = safePlayerResponse.streamingData.formats?.map { format ->
                    format.copy(
                        url = audioStreams.minByOrNull {
                            val bitrate = format.bitrate ?: 0L
                            if (bitrate == 0L) Long.MAX_VALUE
                            else kotlin.math.abs(it.bitrate - bitrate)
                        }?.url
                    )
                }
            )
        ).applyDecipher(decipher)
    }
}

private suspend fun PlayerResponse.applyDecipher(decipher: (suspend (String) -> String)?): PlayerResponse {
    if (decipher == null || streamingData == null) return this
    
    return copy(
        streamingData = streamingData.copy(
            adaptiveFormats = streamingData.adaptiveFormats?.map { format ->
                format.copy(url = format.url?.let { decipherUrl(it, decipher) })
            },
            formats = streamingData.formats?.map { format ->
                format.copy(url = format.url?.let { decipherUrl(it, decipher) })
            }
        )
    )
}

private suspend fun decipherUrl(url: String, decipher: suspend (String) -> String): String {
    val nParam = url.substringAfter("&n=", "").substringBefore("&")
    if (nParam.isEmpty()) return url
    
    val decipheredN = decipher(nParam)
    return url.replace("&n=$nParam", "&n=$decipheredN")
}
