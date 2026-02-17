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
  struct ClipboardSnapshot {
    struct Item {
      struct Entry {
        let type: NSPasteboard.PasteboardType
        let data: Data
      }

      let entries: [Entry]
    }

    let items: [Item]
  }

  static func captureClipboard() -> ClipboardSnapshot {
    let pasteboard = NSPasteboard.general
    let items =
      (pasteboard.pasteboardItems ?? []).map { item in
        let entries: [ClipboardSnapshot.Item.Entry] =
          item.types.compactMap { type in
            guard let data = item.data(forType: type) else {
              return nil
            }
            return ClipboardSnapshot.Item.Entry(type: type, data: data)
          }
        return ClipboardSnapshot.Item(entries: entries)
      }

    return ClipboardSnapshot(items: items)
  }

  static func restoreClipboard(_ snapshot: ClipboardSnapshot) {
    let pasteboard = NSPasteboard.general
    pasteboard.clearContents()

    guard !snapshot.items.isEmpty else {
      return
    }

    let restoredItems: [NSPasteboardItem] =
      snapshot.items.map { snapshotItem in
        let item = NSPasteboardItem()
        snapshotItem.entries.forEach { entry in
          item.setData(entry.data, forType: entry.type)
        }
        return item
      }

    pasteboard.writeObjects(restoredItems)
  }

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
