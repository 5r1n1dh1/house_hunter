package com.househunter.app.data.scrape

import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the schema.org JSON-LD block that most property sites embed **inside each
 * result card**, e.g. MagicBricks renders
 *
 * ```json
 * {"@type":"RentAction","object":{"@type":"Residence","name":"3 BHK ..."},
 *  "priceSpecification":{"price":45000,"priceCurrency":"INR"}}
 * ```
 *
 * while the visible price is injected client-side. Without this, such sites parse
 * as empty even though the data is present in the HTML.
 */
data class JsonLd(
    val price: Double? = null,
    val name: String? = null,
    val image: String? = null,
    val description: String? = null,
    /** schema.org `landlord.name` - MagicBricks publishes the owner/agent here. */
    val owner: String? = null,
) {
    companion object {
        val EMPTY = JsonLd()

        fun from(card: Element): JsonLd = runCatching {
            val scripts = card.select("script[type='application/ld+json']")
            val raw = scripts.firstOrNull()?.data()?.takeIf { it.isNotBlank() }
                ?: card.select("script").firstOrNull { it.data().contains("\"@type\"") }?.data()
                ?: return EMPTY
            parse(raw)
        }.getOrDefault(EMPTY)

        /** Pure parser so it can be unit-tested without Jsoup. */
        fun parse(raw: String): JsonLd {
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return EMPTY

            val price = sequenceOf(
                root.optJSONObject("priceSpecification")?.optNumber("price"),
                root.optJSONObject("offers")?.optNumber("price"),
                root.optNumber("price"),
            ).filterNotNull().firstOrNull { it > 0 }

            val objectNode = root.optJSONObject("object")
            val name = sequenceOf(
                objectNode?.optString("name")?.takeIf { it.isNotBlank() && it != "null" },
                root.optString("name").takeIf { it.isNotBlank() && it != "null" },
                root.optString("headline").takeIf { it.isNotBlank() && it != "null" },
            ).firstOrNull()

            val image = sequenceOf(
                objectNode?.optString("image")?.takeIf { it.startsWith("http") },
                root.optString("image").takeIf { it.startsWith("http") },
                root.optJSONArray("image")?.optString(0)?.takeIf { it.startsWith("http") },
            ).firstOrNull()

            val description = sequenceOf(
                objectNode?.optString("description")?.takeIf { it.isNotBlank() && it != "null" },
                root.optString("description").takeIf { it.isNotBlank() && it != "null" },
            ).firstOrNull()

            val owner = sequenceOf(
                root.optJSONObject("landlord")?.optString("name")?.takeIf { it.isNotBlank() },
                root.optJSONObject("provider")?.optString("name")?.takeIf { it.isNotBlank() },
            ).firstOrNull()?.trim()

            return JsonLd(price, name, image, description, owner)
        }

        private fun JSONObject.optNumber(key: String): Double? {
            if (!has(key) || isNull(key)) return null
            return when (val value = get(key)) {
                is Number -> value.toDouble()
                // Prices are often strings: "₹31,500", "45000 /month"
                is String -> value
                    .replace(Regex("[^0-9.]"), "")
                    .toDoubleOrNull()
                else -> null
            }
        }
    }
}

/** Convenience for sites that ship a JSON array of listings instead of markup. */
internal fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }