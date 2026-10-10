package com.househunter.app.data.repository

import com.househunter.app.data.remote.NominatimApi
import com.househunter.app.data.remote.OverpassApi
import com.househunter.app.di.IoDispatcher
import com.househunter.app.di.OverpassMirror
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
 * Overpass. Results are kept in memory for the session only (zero-cache policy).
 */
@Singleton
class LocationRepository @Inject constructor(
    private val overpass: OverpassApi,
    @OverpassMirror private val overpassMirror: OverpassApi,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val statesMemo = mutableListOf<OsmPlace>()

    suspend fun states(forceReload: Boolean = false): List<OsmPlace> = withContext(io) {
        if (statesMemo.isNotEmpty() && !forceReload) return@withContext statesMemo
        val query = """
            [out:json][timeout:25];
            area["ISO3166-1"="IN"][admin_level=2]->.india;
            relation(area.india)["boundary"="administrative"]["admin_level"="4"];
            out tags;
        """.trimIndent()
        val places = runOverpass(query) { parseElements(it, 4) }
        if (places.isNotEmpty()) {
            statesMemo.clear()
            statesMemo.addAll(places)
        }
        statesMemo.toList()
    }

    /** Districts of a state. Indian admin levels vary (5/6/7), so all are accepted. */
    suspend fun districts(state: OsmPlace): List<OsmPlace> = withContext(io) {
        val query = """
            [out:json][timeout:25];
            rel(id:${state.osmId});
            map_to_area->.a;
            relation(area.a)["boundary"="administrative"]["admin_level"~"^(5|6|7)$"];
            out tags;
        """.trimIndent()
        runOverpass(query) { parseElements(it, 6) }
    }

    /** Localities inside a district: named places plus admin_level 8/9/10 areas. */
    suspend fun localities(district: OsmPlace): List<OsmPlace> = withContext(io) {
        val query = """
            [out:json][timeout:25];
            rel(id:${district.osmId});
            map_to_area->.a;
            node(area.a)["place"~"^(city|town|suburb|quarter|neighbourhood|hamlet|village)$"];
            out tags;
        """.trimIndent()
        val places = runOverpass(query) { parseElements(it, 8) }

        val areaQuery = """
            [out:json][timeout:25];
            rel(id:${district.osmId});
            map_to_area->.a;
            relation(area.a)["boundary"="administrative"]["admin_level"~"^(8|9|10)$"];
            out tags;
        """.trimIndent()
        val areas = runOverpass(areaQuery) { parseElements(it, 9) }

        (places + areas)
            .distinctBy { it.name.lowercase() }
            .sortedBy { it.name }
    }

    private suspend fun runOverpass(
        query: String,
        parse: (JSONObject) -> List<OsmPlace>,
    ): List<OsmPlace> {
        val primary = runCatching { overpass.run(query).string() }.getOrNull()
        if (primary != null) {
            val parsed = runCatching { parse(JSONObject(primary)) }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return parsed
        }
        val mirror = runCatching { overpassMirror.run(query).string() }.getOrNull() ?: return emptyList()
        return runCatching { parse(JSONObject(mirror)) }.getOrDefault(emptyList())
    }

    private fun parseElements(json: JSONObject, adminLevel: Int): List<OsmPlace> {
        val elements = json.optJSONArray("elements") ?: JSONArray()
        val result = mutableListOf<OsmPlace>()
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            val tags = element.optJSONObject("tags") ?: continue
            val name = tags.optString("name:en").takeIf { it.isNotBlank() }
                ?: tags.optString("name").takeIf { it.isNotBlank() }
                ?: continue
            val level = tags.optString("admin_level").toIntOrNull() ?: adminLevel
            result += OsmPlace(element.optLong("id"), name, level)
        }
        return result
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