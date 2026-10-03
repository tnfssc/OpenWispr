package org.futo.inputmethod.latin.openwispr

import org.futo.voiceinput.shared.SpeechWave
import org.futo.voiceinput.shared.pcmRms
import org.futo.voiceinput.shared.speechLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechWaveTest {
    @Test
    fun `short reads ignore stale loud samples in the reused buffer`() {
        assertEquals(0f, pcmRms(shortArrayOf(0, 0, Short.MAX_VALUE), 2), 0f)
        assertEquals(1f, pcmRms(shortArrayOf(Short.MIN_VALUE), 1), 0f)
        assertEquals(0f, pcmRms(shortArrayOf(123), 0), 0f)
    }

    @Test
    fun `speech retains dynamic range and silence has no idle motion`() {
        assertEquals(0f, speechLevel(0f), 0f)
        assertEquals(0f, speechLevel(.0001f), 0f)
        val quiet = speechLevel(.01f)
        val normal = speechLevel(.03f)
        val loud = speechLevel(.1f)
        assertTrue(quiet < normal && normal < loud)
        assertTrue(loud < 1f)
        assertEquals(0f, speechLevel(Float.NaN), 0f)
    }

    @Test
    fun `a syllable reaches the left before the right and then settles`() {
        val wave = SpeechWave()
        repeat(20) { wave.advance(0f, .02f) }
        wave.advance(1f, .02f)
        assertTrue(wave.levelAt(0f) > .5f)
        assertEquals(0f, wave.levelAt(.14f), .0001f)
        repeat(7) { wave.advance(1f, .02f) }
        assertTrue(wave.levelAt(.14f) > .5f)
        repeat(70) { wave.advance(0f, .02f) }
        assertTrue(wave.levelAt(0f) < .0001f)
        assertTrue(wave.levelAt(.14f) < .0001f)
    }

    @Test
    fun `a sustained volume does not oscillate on its own`() {
        val wave = SpeechWave()
        repeat(120) { wave.advance(.5f, .016f) }
        val left = wave.levelAt(0f)
        val right = wave.levelAt(.14f)
        repeat(120) { wave.advance(.5f, .016f) }
        assertEquals(.5f, left, .0001f)
        assertEquals(left, wave.levelAt(0f), .0001f)
        assertEquals(right, wave.levelAt(.14f), .0001f)
    }
}
