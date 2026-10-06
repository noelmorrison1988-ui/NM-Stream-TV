package za.co.nm.streamtv

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque

data class KodiCrewConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    val username: String = "",
    val password: String = ""
)

class KodiCrewRepository(context: Context) {
    companion object {
        private const val CONFIG_KEY = "kodi_crew_config_v1"
        private const val CREW_ADDON_ID = "plugin.video.thecrew"
        private const val ROOT = "plugin://plugin.video.thecrew/"
        private val SPORTS_HINTS = listOf(
            "sport", "sports", "live sport", "replay", "replays", "rugby", "football", "soccer",
            "cricket", "formula 1", "f1", "motorsport", "ufc", "mma", "boxing", "wwe", "nfl",
            "nba", "nhl", "tennis", "golf"
        )
        private val REPLAY_HINTS = listOf("replay", "replays", "catch up", "catch-up", "highlights", "full match")
    }

    private val store = SecretStore(context)
    private val gson = Gson()

    fun config(): KodiCrewConfig {
        val raw = store.get(CONFIG_KEY) ?: return KodiCrewConfig()
        return runCatching { gson.fromJson(raw, KodiCrewConfig::class.java) }.getOrDefault(KodiCrewConfig())
    }

    fun saveConfig(host: String, port: Int, username: String, password: String) {
        require(port in 1..65535) { "Kodi port must be between 1 and 65535" }
        val cleanHost = host.trim()
            .ifBlank { "127.0.0.1" }
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore(':')
        require(cleanHost.isNotBlank()) { "Enter the Kodi host or IP address" }
        store.put(
            CONFIG_KEY,
            gson.toJson(
                KodiCrewConfig(
                    host = cleanHost,
                    port = port,
                    username = username.trim(),
                    password = password
                )
            )
        )
    }

    fun statusLabel(): String {
        val cfg = config()
        return "Kodi · " + cfg.host + ":" + cfg.port + " · The Crew"
    }

    suspend fun testConnection(): Boolean {
        val ping = rpc("JSONRPC.Ping", JsonObject())
        if (ping.has("error")) return false
        val addon = rpc(
            "Addons.GetAddonDetails",
            JsonObject().apply {
                addProperty("addonid", CREW_ADDON_ID)
                add("properties", JsonArray().apply {
                    add("name")
                    add("enabled")
                })
            }
        )
        val details = addon.getAsJsonObject("result")?.getAsJsonObject("addon")
            ?: error("Kodi connected, but The Crew is not installed")
        if (details.get("enabled")?.asBoolean == false) error("Kodi connected, but The Crew is disabled")
        return true
    }

    suspend fun loadSports(): List<AppMedia> = withTimeoutOrNull(4_500) {
        val addon = rpc(
            "Addons.GetAddonDetails",
            JsonObject().apply {
                addProperty("addonid", CREW_ADDON_ID)
                add("properties", JsonArray().apply {
                    add("name")
                    add("enabled")
                })
            }
        )
        val details = addon.getAsJsonObject("result")?.getAsJsonObject("addon")
            ?: error("The Crew is not available through Kodi")
        if (details.get("enabled")?.asBoolean == false) error("The Crew is disabled in Kodi")

        val discoveredRoots = findSportsRoots()
        val queue = ArrayDeque<Pair<String, Int>>()
        discoveredRoots.forEach { queue.add(it to 0) }

        val seen = mutableSetOf<String>()
        val output = mutableListOf<AppMedia>()

        while (queue.isNotEmpty() && seen.size < 180 && output.size < 220) {
            val (directory, depth) = queue.removeFirst()
            if (!seen.add(directory) || depth > 4) continue

            directoryEntries(directory).forEach { entry ->
                val path = entry.path ?: return@forEach
                val label = entry.label.ifBlank { "Sports" }
                val sportsText = (label + " " + entry.plot).lowercase()
                val replay = REPLAY_HINTS.any { sportsText.contains(it) } ||
                    REPLAY_HINTS.any { directory.lowercase().contains(it) }

                if (entry.isDirectory) {
                    if (depth < 4) queue.add(path to (depth + 1))
                } else {
                    output += AppMedia(
                        meta = MetaItem(
                            id = "kodi:crew:" + path.hashCode(),
                            type = "kodi",
                            name = label,
                            poster = entry.thumbnail,
                            background = entry.fanart,
                            description = buildString {
                                append("The Crew · ")
                                append(if (replay) "Sports Replay" else "Live Sports")
                                if (entry.plot.isNotBlank()) append(" · ").append(entry.plot)
                            },
                            genres = classifySports(label + " " + entry.plot, replay)
                        ),
                        originAddonName = "Kodi · The Crew",
                        directUrl = path
                    )
                }
            }
        }

        output
            .distinctBy { normalizeEventName(it.meta.name) + "|" + it.meta.genres.joinToString() }
            .take(180)
    } ?: emptyList()

