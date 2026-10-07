package za.co.nm.streamtv

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SearchHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("nm_stream_mobile_lite_local", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "recent_searches"

    fun load(): List<String> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(json, type)
        }.getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(5)
    }

    fun record(query: String): List<String> {
        val clean = query.trim()
        if (clean.isBlank()) return load()
        val updated = buildList {
            add(clean)
            addAll(load().filterNot { it.equals(clean, ignoreCase = true) })
        }.take(5)
        prefs.edit().putString(key, gson.toJson(updated)).apply()
        return updated
    }
}
