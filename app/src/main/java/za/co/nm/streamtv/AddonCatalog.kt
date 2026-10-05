package za.co.nm.streamtv

data class AddonPreset(
    val name: String,
    val category: String,
    val description: String,
    val manifestUrl: String? = null,
    val setupUrl: String? = null,
    val sports: Boolean = false,
    val note: String? = null
) {
    val oneTapInstall: Boolean get() = !manifestUrl.isNullOrBlank()
}

object AddonCatalog {
    val presets: List<AddonPreset> = listOf(
        AddonPreset("TMDB Addon", "Metadata", "Rich movie and series metadata, artwork and discovery catalogs.", setupUrl = "https://stremio-addons.net/addons/the-movie-database-addon"),
        AddonPreset("Cinemeta", "Metadata", "Official Stremio movie and series metadata/catalog provider.", manifestUrl = "https://v3-cinemeta.strem.io/manifest.json"),
        AddonPreset("Streaming Catalogs", "Discovery", "Platform-style catalogs for Netflix, Disney+, Prime Video and more.", setupUrl = "https://stremio-addons.net/addons/streaming-catalogs"),
        AddonPreset("Streaming Catalogs Plus", "Discovery", "Configurable platform, region, language and family-oriented discovery catalogs.", setupUrl = "https://stremio-addons.net/addons/streaming-catalogs-plus"),
        AddonPreset("TOP Streaming", "Discovery", "Trending/top catalogs across streaming platforms and countries.", setupUrl = "https://stremio-addons.net/addons/top-streaming"),
        AddonPreset("MyTrakt Sync", "Tracking", "Optional Stremio catalog integration for Trakt lists and calendars. NM Stream TV also has native Trakt.", setupUrl = "https://stremio-addons.net/addons/mytrakt-sync-pilot"),
        AddonPreset("AIOLists", "Lists", "Combines and organizes multiple compatible list/catalog sources.", setupUrl = "https://stremio-addons.net/addons/aiolists"),
        AddonPreset("Watchly", "Discovery", "Personalized movie and series recommendations.", setupUrl = "https://stremio-addons.net/addons/watchly"),
        AddonPreset("Ratings", "Ratings", "Configurable movie/show ratings and age information.", setupUrl = "https://stremio-addons.net/addons/ratings"),
        AddonPreset("OpenSubtitles v3", "Subtitles", "Official OpenSubtitles integration.", manifestUrl = "https://opensubtitles-v3.strem.io/manifest.json"),
        AddonPreset("SubSense", "Subtitles", "Multi-source subtitle aggregation and language filtering.", setupUrl = "https://stremio-addons.net/addons/subsense"),
        AddonPreset("Subtitle Sync", "Subtitles", "Subtitle timing and release matching.", setupUrl = "https://stremio-addons.net/addons/subtitle-sync"),
        AddonPreset("Anime Kitsu", "Anime", "Anime catalogs and metadata using Kitsu identifiers.", manifestUrl = "https://anime-kitsu.strem.fun/manifest.json"),
        AddonPreset("Kitsu Tracker", "Anime", "Optional Kitsu tracking and anime list integration.", setupUrl = "https://stremio-addons.net/addons/kitsutracker-v2"),
        AddonPreset("AIO Metadata", "Metadata", "Advanced metadata mapping across multiple movie, TV and anime databases.", setupUrl = "https://stremio-addons.net/addons/aiometadata-fortheweebs"),
        AddonPreset("AIOStreams", "Streams", "Aggregates results returned by multiple compatible Stremio stream add-ons.", setupUrl = "https://stremio-addons.net/addons/aiostreams", note = "Use only with sources you are authorized to access."),
        AddonPreset("Torrentio", "Streams", "Community stream-source add-on. Configure externally, then paste your generated manifest.", setupUrl = "https://stremio-addons.net/addons/torrentio", note = "NM Stream TV does not bundle media sources. Use only lawful content."),
        AddonPreset("Comet", "Streams", "Configurable community stream-source add-on.", setupUrl = "https://stremio-addons.net/addons/comet", note = "Use only with sources you are authorized to access."),
        AddonPreset("MediaFusion", "Streams", "Configurable Stremio provider supporting multiple media types.", setupUrl = "https://stremio-addons.net/addons/mediafusion-elfhosted", note = "Use only with sources you are authorized to access."),
        AddonPreset("PenguPlay", "Streams", "Configurable HTTP stream provider.", setupUrl = "https://stremio-addons.net/addons/penguplay", note = "Use only with sources you are authorized to access."),
        AddonPreset("Jackettio", "Streams", "Advanced self-hosted/indexer integration.", setupUrl = "https://stremio-addons.net/addons/jackettio", note = "Use only with sources and indexers you are authorized to access."),
        AddonPreset("Intelligent Debrid Search", "Debrid", "Searches media already available in a user's supported debrid cloud.", setupUrl = "https://stremio-addons.net/addons/intell-debridsearch"),
        AddonPreset("StremThru", "Debrid", "Interoperability layer for compatible debrid and Stremio services.", setupUrl = "https://stremio-addons.net/users/muniftanjim/lists/stremthru"),
        AddonPreset("Local Files", "Local", "Stremio local-file service compatibility entry.", note = "Stremio's localhost local-files service is not bundled into NM Stream TV."),
        AddonPreset("WatchHub", "Legal streaming", "Official Stremio service showing legal streaming availability.", manifestUrl = "https://watchhub.strem.io/manifest.json"),

        AddonPreset("IPTV Addon by Savi", "IPTV", "Use your private Xtream Codes provider or IPTV-org public channels.", setupUrl = "https://stremio-addons.net/addons/iptv-stremio-addon", sports = true, note = "Supports user-owned/private IPTV and public free channels."),
        AddonPreset("M3U IPTV", "IPTV", "Use your personal M3U playlist or Xtream Codes IPTV.", setupUrl = "https://stremio-addons.net/addons/m3u-iptv", sports = true),
        AddonPreset("EPG - TV", "IPTV", "Use your own M3U playlist with XMLTV EPG data.", setupUrl = "https://stremio-addons.net/addons/epg-tv-addon", sports = true),
        AddonPreset("IPTV Catchup (M3U/EPG)", "IPTV", "Self-hosted/user-configured IPTV with M3U or Xtream, EPG, categories and catch-up.", setupUrl = "https://stremio-addons.net/users/dorariel1987/lists/starred-addons", sports = true)
    )
}
