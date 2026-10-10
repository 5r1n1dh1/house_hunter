package com.househunter.app.data.scrape

import android.content.Context
import com.househunter.app.data.prefs.PrefsRepository
import com.househunter.app.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/** Merges built-in site configs (assets) with user-added configs (DataStore). */
@Singleton
class SiteRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefsRepository: PrefsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    @Volatile
    private var builtInCache: List<SiteConfig>? = null

    private suspend fun builtIns(): List<SiteConfig> {
        builtInCache?.let { return it }
        return withContext(io) {
            builtInCache ?: runCatching {
                val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
                val array = JSONArray(text)
                (0 until array.length()).mapNotNull { SiteConfig.fromJson(array.getJSONObject(it)) }
            }.getOrDefault(emptyList()).also { builtInCache = it }
        }
    }

    suspend fun all(): List<SiteConfig> {
        val overrides = prefsRepository.siteOverrides.first()
        val custom = prefsRepository.customSites.first()
        val builtIn = builtIns().map { site ->
            val enabled = overrides[site.id] ?: site.enabled
            site.copy(enabled = enabled)
        }
        return builtIn + custom
    }

    /** Sites that are switched on. `requiresManual` entries ship disabled until configured. */
    suspend fun enabled(): List<SiteConfig> = all().filter { it.enabled }

    suspend fun find(id: String): SiteConfig? = all().firstOrNull { it.id == id }

    companion object {
        const val ASSET = "sites_default.json"
    }
}