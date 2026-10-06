package za.co.nm.streamtv

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class NmPairRequest(
    val code: String = "",
    val requestSecret: String = "",
    val deviceId: String = "",
    val expiresAt: String = ""
)

data class NmPairStatus(
    val linked: Boolean = false,
    val expiresAt: String? = null,
    val deviceToken: String? = null,
    val deviceId: String? = null,
    val accountName: String? = null
)

data class NmSyncedIptv(
    val m3uUrl: String = "",
    val epgUrl: String = "",
    val xtreamServer: String = "",
    val xtreamUsername: String = "",
    val xtreamPassword: String = ""
)

data class NmRemoteSettings(
    val preferredQuality: Int = 720,
    val preferHttpDebrid: Boolean = true,
    val preferredAudioLanguage: String = "en",
    val subtitleLanguage: String = "en",
    val addonManifests: List<String> = emptyList(),
    val syncIptv: Boolean = false,
    val iptv: NmSyncedIptv? = null,
    val traktAuth: TraktStoredAuth? = null,
    val realDebridAuth: RdStoredAuth? = null,
    val settingsVersion: Long = 0L,
    val updatedAt: String = ""
)

data class NmDeviceState(
    val accountName: String = "",
    val deviceId: String = "",
    val settings: NmRemoteSettings = NmRemoteSettings()
)

data class NmPlaybackPreferences(
    val preferredQuality: Int = 720,
    val preferHttpDebrid: Boolean = true,
    val preferredAudioLanguage: String = "en",
    val subtitleLanguage: String = "en"
)

class NmAccountRepository(context: Context) {
    companion object {
        const val DASHBOARD_URL = "https://nm-stream-tv-account.floot.app"
        private const val API = "$DASHBOARD_URL/_api"

        private const val DEVICE_TOKEN_KEY = "nm_account_device_token"
        private const val DEVICE_ID_KEY = "nm_account_device_id"
        private const val ACCOUNT_NAME_KEY = "nm_account_name"
        private const val SETTINGS_VERSION_KEY = "nm_account_settings_version"
        private const val PREF_QUALITY_KEY = "nm_pref_quality"
        private const val PREF_HTTP_KEY = "nm_pref_http_debrid"
        private const val PREF_AUDIO_KEY = "nm_pref_audio"
        private const val PREF_SUBTITLE_KEY = "nm_pref_subtitle"
    }

    private val store = SecretStore(context)
    private val gson = Gson()

    fun isLinked(): Boolean = !store.get(DEVICE_TOKEN_KEY).isNullOrBlank()

    fun accountName(): String? = store.get(ACCOUNT_NAME_KEY)?.takeIf { it.isNotBlank() }

    fun lastAppliedVersion(): Long =
        store.get(SETTINGS_VERSION_KEY)?.toLongOrNull() ?: 0L

    fun playbackPreferences(): NmPlaybackPreferences =
        NmPlaybackPreferences(
            preferredQuality = store.get(PREF_QUALITY_KEY)?.toIntOrNull()?.takeIf {
                it in setOf(360, 480, 576, 720, 1080, 1440, 2160)
            } ?: 720,
            preferHttpDebrid = store.get(PREF_HTTP_KEY)?.toBooleanStrictOrNull() ?: true,
            preferredAudioLanguage = store.get(PREF_AUDIO_KEY)?.takeIf { it.isNotBlank() } ?: "en",
            subtitleLanguage = store.get(PREF_SUBTITLE_KEY)?.takeIf { it.isNotBlank() } ?: "en"
        )

    fun savePlaybackPreferences(preferences: NmPlaybackPreferences) {
        store.put(PREF_QUALITY_KEY, preferences.preferredQuality.toString())
        store.put(PREF_HTTP_KEY, preferences.preferHttpDebrid.toString())
        store.put(PREF_AUDIO_KEY, preferences.preferredAudioLanguage.ifBlank { "en" })
        store.put(PREF_SUBTITLE_KEY, preferences.subtitleLanguage.ifBlank { "en" })
    }

    suspend fun startPairing(deviceName: String, appVersion: String): NmPairRequest {
        val body = gson.toJson(
            mapOf(
                "deviceName" to deviceName,
                "appVersion" to appVersion
            )
        )
        val response = SimpleHttp.postJson("$API/pair/request", body)
        return gson.fromJson(
            SimpleHttp.requireSuccess(response, "Creating NM Account pairing code"),
            NmPairRequest::class.java
        )
    }

    suspend fun pollPairing(pair: NmPairRequest): NmPairStatus {
        val body = gson.toJson(
            mapOf(
                "code" to pair.code,
                "requestSecret" to pair.requestSecret
            )
        )
        val response = SimpleHttp.postJson("$API/pair/status", body)
        return gson.fromJson(
            SimpleHttp.requireSuccess(response, "Checking NM Account pairing"),
            NmPairStatus::class.java
        )
    }

    fun saveLinked(status: NmPairStatus) {
        val token = status.deviceToken?.takeIf { it.isNotBlank() }
            ?: error("NM Account did not return a device token")
        store.put(DEVICE_TOKEN_KEY, token)
        status.deviceId?.takeIf { it.isNotBlank() }?.let { store.put(DEVICE_ID_KEY, it) }
        status.accountName?.takeIf { it.isNotBlank() }?.let { store.put(ACCOUNT_NAME_KEY, it) }
    }

