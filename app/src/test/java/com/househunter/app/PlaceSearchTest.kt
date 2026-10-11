package com.househunter.app

import com.househunter.app.data.repository.PlaceSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the work-location search failures reproduced against the live Nominatim API:
 * a query that matches nothing ("hpe iwf01 bangaluru") and one whose only hits are
 * bus stops ("manyatha tech park").
 */
class PlaceSearchTest {

    /** Verbatim shape Nominatim returns for "hpe iwf01 bangaluru". */
    private val emptyBody = "[]"

    /** Two bus stops plus the business park, as returned for "manyatha tech park". */
    private val manyathaBody = """
        [
          {"name":"Manyatha Tech Park","display_name":"Manyatha Tech Park, Outer Ring Road, Kempapura, Bengaluru North City Corporation, Bengaluru, Bangalore North, Bengaluru Urban, Karnataka, 560045, India","lat":"13.0411801","lon":"77.6204098","category":"highway","type":"bus_stop"},
          {"name":"Manyatha Tech Park","display_name":"Manyatha Tech Park, Service Road, Nagavara, Bengaluru North, Bengaluru, Karnataka, 560045, India","lat":"13.0476000","lon":"77.5988000","category":"highway","type":"bus_stop"},
          {"name":"Embassy TechVillage","display_name":"Embassy TechVillage, Devarabeesanahalli, Bengaluru, Karnataka, 560103, India","lat":"12.9989000","lon":"77.6872000","category":"landuse","type":"commercial"}
        ]
    """.trimIndent()

    private val hpeBody = """
        [{"name":"Hewlett Packard Enterprise","display_name":"Hewlett Packard Enterprise, B. Narayanapura, Bengaluru, Karnataka, India","lat":"13.0497000","lon":"77.6944000","category":"landuse","type":"commercial"}]
    """.trimIndent()

    @Test
    fun anExactMissYieldsNoCandidates() {
        assertTrue(PlaceSearch.parse(emptyBody, "hpe iwf01 bangaluru").isEmpty())
    }

    @Test
    fun businessParksOutrankBusStops() {
        // Nominatim's own ordering puts the bus shelters first; a campus is what
        // the user means, so the landuse hit must lead.
        val ranked = PlaceSearch.parse(manyathaBody, "manyatha tech park")
        assertTrue(ranked.isNotEmpty())
        assertEquals("Embassy TechVillage", ranked.first().name)
    }

    @Test
    fun busStopsRemainSelectableRatherThanHidden() {
        // Hiding them would leave the user with nothing, which is the bug being fixed.
        val ranked = PlaceSearch.parse(manyathaBody, "manyatha tech park")
        assertEquals(3, ranked.size)
        assertTrue(ranked.any { it.name.contains("Manyatha") })
    }

    @Test
    fun relaxationDropsTheOfficeCodeThatOpenStreetMapLacks() {
        val relaxed = PlaceSearch.relaxedQueries("hpe iwf01 bangaluru")
        assertTrue("expected a query without 'iwf01'", relaxed.none { it.contains("iwf01") })
        assertTrue(relaxed.any { it.equals("hpe", ignoreCase = true) })
    }

    @Test
    fun relaxationNeverReturnsTheOriginalQuery() {
        val q = "hpe iwf01 bangaluru"
        assertFalse(PlaceSearch.relaxedQueries(q).contains(q))
    }

    @Test
    fun singleWordQueriesAreNotRelaxed() {
        // Dropping tokens from one word leaves nothing useful.
        assertTrue(PlaceSearch.relaxedQueries("manyatha").isEmpty())
    }

    @Test
    fun parsesCoordinatesAndKind() {
        val first = PlaceSearch.parse(manyathaBody, "manyatha tech park").first()
        assertEquals(12.9989, first.lat, 0.0001)
        assertEquals("landuse.commercial", first.kind)
    }

    @Test
    fun candidatesConvertToWorkLocations() {
        val location = PlaceSearch.parse(hpeBody, "hpe bangaluru").first().toWorkLocation()
        assertEquals("Hewlett Packard Enterprise", location.label)
        assertEquals(13.0497, location.lat, 0.0001)
        assertNotNull(location.lng)
    }

    @Test
    fun malformedBodiesDoNotThrow() {
        assertTrue(PlaceSearch.parse("not json", "x").isEmpty())
        assertTrue(PlaceSearch.parse("", "x").isEmpty())
    }
}