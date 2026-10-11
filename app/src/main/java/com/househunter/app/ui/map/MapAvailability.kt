package com.househunter.app.ui.map

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Decides whether the Google map can be shown, before anything risky is composed.
 *
 * The map used to crash the app outright. Two failure modes are checked here:
 *  - **No API key**: the Maps SDK throws when the `com.google.android.geo.API_KEY`
 *    meta-data is blank (a build without the secret), which took the whole app down.
 *  - **No/broken Play services**: the SDK fails to initialise on devices without it
 *    (or while it is updating), which also crashed.
 *
 * Returning false routes the UI to a non-crashing fallback, so a missing key degrades
 * to "no map" instead of an unlaunchable app.
 */
object MapAvailability {

    const val KEY_BLANK: String = "no-key"
    const val NO_PLAY_SERVICES: String = "no-play-services"

    /** True when a map is configured and Google Play services can service it. */
    fun isUsable(context: Context, apiKey: String?): Boolean = reason(context, apiKey) == null

    /** Null when usable, otherwise a short machine-readable reason. */
    fun reason(context: Context, apiKey: String?): String? {
        if (apiKey.isNullOrBlank()) return KEY_BLANK
        return runCatching {
            val status = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context)
            if (status == ConnectionResult.SUCCESS) null else NO_PLAY_SERVICES
        }.getOrElse { NO_PLAY_SERVICES }
    }

    /** Human-readable explanation for the fallback panel. */
    fun message(reason: String?): String = when (reason) {
        NO_PLAY_SERVICES ->
            "Google Play services are unavailable on this device, so the map is disabled. " +
                "Search still works and distances are still computed."
        else ->
            "No Google Maps API key is configured in this build, so the map is disabled. " +
                "Search still works and distances are still computed."
    }
}