package com.github.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class Context(
    val client: Client,
    val thirdParty: ThirdParty? = null
) {
    @Serializable
    data class Client(
        val clientName: String,
        val clientVersion: String,
        val clientId: String? = null,
        val osVersion: String? = null,
        val platform: String? = null,
        val userAgent: String,
        val gl: String? = null,
        val hl: String? = null,
        val visitorData: String? = null,
        // YouTube's signature timestamp — required by WEB_REMIX and TVHTML5 clients
        // to prove the request originates from a real browser. Without this, YouTube
        // returns 403 Forbidden or removes streamingData from the response.
        val signatureTimestamp: Long? = null
    )

    @Serializable
    data class ThirdParty(
        val embedUrl: String
    )
}