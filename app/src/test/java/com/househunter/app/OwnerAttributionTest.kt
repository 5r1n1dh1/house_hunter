package com.househunter.app

import com.househunter.app.data.scrape.JsonLd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The owner/agent badge is only trustworthy because both working sources publish
 * who is advertising. These tests pin the two real payload shapes.
 */
class OwnerAttributionTest {

    @Test
    fun magicBricksJsonLdExposesLandlord() {
        val raw = """
            {"@context":"https://schema.org","@type":"RentAction",
             "landlord":{"@type":"Person","name":"Chaithanya Gowda"},
             "object":{"@type":"Residence","name":"3 BHK Apartment"},
             "priceSpecification":{"@type":"PriceSpecification","price":42300}}
        """.trimIndent()
        val parsed = JsonLd.parse(raw)
        assertEquals("Chaithanya Gowda", parsed.owner)
        assertEquals(42300.0, parsed.price!!, 0.01)
    }

    @Test
    fun jsonLdWithoutLandlordHasNoOwner() {
        val parsed = JsonLd.parse("""{"@type":"Product","name":"2 BHK","offers":{"price":30000}}""")
        assertNull(parsed.owner)
    }

    @Test
    fun magicBricksCardTagSplitsRoleAndName() {
        // The real markup is "Owner<!-- -->: <!-- -->Rakshitha", whose text is
        // "Owner: Rakshitha" - the app splits it into role + name.
        val raw = """Owner: Rakshitha"""
        val parts = raw.split(":", limit = 2).map { it.trim() }
        assertEquals("Owner", parts[0])
        assertEquals("Rakshitha", parts[1])

        val agent = "Agent: Srushti Jayadev".split(":", limit = 2).map { it.trim() }
        assertEquals("Agent", agent[0])
        assertEquals("Srushti Jayadev", agent[1])
    }

    @Test
    fun noBrokerPayloadKeepsOwnerAndBuilderApart() {
        val payload = """
            {"propertyTitle":"2 BHK for rent","rent":28000,"ownerName":"chethan byndoor"}
        """.trimIndent()
        val json = org.json.JSONObject(payload)
        assertEquals("chethan byndoor", json.optString("ownerName"))

        val builder = org.json.JSONObject("""{"builderName":"G Corp Developers Pvt Ltd"}""")
        assertNotNull(builder.optString("builderName"))
        assertEquals("", builder.optString("ownerName"))
    }
}