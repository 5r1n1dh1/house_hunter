package com.househunter.app.data.scrape

/**
 * The JavaScript snippets used to read a page back out of a browser engine.
 *
 * WHY these live here, and not inside [com.househunter.app.data.remote.WebViewFetcher]:
 * the same script has to run under both engines. The HTTP engine ([com.househunter.app.data.remote.HttpFetcher])
 * never runs them (it hands back a raw body), but keeping the snippets in a pure, Android-free
 * object means they can be asserted by plain JVM unit tests — `WebView.evaluateJavascript` is a
 * framework call that no local JVM test can exercise, so a typo in a 3-line script would otherwise
 * only surface as "every site returns null" on a device.
 *
 * Both builders are side-effect free and return the same string on every call, which keeps them
 * safe to call from tests and from the hot path.
 */
object WebViewScripts {

    /**
     * Serialises the *rendered* DOM back to a string.
     *
     * `document.documentElement.outerHTML` is used rather than `document.body.innerHTML` because a
     * scraper also needs `<head>` (JSON-LD blocks, meta refreshes) and because some sites render
     * listings outside `<body>` quirks-mode documents. Reading the outerHTML of the document element
     * returns the complete markup including everything the page's own JS has already inserted.
     *
     * The wrapper is an IIFE because `evaluateJavascript` resolves with the *completion value* of the
     * script: the bare expression `document.documentElement.outerHTML` would also work, but an IIFE
     * lets us guard the two ways this can blow up on a real site — a document with no root element,
     * and a page that throws while the WebView is being torn down. Returning `''` on failure lets the
     * caller treat "nothing extracted" uniformly instead of parsing half a document.
     */
    fun outerHtmlScript(): String =
        "(function () {\n" +
            "  try {\n" +
            "    var root = document.documentElement;\n" +
            "    return root ? root.outerHTML : '';\n" +
            "  } catch (e) {\n" +
            "    return '';\n" +
            "  }\n" +
            "})();"

    /**
     * Publishes a readiness flag the fetch loop can poll.
     *
     * `WebViewClient.onPageFinished` only means the `load` event fired; on a client-rendered site
     * (99acres, housing.com both hydrate listings with JS) the DOM can still be filling in. Exposing
     * `window.__hhReady` lets the caller confirm `document.readyState === 'complete'` before it
     * serialises the DOM, instead of guessing with a fixed sleep.
     *
     * The flag is left on `window` rather than returned only, so a re-check costs nothing and the
     * value survives if the page navigates itself while we are polling. It is written in both the
     * success and the failure branch so the caller never has to distinguish "false" from "script
     * did not run" — a missing flag is treated exactly like a page that is not ready.
     */
    fun pageReadyScript(): String =
        "(function () {\n" +
            "  try {\n" +
            "    window.__hhReady = document.readyState === 'complete';\n" +
            "    return window.__hhReady;\n" +
            "  } catch (e) {\n" +
            "    window.__hhReady = false;\n" +
            "    return false;\n" +
            "  }\n" +
            "})();"
}