    suspend fun bootstrap(
        addonManifests: List<String>,
        preferences: NmPlaybackPreferences
    ) {
        val token = deviceToken() ?: return
        val payload = gson.toJson(
            mapOf(
                "preferredQuality" to preferences.preferredQuality,
                "preferHttpDebrid" to preferences.preferHttpDebrid,
                "preferredAudioLanguage" to preferences.preferredAudioLanguage,
                "subtitleLanguage" to preferences.subtitleLanguage,
                "addonManifests" to addonManifests
            )
        )
        val response = SimpleHttp.postJson(
            "$API/device/bootstrap",
            payload,
            mapOf("Authorization" to "Bearer $token")
        )
        val body = SimpleHttp.requireSuccess(response, "Bootstrapping NM Account")
        val version = runCatching {
            JsonParser.parseString(body).asJsonObject.get("settingsVersion")?.asLong
        }.getOrNull()
        if (version != null) store.put(SETTINGS_VERSION_KEY, version.toString())
    }

    suspend fun fetchState(): NmDeviceState {
        val token = deviceToken()
            ?: error("This TV is not linked to an NM Account")
        val response = SimpleHttp.get(
            "$API/device/state",
            mapOf("Authorization" to "Bearer $token")
        )
        if (response.code == 401) {
            unlink()
            error("NM Account link expired. Link this TV again.")
        }
        return gson.fromJson(
            SimpleHttp.requireSuccess(response, "Syncing NM Account"),
            NmDeviceState::class.java
        )
    }

    suspend fun pushPlaybackPreferences(preferences: NmPlaybackPreferences) {
        val payload = JsonObject().apply {
            addProperty("preferredQuality", preferences.preferredQuality)
            addProperty("preferHttpDebrid", preferences.preferHttpDebrid)
            addProperty("preferredAudioLanguage", preferences.preferredAudioLanguage)
            addProperty("subtitleLanguage", preferences.subtitleLanguage)
        }
        pushPartial(payload)
    }

    suspend fun pushAddonManifests(manifests: List<String>) {
        val payload = JsonObject().apply {
            add("addonManifests", gson.toJsonTree(manifests))
        }
        pushPartial(payload)
    }

    suspend fun pushIptv(syncEnabled: Boolean, config: IptvConfig?) {
        val payload = JsonObject().apply {
            addProperty("syncIptv", syncEnabled)
            if (syncEnabled) {
                add("iptv", config?.let { gson.toJsonTree(it) } ?: JsonNull.INSTANCE)
            }
        }
        pushPartial(payload)
    }

    suspend fun pushTraktAuth(auth: TraktStoredAuth?) {
        val payload = JsonObject().apply {
            add("traktAuth", auth?.let { gson.toJsonTree(it) } ?: JsonNull.INSTANCE)
        }
        pushPartial(payload)
    }

    suspend fun pushRealDebridAuth(auth: RdStoredAuth?) {
        val payload = JsonObject().apply {
            add("realDebridAuth", auth?.let { gson.toJsonTree(it) } ?: JsonNull.INSTANCE)
        }
        pushPartial(payload)
    }

    suspend fun pushAllServiceAuth(
        traktAuth: TraktStoredAuth?,
        realDebridAuth: RdStoredAuth?
    ) {
        val payload = JsonObject().apply {
            add("traktAuth", traktAuth?.let { gson.toJsonTree(it) } ?: JsonNull.INSTANCE)
            add("realDebridAuth", realDebridAuth?.let { gson.toJsonTree(it) } ?: JsonNull.INSTANCE)
        }
        pushPartial(payload)
    }

    fun applyLocalPreferences(state: NmDeviceState) {
        store.put(ACCOUNT_NAME_KEY, state.accountName.ifBlank { "NM Account" })
        savePlaybackPreferences(
            NmPlaybackPreferences(
                preferredQuality = state.settings.preferredQuality,
                preferHttpDebrid = state.settings.preferHttpDebrid,
                preferredAudioLanguage = state.settings.preferredAudioLanguage.ifBlank { "en" },
                subtitleLanguage = state.settings.subtitleLanguage.ifBlank { "en" }
            )
        )
        store.put(SETTINGS_VERSION_KEY, state.settings.settingsVersion.toString())
    }

    fun unlink() {
        listOf(
            DEVICE_TOKEN_KEY,
            DEVICE_ID_KEY,
            ACCOUNT_NAME_KEY,
            SETTINGS_VERSION_KEY
        ).forEach(store::remove)
    }

    private fun deviceToken(): String? =
        store.get(DEVICE_TOKEN_KEY)?.takeIf { it.isNotBlank() }

    private suspend fun pushPartial(payload: JsonObject) {
        val token = deviceToken() ?: return
        val response = SimpleHttp.postJson(
            "$API/device/state",
            gson.toJson(payload),
            mapOf("Authorization" to "Bearer $token")
        )
        val body = SimpleHttp.requireSuccess(response, "Syncing changes to NM Account")
        val version = JsonParser.parseString(body).asJsonObject
            .get("settingsVersion")?.asLong
        if (version != null) store.put(SETTINGS_VERSION_KEY, version.toString())
    }
}
