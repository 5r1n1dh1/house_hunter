package com.househunter.app

import com.househunter.app.data.scrape.EmbeddedJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NoBroker paints its rental results with JavaScript, so the listings never appear in the DOM -
 * they only exist as a JSON blob inside a `<script>` tag. These fixtures were copied from live
 * responses, so if NoBroker renames a key, drops a field or reshapes the blob, these are the
 * tests that fail first.
 */
class EmbeddedJsonTest {

    @Test
    fun extractsEveryListingFromTheEmbeddedPropertiesArray() {
        val listings = EmbeddedJson.extractArray(noBrokerHtml(LISTING_COUNT), "rent")

        assertEquals(LISTING_COUNT, listings.size)

        val first = listings.first()
        assertEquals(28000.0, first.optDouble("rent"), 0.01)
        assertEquals("1 BHK Flat for Rent in Indiranagar, Bengaluru", first.getString("propertyTitle"))
        assertEquals("BHK1", first.getString("type"))
        assertEquals("600", first.getString("propertySize"))
        assertEquals("Indiranagar", first.getString("locality"))
        assertEquals("Indiranagar", first.getString("localityTruncated"))
        assertEquals("Furnished", first.getString("furnishingDesc"))
        assertEquals(56000.0, first.optDouble("deposit"), 0.01)

        // Both URLs are relative to the site; the scraper resolves them later.
        assertTrue(first.getString("detailUrl").startsWith("/property/"))
        assertTrue(first.getString("originalImageUrl").startsWith("//assets.nobroker.in/"))
        assertTrue(listings.all { it.getString("detailUrl").startsWith("/property/") })

        // 24 % 10 -> the 5th entry of RENTS: the tail must survive the slice too.
        assertEquals(24500.0, listings.last().optDouble("rent"), 0.01)
    }

    @Test
    fun bracketsInsideStringValuesDoNotCorruptTheScan() {
        // Titles and notes legitimately contain brackets and an escaped quote; a scanner that
        // counts brackets blindly closes the array inside this string and returns garbage.
        val html = """
            <html><body><div id="app"></div>
            <script>
            window.__STATE__ = {"properties":[
            {"rent":12000,"propertyTitle":"2 BHK with lift [ready] {furnished}","note":"a [b] {c}"},
            {"rent":14000,"propertyTitle":"He said \"cheap\" ] and { brace"},
            {"rent":15000,"propertyTitle":"Plain listing"}]};
            </script>
            </body></html>
        """.trimIndent()

        val listings = EmbeddedJson.extractArray(html, "rent")

        assertEquals(3, listings.size)
        assertEquals(12000.0, listings[0].optDouble("rent"), 0.01)
        assertEquals("a [b] {c}", listings[0].getString("note"))
        assertEquals("2 BHK with lift [ready] {furnished}", listings[0].getString("propertyTitle"))
        assertEquals("He said \"cheap\" ] and { brace", listings[1].getString("propertyTitle"))
        assertEquals(15000.0, listings[2].optDouble("rent"), 0.01)
    }

    @Test
    fun parsesAMinifiedSingleLinePageSurroundedByOtherArrays() {
        // Deliberately one long line, exactly as the site ships it, with an unrelated nav array
        // (which also mentions "rent" as a *value*) ahead of the listings and an analytics array
        // behind them. This is what breaks naive indexOf("{") / lastIndexOf("}") implementations.
        val html = MINIFIED_PAGE

        val listings = EmbeddedJson.extractArray(html, "rent")

        assertEquals(2, listings.size)
        assertEquals(28000.0, listings[0].optDouble("rent"), 0.01)
        assertEquals("1 BHK Flat for Rent in Indiranagar, Bengaluru", listings[0].getString("propertyTitle"))
        assertEquals(32000.0, listings[1].optDouble("rent"), 0.01)
        assertTrue(listings.all { it.getString("originalImageUrl").startsWith("//") })
    }

