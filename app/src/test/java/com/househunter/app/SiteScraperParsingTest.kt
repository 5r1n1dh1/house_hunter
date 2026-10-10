package com.househunter.app

import com.househunter.app.data.scrape.SelectorSet
import com.househunter.app.data.scrape.SiteConfig
import com.househunter.app.domain.model.UserPrefs
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteScraperParsingTest {

    private val config = SiteConfig(
        id = "fixture",
        name = "Fixture",
        baseUrl = "https://example.com",
        searchUrlTemplate = "https://example.com/rent?loc={LOCALITY}&page={PAGE}",
        selectors = SelectorSet(
            listContainer = "div.property-card",
            title = "h2.title",
            price = "div.price",
            location = "span.location",
            imageUrl = "img",
            detailUrl = "a.details-link",
        ),
    )

    private fun html(): String = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("listings_sample.html"),
    ) { "fixture missing" }.bufferedReader().use { it.readText() }

    @Test
    fun selectorsMatchExpectedCards() {
        val doc = Jsoup.parse(html(), config.baseUrl)
        val cards = doc.select(config.selectors.listContainer)
        assertEquals(2, cards.size)
        assertEquals("2 BHK Flat for Rent in Indiranagar", cards[0].selectFirst("h2.title")!!.text())
        assertTrue(cards[0].selectFirst("div.price")!!.text().contains("24,000"))
    }

    @Test
    fun searchUrlIsBuiltFromTemplate() {
        val scraper = com.househunter.app.data.scrape.SiteScraper(
            com.househunter.app.data.remote.HttpFetcher(okhttp3.OkHttpClient()),
        )
        val prefs = UserPrefs(
            locality = com.househunter.app.domain.model.OsmPlace(1, "Indiranagar", 8),
        )
        val url = scraper.buildUrl(config, prefs, page = 2)
        assertEquals("https://example.com/rent?loc=Indiranagar&page=2", url)
    }
}