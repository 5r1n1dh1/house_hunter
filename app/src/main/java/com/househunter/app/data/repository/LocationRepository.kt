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
 * Location data for the dropdowns.
 *
 * Why this no longer queries Overpass up front: Overpass answers in 10-60 s (and
 * frequently 504s) because admin hierarchy lookups need expensive spatial
 * containment. Measured alternatives:
 *  - States + districts come from the bundled OSM snapshot  -> instant, offline.
 *  - Localities use Nominatim type-ahead                  -> ~0.8 s, free, no key.
 *  - Overpass is still available for an explicit "browse everything" action, which
 *    is the only thing Overpass is genuinely better at (full enumeration).
 */
@Singleton
class LocationRepository @Inject constructor(
    private val overpassApis: Set<@JvmSuppressWildcards OverpassApi>,
    private val nominatim: NominatimApi,
    private val adminSnapshot: AdminSnapshot,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** States are bundled: returns immediately, no network. */
    suspend fun states(): List<OsmPlace> = adminSnapshot.states()

    /** Districts of a state are bundled: returns immediately, no network. */
    suspend fun districts(state: OsmPlace): List<OsmPlace> = adminSnapshot.districts(state.osmId)

    /**
     * Locality type-ahead via Nominatim (free, no API key, ~1 s).
     * Results are filtered to the selected district/state, and carry real
     * coordinates so Work-distance calculations do not need a second geocode.
     */
    suspend fun searchLocalities(
        query: String,
        districtName: String?,
        stateName: String?,
    ): List<OsmPlace> = withContext(io) {
        val q = query.trim()
        if (q.length < 3) return@withContext emptyList()
        throttleNominatim()

        val body = runCatching {
            nominatim.search(q, limit = 20).string()
        }.getOrNull() ?: return@withContext emptyList()

        LocalitySearch.parse(body, districtName, stateName)
    }

    /** Full enumeration of a district - Overpass, slow, opt-in only. */
    suspend fun browseLocalitiesOverpass(district: OsmPlace): List<OsmPlace> = withContext(io) {
        val areaId = AREA_ID_OFFSET + district.osmId
        val places = query(
            """
            [out:json][timeout:60];
            area(id:$areaId)->.d;
            node(area.d)["place"~"^(city|town|suburb|quarter|neighbourhood|hamlet|village)$"];
            out tags;
            """.trimIndent(),
            defaultLevel = 8,
        )
        places.sortedBy { it.name }
    }

    private var lastNominatimAt = 0L

    /** Nominatim usage policy: at most 1 request per second. */
    private suspend fun throttleNominatim() {
        val now = System.currentTimeMillis()
        val wait = 1_100L - (now - lastNominatimAt)
        if (wait > 0) delay(wait)
        lastNominatimAt = System.currentTimeMillis()
    }

    /** Overpass fallback, kept only for the explicit "browse all" path. */
    private suspend fun query(
        query: String,
        defaultLevel: Int,
    ): List<OsmPlace> {
        overpassApis.forEachIndexed { index, api ->
            if (index > 0) delay(400)
            repeat(2) { attempt ->
                val parsed = runCatching { api.run(query).string() }
                    .mapCatching { parseElements(JSONObject(it), defaultLevel) }
                    .getOrNull()
                    .orEmpty()
                if (parsed.isNotEmpty()) return parsed
                if (attempt == 0) delay(1_000)
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
        const val AREA_ID_OFFSET = 3_600_000_000L
    }
}

/**
 * Geocodes free text with Nominatim (1 req/s, in-memory memo) for Work locations
 * and for locating listings that expose no coordinates.
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
                val body = nominatim.search(query, limit = 1).string()
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