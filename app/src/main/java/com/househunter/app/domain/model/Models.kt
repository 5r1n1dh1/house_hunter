package com.househunter.app.domain.model

/**
 * A place used by the dropdowns: an OSM state/district relation, or a locality
 * resolved through geocoding (which also yields real coordinates).
 */
data class OsmPlace(
    val osmId: Long,
    val name: String,
    val adminLevel: Int,
    val lat: Double? = null,
    val lng: Double? = null,
)

data class WorkLocation(
    val label: String,
    val lat: Double,
    val lng: Double,
)

enum class Sort {
    PRICE_ASC,
    PRICE_DESC,
    NEAREST_WORK1,
    NEAREST_WORK2,
    NEWEST;

    val label: String
        get() = when (this) {
            PRICE_ASC -> "Price: low to high"
            PRICE_DESC -> "Price: high to low"
            NEAREST_WORK1 -> "Nearest to Work 1"
            NEAREST_WORK2 -> "Nearest to Work 2"
            NEWEST -> "Newest first"
        }
}

data class Filters(
    val rentMin: Double? = null,
    val rentMax: Double? = null,
    val bhk: Set<Int> = emptySet(),
    val maxKmWork1: Double? = null,
    val maxKmWork2: Double? = null,
    val contactOnly: Boolean = false,
) {
    val activeCount: Int
        get() = listOfNotNull(
            rentMin, rentMax, maxKmWork1, maxKmWork2,
        ).size + bhk.size + if (contactOnly) 1 else 0
}

data class UserPrefs(
    val state: OsmPlace? = null,
    val district: OsmPlace? = null,
    val locality: OsmPlace? = null,
    val work1: WorkLocation? = null,
    val work2: WorkLocation? = null,
    val filters: Filters = Filters(),
    val sort: Sort = Sort.PRICE_ASC,
    val onboarded: Boolean = false,
)

data class Listing(
    val id: String,
    val siteId: String,
    val siteName: String,
    val title: String,
    val rent: Double,
    val deposit: Double? = null,
    val maintenance: Double? = null,
    val bhk: Int? = null,
    val areaSqft: Double? = null,
    val furnishing: String? = null,
    val propertyType: String? = null,
    val amenities: List<String> = emptyList(),
    val locality: String = "",
    val district: String = "",
    val state: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val images: List<String> = emptyList(),
    val phone: String? = null,
    val detailUrl: String,
    val description: String? = null,
    val postedAt: Long? = null,
    val detailLoaded: Boolean = false,
    /**
     * Who is advertising: Owner, Agent or Builder. The distinction matters to most
     * renters, and both NoBroker (`ownerName`) and MagicBricks (`landlord.name` plus
     * an explicit owner/agent tag) publish it, so it is shown prominently on the card.
     */
    val ownerName: String? = null,
    val ownerType: String? = null,
)

data class SiteStatus(
    val siteId: String,
    val name: String,
    val count: Int = 0,
    val error: String? = null,
    val loading: Boolean = false,
)