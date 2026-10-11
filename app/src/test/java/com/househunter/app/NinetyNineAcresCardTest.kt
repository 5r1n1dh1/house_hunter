package com.househunter.app

import com.househunter.app.data.scrape.SelectorSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.jsoup.Jsoup
import org.junit.Test

/**
 * 99acres splits the advertiser across two nodes: `tupleNew__pbL1` holds the role
 * ("Dealer") and `tupleNew__pbL2` the name. Their A/B variants also suffix the
 * class names (`tupleNew__tupleWrapTopaz`), which is why every selector uses
 * attribute-contains matching.
 */
class NinetyNineAcresCardTest {

    private val config = SelectorSet(
        listContainer = "div[class*='tupleNew__tupleWrap']",
        title = "h2[class*='tupleNew__propType']",
        price = "div[class*='tupleNew__priceValWrap']",
        areaSqft = "div[class*='tupleNew__areaType']",
        detailUrl = "a[href*='spid-']",
        ownerTag = "div[class*='tupleNew__pbL1']",
        ownerName = "div[class*='tupleNew__pbL2']",
    )

    private val html = """
        <div class="tupleNew__tupleWrapTopaz tupleNew__platHTopaz">
          <a href="https://www.99acres.com/1-bhk-bedroom-apartment-flat-for-rent-in-ashraya-layout-bangalore-east-610-sqft-spid-R123">
            <h2 class="tupleNew__propType"><span class="tupleNew__bOld">1 BHK Flat for rent</span> in Ashraya Layout</h2>
          </a>
          <div id="tuplePriceArea_123"><div class="tupleNew__priceAndPerSqftWrap ">
            <div class="tupleNew__priceValWrap"><span>&#8377;24,000</span><span class="tupleNew__perSqftWrap"> /month</span></div>
          </div></div>
          <div class="tupleNew__areaType">Super Built-up Area 610 sqft</div>
          <div class="tupleNew__pbL1"><span>Dealer</span><span class="tupleNew__dot">&middot;</span><span>6d ago</span></div>
          <div class="tupleNew__pbL2 ellipsis">Vertex Realty</div>
        </div>
    """.trimIndent()

    @Test
    fun substringSelectorsSurviveAbClassSuffixes() {
        val card = Jsoup.parse(html, "https://www.99acres.com").selectFirst(config.listContainer)
        requireNotNull(card) { "99acres card not matched by the substring selector" }
        // The h2 holds "<strong>1 BHK Flat for rent</strong> in Ashraya Layout",
        // so the app picks up the locality for free - that is worth asserting.
        assertEquals("1 BHK Flat for rent in Ashraya Layout", card.selectFirst(config.title)!!.text())
        assertEquals("Dealer �� 6d ago", card.selectFirst(config.ownerTag)!!.text().replace("·", "·"))
    }

    @Test
    fun priceAreaAndDetailLinkResolve() {
        val card = Jsoup.parse(html, "https://www.99acres.com").selectFirst(config.listContainer)!!
        assertEquals("₹24,000 /month", card.selectFirst(config.price)!!.text())
        assertEquals(610.0, com.househunter.app.data.scrape.Normalizer.parseAreaSqft(
            card.selectFirst(config.areaSqft)!!.text(),
        )!!, 0.01)
        val href = card.selectFirst(config.detailUrl)!!.attr("href")
        assertEquals(
            "https://www.99acres.com/1-bhk-bedroom-apartment-flat-for-rent-in-ashraya-layout-bangalore-east-610-sqft-spid-R123",
            href,
        )
    }

    @Test
    fun ownerRoleAndNameAreSeparateNodes() {
        val card = Jsoup.parse(html, "https://www.99acres.com").selectFirst(config.listContainer)!!
        assertEquals("Dealer", card.selectFirst(config.ownerTag)!!.selectFirst("span")!!.text())
        assertEquals("Vertex Realty", card.selectFirst(config.ownerName)!!.text())
    }

    @Test
    fun combinedOwnerNodeStillParses() {
        val combined = SelectorSet(
            listContainer = "div", title = "h2", price = "span",
            ownerTag = "div.combined",
        )
        val doc = Jsoup.parse("""<div class="combined">Owner: Ramesh Rao</div>""")
        val text = doc.selectFirst(combined.ownerTag!!)!!.text()
        val parts = text.split(":", limit = 2).map { it.trim() }
        assertEquals("Owner", parts[0])
        assertEquals("Ramesh Rao", parts[1])
        assertNull(parts.firstOrNull { it.isEmpty() })
    }
}