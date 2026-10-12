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

        // "lakh" and its abbreviations all appear in the wild: 99acres prints "Rs 2.5 Lac"
        // and bengaluru.rent prints "₹1.20L".
        Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*(?:lakh|lac|lk|l)\\b").find(clean)?.let { match ->
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
        val sqft = Regex("(?i)([0-9]+(?:\\.[0-9]+)?)[\\s_-]*sq").find(cleaned)
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

    /**
     * Any published "when was this posted" value -> epoch millis, or null.
     *
     * Sources disagree on every axis: NoBroker sends epoch **milliseconds**
     * (`creationDate: 1526802196000`), Razorent sends ISO-8601 with a `Z` suffix
     * (`createdAt: 2026-10-11T12:12:33.585Z`), and card-based portals send loose
     * text. The unit is inferred from magnitude - a 13-digit value is millis, a
     * 10-digit one is seconds - because NoBroker and Razorent are both consumed by
     * the same JSON-mode parser and guessing wrong would date listings to 1970 or
     * far future, which the age chip would then display as nonsense.
     */
    fun parsePostedAt(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()

        // Purely numeric: epoch in seconds or milliseconds.
        if (text.matches(Regex("\\d{9,14}"))) {
            val value = text.toLongOrNull() ?: return null
            return when {
                value >= 100_000_000_000L -> value          // millis
                value >= 1_000_000_000L -> value * 1_000L   // seconds
                else -> null
            }
        }

        // ISO-8601 / RFC-1123, which covers Razorent and sitemap <lastmod>.
        runCatching {
            return java.time.Instant.parse(text).toEpochMilli()
        }
        runCatching {
            return java.time.ZonedDateTime.parse(
                text,
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
            ).toInstant().toEpochMilli()
        }
        runCatching {
            return java.time.LocalDateTime.parse(
                text,
                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            ).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }
        return null
    }

    /**
     * Human-friendly age of a listing: "just now", "3h ago", "2d ago", "5w ago".
     *
     * Returns null when [postedAt] is unknown so callers can omit the chip rather
     * than claim a freshness they cannot support. Future timestamps (clock skew
     * between portal and device) clamp to "just now" rather than "-2d ago".
     */
    fun relativeAge(postedAt: Long?, now: Long = System.currentTimeMillis()): String? {
        if (postedAt == null || postedAt <= 0L) return null
        val minutes = (now - postedAt) / 60_000L
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 60 * 24 -> "${minutes / 60}h ago"
            minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d ago"
            else -> "${minutes / (60 * 24 * 7)}w ago"
        }
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