import Foundation
import OpenWisprCore
import Testing

private actor Attempts {
  var count = 0
  var retries: [Int] = []
  func next() -> Int {
    count += 1
    return count
  }
  func retried(_ attempt: Int) { retries.append(attempt) }
}

@Test func transientFailureRecoversWithoutRerecording() async throws {
  let attempts = Attempts()
  let text = try await RetryPolicy.run(
    delays: [0, 0], onRetry: { await attempts.retried($0) },
    operation: {
      if await attempts.next() < 3 { throw URLError(.networkConnectionLost) }
      return "Recovered transcript"
    }
  )
  #expect(text == "Recovered transcript")
  #expect(await attempts.count == 3)
  #expect(await attempts.retries == [2, 3])
}

@Test func retriesStopAfterThreeAttempts() async {
  let attempts = Attempts()
  do {
    let _: String = try await RetryPolicy.run(delays: [0, 0]) {
      _ = await attempts.next()
      throw URLError(.notConnectedToInternet)
    }
    Issue.record("Expected failed transcription")
  } catch {
    #expect((error as? URLError)?.code == .notConnectedToInternet)
  }
  #expect(await attempts.count == 3)
}

@Test func permanentFailuresDoNotRetry() async {
  let attempts = Attempts()
  do {
    let _: String = try await RetryPolicy.run(delays: [0, 0]) {
      _ = await attempts.next()
      throw URLError(.badURL)
    }
    Issue.record("Expected invalid URL failure")
  } catch {
    #expect((error as? URLError)?.code == .badURL)
  }
  #expect(await attempts.count == 1)
  #expect(!RetryPolicy.isTransient(statusCode: 401))
  #expect(!RetryPolicy.isTransient(statusCode: 400))
  #expect(RetryPolicy.isTransient(statusCode: 429))
  #expect(RetryPolicy.isTransient(statusCode: 503))
}

@Test func deadlineCancelsOperation() async {
  let cancellations = Attempts()
  do {
    let _: String = try await withTimeout(seconds: 0.02) {
      do {
        try await Task.sleep(for: .seconds(60))
        return "Late transcript"
      } catch {
        _ = await cancellations.next()
        throw error
      }
    }
    Issue.record("Expected timeout")
  } catch {
    #expect(error is TranscriptionTimeout)
  }
  #expect(await cancellations.count == 1)
}

@Test func cancellationDuringBackoffDoesNotRetry() async {
  let attempts = Attempts()
  let task = Task {
    try await RetryPolicy.run(delays: [60, 60]) {
      _ = await attempts.next()
      throw URLError(.networkConnectionLost)
    }
  }
  while await attempts.count == 0 { await Task.yield() }
  task.cancel()
  do {
    try await task.value
    Issue.record("Expected cancellation")
  } catch {
    #expect(error is CancellationError)
  }
  #expect(await attempts.count == 1)
}

@Test func canceledOperationCannotReturnLateSuccess() async {
  let started = Attempts()
  let task = Task {
    try await RetryPolicy.run {
      _ = await started.next()
      try? await Task.sleep(for: .seconds(60))
      return "Late transcript"
    }
  }
  while await started.count == 0 { await Task.yield() }
  task.cancel()
  do {
    _ = try await task.value
    Issue.record("Canceled transcript must not be delivered")
  } catch {
    #expect(error is CancellationError)
  }
}

@Test func recordingsSurviveRestartAndAreRemovedExplicitly() throws {
  let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
  try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
  defer { try? FileManager.default.removeItem(at: root) }
  let source = root.appendingPathComponent("input.wav")
  let bytes = Data("recorded audio".utf8)
  try bytes.write(to: source)
  let directory = root.appendingPathComponent("pending")
  let saved = try RecordingStore(directory: directory).save(source)
  #expect(!FileManager.default.fileExists(atPath: source.path))

  let reopened = RecordingStore(directory: directory)
  #expect(try reopened.recordings() == [saved])
  #expect(try Data(contentsOf: saved) == bytes)
  // A second dictation must not replace the failed first one.
  try bytes.write(to: source)
  let second = try reopened.save(source)
  #expect(try reopened.recordings().count == 2)
  try reopened.remove(saved)
  #expect(try reopened.recordings() == [second])
  try reopened.remove(second)
  #expect(try reopened.recordings().isEmpty)
}

@Test func storageFailurePreservesOriginalAudio() throws {
  let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
  try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
  defer { try? FileManager.default.removeItem(at: root) }
  let source = root.appendingPathComponent("input.wav")
  let bytes = Data("recorded audio".utf8)
  try bytes.write(to: source)
  // A regular file cannot be used as a recordings directory.
  #expect(throws: (any Error).self) {
    try RecordingStore(directory: source).save(source)
  }
  #expect(try Data(contentsOf: source) == bytes)
}
