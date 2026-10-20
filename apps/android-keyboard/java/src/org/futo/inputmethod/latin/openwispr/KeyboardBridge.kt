package org.futo.inputmethod.latin.openwispr

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.inputmethod.InputMethodManager
import java.lang.ref.WeakReference

/**
 * Lets the floating mic (same process) bring the keyboard back. After the accessibility service has
 * kept it hidden, the app with focus still thinks its show request worked and ignores taps, so the
 * keyboard has to ask to be shown itself.
 */
object KeyboardBridge {
    private var ime: WeakReference<InputMethodService>? = null

    fun attach(service: InputMethodService) {
        ime = WeakReference(service)
    }

    fun detach(service: InputMethodService) {
        if (ime?.get() === service) ime = null
    }

    fun requestShow() {
        // Only works while OpenWispr is the active keyboard; a stale service just ignores it.
        val service = ime?.get() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // A hide first clears the app's stale "keyboard requested" state; a bare show is ignored.
            runCatching { service.requestHideSelf(0) }
            service.window.window?.decorView?.post { runCatching { service.requestShowSelf(0) } }
        } else {
            val token = service.window.window?.attributes?.token ?: return
            @Suppress("DEPRECATION")
            runCatching { service.getSystemService(InputMethodManager::class.java).showSoftInputFromInputMethod(token, 0) }
        }
    }
}
