package za.co.nm.streamtv

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

class AddonRepository(context: Context) {
    private val gson = Gson()
    private val secureStore = SecretStore(context)
    private val storedUrlsKey = "addon_manifest_urls"
    private val manifestCacheKey = "addon_manifest_cache_v1"

    fun storedManifestUrls(): List<String> = loadStoredUrls()

    suspend fun syncManifestUrls(urls: List<String>): List<InstalledAddon> {
        val normalized = urls.mapNotNull { raw ->
            runCatching { SimpleHttp.normalizeManifestUrl(raw) }.getOrNull()
        }.distinct()
        saveStoredUrls(normalized)
        val cache = loadManifestCache().filterKeys { it in normalized }
        saveManifestCache(cache)
        return loadInstalled()
    }

    suspend fun loadInstalled(): List<InstalledAddon> {
        val urls = loadStoredUrls()
        val cached = loadManifestCache().toMutableMap()

        val resolved = supervisorScope {
            urls.map { url ->
                async {
                    val fresh = runCatching { fetchManifest(url) }.getOrNull()
                    if (fresh != null) url to fresh else url to cached[url]
                }
            }.awaitAll()
        }

        resolved.forEach { (url, addon) ->
            if (addon != null) cached[url] = addon
        }
        saveManifestCache(cached.filterKeys { it in urls })

        // A temporary provider outage must not erase saved add-ons.
        // Unavailable manifests remain configured and are retried on refresh.
        return resolved.mapNotNull { it.second }
            .filter(MediaPolicy::allowsAddon)
    }

    suspend fun install(inputUrl: String): InstalledAddon {
        val manifestUrl = SimpleHttp.normalizeManifestUrl(inputUrl)
        val addon = fetchManifest(manifestUrl)
        require(MediaPolicy.allowsAddon(addon)) { "Anime add-ons are blocked by NM Stream TV" }
        require(addon.manifest.id.isNotBlank()) { "Manifest is missing an id" }
        require(addon.manifest.resources.isNotEmpty()) { "Manifest exposes no resources" }
        val urls = loadStoredUrls().toMutableList()
        if (manifestUrl !in urls) {
            urls += manifestUrl
            saveStoredUrls(urls)
        }
        val cache = loadManifestCache().toMutableMap()
        cache[manifestUrl] = addon
        saveManifestCache(cache)
        return addon
    }

    fun remove(manifestUrl: String) {
        val remaining = loadStoredUrls().filterNot { it == manifestUrl }
        saveStoredUrls(remaining)
        val cache = loadManifestCache().toMutableMap()
        cache.remove(manifestUrl)
        saveManifestCache(cache.filterKeys { it in remaining })
    }

    suspend fun loadHome(addons: List<InstalledAddon>): Pair<List<AppMedia>, List<AppMedia>> = supervisorScope {
        suspend fun loadType(type: String): List<AppMedia> {
            val jobs = addons.flatMap { addon ->
                addon.manifest.catalogs
                    .filter { catalog -> catalog.type == type && catalog.extra.none { it.isRequired == true } }
                    .take(3)
                    .map { catalog ->
                        async { runCatching { loadCatalog(addon, catalog) }.getOrDefault(emptyList()) }
                    }
            }
            return jobs.awaitAll().flatten()
                .filter(MediaPolicy::allows)
                .distinctBy { "${it.meta.type}:${it.meta.id}" }
                .take(48)
        }

        val movies = async { loadType("movie") }
        val series = async { loadType("series") }
        movies.await() to series.await()
    }

    suspend fun search(addons: List<InstalledAddon>, query: String): List<AppMedia> = supervisorScope {
        if (query.isBlank()) return@supervisorScope emptyList()
        val jobs = addons.flatMap { addon ->
            addon.manifest.catalogs
                .filter { catalog ->
                    catalog.extra.any { it.name == "search" } &&
                        catalog.extra.none { it.isRequired == true && it.name != "search" }
                }
                .take(8)
                .map { catalog ->
                    async { runCatching { loadCatalog(addon, catalog, query) }.getOrDefault(emptyList()) }
                }
        }
        jobs.awaitAll().flatten()
            .filter(MediaPolicy::allows)
            .distinctBy { "${it.meta.type}:${it.meta.id}" }
            .take(96)
    }

    suspend fun loadMeta(item: AppMedia, addons: List<InstalledAddon>): AppMedia {
        if (item.directUrl != null || item.meta.type == "rd") return item

        val preferred = addons.firstOrNull { it.manifestUrl == item.originManifestUrl }
        val ordered = listOfNotNull(preferred) + addons.filterNot { it === preferred }
        for (addon in ordered) {
            if (!supportsResource(addon.manifest, "meta", item.meta.type, item.meta.id)) continue
            val url = resourceUrl(addon, "meta/${SimpleHttp.encode(item.meta.type)}/${SimpleHttp.encode(item.meta.id)}.json")
            val result = runCatching {
                val body = SimpleHttp.requireSuccess(SimpleHttp.get(url), "Loading metadata")
                gson.fromJson(body, MetaResponse::class.java).meta
            }.getOrNull()
            if (result != null) {
                return item.copy(
                    meta = result,
                    originManifestUrl = addon.manifestUrl,
                    originAddonName = addon.manifest.name
                )
            }
        }
        return item
    }

