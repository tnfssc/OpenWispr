import Foundation

enum STTProvider: String, CaseIterable, Identifiable {
  case local
  case openai
  case groq

  var id: String { rawValue }

  var label: String {
    switch self {
    case .local:
      "Local whisper-cli"
    case .openai:
      "OpenAI Whisper"
    case .groq:
      "Groq Whisper"
    }
  }
}

enum LLMProvider: String, CaseIterable, Identifiable {
  case openai
  case groq

  var id: String { rawValue }

  var label: String {
    switch self {
    case .openai:
      "OpenAI"
    case .groq:
      "Groq"
    }
  }
}

enum RecordingTrigger: String {
  case toggle
  case hold
  case menu
}

enum AppPhase: Equatable {
  case idle
  case recording(RecordingTrigger)
  case processing
  case error(String)
}

struct SettingsSnapshot {
  let holdToSpeakEnabled: Bool
  let holdShortcut: String
  let toggleShortcut: String
  let autoPasteEnabled: Bool
  let notificationsEnabled: Bool
  let silenceTrimEnabled: Bool
  let silenceThreshold: String
  let silenceDuration: Double

  let whisperBinaryPath: String
  let ffmpegBinaryPath: String
  let localModelPath: String

  let sttProvider: STTProvider
  let sttOpenAIEndpoint: String
  let sttOpenAIModel: String
  let sttOpenAIApiKey: String
  let sttGroqEndpoint: String
  let sttGroqModel: String
  let sttGroqApiKey: String

  let llmFilterEnabled: Bool
  let llmProvider: LLMProvider
  let llmOpenAIEndpoint: String
  let llmOpenAIModel: String
  let llmOpenAIApiKey: String
  let llmGroqEndpoint: String
  let llmGroqModel: String
  let llmGroqApiKey: String
  let llmCleanupPrompt: String
}
