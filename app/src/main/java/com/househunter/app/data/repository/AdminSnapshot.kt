package com.househunter.app.data.repository

import android.content.Context
import com.househunter.app.domain.model.OsmPlace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bundled OSM-derived snapshot of India's first-level administrative divisions
 * (admin_level=4). Used **only** when the Overpass API is unreachable, so the
 * State dropdown always opens. This is reference data extracted from OpenStreetMap
 * - it is not listing content and is never a substitute for live results.
 */
@Singleton
class AdminSnapshot @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    @Volatile
    private var cached: List<OsmPlace>? = null

    suspend fun states(): List<OsmPlace> {
        cached?.let { return it }
        return withContext(io) {
            cached ?: load().also { cached = it }
        }
    }

    /** Snapshot holds no district hierarchy - districts always come from Overpass. */
    fun districts(@Suppress("UNUSED_PARAMETER") stateId: Long): List<OsmPlace> = emptyList()

    private fun load(): List<OsmPlace> = runCatching {
        val json = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val array = JSONObject(json).optJSONArray("states") ?: return emptyList()
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optLong("id")
            val name = item.optString("name")
            if (id == 0L || name.isBlank()) return@mapNotNull null
            OsmPlace(id, name, item.optInt("adminLevel", 4))
        }.sortedBy { it.name }
    }.getOrDefault(emptyList())

    private companion object {
        const val ASSET = "admin_states.json"
    }
}