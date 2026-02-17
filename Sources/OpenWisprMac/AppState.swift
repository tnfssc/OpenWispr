import AppKit
import Foundation
import OpenWisprCore

@MainActor
final class AppState: ObservableObject {
  @Published private(set) var phase: AppPhase = .idle
  @Published private(set) var lastTranscript: String = ""
  @Published private(set) var lastError: String = ""

  let settings: SettingsStore

  private let recorder = AudioRecorder()
  private let notifications = NotificationManager()
  private let hotkeys = HotkeyManager()
  private let startAtLogin = StartAtLoginManager()
  private let pipeline = TranscriptionPipeline()
  private var pendingPasteTargetPID: pid_t?
  private var isSyncingStartAtLoginSetting = false

  init(settings: SettingsStore = SettingsStore()) {
    self.settings = settings

    self.settings.hotkeyDidChange = { [weak self] in
      Task { @MainActor in
        self?.configureHotkeys()
      }
    }

    self.settings.startAtLoginDidChange = { [weak self] enabled in
      Task { @MainActor in
        self?.applyStartAtLogin(enabled)
      }
    }

    configureHotkeys()
    syncStartAtLoginFromSystem()
  }

  var statusIconName: String {
    switch phase {
    case .idle:
      "mic"
    case .recording:
      "record.circle.fill"
    case .processing:
      "hourglass"
    case .error:
      "exclamationmark.triangle.fill"
    }
  }

  var statusLabel: String {
    switch phase {
    case .idle:
      "Idle"
    case .recording:
      "Recording"
    case .processing:
      "Processing"
    case .error(let message):
      "Error: \(message)"
    }
  }

  var isRecording: Bool {
    if case .recording = phase {
      return true
    }
    return false
  }

  var isProcessing: Bool {
    if case .processing = phase {
      return true
    }
    return false
  }

  var lastTranscriptPreview: String {
    let collapsed =
      lastTranscript
      .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
      .trimmingCharacters(in: .whitespacesAndNewlines)

    let maxLength = 220
    guard collapsed.count > maxLength else {
      return collapsed
    }

    let endIndex = collapsed.index(collapsed.startIndex, offsetBy: maxLength)
    return String(collapsed[..<endIndex]) + "..."
  }

  var startAtLoginSupported: Bool {
    startAtLogin.isSupported
  }

  func toggleRecording(source: RecordingTrigger = .toggle) {
    switch phase {
    case .idle:
      startRecording(trigger: source)
    case .recording:
      stopRecording(transcribe: true)
    case .processing:
      break
    case .error:
      phase = .idle
      startRecording(trigger: source)
    }
  }

  func startRecording(trigger: RecordingTrigger) {
    guard case .idle = phase else {
      return
    }

    lastError = ""
    pendingPasteTargetPID = NSWorkspace.shared.frontmostApplication?.processIdentifier

    Task {
      let granted = await recorder.requestMicrophoneAccess()
      guard granted else {
        setError("Microphone permission denied")
        return
      }

      do {
        _ = try recorder.startRecording()
        phase = .recording(trigger)
      } catch {
        setError(error.localizedDescription)
      }
    }
  }

  func stopRecording(transcribe: Bool) {
    guard case .recording(let trigger) = phase else {
      return
    }

    let inputURL: URL
    do {
      inputURL = try recorder.stopRecording()
    } catch {
      setError(error.localizedDescription)
      return
    }

    guard transcribe else {
      phase = .idle
      pendingPasteTargetPID = nil
      return
    }

    phase = .processing
    let snapshot = settings.snapshot()

    Task {
      do {
        let text = try await pipeline.run(inputURL: inputURL, settings: snapshot)
        await handleTranscript(text, settings: snapshot, trigger: trigger)
      } catch {
        setError(error.localizedDescription)
      }
    }
  }

  func requestAccessibilityPrompt() {
    PasteInjector.ensureAccessibilityPermissionPrompt()
  }

