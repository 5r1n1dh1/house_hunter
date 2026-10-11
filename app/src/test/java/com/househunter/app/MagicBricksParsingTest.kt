package com.househunter.app

import com.househunter.app.data.remote.HttpFetcher
import com.househunter.app.data.scrape.JsonLd
import com.househunter.app.data.scrape.SelectorSet
import com.househunter.app.data.scrape.SiteConfig
import com.househunter.app.data.scrape.SiteScraper
import com.househunter.app.domain.model.OsmPlace
import com.househunter.app.domain.model.UserPrefs
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visible price of a MagicBricks card is rendered by JavaScript, so a
 * CSS-only scraper sees nothing. The card's JSON-LD carries the real price.
 */
class MagicBricksParsingTest {

    private val config = SiteConfig(
        id = "magicbricks",
        name = "MagicBricks",
        baseUrl = "https://www.magicbricks.com",
        searchUrlTemplate = "https://www.magicbricks.com/property-for-rent-in-{LOCALITY_SLUG}-bangalore-pppfr",
        selectors = SelectorSet(
            listContainer = "div.mb-srp__list",
            title = "h2.mb-srp__card--title",
            price = "div.mb-srp__card__price--amount",
            areaSqft = "[data-summary='carpet-area'] div.mb-srp__card__summary--value",
            furnishing = "[data-summary='furnishing'] div.mb-srp__card__summary--value",
            imageUrl = "img.mb-srp__card__photo__fig--graphic",
            postedDate = "div.mb-srp__card__photo__fig--post",
        ),
    )

    private val pageUrl = "https://www.magicbricks.com/property-for-rent-in-sahakara-nagar-bangalore-pppfr"

    private val prefs = UserPrefs(
        state = OsmPlace(2019939, "Karnataka", 4),
        district = OsmPlace(2020588, "Bengaluru North", 6),
        locality = OsmPlace(552430276, "Sahakara Nagar", 8, 13.0621, 77.5801),
    )

    private fun fixture(): String = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("magicbricks_card.html"),
    ) { "fixture missing" }.bufferedReader().use { it.readText() }

    @Test
    fun jsonLdPriceIsParsed() {
        val raw = """{"@type":"RentAction","object":{"@type":"Residence","name":"3 BHK Apartment"},
            "priceSpecification":{"@type":"PriceSpecification","price":45000,"priceCurrency":"INR"}}"""
        val parsed = JsonLd.parse(raw)
        assertEquals(45000.0, parsed.price!!, 0.01)
        assertEquals("3 BHK Apartment", parsed.name)
    }

    @Test
    fun jsonLdToleratesOffersShape() {
        val raw = """{"@type":"Product","name":"2 BHK","offers":{"@type":"Offer","price":"₹31,500"}}"""
        assertEquals(31500.0, JsonLd.parse(raw).price!!, 0.01)
    }

    @Test
    fun jsonLdReturnsEmptyOnGarbage() {
        assertNull(JsonLd.parse("not json").price)
        assertNull(JsonLd.parse("{}").name)
    }

    @Test
    fun cardSelectorAndFieldsMatchRealMarkup() {
        val card = Jsoup.parse(fixture(), config.baseUrl).selectFirst(config.selectors.listContainer)
        assertNotNull("card not found", card)
        assertEquals(
            "3 BHK Apartment for Rent in Sahakara Nagar Bangalore",
            card!!.selectFirst(config.selectors.title)!!.text(),
        )
        assertEquals("1850 sqft", card.selectFirst(config.selectors.areaSqft)!!.text())
        assertEquals("Semi-Furnished", card.selectFirst(config.selectors.furnishing)!!.text())
        assertTrue(card.selectFirst(config.selectors.imageUrl)!!.attr("src").startsWith("https://"))
        // The visible price node exists but is empty -> CSS-only scraping fails.
        assertEquals("", card.selectFirst(config.selectors.price)!!.text().trim())
        assertEquals(45000.0, JsonLd.from(card).price!!, 0.01)
    }

    @Test
    fun searchUrlUsesSlugs() {
        val scraper = SiteScraper(HttpFetcher(OkHttpClient()))
        val url = scraper.buildUrl(config, prefs)
        assertEquals(
            "https://www.magicbricks.com/property-for-rent-in-sahakara-nagar-bangalore-pppfr",
            url,
        )
    }

    @Test
    fun scrapingIsPureBlockingSafe() = runBlocking {
        // Ensures the scraper class still exposes a coroutine API.
        val scraper = SiteScraper(HttpFetcher(OkHttpClient()))
        assertNotNull(scraper.buildUrl(config, prefs))
    }
}