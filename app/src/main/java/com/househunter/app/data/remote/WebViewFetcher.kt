package com.househunter.app.data.remote

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A [PageFetcher] backed by a real (offscreen) Android WebView.
 *
 * WHY this exists: some hosts reject anything that does not look like a browser, and they reject it
 * below the HTTP client, in the CDN/WAF:
 *  - 99acres answers **403** because Cloudflare fingerprints the TLS/JA3 stack (OkHttp ≠ Chrome),
 *  - housing.com answers **406** when the Accept header does not match a known browser,
 *  - OLX simply refuses the connection.
 * None of that can be fixed by better headers. An Android WebView *is* Chromium with Chrome's
 * TLS and HTTP stack, so the same URL loads fine there — no user ever sees the page.
 *
 * HOW it stays out of the UI: a fresh [WebView] is created per call, driven headlessly, and
 * destroyed in a `finally`. Nothing is ever added to a Compose tree, and the WebView is only
 * attached to the hierarchy when a host [Activity] is available (see [PageProvider]) — and even
 * then it is a 1x1 invisible container, so it occupies no pixels and swallows no touches.
 *
 * WHY an `Application` context: the fetcher is a `@Singleton` owned by the DI graph, which outlives
 * any Activity; holding an Activity reference between `fetch()` calls would leak it. The
 * consequences of an Application-context WebView are documented on [PageProvider]:
 *  - every WebView call (construct, settings, `loadUrl`, `evaluateJavascript`, `destroy`) must run
 *    on the **main thread** — this class therefore does `withContext(Dispatchers.Main)` around the
 *    whole page lifecycle and is `@Singleton`-scoped behind a [Mutex],
 *  - it has no `Window`, so it cannot show JS dialogs, file pickers or fullscreen video. JS dialogs
 *    are auto-dismissed (see [offscreenChromeClient]); anything else is not needed to scrape HTML.
 *
 * WIRING (left to the integrator, no other file is touched here):
 * ```
 * @Provides @Singleton
 * fun provideWebViewFetcher(@ApplicationContext app: Application) = WebViewFetcher(app)
 * ```
 * and, from `MainActivity.onCreate`, `webViewFetcher.pageProvider = PageProvider { this }` so an
 * Activity host exists while the UI is up. The provider is a settable property rather than a
 * constructor parameter so the Hilt graph keeps working when no Activity exists (background work).
 */
@Singleton
class WebViewFetcher @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : PageFetcher {

    /**
     * Supplies the Activity that can host the invisible 1x1 container, if one is alive right now.
     *
     * It is a `fun interface` (i.e. a lambda) so `PageProvider { this }` works from an Activity.
     * It is read **on the main thread**, once per fetch, and may return `null` at any time (the
     * Activity may be finishing, or the app may be running without any UI) — the fetch then runs
     * with an Application-context WebView only, which is enough for ordinary page loads and DOM
     * extraction; attaching is an optimisation for the cases that genuinely need a real window
     * (popups, permissions, some lazy-loaded content).
     */
    fun interface PageProvider {
        /** The current Activity, or `null` when the app has no visible UI. */
        fun currentActivity(): Activity?
    }

    /** Optional, set by the UI layer; `null` (the default) means "never attach to an Activity". */
    @Volatile
    var pageProvider: PageProvider? = null

    /** One WebView at a time: Chromium is process-wide state and a second one fights the first. */
    private val mutex = Mutex()

    /** Only touched while [mutex] is held, so no atomics are needed. */
    private var lastLoadStartedAtMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Loads [url] in a throwaway WebView and returns the fully rendered DOM, or `null` when the
     * page could not be loaded (DNS/connection refused, a WAF error page, or the timeout firing).
     *
     * Access is serialised and rate limited exactly like [HttpFetcher]: a WAF that sees one request
     * every [minIntervalMs] behaves; one that sees a burst bans.
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

    /** Polite delay between page loads; honours the per-site limit the caller passed in. */
    private suspend fun throttle(minIntervalMs: Long) {
        val waitMs = (lastLoadStartedAtMs + minIntervalMs.coerceAtLeast(0L)) - System.currentTimeMillis()
        if (waitMs > 0) delay(waitMs)
        lastLoadStartedAtMs = System.currentTimeMillis()
    }

