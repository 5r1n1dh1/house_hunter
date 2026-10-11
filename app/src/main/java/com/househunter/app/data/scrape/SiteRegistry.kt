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

/**
 * Source of site configurations.
 *
 * An interface rather than a concrete class so the scrape orchestration can be
 * unit-tested on a plain JVM: the real implementation needs an Android `Context`
 * to read the bundled asset and a DataStore-backed `PrefsRepository` for
 * user-added sites, neither of which exists off-device. Consumers depend on this
 * type, so the Hilt binding is unchanged.
 */
interface SiteRegistry {
    /** Every known site: bundled plus user-added. */
    suspend fun all(): List<SiteConfig>

    /** Sites that are switched on. `requiresManual` entries ship disabled until configured. */
    suspend fun enabled(): List<SiteConfig>

    suspend fun find(id: String): SiteConfig?
}

/**
 * Merges built-in site configs (assets) with user-added configs (DataStore).
 *
 * [all] is safe to call concurrently: [builtInCache] is written once, after its
 * immutable [List] is fully built, and `@Volatile` supplies the happens-before
 * edge, so a reader sees either `null` or a complete value. Concurrent first
 * calls may duplicate the parse, but never observe a torn one.
 */
@Singleton
class DefaultSiteRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefsRepository: PrefsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SiteRegistry {

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

    override suspend fun all(): List<SiteConfig> {
        val overrides = prefsRepository.siteOverrides.first()
        val custom = prefsRepository.customSites.first()
        val builtIn = builtIns().map { site ->
            val enabled = overrides[site.id] ?: site.enabled
            site.copy(enabled = enabled)
        }
        return builtIn + custom
    }

    /** Sites that are switched on. `requiresManual` entries ship disabled until configured. */
    override suspend fun enabled(): List<SiteConfig> = all().filter { it.enabled }

    override suspend fun find(id: String): SiteConfig? = all().firstOrNull { it.id == id }

    companion object {
        const val ASSET = "sites_default.json"
    }
}