import AppKit
import Carbon
import Foundation
import OpenWisprCore

enum HotkeyError: LocalizedError {
  case registrationFailed(String)
  case invalidShortcut(String)

  var errorDescription: String? {
    switch self {
    case .registrationFailed(let value), .invalidShortcut(let value):
      value
    }
  }
}

private struct RegisteredHotkeyCallback {
  let onPress: () -> Void
  let onRelease: () -> Void
}

final class HotkeyManager {
  private var eventHandler: EventHandlerRef?
  private var toggleHotkeyRef: EventHotKeyRef?
  private var holdHotkeyRef: EventHotKeyRef?
  private var rightOptionGlobalMonitor: Any?
  private var rightOptionLocalMonitor: Any?
  private var rightOptionCallback: RegisteredHotkeyCallback?
  private var isRightOptionDown = false
  private var callbacks: [UInt32: RegisteredHotkeyCallback] = [:]
  private var nextHotkeyID: UInt32 = 1

  init() {
    installEventHandler()
  }

  deinit {
    unregisterAll()

    if let eventHandler {
      RemoveEventHandler(eventHandler)
    }
  }

  func register(
    toggleShortcut: String,
    holdShortcut: String,
    holdEnabled: Bool,
    onToggle: @escaping () -> Void,
    onHoldStart: @escaping () -> Void,
    onHoldStop: @escaping () -> Void
  ) throws {
    unregisterAll()

    toggleHotkeyRef = try registerHotkey(
      shortcut: toggleShortcut,
      callback: RegisteredHotkeyCallback(
        onPress: onToggle,
        onRelease: {}
      )
    )

    if holdEnabled {
      let callback = RegisteredHotkeyCallback(
        onPress: onHoldStart,
        onRelease: onHoldStop
      )

      if isRightOptionShortcut(holdShortcut) {
        try registerRightOptionHold(callback: callback)
      } else {
        holdHotkeyRef = try registerHotkey(
          shortcut: holdShortcut,
          callback: callback
        )
      }
    }
  }

  private func installEventHandler() {
    var eventSpecs = [
      EventTypeSpec(
        eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed)),
      EventTypeSpec(
        eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyReleased)),
    ]

    let handler: EventHandlerUPP = { _, event, userData in
      guard let userData, let event else {
        return noErr
      }

      let manager = Unmanaged<HotkeyManager>.fromOpaque(userData).takeUnretainedValue()
      return manager.handle(event: event)
    }

    _ = eventSpecs.withUnsafeMutableBufferPointer { buffer in
      InstallEventHandler(
        GetApplicationEventTarget(),
        handler,
        2,
        buffer.baseAddress,
        Unmanaged.passUnretained(self).toOpaque(),
        &eventHandler
      )
    }
  }

  private func handle(event: EventRef) -> OSStatus {
    var hotKeyID = EventHotKeyID()
    let status = GetEventParameter(
      event,
      EventParamName(kEventParamDirectObject),
      EventParamType(typeEventHotKeyID),
      nil,
      MemoryLayout<EventHotKeyID>.size,
      nil,
      &hotKeyID
    )

    guard status == noErr else {
      return status
    }

    guard let callback = callbacks[hotKeyID.id] else {
      return noErr
    }

    let eventKind = GetEventKind(event)
    if eventKind == UInt32(kEventHotKeyPressed) {
      callback.onPress()
    } else if eventKind == UInt32(kEventHotKeyReleased) {
      callback.onRelease()
    }

    return noErr
  }

  private func registerHotkey(shortcut: String, callback: RegisteredHotkeyCallback) throws
    -> EventHotKeyRef
  {
    guard let parsed = ShortcutParser.parse(shortcut) else {
      throw HotkeyError.invalidShortcut("Invalid shortcut format: \(shortcut)")
    }

    let hotkeyID = nextHotkeyID
    nextHotkeyID += 1

    var hotKeyRef: EventHotKeyRef?
    let eventHotKeyID = EventHotKeyID(signature: 0x4F57_5350, id: hotkeyID)

    let status = RegisterEventHotKey(
      parsed.keyCode,
      parsed.modifiers,
      eventHotKeyID,
      GetApplicationEventTarget(),
      0,
      &hotKeyRef
    )

    guard status == noErr, let hotKeyRef else {
      throw HotkeyError.registrationFailed("Could not register shortcut: \(shortcut)")
    }

    callbacks[hotkeyID] = callback
    return hotKeyRef
  }

  private func registerRightOptionHold(callback: RegisteredHotkeyCallback) throws {
    rightOptionCallback = callback
    isRightOptionDown = false

    rightOptionGlobalMonitor = NSEvent.addGlobalMonitorForEvents(matching: .flagsChanged) {
      [weak self] event in
      self?.handleRightOptionEvent(event)
    }

    rightOptionLocalMonitor = NSEvent.addLocalMonitorForEvents(matching: .flagsChanged) {
      [weak self] event in
      self?.handleRightOptionEvent(event)
      return event
    }

    if rightOptionGlobalMonitor == nil && rightOptionLocalMonitor == nil {
      throw HotkeyError.registrationFailed(
        "Could not register Right Option hold-to-speak monitor"
      )
    }
  }

  private func handleRightOptionEvent(_ event: NSEvent) {
    guard event.type == .flagsChanged else {
      return
    }

    guard event.keyCode == UInt16(kVK_RightOption) else {
      return
    }

    let rightOptionPressed = (event.modifierFlags.rawValue & UInt(NX_DEVICERALTKEYMASK)) != 0

    if rightOptionPressed, !isRightOptionDown {
      isRightOptionDown = true
      rightOptionCallback?.onPress()
    } else if !rightOptionPressed, isRightOptionDown {
      isRightOptionDown = false
      rightOptionCallback?.onRelease()
    }
  }

  private func isRightOptionShortcut(_ shortcut: String) -> Bool {
    let normalized =
      shortcut
      .lowercased()
      .replacingOccurrences(of: " ", with: "")
      .replacingOccurrences(of: "_", with: "")
      .replacingOccurrences(of: "-", with: "")

    return normalized == "rightoption" || normalized == "ralt"
  }

  private func unregisterAll() {
    if let toggleHotkeyRef {
      UnregisterEventHotKey(toggleHotkeyRef)
    }

    if let holdHotkeyRef {
      UnregisterEventHotKey(holdHotkeyRef)
    }

    if let rightOptionGlobalMonitor {
      NSEvent.removeMonitor(rightOptionGlobalMonitor)
    }

    if let rightOptionLocalMonitor {
      NSEvent.removeMonitor(rightOptionLocalMonitor)
    }

    toggleHotkeyRef = nil
    holdHotkeyRef = nil
    rightOptionGlobalMonitor = nil
    rightOptionLocalMonitor = nil
    rightOptionCallback = nil
    isRightOptionDown = false
    callbacks.removeAll()
  }
}
