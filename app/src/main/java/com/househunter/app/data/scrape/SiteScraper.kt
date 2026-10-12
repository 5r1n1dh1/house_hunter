package com.househunter.app.data.scrape

import com.househunter.app.data.remote.HttpFetcher
import com.househunter.app.data.remote.PageFetcher
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
open class SiteScraper @Inject constructor(
    private val fetcher: HttpFetcher,
    /**
     * Real-browser engine for CDNs that reject plain HTTP clients (99acres' Cloudflare
     * 403, housing.com's 406, OLX refusing connections). Injected unconditionally so the
     * engine stays a per-site config choice.
     */
    /**
     * Null only in plain-JVM unit tests (Kotlin default argument); Hilt always injects
     * a real instance, because Dagger supplies every constructor parameter.
     */
    private val webViewFetcher: PageFetcher? = null,
) {

    /** Builds the search URL from the template + current preferences. */
    fun buildUrl(config: SiteConfig, prefs: UserPrefs, page: Int = 1): String {
        val state = prefs.state?.name.orEmpty()
        val district = prefs.district?.name.orEmpty()
        val locality = prefs.locality?.name.orEmpty()
        // The dropdown yields OSM districts, which carry a cardinal suffix ("Bengaluru
        // North"). Property sites address the city without it, and "bengaluru-north"
        // matches nothing on their side, so {CITY_NAME} strips it.
        val cityName = REGION_SUFFIX.replace(district, "").trim().ifBlank { district }
        return config.searchUrlTemplate
            .replace("{STATE}", enc(state))
            .replace("{STATE_SLUG}", enc(slug(state)))
            .replace("{DISTRICT}", enc(district))
            .replace("{DISTRICT_SLUG}", enc(slug(district)))
            .replace("{CITY}", enc(slug(district)))
            .replace("{CITY_NAME}", enc(slug(cityName)))
            .replace("{LOCALITY}", enc(locality))
            .replace("{LOCALITY_SLUG}", enc(slug(locality)))
            .replace("{PAGE}", page.toString())
    }

    /**
     * Fetches a page with the engine the site declares. The WebView engine exists
     * for CDNs that reject plain HTTP clients (99acres/housing.com/OLX), so the
     * engine is a per-site config choice, never a hard-coded path.
     */
    private suspend fun fetchPage(
        config: SiteConfig,
        url: String,
        minIntervalMs: Long = config.rateLimitSeconds * 1000L,
    ): String? = engineFor(config).fetch(url = url, referer = config.baseUrl, minIntervalMs = minIntervalMs)

    private fun engineFor(config: SiteConfig): PageFetcher =
        if (config.needsBrowser) webViewFetcher ?: fetcher else fetcher

    open suspend fun scrape(config: SiteConfig, prefs: UserPrefs): List<Listing> = when (config.mode) {
        SourceMode.JSON -> scrapeJson(config, prefs)
        SourceMode.SITEMAP -> scrapeSitemap(config, prefs)
        else -> scrapeCards(config, prefs)
    }

    /** Classic CSS-card scraping over a paginated search page. */
    private suspend fun scrapeCards(config: SiteConfig, prefs: UserPrefs): List<Listing> {
        if (config.searchUrlTemplate.isBlank() || config.selectors.listContainer.isBlank()) return emptyList()

        val listings = mutableListOf<Listing>()
        val pages = config.pagination.maxPages.coerceIn(1, 10)

        for (page in 1..pages) {
            val url = buildUrl(config, prefs, page)
            val html = fetchPage(config, url) ?: break

            val doc = runCatching { Jsoup.parse(html, config.baseUrl) }.getOrNull() ?: break
            val cards = runCatching { doc.select(config.selectors.listContainer) }.getOrNull().orEmpty()
            if (cards.isEmpty()) break

            var found = 0
            val locality = prefs.locality?.name
            cards.forEach { card ->
                val listing = parseCard(card, config, prefs, url) ?: return@forEach
                // City-wide sources (99acres, OLX) need a locality narrowing step.
                if (config.localityFilter && !matchesLocality(listing, locality)) return@forEach
                listings += listing
                found++
            }
            if (found == 0 && config.localityFilter) continue

            val hasNext = config.pagination.nextPageSelector?.let {
                runCatching { doc.select(it).isNotEmpty() }.getOrDefault(false)
            } ?: (page < pages)
            if (!hasNext) break
        }
        return listings
    }

    /**
     * NoBroker-style: the search page embeds the listing array as JSON, while the
     * visible price is rendered client-side. Parsing the JSON is what made this
     * source return data at all.
     */
    private suspend fun scrapeJson(config: SiteConfig, prefs: UserPrefs): List<Listing> {
        val anchor = config.jsonAnchorKey ?: return emptyList()
        val pages = config.pagination.maxPages.coerceIn(1, 10)
        val listings = mutableListOf<Listing>()

        for (page in 1..pages) {
            val url = buildUrl(config, prefs, page)
            val html = fetchPage(config, url) ?: break
            val objects = EmbeddedJson.extractArray(html, anchor)
            if (objects.isEmpty()) break

            objects.mapNotNullTo(listings) { listingFromJson(it, config, prefs, url) }
            if (page >= pages) break
        }
        return listings
    }

    /**
     * Stanza-style: every property is listed in a sitemap, so enumerate URLs and
     * read each page's JSON-LD. No search endpoint has to be guessed.
     */
    private suspend fun scrapeSitemap(config: SiteConfig, prefs: UserPrefs): List<Listing> {
        val indexUrl = config.sitemapIndexUrl ?: return emptyList()
        val indexXml = fetchPage(config, indexUrl) ?: return emptyList()

        // All I/O happens here in the suspend context; the parser then works over a
        // plain map, because SitemapSource's fetcher is a non-suspending lambda.
        val downloaded = HashMap<String, String>()
        val source = SitemapSource { url -> downloaded[url] }
        val targets = LinkedHashSet<String>(source.urls(indexXml))
        for (mapUrl in source.discoverSitemaps(indexXml)) {
            val mapXml = fetchPage(config, mapUrl)
            if (mapXml.isNullOrBlank()) continue
            downloaded[mapUrl] = mapXml
            targets += source.urls(mapXml)
        }
        if (targets.isEmpty()) return emptyList()

        val city = config.jsonFieldMap["city"]
            ?: prefs.district?.name?.substringAfterLast(' ')?.lowercase()
            ?: prefs.state?.name?.lowercase()
            .orEmpty()
        val locality = prefs.locality?.name

        // Stanza has no residence in every locality (Sahakara Nagar has none), so a
        // locality miss falls back to the whole city rather than showing nothing.
        val byLocality = source.filter(targets.toList(), city = city, locality = locality)
        val filtered = byLocality
            .ifEmpty { source.filter(targets.toList(), city = city) }
            .take(MAX_SITEMAP_URLS)

        return filtered.mapNotNull { propertyUrl ->
            val html = fetchPage(config, propertyUrl) ?: return@mapNotNull null
            listingFromJsonLd(config, prefs, propertyUrl, html)
        }
    }

    /**
     * Maps an embedded JSON listing object to a [Listing] using the site's
     * `jsonFieldMap` (target field -> source field), so NoBroker-style payloads stay
     * data-driven rather than hard-coded to one site.
     */
    private fun listingFromJson(
        json: org.json.JSONObject,
        config: SiteConfig,
        prefs: UserPrefs,
        pageUrl: String,
    ): Listing? = with(EmbeddedJson) {
        val map = config.jsonFieldMap
        val rent = Normalizer.plausible(json.firstDouble(map["rent"] ?: "rent")) ?: return@with null
        val title = json.firstString(map["title"] ?: "propertyTitle") ?: return@with null

        // Razorent addresses its pages by an opaque id ("id":"b1f87a83-...") rather than a
        // link field, so `detailUrl` may be a template containing the id field's value.
        val rawDetail = json.firstString(map["detailUrl"] ?: "detailUrl")
        val detailUrl = Normalizer.resolveUrl(
            baseUrl = config.baseUrl,
            href = rawDetail?.takeIf { it.contains('{') }?.let { template ->
                val idKey = template.substringAfter('{').substringBefore('}').trim()
                json.firstString(idKey)?.let { template.replace("{$idKey}", it) }
            } ?: rawDetail,
        ) ?: pageUrl
        val image = json.firstString(map["image"] ?: "originalImageUrl")

        // Razorent publishes `"bhk": 2`; NoBroker publishes `"type":"BHK2"`. Accept both.
        val bhk = json.firstDouble(map["bhk"] ?: "bhk")?.toInt()
            ?: parseBhkCode(json.firstString(map["type"] ?: "type"))
            ?: Normalizer.parseBhk(title)
        val ownerName = json.firstString(map["owner"] ?: "ownerName")
        val builderName = json.firstString(map["builder"] ?: "builderName")
        val advertiser = ownerName ?: builderName

        Listing(
            id = listingId(config.id, detailUrl),
            siteId = config.id,
            siteName = config.name,
            title = title.trim().take(120),
            rent = rent,
            deposit = Normalizer.plausible(json.firstDouble("deposit") ?: json.firstDouble("securityDeposit")),
            maintenance = Normalizer.plausible(json.firstDouble("maintenance") ?: json.firstDouble("maintenanceCharges")),
            bhk = bhk,
            areaSqft = json.firstDouble(map["area"] ?: "propertySize"),
            furnishing = json.firstString(map["furnishing"] ?: "furnishingDesc"),
            propertyType = json.firstString(map["propertyType"] ?: "propertyType"),
            amenities = emptyList(),
            locality = json.firstString(map["locality"] ?: "localityTruncated")
                ?.takeIf { it.isNotBlank() }
                ?: prefs.locality?.name.orEmpty(),
            district = json.firstString(map["district"] ?: "district")
                ?: prefs.district?.name.orEmpty(),
            state = json.firstString(map["state"] ?: "state")
                ?: prefs.state?.name.orEmpty(),
            lat = json.firstDouble(map["lat"] ?: "lat"),
            lng = json.firstDouble(map["lng"] ?: "longitude"),
            hasRealLocation = json.has(map["lat"] ?: "lat") && json.has(map["lng"] ?: "longitude"),
            images = listOfNotNull(ImageUrl.normalize(image)),
            phone = Normalizer.sanitizePhone(json.firstString(map["phone"] ?: "phone")),
            detailUrl = detailUrl,
            description = json.firstString("description"),
            // NoBroker publishes epoch-millis `creationDate`; Razorent ISO-8601
            // `createdAt`. Neither site declares a `postedDate` key, so try the
            // known names rather than leaving every listing undated.
            postedAt = Normalizer.parsePostedAt(
                json.firstString(map["postedDate"] ?: "postedDate")
                    ?: json.firstString("creationDate")
                    ?: json.firstString("createdAt")
                    ?: json.firstString("active_date")
                    ?: json.firstString("updateDate")
                    ?: json.firstString(map["lastUpdated"] ?: "lastUpdated"),
            ),
            ownerName = advertiser,
            ownerType = when {
                !ownerName.isNullOrBlank() -> "Owner"
                !builderName.isNullOrBlank() -> "Builder"
                else -> null
            },
        )
    }

    private fun listingFromJsonLd(
        config: SiteConfig,
        prefs: UserPrefs,
        pageUrl: String,
        html: String,
    ): Listing? {
        val jsonLd = runCatching { JsonLd.parse(html) }.getOrNull()
        val rent = Normalizer.plausible(jsonLd?.price) ?: return null
        val title = jsonLd?.name ?: jsonLd?.description?.take(60) ?: return null
        // Stanza-style pages put the property photo in OpenGraph, not in the JSON-LD.
        val image = jsonLd?.image ?: Normalizer.capture(OG_IMAGE_REGEX, html)
        return Listing(
            id = listingId(config.id, pageUrl),
            siteId = config.id,
            siteName = config.name,
            title = title.substringBefore(" - ").trim().take(90),
            rent = rent,
            locality = prefs.locality?.name.orEmpty(),
            district = prefs.district?.name.orEmpty(),
            state = prefs.state?.name.orEmpty(),
            images = listOfNotNull(ImageUrl.normalize(image)),
            detailUrl = pageUrl,
            description = jsonLd?.description,
            ownerName = jsonLd?.owner,
            ownerType = jsonLd?.owner?.let { "Owner" },
        )
    }

    /**
     * Locality match over title/locality text. Tolerates "HSR Layout" vs "HSR",
     * which is how these sites spell the same place.
     */
    private fun matchesLocality(listing: Listing, locality: String?): Boolean {
        val needle = locality?.trim()?.lowercase()?.takeIf { it.length >= 3 } ?: return true
        val haystack = listOf(listing.title, listing.locality).joinToString(" ").lowercase()
        if (haystack.contains(needle)) return true
        val words = needle.split(' ', '-').filter { it.length >= 4 }
        return words.isNotEmpty() && words.any { haystack.contains(it) }
    }

    private fun parseBhkCode(code: String?): Int? {
        if (code.isNullOrBlank()) return null
        val digits = code.filter { it.isDigit() }
        return digits.toIntOrNull()?.takeIf { it in 1..20 }
    }

    /** Second phase: parse the detail page for the full description, images, phone. */
    suspend fun fetchDetail(config: SiteConfig, listing: Listing): Listing {
        val html = fetchPage(config, listing.detailUrl) ?: return listing

        val fields = config.detailFields
        val description = Normalizer.capture(fields.description, html)
        val amenities = Normalizer.capture(fields.amenities, html)
            ?.split(",", ";", "|")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val images = Normalizer.capture(fields.images, html)
            ?.split(",", ";", "|")
            ?.mapNotNull { ImageUrl.normalize(it.trim()) }
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
        val ownerTag = ownerTag(card, config)

        val detailUrl = Normalizer.resolveUrl(
            baseUrl = config.baseUrl,
            href = selectors.detailUrl?.let { selector ->
                // bengaluru.rent renders each listing as a bare <a class="listing" href="?pin=...">
                // with no wrapper and no nested anchor, so the card is its own link.
                if (selector == SELECTOR_SELF) attr(card, "@href") else text(card, selector) ?: attr(card, selector)
            },
        ) ?: pageUrl

        val rawTitle = selectors.title.let { text(card, it) } ?: jsonLd.name
        val rawPrice = selectors.price.let { text(card, it) }
        val rent = Normalizer.plausible(Normalizer.parseRent(rawPrice))
            ?: Normalizer.plausible(jsonLd.price)
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
                ImageUrl.normalize(
                    selectors.imageUrl?.let { text(card, it) ?: attr(card, it) }
                        ?.let { Normalizer.resolveUrl(config.baseUrl, it) }
                        ?: jsonLd.image,
                ),
            ),
            phone = Normalizer.sanitizePhone(selectors.phone?.let { text(card, it) }),
            detailUrl = detailUrl,
            description = jsonLd.description,
            // Card portals print free text ("2 days ago"), which `parsePostedAt`
            // deliberately does not guess at; a configured selector or a JSON-LD
            // `datePosted` still yields a real timestamp.
            postedAt = Normalizer.parsePostedAt(
                selectors.postedDate?.let { text(card, it) } ?: jsonLd.datePosted,
            ),
            ownerName = jsonLd.owner ?: ownerTag?.second,
            ownerType = ownerTag?.first ?: jsonLd.owner?.let { "Owner" },
        )
    }

    /**
     * MagicBricks renders "Owner: Rakshitha" / "Agent: Chaithanya Gowda" in a single
     * node, so splitting it yields both the role and the name in one request.
     */
    private fun ownerTag(card: Element, config: SiteConfig): Pair<String?, String?>? {
        val roleText = config.selectors.ownerTag?.let { text(card, it) }
        val nameText = config.selectors.ownerName?.let { text(card, it) }
        if (roleText.isNullOrBlank() && nameText.isNullOrBlank()) return null

        // Some sites put both in one node ("Owner: Name"), 99acres splits them
        // (pbL1 = "Dealer", pbL2 = the dealer's name).
        val combined = roleText?.split(":", limit = 2)?.map { it.trim() }
        val role = combined?.getOrNull(0)
            ?: roleText?.trim()?.takeIf { it.length <= 12 }
        val name = combined?.getOrNull(1) ?: nameText?.trim()
        return role?.takeIf { it.isNotEmpty() } to name?.takeIf { it.isNotEmpty() }
    }

    private fun attr(card: Element, selector: String): String? {
        if (selector.isBlank()) return null
        val element = runCatching { card.selectFirst(selector) }.getOrNull() ?: return null
        val name = selector.removePrefix("@")
        return runCatching { element.attr(name) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
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

/** Sitemap sources return every property on the site; cap the work per refresh. */
private const val MAX_SITEMAP_URLS = 60

/** Pseudo-selector meaning "the matched card element itself" rather than a descendant. */
private const val SELECTOR_SELF = ":self"

/** Trailing cardinal that OSM districts carry but property sites never use in city URLs. */
private val REGION_SUFFIX = Regex("[\\s-]+(North|South|East|West)$", RegexOption.IGNORE_CASE)

/** OpenGraph image, used when the JSON-LD carries no image (Stanza pages). */
private const val OG_IMAGE_REGEX =
    "<meta[^>]+(?:property|name)=\"og:image\"[^>]+content=\"([^\"]+)\""
