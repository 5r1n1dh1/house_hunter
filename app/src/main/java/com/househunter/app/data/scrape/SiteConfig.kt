package com.househunter.app.data.scrape

import org.json.JSONObject

/** CSS selectors used to parse a search-results page. `listContainer` matches each result card. */
data class SelectorSet(
    val listContainer: String,
    val title: String,
    val price: String,
    val bhk: String? = null,
    val areaSqft: String? = null,
    val location: String? = null,
    val imageUrl: String? = null,
    val detailUrl: String? = null,
    val phone: String? = null,
    val postedDate: String? = null,
    val furnishing: String? = null,
    /** Optional element holding "Owner" / "Agent" and the advertiser's name. */
    val ownerTag: String? = null,
)

/** Regexes (capture group 1) applied to the detail page. */
data class DetailFields(
    val description: String? = null,
    val amenities: String? = null,
    val images: String? = null,
    val phone: String? = null,
    val deposit: String? = null,
    val maintenance: String? = null,
)

data class Pagination(
    val maxPages: Int = 1,
    val nextPageSelector: String? = null,
    val pageParam: String? = null,
)

/**
 * How a site's listings are discovered.
 *
 *  - [MODE_CARDS] — CSS cards on a search page (classic scraping).
 *  - [MODE_JSON] — listings live in an embedded JSON array; [jsonAnchorKey] names a
 *    field present on every listing object (NoBroker uses "rent").
 *  - [MODE_SITEMAP] — every property URL is enumerated from a sitemap, then each
 *    page yields one listing via its JSON-LD (Stanza Living).
 */
object SourceMode {
    const val CARDS = "cards"
    const val JSON = "json"
    const val SITEMAP = "sitemap"
}

