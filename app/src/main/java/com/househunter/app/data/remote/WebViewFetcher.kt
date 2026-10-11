package com.househunter.app.data.remote

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.househunter.app.BuildConfig
import com.househunter.app.data.scrape.WebViewScripts
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A [PageFetcher] backed by a real — but invisible — Android WebView.
 *
 * WHY this exists: some hosts reject anything that is not a browser, and they reject it *below* the
 * HTTP client, in the CDN/WAF:
 *  - 99acres answers **403**, because Cloudflare fingerprints the TLS/JA3 stack (OkHttp ≠ Chrome),
 *  - housing.com answers **406** when the request does not match a known browser profile,
 *  - OLX refuses the connection outright.
 * No header tweak fixes that. An Android WebView *is* Chromium with Chrome's own TLS and HTTP
 * stack, so the same URL loads there — no user ever sees the page.
 *
 * HOW it stays out of the UI: a fresh [WebView] is created per call, driven headlessly, and destroyed
 * in a `finally`. It is never added to a Compose tree; when a host Activity is available it is put
 * inside a 1x1 invisible container (see [PageProvider]) so it occupies no pixels and eats no taps.
 *
 * WHY an `Application` context: the fetcher is a `@Singleton` owned by the DI graph, which outlives
 * any Activity — holding an Activity across `fetch()` calls would leak it. The consequences of an
 * Application-context WebView are:
 *  - every WebView call (construct, settings, `loadUrl`, `evaluateJavascript`, `destroy`) must run
 *    on the **main thread**, so the whole page lifecycle lives inside `withContext(Dispatchers.Main)`
 *    and calls are serialised behind a [Mutex] (a second live WebView fights the first over
 *    process-wide Chromium state),
 *  - it has no [android.view.Window], so it cannot show JS dialogs, file pickers or fullscreen video.
 *    JS dialogs are auto-cancelled (see [offscreenChromeClient]); nothing else is needed to scrape
 *    HTML, and [PageProvider] exists for the rare cases that do want a real window.
 *
 * WIRING: none is needed for the object itself — the `@Inject` constructor plus `@ApplicationContext`
 * is a complete Dagger binding, and `@Singleton` scopes it to `SingletonComponent`, so anything that
 * asks for a `WebViewFetcher` (e.g. `SiteScraper`) gets this one instance. Do **not** also add a
 * `@Provides` for it: Dagger rejects a class that has both an `@Inject` constructor and an explicit
 * provider for the same type. The only wiring the UI layer owns is one line, from `MainActivity`:
 * ```
 * webViewFetcher.pageProvider = WebViewFetcher.PageProvider { this }
 * ```
 * so a host Activity exists while the UI is up. The provider is a settable property rather than a
 * constructor parameter so the DI graph still resolves when no Activity exists (background scraping).
 */
@Singleton
class WebViewFetcher @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : PageFetcher {

    /**
     * Supplies the Activity that can host the invisible 1x1 container, if one is alive right now.
     *
     * A `fun interface`, so `PageProvider { this }` works from an Activity. It is read **on the
     * main thread**, once per fetch, and may return `null` at any time (the Activity can be
     * finishing, or the app can be running headless) — the fetch then runs on an
     * Application-context WebView only, which is enough for ordinary page loads and DOM extraction.
     * Attaching is an optimisation for what genuinely needs a window (popups, permissions), not a
     * requirement for reading HTML.
     *
     * The Activity that registered itself is *not* guaranteed to still be alive by the time a fetch
     * runs (a registration from `onCreate` is never cleared), so every Activity handed back here is
     * re-checked with `isFinishing`/`isDestroyed` before it is touched. Never cache it.
     */
    fun interface PageProvider {
        /** The current Activity, or `null` when the app has no visible UI. */
        fun currentActivity(): Activity?
    }

    /** Optional, set by the UI layer; `null` (the default) means "never attach to an Activity". */
    @Volatile
    var pageProvider: PageProvider? = null

    /** One page at a time: Chromium is process-wide state and concurrent WebViews corrupt it. */
    private val mutex = Mutex()

    /** Only touched while [mutex] is held, so a plain field is enough. */
    private var lastLoadStartedAtMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Loads [url] in a throwaway WebView and returns the fully rendered DOM, or `null` when the page
     * could not be loaded (connection refused, a WAF error page, or the timeout firing).
     *
     * Access is serialised and rate limited exactly like [HttpFetcher]: a WAF that sees one request
     * every [minIntervalMs] serves us; one that sees a burst bans us.
     */
    override suspend fun fetch(
        url: String,
        referer: String?,
        minIntervalMs: Long,
    ): String? {
        if (url.isBlank()) return null
        return mutex.withLock {
            throttle(minIntervalMs)
            loadAndExtract(url, referer)?.takeIf { it.isNotBlank() }
        }
    }