    /**
     * The whole page lifecycle on the main thread: create → attach → load → read DOM → destroy.
     *
     * The `CancellationException` rethrow is deliberate: wrapping this in a blanket `runCatching`
     * would swallow cancellation and keep a destroyed page's work running.
     */
    private suspend fun loadAndExtract(url: String, referer: String?): String? = withContext(Dispatchers.Main.immediate) {
        try {
            coroutineScope {
                val outcome = CompletableDeferred<String?>()
                // First terminal callback wins: a WAF 403 arrives *and then* onPageFinished fires,
                // and the error must not be overwritten by the challenge page's DOM.
                val settled = AtomicBoolean(false)
                val session = Offscreen(createWebView())

                try {
                    session.view.webViewClient = pageClient(outcome, settled)
                    session.view.webChromeClient = offscreenChromeClient()
                    load(session.view, url, referer)
                    withTimeoutOrNull(PAGE_TIMEOUT_MS) { outcome.await() }
                } finally {
                    destroySession(session)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // No WebView provider on this device, a destroyed WebView mid-read, … all mean
            // "this engine is unavailable for now", not "crash the scrape".
            null
        }
    }

    private fun createWebView(): WebView = WebView(appContext).apply {
        configure(this)
        attachToActivity(this)
    }

    /** Desktop Chrome UA, JS on, no persisted storage, no zoom/viewport games. */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        view.settings.apply {
            // The single most important setting: the default WebView UA contains the "; wv" token
            // that every WAF fingerprints, and these sites serve a different layout without a
            // desktop UA (often an "enable JavaScript" interstitial instead of listings).
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
            // Offscreen pages must not be able to reach the filesystem or the assets provider.
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            loadsImagesAutomatically = !BLOCK_IMAGES
            // Every scrape target is https; mixed content is never needed and is a red flag.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setDefaultTextEncodingName("utf-8")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        if (BuildConfig.DEBUG) {
            // Lets the integrator inspect the DOM in chrome://inspect when a site misbehaves.
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    /**
     * Hides the WebView inside an invisible 1x1 container in the host Activity, *if* one exists.
     *
     * Only needed for the cases where Chromium wants a real window (popups, permission prompts,
     * some lazy-loading heuristics); it is always invisible, never focusable and never touchable.
     */
    private fun attachToActivity(view: WebView) {
        val activity = runCatching { pageProvider?.currentActivity() }.getOrNull() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val content = runCatching { activity.findViewById<ViewGroup>(android.R.id.content) }.getOrNull() ?: return
        val host = FrameLayoutHost(activity)
        content.addView(host, ViewGroup.LayoutParams(1, 1))
        attachedHost = host
    }

    private fun load(view: WebView, url: String, referer: String?) {
        // loadUrl's extra headers only apply to this first main-frame request (not to subresources),
        // which is exactly what a hotlink-protected Referer check needs.
        if (referer.isNullOrBlank()) view.loadUrl(url) else view.loadUrl(url, mapOf("Referer" to referer))
    }

    private fun pageClient(outcome: CompletableDeferred<String?>, settled: AtomicBoolean): WebViewClient =
        object : WebViewClient() {

            override fun onPageFinished(view: WebView, url: String) {
                if (!settled.compareAndSet(false, true)) return
                // The DOM read is a *suspend* step (it polls, then calls evaluateJavascript) and the
                // callback is on the main thread, so it is handed to a child coroutine of the
                // enclosing scope instead of blocking the main thread.
                launch {
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
                // Subresource failures are the page's business; only a dead main frame is fatal.
                if (!request.isForMainFrame) return
                if (settled.compareAndSet(false, true)) outcome.complete(null)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceResponse,
            ) {
                // The WAF case: 403/406 arrives with a rendered challenge page. Handing that DOM
                // to the scraper would parse noise, so report the block as a failure instead.
                if (!request.isForMainFrame || error.statusCode < 400) return
                if (settled.compareAndSet(false, true)) outcome.complete(null)
            }
        }

    /**
     * A headless page has nobody to answer `alert()`; leaving the default handler in place would
     * suspend JS execution and wedge the page until the timeout fired.
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
     * event, which can precede the last DOM mutation. After the flag flips we still give the page a
     * short settle delay, and if it never flips we extract anyway — a late DOM beats no DOM.
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
        // evaluateJavascript hands back a JSON-encoded value, so the HTML arrives quoted and
        // escaped; unescape it back into real markup.
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }

    private suspend fun evaluate(view: WebView, script: String): String? = suspendCancellableCoroutine { cont ->
        view.evaluateJavascript(script) { raw ->
            if (cont.isActive) cont.resumeWith(Result.success(raw))
        }
    }

    /**
     * Idempotent, main-thread teardown. The `finally` already runs on the main thread, but a
     * cancellation that wins the race can land elsewhere, hence the handler post.
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
            session.view.webChromeClient = null
            session.view.webViewClient = null
        }
        runCatching { (session.host?.parent as? ViewGroup)?.removeView(session.host) }
        runCatching { session.view.destroy() }
        if (CLEAR_SESSION_COOKIES) {
            // Leaves no identifier behind for the next fetch. This clears the process-wide cookie
            // jar, which is safe here because this app's only WebView is this offscreen one.
            runCatching { CookieManager.getInstance().removeAllCookies(null) }
        }
    }

    /** One fetch's WebView plus the 1x1 container it may have been attached to. */
    private class Offscreen(val view: WebView) {
        val destroyed = AtomicBoolean(false)
        var host: ViewGroup? = null
    }

    companion object {
        /**
         * A current desktop Chrome UA. Chromium's own UA has to be plausible: 99acres, housing.com
         * and OLX all serve a different (or blocked) document to anything that is not a desktop
         * browser, and an old Chrome version is itself a bot signal.
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

        /** Hard ceiling for one page: slow CDN + JS hydration, but never hang the scrape forever. */
        const val PAGE_TIMEOUT_MS = 45_000L

        /** How many times the readiness flag is polled before the DOM is read anyway. */
        const val READY_POLL_ATTEMPTS = 4

        /** Gap between readiness polls. */
        const val READY_POLL_INTERVAL_MS = 750L

        /** Quiet period after `readyState === "complete"` so trailing DOM mutations land. */
        const val SETTLE_DELAY_MS = 500L

        /**
         * Images are loaded (the default) because a few sites render listing text from an
         * `onload` handler. Flip to `true` to trade DOM fidelity for speed and data.
         */
        const val BLOCK_IMAGES = false

        /** Wipes the cookie jar after every fetch so no identifier survives between pages. */
        const val CLEAR_SESSION_COOKIES = true
    }
}

/** The invisible 1x1 container the offscreen WebView lives in, when an Activity host exists. */
private fun FrameLayoutHost(context: Context): ViewGroup = android.widget.FrameLayout(context).apply {
    visibility = View.INVISIBLE
    isFocusable = false
    isFocusableInTouchMode = false
    isClickable = false
}