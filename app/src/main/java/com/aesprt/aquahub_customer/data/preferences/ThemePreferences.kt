package com.aesprt.aquahub_customer.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import com.aesprt.aquahub_customer.domain.CustomerAcquisitionSource
import com.aesprt.aquahub_customer.domain.PendingStationLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.aquaHubPreferences by preferencesDataStore(name = "aquahub_customer_preferences")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

class ThemePreferences(private val context: Context) {
    private val key = stringPreferencesKey("theme_mode")
    private val pendingStationLinkKey = stringPreferencesKey("pending_station_link")
    val mode: Flow<ThemeMode> = context.aquaHubPreferences.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[key] ?: ThemeMode.SYSTEM.name) }.getOrDefault(ThemeMode.SYSTEM)
    }
    suspend fun setMode(mode: ThemeMode) {
        context.aquaHubPreferences.edit { it[key] = mode.name }
    }

    /**
     * Keeps a station link that was opened before authentication survives
     * process recreation. The link is intentionally device-local and is
     * cleared after it is consumed or when the user explicitly signs out.
     */
    val pendingStationLink: Flow<PendingStationLink?> = context.aquaHubPreferences.data.map { prefs ->
        prefs[pendingStationLinkKey]?.split('|', limit = 2)?.let { parts ->
            val code = parts.firstOrNull().orEmpty()
            val source = parts.getOrNull(1)?.let(CustomerAcquisitionSource::fromString)
                ?: CustomerAcquisitionSource.STATION_LINK
            code.takeIf { it.isNotBlank() }?.let { PendingStationLink(it, source) }
        }
    }

    suspend fun setPendingStationLink(link: PendingStationLink) {
        context.aquaHubPreferences.edit { prefs ->
            prefs[pendingStationLinkKey] = "${link.publicStationCode}|${link.source.name}"
        }
    }

    suspend fun clearPendingStationLink() {
        context.aquaHubPreferences.edit { it.remove(pendingStationLinkKey) }
    }

    suspend fun clear() {
        context.aquaHubPreferences.edit {
            it.remove(key)
            it.remove(pendingStationLinkKey)
        }
    }
}
