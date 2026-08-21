import re

path = r'C:/Users/Yiam/.minimax/workspace/SoundPod/innertube/src/main/kotlin/com/github/innertube/Innertube.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace the defaultRequest block
old_default = '''        defaultRequest {
            url(scheme = "https", host ="music.youtube.com") {
                contentType(ContentType.Application.Json)
                headers.append("X-Goog-Api-Key", "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8")
                parameters.append("prettyPrint", "false")
            }
        }
    }'''

new_default = '''        defaultRequest {
            // The default request no longer carries a hard-coded X-Goog-Api-Key.
            // Per-endpoint helpers below (applyYouTubeMusicClient) attach
            // client-specific headers (X-YouTube-Client-Name, etc.) and
            // optionally the X-Goog-Visitor-Id when visitorData is present.
            // This matches the behaviour of currently maintained YouTube
            // Music clients for guest browse/search.
            url(scheme = "https", host ="music.youtube.com") {
                contentType(ContentType.Application.Json)
                parameters.append("prettyPrint", "false")
            }
        }
    }

    /**
     * Apply the full set of headers used by current maintained YouTube
     * Music clients (Metrolist-style) for guest browse/search/next calls.
     *
     * IMPORTANT:
     * - X-YouTube-Client-Name carries the *numeric* client id (e.g. "67"),
     *   NOT the string client name.
     * - Do NOT include signatureTimestamp in browse/search context.
     * - X-Goog-Visitor-Id is only sent when visitorData is non-null.
     * - No authentication/cookie headers for guest requests.
     */
    fun HttpRequestBuilder.applyYouTubeMusicClient(
        client: YouTubeClient,
        visitorData: String? = null
    ) {
        contentType(ContentType.Application.Json)
        val numericClientId = client.clientId ?: when (client) {
            YouTubeClient.IOS -> "5"
            YouTubeClient.ANDROID -> "3"
            YouTubeClient.ANDROID_MUSIC -> "21"
            YouTubeClient.MWEB -> "2"
            else -> "67"
        }
        header("X-Goog-Api-Format-Version", "1")
        header("X-YouTube-Client-Name", numericClientId)
        header("X-YouTube-Client-Version", client.clientVersion)
        header("Origin", "https://music.youtube.com")
        header("X-Origin", "https://music.youtube.com")
        header("Referer", "https://music.youtube.com/")
        header("User-Agent", client.userAgent)
        if (!visitorData.isNullOrBlank()) {
            header("X-Goog-Visitor-Id", visitorData)
        }
        parameters.append("prettyPrint", "false")
    }'''

if old_default in content:
    content = content.replace(old_default, new_default)
    print('Patched defaultRequest + added applyYouTubeMusicClient helper')
else:
    print('ERROR: old defaultRequest block not found')
    raise SystemExit(1)

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
print('Written Innertube.kt')
