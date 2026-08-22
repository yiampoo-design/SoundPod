package com.github.soundpod.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val onboardingCompletedKey = "onboardingCompleted"
const val onboardingSkippedKey = "onboardingSkipped"
const val onboardingVersionKey = "onboardingVersion"
const val onboardingGenresKey = "onboardingGenres"
const val onboardingArtistsKey = "onboardingArtists"
const val onboardingErasKey = "onboardingEras"
const val onboardingDiscoveryKey = "onboardingDiscovery"

const val ONBOARDING_VERSION = 1

@Serializable
data class OnboardingArtist(
    val name: String,
    val browseId: String? = null
)

enum class DiscoveryPreference {
    FAMILIAR, BALANCED, EXPLORATORY
}

private val onboardingJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun SharedPreferences.isOnboardingCompleted(): Boolean = getBoolean(onboardingCompletedKey, false)
fun SharedPreferences.isOnboardingSkipped(): Boolean = getBoolean(onboardingSkippedKey, false)
fun SharedPreferences.getOnboardingVersion(): Int = getInt(onboardingVersionKey, 0)

fun SharedPreferences.getOnboardingGenres(): List<String> {
    val raw = getString(onboardingGenresKey, null) ?: return emptyList()
    return try { onboardingJson.decodeFromString<List<String>>(raw) } catch (_: Exception) { raw.split(",").map { it.trim() }.filter { it.isNotEmpty() } }
}

fun SharedPreferences.getOnboardingEras(): List<String> {
    val raw = getString(onboardingErasKey, null) ?: return emptyList()
    return try { onboardingJson.decodeFromString<List<String>>(raw) } catch (_: Exception) { raw.split(",").map { it.trim() }.filter { it.isNotEmpty() } }
}

fun SharedPreferences.getOnboardingArtists(): List<OnboardingArtist> {
    val raw = getString(onboardingArtistsKey, null) ?: return emptyList()
    return try { onboardingJson.decodeFromString<List<OnboardingArtist>>(raw) } catch (_: Exception) { emptyList() }
}

fun SharedPreferences.getOnboardingDiscovery(): DiscoveryPreference {
    val raw = getString(onboardingDiscoveryKey, null) ?: return DiscoveryPreference.BALANCED
    return try { DiscoveryPreference.valueOf(raw) } catch (_: Exception) { DiscoveryPreference.BALANCED }
}

fun SharedPreferences.Editor.putOnboardingGenres(genres: List<String>) {
    putString(onboardingGenresKey, onboardingJson.encodeToString(genres))
}
fun SharedPreferences.Editor.putOnboardingEras(eras: List<String>) {
    putString(onboardingErasKey, onboardingJson.encodeToString(eras))
}
fun SharedPreferences.Editor.putOnboardingArtists(artists: List<OnboardingArtist>) {
    putString(onboardingArtistsKey, onboardingJson.encodeToString(artists))
}
fun SharedPreferences.Editor.putOnboardingDiscovery(pref: DiscoveryPreference) {
    putString(onboardingDiscoveryKey, pref.name)
}

fun Context.completeOnboarding(
    genres: List<String>,
    artists: List<OnboardingArtist>,
    eras: List<String>,
    discovery: DiscoveryPreference
) {
    preferences.edit {
        putBoolean(onboardingCompletedKey, true)
        putBoolean(onboardingSkippedKey, false)
        putInt(onboardingVersionKey, ONBOARDING_VERSION)
        putOnboardingGenres(genres)
        putOnboardingArtists(artists)
        putOnboardingEras(eras)
        putOnboardingDiscovery(discovery)
    }
}

fun Context.skipOnboarding() {
    preferences.edit {
        putBoolean(onboardingCompletedKey, true)
        putBoolean(onboardingSkippedKey, true)
        putInt(onboardingVersionKey, ONBOARDING_VERSION)
    }
}

fun computeOnboardingWeight(meaningfulPlayCount: Int): Double {
    return when {
        meaningfulPlayCount <= 0 -> 0.80
        meaningfulPlayCount == 1 -> 0.75
        meaningfulPlayCount == 2 -> 0.675
        meaningfulPlayCount == 3 -> 0.60
        meaningfulPlayCount == 4 -> 0.525
        meaningfulPlayCount == 5 -> 0.45
        meaningfulPlayCount in 6..10 -> {
            // 0.45 -> 0.20 over 5 steps
            0.45 - (meaningfulPlayCount - 5) * 0.05
        }
        meaningfulPlayCount in 11..20 -> {
            // 0.20 -> 0.10 over 10 steps
            0.20 - (meaningfulPlayCount - 10) * 0.01
        }
        else -> 0.10
    }.coerceIn(0.10, 0.80)
}