  func dependencyChecks() -> [String] {
    let snapshot = settings.snapshot()
    let whisperPath = PathResolver.resolveExecutable(snapshot.whisperBinaryPath)
    let ffmpegPath = PathResolver.resolveExecutable(snapshot.ffmpegBinaryPath)
    let modelPath = PathResolver.expand(snapshot.localModelPath)

    return [
      checkLine(path: whisperPath, label: "whisper-cli"),
      checkLine(path: ffmpegPath, label: "ffmpeg"),
      checkLine(path: modelPath, label: "local model"),
    ]
  }

  private func checkLine(path: String, label: String) -> String {
    if FileManager.default.fileExists(atPath: path) {
      return "[ok] \(label): \(path)"
    }
    return "[missing] \(label): \(path)"
  }

  private func handleTranscript(
    _ text: String, settings: SettingsSnapshot, trigger: RecordingTrigger
  )
    async
  {
    phase = .idle

    let cleaned = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !cleaned.isEmpty else {
      notifications.notify(
        title: "OpenWispr",
        body: "No speech detected",
        enabled: settings.notificationsEnabled
      )
      return
    }

    lastTranscript = cleaned
    let clipboardSnapshot =
      settings.autoPasteEnabled && settings.restoreClipboardEnabled
      ? PasteInjector.captureClipboard() : nil
    PasteInjector.copyToClipboard(cleaned)

    if settings.autoPasteEnabled {
      if trigger == .hold {
        try? await Task.sleep(nanoseconds: 120_000_000)
      }

      do {
        try PasteInjector.pasteClipboard(preferredTargetPID: pendingPasteTargetPID)

        if let clipboardSnapshot {
          try? await Task.sleep(nanoseconds: 120_000_000)
          PasteInjector.restoreClipboard(clipboardSnapshot)
        }
      } catch {
        let message = "Copied to clipboard. Auto-paste failed: \(error.localizedDescription)"
        lastError = message

        notifications.notify(
          title: "OpenWispr",
          body: message,
          enabled: settings.notificationsEnabled
        )
        pendingPasteTargetPID = nil
        return
      }
    }

    lastError = ""
    pendingPasteTargetPID = nil

    notifications.notify(
      title: "OpenWispr",
      body: "Transcription complete",
      enabled: settings.notificationsEnabled
    )
  }

  private func setError(_ message: String) {
    lastError = message
    phase = .error(message)
    pendingPasteTargetPID = nil
    notifications.notify(
      title: "OpenWispr Error",
      body: message,
      enabled: settings.notificationsEnabled
    )
  }

  private func syncStartAtLoginFromSystem() {
    guard startAtLogin.isSupported else {
      isSyncingStartAtLoginSetting = true
      settings.startAtLoginEnabled = false
      isSyncingStartAtLoginSetting = false
      return
    }

    do {
      let enabled = try startAtLogin.currentEnabled()
      isSyncingStartAtLoginSetting = true
      settings.startAtLoginEnabled = enabled
      isSyncingStartAtLoginSetting = false
    } catch {
      lastError = error.localizedDescription
    }
  }

  private func applyStartAtLogin(_ enabled: Bool) {
    guard !isSyncingStartAtLoginSetting else {
      return
    }

    do {
      try startAtLogin.setEnabled(enabled)
      lastError = ""
    } catch {
      lastError = error.localizedDescription
      syncStartAtLoginFromSystem()
    }
  }

  private func configureHotkeys() {
    do {
      try hotkeys.register(
        toggleShortcut: settings.toggleShortcut,
        holdShortcut: settings.holdShortcut,
        holdEnabled: settings.holdToSpeakEnabled,
        onToggle: { [weak self] in
          Task { @MainActor in
            self?.toggleRecording(source: .toggle)
          }
        },
        onHoldStart: { [weak self] in
          Task { @MainActor in
            self?.startRecording(trigger: .hold)
          }
        },
        onHoldStop: { [weak self] in
          Task { @MainActor in
            self?.stopRecording(transcribe: true)
          }
        }
      )
    } catch {
      setError(error.localizedDescription)
    }
  }
}
