package com.househunter.app.ui.images

import android.content.Context
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.Options
import com.househunter.app.data.scrape.ImageUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val HEADER_REFERER = "Referer"
private const val HEADER_USER_AGENT = "User-Agent"

/**
 * Builds the single [ImageLoader] used for every listing photo.
 *
 * Why a custom loader instead of the Coil default:
 *  - **Referer.** `assets.nobroker.in` and `img.staticmb.com` are hotlink protected and answer
 *    `403 Forbidden` to any request without a `Referer`, which is exactly why listing photos
 *    rendered blank. [ListingImageFetcher] therefore identifies itself and attaches a referer -
 *    per request via [withListingHeaders], otherwise the image's own origin.
 *  - **Zero caching.** The app has an explicit no-caching policy, so no downloaded photo may be
 *    written to disk. Coil would otherwise install its own singleton disk cache and silently
 *    persist every image, so the disk cache is explicitly disabled here (see [create]).
 *  - **Coil 2.x.** This targets `io.coil-kt:coil-compose:2.7.0`, where per-request HTTP headers go
 *    through [ImageRequest.Builder.setHeader]; the equivalent call in Coil 3 is `httpHeaders(...)`.
 */
object ImageLoaderFactory {

    /** Identifying User-Agent; some CDNs also refuse anonymous image requests. */
    const val USER_AGENT = "HouseHunterBot/1.0"

    /**
     * Creates the shared image loader, resolving the application context itself.
     *
     * Prefer the overload that takes a [Context] when one is at hand; this overload only exists so
     * the loader can be built from a no-argument call site.
     *
     * @param client optional shared OkHttp client; a fresh cache-less one is built when absent.
     * @throws IllegalStateException when no application context can be resolved.
     */
    fun create(client: OkHttpClient? = null): ImageLoader =
        create(applicationContext(), client)

    /**
     * Creates the shared image loader with an explicit [context] (pass `applicationContext`).
     *
     * @param client optional shared OkHttp client; a fresh cache-less one is built when absent.
     */
    fun create(context: Context, client: OkHttpClient? = null): ImageLoader {
        val httpClient = client ?: defaultClient()
        return ImageLoader.Builder(context)
            // The fetcher below owns every http(s) request, so it needs the same client.
            .okHttpClient(httpClient)
            .components {
                // Registered first: user components are consulted before Coil's built-in
                // HttpUriFetcher, which knows nothing about Referer headers.
                add(ListingImageFetcher.Factory(httpClient), Uri::class.java)
            }
            // Memory cache only: it dies with the process and is invisible to the user.
            .memoryCachePolicy(CachePolicy.ENABLED)
            // Hard zero-caching. `.diskCache(null)` matters: without it Coil creates its own
            // singleton disk cache under the cache dir and persists every photo it downloads.
            .diskCache(null)
            .diskCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            // Remote `Cache-Control`/`ETag` headers must not be able to re-create hidden caching.
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
    }

    /**
     * Fallback client: polite timeouts, no OkHttp disk cache (zero-caching policy), no interceptors
     * so that image requests keep exactly the headers set by [ListingImageFetcher].
     */
    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Best-effort application context lookup for the no-argument [create] overload: the loader needs
     * a `Context` for the memory cache and its temp files, and the application outlives any screen.
     */
    private fun applicationContext(): Context {
        val app = runCatching {
            Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null)
        }.getOrNull() as? Context
        return checkNotNull(app?.applicationContext) {
            "No application context available; call ImageLoaderFactory.create(context) instead."
        }
    }
}

/**
 * Attaches the listing's `Referer` origin to this image request.
 *
 * Call sites already know the listing detail URL, so
 * `ImageRequest.Builder.withListingHeaders(ImageUrl.baseRefererFor(listing.url))` is enough to get
 * past the 403s that hotlink-protected CDNs return for referer-less image requests. A blank or
 * missing referer is ignored - the fetcher then falls back to the image's own origin.
 */
fun ImageRequest.Builder.withListingHeaders(referer: String?): ImageRequest.Builder = apply {
    val value = referer?.trim()
    if (!value.isNullOrBlank()) setHeader(HEADER_REFERER, value)
}

/**
 * Fetches http(s) images through OkHttp with an identifying User-Agent and a `Referer`.
 *
 * Registered ahead of Coil's built-in network fetcher so it wins for every http(s) image; its
 * factory returns `null` for other schemes so assets/drawables keep using the default components.
 */
private class ListingImageFetcher(
    private val client: OkHttpClient,
    private val url: String,
    private val options: Options,
    private val defaultReferer: String?,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        // Coil already fetches off the main thread; Dispatchers.IO keeps that true regardless.
        val response = withContext(Dispatchers.IO) {
            client.newCall(buildRequest()).execute()
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("Image request failed with HTTP $code: $url")
        }
        val body = response.body
        if (body == null) {
            response.close()
            throw IOException("Image response had no body: $url")
        }
        return SourceResult(
            // The body deliberately stays open: the decoder reads it lazily from this source.
            source = ImageSource(body.source(), options.context),
            mimeType = response.header("Content-Type")?.substringBefore(';')?.trim(),
            dataSource = DataSource.NETWORK,
        )
    }

    private fun buildRequest(): Request {
        val builder = Request.Builder()
            .url(url)
            // Per-request headers first (see withListingHeaders); our own header wins below.
            .headers(options.headers)
            .header(HEADER_USER_AGENT, ImageLoaderFactory.USER_AGENT)
            .header("Accept", "image/*")
        referer()?.let { builder.header(HEADER_REFERER, it) }
        return builder.build()
    }

    /** Per-request referer, else the loader default, else the image host's own origin. */
    private fun referer(): String? =
        options.headers[HEADER_REFERER]?.takeIf { it.isNotBlank() }
            ?: defaultReferer?.takeIf { it.isNotBlank() }
            ?: ImageUrl.baseRefererFor(url)

    /**
     * Creates a [ListingImageFetcher] for http(s) URIs.
     *
     * @param defaultReferer referer to use when a request carries none; `null` means "use the
     *   image's own origin".
     */
    class Factory(
        private val client: OkHttpClient,
        private val defaultReferer: String? = null,
    ) : Fetcher.Factory<Uri> {

        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val scheme = data.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            return ListingImageFetcher(client, data.toString(), options, defaultReferer)
        }
    }
}