package za.co.nm.streamtv

import android.content.Context
import android.util.Xml
import com.google.gson.Gson
import com.google.gson.JsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

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

        private val RUGBY_TERMS = listOf(
            "rugby", "springbok", "springboks", "urc", "currie", "six nations",
            "super rugby", "rugby championship", "premiership rugby", "top 14"
        )
        private val MOTORSPORT_TERMS = listOf(
            "formula 1", "formula one", "f1", "motorsport", "motor sport", "motogp",
            "moto gp", "racing", "nascar", "indycar", "formula e", "wec", "le mans", "wrc"
        )
        private val SOCCER_TERMS = listOf(
            "soccer", "football", "premier league", "champions league", "uefa", "fifa",
            "la liga", "serie a", "bundesliga", "ligue 1"
        )
        private val CRICKET_TERMS = listOf(
            "cricket", "ipl", "proteas", "t20", "test cricket", "odi", "ashes"
        )
        private val GENERAL_SPORT_TERMS = listOf(
            "sport", "supersport", "sky sports", "espn", "premier sports",
            "tennis", "golf", "boxing", "ufc", "mma", "nba", "nfl", "nhl", "baseball", "cycling"
        )

        private val SPORTS_TERMS =
            RUGBY_TERMS + MOTORSPORT_TERMS + SOCCER_TERMS + CRICKET_TERMS + GENERAL_SPORT_TERMS
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

    fun replaceConfig(remote: IptvConfig) {
        val m3u = remote.m3uUrl.trim().takeIf { it.isNotBlank() }?.let {
            validateRemoteUrl(it, "M3U playlist URL")
        }.orEmpty()
        val epg = remote.epgUrl.trim().takeIf { it.isNotBlank() }?.let {
            validateRemoteUrl(it, "EPG URL")
        }.orEmpty()
        val server = remote.xtreamServer.trim().takeIf { it.isNotBlank() }?.let {
            validateRemoteUrl(it, "Xtream server URL").trimEnd('/')
        }.orEmpty()

        val clean = IptvConfig(
            m3uUrl = m3u,
            epgUrl = epg,
            xtreamServer = server,
            xtreamUsername = remote.xtreamUsername.trim(),
            xtreamPassword = remote.xtreamPassword
        )

        if (!clean.configured) {
            clear()
        } else {
            store.put(CONFIG_KEY, gson.toJson(clean))
        }
    }

    fun clear() = store.remove(CONFIG_KEY)

    fun configured(): Boolean = config().configured

    fun status(): String {
        val cfg = config()
        val base = when {
            cfg.hasM3u && cfg.hasXtream -> "M3U + Xtream configured"
            cfg.hasM3u -> "M3U configured"
            cfg.hasXtream -> "Xtream configured"
            else -> "Not configured"
        }
        if (!cfg.configured) return base
        return if (cfg.epgUrl.isNotBlank() || cfg.hasXtream) "$base · EPG available" else base
    }

    suspend fun loadChannels(limit: Int = 1200): List<AppMedia> {
        val cfg = config()
        val combined = mutableListOf<AppMedia>()
        if (cfg.hasM3u) combined += runCatching { loadM3u(cfg.m3uUrl) }.getOrDefault(emptyList())
        if (cfg.hasXtream) combined += runCatching { loadXtream(cfg) }.getOrDefault(emptyList())
        return combined.distinctBy { it.directUrl }.take(limit)
    }

    fun sportsOnly(items: List<AppMedia>): List<AppMedia> = items.filter { matchesAny(it, SPORTS_TERMS) }

    fun categoryRows(items: List<AppMedia>): List<LiveTvCategory> {
        val rows = mutableListOf<LiveTvCategory>()

        fun special(name: String, terms: List<String>) {
            val matches = items.filter { matchesAny(it, terms) }
            if (matches.isNotEmpty()) rows += LiveTvCategory(name, matches)
        }

        special("Rugby", RUGBY_TERMS)
        special("F1 & Motorsport", MOTORSPORT_TERMS)
        special("Soccer & Football", SOCCER_TERMS)
        special("Cricket", CRICKET_TERMS)

        val featuredIds = rows.flatMap { it.channels }.map { it.meta.id }.toSet()
        val otherSports = sportsOnly(items).filterNot { it.meta.id in featuredIds }
        if (otherSports.isNotEmpty()) rows += LiveTvCategory("Other Sports", otherSports)

        val providerGroups = items
            .groupBy { it.meta.genres.firstOrNull()?.trim().orEmpty().ifBlank { "Other Channels" } }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<AppMedia>>> { entry ->
                    entry.value.count { item -> item.meta.id in featuredIds }
                }.thenBy { it.key.lowercase() }
            )

        providerGroups.forEach { (name, channels) ->
            if (rows.none { it.name.equals(name, true) }) {
                rows += LiveTvCategory(name, channels)
            }
        }
        return rows.take(30)
    }

    suspend fun loadGuide(
        channels: List<AppMedia>,
        hoursForward: Int = 12,
        programmesPerChannel: Int = 4
    ): Map<String, List<EpgProgramme>> {
        if (channels.isEmpty()) return emptyMap()
        val cfg = config()
        val url = when {
            cfg.epgUrl.isNotBlank() -> cfg.epgUrl
            cfg.hasXtream -> {
                val user = SimpleHttp.encode(cfg.xtreamUsername)
                val pass = SimpleHttp.encode(cfg.xtreamPassword)
                "${cfg.xtreamServer}/xmltv.php?username=$user&password=$pass"
            }
            else -> return emptyMap()
        }

        val parsed = runCatching { parseXmlTv(url) }.getOrDefault(ParsedGuide())
        if (parsed.programmes.isEmpty()) return emptyMap()

        val now = System.currentTimeMillis()
        val horizon = now + hoursForward.coerceIn(2, 48) * 60L * 60L * 1000L
        val displayNameLookup = parsed.channelNames.entries.associate { normalizeName(it.value) to it.key }

        return channels.mapNotNull { channel ->
            val exactId = channel.epgId?.takeIf { it.isNotBlank() }
            val fallbackId = displayNameLookup[normalizeName(channel.meta.name)]
            val ids = listOfNotNull(exactId, fallbackId).distinct()
            val programmes = ids
                .flatMap { parsed.programmes[it].orEmpty() }
                .filter { it.stopMs > now && it.startMs < horizon }
                .distinctBy { "${it.channelId}|${it.startMs}|${it.title}" }
                .sortedBy { it.startMs }
                .take(programmesPerChannel.coerceIn(1, 12))

            if (programmes.isEmpty()) null else channel.meta.id to programmes
        }.toMap()
    }

    private fun matchesAny(item: AppMedia, terms: List<String>): Boolean {
        val haystack = buildString {
            append(item.meta.name)
            append(' ')
            append(item.meta.description.orEmpty())
            append(' ')
            append(item.meta.genres.joinToString(" "))
        }.lowercase()
        return terms.any { haystack.contains(it) }
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
                        directUrl = line,
                        epgId = tvgId.takeIf { it.isNotBlank() }
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
        val categoriesUrl = "${cfg.xtreamServer}/player_api.php?username=$user&password=$pass&action=get_live_categories"
        val categories = runCatching {
            val body = SimpleHttp.requireSuccess(SimpleHttp.get(categoriesUrl), "Loading Xtream categories")
            gson.fromJson(body, JsonArray::class.java).mapNotNull { element ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val id = obj.get("category_id")?.asString ?: return@mapNotNull null
                id to (obj.get("category_name")?.asString ?: "Live TV")
            }.toMap()
        }.getOrDefault(emptyMap())

        val api = "${cfg.xtreamServer}/player_api.php?username=$user&password=$pass&action=get_live_streams"
        val body = SimpleHttp.requireSuccess(SimpleHttp.get(api), "Loading Xtream live channels")
        val array = gson.fromJson(body, JsonArray::class.java)
        return array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val streamId = obj.get("stream_id")?.asInt ?: return@mapNotNull null
            val name = obj.get("name")?.asString?.takeIf { it.isNotBlank() } ?: "Live channel"
            val logo = obj.get("stream_icon")?.takeUnless { it.isJsonNull }?.asString
            val categoryId = obj.get("category_id")?.takeUnless { it.isJsonNull }?.asString
            val category = categoryId?.let { categories[it] } ?: "Live TV"
            val epgId = obj.get("epg_channel_id")?.takeUnless { it.isJsonNull }?.asString
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
                directUrl = stream,
                epgId = epgId?.takeIf { it.isNotBlank() }
            )
        }
    }

    private data class ParsedGuide(
        val channelNames: MutableMap<String, String> = mutableMapOf(),
        val programmes: MutableMap<String, MutableList<EpgProgramme>> = mutableMapOf()
    )

    private suspend fun parseXmlTv(url: String): ParsedGuide = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 35_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NMStreamTV/0.5")
            setRequestProperty("Accept", "application/xml, text/xml, application/gzip, */*")
        }

        val raw = BufferedInputStream(connection.inputStream)
        val input: InputStream = if (
            url.endsWith(".gz", true) ||
            connection.contentEncoding.equals("gzip", true) ||
            looksGzip(raw)
        ) {
            GZIPInputStream(raw)
        } else raw

        input.use { stream ->
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(stream, null)
            }

            val guide = ParsedGuide()
            var channelId: String? = null
            var channelDisplayName: String? = null

            var programmeChannel: String? = null
            var programmeStart = 0L
            var programmeStop = 0L
            var programmeTitle: String? = null
            var programmeDescription: String? = null

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "channel" -> {
                            channelId = parser.getAttributeValue(null, "id")
                            channelDisplayName = null
                        }
                        "display-name" -> if (channelId != null && channelDisplayName == null) {
                            channelDisplayName = parser.nextText().trim()
                        }
                        "programme" -> {
                            programmeChannel = parser.getAttributeValue(null, "channel")
                            programmeStart = parseXmlTvTime(parser.getAttributeValue(null, "start"))
                            programmeStop = parseXmlTvTime(parser.getAttributeValue(null, "stop"))
                            programmeTitle = null
                            programmeDescription = null
                        }
                        "title" -> if (programmeChannel != null) {
                            programmeTitle = parser.nextText().trim()
                        }
                        "desc" -> if (programmeChannel != null) {
                            programmeDescription = parser.nextText().trim().takeIf { it.isNotBlank() }
                        }
                    }

                    XmlPullParser.END_TAG -> when (parser.name) {
                        "channel" -> {
                            val id = channelId
                            if (!id.isNullOrBlank() && !channelDisplayName.isNullOrBlank()) {
                                guide.channelNames[id] = channelDisplayName.orEmpty()
                            }
                            channelId = null
                            channelDisplayName = null
                        }
                        "programme" -> {
                            val id = programmeChannel
                            val title = programmeTitle
                            if (!id.isNullOrBlank() && !title.isNullOrBlank() && programmeStart > 0L) {
                                val stop = if (programmeStop > programmeStart) programmeStop else programmeStart + 2 * 60 * 60 * 1000L
                                guide.programmes.getOrPut(id) { mutableListOf() }.add(
                                    EpgProgramme(
                                        channelId = id,
                                        title = title,
                                        description = programmeDescription,
                                        startMs = programmeStart,
                                        stopMs = stop
                                    )
                                )
                            }
                            programmeChannel = null
                            programmeTitle = null
                            programmeDescription = null
                            programmeStart = 0L
                            programmeStop = 0L
                        }
                    }
                }
                event = parser.next()
            }

            guide.programmes.values.forEach { it.sortBy(EpgProgramme::startMs) }
            guide
        }.also { connection.disconnect() }
    }

    private fun looksGzip(stream: BufferedInputStream): Boolean {
        stream.mark(2)
        val first = stream.read()
        val second = stream.read()
        stream.reset()
        return first == 0x1f && second == 0x8b
    }

    private fun parseXmlTvTime(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val cleaned = value.trim().replace(Regex("\\s+"), " ")
        val patterns = listOf(
            "yyyyMMddHHmmss Z",
            "yyyyMMddHHmm Z",
            "yyyyMMddHHmmss",
            "yyyyMMddHHmm"
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = true
                    if (!pattern.contains("Z")) timeZone = TimeZone.getDefault()
                }.parse(cleaned)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return 0L
    }

    private fun normalizeName(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

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
