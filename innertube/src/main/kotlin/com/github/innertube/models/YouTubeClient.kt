package com.github.innertube.models

import java.util.Locale

@Suppress("SpellCheckingInspection")
enum class YouTubeClient(
    val clientName: String,
    val clientVersion: String,
    val userAgent: String,
    val platform: String? = null,
    val osVersion: String? = null,
    val clientId: String? = null,
    // New fields required by YouTube's anti-bot check.
    // - useSignatureTimestamp: include "signatureTimestamp" in the client context
    // - useWebPoTokens: requires a Web PO token from BotGuard for some endpoints
    // - requirePoToken: refuses to make requests without a valid PO token
    // - includeUserAgentInContext: include the User-Agent string in the context body
    val useSignatureTimestamp: Boolean = false,
    val useWebPoTokens: Boolean = false,
    val requirePoToken: Boolean = false,
    val loginSupported: Boolean = false,
    val includeUserAgentInContext: Boolean = false,
    val isEmbedded: Boolean = false,
) {
    WEB_REMIX(
        clientName = "WEB_REMIX",
        clientVersion = "1.20260114.03.00",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
        platform = "DESKTOP",
        clientId = "67",
        loginSupported = true,
        useSignatureTimestamp = true,
        useWebPoTokens = true,
    ),
    IOS(
        clientName = "IOS",
        clientVersion = "21.03.1",
        userAgent = "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)",
        osVersion = "18.2.22C152"
    ),
    MWEB(
        clientName = "MWEB",
        clientVersion = "2.20260114.08.00",
        userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Mobile Safari/537.36",
        clientId = "2"
    ),
    ANDROID(
        clientName = "ANDROID",
        clientVersion = "21.03.38",
        userAgent = "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip",
        osVersion = "14",
        clientId = "3",
        useSignatureTimestamp = true
    ),
    ANDROID_MUSIC(
        clientName = "ANDROID_MUSIC",
        clientVersion = "7.27.51",
        userAgent = "com.google.android.apps.youtube.music/7.27.51 (Linux; U; Android 14; en_US; SM-S928B Build/UP1A.231005.007)",
        osVersion = "14",
        clientId = "21"
    ),
    ANDROID_TESTSUITE(
        clientName = "ANDROID_TESTSUITE",
        clientVersion = "1.9.31",
        userAgent = "com.google.android.youtube.testsuite/1.9.31 (Linux; U; Android 14; en_US; SM-S928B Build/UP1A.231005.007)",
        osVersion = "14",
        clientId = "3"
    ),
    TVHTML5_SIMPLY_EMBEDDED_PLAYER(
        clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        clientVersion = "2.0",
        userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
        platform = "TV",
        clientId = "85",
        loginSupported = true,
        useSignatureTimestamp = true,
        isEmbedded = true
    ),
    ANDROID_VR(
        clientName = "ANDROID_VR",
        clientVersion = "1.61.48",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Oculus Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
        osVersion = "12",
        clientId = "28",
        // ANDROID_VR returns stream URLs that need a signature timestamp to
        // be playable. Without it, the URLs come back unsigned or with a
        // signature that expires immediately. The other playback clients
        // (ANDROID, TVHTML5, WEB_REMIX) already include it.
        useSignatureTimestamp = true,
        includeUserAgentInContext = true
    ),
    MAC_SAFARI_WEB_REMIX(
        clientName = "WEB_REMIX",
        clientVersion = "1.20260114.03.00",
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
        platform = "DESKTOP",
        osVersion = "10_15_7",
        clientId = "67",
        loginSupported = true,
        useSignatureTimestamp = true,
        useWebPoTokens = true
    );

    fun toContext(
        localized: Boolean = true,
        visitorData: String? = null,
        gl: String? = null,
        hl: String? = null
    ) = Context(
        thirdParty = if (isEmbedded) Context.ThirdParty(embedUrl = "https://www.google.com/") else null,
        client = Context.Client(
            clientName = clientName,
            clientVersion = clientVersion,
            clientId = clientId ?: when (this) {
                IOS -> "5"
                ANDROID -> "3"
                ANDROID_MUSIC -> "21"
                MWEB -> "2"
                else -> "67"
            },
            osVersion = osVersion ?: when (this) {
                ANDROID, ANDROID_MUSIC, ANDROID_TESTSUITE -> "14"
                IOS -> "18.2"
                MWEB -> "14"
                else -> "13"
            },
            platform = platform ?: when (this) {
                IOS -> "MOBILE"
                ANDROID -> "MOBILE"
                ANDROID_MUSIC -> "MOBILE"
                ANDROID_VR -> "MOBILE"
                MWEB -> "MOBILE"
                TVHTML5_SIMPLY_EMBEDDED_PLAYER -> "TV"
                else -> "DESKTOP"
            },
            userAgent = userAgent,
            gl = gl ?: if (localized) Locale.getDefault().country.takeIf { it.length == 2 } ?: "US" else "US",
            hl = hl ?: if (localized) Locale.getDefault().language.ifBlank { "en" } else "en",
            visitorData = visitorData ?: "",
            // YouTube's anti-bot check verifies this timestamp. Must be a value
            // that YouTube has publicly signed — 20039 is the well-known
            // value used by all open-source YouTube clients.
            signatureTimestamp = if (useSignatureTimestamp) SIGNATURE_TIMESTAMP else null
        )
    )

    companion object {
        // YouTube's known signature timestamp — must be a value YouTube has signed.
        // This is the standard value used by all open-source YouTube clients.
        const val SIGNATURE_TIMESTAMP: Long = 20039
    }
}
