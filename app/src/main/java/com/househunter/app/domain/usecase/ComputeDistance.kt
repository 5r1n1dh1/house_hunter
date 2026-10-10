package com.househunter.app.domain.usecase

import com.househunter.app.domain.model.Filters
import com.househunter.app.domain.model.Listing
import com.househunter.app.domain.model.Sort
import com.househunter.app.domain.model.WorkLocation
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle distance helpers (pure, unit-testable). */
object ComputeDistance {

    private const val EARTH_RADIUS_KM = 6371.0088

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(a)))
    }

    fun distanceTo(listing: Listing, work: WorkLocation?): Double? {
        if (work == null || listing.lat == null || listing.lng == null) return null
        return haversineKm(listing.lat, listing.lng, work.lat, work.lng)
    }
}

/** In-memory filtering + sorting of freshly scraped listings. */
object ApplyFilters {

    fun apply(
        listings: List<Listing>,
        filters: Filters,
        sort: Sort,
        work1: WorkLocation? = null,
        work2: WorkLocation? = null,
    ): List<Listing> {
        val filtered = listings.filter { listing ->
            (filters.rentMin == null || listing.rent >= filters.rentMin) &&
                (filters.rentMax == null || listing.rent <= filters.rentMax) &&
                (filters.bhk.isEmpty() || (listing.bhk != null && listing.bhk in filters.bhk)) &&
                (!filters.contactOnly || !listing.phone.isNullOrBlank()) &&
                withinRadius(listing, work1, filters.maxKmWork1) &&
                withinRadius(listing, work2, filters.maxKmWork2)
        }

        return when (sort) {
            Sort.PRICE_ASC -> filtered.sortedBy { it.rent }
            Sort.PRICE_DESC -> filtered.sortedByDescending { it.rent }
            Sort.NEAREST_WORK1 -> filtered.sortedBy { ComputeDistance.distanceTo(it, work1) ?: Double.MAX_VALUE }
            Sort.NEAREST_WORK2 -> filtered.sortedBy { ComputeDistance.distanceTo(it, work2) ?: Double.MAX_VALUE }
            Sort.NEWEST -> filtered.sortedWith(
                compareByDescending<Listing> { it.postedAt != null }.thenByDescending { it.postedAt ?: 0L },
            )
        }
    }

    private fun withinRadius(listing: Listing, work: WorkLocation?, maxKm: Double?): Boolean {
        if (maxKm == null) return true
        val d = ComputeDistance.distanceTo(listing, work) ?: return true
        return d <= maxKm
    }
}