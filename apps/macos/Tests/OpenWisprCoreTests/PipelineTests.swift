import Foundation
import OpenWisprCore
import Testing

@testable import OpenWispr

private final class StubState: @unchecked Sendable {
  private let lock = NSLock()
  private var counts: [String: Int] = [:]

  func increment(_ key: String) -> Int {
    lock.lock()
    defer { lock.unlock() }
    counts[key, default: 0] += 1
    return counts[key]!
  }

  func count(_ key: String) -> Int {
    lock.lock()
    defer { lock.unlock() }
    return counts[key, default: 0]
  }
}

private final class StubProtocol: URLProtocol, @unchecked Sendable {
  static let state = StubState()

  override class func canInit(with request: URLRequest) -> Bool { true }
  override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

  override func startLoading() {
    let path = request.url!.path
    let attempt = Self.state.increment(path)
    if path == "/hang" || path == "/request-hang" { return }
    if path == "/recover" && attempt == 1 {
      client?.urlProtocol(self, didFailWithError: URLError(.networkConnectionLost))
      return
    }
    let status = path == "/unauthorized" ? 401 : 200
    let response = HTTPURLResponse(
      url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
    client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
    client?.urlProtocol(self, didLoad: Data("{\"text\":\"Recovered words\"}".utf8))
    client?.urlProtocolDidFinishLoading(self)
  }

  override func stopLoading() {
    _ = Self.state.increment("stopped" + request.url!.path)
  }
}

private func testSettings(endpoint: String) -> SettingsSnapshot {
  SettingsSnapshot(
    holdToSpeakEnabled: false,
    holdShortcut: "",
    toggleShortcut: "",
    autoPasteEnabled: false,
    restoreClipboardEnabled: false,
    notificationsEnabled: false,
    silenceTrimEnabled: false,
    silenceThreshold: "",
    silenceDuration: 0,
    whisperBinaryPath: "",
    ffmpegBinaryPath: "",
    localModelPath: "",
    sttProvider: .openai,
    sttOpenAIEndpoint: "https://test.invalid/\(endpoint)",
    sttOpenAIModel: "whisper-1",
    sttOpenAIApiKey: "test-key",
    sttGroqEndpoint: "",
    sttGroqModel: "",
    sttGroqApiKey: "",
    sttOpenRouterEndpoint: "",
    sttOpenRouterModel: "",
    sttOpenRouterApiKey: "",
    llmFilterEnabled: false,
    llmProvider: .openai,
    llmOpenAIEndpoint: "",
    llmOpenAIModel: "",
    llmOpenAIApiKey: "",
    llmGroqEndpoint: "",
    llmGroqModel: "",
    llmGroqApiKey: "",
    llmOpenRouterEndpoint: "",
    llmOpenRouterModel: "",
    llmOpenRouterApiKey: "",
    llmCleanupPrompt: ""
  )
}

private func testPipeline() -> TranscriptionPipeline {
  var pipeline = TranscriptionPipeline()
  pipeline.sessionConfiguration = {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [StubProtocol.self]
    return configuration
  }
  return pipeline
}

private func testAudio() throws -> URL {
  let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID()).wav")
  try Data("test audio".utf8).write(to: url)
  return url
}

@Test func pipelineRetriesOriginalAudio() async throws {
  let url = try testAudio()
  defer { try? FileManager.default.removeItem(at: url) }
  let settings = testSettings(endpoint: "recover")
  let result = try await testPipeline().run(inputURL: url, settings: settings)
  #expect(result == "Recovered words")
  #expect(StubProtocol.state.count("/recover") == 2)
  // The caller, not the pipeline, owns recording lifetime.
  #expect(FileManager.default.fileExists(atPath: url.path))
}

@Test func pipelinePreservesAudioOnAuthenticationFailure() async throws {
  let url = try testAudio()
  defer { try? FileManager.default.removeItem(at: url) }
  let settings = testSettings(endpoint: "unauthorized")
  do {
    _ = try await testPipeline().run(inputURL: url, settings: settings)
    Issue.record("Expected authentication failure")
  } catch {
    guard case PipelineError.remoteHTTP(_, let code, _) = error else {
      Issue.record("Unexpected error: \(error)")
      return
    }
    #expect(code == 401)
  }
  #expect(StubProtocol.state.count("/unauthorized") == 1)
  #expect(FileManager.default.fileExists(atPath: url.path))
}

@Test func deadlineCancelsNetworkRequestAndPreservesAudio() async throws {
  let url = try testAudio()
  defer { try? FileManager.default.removeItem(at: url) }
  let settings = testSettings(endpoint: "hang")
  let pipeline = testPipeline()
  do {
    _ = try await withTimeout(seconds: 0.1) {
      try await pipeline.run(inputURL: url, settings: settings)
    }
    Issue.record("Expected deadline")
  } catch {
    #expect(error is TranscriptionTimeout)
  }
  #expect(StubProtocol.state.count("/hang") == 1)
  #expect(StubProtocol.state.count("stopped/hang") == 1)
  #expect(FileManager.default.fileExists(atPath: url.path))
}

@Test func deadlineStopsLocalCommand() async {
  do {
    _ = try await withTimeout(seconds: 0.1) {
      try await CommandRunner.run(executable: "/bin/sleep", arguments: ["60"])
    }
    Issue.record("Expected deadline")
  } catch {
    #expect(error is TranscriptionTimeout)
  }
}

@Test func requestTimeoutRetriesAndCancelsEveryAttempt() async throws {
  let url = try testAudio()
  defer { try? FileManager.default.removeItem(at: url) }
  let settings = testSettings(endpoint: "request-hang")
  var pipeline = testPipeline()
  pipeline.requestTimeout = 0.05
  do {
    _ = try await pipeline.run(inputURL: url, settings: settings)
    Issue.record("Expected request timeout")
  } catch {
    #expect(error is TranscriptionTimeout || (error as? URLError)?.code == .timedOut)
  }
  #expect(StubProtocol.state.count("/request-hang") == 3)
  #expect(StubProtocol.state.count("stopped/request-hang") == 3)
  #expect(FileManager.default.fileExists(atPath: url.path))
}
