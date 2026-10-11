package com.househunter.app

import com.househunter.app.data.repository.LocalitySearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Filtering of Nominatim type-ahead results - pure logic, no network. */
class LocalitySearchParsingTest {

    private val payload = """
        [
          {"name":"Indiranagar","display_name":"Indiranagar, Bengaluru Urban, Karnataka, India","osm_id":123,"lat":"12.9719","lon":"77.6412"},
          {"name":"Indiranagar","display_name":"Indiranagar, Dhule Taluka, Maharashtra, India","osm_id":456,"lat":"20.9","lon":"74.7"},
          {"name":"Koramangala","display_name":"Koramangala, Bangalore South, Karnataka, India","osm_id":789,"lat":"12.93","lon":"77.61"}
        ]
    """.trimIndent()

    @Test
    fun keepsOnlyResultsInsideSelectedDistrictAndState() {
        val results = LocalitySearch.parse(payload, "Bengaluru Urban", "Karnataka")
        assertEquals(listOf("Indiranagar"), results.map { it.name })
        assertEquals(123L, results.first().osmId)
        assertEquals(12.9719, results.first().lat!!, 0.0001)
    }

    @Test
    fun districtFilterAloneStillExcludesOtherStates() {
        val results = LocalitySearch.parse(payload, "Bengaluru Urban", null)
        assertEquals(listOf("Indiranagar"), results.map { it.name })
    }

    @Test
    fun toleratesBangaloreBengaluruNaming() {
        val results = LocalitySearch.parse(payload, "Bangalore South", "Karnataka")
        assertTrue(results.any { it.name == "Koramangala" })
    }

    @Test
    fun malformedInputYieldsEmptyList() {
        assertTrue(LocalitySearch.parse("[]", "Bengaluru Urban", "Karnataka").isEmpty())
        assertTrue(LocalitySearch.parse("not json", null, null).isEmpty())
    }
}