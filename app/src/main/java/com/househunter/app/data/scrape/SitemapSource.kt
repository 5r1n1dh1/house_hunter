package com.househunter.app.data.scrape

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI

/**
 * Sitemap reader for Stanza Living (co-living / PG operator).
 *
 * Stanza publishes **every** residence page in plain XML sitemaps, which means the app
 * can enumerate real listings instead of guessing search URLs and hoping a selector still
 * matches. That is the whole reason this exists: a guessed search page breaks silently,
 * whereas `sitemap-residence.xml` is a stable contract.
 *
 * Two operational realities shape the design:
 *
 *  - **The public endpoint is fragile.** `https://www.stanzaliving.com/sitemap.xml` and the
 *    per-section files are regularly overloaded or rate-limited. There is no single mirror to
 *    trust, so callers should try several (index URL, `sitemap-residence.xml` directly, and
 *    re-fetch later) and use whichever answers. Nothing here hides that: parsing never
 *    retries for you, it just never gets in the way.
 *  - **Parsing must stay pure.** Every function below takes XML it was handed and returns
 *    plain data, with no network, no Android types and no coroutines, so the interesting
 *    logic (which city/locality a URL belongs to) is unit-testable on the JVM.
 *
 * Network access is confined to the injected [fetcher] seam ([urlsFrom] / [sitemapsFrom]),
 * so tests drive the parser with canned XML and never touch a socket.
 */
class SitemapSource(private val fetcher: (String) -> String?) {

    /**
     * Reads a `<sitemapindex>` document and returns every child `<loc>`.
     *
     * Returns an empty list when handed a `<urlset>` instead: the index and the listing
     * documents share the same `<loc>` vocabulary, so mixing them up silently would feed
     * residence URLs to the sitemap crawler. Callers get an empty list and can fall back to
     * the next mirror instead of scraping nonsense.
     */
    fun discoverSitemaps(indexXml: String): List<String> {
        val doc = parse(indexXml) ?: return emptyList()
        if (rootTag(doc) == "urlset") return emptyList()
        return doc.select("sitemap > loc").mapNotNull(::locText)
    }

    /** Reads a `<urlset>` document and returns every `<loc>`. */
    fun urls(urlsetXml: String): List<String> {
        val doc = parse(urlsetXml) ?: return emptyList()
        return doc.select("loc").mapNotNull(::locText)
    }

    /**
     * Keeps only URLs matching a city and optional locality/micromarket slug.
     * Stanza URL shape: https://www.stanzaliving.com/`<city>`/`<slug-type>-in-<locality>`/`<gender>`/`<house>`
     * e.g. `bengaluru/pg-hostel-in-banashankari/male/kampala-house`
     *
     * Matching is token-based rather than substring-based on purpose. User input arrives as
     * "Sahakara Nagar" while the URL carries "sahakara-nagar", so dashes, underscores and
     * spaces are all folded to the same tokens; conversely a substring check would let city
     * "delhi" match "delhi-ncr" and locality "ban" match "baner", handing the app listings
     * from the wrong city. Tokens must line up exactly instead, so `greater-noida` is only
     * reachable as "Greater Noida" and never as "Noida".
     *
     * The locality is matched against the micromarket segment only (second path segment).
     * Scanning the whole path would match on the house slug, and Stanza names houses after
     * real cities ("london-house", "pune-house") — that would pull in every listing.
     */
    fun filter(urls: List<String>, city: String, locality: String? = null): List<String> {
        val wantedCity = tokens(city)
        if (wantedCity.isEmpty()) return emptyList()
        val wantedLocality = locality?.let(::tokens)?.takeIf { it.isNotEmpty() }

        return urls.filter { url ->
            val segments = pathSegments(url)
            startsWith(segments, wantedCity) &&
                (wantedLocality == null || containsTokens(segments.getOrNull(1).orEmpty(), wantedLocality))
        }
    }

    /**
     * Fetches [url] through the injected fetcher and parses it as a `<sitemapindex>`.
     *
     * This is the seam callers drive per mirror: try a few, take the first that answers.
     * A failed fetch yields an empty list rather than an exception so one bad mirror cannot
     * abort the sweep.
     */
    fun sitemapsFrom(url: String): List<String> = discoverSitemaps(fetcher(url).orEmpty())

    /**
     * Fetches [url] through the injected fetcher and parses it as a `<urlset>`.
     * Mirrors [sitemapsFrom] for listing documents; same empty-on-failure contract.
     */
    fun urlsFrom(url: String): List<String> = urls(fetcher(url).orEmpty())

    /** Jsoup is lenient, but a truncated/garbage body must never take the sweep down. */
    private fun parse(xml: String): Document? {
        if (xml.isBlank()) return null
        return runCatching { Jsoup.parse(xml, "", Parser.xmlParser()) }.getOrNull()
    }

    private fun rootTag(doc: Document): String = doc.firstElementChild()?.tagName()?.lowercase().orEmpty()

    private fun locText(element: Element): String? = element.text().trim().takeIf { it.isNotEmpty() }

    /** Path segments of an absolute URL; empty when the URL is unusable. */
    private fun pathSegments(url: String): List<String> {
        val path = runCatching { URI(url.trim()).path }.getOrNull() ?: return emptyList()
        return path.split('/').filter { it.isNotEmpty() }
    }

    /** Lower-cased alphanumeric tokens: "Sahakara Nagar" and "sahakara-nagar" collapse alike. */
    private fun tokens(value: String): List<String> =
        value.lowercase().split(NON_ALPHANUMERIC).filter { it.isNotEmpty() }

    /**
     * True when the path starts with exactly [wanted] tokens. The scan stops as soon as
     * enough tokens are seen and rejects an overrun, so "delhi" does not match "delhi-ncr".
     */
    private fun startsWith(segments: List<String>, wanted: List<String>): Boolean {
        val seen = mutableListOf<String>()
        for (segment in segments) {
            seen += tokens(segment)
            if (seen.size >= wanted.size) return seen.size == wanted.size && seen == wanted
        }
        return false
    }

    /** True when [wanted] appears as one contiguous run of whole tokens inside [segment]. */
    private fun containsTokens(segment: String, wanted: List<String>): Boolean {
        val available = tokens(segment)
        if (wanted.isEmpty() || wanted.size > available.size) return false
        return (0..available.size - wanted.size).any { start ->
            available.subList(start, start + wanted.size) == wanted
        }
    }

    private companion object {
        val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    }
}