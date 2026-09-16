import AppKit
import SwiftUI

final class AppDelegate: NSObject, NSApplicationDelegate {
  func applicationDidFinishLaunching(_ notification: Notification) {
    NSApplication.shared.setActivationPolicy(.accessory)
  }
}

struct MenuBarContentView: View {
  @EnvironmentObject var appState: AppState

  var body: some View {
    VStack(alignment: .leading, spacing: 12) {
      Text("OpenWispr")
        .font(.headline)

      Text(appState.statusLabel)
        .font(.subheadline)
        .foregroundStyle(.secondary)

      Button(appState.isRecording ? "Stop Recording" : "Start Recording") {
        appState.toggleRecording(source: .menu)
      }
      .keyboardShortcut("r", modifiers: [.control, .option])
      .disabled(appState.isProcessing)

      if appState.isProcessing {
        Button("Cancel") { appState.cancelProcessing() }
      }

      if !appState.savedRecordings.isEmpty && !appState.isProcessing {
        Text("Saved recordings: \(appState.savedRecordings.count)")
          .font(.caption)
          .foregroundStyle(.secondary)
        HStack {
          Button("Retry") { appState.retrySavedRecording() }
          Button("Discard") { appState.discardSavedRecording() }
        }
        .disabled(appState.isRecording)
        Text("Retry processes the oldest recording and copies the result.")
          .font(.caption2)
          .foregroundStyle(.secondary)
      }

      if !appState.lastTranscript.isEmpty {
        HStack {
          Text("Last transcript")
            .font(.caption)
            .foregroundStyle(.secondary)

          Spacer()

          Button("Copy") {
            PasteInjector.copyToClipboard(appState.lastTranscript)
          }
          .buttonStyle(.bordered)
          .controlSize(.small)
          .fixedSize()
          .font(.caption)
        }

        Text(appState.lastTranscriptPreview)
          .lineLimit(3)
          .truncationMode(.tail)
          .fixedSize(horizontal: false, vertical: true)
          .textSelection(.enabled)
          .font(.system(size: 12))

        if appState.lastTranscriptPreview != appState.lastTranscript {
          Text("Preview truncated")
            .font(.caption2)
            .foregroundStyle(.secondary)
        }
      }

      if !appState.lastError.isEmpty {
        Text("Last error")
          .font(.caption)
          .foregroundStyle(.secondary)

        Text(appState.lastError)
          .lineLimit(3)
          .font(.system(size: 12))
          .foregroundStyle(.red)
      }

      Divider()

      SettingsLink {
        Text("Settings")
      }

      Button("Quit") {
        NSApplication.shared.terminate(nil)
      }
    }
    .padding(14)
    .frame(width: 340)
  }
}

@main
struct OpenWisprApp: App {
  @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
  @StateObject private var appState = AppState()

  var body: some Scene {
    MenuBarExtra("OpenWispr", systemImage: appState.statusIconName) {
      MenuBarContentView()
        .environmentObject(appState)
    }
    .menuBarExtraStyle(.window)

    Settings {
      SettingsView(settings: appState.settings)
        .environmentObject(appState)
    }
  }
}
