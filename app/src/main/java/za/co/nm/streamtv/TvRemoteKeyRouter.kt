package za.co.nm.streamtv

import android.view.KeyEvent

internal object TvRemoteKeyRouter {
    var handler: ((KeyEvent) -> Boolean)? = null

    fun dispatch(event: KeyEvent): Boolean = handler?.invoke(event) == true
}
