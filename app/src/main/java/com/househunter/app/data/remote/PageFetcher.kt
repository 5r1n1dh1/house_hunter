package com.househunter.app.data.remote

/**
 * Fetches a page's HTML/JSON.
 *
 * Two engines implement this:
 *  - [HttpFetcher] — plain OkHttp (fast, the default).
 *  - `WebViewFetcher` — an offscreen Android WebView, which passes the TLS-fingerprint
 *    bot checks that Cloudflare-style WAFs apply (99acres, housing.com, OLX).
 *
 * The scraper only ever talks to this interface, so a site's engine is a config
 * choice rather than a code change.
 */
interface PageFetcher {
    suspend fun fetch(
        url: String,
        referer: String? = null,
        minIntervalMs: Long = DEFAULT_INTERVAL_MS,
    ): String?

    companion object {
        const val DEFAULT_INTERVAL_MS = 5_000L
    }
}