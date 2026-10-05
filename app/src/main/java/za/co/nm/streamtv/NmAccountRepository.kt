package za.co.nm.streamtv

import android.content.Context
import com.google.gson.Gson

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
    val subtitleLanguage: String = "en",
    val addonManifests: List<String> = emptyList(),
    val syncIptv: Boolean = false,
    val iptv: NmSyncedIptv? = null,
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
            subtitleLanguage = store.get(PREF_SUBTITLE_KEY)?.takeIf { it.isNotBlank() } ?: "en"
        )

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
        val token = store.get(DEVICE_TOKEN_KEY)?.takeIf { it.isNotBlank() }
            ?: return
        val payload = gson.toJson(
            mapOf(
                "preferredQuality" to preferences.preferredQuality,
                "preferHttpDebrid" to preferences.preferHttpDebrid,
                "subtitleLanguage" to preferences.subtitleLanguage,
                "addonManifests" to addonManifests
            )
        )
        val response = SimpleHttp.postJson(
            "$API/device/bootstrap",
            payload,
            mapOf("Authorization" to "Bearer $token")
        )
        SimpleHttp.requireSuccess(response, "Bootstrapping NM Account")
    }

    suspend fun fetchState(): NmDeviceState {
        val token = store.get(DEVICE_TOKEN_KEY)?.takeIf { it.isNotBlank() }
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

    fun applyLocalPreferences(state: NmDeviceState) {
        store.put(ACCOUNT_NAME_KEY, state.accountName.ifBlank { "NM Account" })
        store.put(PREF_QUALITY_KEY, state.settings.preferredQuality.toString())
        store.put(PREF_HTTP_KEY, state.settings.preferHttpDebrid.toString())
        store.put(PREF_SUBTITLE_KEY, state.settings.subtitleLanguage.ifBlank { "en" })
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
}
