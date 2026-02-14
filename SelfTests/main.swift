import Carbon
import Foundation
import OpenWisprCore

struct SelfTestRunner {
  private(set) var failures = 0

  mutating func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
    if condition() {
      print("[pass] \(message)")
    } else {
      failures += 1
      print("[fail] \(message)")
    }
  }

  mutating func run() {
    let parsedAngle = ShortcutParser.parse("<Control><Option>R")
    expect(parsedAngle != nil, "Shortcut parser handles angle-bracket style")
    expect(parsedAngle?.keyCode == UInt32(kVK_ANSI_R), "Shortcut parser maps key code")
    expect(
      parsedAngle?.modifiers == (UInt32(controlKey) | UInt32(optionKey)),
      "Shortcut parser maps modifier flags"
    )

    expect(
      ShortcutParser.parse("<Control><Option>") == nil, "Shortcut parser rejects missing key token")

    if let sttData = "{\"text\":\"hello world\"}".data(using: .utf8) {
      expect(
        TranscriptParser.parseSTTResponse(data: sttData) == "hello world",
        "STT parser extracts text")
    } else {
      failures += 1
      print("[fail] STT parser test data could not be created")
    }

    if let llmData = "{\"choices\":[{\"message\":{\"content\":\" cleaned text \"}}]}".data(
      using: .utf8)
    {
      expect(
        TranscriptParser.parseLLMResponse(data: llmData) == "cleaned text",
        "LLM parser extracts cleaned content")
    } else {
      failures += 1
      print("[fail] LLM parser test data could not be created")
    }

    expect(
      TranscriptParser.normalize("\"Hello\"") == "Hello",
      "Transcript normalizer strips wrapping quotes")
    expect(
      TranscriptParser.normalize("```markdown\nHello\n```") == "Hello",
      "Transcript normalizer strips code fences"
    )

    let expanded = PathResolver.expand("~/openwispr/model.bin")
    expect(expanded.hasPrefix(NSHomeDirectory()), "Path resolver expands tilde prefix")

    let unknownBinary = "openwispr-test-\(UUID().uuidString)"
    expect(
      PathResolver.resolveExecutable(unknownBinary) == unknownBinary,
      "Path resolver preserves unknown executable names"
    )

    if failures == 0 {
      print("\nAll self-tests passed.")
    } else {
      print("\nSelf-tests failed: \(failures)")
      exit(1)
    }
  }
}

var runner = SelfTestRunner()
runner.run()
