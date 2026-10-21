package org.futo.inputmethod.latin.openwispr

import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.SettingsActivity

/** Switches the floating mic bubble on or off from the keyboard toolbar. */
val FloatingMicAction = Action(
    icon = R.drawable.floating_mic,
    name = R.string.action_floating_mic_title,
    simplePressImpl = { manager, _ ->
        val context = manager.getContext()
        if (!FloatingMicService.isEnabled(context)) {
            // Android only lets the user grant accessibility, so send them to the setup page.
            SettingsActivity.openToNavDest(context, "openwisprFloatingMic")
        } else manager.getLifecycleScope().launch {
            val on = !context.getSetting(FLOATING_MIC_BUBBLE)
            context.setSetting(FLOATING_MIC_BUBBLE, on)
            // The floating mic service confirms on screen; toasts from the keyboard are often suppressed.
            manager.announce(context.getString(
                if (on) R.string.floating_mic_turned_on else R.string.floating_mic_turned_off))
        }
    },
    windowImpl = null,
)
