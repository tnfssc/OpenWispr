package org.futo.inputmethod.latin.uix.actions

import app.openwispr.VoiceKeyIcon
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.openwispr.InlineDictation
import org.futo.inputmethod.latin.uix.Action

val SystemVoiceInputAction = Action(
    icon = R.drawable.mic_fill,
    name = R.string.action_system_voice_input_title,
    simplePressImpl = { manager, _ -> manager.triggerSystemVoiceInput() },
    persistentState = null,
    windowImpl = null,
    shownInEditor = false,
)

val VoiceInputAction = Action(
    icon = R.drawable.openwispr_glyph,
    tintIcon = false,
    name = R.string.action_voice_input_title,
    simplePressImpl = { manager, _ -> InlineDictation.toggle(manager) },
    windowImpl = null,
    iconContent = { modifier -> VoiceKeyIcon(InlineDictation.phase.value, InlineDictation.level.floatValue, modifier) },
)