    suspend fun loadStreams(addons: List<InstalledAddon>, type: String, videoId: String): List<StreamOption> =
        supervisorScope {
            addons
                .filter { supportsResource(it.manifest, "stream", type, videoId) }
                .map { addon ->
                    async {
                        runCatching {
                            val url = resourceUrl(addon, "stream/${SimpleHttp.encode(type)}/${SimpleHttp.encode(videoId)}.json")
                            val body = SimpleHttp.requireSuccess(SimpleHttp.get(url), "Loading streams")
                            gson.fromJson(body, StreamResponse::class.java).streams.map { stream ->
                                StreamOption(addon.manifest.name, stream)
                            }
                        }.getOrDefault(emptyList())
                    }
                }
                .awaitAll()
                .flatten()
                .distinctBy { option ->
                    option.playableUrl ?: option.stream.externalUrl ?: option.stream.infoHash ?: option.displayTitle()
                }
        }

    suspend fun loadSubtitles(addons: List<InstalledAddon>, type: String, videoId: String): List<SubtitleOption> =
        supervisorScope {
            addons
                .filter { supportsResource(it.manifest, "subtitles", type, videoId) }
                .map { addon ->
                    async {
                        runCatching {
                            val url = resourceUrl(addon, "subtitles/${SimpleHttp.encode(type)}/${SimpleHttp.encode(videoId)}.json")
                            val body = SimpleHttp.requireSuccess(SimpleHttp.get(url), "Loading subtitles")
                            gson.fromJson(body, SubtitleResponse::class.java).subtitles
                                .filter { it.url.startsWith("https://", true) || it.url.startsWith("http://", true) }
                                .map { SubtitleOption(addon.manifest.name, it) }
                        }.getOrDefault(emptyList())
                    }
                }
                .awaitAll()
                .flatten()
                .distinctBy { "${it.subtitle.lang}:${it.subtitle.url}" }
                .take(40)
        }

    private suspend fun loadCatalog(
        addon: InstalledAddon,
        catalog: CatalogSpec,
        search: String? = null
    ): List<AppMedia> {
        val extra = if (search.isNullOrBlank()) "" else "/search=${SimpleHttp.encode(search)}"
        val url = resourceUrl(
            addon,
            "catalog/${SimpleHttp.encode(catalog.type)}/${SimpleHttp.encode(catalog.id)}$extra.json"
        )
        val body = SimpleHttp.requireSuccess(SimpleHttp.get(url), "Loading ${catalog.name ?: catalog.id}")
        return gson.fromJson(body, CatalogResponse::class.java).metas.map { meta ->
            AppMedia(meta = meta, originManifestUrl = addon.manifestUrl, originAddonName = addon.manifest.name)
        }.filter(MediaPolicy::allows)
    }

    private suspend fun fetchManifest(manifestUrl: String): InstalledAddon {
        val body = SimpleHttp.requireSuccess(SimpleHttp.get(manifestUrl), "Loading add-on manifest")
        val manifest = gson.fromJson(body, AddonManifest::class.java)
        return InstalledAddon(
            manifestUrl = manifestUrl,
            baseUrl = SimpleHttp.baseUrlFromManifest(manifestUrl),
            manifest = manifest,
            resourceQuery = runCatching { java.net.URI(manifestUrl).rawQuery }.getOrNull()
        )
    }

    private fun resourceUrl(addon: InstalledAddon, path: String): String {
        val base = "${addon.baseUrl}/$path"
        return addon.resourceQuery?.takeIf { it.isNotBlank() }?.let { "$base?$it" } ?: base
    }

    private fun supportsResource(manifest: AddonManifest, resourceName: String, type: String, id: String): Boolean =
        manifest.resources.any { resourceMatches(it, resourceName, type, id) }

    private fun resourceMatches(element: JsonElement, resourceName: String, type: String, id: String): Boolean {
        if (element.isJsonPrimitive) return element.asString == resourceName
        if (!element.isJsonObject) return false
        val obj = element.asJsonObject
        if (obj.get("name")?.asString != resourceName) return false

        val types = obj.getAsJsonArray("types")?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()
        if (types.isNotEmpty() && type !in types) return false

        val prefixes = obj.getAsJsonArray("idPrefixes")?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()
        if (prefixes.isNotEmpty() && prefixes.none { id.startsWith(it) }) return false
        return true
    }

    private fun loadManifestCache(): Map<String, InstalledAddon> {
        val json = secureStore.get(manifestCacheKey) ?: return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, InstalledAddon>>() {}.type
            gson.fromJson<Map<String, InstalledAddon>>(json, type)
        }.getOrDefault(emptyMap())
    }

    private fun saveManifestCache(cache: Map<String, InstalledAddon>) {
        secureStore.put(manifestCacheKey, gson.toJson(cache))
    }

    private fun loadStoredUrls(): List<String> {
        val json = secureStore.get(storedUrlsKey) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(json, type)
        }.getOrDefault(emptyList())
    }

    private fun saveStoredUrls(urls: List<String>) {
        secureStore.put(storedUrlsKey, gson.toJson(urls.distinct()))
    }
}
