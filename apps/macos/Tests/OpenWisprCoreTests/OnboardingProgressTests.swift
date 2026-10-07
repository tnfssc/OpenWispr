import Foundation
import OpenWisprCore
import Testing

private func isolatedDefaults() -> (UserDefaults, String) {
  let name = "OpenWispr.setup-tests.\(UUID().uuidString)"
  return (UserDefaults(suiteName: name)!, name)
}

@Test func setupFreshInstallPresentsWithoutWritingProviderSettings() {
  let (defaults, name) = isolatedDefaults()
  defer { defaults.removePersistentDomain(forName: name) }
  let progress = OnboardingProgress(defaults: defaults)
  #expect(progress.shouldPresentAutomatically)
  #expect(progress.step == .welcome)
  #expect(defaults.object(forKey: "sttProvider") == nil)
}

@Test func setupLeaveAndReopenPreserveProgressWithoutCompleting() {
  let (defaults, name) = isolatedDefaults()
  defer { defaults.removePersistentDomain(forName: name) }
  let progress = OnboardingProgress(defaults: defaults)
  progress.step = .shortcuts
  progress.leave()
  let reopened = OnboardingProgress(defaults: defaults)
  #expect(!reopened.shouldPresentAutomatically)
  #expect(!reopened.completed)
  #expect(reopened.step == .shortcuts)
  reopened.step = .permissions  // Back persists too.
  #expect(OnboardingProgress(defaults: defaults).step == .permissions)
}

@Test func setupCompletionSurvivesRestart() {
  let (defaults, name) = isolatedDefaults()
  defer { defaults.removePersistentDomain(forName: name) }
  OnboardingProgress(defaults: defaults).finish()
  let restarted = OnboardingProgress(defaults: defaults)
  #expect(restarted.completed)
  #expect(restarted.step == .ready)
  #expect(!restarted.shouldPresentAutomatically)
}

@Test(arguments: ["sttGroqApiKey", "sttOpenAIApiKey", "sttOpenRouterApiKey", "localModelPath"])
func setupExistingUsersAreNotInterruptedOrReset(key: String) {
  let (defaults, name) = isolatedDefaults()
  defer { defaults.removePersistentDomain(forName: name) }
  defaults.set("existing value", forKey: key)
  defaults.set("my custom endpoint", forKey: "sttGroqEndpoint")
  let progress = OnboardingProgress(defaults: defaults)
  #expect(!progress.shouldPresentAutomatically)
  progress.leave()
  progress.finish()
  #expect(defaults.string(forKey: key) == "existing value")
  #expect(defaults.string(forKey: "sttGroqEndpoint") == "my custom endpoint")
}

@Test func setupExplicitProviderWithoutKeyIsStillAnExistingUser() {
  let (defaults, name) = isolatedDefaults()
  defer { defaults.removePersistentDomain(forName: name) }
  defaults.set("local", forKey: "sttProvider")
  #expect(!OnboardingProgress(defaults: defaults).shouldPresentAutomatically)
}

@Test func setupWhitespaceCredentialsDoNotCountAsConfigured() {
  let (defaults, name) = isolatedDefaults()
  defer { defaults.removePersistentDomain(forName: name) }
  defaults.set("  \n", forKey: "sttGroqApiKey")
  defaults.set(500, forKey: "setupStep")
  let progress = OnboardingProgress(defaults: defaults)
  #expect(progress.shouldPresentAutomatically)
  #expect(progress.step == .welcome)
}

@Test func setupAndRecoveryDeliveryNeverAutoPaste() {
  #expect(!TranscriptDelivery.setupTest.copiesToClipboard)
  #expect(!TranscriptDelivery.setupTest.allowsAutoPaste)
  #expect(TranscriptDelivery.recovery.copiesToClipboard)
  #expect(!TranscriptDelivery.recovery.allowsAutoPaste)
  #expect(TranscriptDelivery.dictation.copiesToClipboard)
  #expect(TranscriptDelivery.dictation.allowsAutoPaste)
}
