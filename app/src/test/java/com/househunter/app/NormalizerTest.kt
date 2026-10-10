package com.househunter.app

import com.househunter.app.data.scrape.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizerTest {

    @Test
    fun parsesIndianRupeeFormats() {
        assertEquals(18000.0, Normalizer.parseRent("₹18,000")!!, 0.01)
        assertEquals(25000.0, Normalizer.parseRent("Rs. 25,000 /month")!!, 0.01)
        assertEquals(180000.0, Normalizer.parseRent("1.8 Lakh")!!, 0.01)
        assertEquals(45000.0, Normalizer.parseRent("45k")!!, 0.01)
        assertEquals(32000.0, Normalizer.parseRent("32000")!!, 0.01)
        assertNull(Normalizer.parseRent("Price on request"))
        assertNull(Normalizer.parseRent(null))
    }

    @Test
    fun parsesBhkAndArea() {
        assertEquals(2, Normalizer.parseBhk("2 BHK"))
        assertEquals(3, Normalizer.parseBhk("3bhk Flat"))
        assertEquals(2, Normalizer.parseBhk("2.5 BHK"))
        assertNull(Normalizer.parseBhk("Studio apartment"))

        assertEquals(1200.0, Normalizer.parseAreaSqft("1,200 sqft")!!, 0.01)
        assertEquals(1100.0, Normalizer.parseAreaSqft("1100 sq.ft")!!, 0.01)
        assertEquals(1500.0, Normalizer.parseAreaSqft("1500")!!, 0.01)
    }

    @Test
    fun sanitizesPhoneNumbers() {
        assertEquals("919876543210", Normalizer.sanitizePhone("+91 98765 43210"))
        assertNull(Normalizer.sanitizePhone("N/A"))
        assertNull(Normalizer.sanitizePhone(null))
    }

    @Test
    fun resolvesRelativeUrls() {
        val resolved = Normalizer.resolveUrl("https://example.com", "/rent/2-bhk/12")
        assertEquals("https://example.com/rent/2-bhk/12", resolved)
        assertEquals("https://other.com/x", Normalizer.resolveUrl("https://example.com", "https://other.com/x"))
        assertNull(Normalizer.resolveUrl("https://example.com", ""))
    }

    @Test
    fun capturesRegexGroups() {
        assertEquals("1200", Normalizer.capture("([0-9,]+)\\s*sqft", "Built up area 1200 sqft"))
        assertNull(Normalizer.capture("([0-9]+)", "no digits here"))
    }

    @Test
    fun dedupeKeyNormalisation() {
        assertTrue(
            Normalizer.normalizeForDedupe("2 BHK Flat  for Rent!!") ==
                Normalizer.normalizeForDedupe("2 bhk flat for rent"),
        )
    }
}