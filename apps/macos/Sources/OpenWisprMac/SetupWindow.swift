import AVFoundation
import AppKit
import ApplicationServices
import OpenWisprCore
import SwiftUI

@MainActor
final class SetupWindowController: NSWindowController, NSWindowDelegate {
  private weak var appState: AppState?

  init(appState: AppState) {
    self.appState = appState
    let window = NSWindow(
      contentRect: NSRect(x: 0, y: 0, width: 620, height: 620),
      styleMask: [.titled, .closable, .resizable],
      backing: .buffered, defer: false)
    window.title = "Set up OpenWispr"
    window.isReleasedWhenClosed = false
    window.minSize = NSSize(width: 580, height: 560)
    super.init(window: window)
    window.contentView = NSHostingView(rootView: SetupView(appState: appState))
    window.delegate = self
    window.center()
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

  func windowDidResignKey(_ notification: Notification) { appState?.shortcutCapture.end() }

  func windowWillClose(_ notification: Notification) {
    appState?.setupDidClose()
    appState?.setupProgress.leave()
    appState?.shortcutCapture.end()
    appState?.setupDictation.leave()
  }
}

@MainActor
struct SetupView: View {
  @ObservedObject var appState: AppState
  @ObservedObject var settings: SettingsStore
  @ObservedObject var test: SetupDictation
  @ObservedObject var capture: ShortcutCapture
  @State private var step: OnboardingProgress.Step
  @State private var microphone = AVCaptureDevice.authorizationStatus(for: .audio)
  @State private var accessibility = AXIsProcessTrusted()
  @State private var requestingPermission = false

  init(appState: AppState) {
    self.appState = appState
    settings = appState.settings
    test = appState.setupDictation
    capture = appState.shortcutCapture
    let saved = appState.setupProgress.step
    _step = State(
      initialValue: saved == .ready && !appState.setupProgress.completed ? .test : saved)
  }

  private let titles = [
    "Welcome", "Speech provider", "Permissions", "Shortcuts", "Try dictation", "Ready",
  ]

