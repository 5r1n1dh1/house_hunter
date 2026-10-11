package com.househunter.app

import com.househunter.app.data.scrape.SitemapSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests for the Stanza sitemap layer. Fixtures are verbatim copies of the live
 * `https://www.stanzaliving.com/sitemap.xml` / `sitemap-residence.xml` shapes, because the
 * whole point of the sitemap route is that it stays stable when the HTML search page does not.
 */
class SitemapSourceTest {

    // The parsing functions take XML directly, so a fetcher that yields nothing is enough.
    private val source = SitemapSource { null }

    /** Verbatim `<sitemapindex>` shape: `<loc>` + `<lastmod>` children per entry. */
    private val sitemapIndex = """
        <?xml version="1.0" encoding="UTF-8"?>
        <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">

            <sitemap>
                <loc>https://www.stanzaliving.com/sitemaps/sitemap-city.xml</loc>
                <lastmod>2026-09-26</lastmod>
            </sitemap>

            <sitemap>
                <loc>https://www.stanzaliving.com/sitemaps/sitemap-micromarket.xml</loc>
                <lastmod>2026-09-28</lastmod>
            </sitemap>

            <sitemap>
                <loc>https://www.stanzaliving.com/sitemaps/sitemap-residence.xml</loc>
                <lastmod>2026-10-01</lastmod>
            </sitemap>

            <sitemap>
                <loc>https://www.stanzaliving.com/sitemaps/sitemap-room-type.xml</loc>
                <lastmod>2026-10-02</lastmod>
            </sitemap>

            <sitemap>
                <loc>https://www.stanzaliving.com/sitemaps/sitemap-static.xml</loc>
                <lastmod>2026-10-03</lastmod>
            </sitemap>

            <sitemap>
                <loc>https://www.stanzaliving.com/sitemaps/dynamic-sitemap-index.xml</loc>
                <lastmod>2025-12-10</lastmod>
            </sitemap>

        </sitemapindex>
    """.trimIndent()

    /** Ten real residence URLs: 4 Bengaluru, 3 Pune, 3 Delhi. */
    private val residenceUrlset = """
        <?xml version="1.0" encoding="UTF-8"?>
        <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
         <url>
         <loc>https://www.stanzaliving.com/bengaluru/pg-hostel-in-banashankari/male/kampala-house</loc>
         <lastmod>2026-10-01</lastmod>
         <changefreq>monthly</changefreq>
         <priority>0.9</priority>
         </url>
         <url>
         <loc>https://www.stanzaliving.com/bengaluru/pg-hostel-in-bellandur/co_ed/shanghai-house</loc>
         <lastmod>2026-10-01</lastmod>
         <changefreq>monthly</changefreq>
         <priority>0.9</priority>
         </url>
         <url>
         <loc>https://www.stanzaliving.com/bengaluru/pg-hostel-in-hsr-layout/co_ed/cork-house</loc>
         <lastmod>2026-10-01</lastmod>
         <changefreq>monthly</changefreq>
         <priority>0.9</priority>
         </url>
         <url>
         <loc>https://www.stanzaliving.com/bengaluru/pg-hostel-in-koramangala/female/como-house</loc>
         <lastmod>2026-10-01</lastmod>
         <changefreq>monthly</changefreq>
         <priority>0.9</priority>
         </url>
         <url>
         <loc>https://www.stanzaliving.com/pune/hostel-pg-in-dhankawadi/male/sarnen-house</loc>
         <lastmod>2026-10-01</lastmod>
         <changefreq>monthly</changefreq>
         <priority>0.9</priority>
         </url>
         <url>
         <loc>https://www.stanzaliving.com/pune/pg-in-karve-nagar/male/mckinney-house</loc>
         <lastmod>2026-10-01</lastmod>
         <changefreq>monthly</changefreq>
         <priority>0.9</priority>
         </url>
         <url>
         <loc>https://www.stanzaliving.com/pune/pg-in-viman-nagar/co_ed/malmo-house</loc>
         <lastmod>2026-10-01</lastmod>
         <changefreq>monthly</changefreq>
         <priority>0.9</priority>
         </url>
         <url>
         <loc>https://www.stanzaliving.com/delhi/pg-hostel-in-laxmi-nagar/male/cardiff-house</loc>
         <lastmod>2026-10-01</lastmod>
         <changefreq>monthly</changefreq>
         <priority>0.9</priority>
         </url>
         <url>
         <loc>https://www.stanzaliving.com/delhi/pg-hostel-in-north-campus/male/boston-house</loc>
         <lastmod>2026-10-01</lastmod>
         <changefreq>monthly</changefreq>
         <priority>0.9</priority>
         </url>
         <url>
         <loc>https://www.stanzaliving.com/delhi/pg-hostel-in-south-campus/female/sendai-house</loc>
         <lastmod>2026-10-01</lastmod>
         <changefreq>monthly</changefreq>
         <priority>0.9</priority>
         </url>
        </urlset>
    """.trimIndent()

