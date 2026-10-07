package za.co.nm.streamtv

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class MyListStore(context: Context) {
    private val prefs = context.getSharedPreferences("nm_stream_sarah_edition_local", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "sarah_picks"

    fun load(): List<AppMedia> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<AppMedia>>() {}.type
            gson.fromJson<List<AppMedia>>(json, type)
        }.getOrDefault(emptyList())
            .filter(MediaPolicy::allows)
            .distinctBy(::mediaKey)
    }

    fun contains(item: AppMedia): Boolean =
        load().any { mediaKey(it) == mediaKey(item) || titleKey(it) == titleKey(item) }

    fun toggle(item: AppMedia): Boolean {
        val current = load().toMutableList()
        val index = current.indexOfFirst {
            mediaKey(it) == mediaKey(item) || titleKey(it) == titleKey(item)
        }
        val added = index < 0
        if (added) current.add(0, item) else current.removeAt(index)
        prefs.edit().putString(key, gson.toJson(current.take(200))).apply()
        return added
    }

    private fun mediaKey(item: AppMedia): String =
        item.meta.tmdbId?.let { "tmdb|${item.meta.type}|$it" }
            ?: "${item.meta.type}|${item.meta.id}"

    private fun titleKey(item: AppMedia): String =
        "${item.meta.type}|" + item.meta.name.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}
