import AppKit
import Combine
import Foundation
import OpenWisprCore

@MainActor
final class AppState: ObservableObject {
  @Published private(set) var phase: AppPhase = .idle
  @Published private(set) var lastTranscript: String = ""
  @Published private(set) var lastError: String = ""

  @Published private(set) var savedRecordings: [URL] = []
  @Published private(set) var processingLabel = "Processing"

  private let recordingStore = RecordingStore(
    directory: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("OpenWispr/PendingRecordings", isDirectory: true))
  private var processingTask: Task<Void, Never>?

  let shortcutCapture = ShortcutCapture()
  @Published private(set) var shortcutError = ""
  let setupDictation = SetupDictation()
  let setupProgress = OnboardingProgress()
  private var setupWindow: SetupWindowController?
  private var launchObservation: AnyCancellable?
  private var terminationObservation: AnyCancellable?
  private var setupObservation: AnyCancellable?
  @Published private(set) var setupIsVisible = false
  @Published private(set) var recordingStartPending = false
  private var capturingShortcut = false

  let settings: SettingsStore

  private let recorder = AudioRecorder()
  private let notifications = NotificationManager()
  private let hotkeys = HotkeyManager()
  private let startAtLogin = StartAtLoginManager()
  private var pendingPasteTargetPID: pid_t?
  private var isSyncingStartAtLoginSetting = false

  init(settings: SettingsStore = SettingsStore()) {
    self.settings = settings
    do {
      savedRecordings = try recordingStore.recordings()
    } catch {
      lastError = "Could not load saved recordings: \(error.localizedDescription)"
    }

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

    setupObservation = setupDictation.objectWillChange.sink { [weak self] _ in
      self?.objectWillChange.send()
    }
    configureHotkeys()
    syncStartAtLoginFromSystem()
    launchObservation = NotificationCenter.default.publisher(
      for: NSApplication.didFinishLaunchingNotification
    )
    .sink { [weak self] _ in
      Task { @MainActor in
        guard let self, self.setupProgress.shouldPresentAutomatically else { return }
        self.openSetup()
      }
    }
    terminationObservation = NotificationCenter.default.publisher(
      for: NSApplication.willTerminateNotification
    )
    .sink { [weak self] _ in
      MainActor.assumeIsolated { self?.setupDictation.terminate() }
    }
  }

  func openSetup() {
    setupIsVisible = true
    if setupWindow == nil { setupWindow = SetupWindowController(appState: self) }
    setupWindow?.showWindow(nil)
    setupWindow?.window?.makeKeyAndOrderFront(nil)
    NSApplication.shared.activate(ignoringOtherApps: true)
  }

  func closeSetup() { setupWindow?.close() }
  func setupDidClose() { setupIsVisible = false }

  func setShortcutCapture(_ active: Bool) {
    capturingShortcut = active
    if active { hotkeys.suspend() } else { configureHotkeys() }
  }

  var canStartSetupTest: Bool {
    !isRecording && !isProcessing && !recordingStartPending && !capturingShortcut
  }

