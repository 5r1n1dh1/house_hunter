package com.househunter.app.data.repository

import android.content.Context
import com.househunter.app.domain.model.OsmPlace
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bundled OSM-derived snapshot of India's administrative divisions
 * (admin_level 4 states and their 5/6/7 children). Used **only** when the Overpass
 * API is unreachable, so the State and District dropdowns always open. This is
 * reference data extracted from OpenStreetMap - it is not listing content and is
 * never a substitute for live listing results.
 */
@Singleton
class AdminSnapshot @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private class StateEntry(
        val id: Long,
        val name: String,
        val districts: List<OsmPlace>,
    )

    @Volatile
    private var cached: Map<Long, StateEntry>? = null

    /** Bundled state list (used when Overpass is unreachable). */
    suspend fun states(): List<OsmPlace> = load().values
        .map { OsmPlace(it.id, it.name, 4) }
        .sortedBy { it.name }

    /** Bundled districts of [stateId]. */
    suspend fun districts(stateId: Long): List<OsmPlace> =
        load()[stateId]?.districts.orEmpty()

    private fun load(): Map<Long, StateEntry> {
        cached?.let { return it }
        return runCatching {
            val json = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            val array = JSONObject(json).optJSONArray("states") ?: return emptyMap()
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optLong("id")
                val name = item.optString("name")
                if (id == 0L || name.isBlank()) return@mapNotNull null
                val districtsJson = item.optJSONArray("districts")
                val districts = buildList {
                    for (i in 0 until (districtsJson?.length() ?: 0)) {
                        val d = districtsJson.optJSONObject(i) ?: continue
                        val did = d.optLong("id")
                        val dname = d.optString("name")
                        if (did != 0L && dname.isNotBlank()) {
                            add(OsmPlace(did, dname, d.optInt("adminLevel", 6)))
                        }
                    }
                }
                id to StateEntry(id, name, districts)
            }.toMap()
        }.getOrDefault(emptyMap()).also { cached = it }
    }

    private companion object {
        const val ASSET = "admin_states.json"
    }
}