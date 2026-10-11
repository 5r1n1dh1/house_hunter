package com.househunter.app

import android.app.Application
import coil.ImageLoader
import com.househunter.app.ui.images.ImageLoaderFactory as HouseImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp

/**
 * Coil 2.x installs its image loader from the Application, which is the only
 * hook available before the first frame. Photos were invisible until this existed:
 * the loader sends the Referer that hotlink-protected CDNs require and keeps disk
 * caching disabled for the app's zero-caching policy.
 */
@HiltAndroidApp
class HouseHunterApp : Application(), coil.ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = HouseImageLoaderFactory.create(this)
}