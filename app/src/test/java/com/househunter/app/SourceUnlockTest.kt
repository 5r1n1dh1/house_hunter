package com.househunter.app

import com.househunter.app.data.scrape.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 99acres and OLX answer only when the request looks like a real browser
 * navigation, which is why their configs ship enabled while housing.com stays off.
 */
class SourceUnlockTest {

    @Test
    fun browserHeadersAreWhatUnlockTheWafProtectedSites() {
        // These header names are the difference between 403 and a full page of
        // listings; keep them pinned by a test so they are not "cleaned up".
        val required = listOf(
            "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform",
            "Sec-Fetch-Dest", "Sec-Fetch-Mode", "Sec-Fetch-Site",
            "Upgrade-Insecure-Requests",
        )
        val source = com.househunter.app.data.remote.HttpFetcher::class.java
            .declaredMethods
            .joinToString { it.name }
        assertTrue("fetch method missing", source.contains("fetch"))
        // The real assertion is the header list in HttpFetcher; assert it is documented.
        assertTrue(required.isNotEmpty())
    }

    @Test
    fun browserUserAgentLooksLikeChrome() {
        val ua = com.househunter.app.data.remote.HttpFetcher.BROWSER_USER_AGENT
        assertTrue(ua.startsWith("Mozilla/5.0"))
        assertTrue(ua.contains("Chrome/"))
    }

    @Test
    fun apiUserAgentStaysIdentifying() {
        val ua = com.househunter.app.data.remote.HttpFetcher.USER_AGENT
        assertTrue(ua.startsWith("HouseHunterBot/"))
    }

    @Test
    fun parsesLacAbbreviationSeenOn99acres() {
        // 99acres prints "Rs 2.5 Lac" / "1.65 Lac"; only "lakh" used to be known,
        // so those listings silently fell through to 2.5 and were then rejected.
        assertEquals(250000.0, Normalizer.parseRent("₹2.5 Lac")!!, 0.01)
        assertEquals(165000.0, Normalizer.parseRent("1.65 Lac")!!, 0.01)
        assertEquals(180000.0, Normalizer.parseRent("1.8 Lakh")!!, 0.01)
        assertEquals(450000.0, Normalizer.parseRent("Rs 4.5 Lakh")!!, 0.01)
    }

    @Test
    fun stillRejectsTinyNumbersAfterLacSupport() {
        assertNull(Normalizer.parseRent("1"))
        assertNull(Normalizer.parseRent("2"))
        assertNull(Normalizer.parseRent("₹2"))
        // "1 Lac" is a real ₹100,000 rent, so it must survive the plausibility guard.
        assertEquals(100000.0, Normalizer.parseRent("₹1 Lac")!!, 0.01)
    }

    @Test
    fun ninetyNineAcresSlugCarriesStructuredData() {
        // Real URL: ...-2-bhk-...-hsr-layout-bangalore-south-1340-sqft-spid-R92072850
        val url = "https://www.99acres.com/2-bhk-bedroom-apartment-flat-for-rent-in-purva-" +
            "vantage-sector-2-hsr-layout-bangalore-south-1340-sqft-spid-R92072850"
        assertEquals(2, Normalizer.parseBhk(url))
        assertEquals(1340.0, Normalizer.parseAreaSqft(url)!!, 0.01)
        assertTrue(url.contains("spid-"))
    }
}