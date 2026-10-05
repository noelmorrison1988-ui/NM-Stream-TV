package za.co.nm.streamtv

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import java.net.URI

data class IptvConfig(
    val m3uUrl: String = "",
    val epgUrl: String = "",
    val xtreamServer: String = "",
    val xtreamUsername: String = "",
    val xtreamPassword: String = ""
) {
    val hasM3u: Boolean get() = m3uUrl.isNotBlank()
    val hasXtream: Boolean get() = xtreamServer.isNotBlank() && xtreamUsername.isNotBlank() && xtreamPassword.isNotBlank()
    val configured: Boolean get() = hasM3u || hasXtream
}

class IptvRepository(context: Context) {
    companion object {
        private const val CONFIG_KEY = "iptv_config_v1"
        private val SPORTS_TERMS = listOf(
            "rugby", "springbok", "urc", "currie", "six nations", "super rugby",
            "formula 1", "formula one", "f1", "motorsport", "motogp", "racing",
            "soccer", "football", "premier league", "champions league", "uefa", "fifa",
            "cricket", "ipl", "proteas", "t20", "test cricket",
            "sport", "supersport", "sky sports", "espn", "premier sports",
            "tennis", "golf", "boxing", "ufc", "mma", "nba", "nfl", "nhl", "baseball", "cycling"
        )
    }

    private val store = SecretStore(context)
    private val gson = Gson()

    fun config(): IptvConfig {
        val raw = store.get(CONFIG_KEY) ?: return IptvConfig()
        return runCatching { gson.fromJson(raw, IptvConfig::class.java) }.getOrDefault(IptvConfig())
    }

    fun saveM3u(m3uUrl: String, epgUrl: String) {
        val cleanM3u = validateRemoteUrl(m3uUrl, "M3U playlist URL")
        val cleanEpg = epgUrl.trim().takeIf { it.isNotBlank() }?.let { validateRemoteUrl(it, "EPG URL") }.orEmpty()
        val previous = config()
        store.put(CONFIG_KEY, gson.toJson(previous.copy(m3uUrl = cleanM3u, epgUrl = cleanEpg)))
    }

    fun saveXtream(server: String, username: String, password: String) {
        val cleanServer = validateRemoteUrl(server, "Xtream server URL").trimEnd('/')
        require(username.isNotBlank()) { "Enter the Xtream username" }
        require(password.isNotBlank()) { "Enter the Xtream password" }
        val previous = config()
        store.put(
            CONFIG_KEY,
            gson.toJson(
                previous.copy(
                    xtreamServer = cleanServer,
                    xtreamUsername = username.trim(),
                    xtreamPassword = password
                )
            )
        )
    }

    fun clear() = store.remove(CONFIG_KEY)

    fun configured(): Boolean = config().configured

    fun status(): String {
        val cfg = config()
        return when {
            cfg.hasM3u && cfg.hasXtream -> "M3U + Xtream configured"
            cfg.hasM3u -> "M3U configured"
            cfg.hasXtream -> "Xtream configured"
            else -> "Not configured"
        }
    }

    suspend fun loadChannels(limit: Int = 1200): List<AppMedia> {
        val cfg = config()
        val combined = mutableListOf<AppMedia>()
        if (cfg.hasM3u) combined += runCatching { loadM3u(cfg.m3uUrl) }.getOrDefault(emptyList())
        if (cfg.hasXtream) combined += runCatching { loadXtream(cfg) }.getOrDefault(emptyList())
        return combined.distinctBy { it.directUrl }.take(limit)
    }

    fun sportsOnly(items: List<AppMedia>): List<AppMedia> =
        items.filter { item ->
            val haystack = buildString {
                append(item.meta.name)
                append(' ')
                append(item.meta.description.orEmpty())
                append(' ')
                append(item.meta.genres.joinToString(" "))
            }.lowercase()
            SPORTS_TERMS.any { haystack.contains(it) }
        }

    private suspend fun loadM3u(url: String): List<AppMedia> {
        val body = SimpleHttp.requireSuccess(SimpleHttp.get(url), "Loading IPTV playlist")
        val lines = body.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val out = mutableListOf<AppMedia>()
        var info: String? = null

        for (line in lines) {
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> info = line
                line.startsWith("#") -> Unit
                line.startsWith("http://", true) || line.startsWith("https://", true) -> {
                    val extinf = info.orEmpty()
                    val name = extinf.substringAfterLast(',', "").trim().ifBlank { "Live channel" }
                    val logo = attribute(extinf, "tvg-logo")
                    val group = attribute(extinf, "group-title").ifBlank { "Live TV" }
                    val tvgId = attribute(extinf, "tvg-id")
                    val idSeed = tvgId.ifBlank { "$name|$line" }
                    out += AppMedia(
                        meta = MetaItem(
                            id = "iptv:${idSeed.hashCode()}",
                            type = "tv",
                            name = name,
                            poster = logo.takeIf { it.isNotBlank() },
                            background = logo.takeIf { it.isNotBlank() },
                            description = "Live IPTV · $group",
                            releaseInfo = "LIVE",
                            genres = listOf(group)
                        ),
                        originAddonName = "NM IPTV · M3U",
                        directUrl = line
                    )
                    info = null
                }
            }
        }
        return out
    }

    private suspend fun loadXtream(cfg: IptvConfig): List<AppMedia> {
        val user = SimpleHttp.encode(cfg.xtreamUsername)
        val pass = SimpleHttp.encode(cfg.xtreamPassword)
        val api = "${cfg.xtreamServer}/player_api.php?username=$user&password=$pass&action=get_live_streams"
        val body = SimpleHttp.requireSuccess(SimpleHttp.get(api), "Loading Xtream live channels")
        val array = gson.fromJson(body, JsonArray::class.java)
        return array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val streamId = obj.get("stream_id")?.asInt ?: return@mapNotNull null
            val name = obj.get("name")?.asString?.takeIf { it.isNotBlank() } ?: "Live channel"
            val logo = obj.get("stream_icon")?.takeUnless { it.isJsonNull }?.asString
            val category = obj.get("category_name")?.takeUnless { it.isJsonNull }?.asString
                ?: obj.get("category_id")?.takeUnless { it.isJsonNull }?.asString
                ?: "Live TV"
            val stream = "${cfg.xtreamServer}/live/$user/$pass/$streamId.ts"
            AppMedia(
                meta = MetaItem(
                    id = "xtream:$streamId",
                    type = "tv",
                    name = name,
                    poster = logo,
                    background = logo,
                    description = "Live Xtream IPTV · $category",
                    releaseInfo = "LIVE",
                    genres = listOf(category)
                ),
                originAddonName = "NM IPTV · Xtream",
                directUrl = stream
            )
        }
    }

    private fun attribute(line: String, name: String): String {
        val pattern = Regex("""$name\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
        return pattern.find(line)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun validateRemoteUrl(raw: String, label: String): String {
        val value = raw.trim()
        require(value.startsWith("https://", true) || value.startsWith("http://", true)) {
            "$label must start with http:// or https://"
        }
        val uri = URI(value)
        require(!uri.host.isNullOrBlank()) { "$label is invalid" }
        require(uri.userInfo == null) { "$label cannot contain username/password in the URL authority" }
        return value
    }
}
