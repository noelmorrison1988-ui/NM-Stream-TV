package za.co.nm.streamtv

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Offline startup artwork supplied for NM Stream TV.
 *
 * The image is split across small source chunks so the APK does not depend on
 * a remote image host at launch.
 */
internal object SplashArtwork {
    private val encoded: String by lazy {
        buildString(68652) {
            append(SplashChunk0.DATA)
            append(SplashChunk1.DATA)
            append(SplashChunk2.DATA)
            append(SplashChunk3.DATA)
            append(SplashChunk4.DATA)
            append(SplashChunk5.DATA)
        }
    }

    fun decode(): ImageBitmap? = runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}