  var statusIconName: String {
    if setupDictation.phase == .recording { return "record.circle.fill" }
    if setupDictation.phase == .processing { return "hourglass" }
    return switch phase {
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
    if setupDictation.phase == .requesting { return "Waiting for test microphone" }
    if setupDictation.phase == .recording { return "Test recording" }
    if setupDictation.phase == .processing { return "Test: \(setupDictation.processingLabel)" }
    return switch phase {
    case .idle:
      "Idle"
    case .recording:
      "Recording"
    case .processing:
      processingLabel
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
    guard !setupDictation.isBusy && !recordingStartPending && !capturingShortcut else { return }
    switch phase {
    case .idle:
      guard !setupIsVisible else { return }
      startRecording(trigger: source)
    case .recording:
      stopRecording(transcribe: true)
    case .processing:
      break
    case .error:
      guard !setupIsVisible else { return }
      phase = .idle
      startRecording(trigger: source)
    }
  }

  func startRecording(trigger: RecordingTrigger) {
    guard !setupIsVisible else { return }
    guard !setupDictation.isBusy && !recordingStartPending && !capturingShortcut else { return }
    guard case .idle = phase else {
      return
    }

    lastError = ""
    pendingPasteTargetPID = NSWorkspace.shared.frontmostApplication?.processIdentifier

    recordingStartPending = true
    Task {
      defer { recordingStartPending = false }
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
      try? FileManager.default.removeItem(at: inputURL)
      phase = .idle
      pendingPasteTargetPID = nil
      return
    }

    let savedURL: URL
    do {
      savedURL = try recordingStore.save(inputURL)
    } catch {
      // Keep the original available in this session if durable storage failed.
      savedRecordings.append(inputURL)
      setError(
        "Could not save recording: \(error.localizedDescription). Recording available until quit.")
      return
    }
    savedRecordings.append(savedURL)
    processRecording(savedURL, trigger: trigger, manualRetry: false)
  }

  func retrySavedRecording() {
    guard !setupDictation.isBusy, !recordingStartPending, !isProcessing, !isRecording,
      let url = savedRecordings.first
    else { return }
    pendingPasteTargetPID = nil
    processRecording(url, trigger: .menu, manualRetry: true)
  }

  func discardSavedRecording() {
    guard !setupDictation.isBusy, !recordingStartPending, !isProcessing, !isRecording,
      let url = savedRecordings.first
    else { return }
    do {
      try recordingStore.remove(url)
      savedRecordings.removeAll { $0 == url }
      lastError = ""
      phase = .idle
    } catch {
      setError("Could not discard recording: \(error.localizedDescription)")
    }
  }

  func cancelProcessing() {
    guard isProcessing else { return }
    processingLabel = "Canceling…"
    processingTask?.cancel()
  }

  private func processRecording(_ url: URL, trigger: RecordingTrigger, manualRetry: Bool) {
    phase = .processing
    processingLabel = "Processing"
    lastError = ""
    let snapshot = settings.snapshot()

    processingTask = Task {
      defer { processingTask = nil }
      do {
        let pipeline = TranscriptionPipeline(onRetry: { [weak self] attempt in
          await self?.showRetry(attempt)
        })
        let text = try await withTimeout(seconds: 90) {
          try await pipeline.run(inputURL: url, settings: snapshot)
        }
        try Task.checkCancellation()
        // Remove audio only after a successful result, including a no-speech result.
        try recordingStore.remove(url)
        savedRecordings.removeAll { $0 == url }
        await handleTranscript(
          text, settings: snapshot, trigger: trigger,
          allowAutoPaste: (manualRetry ? TranscriptDelivery.recovery : .dictation).allowsAutoPaste)
      } catch {
        if Task.isCancelled {
          setError("Transcription canceled. Your recording is saved.")
        } else if error is TranscriptionTimeout || (error as? URLError)?.code == .timedOut {
          setError("Transcription timed out. Your recording is saved.")
        } else {
          setError("\(error.localizedDescription). Your recording is saved.")
        }
      }
    }
  }

  private func showRetry(_ attempt: Int) {
    guard isProcessing, !Task.isCancelled else { return }
    processingLabel = "Retrying… (attempt \(attempt) of 3)"
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
    _ text: String, settings: SettingsSnapshot, trigger: RecordingTrigger, allowAutoPaste: Bool
  )
    async
  {
    defer {
      phase = .idle
      pendingPasteTargetPID = nil
    }

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
      allowAutoPaste && settings.autoPasteEnabled && settings.restoreClipboardEnabled
      ? PasteInjector.captureClipboard() : nil
    PasteInjector.copyToClipboard(cleaned)

    if allowAutoPaste && settings.autoPasteEnabled {
      if trigger == .hold {
        try? await Task.sleep(nanoseconds: 120_000_000)
      }

      guard !Task.isCancelled else {
        pendingPasteTargetPID = nil
        return
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
    guard !capturingShortcut else { return }
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
      shortcutError = ""
    } catch {
      shortcutError = error.localizedDescription
      setError(error.localizedDescription)
    }
  }
}
