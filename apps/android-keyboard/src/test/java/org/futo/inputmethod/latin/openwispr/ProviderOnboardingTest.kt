package org.futo.inputmethod.latin.openwispr

import app.openwispr.OpenWisprProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderOnboardingTest {
    @Test
    fun `provider onboarding points to the correct key pages`() {
        val groq = providerOnboardingDetails(OpenWisprProvider.GROQ)
        assertEquals("https://console.groq.com/keys", groq.url)
        assertEquals("Create Groq API key", groq.action)
        assertTrue(groq.message.contains("fastest free default"))

        val openRouter = providerOnboardingDetails(OpenWisprProvider.OPEN_ROUTER)
        assertEquals("https://openrouter.ai/keys", openRouter.url)
        assertEquals("Create OpenRouter API key", openRouter.action)
        assertTrue(openRouter.message.contains("NVIDIA Parakeet"))
        assertTrue(openRouter.message.contains("Qwen 3.6 27B"))
    }
}
