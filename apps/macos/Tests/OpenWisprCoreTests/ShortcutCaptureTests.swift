import OpenWisprCore
import Testing

@Test func capturedShortcutRoundTripsToExistingParser() {
  let captured = ShortcutParser.capturedShortcut(
    keyCode: 15, control: true, option: true, shift: false, command: false)
  #expect(captured == "<Control><Option>R")
  #expect(ShortcutParser.parse(captured!) == ShortcutParser.parse("Control+Option+R"))
}

@Test func captureRejectsBareKeysAndUnsupportedKeys() {
  #expect(
    ShortcutParser.capturedShortcut(
      keyCode: 15, control: false, option: false, shift: true, command: false) == nil)
  #expect(
    ShortcutParser.capturedShortcut(
      keyCode: 53, control: true, option: false, shift: false, command: false) == nil)
}

@Test func captureRightOptionIsHoldOnly() {
  #expect(
    ShortcutParser.capturedShortcut(
      keyCode: 61, control: false, option: true, shift: false, command: false,
      allowRightOption: true) == "RightOption")
  #expect(
    ShortcutParser.capturedShortcut(
      keyCode: 61, control: false, option: true, shift: false, command: false) == nil)
}

@Test func shortcutLabelsAreReadableWithoutChangingStoredValues() {
  #expect(ShortcutParser.displayLabel("<Control><Option>R") == "⌃⌥R")
  #expect(ShortcutParser.displayLabel("RightOption") == "Right Option")
  #expect(ShortcutParser.displayLabel("Command+Space") == "⌘Space")
  #expect(ShortcutParser.displayLabel("invalid") == "invalid")
}
