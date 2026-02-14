import AppKit
import ApplicationServices
import Carbon
import Foundation

enum PasteInjectorError: LocalizedError {
  case accessibilityPermissionMissing
  case eventInjectionFailed

  var errorDescription: String? {
    switch self {
    case .accessibilityPermissionMissing:
      "Auto-paste requires Accessibility permission."
    case .eventInjectionFailed:
      "Could not inject Cmd+V key events."
    }
  }
}

enum PasteInjector {
  static func copyToClipboard(_ text: String) {
    let pasteboard = NSPasteboard.general
    pasteboard.clearContents()
    pasteboard.setString(text, forType: .string)
  }

  static func ensureAccessibilityPermissionPrompt() {
    let options = ["AXTrustedCheckOptionPrompt": true] as CFDictionary
    _ = AXIsProcessTrustedWithOptions(options)
  }

  static func pasteClipboard(preferredTargetPID: pid_t?) throws {
    guard AXIsProcessTrusted() else {
      ensureAccessibilityPermissionPrompt()
      throw PasteInjectorError.accessibilityPermissionMissing
    }

    let targetPID = preferredTargetPID ?? NSWorkspace.shared.frontmostApplication?.processIdentifier
    let currentPID = ProcessInfo.processInfo.processIdentifier
    let keyCodeV = CGKeyCode(kVK_ANSI_V)
    guard let source = CGEventSource(stateID: .hidSystemState),
      let keyDown = CGEvent(keyboardEventSource: source, virtualKey: keyCodeV, keyDown: true),
      let keyUp = CGEvent(keyboardEventSource: source, virtualKey: keyCodeV, keyDown: false)
    else {
      throw PasteInjectorError.eventInjectionFailed
    }

    keyDown.flags = .maskCommand
    keyUp.flags = .maskCommand

    if let targetPID,
      targetPID != currentPID,
      let targetApp = NSRunningApplication(processIdentifier: targetPID)
    {
      _ = targetApp.activate(options: [])
      Thread.sleep(forTimeInterval: 0.05)
    }

    keyDown.post(tap: .cghidEventTap)
    keyUp.post(tap: .cghidEventTap)
  }
}