    /** Polite gap between page loads, honouring the per-site limit the caller passed in. */
    private suspend fun throttle(minIntervalMs: Long) {
        val interval = minIntervalMs.coerceAtLeast(0L)
        val waitMs = (lastLoadStartedAtMs + interval) - System.currentTimeMillis()
        if (waitMs > 0) delay(waitMs)
        lastLoadStartedAtMs = System.currentTimeMillis()
    }

    /**
     * The whole page lifecycle on the main thread: create → attach → load → read DOM → destroy.
     *
     * The explicit `CancellationException` rethrow matters: a blanket `runCatching` here would
     * swallow cancellation and let a page the caller already abandoned keep running.
     */
    private suspend fun loadAndExtract(url: String, referer: String?): String? =
        withContext(Dispatchers.Main.immediate) {
            try {
                coroutineScope {
                    val outcome = CompletableDeferred<String?>()
                    // First terminal callback wins: a WAF 403 arrives *and then* onPageFinished
                    // still fires, and the block must not be overwritten by the challenge DOM.
                    val settled = AtomicBoolean(false)
                    val view = createWebView()
                    val session = Offscreen(view).also { it.host = attachToActivity(view) }
                    try {
                        view.webViewClient = pageClient(this, outcome, settled)
                        view.webChromeClient = offscreenChromeClient()
                        load(view, url, referer)
                        withTimeoutOrNull(PAGE_TIMEOUT_MS) { outcome.await() }
                    } finally {
                        destroySession(session)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // No WebView provider on this device, a view torn down mid-read, … all mean "this
                // engine is unavailable right now", not "crash the scrape".
                null
            }
        }

    private fun createWebView(): WebView = WebView(appContext).apply { configure(this) }

    /** Desktop Chrome UA, JS on, nothing persisted, no zoom/viewport games. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        view.settings.apply {
            // The single most important setting: the default WebView UA carries the "; wv" token
            // that every WAF fingerprints, and these sites serve an interstitial (or nothing) to a
            // non-desktop profile.
            setUserAgentString(USER_AGENT)
            javaScriptEnabled = true
            // Scraping never needs persistence: no localStorage, no IndexedDB, no cookie jar.
            domStorageEnabled = false
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            loadWithOverviewMode = false
            useWideViewPort = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            // An offscreen page must not be able to read the app's files or content provider.
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            loadsImagesAutomatically = !BLOCK_IMAGES
            // Every scrape target is https; mixed content is never needed and is itself a red flag.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setDefaultTextEncodingName("utf-8")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        if (BuildConfig.DEBUG) {
            // Lets the integrator watch the offscreen page in chrome://inspect when a site lies.
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    /**
     * Hides the WebView inside an invisible 1x1 container in the host Activity, *if* one exists.
     *
     * Only needed where Chromium insists on a real window (popups, permission prompts); it is
     * always invisible, never focusable, never clickable, and is removed again on teardown.
     * Returns the container so the caller can unattach it, or `null` when nothing was attached.
     */
    private fun attachToActivity(view: WebView): ViewGroup? {
        val activity = runCatching { pageProvider?.currentActivity() }.getOrNull() ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        val content = runCatching { activity.findViewById<ViewGroup>(android.R.id.content) }.getOrNull()
            ?: return null
        val host = FrameLayout(activity).apply {
            visibility = View.INVISIBLE
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
        }
        return runCatching {
            content.addView(host, ViewGroup.LayoutParams(1, 1))
            host
        }.getOrNull()
    }

    private fun load(view: WebView, url: String, referer: String?) {
        // The extra headers apply to this first main-frame request only (not to subresources),
        // which is exactly what a hotlink-protected `Referer` check needs.
        if (referer.isNullOrBlank()) {
            view.loadUrl(url)
        } else {
            view.loadUrl(url, mapOf("Referer" to referer))
        }
    }

    private fun pageClient(
        scope: CoroutineScope,
        outcome: CompletableDeferred<String?>,
        settled: AtomicBoolean,
    ): WebViewClient = object : WebViewClient() {

        override fun onPageFinished(view: WebView, url: String) {
            if (!settled.compareAndSet(false, true)) return
            // Reading the DOM is a *suspend* step (it polls, then calls evaluateJavascript) and this
            // callback runs on the main thread, so it is handed to a child coroutine of the load
            // scope instead of blocking the looper. Cancelling the scope cancels this too.
            scope.launch {
                val html = try {
                    awaitRenderedDom(view)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    null
                }
                outcome.complete(html)
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            // Subresource failures are the page's own business; only a dead main frame is fatal.
            if (!request.isForMainFrame) return
            if (settled.compareAndSet(false, true)) outcome.complete(null)
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceResponse,
        ) {
            // The WAF case: 403/406 arrive wrapped in a rendered challenge page. Handing that DOM to
            // the scraper would parse noise, so report the block as a failure instead.
            if (!request.isForMainFrame || error.statusCode < 400) return
            if (settled.compareAndSet(false, true)) outcome.complete(null)
        }
    }

    /**
     * A headless page has nobody to answer `alert()`; with the default handler JS execution would
     * block until the timeout fired, so every dialog is cancelled on sight.
     */
    private fun offscreenChromeClient(): WebChromeClient = object : WebChromeClient() {
        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
            result.cancel()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
            result.cancel()
            return true
        }

        override fun onJsPrompt(
            view: WebView,
            url: String,
            message: String,
            defaultValue: String,
            result: JsResult,
        ): Boolean {
            result.cancel()
            return true
        }
    }

    /**
     * Waits for the document to report `readyState === "complete"`, then serialises the DOM.
     *
     * `onPageFinished` alone is not enough on a client-rendered listing page: it fires on the `load`
     * event, which can precede the last DOM mutation. After the flag flips we still allow a short
     * settle period, and if it never flips we extract anyway — a late DOM beats no DOM.
     */
    private suspend fun awaitRenderedDom(view: WebView): String? {
        repeat(READY_POLL_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(READY_POLL_INTERVAL_MS)
            if (evaluate(view, WebViewScripts.pageReadyScript())?.trim() == "true") {
                delay(SETTLE_DELAY_MS)
                return extractHtml(view)
            }
        }
        return extractHtml(view)
    }

    private suspend fun extractHtml(view: WebView): String? {
        val raw = evaluate(view, WebViewScripts.outerHtmlScript()) ?: return null
        // evaluateJavascript hands back a *JSON-encoded* value, so the markup arrives quoted and
        // escaped; decode it back into real HTML before Jsoup ever sees it.
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }

    private suspend fun evaluate(view: WebView, script: String): String? =
        suspendCancellableCoroutine<String?> { cont ->
            view.evaluateJavascript(script) { raw ->
                if (cont.isActive) cont.resumeWith(Result.success(raw))
            }
        }

    /**
     * Idempotent, main-thread teardown — the `finally` already runs on the main thread, but a
     * cancellation winning the race can land elsewhere, hence the handler post.
     */
    private fun destroySession(session: Offscreen) {
        if (!session.destroyed.compareAndSet(false, true)) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            doDestroy(session)
        } else {
            mainHandler.post { doDestroy(session) }
        }
    }

    private fun doDestroy(session: Offscreen) {
        runCatching {
            session.view.stopLoading()
            // Drop the callbacks first: they capture this fetch's coroutine scope.
            session.view.webChromeClient = null
            session.view.webViewClient = null
        }
        runCatching { (session.host?.parent as? ViewGroup)?.removeView(session.host) }
        // Never leaks: no matter how this run ended, the WebView is released exactly once.
        runCatching { session.view.destroy() }
        if (CLEAR_SESSION_COOKIES) {
            // Leaves no identifier behind for the next fetch. This clears the process-wide cookie
            // jar, which is safe here because this offscreen WebView is the app's only one.
            runCatching { CookieManager.getInstance().removeAllCookies(null) }
        }
    }

    /** One fetch's WebView, plus the 1x1 container it may have been attached to. */
    private class Offscreen(val view: WebView) {
        val destroyed = AtomicBoolean(false)
        var host: ViewGroup? = null
    }

    companion object {
        /**
         * A current desktop Chrome UA. It has to be plausible: these sites serve a different — or
         * blocked — document to anything that is not a desktop browser, and an ancient Chrome
         * build is itself a bot signal.
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

        /** Hard ceiling for one page: slow CDN + JS hydration, but never hang the scrape forever. */
        const val PAGE_TIMEOUT_MS = 45_000L

        /** How often the readiness flag is polled before the DOM is read anyway. */
        const val READY_POLL_ATTEMPTS = 4

        /** Gap between readiness polls. */
        const val READY_POLL_INTERVAL_MS = 750L

        /** Quiet period after `readyState === "complete"`, so trailing DOM mutations land. */
        const val SETTLE_DELAY_MS = 500L

        /**
         * Images load by default because a few sites render listing text from an `onload` handler.
         * Set to `true` to trade DOM fidelity for speed and data.
         */
        const val BLOCK_IMAGES = false

        /** Wipes the cookie jar after every fetch, so no identifier survives between pages. */
        const val CLEAR_SESSION_COOKIES = true
    }
}