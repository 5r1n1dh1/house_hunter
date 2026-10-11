package com.househunter.app

import com.househunter.app.data.scrape.EmbeddedJson
import com.househunter.app.data.scrape.Normalizer
import com.househunter.app.data.scrape.SelectorSet
import com.househunter.app.data.scrape.SiteConfig
import com.househunter.app.data.scrape.SiteScraper
import com.househunter.app.domain.model.OsmPlace
import com.househunter.app.domain.model.UserPrefs
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two sources unlocked by reading what each site already publishes:
 * Razorent's public JSON API and bengaluru.rent's server-rendered owner listings.
 *
 * The payload shapes below are copied from live responses, so a silent upstream
 * reshape shows up here rather than as an empty list on the user's phone.
 */
class RazorentApiTest {

    /** Shape of `GET api.razorent.com/properties?city=bengaluru`, trimmed to the fields used. */
    private val payload = """
        {
          "properties": [
            {
              "id": "b1f87a83-762d-4817-a85d-3826f9dd6d26",
              "title": "Quite pleasant place to stay",
              "rent": 45000,
              "bhk": 2,
              "locality": "Thaninsandara",
              "city": "Bengaluru",
              "propertyType": "FLAT",
              "listingType": "RENT",
              "furnishingType": "FULLY_FURNISHED",
              "area": 1109,
              "heroImageUrl": "https://res.cloudinary.com/dgzsc95r1/image/upload/v1/rentable.jpg",
              "latitude": 13.1315138,
              "longitude": 77.6825721,
              "securityDeposit": 135000,
              "maintenanceCharges": 6000,
              "owner": { "id": "02111d78", "name": "Roma", "phoneVerified": true }
            },
            {
              "id": "c2a0d4e1-1111-2222-3333-444455556666",
              "title": "2 BHK near park",
              "rent": 24000,
              "bhk": 2,
              "area": 1060,
              "heroImageUrl": "https://res.cloudinary.com/dgzsc95r1/image/upload/v1/other.jpg",
              "latitude": 12.9719,
              "longitude": 77.6412,
              "owner": { "id": "02111d79", "name": "Mohan" }
            },
            {
              "id": "d3b1e5f2-9999-8888-7777-666655554444",
              "title": "Plot for sale",
              "listingType": "SALE",
              "salePrice": 8500000,
              "owner": { "id": "02111d80", "name": "Dealer" }
            }
          ],
          "pagination": { "page": 1, "limit": 20, "total": 39, "hasMore": true }
        }
    """.trimIndent()

    @Test
    fun findsThePropertiesArrayWithoutKnowingTheEnvelopeKey() {
        // The response nests listings under "properties" inside an object, so the
        // extractor must walk past the envelope rather than expect a bare array.
        val rows = EmbeddedJson.extractArray(payload, "rent")
        assertEquals(3, rows.size)
        assertEquals("Quite pleasant place to stay", rows[0].getString("title"))
    }

    @Test
    fun mapsRentBhkGeoAndOwnerName() {
        val row = EmbeddedJson.extractArray(payload, "rent")[0]
        assertEquals(45000.0, row.firstDouble("rent")!!, 0.01)
        assertEquals(2, row.firstDouble("bhk")!!.toInt())
        assertEquals(1109.0, row.firstDouble("area")!!, 0.01)
        assertEquals(13.1315138, row.firstDouble("latitude")!!, 0.0001)
        assertEquals(135000.0, row.firstDouble("securityDeposit")!!, 0.01)
        // The advertiser is a nested object, which is why dotted paths exist.
        assertEquals("Roma", row.firstString("owner.name"))
        assertNull(row.firstString("owner"))
    }

    @Test
    fun saleListingsCarryNoRentAndAreDropped() {
        // A sale listing has salePrice and no rent; the plausibility check on rent is
        // what keeps ₹85 lakh sale prices out of a rental list.
        val rows = EmbeddedJson.extractArray(payload, "rent")
        val sale = rows.first { it.getString("title") == "Plot for sale" }
        assertNull(sale.firstDouble("rent"))
        assertEquals(8500000.0, sale.firstDouble("salePrice")!!, 0.01)
    }

    @Test
    fun detailUrlIsBuiltFromTheIdByTemplate() {
        val row = EmbeddedJson.extractArray(payload, "rent")[0]
        val template = "https://razorent.com/property/{id}"
        val built = template.replace("{id}", row.firstString("id")!!)
        assertEquals(
            "https://razorent.com/property/b1f87a83-762d-4817-a85d-3826f9dd6d26",
            built,
        )
    }
}

/**
 * bengaluru.rent server-renders each owner listing as a bare `<a class="listing">`
 * with no wrapper, so the card is its own link and the config uses the `:self`
 * pseudo-selector for `detailUrl`.
 */
class BengaluruRentCardTest {

