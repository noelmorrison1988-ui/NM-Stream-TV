package za.co.nm.streamtv

import android.content.Context
import android.content.Intent

object KodiCore {
    const val VERSION_LABEL = "Kodi 21.3 Omega"
    private const val SPLASH_CLASS = "org.xbmc.kodi.Splash"

    fun isAvailable(): Boolean =
        runCatching { Class.forName(SPLASH_CLASS) }.isSuccess

    fun open(context: Context): Boolean {
        val clazz = runCatching { Class.forName(SPLASH_CLASS) }.getOrNull() ?: return false
        return runCatching {
            context.startActivity(
                Intent(context, clazz).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        }.getOrDefault(false)
    }
}
