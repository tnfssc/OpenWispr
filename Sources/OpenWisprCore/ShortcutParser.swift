import Carbon
import Foundation

public struct KeyShortcut: Equatable {
  public let keyCode: UInt32
  public let modifiers: UInt32

  public init(keyCode: UInt32, modifiers: UInt32) {
    self.keyCode = keyCode
    self.modifiers = modifiers
  }
}

public enum ShortcutParser {
  public static func parse(_ rawShortcut: String) -> KeyShortcut? {
    let shortcut = rawShortcut.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !shortcut.isEmpty else {
      return nil
    }

    let modifierTokens: [String]
    let keyToken: String

    if shortcut.contains("<") {
      let regex = try? NSRegularExpression(pattern: "<([^>]+)>")
      let nsRange = NSRange(shortcut.startIndex..<shortcut.endIndex, in: shortcut)
      let matches = regex?.matches(in: shortcut, range: nsRange) ?? []

      modifierTokens = matches.compactMap { match in
        guard let range = Range(match.range(at: 1), in: shortcut) else { return nil }
        return String(shortcut[range]).lowercased()
      }

      keyToken =
        regex?.stringByReplacingMatches(in: shortcut, range: nsRange, withTemplate: "")
        .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    } else if shortcut.contains("+") {
      let parts = shortcut.split(separator: "+").map {
        String($0).trimmingCharacters(in: .whitespacesAndNewlines)
      }
      guard let last = parts.last else {
        return nil
      }

      modifierTokens = parts.dropLast().map { $0.lowercased() }
      keyToken = last
    } else {
      modifierTokens = []
      keyToken = shortcut
    }

    guard let keyCode = keyCodeForToken(keyToken) else {
      return nil
    }

    var modifiers: UInt32 = 0
    for token in modifierTokens {
      switch token {
      case "control", "ctrl", "primary":
        modifiers |= UInt32(controlKey)
      case "option", "alt", "mod1":
        modifiers |= UInt32(optionKey)
      case "shift":
        modifiers |= UInt32(shiftKey)
      case "command", "cmd", "super", "meta", "mod4":
        modifiers |= UInt32(cmdKey)
      default:
        break
      }
    }

    return KeyShortcut(keyCode: keyCode, modifiers: modifiers)
  }

  private static func keyCodeForToken(_ rawToken: String) -> UInt32? {
    let token = rawToken.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    guard !token.isEmpty else {
      return nil
    }

    let keyMap: [String: UInt32] = [
      "a": UInt32(kVK_ANSI_A), "b": UInt32(kVK_ANSI_B), "c": UInt32(kVK_ANSI_C),
      "d": UInt32(kVK_ANSI_D), "e": UInt32(kVK_ANSI_E), "f": UInt32(kVK_ANSI_F),
      "g": UInt32(kVK_ANSI_G), "h": UInt32(kVK_ANSI_H), "i": UInt32(kVK_ANSI_I),
      "j": UInt32(kVK_ANSI_J), "k": UInt32(kVK_ANSI_K), "l": UInt32(kVK_ANSI_L),
      "m": UInt32(kVK_ANSI_M), "n": UInt32(kVK_ANSI_N), "o": UInt32(kVK_ANSI_O),
      "p": UInt32(kVK_ANSI_P), "q": UInt32(kVK_ANSI_Q), "r": UInt32(kVK_ANSI_R),
      "s": UInt32(kVK_ANSI_S), "t": UInt32(kVK_ANSI_T), "u": UInt32(kVK_ANSI_U),
      "v": UInt32(kVK_ANSI_V), "w": UInt32(kVK_ANSI_W), "x": UInt32(kVK_ANSI_X),
      "y": UInt32(kVK_ANSI_Y), "z": UInt32(kVK_ANSI_Z),
      "0": UInt32(kVK_ANSI_0), "1": UInt32(kVK_ANSI_1), "2": UInt32(kVK_ANSI_2),
      "3": UInt32(kVK_ANSI_3), "4": UInt32(kVK_ANSI_4), "5": UInt32(kVK_ANSI_5),
      "6": UInt32(kVK_ANSI_6), "7": UInt32(kVK_ANSI_7), "8": UInt32(kVK_ANSI_8),
      "9": UInt32(kVK_ANSI_9),
      "space": UInt32(kVK_Space),
      "f1": UInt32(kVK_F1), "f2": UInt32(kVK_F2), "f3": UInt32(kVK_F3),
      "f4": UInt32(kVK_F4), "f5": UInt32(kVK_F5), "f6": UInt32(kVK_F6),
      "f7": UInt32(kVK_F7), "f8": UInt32(kVK_F8), "f9": UInt32(kVK_F9),
      "f10": UInt32(kVK_F10), "f11": UInt32(kVK_F11), "f12": UInt32(kVK_F12),
    ]

    return keyMap[token]
  }
}
