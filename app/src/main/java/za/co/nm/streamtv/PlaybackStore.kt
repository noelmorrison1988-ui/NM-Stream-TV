package za.co.nm.streamtv

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PlaybackStore(context: Context) {
    private val prefs = context.getSharedPreferences("nm_stream_playback", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "continue_watching"

    fun load(): List<PlaybackProgress> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<PlaybackProgress>>() {}.type
            gson.fromJson<List<PlaybackProgress>>(json, type)
        }.getOrDefault(emptyList())
            .filter { it.positionMs > 20_000 && (it.durationMs <= 0 || it.percent < 95) }
            .sortedByDescending { it.updatedAtMs }
            .take(30)
    }

    fun save(progress: PlaybackProgress) {
        val all = load().toMutableList()
        all.removeAll { sameKey(it, progress) }
        if (progress.positionMs > 20_000 && (progress.durationMs <= 0 || progress.percent < 95)) {
            all.add(0, progress)
        }
        prefs.edit().putString(key, gson.toJson(all.take(30))).apply()
    }

    fun complete(media: AppMedia, videoId: String) {
        val all = load().filterNot { it.media.meta.id == media.meta.id && it.videoId == videoId }
        prefs.edit().putString(key, gson.toJson(all)).apply()
    }

    fun resumePosition(media: AppMedia, videoId: String): Long =
        load().firstOrNull { it.media.meta.id == media.meta.id && it.videoId == videoId }?.positionMs ?: 0L

    private fun sameKey(a: PlaybackProgress, b: PlaybackProgress): Boolean =
        a.media.meta.id == b.media.meta.id && a.videoId == b.videoId
}
