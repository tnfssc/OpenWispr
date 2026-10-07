import AppKit
import Combine
import OpenWisprCore

@MainActor
final class ShortcutCapture: ObservableObject {
  enum Kind: Equatable { case toggle, hold }
  @Published private(set) var kind: Kind?
  @Published private(set) var message = ""
  private var monitor: Any?
  private weak var appState: AppState?

  func begin(_ kind: Kind, appState: AppState) {
    guard appState.canStartSetupTest, !appState.setupDictation.isBusy else { return }
    end()
    self.appState = appState
    self.kind = kind
    message = "Press a shortcut. Escape cancels."
    appState.setShortcutCapture(true)
    monitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown, .flagsChanged]) {
      [weak self] event in
      MainActor.assumeIsolated {
        guard let self else { return event }
        return self.receive(event)
      }
    }
  }

  private func receive(_ event: NSEvent) -> NSEvent? {
    guard let kind, let appState else { return event }
    if event.type == .keyDown && event.keyCode == 53 {
      end()
      return nil
    }
    let flags = event.modifierFlags
    guard
      let value = ShortcutParser.capturedShortcut(
        keyCode: UInt32(event.keyCode), control: flags.contains(.control),
        option: flags.contains(.option), shift: flags.contains(.shift),
        command: flags.contains(.command), allowRightOption: kind == .hold
      )
    else {
      if event.type == .keyDown {
        message = "Use Control, Option, or Command with a letter, number, Space, or F-key."
      }
      return event.type == .keyDown ? nil : event
    }
    let other = kind == .toggle ? appState.settings.holdShortcut : appState.settings.toggleShortcut
    if let parsed = ShortcutParser.parse(value), parsed == ShortcutParser.parse(other) {
      message = "Choose a different shortcut for toggle and hold."
      return nil
    }
    if kind == .toggle {
      appState.settings.toggleShortcut = value
    } else {
      appState.settings.holdShortcut = value
    }
    end()
    return nil
  }

  func end() {
    if let monitor { NSEvent.removeMonitor(monitor) }
    monitor = nil
    kind = nil
    message = ""
    appState?.setShortcutCapture(false)
    appState = nil
  }
}
