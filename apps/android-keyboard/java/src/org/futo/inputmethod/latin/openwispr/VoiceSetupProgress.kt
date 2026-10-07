package org.futo.inputmethod.latin.openwispr

/** Progress is separate from provider settings; reopening never resets credentials. */
data class VoiceSetupProgress(
    val step: Int = 0,
    val active: Boolean = true,
    val testPassed: Boolean = false,
    val completed: Boolean = false,
) {
    fun providerSaved() = copy(step = 1, testPassed = false)
    fun back() = copy(step = (step - 1).coerceAtLeast(0))
    fun next(ready: Boolean) = if (ready) copy(step = (step + 1).coerceAtMost(2)) else this
    fun leave() = copy(active = false)
    fun reopen() = copy(active = true, step = if (completed) 0 else step)
    fun tested(text: String) = copy(testPassed = testPassed || text.isNotBlank())
    fun finish() = if (testPassed) copy(active = false, completed = true) else this
}
