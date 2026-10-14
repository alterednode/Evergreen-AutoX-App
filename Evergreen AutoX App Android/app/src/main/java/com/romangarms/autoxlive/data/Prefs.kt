package com.romangarms.autoxlive.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import java.security.SecureRandom

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

// Kept apart from the settings so clearing those can never orphan the token,
// which is what the server knows this device's joined boards and blocks by.
// Both stores are in Auto Backup, so a reinstall keeps them.
private val Context.identityStore: DataStore<Preferences> by preferencesDataStore(name = "identity")

@Serializable
data class StoredSettings(
    val selectedEventId: Int? = null,
    val liveSortByClass: Boolean = false,
    val speedhiveAutoXOnly: Boolean = true,
    val acceptedGuidelines: Boolean = false,
    val hiddenCourseIds: Set<Int> = emptySet(),
    val pinsByEvent: Map<String, List<String>> = emptyMap(),
    val nicknamesByEvent: Map<String, Map<String, String>> = emptyMap(),
    val meNumberByEvent: Map<String, String> = emptyMap(),
    val mePromptDismissedEvents: List<String> = emptyList(),
    val sessionChoice: Map<String, Int> = emptyMap(),
    val lastUpdateCheck: Long = 0,
)

interface SettingsStore {
    suspend fun load(): StoredSettings
    suspend fun save(settings: StoredSettings)
}

class DataStoreSettings(private val context: Context) : SettingsStore {
    private val key = stringPreferencesKey("settings")

    override suspend fun load(): StoredSettings {
        val raw = context.settingsStore.data.first()[key] ?: return StoredSettings()
        return runCatching { ApiClient.json.decodeFromString<StoredSettings>(raw) }.getOrDefault(StoredSettings())
    }

    override suspend fun save(settings: StoredSettings) {
        context.settingsStore.edit { it[key] = ApiClient.json.encodeToString(settings) }
    }
}

object DeviceIdentity {
    private val tokenKey = stringPreferencesKey("token")

    suspend fun token(context: Context): String {
        context.identityStore.data.first()[tokenKey]?.takeIf { it.isNotEmpty() }?.let { return it }
        var minted = ""
        context.identityStore.edit { prefs ->
            minted = prefs[tokenKey]?.takeIf { it.isNotEmpty() } ?: newToken().also { prefs[tokenKey] = it }
        }
        return minted
    }

    fun newToken(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
