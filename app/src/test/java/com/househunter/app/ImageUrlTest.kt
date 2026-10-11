package com.househunter.app

import com.househunter.app.data.scrape.ImageUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for "listing photos do not appear".
 *
 * NoBroker emits protocol-relative photo URLs (`//assets.nobroker.in/...`), which an image loader
 * cannot fetch, and its CDN refuses requests without a `Referer` with 403. [ImageUrl] is what
 * repairs both before any image request is made.
 */
class ImageUrlTest {

    /** A real asset path from NoBroker's listing pages, protocol-relative as served. */
    private val nobrokerAsset =
        "//assets.nobroker.in/8a9fa98277d9f1d10177da4ec18318f1_21192_473441_original.jpg"

    private val nobrokerAssetHttps =
        "https://assets.nobroker.in/8a9fa98277d9f1d10177da4ec18318f1_21192_473441_original.jpg"

    @Test
    fun upgradesProtocolRelativeUrlsToHttps() {
        assertEquals(
            "https://assets.nobroker.in/x.jpg",
            ImageUrl.normalize("//assets.nobroker.in/x.jpg"),
        )
        assertEquals(nobrokerAssetHttps, ImageUrl.normalize(nobrokerAsset))
    }

    @Test
    fun upgradesBareHostUrlsToHttps() {
        assertEquals(
            "https://assets.nobroker.in/x.jpg",
            ImageUrl.normalize("assets.nobroker.in/x.jpg"),
        )
        assertEquals(
            "https://img.staticmb.com/photo/1.jpg",
            ImageUrl.normalize("  img.staticmb.com/photo/1.jpg  "),
        )
    }

    @Test
    fun leavesAbsoluteUrlsUntouched() {
        assertEquals(nobrokerAssetHttps, ImageUrl.normalize(nobrokerAssetHttps))
        assertEquals(
            "http://assets.nobroker.in/x.jpg",
            ImageUrl.normalize("http://assets.nobroker.in/x.jpg"),
        )
        assertEquals(
            "https://img.staticmb.com/photo/1.jpg?v=2",
            ImageUrl.normalize("https://img.staticmb.com/photo/1.jpg?v=2"),
        )
    }

    @Test
    fun rejectsUnusableValues() {
        assertNull(ImageUrl.normalize(null))
        assertNull(ImageUrl.normalize(""))
        assertNull(ImageUrl.normalize("   "))
        assertNull(ImageUrl.normalize("javascript:alert(1)"))
        assertNull(ImageUrl.normalize("data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=="))
        assertNull(ImageUrl.normalize("https://"))
        assertNull(ImageUrl.normalize("not a url"))
    }

    @Test
    fun buildsRefererOriginFromDetailUrl() {
        assertEquals(
            "https://www.nobroker.in/",
            ImageUrl.baseRefererFor("https://www.nobroker.in/property/x/detail"),
        )
    }

    @Test
    fun refererIsNullForUnusableDetailUrls() {
        assertNull(ImageUrl.baseRefererFor(null))
        assertNull(ImageUrl.baseRefererFor("not a url"))
        assertNull(ImageUrl.baseRefererFor(""))
    }

    @Test
    fun refererKeepsOnlyTheOrigin() {
        assertEquals(
            "https://www.nobroker.in/",
            ImageUrl.baseRefererFor("https://www.nobroker.in/property/x/detail?tab=photos#gallery"),
        )
        assertEquals(
            "http://img.staticmb.com/",
            ImageUrl.baseRefererFor("http://img.staticmb.com/listings/123?ref=home#photos"),
        )
    }
}