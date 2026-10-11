package com.househunter.app.data.scrape

/**
 * Pure parsing helpers for raw listing text (no Android dependencies so they
 * can be unit-tested on the JVM).
 */
object Normalizer {

    /** Rents below this are mis-parsed (floor/bedroom counts), not real listings. */
    const val MIN_PLAUSIBLE_RENT = 500.0

    /** Guard against runaway numbers (₹1 crore ceiling). */
    const val MAX_PLAUSIBLE_RENT = 10_000_000.0

    /**
     * "₹18,000" | "Rs. 25,000 /month" | "1.8 Lakh" -> Double rupees.
     *
     * Prefers numbers adjacent to a currency marker so "Floor 1, ₹24,000" yields
     * 24000 rather than 1, and rejects anything outside
     * [MIN_PLAUSIBLE_RENT, MAX_PLAUSIBLE_RENT] — that guard is what stops bogus
     * "₹1/mo" cards when a site selector matches a floor/bedroom count.
     */
    fun parseRent(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val clean = text.replace(",", "").replace("₹", "").replace("Rs", "", ignoreCase = true)

        // "lakh" and the abbreviated "lac"/"lk" all appear in the wild
        // (99acres prints "Rs 2.5 Lac").
        Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*(?:lakh|lac|lk)\\b").find(clean)?.let { match ->
            return plausible(match.groupValues[1].toDoubleOrNull()?.times(100_000.0))
        }
        Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*k\\b").find(clean)?.let { match ->
            return plausible(match.groupValues[1].toDoubleOrNull()?.times(1_000.0))
        }

        // 1) A number attached to a currency marker anywhere in the text - safest.
        val withCurrency = Regex("(?i)(?:₹|rs\\.?|inr)\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.replace(",", "")
            ?.toDoubleOrNull()
        if (withCurrency != null) return plausible(withCurrency)

        // 2) Otherwise the first well-formed number.
        val first = Regex("([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.replace(",", "")
            ?.toDoubleOrNull()

        return plausible(first)
    }

    /** Indian rents sit between ₹500 and ₹1 crore; anything else is a mis-parse. */
    fun plausible(value: Double?): Double? =
        value?.takeIf { it >= MIN_PLAUSIBLE_RENT && it <= MAX_PLAUSIBLE_RENT }

    /** "2 BHK" | "3bhk" | "2.5 BHK" -> 2 */
    fun parseBhk(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val match = Regex("(?i)([0-9]+(?:\\.[0-9]+)?)[\\s_-]*(?:bhk|bed)").find(text) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return value.toInt().coerceIn(1, 20)
    }

    /** "1,200 sqft" | "1200 sq.ft" | "1100" -> 1200.0 */
    fun parseAreaSqft(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val cleaned = text.replace(",", "")
        val sqft = Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*sq").find(cleaned)
        if (sqft != null) return sqft.groupValues[1].toDoubleOrNull()
        val match = Regex("([0-9]{2,6})").find(cleaned) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }

    /** Digits only, keeping the country code (e.g. +91 98765 43210 -> 919876543210). */
    fun sanitizePhone(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val digits = text.replace(Regex("[^0-9]"), "")
        if (digits.length < 8) return null
        return digits
    }

    /** Lower-case, whitespace collapsed - used for cross-site dedupe. */
    fun normalizeForDedupe(text: String?): String =
        text?.lowercase()?.replace(Regex("[^a-z0-9]+"), " ")?.trim().orEmpty()

    /** Relative or absolute href -> absolute URL, or null when unusable. */
    fun resolveUrl(baseUrl: String, href: String?): String? {
        if (href.isNullOrBlank()) return null
        val trimmed = href.trim()
        return runCatching {
            java.net.URI(if (trimmed.startsWith("http")) trimmed else baseUrl.trimEnd('/') + "/" + trimmed.removePrefix("/"))
                .toString()
        }.getOrNull()
    }

    /** Extracts the first capture group of [regex] from [text], if present. */
    fun capture(regex: String?, text: String?): String? {
        if (regex.isNullOrBlank() || text.isNullOrBlank()) return null
        val match = runCatching { Regex(regex, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(text) }.getOrNull() ?: return null
        return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }
}