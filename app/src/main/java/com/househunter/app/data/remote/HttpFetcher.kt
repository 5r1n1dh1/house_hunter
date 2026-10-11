package com.househunter.app.data.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Polite HTTP client used by the scraper:
 *  - per-host rate limiting (default 5s between requests),
 *  - robots.txt respected (fetched once per host, in-memory only),
 *  - identifying User-Agent and a site Referer (hotlink-protected CDNs).
 */
@Singleton
class HttpFetcher @Inject constructor(private val client: OkHttpClient) : PageFetcher {

    private val lastRequestAt = ConcurrentHashMap<String, Long>()
    private val robotsCache = ConcurrentHashMap<String, List<String>>()
    private val hostMutexes = ConcurrentHashMap<String, Mutex>()

    /** Returns the response body, or null when blocked/failed. */
    override suspend fun fetch(
        url: String,
        referer: String?,
        minIntervalMs: Long,
    ): String? {
        val host = runCatching { URI(url).host }.getOrNull() ?: return null
        if (!isAllowed(url)) return null

        hostMutexes.getOrPut(host) { Mutex() }.withLock {
            throttle(host, minIntervalMs)
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                // This header family is what makes 99acres and OLX answer at all:
                // with only a User-Agent their WAFs reply 403/406.
                .header("sec-ch-ua", "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\"")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"Linux\"")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Upgrade-Insecure-Requests", "1")
            if (referer != null) builder.header("Referer", referer)

            return runCatching {
                client.newCall(builder.build()).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                }
            }.getOrNull()
        }
    }

    private suspend fun throttle(host: String, minIntervalMs: Long) {
        val last = lastRequestAt[host] ?: 0L
        val now = System.currentTimeMillis()
        val wait = (last + minIntervalMs) - now
        if (wait > 0) delay(wait)
        lastRequestAt[host] = System.currentTimeMillis()
    }

    private suspend fun isAllowed(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        val disallows = robotsCache.getOrPut(host) { fetchRobots(host) }
        if (disallows.isEmpty()) return true
        val path = runCatching { URI(url).path ?: "/" }.getOrDefault("/")
        return disallows.none { it.isNotBlank() && path.startsWith(it) }
    }

    private fun fetchRobots(host: String): List<String> = runCatching {
        val request = Request.Builder()
            .url("https://$host/robots.txt")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val body = response.body?.string().orEmpty()
            parseDisallows(body)
        }
    }.getOrDefault(emptyList())

    private fun parseDisallows(text: String): List<String> {
        val result = mutableListOf<String>()
        var appliesToUs = false
        var inStarGroup = false
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            when (key) {
                "user-agent" -> {
                    if (inStarGroup) appliesToUs = false
                    appliesToUs = value == "*" || value.contains(HOUSE_HUNTER_TOKEN, ignoreCase = true)
                    inStarGroup = appliesToUs
                }
                "disallow" -> if (appliesToUs && value.isNotEmpty()) result += value
            }
        }
        return result
    }

    companion object {
        /** Identifying UA for APIs that demand one (Overpass, Nominatim). */
        const val USER_AGENT =
            "HouseHunterBot/1.0 (personal rental aggregator; +https://github.com/5r1n1dh1/house_hunter)"

        /**
         * Browser-shaped UA for property sites: their CDNs reject anything that
         * does not look like a real Chrome navigation (99acres 403, OLX connection
         * refused), and this header set is what gets them to answer.
         */
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Safari/537.36"
        private const val HOUSE_HUNTER_TOKEN = "househunter"
        private const val DEFAULT_INTERVAL_MS = PageFetcher.DEFAULT_INTERVAL_MS
    }
}