    @Test
    fun discoversEverySitemapInTheIndex() {
        val discovered = source.discoverSitemaps(sitemapIndex)

        assertEquals(6, discovered.size)
        assertTrue(discovered.contains("https://www.stanzaliving.com/sitemaps/sitemap-residence.xml"))
        assertTrue(discovered.contains("https://www.stanzaliving.com/sitemaps/sitemap-city.xml"))
        // Order is preserved so callers can prefer the richest file without re-sorting.
        assertEquals("https://www.stanzaliving.com/sitemaps/sitemap-city.xml", discovered.first())
        assertEquals("https://www.stanzaliving.com/sitemaps/dynamic-sitemap-index.xml", discovered.last())
    }

    @Test
    fun readsEveryResidenceUrlAndFiltersByCity() {
        val urls = source.urls(residenceUrlset)

        val first = "https://www.stanzaliving.com/bengaluru/pg-hostel-in-banashankari/male/kampala-house"
        val last = "https://www.stanzaliving.com/delhi/pg-hostel-in-south-campus/female/sendai-house"

        assertEquals(10, urls.size)
        assertEquals(first, urls.first())
        assertEquals(last, urls.last())

        assertEquals(4, source.filter(urls, "bengaluru").size)
        assertEquals(3, source.filter(urls, "pune").size)
        assertEquals(3, source.filter(urls, "delhi").size)
        assertEquals(10, source.filter(urls, "bengaluru").size + source.filter(urls, "pune").size +
            source.filter(urls, "delhi").size)
    }

    @Test
    fun filtersByLocalityWithinTheCity() {
        val urls = source.urls(residenceUrlset)
        val banashankari = "https://www.stanzaliving.com/bengaluru/pg-hostel-in-banashankari/male/kampala-house"

        assertEquals(listOf(banashankari), source.filter(urls, "bengaluru", "banashankari"))
        // User input arrives title-cased from the locality picker; the URL slug is lower-case.
        assertEquals(listOf(banashankari), source.filter(urls, "Bengaluru", "Banashankari"))
        assertEquals(listOf(banashankari), source.filter(urls, "BENGALURU", "BANASHANKARI"))
    }

    @Test
    fun localityThatMatchesNothingReturnsEmptyList() {
        val urls = source.urls(residenceUrlset)

        // "Baner" is a real Pune locality but must not leak into a Bengaluru search, and the
        // Bengaluru locality must not leak into Delhi or into a longer nearby slug.
        assertEquals(emptyList<String>(), source.filter(urls, "bengaluru", "baner"))
        assertEquals(emptyList<String>(), source.filter(urls, "delhi", "banashankari"))
        assertEquals(emptyList<String>(), source.filter(urls, "bengaluru", "koramangala-extension"))
    }

    @Test
    fun discoverSitemapsOnAUrlsetDocumentReturnsEmptyList() {
        // Index and listing documents share the <loc> vocabulary; confusing them would feed
        // residence URLs to the sitemap crawler.
        assertEquals(emptyList<String>(), source.discoverSitemaps(residenceUrlset))
    }

    @Test
    fun malformedXmlReturnsEmptyListWithoutThrowing() {
        val truncated = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><sitemapindex><sitemap><loc>"
        val garbage = "<<< not xml at all >>> &&& <<<"
        val errorPage = "<html><body><h1>503 Service Unavailable</h1></body></html>"

        for (body in listOf(truncated, garbage, errorPage)) {
            assertEquals(emptyList<String>(), source.discoverSitemaps(body))
            assertEquals(emptyList<String>(), source.urls(body))
        }
        assertEquals(emptyList<String>(), source.discoverSitemaps(""))
        assertEquals(emptyList<String>(), source.urls(""))
    }

    @Test
    fun matchesSeparatorVariantsAndRejectsSubstringsOfLongerWords() {
        val dashed = "https://www.stanzaliving.com/bengaluru/pg-hostel-in-sahakara-nagar/male/london-house"
        val underscored = "https://www.stanzaliving.com/bengaluru/pg-in-sahakara_nagar/co_ed/pune-house"
        val urls = listOf(dashed, underscored)

        // "Sahakara Nagar" as typed by the user must reach both slug spellings.
        assertEquals(urls, source.filter(urls, "bengaluru", "Sahakara Nagar"))
        assertEquals(urls, source.filter(urls, "bengaluru", "sahakara-nagar"))
        assertEquals(urls, source.filter(urls, "bengaluru", "sahakara_nagar"))

        // A locality that is only a prefix of the segment's real name must not match.
        val sahakaraRoad = "https://www.stanzaliving.com/bengaluru/pg-hostel-in-sahakara-road/male/london-house"
        assertEquals(emptyList<String>(), source.filter(listOf(sahakaraRoad), "bengaluru", "sahakara nagar"))

        // "delhi" must not swallow "delhi-ncr", and "noida" must not swallow "greater-noida".
        val delhiNcr = "https://www.stanzaliving.com/delhi-ncr/pg-hostel-in-laxmi-nagar/male/cardiff-house"
        val greaterNoida = "https://www.stanzaliving.com/greater-noida/pg-hostel-in-knowledge-park/male/athens-house"
        assertEquals(emptyList<String>(), source.filter(listOf(delhiNcr), "delhi"))
        assertEquals(emptyList<String>(), source.filter(listOf(greaterNoida), "noida"))
        assertEquals(listOf(greaterNoida), source.filter(listOf(greaterNoida), "Greater Noida"))
    }
}