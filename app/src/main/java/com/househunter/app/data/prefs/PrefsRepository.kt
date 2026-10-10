package com.househunter.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.househunter.app.data.scrape.SiteConfig
import com.househunter.app.domain.model.Filters
import com.househunter.app.domain.model.OsmPlace
import com.househunter.app.domain.model.Sort
import com.househunter.app.domain.model.UserPrefs
import com.househunter.app.domain.model.WorkLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "house_hunter_settings")

/**
 * The only persisted state in the app: user settings and user-added site
 * configs. No listing data is ever written to disk.
 */
@Singleton
class PrefsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val STATE_ID = stringPreferencesKey("state_id")
        val STATE_NAME = stringPreferencesKey("state_name")
        val DISTRICT_ID = stringPreferencesKey("district_id")
        val DISTRICT_NAME = stringPreferencesKey("district_name")
        val LOCALITY_ID = stringPreferencesKey("locality_id")
        val LOCALITY_NAME = stringPreferencesKey("locality_name")
        val WORK1 = stringPreferencesKey("work1_json")
        val WORK2 = stringPreferencesKey("work2_json")
        val RENT_MIN = doublePreferencesKey("rent_min")
        val RENT_MAX = doublePreferencesKey("rent_max")
        val BHK = stringPreferencesKey("bhk")
        val MAX_KM_1 = doublePreferencesKey("max_km_work1")
        val MAX_KM_2 = doublePreferencesKey("max_km_work2")
        val CONTACT_ONLY = booleanPreferencesKey("contact_only")
        val SORT = stringPreferencesKey("sort")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val CUSTOM_SITES = stringPreferencesKey("custom_sites")
        val SITE_OVERRIDES = stringPreferencesKey("site_overrides")
    }

    private val preferences: Flow<Preferences> = context.dataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }

    val prefs: Flow<UserPrefs> = preferences.map { p ->
        UserPrefs(
            state = p.place(Keys.STATE_ID, Keys.STATE_NAME, 4),
            district = p.place(Keys.DISTRICT_ID, Keys.DISTRICT_NAME, 6),
            locality = p.place(Keys.LOCALITY_ID, Keys.LOCALITY_NAME, 8),
            work1 = p.work(Keys.WORK1),
            work2 = p.work(Keys.WORK2),
            filters = Filters(
                rentMin = p[Keys.RENT_MIN],
                rentMax = p[Keys.RENT_MAX],
                bhk = p[Keys.BHK].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet(),
                maxKmWork1 = p[Keys.MAX_KM_1],
                maxKmWork2 = p[Keys.MAX_KM_2],
                contactOnly = p[Keys.CONTACT_ONLY] ?: false,
            ),
            sort = p[Keys.SORT]?.let { runCatching { Sort.valueOf(it) }.getOrNull() } ?: Sort.PRICE_ASC,
            onboarded = p[Keys.ONBOARDED] ?: false,
        )
    }

    val customSites: Flow<List<SiteConfig>> = preferences.map { decodeSites(it[Keys.CUSTOM_SITES]) }

    val siteOverrides: Flow<Map<String, Boolean>> = preferences.map { p ->
        decodeOverrides(p[Keys.SITE_OVERRIDES])
    }

    suspend fun current(): UserPrefs = prefs.first()

    suspend fun setLocation(state: OsmPlace?, district: OsmPlace?, locality: OsmPlace?) {
        context.dataStore.edit { p ->
            writePlace(p, Keys.STATE_ID, Keys.STATE_NAME, state)
            writePlace(p, Keys.DISTRICT_ID, Keys.DISTRICT_NAME, district)
            writePlace(p, Keys.LOCALITY_ID, Keys.LOCALITY_NAME, locality)
            p[Keys.ONBOARDED] = true
        }
    }

    suspend fun setWork(index: Int, location: WorkLocation?) {
        context.dataStore.edit { p ->
            val key = if (index == 0) Keys.WORK1 else Keys.WORK2
            if (location == null) p.remove(key) else p[key] = JSONObject().apply {
                put("label", location.label)
                put("lat", location.lat)
                put("lng", location.lng)
            }.toString()
        }
    }

    suspend fun setFilters(filters: Filters) {
        context.dataStore.edit { p ->
            filters.rentMin?.let { p[Keys.RENT_MIN] = it } ?: p.remove(Keys.RENT_MIN)
            filters.rentMax?.let { p[Keys.RENT_MAX] = it } ?: p.remove(Keys.RENT_MAX)
            filters.maxKmWork1?.let { p[Keys.MAX_KM_1] = it } ?: p.remove(Keys.MAX_KM_1)
            filters.maxKmWork2?.let { p[Keys.MAX_KM_2] = it } ?: p.remove(Keys.MAX_KM_2)
            p[Keys.BHK] = filters.bhk.sorted().joinToString(",")
            p[Keys.CONTACT_ONLY] = filters.contactOnly
        }
    }

    suspend fun setSort(sort: Sort) {
        context.dataStore.edit { it[Keys.SORT] = sort.name }
    }

    suspend fun upsertSite(site: SiteConfig) {
        context.dataStore.edit { p ->
            val current = decodeSites(p[Keys.CUSTOM_SITES]).toMutableList()
            val index = current.indexOfFirst { it.id == site.id }
            val copy = site.copy(isCustom = true)
            if (index >= 0) current[index] = copy else current.add(copy)
            p[Keys.CUSTOM_SITES] = encodeSites(current)
        }
    }

    suspend fun deleteSite(id: String) {
        context.dataStore.edit { p ->
            p[Keys.CUSTOM_SITES] = encodeSites(decodeSites(p[Keys.CUSTOM_SITES]).filterNot { it.id == id })
        }
    }

    suspend fun setSiteEnabled(id: String, enabled: Boolean) {
        context.dataStore.edit { p ->
            val overrides = decodeOverrides(p[Keys.SITE_OVERRIDES]).toMutableMap()
            overrides[id] = enabled
            p[Keys.SITE_OVERRIDES] = JSONObject(overrides).toString()
        }
    }

    private fun Preferences.place(
        idKey: Preferences.Key<String>,
        nameKey: Preferences.Key<String>,
        level: Int,
    ): OsmPlace? {
        val id = this[idKey] ?: return null
        val name = this[nameKey] ?: return null
        return OsmPlace(id.toLongOrNull() ?: return null, name, level)
    }

    private fun Preferences.work(key: Preferences.Key<String>): WorkLocation? {
        val raw = this[key] ?: return null
        return runCatching {
            val json = JSONObject(raw)
            WorkLocation(
                label = json.optString("label", "Work"),
                lat = json.getDouble("lat"),
                lng = json.getDouble("lng"),
            )
        }.getOrNull()
    }

    private fun writePlace(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        idKey: Preferences.Key<String>,
        nameKey: Preferences.Key<String>,
        place: OsmPlace?,
    ) {
        if (place == null) {
            prefs.remove(idKey)
            prefs.remove(nameKey)
        } else {
            prefs[idKey] = place.osmId.toString()
            prefs[nameKey] = place.name
        }
    }

    private fun encodeSites(sites: List<SiteConfig>): String {
        val array = JSONArray()
        sites.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    private fun decodeSites(raw: String?): List<SiteConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { SiteConfig.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun decodeOverrides(raw: String?): Map<String, Boolean> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.optBoolean(it) }
        }.getOrDefault(emptyMap())
    }
}