package com.househunter.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.househunter.app.data.remote.WebViewFetcher
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
            HouseHunterTheme {
                HouseHunterNavHost()
            }
        }
    }
}