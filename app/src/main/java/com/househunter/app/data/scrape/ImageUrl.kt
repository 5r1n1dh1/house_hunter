package com.househunter.app.data.scrape

import java.net.URI

/**
 * Pure helpers that turn scraped photo links into something an image loader can actually fetch.
 *
 * Two measured failures caused "listing photos never appear":
 *  1. NoBroker writes its photos into the listing HTML as *protocol-relative* URLs
 *     (`//assets.nobroker.in/8a9fa98277d9f1d10177da4ec18318f1_21192_473441_original.jpg`).
 *     Coil reads a leading `//` as a relative path with no host, so the request never reaches the
 *     CDN and nothing renders. [normalize] repairs those links before they get anywhere near Coil.
 *  2. Image CDNs (`assets.nobroker.in`, `img.staticmb.com`) reject requests that carry no
 *     `Referer` with `403 Forbidden`. [baseRefererFor] builds the listing origin that is sent as
 *     the `Referer` header for image requests.
 *
 * Intentionally free of Android and Coil imports so it stays unit-testable on the JVM.
 */
object ImageUrl {

    /** The only schemes an image loader can fetch over the network. */
    private val FETCHABLE_SCHEMES = setOf("http", "https")

    /** `scheme:` prefix of an absolute URL, e.g. `https:` or `javascript:`. */
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")

    /**
     * A bare, scheme-less `host/path` - at least one dot, optional port, optional path/query.
     * Keeps junk such as `/rent/2-bhk/12`, `not a url` or `index` from being turned into
     * `https://not a url`.
     */
    private val HOST_LIKE = Regex(
        "^[A-Za-z0-9](?:[A-Za-z0-9\\-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9\\-]*[A-Za-z0-9])?)+" +
            "(?::\\d{1,5})?(?:[/?#].*)?$",
    )

    /**
     * Turns a raw scraped image link into an absolute `http(s)` URL, or `null` when it cannot be
     * loaded at all.
     *
     * - `//assets.nobroker.in/x.jpg` -> `https://assets.nobroker.in/x.jpg` (the NoBroker case that
     *   broke every photo: protocol-relative links carry no scheme, so the loader had no host).
     * - `assets.nobroker.in/x.jpg` -> `https://assets.nobroker.in/x.jpg` (bare host, scheme dropped
     *   by some scrapers/JSON payloads).
     * - Absolute `http(s)` URLs are returned untouched.
     * - `null`/blank/whitespace, a non-fetchable scheme (`javascript:`, `data:`, `mailto:`,
     *   `file:`, ...), a URL without a host, or junk without a host returns `null` so the caller
     *   can render a placeholder instead of firing a guaranteed-to-fail request.
     */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null

        // Protocol-relative: inherit https, which is what the NoBroker CDN serves.
        if (trimmed.startsWith("//")) return accept("https:$trimmed")

        val scheme = SCHEME.find(trimmed)?.value?.dropLast(1)?.lowercase()
        return when {
            scheme == null -> if (HOST_LIKE.matches(trimmed)) accept("https://$trimmed") else null
            scheme in FETCHABLE_SCHEMES -> accept(trimmed)
            // javascript:, data:, file:, mailto:, content: ... never reachable over http(s).
            else -> null
        }
    }

    /**
     * Builds the `Referer` to send with image requests for a listing: the origin of its detail URL,
     * e.g. `https://www.nobroker.in/property/x/detail` -> `https://www.nobroker.in/`.
     *
     * Hotlink-protected CDNs (assets.nobroker.in, img.staticmb.com) answer `403` when the request
     * has no `Referer`, so every image request carries this origin. Path, query and fragment are
     * dropped - only `scheme://host/` survives - because a referer must identify the site, not the
     * page. Returns `null` when the detail URL is missing or unusable, in which case the caller
     * simply sends no referer.
     */
    fun baseRefererFor(detailUrl: String?): String? {
        val url = normalize(detailUrl) ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it in FETCHABLE_SCHEMES } ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port/"
    }

    /** Final gate: the candidate must parse as an absolute http(s) URL that has a host. */
    private fun accept(candidate: String): String? {
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in FETCHABLE_SCHEMES) return null
        return candidate.takeIf { !uri.host.isNullOrBlank() }
    }
}