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
        val visitorData: String? = null
        // signatureTimestamp was removed: hardcoding it caused YouTube to
        // return 400 "Request contains an invalid argument" once YouTube
        // rotated the timestamp value. The proper place to set it is inside
        // the player response post-processing (decipher), not the request
        // body for browse/search.
    )

    @Serializable
    data class ThirdParty(
        val embedUrl: String
    )
}