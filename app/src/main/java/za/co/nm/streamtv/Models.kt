package za.co.nm.streamtv

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class AddonManifest(
    val id: String = "",
    val version: String = "",
    val name: String = "Unnamed add-on",
    val description: String? = null,
    val resources: List<JsonElement> = emptyList(),
    val types: List<String> = emptyList(),
    val catalogs: List<CatalogSpec> = emptyList()
)

data class CatalogSpec(
    val type: String = "",
    val id: String = "",
    val name: String? = null,
    val extra: List<CatalogExtra> = emptyList()
)

data class CatalogExtra(
    val name: String = "",
    val isRequired: Boolean? = null,
    val options: List<String>? = null
)

data class InstalledAddon(
    val manifestUrl: String,
    val baseUrl: String,
    val manifest: AddonManifest,
    val resourceQuery: String? = null
)

data class CatalogResponse(val metas: List<MetaItem> = emptyList())
data class MetaResponse(val meta: MetaItem? = null)
data class StreamResponse(val streams: List<AddonStream> = emptyList())
data class SubtitleResponse(val subtitles: List<AddonSubtitle> = emptyList())

data class MetaItem(
    val id: String = "",
    val type: String = "movie",
    val name: String = "Untitled",
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val videos: List<VideoItem> = emptyList()
)

data class VideoItem(
    val id: String = "",
    val title: String? = null,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null
) {
    fun displayName(): String {
        val prefix = when {
            season != null && episode != null -> "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
            episode != null -> "Episode $episode"
            else -> null
        }
        val label = title ?: name ?: "Episode"
        return listOfNotNull(prefix, label).joinToString(" · ")
    }
}

data class AddonStream(
    val name: String? = null,
    val title: String? = null,
    val url: String? = null,
    val ytId: String? = null,
    val externalUrl: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val behaviorHints: StreamBehaviorHints? = null
)

data class StreamBehaviorHints(
    val filename: String? = null,
    val videoSize: Long? = null,
    val notWebReady: Boolean? = null,
    val bingeGroup: String? = null,
    val proxyHeaders: ProxyHeaders? = null
)

data class ProxyHeaders(
    val request: Map<String, String>? = null,
    val response: Map<String, String>? = null
)

data class AddonSubtitle(
    val id: String = "",
    val url: String = "",
    val lang: String = "und"
)

data class AppMedia(
    val meta: MetaItem,
    val originManifestUrl: String? = null,
    val originAddonName: String? = null,
    val directUrl: String? = null,
    val epgId: String? = null
)

data class EpgProgramme(
    val channelId: String,
    val title: String,
    val description: String? = null,
    val startMs: Long,
    val stopMs: Long
) {
    fun isLive(nowMs: Long = System.currentTimeMillis()): Boolean =
        startMs <= nowMs && stopMs > nowMs
}

data class LiveTvCategory(
    val name: String,
    val channels: List<AppMedia>
)

data class StreamOption(
    val addonName: String,
    val stream: AddonStream
) {
    val playableUrl: String?
        get() = stream.url?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }

    val requestHeaders: Map<String, String>
        get() = stream.behaviorHints?.proxyHeaders?.request.orEmpty()

    fun displayTitle(): String = stream.title
        ?: stream.name
        ?: stream.behaviorHints?.filename
        ?: "Stream"

    fun statusText(): String = when {
        playableUrl != null -> "Ready to play"
        !stream.externalUrl.isNullOrBlank() -> "Opens externally"
        !stream.infoHash.isNullOrBlank() -> "Torrent source — configure the add-on to return a playable URL"
        else -> "No directly playable URL returned"
    }
}

data class SubtitleOption(
    val addonName: String,
    val subtitle: AddonSubtitle
)

data class PlaybackProgress(
    val media: AppMedia,
    val videoId: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
    val cloudPercent: Double? = null,
    val source: String? = null
) {
    val percent: Int
        get() = cloudPercent?.toInt()?.coerceIn(0, 100)
            ?: if (durationMs <= 0) 0 else ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100)
}

data class RdDeviceCode(
    @SerializedName("device_code") val deviceCode: String = "",
    @SerializedName("user_code") val userCode: String = "",
    val interval: Int = 5,
    @SerializedName("expires_in") val expiresIn: Int = 1800,
    @SerializedName("verification_url") val verificationUrl: String = "https://real-debrid.com/device"
)

data class RdBoundCredentials(
    @SerializedName("client_id") val clientId: String = "",
    @SerializedName("client_secret") val clientSecret: String = ""
)

data class RdTokenResponse(
    @SerializedName("access_token") val accessToken: String = "",
    @SerializedName("expires_in") val expiresIn: Long = 3600,
    @SerializedName("token_type") val tokenType: String = "Bearer",
    @SerializedName("refresh_token") val refreshToken: String = ""
)

data class RdStoredAuth(
    val clientId: String,
    val clientSecret: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long
)

data class RdUser(
    val id: Long = 0,
    val username: String = "",
    val email: String = "",
    val points: Int = 0,
    val avatar: String? = null,
    val type: String = "",
    val premium: Long = 0,
    val expiration: String? = null
)

data class RdDownload(
    val id: String = "",
    val filename: String = "",
    val mimeType: String? = null,
    val filesize: Long = 0,
    val link: String? = null,
    val host: String? = null,
    val chunks: Int? = null,
    val download: String? = null,
    val generated: String? = null,
    val type: String? = null
)

data class TraktDeviceCode(
    @SerializedName("device_code") val deviceCode: String = "",
    @SerializedName("user_code") val userCode: String = "",
    @SerializedName("verification_url") val verificationUrl: String = "https://trakt.tv/activate",
    @SerializedName("expires_in") val expiresIn: Int = 600,
    val interval: Int = 5
)

data class TraktToken(
    @SerializedName("access_token") val accessToken: String = "",
    @SerializedName("refresh_token") val refreshToken: String = "",
    @SerializedName("expires_in") val expiresIn: Long = 604800,
    @SerializedName("created_at") val createdAt: Long = 0,
    @SerializedName("token_type") val tokenType: String = "bearer"
)

data class TraktStoredAuth(
    val clientId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long
)

data class TraktUser(
    val username: String = "",
    val name: String = ""
)
