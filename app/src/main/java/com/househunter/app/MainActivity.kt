package com.househunter.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import coil.compose.LocalSingletonImageLoader
import com.househunter.app.data.remote.WebViewFetcher
import com.househunter.app.ui.images.ImageLoaderFactory
import com.househunter.app.ui.navigation.HouseHunterNavHost
import com.househunter.app.ui.theme.HouseHunterTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * The WebView scraping engine works headless, but attaching to a live window
     * makes pages that need one (popups, permission prompts) behave normally. The
     * activity is the cheapest host available, so we register it while it is alive.
     */
    @Inject
    lateinit var webViewFetcher: WebViewFetcher

    override fun onCreate(savedInstanceState: Bundle?) {
        webViewFetcher.pageProvider = WebViewFetcher.PageProvider { this }
        super.onCreate(savedInstanceState)
        setContent {
            // Photos were invisible until this loader existed: it sends the Referer that
            // hotlink-protected CDNs require, and keeps disk caching off by policy.
            val imageLoader = remember { ImageLoaderFactory.create(applicationContext) }
            CompositionLocalProvider(LocalSingletonImageLoader provides imageLoader) {
                HouseHunterTheme {
                    HouseHunterNavHost()
                }
            }
        }
    }
}