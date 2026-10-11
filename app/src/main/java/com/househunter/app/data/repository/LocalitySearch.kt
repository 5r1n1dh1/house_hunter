package com.househunter.app.data.repository

import com.househunter.app.domain.model.OsmPlace
import org.json.JSONArray

/**
 * Parses Nominatim type-ahead responses into dropdown entries.
 * Pure logic (no network, no Android deps) so it is unit-testable on the JVM.
 */
object LocalitySearch {

    fun parse(
        body: String,
        districtName: String?,
        stateName: String?,
    ): List<OsmPlace> {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val districtNeedle = districtName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val stateNeedle = stateName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        var firstName: String? = null
        val parsed = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name").takeIf { it.isNotBlank() }
                ?: item.optString("display_name").substringBefore(",").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if (firstName == null) firstName = name

            val display = item.optString("display_name").lowercase()
            val inDistrict = districtNeedle == null || matches(display, districtNeedle)
            val inState = stateNeedle == null || matches(display, stateNeedle)
            if (!inDistrict || !inState) return@mapNotNull null

            OsmPlace(
                osmId = item.optLong("osm_id", 0L),
                name = name,
                adminLevel = 8,
                lat = item.optString("lat").toDoubleOrNull(),
                lng = item.optString("lon").toDoubleOrNull(),
            )
        }

        val prefix = firstName?.lowercase()
        return parsed
            .distinctBy { it.name.lowercase() }
            .sortedByDescending { prefix != null && it.name.lowercase().startsWith(prefix) }
    }

    /**
     * Loose-but-strict containment: **every** significant word of [needle] must
     * appear in [haystack], with "Bengaluru"/"Bangalore" treated as aliases.
     *
     * Requiring all words matters: with a looser rule "Bengaluru Urban" matched
     * Nominatim's "Bangalore South", which is a different district.
     */
    private fun matches(haystack: String, needle: String): Boolean {
        if (haystack.contains(needle)) return true

        val words = needle.split(' ', '-')
            .map { it.trim().removeSuffix("district").trim() }
            .filter { it.length >= 4 }
        if (words.isEmpty()) return false

        return words.all { word ->
            when {
                haystack.contains(word) -> true
                word.startsWith("bangalore") -> haystack.contains("bengaluru")
                word.startsWith("bengaluru") -> haystack.contains("bangalore")
                else -> false
            }
        }
    }
}