    @Test
    fun survivesMarkupThatDesynchronisesTheQuoteTracker() {
        // The stray `"` in the body text flips the string tracker for the rest of the document;
        // the retry inside <script> bodies still recovers the listings.
        val html = """<html><body><p>Pipe is 5" wide</p><script>""" +
            """{"properties":[{"rent":33000},{"rent":44000}]}""" +
            """</script></body></html>"""

        val listings = EmbeddedJson.extractArray(html, "rent")

        assertEquals(2, listings.size)
        assertEquals(33000.0, listings[0].optDouble("rent"), 0.01)
        assertEquals(44000.0, listings[1].optDouble("rent"), 0.01)
    }

    @Test
    fun returnsEmptyListWhenTheAnchorKeyIsAbsent() {
        val html = noBrokerHtml(LISTING_COUNT)

        assertTrue(EmbeddedJson.extractArray(html, "maintenanceCharge").isEmpty())
        assertTrue(EmbeddedJson.extractArray(html, "pricePerSqft").isEmpty())
        // Unquoted text is not a JSON key, so a page with no blob must not "match" anything.
        assertTrue(EmbeddedJson.extractArray("<html><body>No results found</body></html>", "rent").isEmpty())
        assertTrue(EmbeddedJson.extractArray(html, "").isEmpty())
        assertTrue(EmbeddedJson.extractArray(html, "   ").isEmpty())
        assertTrue(EmbeddedJson.extractArray("", "rent").isEmpty())
    }

    @Test
    fun returnsEmptyListOnMalformedJson() {
        // Response cut off mid-array: the bracket walker never finds a balanced span.
        val truncated = """<script>window.s={"properties":[{"rent":28000,"type":"BHK2"}</script>"""
        // Balanced brackets, broken syntax (missing ':'): org.json throws and we swallow it.
        val broken = """<script>window.s={"properties":[{"propertyTitle":"2 BHK" "rent":28000}]};</script>"""
        // An array of strings that happens to contain the anchor - not a listing array.
        val wrongShape = """<script>window.s={"tags":["rent","deposit","maintenance"]};</script>"""

        assertTrue(EmbeddedJson.extractArray(truncated, "rent").isEmpty())
        assertTrue(EmbeddedJson.extractArray(broken, "rent").isEmpty())
        assertTrue(EmbeddedJson.extractArray(wrongShape, "rent").isEmpty())
    }

    @Test
    fun firstStringSkipsMissingNullAndBlankValues() {
        val listing = JSONObject(
            """
            {"propertyTitle":"   ","locality":null,"type":"BHK2","floor":"3","rooms":2,"tags":["a"]}
            """.trimIndent(),
        )

        with(EmbeddedJson) {
            assertEquals("BHK2", listing.firstString("propertyTitle", "locality", "type"))
            assertEquals("3", listing.firstString("floor"))
            assertEquals("2", listing.firstString("rooms"))
            assertEquals("BHK2", listing.firstString("absent", "type"))
            assertNull(listing.firstString("propertyTitle"))
            assertNull(listing.firstString("locality"))
            assertNull(listing.firstString("tags"))
            assertNull(listing.firstString())
        }
    }

    @Test
    fun firstDoubleAcceptsFormattedStringsAndRejectsNonPositiveValues() {
        val listing = JSONObject(
            """
            {"rent":"₹45,000","rentPerMonth":"28000 /month","deposit":50000,"maintenance":"₹0",
             "score":"abc","discount":-5000,"rebate":"-5000","id":101}
            """.trimIndent(),
        )

        with(EmbeddedJson) {
            assertEquals(45000.0, listing.firstDouble("rent")!!, 0.01)
            assertEquals(28000.0, listing.firstDouble("rentPerMonth")!!, 0.01)
            assertEquals(50000.0, listing.firstDouble("deposit")!!, 0.01)
            assertEquals(45000.0, listing.firstDouble("absent", "rent")!!, 0.01)
            // Unusable values are skipped and the next key wins.
            assertEquals(45000.0, listing.firstDouble("score", "maintenance", "rent")!!, 0.01)
            assertNull(listing.firstDouble("score"))
            assertNull(listing.firstDouble("maintenance"))
            assertNull(listing.firstDouble("discount"))
            assertNull(listing.firstDouble("rebate"))
            assertNull(listing.firstDouble("absent"))
        }
    }

