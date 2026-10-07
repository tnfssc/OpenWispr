import AppKit
import Foundation
import Testing

@testable import OpenWispr

@MainActor
private final class TestRecorder: SetupAudioRecording {
  let url = FileManager.default.temporaryDirectory.appendingPathComponent(
    "setup-test-\(UUID().uuidString).wav")
  var permitted = true
  func requestMicrophoneAccess() async -> Bool { permitted }
  func startRecording() throws -> URL {
    try Data("test audio".utf8).write(to: url)
    return url
  }
  func stopRecording() throws -> URL { url }
}

@MainActor
private func testSettings() -> SettingsSnapshot {
  let name = "OpenWispr.setup-dictation-tests.\(UUID().uuidString)"
  let defaults = UserDefaults(suiteName: name)!
  defer { defaults.removePersistentDomain(forName: name) }
  return SettingsStore(defaults: defaults).snapshot()
}

@MainActor
private func waitForPhase(_ test: SetupDictation, _ phase: SetupDictation.Phase) async {
  for _ in 0..<500 {
    if test.phase == phase { return }
    try? await Task.sleep(for: .milliseconds(2))
  }
  #expect(test.phase == phase)
}

@Test @MainActor func setupTestShowsResultDeletesAudioAndDoesNotTouchClipboard() async {
  let recorder = TestRecorder()
  defer { try? FileManager.default.removeItem(at: recorder.url) }
  let clipboardChangeCount = NSPasteboard.general.changeCount
  let test = SetupDictation(
    recorder: recorder,
    transcribe: { url, _ in
      let bytes = try Data(contentsOf: url)
      #expect(bytes == Data("test audio".utf8))
      return "  A real pipeline result.  "
    })
  test.start()
  await waitForPhase(test, .recording)
  test.stop(settings: testSettings())
  await waitForPhase(test, .idle)
  #expect(test.result == "A real pipeline result.")
  #expect(!test.hasAudio)
  #expect(!FileManager.default.fileExists(atPath: recorder.url.path))
  #expect(NSPasteboard.general.changeCount == clipboardChangeCount)
}

@Test @MainActor func setupFailedTestKeepsOnlyItsAudioForRetryAndDiscard() async throws {
  let recorder = TestRecorder()
  defer { try? FileManager.default.removeItem(at: recorder.url) }
  let unrelated = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
  try Data("pending dictation".utf8).write(to: unrelated)
  defer { try? FileManager.default.removeItem(at: unrelated) }
  let test = SetupDictation(
    recorder: recorder, transcribe: { _, _ in throw URLError(.notConnectedToInternet) })
  test.start()
  await waitForPhase(test, .recording)
  test.stop(settings: testSettings())
  await waitForPhase(test, .idle)
  #expect(test.hasAudio)
  #expect(!test.error.isEmpty)
  test.process(settings: testSettings())
  await waitForPhase(test, .idle)
  #expect(test.hasAudio)
  test.discard()
  #expect(!test.hasAudio)
  #expect(!FileManager.default.fileExists(atPath: recorder.url.path))
  #expect(try Data(contentsOf: unrelated) == Data("pending dictation".utf8))
}

@Test @MainActor func setupLeavingRecordingStopsWithoutUploadAndReopensForRetry() async {
  let recorder = TestRecorder()
  let test = SetupDictation(
    recorder: recorder,
    transcribe: { _, _ in
      Issue.record("Leaving must not upload audio")
      return ""
    })
  test.start()
  await waitForPhase(test, .recording)
  test.leave()
  #expect(test.phase == .idle)
  #expect(test.hasAudio)
  #expect(FileManager.default.fileExists(atPath: recorder.url.path))
  test.terminate()
  #expect(!FileManager.default.fileExists(atPath: recorder.url.path))
}

@Test @MainActor func setupDeniedMicrophoneDoesNotRecord() async {
  let recorder = TestRecorder()
  recorder.permitted = false
  let test = SetupDictation(recorder: recorder)
  test.start()
  await waitForPhase(test, .idle)
  #expect(!test.hasAudio)
  #expect(!test.error.isEmpty)
  #expect(!FileManager.default.fileExists(atPath: recorder.url.path))
}

@Test @MainActor func setupCancellationCannotShowLateSuccess() async {
  let recorder = TestRecorder()
  defer { try? FileManager.default.removeItem(at: recorder.url) }
  let test = SetupDictation(
    recorder: recorder,
    transcribe: { _, _ in
      try? await Task.sleep(for: .seconds(60))
      return "Late result must not appear"
    })
  test.start()
  await waitForPhase(test, .recording)
  test.stop(settings: testSettings())
  test.cancel()
  await waitForPhase(test, .idle)
  #expect(test.result.isEmpty)
  #expect(test.hasAudio)
  test.discard()
}
