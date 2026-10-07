import Foundation

/// Setup state only: no credentials, transcripts, or recording history.
public struct OnboardingProgress {
  public enum Step: Int, CaseIterable, Sendable {
    case welcome, provider, permissions, shortcuts, test, ready
  }

  private let defaults: UserDefaults
  public init(defaults: UserDefaults = .standard) { self.defaults = defaults }

  public var step: Step {
    get { Step(rawValue: defaults.integer(forKey: "setupStep")) ?? .welcome }
    nonmutating set { defaults.set(newValue.rawValue, forKey: "setupStep") }
  }

  public var completed: Bool { defaults.bool(forKey: "setupCompleted") }

  public var shouldPresentAutomatically: Bool {
    !completed && !defaults.bool(forKey: "setupDeferred") && !hasExistingConfiguration
  }

  // A deliberately selected provider is existing configuration, even if its key
  // is temporarily missing. Never reset settings or force an upgrade walkthrough.
  public var hasExistingConfiguration: Bool {
    if defaults.object(forKey: "sttProvider") != nil { return true }
    return ["sttGroqApiKey", "sttOpenAIApiKey", "sttOpenRouterApiKey", "localModelPath"]
      .contains {
        !(defaults.string(forKey: $0) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
      }
  }

  public func leave() { defaults.set(true, forKey: "setupDeferred") }
  public func finish() {
    defaults.set(true, forKey: "setupCompleted")
    step = .ready
  }
}

/// Setup tests must never touch the clipboard or a previously focused app.
public enum TranscriptDelivery: Sendable {
  case dictation, recovery, setupTest

  public var copiesToClipboard: Bool { self != .setupTest }
  public var allowsAutoPaste: Bool { self == .dictation }
}
