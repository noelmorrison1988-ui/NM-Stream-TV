package za.co.nm.streamtv

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PlaybackStore(context: Context) {
    private val prefs = context.getSharedPreferences("nm_stream_playback", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val continueKey = "continue_watching"
    private val historyKey = "watch_history"

    fun load(): List<PlaybackProgress> =
        readList(continueKey)
            .filter { it.positionMs > 20_000 && (it.durationMs <= 0 || it.percent < 95) }
            .sortedByDescending { it.updatedAtMs }
            .take(30)

    fun history(): List<PlaybackProgress> =
        (readList(historyKey) + readList(continueKey))
            .filter { it.positionMs > 5_000 }
            .sortedByDescending { it.updatedAtMs }
            .distinctBy {
                if (it.media.meta.type == "series") "series|${it.media.meta.id}"
                else "movie|${it.media.meta.id}"
            }
            .take(60)

    fun save(progress: PlaybackProgress) {
        recordHistory(progress)

        val all = load().toMutableList()
        all.removeAll { sameKey(it, progress) }
        if (progress.positionMs > 20_000 && (progress.durationMs <= 0 || progress.percent < 95)) {
            all.add(0, progress)
        }
        writeList(continueKey, all.take(30))
    }

    fun complete(media: AppMedia, videoId: String) {
        val all = load().filterNot { it.media.meta.id == media.meta.id && it.videoId == videoId }
        writeList(continueKey, all)
    }

    fun resumePosition(media: AppMedia, videoId: String): Long =
        load().firstOrNull { it.media.meta.id == media.meta.id && it.videoId == videoId }?.positionMs ?: 0L

    private fun recordHistory(progress: PlaybackProgress) {
        val all = readList(historyKey).toMutableList()
        all.removeAll { sameKey(it, progress) }
        if (progress.positionMs > 5_000) all.add(0, progress)
        writeList(historyKey, all.take(100))
    }

    private fun readList(key: String): List<PlaybackProgress> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<PlaybackProgress>>() {}.type
            gson.fromJson<List<PlaybackProgress>>(json, type)
        }.getOrDefault(emptyList())
    }

    private fun writeList(key: String, items: List<PlaybackProgress>) {
        prefs.edit().putString(key, gson.toJson(items)).apply()
    }

    private fun sameKey(a: PlaybackProgress, b: PlaybackProgress): Boolean =
        a.media.meta.id == b.media.meta.id && a.videoId == b.videoId
}