    private fun listingJson(index: Int): String {
        val locality = LOCALITIES[index % LOCALITIES.size]
        val bhk = (index % 3) + 1
        val rent = RENTS[index % RENTS.size]
        val size = 600 + (index % 8) * 150
        val slug = locality.lowercase().replace(' ', '-')
        val furnishing = FURNISHING[index % FURNISHING.size]
        val deposit = rent * 2
        val id = 1000 + index
        return """
            {"propertyTitle":"$bhk BHK Flat for Rent in $locality, Bengaluru",
            "rent":$rent,
            "type":"BHK$bhk",
            "propertySize":"$size",
            "locality":"$locality",
            "localityTruncated":"$locality",
            "detailUrl":"/property/$id/BHK${bhk}_Flat_for_Rent_in_${slug}_in_Bengaluru",
            "originalImageUrl":"//assets.nobroker.in/nnlh/$id/${slug}_BHK$bhk.jpg",
            "furnishingDesc":"$furnishing",
            "deposit":$deposit}
        """.trimIndent().replace("\n", "")
    }

    /** A realistic response: boilerplate markup, an ads blob, then the listing array. */
    private fun noBrokerHtml(count: Int): String {
        val listings = (0 until count).joinToString(",") { listingJson(it) }
        val blob = """{"properties":[$listings],"pageMeta":{"total":$count,"page":1}}"""
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <title>Property for rent in Bengaluru | NoBroker</title>
            <script>window.__ADS__={"slots":[{"id":1,"tags":["premium","rent"]}]};</script>
            <script>window.__INITIAL_STATE__ = $blob;</script>
            </head>
            <body>
            <div id="app"></div>
            <noscript>Please enable JavaScript.</noscript>
            </body>
            </html>
        """.trimIndent()
    }

    private companion object {

        const val LISTING_COUNT = 25

        val LOCALITIES = listOf(
            "Indiranagar", "Koramangala", "Whitefield", "HSR Layout", "BTM Layout",
            "Marathahalli", "Jayanagar", "Rajajinagar", "Yelahanka", "Hebbal",
        )

        val RENTS = listOf(28000, 32000, 18500, 42000, 24500, 39000, 21500, 27500, 36000, 16000)

        val FURNISHING = listOf("Furnished", "Semi-Furnished", "Unfurnished")

        const val MINIFIED_PAGE = "<!doctype html><html><head><script>var nav=[{\"label\":\"Rent\"},{\"label\":\"Buy\"}];window.s={\"user\":{\"id\":7},\"search\":{\"filters\":[{\"name\":\"rent\",\"type\":\"SLIDER\"}]},\"response\":{\"properties\":[{\"propertyTitle\":\"1 BHK Flat for Rent in Indiranagar, Bengaluru\",\"rent\":28000,\"type\":\"BHK1\",\"propertySize\":\"600\",\"locality\":\"Indiranagar\",\"localityTruncated\":\"Indiranagar\",\"detailUrl\":\"/property/1000/BHK1_Flat_for_Rent_in_indiranagar_in_Bengaluru\",\"originalImageUrl\":\"//assets.nobroker.in/nnlh/1000/i_BHK1.jpg\",\"furnishingDesc\":\"Furnished\",\"deposit\":56000},{\"propertyTitle\":\"2 BHK Flat for Rent in Koramangala, Bengaluru\",\"rent\":32000,\"type\":\"BHK2\",\"propertySize\":\"1150\",\"locality\":\"Koramangala\",\"localityTruncated\":\"Koramangala\",\"detailUrl\":\"/property/1001/BHK2_Flat_for_Rent_in_koramangala_in_Bengaluru\",\"originalImageUrl\":\"//assets.nobroker.in/nnlh/1001/k_BHK2.jpg\",\"furnishingDesc\":\"Semi-Furnished\",\"deposit\":64000}],\"pageMeta\":{\"total\":2}}};</script><script>window.ga=[[\"create\",\"UA-1\"],[\"send\",\"pageview\"]];</script></head><body><div id=\"app\"></div></body></html>"
    }
}
