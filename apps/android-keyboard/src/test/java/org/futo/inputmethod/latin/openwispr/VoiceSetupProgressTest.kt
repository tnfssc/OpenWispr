package org.futo.inputmethod.latin.openwispr

import org.junit.Assert.*
import org.junit.Test

class VoiceSetupProgressTest {
    @Test fun progressRequiresReadinessAndSafeTest() {
        val initial = VoiceSetupProgress()
        assertEquals(initial, initial.next(false))
        val test = initial.next(true).next(true)
        assertEquals(2, test.step)
        assertFalse(test.finish().completed)
        assertFalse(test.tested("old provider").providerSaved().testPassed)
        assertFalse(test.tested(" ").finish().completed)
        assertTrue(test.tested("hello").finish().completed)
    }
    @Test fun leaveAndReopenPreserveProgress() {
        val left = VoiceSetupProgress().next(true).leave()
        assertFalse(left.active)
        assertEquals(1, left.reopen().step)
        assertTrue(left.reopen().active)
        assertEquals(0, left.reopen().back().back().step)
    }
    @Test fun completionDoesNotTrapConfiguredUsers() {
        val complete = VoiceSetupProgress(step = 2).tested("hello").finish()
        assertTrue(complete.completed)
        assertFalse(complete.active)
        assertTrue(complete.reopen().active)
        assertTrue(complete.reopen().completed)
        assertEquals(0, complete.reopen().step)
    }
}
