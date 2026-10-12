package com.househunter.app

import com.househunter.app.data.scrape.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The unit is the whole game here: NoBroker sends epoch **milliseconds** and
 * Razorent sends ISO-8601, and both are read by the same JSON-mode parser.
 * Reading millis as seconds dates every NoBroker listing to 1970; reading
 * seconds as millis dates it to the far future. Either way the age chip lies.
 */
class NormalizerDatesTest {

    private val now = 1_800_000_000_000L // fixed clock, ~2027

    @Test
    fun parsesEpochMillisWithoutRescaling() {
        val millis = 1_700_000_000_000L
        assertEquals(millis, Normalizer.parsePostedAt(millis.toString()))
    }

    @Test
    fun parsesEpochSecondsAndPromotesToMillis() {
        val seconds = 1_700_000_000L
        assertEquals(seconds * 1000L, Normalizer.parsePostedAt(seconds.toString()))
    }

    @Test
    fun parsesIso8601WithZulu() {
        // A real Razorent `createdAt` value.
        assertEquals(
            1_791_720_753_585L,
            Normalizer.parsePostedAt("2026-10-11T12:12:33.585Z"),
        )
    }

    @Test
    fun parsesIso8601WithoutZoneAsUtc() {
        assertEquals(
            1_700_000_000_000L,
            Normalizer.parsePostedAt("2023-11-14T22:13:20"),
        )
    }

    @Test
    fun parsesNoBrokerCreationDate() {
        // Real NoBroker `creationDate` observed on a live listing.
        assertEquals(1_526_802_196_000L, Normalizer.parsePostedAt("1526802196000"))
    }

    @Test
    fun rejectsValuesTooShortToBeADate() {
        assertNull(Normalizer.parsePostedAt("12345"))
        assertNull(Normalizer.parsePostedAt(""))
        assertNull(Normalizer.parsePostedAt(null))
    }

    @Test
    fun rejectsFreeTextRatherThanGuessing() {
        // Card portals print "2 days ago"; guessing would fabricate a timestamp.
        assertNull(Normalizer.parsePostedAt("2 days ago"))
        assertNull(Normalizer.parsePostedAt("posted today"))
    }

    @Test
    fun ageIsRelativeAndBounded() {
        assertEquals("just now", Normalizer.relativeAge(now - 30_000L, now))
        assertEquals("5m ago", Normalizer.relativeAge(now - 5 * 60_000L, now))
        assertEquals("3h ago", Normalizer.relativeAge(now - 3 * 3_600_000L, now))
        assertEquals("2d ago", Normalizer.relativeAge(now - 2 * 86_400_000L, now))
        assertEquals("5w ago", Normalizer.relativeAge(now - 5 * 7 * 86_400_000L, now))
    }

    @Test
    fun unknownPostDateYieldsNoChipAtAll() {
        assertNull(Normalizer.relativeAge(null, now))
        assertNull(Normalizer.relativeAge(0L, now))
    }

    @Test
    fun futureTimestampClampsRatherThanShowingNegativeAge() {
        // Portal/device clock skew must not produce "-2d ago".
        assertEquals("just now", Normalizer.relativeAge(now + 2 * 86_400_000L, now))
    }
}