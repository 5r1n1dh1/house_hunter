package com.househunter.app

import com.househunter.app.domain.model.Filters
import com.househunter.app.domain.model.Listing
import com.househunter.app.domain.model.Sort
import com.househunter.app.domain.model.WorkLocation
import com.househunter.app.domain.usecase.ApplyFilters
import com.househunter.app.domain.usecase.ComputeDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceAndFilterTest {

    private val whitefield = WorkLocation("Work 1", 12.9698, 77.7500)
    private const val INDIRANAGAR_LAT = 12.9719
    private const val INDIRANAGAR_LON = 77.6412

    private fun listing(id: String, rent: Double, bhk: Int? = 2, lat: Double? = null, lng: Double? = null) =
        Listing(
            id = id,
            siteId = "test",
            siteName = "Test",
            title = "2 BHK near Indiranagar",
            rent = rent,
            bhk = bhk,
            locality = "Indiranagar",
            district = "Bengaluru Urban",
            state = "Karnataka",
            lat = lat,
            lng = lng,
            detailUrl = "https://example.com/$id",
            phone = "+919999999999",
        )

    @Test
    fun haversineMatchesKnownDistance() {
        // Indiranagar -> Whitefield is roughly 10.5 km
        val km = ComputeDistance.haversineKm(INDIRANAGAR_LAT, INDIRANAGAR_LON, whitefield.lat, whitefield.lng)
        assertTrue("distance was $km", km in 9.0..12.0)
    }

    @Test
    fun sortsByPrice() {
        val listings = listOf(listing("a", 30000.0), listing("b", 15000.0), listing("c", 22000.0))
        val sorted = ApplyFilters.apply(listings, Filters(), Sort.PRICE_ASC)
        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    @Test
    fun sortsByDistanceToWorkOne() {
        val near = listing("near", 20000.0, lat = INDIRANAGAR_LAT, lng = INDIRANAGAR_LON)
        val far = listing("far", 15000.0, lat = 13.02, lng = 77.71)
        val unknown = listing("unknown", 10000.0)

        val sorted = ApplyFilters.apply(
            listings = listOf(unknown, far, near),
            filters = Filters(),
            sort = Sort.NEAREST_WORK1,
            work1 = whitefield,
        )
        assertEquals(listOf("near", "far", "unknown"), sorted.map { it.id })
    }

    @Test
    fun filtersByBudgetBhkAndRadius() {
        val near = listing("near", 18000.0, lat = INDIRANAGAR_LAT, lng = INDIRANAGAR_LON)
        val far = listing("far", 45000.0, lat = 13.05, lng = 77.80)
        val big = listing("big", 60000.0, bhk = 3, lat = INDIRANAGAR_LAT, lng = INDIRANAGAR_LON)

        val result = ApplyFilters.apply(
            listings = listOf(near, far, big),
            filters = Filters(rentMax = 50_000.0, bhk = setOf(2), maxKmWork1 = 12.0),
            sort = Sort.PRICE_ASC,
            work1 = whitefield,
        )
        assertEquals(listOf("near"), result.map { it.id })
    }

    @Test
    fun contactOnlyFilterKeepsListingsWithPhone() {
        val withoutPhone = listing("x", 20000.0).copy(phone = null)
        val withPhone = listing("y", 21000.0)
        val result = ApplyFilters.apply(
            listings = listOf(withoutPhone, withPhone),
            filters = Filters(contactOnly = true),
            sort = Sort.PRICE_ASC,
        )
        assertEquals(listOf("y"), result.map { it.id })
    }
}