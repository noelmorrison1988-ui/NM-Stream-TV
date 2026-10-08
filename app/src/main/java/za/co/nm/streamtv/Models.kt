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
    val tmdbId: Int? = null,
    val contentRating: String? = null,
    val contentAdvisories: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val isAnime: Boolean = false,
    val videos: List<VideoItem> = emptyList(),
    val trailers: List<TrailerRef> = emptyList(),
    val cast: List<String> = emptyList(),
    val director: String? = null,
    val runtimeMinutes: Int? = null,
    val fullReleaseDate: String? = null,
    val ratingCount: Int? = null,
    // IMDb title identifiers are understood by major Stremio stream add-ons.
    val imdbId: String? = null,
    // All known TV seasons; episode lists are fetched only on selection.
    val seasonNumbers: List<Int> = emptyList()
)

data class TrailerRef(
    val source: String? = null,
    val type: String? = null,
    val url: String? = null,
    val ytId: String? = null,
    val title: String? = null,
    val name: String? = null,
    val quality: Int? = null,
    val official: Boolean? = null
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

enum class SearchCategory {
    MOVIE,
    SERIES,
    PERSON
}

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

    val youtubeUrl: String?
        get() = stream.ytId?.takeIf { it.isNotBlank() }
            ?.let { "https://www.youtube.com/watch?v=$it" }

    val requestHeaders: Map<String, String>
        get() = stream.behaviorHints?.proxyHeaders?.request.orEmpty()

    private val searchableText: String
        get() = listOfNotNull(
            addonName,
            stream.name,
            stream.title,
            stream.behaviorHints?.filename
        ).joinToString(" ").lowercase()

    val detectedQuality: Int?
        get() = when {
            Regex("""\b2160p?\b|\b4k\b""", RegexOption.IGNORE_CASE).containsMatchIn(searchableText) -> 2160
            Regex("""\b1440p?\b""", RegexOption.IGNORE_CASE).containsMatchIn(searchableText) -> 1440
            Regex("""\b1080p?\b""", RegexOption.IGNORE_CASE).containsMatchIn(searchableText) -> 1080
            Regex("""\b720p?\b""", RegexOption.IGNORE_CASE).containsMatchIn(searchableText) -> 720
            Regex("""\b576p?\b""", RegexOption.IGNORE_CASE).containsMatchIn(searchableText) -> 576
            Regex("""\b480p?\b""", RegexOption.IGNORE_CASE).containsMatchIn(searchableText) -> 480
            Regex("""\b360p?\b""", RegexOption.IGNORE_CASE).containsMatchIn(searchableText) -> 360
            else -> null
        }

    val isDebrid: Boolean
        get() = listOf(
            "debrid", "real-debrid", "real debrid", "alldebrid", "all-debrid",
            "premiumize", "torbox", "debrid-link", "stremthru", "rd+"
        ).any { searchableText.contains(it) }

    val cacheSignalText: String
        get() = listOfNotNull(
            addonName,
            stream.name,
            stream.title,
            stream.behaviorHints?.filename,
            stream.url,
            stream.externalUrl
        ).joinToString(" ").lowercase()

    val isKnownUncached: Boolean
        get() {
            val text = cacheSignalText
            val negativeMarkers = listOf(
                "media_not_cached_yet",
                "media not cached yet",
                "not cached",
                "not_cached",
                "not-cached",
                "uncached",
                "cache miss",
                "cache-miss",
                "not ready",
                "not-ready",
                "needs caching",
                "needs download",
                "download to debrid",
                "queued for download"
            )
            return negativeMarkers.any(text::contains) ||
                (isDebrid && stream.behaviorHints?.notWebReady == true)
        }

    val isExplicitlyCached: Boolean
        get() {
            if (isKnownUncached) return false
            val text = cacheSignalText
            return listOf(
                "cached",
                "cache hit",
                "instant",
                "rd+",
                "real-debrid+",
                "⚡"
            ).any(text::contains)
        }

    val isRealDebrid: Boolean
        get() {
            val text = cacheSignalText
            return text.contains("real-debrid") ||
                text.contains("real debrid") ||
                text.contains("realdebrid") ||
                Regex("""(^|[^a-z0-9])rd\+?([^a-z0-9]|$)""").containsMatchIn(text)
        }

    val isP2p: Boolean
        get() = !stream.infoHash.isNullOrBlank() && playableUrl == null

    // Some add-ons return a donation/subscribe screen instead of a stream.
    // Never place these advertisements in the playable source selector.
    val isPromotional: Boolean
        get() {
            val title = listOfNotNull(stream.name, stream.title).joinToString(" ").lowercase()
            val external = stream.externalUrl.orEmpty().lowercase()
            val markers = listOf(
                "support the project", "donate", "donation",
                "buy me a coffee", "buymeacoffee", "ko-fi.com",
                "patreon.com", "upgrade account", "click to configure",
                "subscribe to unlock"
            )
            return markers.any { title.contains(it) || external.contains(it) }
        }

    fun displayTitle(): String = stream.title
        ?: stream.name
        ?: stream.behaviorHints?.filename
        ?: "Stream"

    // An opaque, stable identifier for distinguishing individual links even
    // when the provider gives several streams the same display name.
    // Never put signed URLs, API keys or debrid tokens into on-screen labels.
    fun safeLinkId(): String {
        val identity = playableUrl
            ?: stream.externalUrl
            ?: stream.infoHash
            ?: youtubeUrl
            ?: (addonName + "|" + displayTitle())
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .take(5)
            .joinToString("") { "%02X".format(it.toInt() and 0xff) }
    }

    fun switchIdentityLabel(): String {
        val safeTitle = displayTitle()
            .replace(Regex("""https?://\S+""", RegexOption.IGNORE_CASE), "[URL]")
            .replace(Regex("""\s+"""), " ")
            .take(48)
        return "$addonName · $safeTitle · ${qualityLabel()} · #${safeLinkId()}"
    }

    fun qualityLabel(): String = detectedQuality?.let { "${it}p" } ?: "Quality unknown"

    fun transportLabel(): String = when {
        playableUrl != null && isDebrid -> "Debrid / HTTP"
        playableUrl != null -> "HTTP"
        youtubeUrl != null -> "YouTube"
        !stream.externalUrl.isNullOrBlank() -> "External"
        isP2p -> "P2P"
        else -> "Unavailable"
    }

    fun preferenceScore(
        preferredQuality: Int = 720,
        preferHttpDebrid: Boolean = true,
        preferredAudioLanguage: String = "en"
    ): Int {
        val transport = when {
            playableUrl != null && isDebrid -> 0
            playableUrl != null -> 1
            youtubeUrl != null -> 2
            !stream.externalUrl.isNullOrBlank() -> 3
            isP2p -> 8
            else -> 9
        }

        val qualityOrder = listOf(
            preferredQuality,
            720,
            1080,
            576,
            480,
            1440,
            2160,
            360
        ).distinct()
        val quality = detectedQuality?.let { q ->
            qualityOrder.indexOf(q).takeIf { it >= 0 } ?: qualityOrder.size
        } ?: qualityOrder.size + 1

        val p2pPenalty = when {
            !isP2p -> 0
            preferHttpDebrid -> 1000
            else -> 100
        }

        val lang = preferredAudioLanguage.lowercase()
        val preferredTokens = when {
            lang.startsWith("en") -> listOf(" english ", " eng ", "[eng]", ".eng.", " en ")
            lang.startsWith("de") -> listOf(" german ", " ger ", " deutsch ", "[ger]")
            lang.startsWith("fr") -> listOf(" french ", " fre ", " fra ", "[fre]")
            lang.startsWith("es") -> listOf(" spanish ", " spa ", " esp ", "[spa]")
            else -> listOf(" $lang ", "[$lang]")
        }
        val foreignTokens = when {
            lang.startsWith("en") -> listOf(
                " russian ", " rus ", "[rus]", " hindi ", " hin ", "[hin]",
                " spanish ", " spa ", "[spa]", " italian ", " ita ", "[ita]",
                " german ", " ger ", "[ger]", " french ", " fre ", "[fre]",
                " polish ", " pol ", "[pol]"
            )
            else -> emptyList()
        }
        val padded = " $searchableText "
        val languagePenalty = when {
            preferredTokens.any { padded.contains(it) } -> 0
            foreignTokens.any { padded.contains(it) } -> 120
            else -> 20
        }

        return p2pPenalty + languagePenalty + quality * 10 + transport
    }

    fun statusText(): String = when {
        playableUrl != null -> "${transportLabel()} · ${qualityLabel()}"
        youtubeUrl != null -> "YouTube · 720p preferred"
        !stream.externalUrl.isNullOrBlank() -> "External · ${qualityLabel()}"
        !stream.infoHash.isNullOrBlank() -> "P2P source · deprioritized"
        else -> "No directly playable URL returned"
    }
}

data class SubtitleOption(
    val addonName: String,
    val subtitle: AddonSubtitle
)

data class LastPlaybackSession(
    val media: AppMedia,
    val videoId: String,
    val title: String,
    val source: StreamOption,
    val subtitles: List<SubtitleOption> = emptyList(),
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long
) {
    val percent: Int
        get() = if (durationMs <= 0L) 0
        else ((positionMs * 100L) / durationMs).toInt().coerceIn(0, 100)
}
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