    suspend fun play(pluginPath: String): Boolean = withTimeoutOrNull(4_000) {
        require(pluginPath.startsWith("plugin://" + CREW_ADDON_ID)) {
            "Only The Crew paths can be opened by this bridge"
        }
        val result = rpc(
            "Player.Open",
            JsonObject().apply {
                add("item", JsonObject().apply { addProperty("file", pluginPath) })
            }
        )
        !result.has("error")
    } ?: false

    private suspend fun findSportsRoots(): List<String> {
        val rootEntries = directoryEntries(ROOT)
        val direct = rootEntries
            .filter { it.isDirectory && isSportsLabel(it.label) }
            .mapNotNull { it.path }

        if (direct.isNotEmpty()) return direct.distinct().take(12)

        val secondLevel = mutableListOf<String>()
        rootEntries.filter { it.isDirectory }.take(28).forEach { parent ->
            val path = parent.path ?: return@forEach
            runCatching { directoryEntries(path) }.getOrDefault(emptyList()).forEach { child ->
                if (child.isDirectory && isSportsLabel(child.label)) child.path?.let(secondLevel::add)
            }
        }
        return secondLevel.distinct().take(12)
    }

    private fun isSportsLabel(value: String): Boolean {
        val text = value.lowercase()
        return SPORTS_HINTS.any { text.contains(it) }
    }

    private data class KodiEntry(
        val label: String,
        val path: String?,
        val isDirectory: Boolean,
        val plot: String,
        val thumbnail: String?,
        val fanart: String?
    )

    private suspend fun directoryEntries(directory: String): List<KodiEntry> {
        val response = rpc(
            "Files.GetDirectory",
            JsonObject().apply {
                addProperty("directory", directory)
                addProperty("media", "files")
                add("properties", JsonArray().apply {
                    add("title")
                    add("plot")
                    add("thumbnail")
                    add("art")
                })
            }
        )

        return response.getAsJsonObject("result")
            ?.getAsJsonArray("files")
            ?.mapNotNull { element ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val path = obj.string("file")
                val label = obj.string("label") ?: obj.string("title") ?: path ?: return@mapNotNull null
                val art = obj.getAsJsonObject("art")
                KodiEntry(
                    label = label,
                    path = path,
                    isDirectory = obj.string("filetype").equals("directory", true),
                    plot = obj.string("plot").orEmpty(),
                    thumbnail = obj.string("thumbnail") ?: art?.string("thumb") ?: art?.string("poster"),
                    fanart = art?.string("fanart") ?: art?.string("landscape")
                )
            }
            .orEmpty()
    }

    private suspend fun rpc(method: String, params: JsonObject): JsonObject {
        val cfg = config()
        val body = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", 1)
            addProperty("method", method)
            add("params", params)
        }.toString()

        val headers = mutableMapOf<String, String>()
        if (cfg.username.isNotBlank() || cfg.password.isNotBlank()) {
            val token = Base64.encodeToString(
                (cfg.username + ":" + cfg.password).toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            headers["Authorization"] = "Basic " + token
        }

        val result = SimpleHttp.postJson(
            "http://" + cfg.host + ":" + cfg.port + "/jsonrpc",
            body,
            headers
        )
        val response = JsonParser.parseString(
            SimpleHttp.requireSuccess(result, "Connecting to Kodi")
        ).asJsonObject
        response.getAsJsonObject("error")?.let { rpcError ->
            val message = rpcError.get("message")?.asString ?: "Kodi JSON-RPC error"
            error(message)
        }
        return response
    }

    private fun classifySports(text: String, replay: Boolean): List<String> {
        val value = text.lowercase()
        val category = when {
            listOf("rugby", "urc", "springbok", "six nations").any(value::contains) -> "Rugby"
            listOf("formula 1", "f1", "motogp", "nascar", "motorsport").any(value::contains) -> "Motorsport"
            listOf("soccer", "football", "premier league", "uefa", "champions league").any(value::contains) -> "Football"
            listOf("cricket", "ipl", "t20", "test match").any(value::contains) -> "Cricket"
            listOf("ufc", "mma", "boxing", "wwe", "wrestling").any(value::contains) -> "Combat Sports"
            listOf("tennis", "atp", "wta").any(value::contains) -> "Tennis"
            listOf("nba", "basketball").any(value::contains) -> "Basketball"
            listOf("nfl", "american football").any(value::contains) -> "NFL"
            else -> "Other Sports"
        }
        return listOf(if (replay) "Replay" else "Live", category)
    }

    private fun normalizeEventName(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
}
