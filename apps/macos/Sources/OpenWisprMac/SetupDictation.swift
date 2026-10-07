import Combine
import Foundation
import OpenWisprCore

@MainActor
protocol SetupAudioRecording {
  func requestMicrophoneAccess() async -> Bool
  func startRecording() throws -> URL
  func stopRecording() throws -> URL
}

extension AudioRecorder: SetupAudioRecording {}

@MainActor
final class SetupDictation: ObservableObject {
  enum Phase: Equatable { case idle, requesting, recording, processing }
  @Published private(set) var phase: Phase = .idle
  @Published private(set) var result = ""
  @Published private(set) var error = ""
  @Published private(set) var hasAudio = false
  @Published private(set) var processingLabel = "Processing"
  typealias Transcribe = @Sendable (URL, SettingsSnapshot) async throws -> String
  private let recorder: any SetupAudioRecording
  private let transcribe: Transcribe?
  private var audioURL: URL?
  private var task: Task<Void, Never>?

  init(recorder: any SetupAudioRecording = AudioRecorder(), transcribe: Transcribe? = nil) {
    self.recorder = recorder
    self.transcribe = transcribe
  }

  var isBusy: Bool { phase != .idle }

  func start() {
    guard !isBusy, !hasAudio else { return }
    phase = .requesting
    result = ""
    error = ""
    task = Task {
      let granted = await recorder.requestMicrophoneAccess()
      guard !Task.isCancelled else {
        phase = .idle
        return
      }
      guard granted else {
        error = "Allow microphone access in System Settings, then try again."
        phase = .idle
        return
      }
      do {
        audioURL = try recorder.startRecording()
        hasAudio = true
        phase = .recording
      } catch {
        self.error = error.localizedDescription
        phase = .idle
      }
    }
  }

  func stop(settings: SettingsSnapshot) {
    guard phase == .recording else { return }
    do {
      audioURL = try recorder.stopRecording()
      phase = .idle
      process(settings: settings)
    } catch {
      self.error = error.localizedDescription
      phase = .idle
    }
  }

  func process(settings: SettingsSnapshot) {
    guard !isBusy, let url = audioURL else { return }
    phase = .processing
    processingLabel = "Processing"
    error = ""
    task = Task {
      defer { phase = .idle }
      do {
        let pipeline = TranscriptionPipeline(onRetry: { [weak self] attempt in
          await self?.showRetry(attempt)
        })
        let transcribe = self.transcribe
        let text = try await withTimeout(seconds: 90) {
          if let transcribe { return try await transcribe(url, settings) }
          return try await pipeline.run(inputURL: url, settings: settings)
        }
        try Task.checkCancellation()
        // Deliberately no PasteInjector, target PID, notification, or clipboard write.
        result = text.trimmingCharacters(in: .whitespacesAndNewlines)
        try FileManager.default.removeItem(at: url)
        audioURL = nil
        hasAudio = false
        if result.isEmpty { error = "No speech detected. Try a short sentence." }
      } catch {
        self.error =
          Task.isCancelled
          ? "Test canceled. Retry or discard the audio."
          : "\(error.localizedDescription). Test audio is kept on this Mac for Retry or Discard."
      }
    }
  }

  private func showRetry(_ attempt: Int) {
    processingLabel = "Retrying… (attempt \(attempt) of 3)"
  }

  func cancel() { task?.cancel() }

  func discard() {
    guard !isBusy else { return }
    if let audioURL {
      do { try FileManager.default.removeItem(at: audioURL) } catch {
        self.error = "Could not discard test audio: \(error.localizedDescription)"
        return
      }
    }
    audioURL = nil
    hasAudio = false
    error = ""
  }

  // Leaving never starts an upload. A stopped test can be retried on reopening.
  func leave() {
    if phase == .requesting { task?.cancel() }
    if phase == .recording {
      do { audioURL = try recorder.stopRecording() } catch {
        self.error = error.localizedDescription
      }
      phase = .idle
      error = "Test stopped. Retry to process it, or discard it."
    }
  }

  func terminate() {
    leave()
    task?.cancel()
    if let audioURL { try? FileManager.default.removeItem(at: audioURL) }
  }
}