  var body: some View {
    VStack(alignment: .leading, spacing: 16) {
      HStack {
        Text(titles[step.rawValue]).font(.title2.bold())
        Spacer()
        Text("\(step.rawValue + 1) of 6").foregroundStyle(.secondary)
      }
      ProgressView(value: Double(step.rawValue + 1), total: 6)
        .accessibilityLabel("Setup progress")
      ScrollView {
        VStack(alignment: .leading, spacing: 16) {
          switch step {
          case .welcome: welcome
          case .provider: provider.disabled(!appState.canStartSetupTest)
          case .permissions: permissions.disabled(!appState.canStartSetupTest)
          case .shortcuts: shortcuts
          case .test: testStep
          case .ready: ready
          }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 4)
      }
      Divider()
      HStack {
        Button("Back") { move(-1) }
          .disabled(step == .welcome || test.isBusy || capture.kind != nil)
        Button("Finish later") { closeWindow() }
        Spacer()
        if step == .ready {
          Button("Done") {
            appState.setupProgress.finish()
            closeWindow()
          }
          .keyboardShortcut(.defaultAction)
        } else {
          Button("Continue") { move(1) }
            .keyboardShortcut(.defaultAction)
            .disabled(!canContinue || test.isBusy || capture.kind != nil)
        }
      }
    }
    .padding(24)
    .frame(minWidth: 532, minHeight: 512)
    .onAppear { refreshPermissions() }
    .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification))
    { _ in
      refreshPermissions()
    }
    .onReceive(NotificationCenter.default.publisher(for: NSWindow.didBecomeKeyNotification)) { _ in
      refreshPermissions()
    }
  }

  private var welcome: some View {
    VStack(alignment: .leading, spacing: 16) {
      Text("Speak. Get text where you need it.").font(.headline)
      Text(
        "We’ll connect a speech provider, check your microphone, and try a short dictation here.")
      Text(
        "Remote providers receive your audio. Local whisper-cli keeps speech recognition on this Mac. Optional cleanup sends transcript text to its selected provider."
      )
      Text(
        "Failed dictations stay on this Mac for Retry or Discard. Successful audio is deleted. There’s no recording archive."
      )
      Text(
        "Your choices save as you go. Leave anytime and reopen Set up OpenWispr from the menu or Settings."
      )
      .foregroundStyle(.secondary)
    }
  }

  private var provider: some View {
    VStack(alignment: .leading, spacing: 12) {
      Picker("Speech provider", selection: $settings.sttProvider) {
        ForEach(STTProvider.allCases) { provider in
          Text(provider == .groq ? "Groq Whisper (recommended)" : provider.label).tag(provider)
        }
      }
      Text("Groq is a quick way to start. Keep the default model; you only need your own API key.")
        .foregroundStyle(.secondary)
      if settings.sttProvider == .local {
        Text(
          "Speech recognition stays on this Mac. Install whisper-cli and choose an existing Whisper model file."
        )
        Link(
          "Local setup instructions",
          destination: URL(string: "https://github.com/ggml-org/whisper.cpp#quick-start")!)
        LabeledContent("Model file") {
          TextField("Whisper model path", text: $settings.localModelPath)
          Button("Choose…") { chooseModel() }
        }
        DisclosureGroup("Local tools") {
          LabeledContent("whisper-cli") { TextField("Path", text: $settings.whisperBinaryPath) }
          LabeledContent("ffmpeg") { TextField("Path", text: $settings.ffmpegBinaryPath) }
          Text("ffmpeg is only needed when silence trimming is enabled.").font(.caption)
        }
        ForEach(appState.dependencyChecks(), id: \.self) {
          Text($0).font(.caption).textSelection(.enabled)
        }
      } else {
        Link("Create \(settings.sttProvider.label) API key", destination: keyURL)
        SecureField("API key", text: speechKey).textFieldStyle(.roundedBorder)
        Text("Audio goes to \(settings.sttProvider.label):\n\(speechEndpoint)")
          .font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
      }
      Toggle("Clean up wording (optional)", isOn: $settings.llmFilterEnabled)
      if settings.llmFilterEnabled {
        Picker("Cleanup provider", selection: $settings.llmProvider) {
          ForEach(LLMProvider.allCases) { Text($0.label).tag($0) }
        }
        Text(
          "Transcript text goes to \(settings.llmProvider.label):\n\(cleanupEndpoint). If cleanup fails, the original transcript is used."
        )
        .font(.caption).foregroundStyle(.secondary)
        Link("Create cleanup API key", destination: cleanupKeyURL)
        SecureField("Cleanup API key", text: cleanupKey).textFieldStyle(.roundedBorder)
        if settings.sttProvider.rawValue == settings.llmProvider.rawValue {
          Button("Use speech key for cleanup") { cleanupKey.wrappedValue = speechKey.wrappedValue }
        }
      }
      SettingsLink { Text("Advanced provider settings…") }
      Text("Endpoints and models use your current settings. Setup never resets them.")
        .font(.caption).foregroundStyle(.secondary)
    }
  }

  private var permissions: some View {
    VStack(alignment: .leading, spacing: 16) {
      Label(
        microphone == .authorized ? "Microphone allowed" : "Microphone access needed",
        systemImage: microphone == .authorized ? "checkmark.circle" : "mic")
      Text("The next test records your microphone. macOS controls permission.")
      HStack {
        Button("Allow microphone") {
          requestingPermission = true
          Task {
            _ = await AVCaptureDevice.requestAccess(for: .audio)
            microphone = AVCaptureDevice.authorizationStatus(for: .audio)
            requestingPermission = false
          }
        }
        .disabled(microphone != .notDetermined || requestingPermission)
        Button("Microphone settings…") { openPrivacy("Microphone") }
      }
      Divider()
      Label(
        accessibility ? "Accessibility allowed" : "Accessibility not allowed",
        systemImage: accessibility ? "checkmark.circle" : "keyboard")
      Text(
        "Accessibility lets OpenWispr paste into the app you’re using. The test never pastes. Clipboard-only dictation doesn’t need this permission."
      )
      Toggle("Paste automatically after dictation", isOn: $settings.autoPasteEnabled)
      if settings.autoPasteEnabled {
        HStack {
          Button("Allow Accessibility") { appState.requestAccessibilityPrompt() }
          Button("Accessibility settings…") { openPrivacy("Accessibility") }
        }
        if !accessibility {
          Text("Enable OpenWispr in System Settings, or turn off automatic paste to continue.")
            .font(.caption).foregroundStyle(.secondary)
        }
      }
      Button("Check again") { refreshPermissions() }
    }
  }

  private var shortcuts: some View {
    VStack(alignment: .leading, spacing: 16) {
      Text("Toggle: press once to record, then again to stop.")
      HStack {
        Text(toggleLabel).font(.system(.body, design: .monospaced))
        Spacer()
        Button("Record toggle shortcut") { capture.begin(.toggle, appState: appState) }
      }
      Toggle("Enable hold to speak", isOn: $settings.holdToSpeakEnabled)
      if settings.holdToSpeakEnabled {
        Text("Hold the shortcut while speaking. Release to transcribe.")
        HStack {
          Text(holdLabel).font(.system(.body, design: .monospaced))
          Spacer()
          Button("Record hold shortcut") { capture.begin(.hold, appState: appState) }
        }
        Text(
          "With Right Option, a quick tap toggles recording; a longer hold records until release."
        )
        .font(.caption).foregroundStyle(.secondary)
      }
      if capture.kind != nil {
        Text(capture.message)
        Button("Cancel capture") { capture.end() }
      }
      if !appState.shortcutError.isEmpty { Text(appState.shortcutError).foregroundStyle(.red) }
      if !appState.canStartSetupTest && capture.kind == nil {
        Text("Finish your current dictation before changing shortcuts.")
      }
      Text("Choose a combination that other apps don’t use. Your shortcut is checked when saved.")
        .font(.caption).foregroundStyle(.secondary)
      Text(
        "In everyday use, focus a text field before recording. \(settings.autoPasteEnabled ? "OpenWispr pastes there when you stop." : "OpenWispr copies the result; press Command-V to insert it.")"
      )
    }
    .disabled(!appState.canStartSetupTest && capture.kind == nil)
  }

  private var testStep: some View {
    VStack(alignment: .leading, spacing: 14) {
      Text("Try: “This is my first dictation with OpenWispr.”")
      Text("Uses your current speech provider and optional cleanup.").font(.caption)
      Text(
        "Use the buttons for this test. Text appears only here; it won’t paste or change your clipboard. Global recording shortcuts resume when you close setup."
      )
      .foregroundStyle(.secondary)
      if !appState.canStartSetupTest {
        Text(
          "Finish your current dictation before starting a test. Saved recordings are left untouched."
        )
      }
      switch test.phase {
      case .idle:
        if test.hasAudio {
          HStack {
            Button("Retry test") { test.process(settings: settings.snapshot()) }
              .disabled(!appState.canStartSetupTest)
            Button("Discard test audio") { test.discard() }
          }
        } else {
          Button(test.result.isEmpty ? "Start test recording" : "Try again") { test.start() }
            .disabled(!appState.canStartSetupTest || microphone != .authorized)
        }
      case .requesting: ProgressView("Waiting for microphone…")
      case .recording:
        Label("Recording — speak now", systemImage: "record.circle")
        Button("Stop and transcribe") { test.stop(settings: settings.snapshot()) }
      case .processing:
        ProgressView(test.processingLabel)
        Button("Cancel test") { test.cancel() }
      }
      if !test.error.isEmpty { Text(test.error).foregroundStyle(.red).textSelection(.enabled) }
      if !test.result.isEmpty {
        Text("Your result").font(.headline)
        Text(test.result).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
          .padding(12).background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
      }
      Text(
        "Test audio is kept only until success, Discard, or quitting OpenWispr. Leaving while recording stops the test without sending it. A test already processing can finish here."
      )
      .font(.caption).foregroundStyle(.secondary)
      if !appState.savedRecordings.isEmpty {
        Text(
          "You also have \(appState.savedRecordings.count) saved dictation(s). Retry or Discard those from the menu; this test does not change them."
        )
        .font(.caption)
      }
    }
  }

  private var ready: some View {
    VStack(alignment: .leading, spacing: 16) {
      Label("You’re ready to dictate", systemImage: "checkmark.circle").font(.headline)
      Text("Close setup and focus a text field. Press \(toggleLabel) to start and again to stop.")
      if settings.holdToSpeakEnabled {
        Text("Or hold \(holdLabel) while speaking and release to transcribe.")
        if settings.holdShortcut == "RightOption" {
          Text("A quick Right Option tap toggles recording.").font(.caption)
        }
      }
      Text(
        settings.autoPasteEnabled
          ? "Text is copied and pasted into the focused app. Accessibility permission is required."
          : "Text is copied. Use Command-V to paste where you want it.")
      Text(
        "If processing fails, your audio stays on this Mac. Retry uses current settings and copies the result without pasting into an old app. Discard deletes the saved audio."
      )
      Text(
        "You can change providers, shortcuts, and insertion in Settings. Reopen this setup anytime from the menu."
      )
      .foregroundStyle(.secondary)
    }
  }

  private var canContinue: Bool {
    switch step {
    case .welcome, .ready: true
    case .provider:
      settings.sttProvider == .local
        ? appState.dependencyChecks().prefix(1).allSatisfy { $0.hasPrefix("[ok]") }
          && FileManager.default.fileExists(atPath: PathResolver.expand(settings.localModelPath))
        : !speechKey.wrappedValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    case .permissions: microphone == .authorized && (!settings.autoPasteEnabled || accessibility)
    case .shortcuts:
      appState.shortcutError.isEmpty && ShortcutParser.parse(settings.toggleShortcut) != nil
        && (!settings.holdToSpeakEnabled || settings.holdShortcut == "RightOption"
          || ShortcutParser.parse(settings.holdShortcut) != nil)
    case .test: !test.result.isEmpty && !test.hasAudio
    }
  }

  private var toggleLabel: String { ShortcutParser.displayLabel(settings.toggleShortcut) }
  private var holdLabel: String { ShortcutParser.displayLabel(settings.holdShortcut) }

  private var speechKey: Binding<String> {
    switch settings.sttProvider {
    case .groq: $settings.sttGroqApiKey
    case .openai: $settings.sttOpenAIApiKey
    case .openrouter: $settings.sttOpenRouterApiKey
    case .local: .constant("")
    }
  }
  private var cleanupKey: Binding<String> {
    switch settings.llmProvider {
    case .groq: $settings.llmGroqApiKey
    case .openai: $settings.llmOpenAIApiKey
    case .openrouter: $settings.llmOpenRouterApiKey
    }
  }
  private var speechEndpoint: String {
    switch settings.sttProvider {
    case .groq: settings.sttGroqEndpoint
    case .openai: settings.sttOpenAIEndpoint
    case .openrouter: settings.sttOpenRouterEndpoint
    case .local: "This Mac"
    }
  }
  private var cleanupEndpoint: String {
    switch settings.llmProvider {
    case .groq: settings.llmGroqEndpoint
    case .openai: settings.llmOpenAIEndpoint
    case .openrouter: settings.llmOpenRouterEndpoint
    }
  }
  private var keyURL: URL { providerKeyURL(settings.sttProvider.rawValue) }
  private var cleanupKeyURL: URL { providerKeyURL(settings.llmProvider.rawValue) }
  private func providerKeyURL(_ provider: String) -> URL {
    URL(
      string: provider == "groq"
        ? "https://console.groq.com/keys"
        : provider == "openrouter"
          ? "https://openrouter.ai/keys" : "https://platform.openai.com/api-keys")!
  }
  private func move(_ delta: Int) {
    guard let next = OnboardingProgress.Step(rawValue: step.rawValue + delta) else { return }
    step = next
    appState.setupProgress.step = next
  }
  private func closeWindow() {
    appState.closeSetup()
  }
  private func refreshPermissions() {
    microphone = AVCaptureDevice.authorizationStatus(for: .audio)
    accessibility = AXIsProcessTrusted()
  }
  private func openPrivacy(_ pane: String) {
    NSWorkspace.shared.open(
      URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_\(pane)")!)
  }
  private func chooseModel() {
    let panel = NSOpenPanel()
    panel.canChooseDirectories = false
    panel.allowsMultipleSelection = false
    if panel.runModal() == .OK, let url = panel.url { settings.localModelPath = url.path }
  }
}
