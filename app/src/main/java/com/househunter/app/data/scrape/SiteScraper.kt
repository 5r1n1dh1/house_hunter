package com.househunter.app.data.scrape

import com.househunter.app.data.remote.HttpFetcher
import com.househunter.app.domain.model.Listing
import com.househunter.app.domain.model.UserPrefs
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Config-driven scraper: every site is described by a [SiteConfig], so adding a
 * new site never requires new code.
 */
@Singleton
class SiteScraper @Inject constructor(private val fetcher: HttpFetcher) {

    /** Builds the search URL from the template + current preferences. */
    fun buildUrl(config: SiteConfig, prefs: UserPrefs, page: Int = 1): String {
        val state = prefs.state?.name.orEmpty()
        val district = prefs.district?.name.orEmpty()
        val locality = prefs.locality?.name.orEmpty()
        return config.searchUrlTemplate
            .replace("{STATE}", enc(state))
            .replace("{STATE_SLUG}", enc(slug(state)))
            .replace("{DISTRICT}", enc(district))
            .replace("{DISTRICT_SLUG}", enc(slug(district)))
            .replace("{CITY}", enc(slug(district)))
            .replace("{LOCALITY}", enc(locality))
            .replace("{LOCALITY_SLUG}", enc(slug(locality)))
            .replace("{PAGE}", page.toString())
    }

    suspend fun scrape(config: SiteConfig, prefs: UserPrefs): List<Listing> {
        if (config.searchUrlTemplate.isBlank() || config.selectors.listContainer.isBlank()) return emptyList()

        val listings = mutableListOf<Listing>()
        val pages = config.pagination.maxPages.coerceIn(1, 10)

        for (page in 1..pages) {
            val url = buildUrl(config, prefs, page)
            val html = fetcher.get(
                url = url,
                referer = config.baseUrl,
                minIntervalMs = config.rateLimitSeconds * 1000L,
            ) ?: break

            val doc = runCatching { Jsoup.parse(html, config.baseUrl) }.getOrNull() ?: break
            val cards = runCatching { doc.select(config.selectors.listContainer) }.getOrNull().orEmpty()
            if (cards.isEmpty()) break

            var found = 0
            cards.forEach { card ->
                parseCard(card, config, prefs, url)?.let {
                    listings += it
                    found++
                }
            }
            if (found == 0) break

            val hasNext = config.pagination.nextPageSelector?.let {
                runCatching { doc.select(it).isNotEmpty() }.getOrDefault(false)
            } ?: (page < pages)
            if (!hasNext) break
        }
        return listings
    }

    /** Second phase: parse the detail page for the full description, images, phone. */
    suspend fun fetchDetail(config: SiteConfig, listing: Listing): Listing {
        val html = fetcher.get(
            url = listing.detailUrl,
            referer = config.baseUrl,
            minIntervalMs = config.rateLimitSeconds * 1000L,
        ) ?: return listing

        val fields = config.detailFields
        val description = Normalizer.capture(fields.description, html)
        val amenities = Normalizer.capture(fields.amenities, html)
            ?.split(",", ";", "|")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val images = Normalizer.capture(fields.images, html)
            ?.split(",", ";", "|")
            ?.map { it.trim() }
            ?.filter { it.startsWith("http") }
            .orEmpty()
        val phone = Normalizer.sanitizePhone(
            Normalizer.capture(fields.phone, html) ?: Normalizer.capture(fields.phone, listing.detailUrl),
        )
        val deposit = Normalizer.parseRent(Normalizer.capture(fields.deposit, html))
        val maintenance = Normalizer.parseRent(Normalizer.capture(fields.maintenance, html))

        return listing.copy(
            description = description ?: listing.description,
            amenities = if (amenities.isNotEmpty()) amenities else listing.amenities,
            images = if (images.isNotEmpty()) images else listing.images,
            phone = phone ?: listing.phone,
            deposit = deposit ?: listing.deposit,
            maintenance = maintenance ?: listing.maintenance,
            detailLoaded = true,
        )
    }

    /**
     * Parses one result card.
     *
     * Price/title/image fall back to the card's **schema.org JSON-LD** block, which
     * many sites (MagicBricks, Stanza, ...) render server-side even when the visible
     * price is injected by JavaScript. Without this, modern sites look "empty".
     * When a card exposes no link, the page URL is used so "open original listing"
     * always has somewhere to go.
     */
    private fun parseCard(
        card: Element,
        config: SiteConfig,
        prefs: UserPrefs,
        pageUrl: String,
    ): Listing? {
        val selectors = config.selectors
        val jsonLd = JsonLd.from(card)

        val detailUrl = Normalizer.resolveUrl(
            baseUrl = config.baseUrl,
            href = selectors.detailUrl?.let { text(card, it) ?: attr(card, it) },
        ) ?: pageUrl

        val rawTitle = selectors.title.let { text(card, it) } ?: jsonLd.name
        val rawPrice = selectors.price.let { text(card, it) }
        val rent = Normalizer.parseRent(rawPrice)
            ?: jsonLd.price
            ?: return null
        val title = rawTitle?.takeIf { it.isNotBlank() } ?: "Rental listing"

        return Listing(
            id = listingId(config.id, detailUrl + "|" + (jsonLd.name ?: title)),
            siteId = config.id,
            siteName = config.name,
            title = title,
            rent = rent,
            deposit = null,
            maintenance = null,
            bhk = Normalizer.parseBhk(selectors.bhk?.let { text(card, it) } ?: card.text()),
            areaSqft = Normalizer.parseAreaSqft(selectors.areaSqft?.let { text(card, it) } ?: card.text()),
            furnishing = selectors.furnishing?.let { text(card, it) },
            propertyType = null,
            amenities = emptyList(),
            locality = selectors.location?.let { text(card, it) } ?: prefs.locality?.name.orEmpty(),
            district = prefs.district?.name.orEmpty(),
            state = prefs.state?.name.orEmpty(),
            lat = null,
            lng = null,
            images = listOfNotNull(
                selectors.imageUrl?.let { text(card, it) ?: attr(card, it) }
                    ?.let { Normalizer.resolveUrl(config.baseUrl, it) }
                    ?: jsonLd.image,
            ),
            phone = Normalizer.sanitizePhone(selectors.phone?.let { text(card, it) }),
            detailUrl = detailUrl,
            description = jsonLd.description,
        )
    }

    private fun attr(card: Element, selector: String): String? {
        if (selector.isBlank()) return null
        val element = runCatching { card.selectFirst(selector) }.getOrNull() ?: return null
        return runCatching { element.attr("href") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun text(card: Element, selector: String): String? {
        if (selector.isBlank()) return null
        val element = runCatching { card.selectFirst(selector) }.getOrNull() ?: return null
        val value = runCatching { element.text() }.getOrNull()?.trim()
        return value?.takeIf { it.isNotEmpty() }
    }

    private fun listingId(siteId: String, detailUrl: String): String {
        val seed = "$siteId|$detailUrl"
        // Stable, collision-resistant enough for an in-memory dedupe key.
        return UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    }

    private fun enc(value: String): String =
        runCatching { URLEncoder.encode(value, "UTF-8").replace("+", "%20") }.getOrDefault("")

    private fun slug(value: String): String =
        value.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}