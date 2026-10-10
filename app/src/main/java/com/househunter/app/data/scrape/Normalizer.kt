package com.househunter.app.data.scrape

/**
 * Pure parsing helpers for raw listing text (no Android dependencies so they
 * can be unit-tested on the JVM).
 */
object Normalizer {

    /** "₹18,000" | "Rs. 25000 /month" | "1.8 Lakh" -> Double rupees. */
    fun parseRent(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val clean = text.replace(",", "").replace("₹", "").replace("Rs", "", ignoreCase = true)
        Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*lakh").find(clean)?.let { match ->
            return match.groupValues[1].toDoubleOrNull()?.times(100_000.0)
        }
        Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*k\\b").find(clean)?.let { match ->
            return match.groupValues[1].toDoubleOrNull()?.times(1_000.0)
        }
        // Take the first well-formed number: "25,000" -> 25000, ignoring stray dots/symbols.
        val number = Regex("([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.replace(",", "")
        return number?.toDoubleOrNull()?.takeIf { it > 0 }
    }

    /** "2 BHK" | "3bhk" | "2.5 BHK" -> 2 */
    fun parseBhk(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val match = Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*(?:bhk|bed)").find(text) ?: return null
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