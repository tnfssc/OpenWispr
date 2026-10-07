package org.futo.inputmethod.latin.openwispr

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.futo.inputmethod.latin.uix.settings.pages.VoiceSetupStore
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

class VoiceSetupStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before @After fun clearProgress() {
        context.getSharedPreferences("openwispr_voice_setup", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun configuredUsersAreNotForcedIntoSetup() {
        assertFalse(VoiceSetupStore(context).load(configured = true).active)
        assertTrue(VoiceSetupStore(context).load(configured = false).active)
    }
    @Test fun leavingAndCompletionSurviveRecreation() {
        val store = VoiceSetupStore(context)
        val left = VoiceSetupProgress(step = 1).leave()
        store.save(left)
        assertEquals(left, VoiceSetupStore(context).load(configured = true))
        val complete = left.reopen().next(true).tested("safe result").finish()
        store.save(complete)
        assertEquals(complete, VoiceSetupStore(context).load(configured = true))
    }
    @Test fun setupProgressNeverResetsProviderSettings() {
        val before = OpenWisprConfigStore.load(context)
        VoiceSetupStore(context).save(VoiceSetupProgress(step = 2).tested("safe result").finish())
        assertEquals(before, OpenWisprConfigStore.load(context))
    }
}
