package com.househunter.app.data.repository

import com.househunter.app.data.remote.NominatimApi
import com.househunter.app.data.remote.OverpassApi
import com.househunter.app.di.IoDispatcher
import com.househunter.app.domain.model.OsmPlace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cascading State -> District -> Locality dropdowns backed by OpenStreetMap
 * Overpass.
 *
 * Robustness notes (learned the hard way):
 *  - Overpass answers **406** unless an identifying User-Agent is sent (see
 *    [com.househunter.app.di.NetworkModule]).
 *  - Public Overpass instances time out (504) frequently, so every query is
 *    retried against a second mirror.
 *  - Containment queries (`relation(area.x)`) are expensive; the cheapest
 *    formulation is used for each level.
 *  - A bundled OSM-derived snapshot ([AdminSnapshot]) is the last-resort
 *    fallback so the dropdowns always open, even fully offline.
 */
@Singleton
class LocationRepository @Inject constructor(
    private val overpassApis: Set<@JvmSuppressWildcards OverpassApi>,
    private val adminSnapshot: AdminSnapshot,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val statesMemo = mutableListOf<OsmPlace>()

    /** "live" when the last state query came from Overpass, "snapshot" when bundled. */
    @Volatile
    var lastSource: String = "live"
        private set

    suspend fun states(forceReload: Boolean = false): List<OsmPlace> = withContext(io) {
        if (statesMemo.isNotEmpty() && !forceReload) return@withContext statesMemo

        val live = query(
            """
            [out:json][timeout:45];
            relation["boundary"="administrative"]["admin_level"="4"]["ISO3166-2"~"^IN-"];
            out tags;
            """.trimIndent(),
            defaultLevel = 4,
        )
        lastSource = if (live.isEmpty()) "snapshot" else "live"
        val places = live.ifEmpty { adminSnapshot.states() }
        if (places.isNotEmpty()) {
            statesMemo.clear()
            statesMemo.addAll(places)
        }
        statesMemo.toList()
    }

    /**
     * Districts of a state. Uses area containment, which is the only reliable way;
     * falls back to the bundled snapshot when Overpass is unavailable.
     */
    suspend fun districts(state: OsmPlace): List<OsmPlace> = withContext(io) {
        val areaId = areaId(state.osmId)
        val live = query(
            """
            [out:json][timeout:60];
            area(id:$areaId)->.s;
            relation(area.s)["boundary"="administrative"]["admin_level"~"^(5|6|7)$"];
            out tags;
            """.trimIndent(),
            defaultLevel = 6,
        )
        live.ifEmpty { adminSnapshot.districts(state.osmId) }
    }

    /** Localities inside a district: named places plus admin_level 8/9/10 areas. */
    suspend fun localities(district: OsmPlace): List<OsmPlace> = withContext(io) {
        val areaId = areaId(district.osmId)
        val places = query(
            """
            [out:json][timeout:60];
            area(id:$areaId)->.d;
            node(area.d)["place"~"^(city|town|suburb|quarter|neighbourhood|hamlet|village)$"];
            out tags;
            """.trimIndent(),
            defaultLevel = 8,
        )
        val areas = query(
            """
            [out:json][timeout:60];
            area(id:$areaId)->.d;
            relation(area.d)["boundary"="administrative"]["admin_level"~"^(8|9|10)$"];
            out tags;
            """.trimIndent(),
            defaultLevel = 9,
        )
        (places + areas)
            .distinctBy { it.name.lowercase() }
            .sortedBy { it.name }
    }

    /**
 * Runs [query] against every Overpass mirror in turn.
 *
 * Public Overpass servers fail transiently very often (504 gateway timeouts), so
 * each endpoint gets several attempts with a short backoff before moving on.
 */
    private suspend fun query(
        query: String,
        defaultLevel: Int,
    ): List<OsmPlace> {
        overpassApis.forEachIndexed { index, api ->
            if (index > 0) delay(400)
            repeat(3) { attempt ->
                val parsed = runCatching { api.run(query).string() }
                    .mapCatching { parseElements(JSONObject(it), defaultLevel) }
                    .getOrNull()
                    .orEmpty()
                if (parsed.isNotEmpty()) return parsed
                if (attempt < 2) delay(1_200L * (attempt + 1))
            }
        }
        return emptyList()
    }

    private fun parseElements(json: JSONObject, defaultLevel: Int): List<OsmPlace> {
        val elements = json.optJSONArray("elements") ?: JSONArray()
        val result = mutableListOf<OsmPlace>()
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            val tags = element.optJSONObject("tags") ?: continue
            val name = tags.optString("name:en").takeIf { it.isNotBlank() }
                ?: tags.optString("name").takeIf { it.isNotBlank() }
                ?: continue
            val level = tags.optString("admin_level").toIntOrNull() ?: defaultLevel
            result += OsmPlace(element.optLong("id"), name, level)
        }
        return result
    }

    private companion object {
        /** OSM relation ids map to negative area ids via this offset. */
        fun areaId(relationId: Long): Long = 3_600_000_000L + relationId
    }
}

/**
 * Geocodes free text with Nominatim (1 req/s, in-memory memo) so distances to
 * Work 1 / Work 2 can be computed for listings that expose no coordinates.
 */
@Singleton
class Geocoder @Inject constructor(
    private val nominatim: NominatimApi,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val memo = ConcurrentHashMap<String, Pair<Double, Double>?>()
    private var lastCallAt = 0L

    suspend fun geocode(query: String): Pair<Double, Double>? {
        val key = query.trim().lowercase()
        if (key.isEmpty()) return null
        if (memo.containsKey(key)) return memo[key]

        val result = withContext(io) {
            val now = System.currentTimeMillis()
            val wait = 1_100L - (now - lastCallAt)
            if (wait > 0) delay(wait)
            lastCallAt = System.currentTimeMillis()

            runCatching {
                val body = nominatim.search(query).string()
                val arr = JSONArray(body)
                val first = arr.optJSONObject(0) ?: return@runCatching null
                val lat = first.optString("lat").toDoubleOrNull()
                val lon = first.optString("lon").toDoubleOrNull()
                if (lat != null && lon != null) lat to lon else null
            }.getOrNull()
        }
        memo[key] = result
        return result
    }
}