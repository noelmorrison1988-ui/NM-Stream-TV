package za.co.nm.streamtv

object MediaPolicy {
    private val animeTokens = listOf(
        "anime", "kitsu", "anilist", "myanimelist", "manga", "weeb", "otaku"
    )

    fun allows(item: AppMedia): Boolean = !isAnime(item)

    fun filter(items: List<AppMedia>): List<AppMedia> = items.filter(::allows)

    fun allowsAddon(addon: InstalledAddon): Boolean {
        val text = listOf(
            addon.manifest.id,
            addon.manifest.name,
            addon.manifest.description.orEmpty(),
            addon.manifestUrl
        ).joinToString(" ").lowercase()
        return animeTokens.none(text::contains)
    }

    fun isAnime(item: AppMedia): Boolean {
        if (item.meta.isAnime) return true
        val addon = item.originAddonName.orEmpty().lowercase()
        if (animeTokens.any(addon::contains)) return true

        val genreText = item.meta.genres.joinToString(" ").lowercase()
        if (animeTokens.any(genreText::contains)) return true

        val text = buildString {
            append(item.meta.name)
            append(' ')
            append(item.meta.description.orEmpty())
            append(' ')
            append(item.meta.releaseInfo.orEmpty())
        }.lowercase()

        return listOf(
            " anime ", " anime.", " anime,", " anime:", "japanese animation",
            "manga adaptation", "based on the manga"
        ).any { token -> (" $text ").contains(token) }
    }
}