    private val html = """
        <a class="listing" href="https://bengaluru.rent/?pin=03b142ff">
          <img src="https://mpnjtkqklmwczowhodfh.supabase.co/storage/v1/object/public/listing-photos/a.jpg">
          <span class="lr">₹18,000</span>
          <span class="lb">1BHK · whole flat · 6 photos</span>
          <span class="lv">View on map →</span>
        </a>
        <a class="listing" href="https://bengaluru.rent/?pin=57fa52e4">
          <img src="https://mpnjtkqklmwczowhodfh.supabase.co/storage/v1/object/public/listing-photos/b.jpg">
          <span class="lr">₹25,000/room</span>
          <span class="lb">3BHK · Vars Fantasy · room · furnished · gated · 6 photos</span>
          <span class="lv">View on map →</span>
        </a>
        <a class="listing" href="https://bengaluru.rent/?pin=63c00746">
          <img src="https://mpnjtkqklmwczowhodfh.supabase.co/storage/v1/object/public/listing-photos/c.jpg">
          <span class="lr">₹1.20L</span>
          <span class="lb">3BHK · kalpana pride homes · whole flat</span>
          <span class="lv">View on map →</span>
        </a>
    """.trimIndent()

    private val card = Jsoup.parse(html, "https://bengaluru.rent")
        .select("a.listing")

    @Test
    fun everyListingIsAMatchedCard() {
        assertEquals(3, card.size)
    }

    @Test
    fun priceSpanParsesIncludingPerRoomAndLakhForms() {
        val prices = card.map { Normalizer.parseRent(it.selectFirst("span.lr")!!.text()) }
        assertEquals(18000.0, prices[0]!!, 0.01)
        // A shared room is quoted per room; the number is still the monthly rent.
        assertEquals(25000.0, prices[1]!!, 0.01)
        assertEquals(120000.0, prices[2]!!, 0.01)
        prices.forEach { assertTrue("plausible rent expected", Normalizer.plausible(it) != null) }
    }

    @Test
    fun labelSpanCarriesBhkSocietyAndFurnishing() {
        val label = card[1].selectFirst("span.lb")!!.text()
        assertEquals(3, Normalizer.parseBhk(label))
        assertTrue(label.contains("Vars Fantasy"))
        assertTrue(label.contains("furnished"))
    }

    @Test
    fun theCardIsItsOwnLink() {
        // `detailUrl` is the `:self` pseudo-selector: read href off the matched element.
        card.forEach { c ->
            val href = c.attr("href")
            assertTrue(href.startsWith("https://bengaluru.rent/?pin="))
        }
        assertEquals("https://bengaluru.rent/?pin=03b142ff", card.first().attr("href"))
    }
}

/**
 * `{CITY}` expands to the district slug, which carries a cardinal suffix ("Bengaluru
 * North"). Property sites address the city without it and return nothing for
 * "bengaluru-north", so `{CITY_NAME}` exists to strip it.
 */
class CityUrlTemplatingTest {

    private val prefs = UserPrefs(
        state = OsmPlace(2019939, "Karnataka", 4),
        district = OsmPlace(2020588, "Bengaluru North", 6),
        locality = OsmPlace(552430276, "Sahakara Nagar", 8, 13.0621, 77.5801),
    )

    private fun config(template: String) = SiteConfig(
        id = "t",
        name = "T",
        baseUrl = "https://example.com",
        searchUrlTemplate = template,
        selectors = SelectorSet(listContainer = "div", title = "h1", price = "span"),
    )

    private fun build(template: String) = SiteScraper().buildUrl(config(template), prefs)

    @Test
    fun cityNameDropsTheCardinalSuffix() {
        assertEquals(
            "https://api.razorent.com/properties?city=bengaluru&limit=50",
            build("https://api.razorent.com/properties?city={CITY_NAME}&limit=50"),
        )
    }

    @Test
    fun cityStillCarriesTheFullDistrictForSitesThatWantIt() {
        assertEquals(
            "https://example.com/bengaluru-north",
            build("https://example.com/{CITY}"),
        )
    }

    @Test
    fun localitySlugMatchesBengaluruRentPathStyle() {
        assertEquals("https://bengaluru.rent/sahakara-nagar", build("https://bengaluru.rent/{LOCALITY_SLUG}"))
    }
}

/** The bare-`L` lakh shorthand, seen on bengaluru.rent and common in local ads. */
class LakhAbbreviationTest {

    @Test
    fun parsesAllSeenLakhSpellings() {
        assertEquals(120000.0, Normalizer.parseRent("₹1.20L")!!, 0.01)
        assertEquals(250000.0, Normalizer.parseRent("₹2.5 Lac")!!, 0.01)
        assertEquals(180000.0, Normalizer.parseRent("1.8 Lakh")!!, 0.01)
        assertEquals(90000.0, Normalizer.parseRent("₹90k")!!, 0.01)
    }

    @Test
    fun doesNotInventALakhWhereThereIsNone() {
        // "3 BHK" and "2nd floor" contain letters but no lakh marker; a stray
        // match here would inflate a rent by 100,000x.
        assertNull(Normalizer.parseRent("3 BHK Flat"))
        assertNull(Normalizer.parseRent("2nd floor, 1100 sqft"))
        assertNull(Normalizer.parseRent("1"))
    }
}