data class SiteConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val searchUrlTemplate: String,
    val selectors: SelectorSet,
    val detailFields: DetailFields = DetailFields(),
    val pagination: Pagination = Pagination(),
    val rateLimitSeconds: Int = 6,
    val enabled: Boolean = false,
    val isCustom: Boolean = false,
    val requiresManual: Boolean = false,
    val note: String = "",
    val mode: String = SourceMode.CARDS,
    val jsonAnchorKey: String? = null,
    val jsonFieldMap: Map<String, String> = emptyMap(),
    val sitemapIndexUrl: String? = null,
    val sitemapPattern: String? = null,
    val engine: String = ENGINE_HTTP,
    val searchUrlFallback: String? = null,
) {
    /** True when the site can only be read through a real browser engine. */
    val needsBrowser: Boolean get() = engine == ENGINE_WEBVIEW

    /**
     * Where to send the user when scraping cannot be trusted (bot-blocked sites).
     * Templates still carrying placeholders are useless in a browser, so they fall
     * back to the site root.
     */
    val browserUrl: String
        get() = searchUrlFallback?.takeIf { it.isNotBlank() }
            ?: searchUrlTemplate.takeIf { it.isNotBlank() && !it.contains('{') }
            ?: baseUrl

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("baseUrl", baseUrl)
        put("searchUrlTemplate", searchUrlTemplate)
        put("selectors", JSONObject().apply {
            put("listContainer", selectors.listContainer)
            put("title", selectors.title)
            put("price", selectors.price)
            selectors.bhk?.let { put("bhk", it) }
            selectors.areaSqft?.let { put("areaSqft", it) }
            selectors.location?.let { put("location", it) }
            selectors.imageUrl?.let { put("imageUrl", it) }
            selectors.detailUrl?.let { put("detailUrl", it) }
            selectors.phone?.let { put("phone", it) }
            selectors.postedDate?.let { put("postedDate", it) }
            selectors.furnishing?.let { put("furnishing", it) }
            selectors.ownerTag?.let { put("ownerTag", it) }
        })
        put("detailFields", JSONObject().apply {
            detailFields.description?.let { put("description", it) }
            detailFields.amenities?.let { put("amenities", it) }
            detailFields.images?.let { put("images", it) }
            detailFields.phone?.let { put("phone", it) }
            detailFields.deposit?.let { put("deposit", it) }
            detailFields.maintenance?.let { put("maintenance", it) }
        })
        put("pagination", JSONObject().apply {
            put("maxPages", pagination.maxPages)
            pagination.nextPageSelector?.let { put("nextPageSelector", it) }
            pagination.pageParam?.let { put("pageParam", it) }
        })
        put("rateLimitSeconds", rateLimitSeconds)
        put("enabled", enabled)
        put("requiresManual", requiresManual)
        put("note", note)
        put("mode", mode)
        jsonAnchorKey?.let { put("jsonAnchorKey", it) }
        if (jsonFieldMap.isNotEmpty()) {
            put(
                "jsonFieldMap",
                JSONObject().apply { jsonFieldMap.forEach { (k, v) -> put(k, v) } },
            )
        }
        sitemapIndexUrl?.let { put("sitemapIndexUrl", it) }
        sitemapPattern?.let { put("sitemapPattern", it) }
        put("engine", engine)
        searchUrlFallback?.let { put("searchUrlFallback", it) }
    }

    companion object {
        /** Plain HTTP is the default; `webview` opts into the real-browser engine. */
        const val ENGINE_HTTP = "http"
        const val ENGINE_WEBVIEW = "webview"

        fun fromJson(json: JSONObject): SiteConfig? {
            return runCatching {
                val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
                val s = json.optJSONObject("selectors") ?: return null
                val d = json.optJSONObject("detailFields")
                val p = json.optJSONObject("pagination")
                SiteConfig(
                    id = id,
                    name = json.optString("name", id),
                    baseUrl = json.optString("baseUrl"),
                    searchUrlTemplate = json.optString("searchUrlTemplate"),
                    selectors = SelectorSet(
                        listContainer = s.optString("listContainer"),
                        title = s.optString("title"),
                        price = s.optString("price"),
                        bhk = s.optStringOrNull("bhk"),
                        areaSqft = s.optStringOrNull("areaSqft"),
                        location = s.optStringOrNull("location"),
                        imageUrl = s.optStringOrNull("imageUrl"),
                        detailUrl = s.optStringOrNull("detailUrl"),
                        phone = s.optStringOrNull("phone"),
                        postedDate = s.optStringOrNull("postedDate"),
                        furnishing = s.optStringOrNull("furnishing"),
                        ownerTag = s.optStringOrNull("ownerTag"),
                    ),
                    detailFields = DetailFields(
                        description = d?.optStringOrNull("description"),
                        amenities = d?.optStringOrNull("amenities"),
                        images = d?.optStringOrNull("images"),
                        phone = d?.optStringOrNull("phone"),
                        deposit = d?.optStringOrNull("deposit"),
                        maintenance = d?.optStringOrNull("maintenance"),
                    ),
                    pagination = Pagination(
                        maxPages = p?.optInt("maxPages", 1) ?: 1,
                        nextPageSelector = p?.optStringOrNull("nextPageSelector"),
                        pageParam = p?.optStringOrNull("pageParam"),
                    ),
                    rateLimitSeconds = json.optInt("rateLimitSeconds", 6),
                    enabled = json.optBoolean("enabled", false),
                    isCustom = json.optBoolean("isCustom", false),
                    requiresManual = json.optBoolean("requiresManual", false),
                    note = json.optString("note", ""),
                    mode = json.optString("mode", SourceMode.CARDS),
                    jsonAnchorKey = json.optStringOrNull("jsonAnchorKey"),
                    jsonFieldMap = json.optJSONObject("jsonFieldMap")?.let { obj ->
                        obj.keys().asSequence().associateWith { obj.optString(it) }
                    }.orEmpty(),
                    sitemapIndexUrl = json.optStringOrNull("sitemapIndexUrl"),
                    sitemapPattern = json.optStringOrNull("sitemapPattern"),
                    engine = json.optString("engine", ENGINE_HTTP),
                    searchUrlFallback = json.optStringOrNull("searchUrlFallback"),
                )
            }.getOrNull()
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}