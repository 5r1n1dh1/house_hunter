package com.househunter.app.data.scrape

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pulls the listing payload that some sites hide inside a `<script>` block instead of
 * rendering into the DOM.
 *
 * NoBroker was the driver here: its result cards are drawn by JavaScript, so a CSS-only
 * scraper sees an empty page, but the server response embeds the whole result set as
 *
 * ```html
 * <script>window.__INITIAL_STATE__ = {"properties":[{"propertyTitle":"2 BHK ...","rent":28000,
 *  "type":"BHK2","detailUrl":"/property/...","originalImageUrl":"//assets.nobroker.in/...jpg"}]};</script>
 * ```
 *
 * The shapes were reverse-engineered from live responses, so every step here is deliberately
 * forgiving: a page that renames a key or ships two blobs must degrade to "no listings"
 * rather than crash a background refresh. Nothing in this object touches Android or the
 * network, so it is unit-testable on a plain JVM.
 */
object EmbeddedJson {

    /**
     * Returns the objects of the first JSON array in [html] that *encloses* [anchorKey] -
     * e.g. `extractArray(html, "rent")` recovers the NoBroker `properties` array.
     *
     * The page is not JSON, so we cannot parse it directly. Instead the document is scanned
     * once with a string-aware bracket walker to find every balanced `[...]` span, and the
     * spans that contain the anchor are parsed with org.json in order of appearance.
     * Anchoring is what disambiguates pages that embed several unrelated arrays
     * (navigation menus, ad configs, analytics blobs) next to the listings.
     *
     * Returns an empty list - never throws - when the anchor is absent, when the candidate
     * slice is malformed, or when the array holds no objects.
     */
    fun extractArray(html: String, anchorKey: String): List<JSONObject> {
        if (html.isEmpty() || anchorKey.isBlank()) return emptyList()
        val needle = "\"$anchorKey\""
        val anchor = html.indexOf(needle)
        if (anchor < 0) return emptyList()

        // Spans are ordered by their opening bracket, so the outermost candidate that wraps
        // the anchor is tried first; that is the listing array, not an array *inside* a card.
        for (span in arraySpans(html)) {
            if (span.first > anchor) break
            if (anchor + needle.length > span.last) continue
            val objects = parseObjects(html.substring(span.first, span.last + 1))
            if (objects.any { it.has(anchorKey) }) return objects
        }
        return emptyList()
    }

    /**
     * First key in [keys] that holds a non-blank string or number, trimmed.
     *
     * The same listing field is spelled differently across (and even within) sites -
     * `"rent"`, `"rentAmount"`, `"price"` - and the value is sometimes `null` or `""` in the
     * JSON while still being rendered on screen. Callers pass their whole preference list and
     * take the first usable answer instead of hard-coding one key that may vanish tomorrow.
     */
    fun JSONObject.firstString(vararg keys: String): String? =
        keys.asSequence()
            .filter { has(it) && !isNull(it) }
            .mapNotNull { key ->
                when (val value = get(key)) {
                    is String -> value.trim()
                    is Number -> value.toString()
                    else -> null
                }
            }
            .firstOrNull { it.isNotEmpty() && it != "null" }

    /**
     * First key in [keys] that holds a positive number, accepting both JSON numbers and the
     * formatted strings sites like to embed (`"₹45,000"`, `"28000 /month"`).
     *
     * Zero and negatives are rejected on purpose: they are the signature of a mis-selected
     * node - a floor index, a bedroom count, a "price on request" placeholder - and letting
     * one through poisons the rent statistics far more than dropping the listing.
     */
    fun JSONObject.firstDouble(vararg keys: String): Double? =
        keys.asSequence()
            .filter { has(it) && !isNull(it) }
            .mapNotNull { key ->
                when (val value = get(key)) {
                    is Number -> value.toDouble()
                    // Strip currency symbols/commas/units; a leading '-' keeps the value negative.
                    is String -> value.trim().takeUnless { it.startsWith("-") }
                        ?.replace(NON_NUMERIC, "")
                        ?.toDoubleOrNull()
                    else -> null
                }
            }
            .firstOrNull { it.isFinite() && it > 0.0 }

    /** A balanced `[` ... `]` slice of the source, inclusive of both brackets. */
    private data class ArraySpan(val first: Int, val last: Int)

    private val NON_NUMERIC = Regex("[^0-9.]")

    /**
     * Every balanced array in [html], ordered by opening offset.
     *
     * The walk tracks string literals and backslash escapes because listing titles and
     * descriptions legitimately contain `[{`: a naive brace counter would close the array
     * on the `]` inside `"2 BHK (flat [ready])"` and return a truncated, unparseable slice.
     * Unbalanced closers are ignored rather than popping the stack, which keeps stray `]`
     * characters in the surrounding markup from swallowing the real array.
     */
    private fun arraySpans(html: String): List<ArraySpan> {
        val spans = ArrayList<ArraySpan>()
        val open = ArrayDeque<Int>()
        var inString = false
        var escaped = false

        var i = 0
        while (i < html.length) {
            val ch = html[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{', '[' -> open.addLast(i)
                    '}', ']' -> {
                        val start = open.removeLastOrNull()
                        if (ch == ']' && start != null && html[start] == '[') {
                            spans.add(ArraySpan(start, i))
                        }
                    }
                }
            }
            i++
        }
        return spans.sortedBy { it.first }
    }

    /** Parses one array slice, dropping non-object elements; [] on any parse failure. */
    private fun parseObjects(slice: String): List<JSONObject> = runCatching {
        val array = JSONArray(slice)
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }.getOrDefault(emptyList())
}
