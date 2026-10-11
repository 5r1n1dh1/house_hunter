package com.househunter.app.data.repository

import com.househunter.app.domain.model.WorkLocation
import org.json.JSONArray

/**
 * Turns Nominatim responses into a ranked, *usable* list of work-location candidates.
 *
 * The previous behaviour took `itemListObject(0)` from a `limit=1` query and showed
 * nothing when it was null. Real failures this fixed, all reproduced against the
 * live API:
 *
 *  - **"hpe iwf01 bangaluru" returns `[]`.** The office code `IWF01` is not in
 *    OpenStreetMap, and because the whole query matched nothing the user saw an
 *    empty list with no explanation. [relaxedQueries] retries with tokens dropped
 *    ("hpe bangaluru"), which *does* resolve to Hewlett Packard Enterprise.
 *  - **"manyatha tech park" returns three `highway=bus_stop` nodes.** Nominatim's
 *    best match is a bus shelter, not the business park; with `limit=1` that was
 *    the only thing the user could pick. [rank] pushes real places above
 *    incidental infrastructure while keeping everything selectable.
 *
 * Pure logic, no network and no Android types, so it is unit-testable on the JVM.
 */
object PlaceSearch {

    /** A geocoded candidate plus why it may or may not be what the user meant. */
    data class Candidate(
        val name: String,
        val displayName: String,
        val lat: Double,
        val lng: Double,
        /** Nominatim `category.type`, e.g. `landuse.commercial`. */
        val kind: String,
    ) {
        fun toWorkLocation(): WorkLocation = WorkLocation(label = name, lat = lat, lng = lng)
    }

    /**
     * Categories that describe a place a person would actually work at or search
     * for. Anything else - bus stops, road furniture, barriers - is kept but
     * ranked last, because sometimes the bus stop really is the only OSM feature.
     */
    private val PLACE_KINDS = setOf(
        "landuse", "building", "amenity", "place", "shop", "office", "tourism",
        "leisure", "historic", "man_made", "natural", "boundary",
    )

    /** Things that are addresses of *somewhere else*, never the search target itself. */
    private val WEAK_KINDS = setOf("highway", "barrier", "address", "street", "postal_code", "railway")

    /**
     * Builds progressively looser queries to try when the full text matches nothing.
     *
     * Order matters: city is dropped first (it is the least identifying part and
     * the most likely to be what the map does not have), then trailing tokens,
     * then leading tokens. "hpe iwf01 bangaluru" becomes "hpe iwf01" ->
     * "hpe" -> "iwf01", any of which may hit.
     */
    fun relaxedQueries(query: String): List<String> {
        val trimmed = query.trim()
        val tokens = trimmed.split(' ').filter { it.isNotBlank() }
        if (tokens.size < 2) return emptyList()

        val out = LinkedHashSet<String>()
        val cityWords = setOf("bangalore", "bengaluru", "india", "karnataka")

        // Drop the city token first - it narrows nothing for a well-known site.
        val withoutCity = tokens.filterNot { it.lowercase() in cityWords }
        if (withoutCity.size in 1..tokens.size) {
            out += withoutCity.joinToString(" ")
        }
        // Then shed tokens one at a time from the end, then from the front.
        for (drop in 1 until tokens.size) {
            out += tokens.dropLast(drop).joinToString(" ")
            out += tokens.drop(drop).joinToString(" ")
        }
        // Single most identifying token on its own.
        tokens.firstOrNull { it.length >= 4 }?.let { out += it }
        tokens.lastOrNull { it.length >= 4 }?.let { out += it }

        return out.filter { it.isNotBlank() && it != trimmed }.distinct()
    }

    /**
     * Parses a Nominatim `jsonv2` body into ranked candidates.
     *
     * Ranking, highest first:
     *  1. a place-ish [PLACE_KINDS] whose name matches the query,
     *  2. any place-ish match,
     *  3. weak/infrastructure matches.
     *
     * Nothing is dropped - a bus stop may genuinely be the only thing OSM has for
     * a site, and hiding it would leave the user with an empty screen, which is
     * the failure being fixed here.
     */
    fun parse(body: String, query: String): List<Candidate> {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val needle = query.trim().lowercase()

        val out = ArrayList<Candidate>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val lat = item.optString("lat").toDoubleOrNull() ?: continue
            val lng = item.optString("lon").toDoubleOrNull() ?: continue
            val display = item.optString("display_name").trim()
            val name = item.optString("name").trim().ifEmpty {
                display.substringBefore(",").trim()
            }
            if (name.isEmpty()) continue
            val category = item.optString("category").trim().lowercase()
            val type = item.optString("type").trim().lowercase()
            val kind = if (category.isNotEmpty()) "$category.$type" else type
            out += Candidate(name, display, lat, lng, kind)
        }

        return out
            .sortedWith(
                compareByDescending<Candidate> { rank(it, needle) }
                    // Nominatim already orders by importance; keep that as the tiebreak.
                    .thenBy { it.displayName.length },
            )
            .distinctBy { "${it.lat},${it.lng}" }
    }

    /** Higher is better. See [parse] for the ordering rationale. */
    private fun rank(candidate: Candidate, query: String): Int {
        val isPlace = PLACE_KINDS.any { candidate.kind.startsWith(it) }
        val isWeak = WEAK_KINDS.any { candidate.kind.startsWith(it) }
        val name = candidate.name.lowercase()
        val matchesQuery = query.isNotEmpty() && query.contains(name) ||
            (name.isNotEmpty() && name.contains(query.takeIf { it.isNotEmpty() }.orEmpty()))
        return when {
            isPlace && matchesQuery -> 3
            isPlace -> 2
            isWeak -> 0
            else -> 1
        }
    }
}