import AVFoundation
import Foundation

enum AudioRecorderError: LocalizedError {
  case alreadyRecording
  case notRecording
  case couldNotStart

  var errorDescription: String? {
    switch self {
    case .alreadyRecording:
      "Recording is already in progress."
    case .notRecording:
      "No active recording."
    case .couldNotStart:
      "Could not start recording."
    }
  }
}

@MainActor
final class AudioRecorder {
  private var recorder: AVAudioRecorder?
  private var outputURL: URL?

  func requestMicrophoneAccess() async -> Bool {
    await withCheckedContinuation { continuation in
      AVCaptureDevice.requestAccess(for: .audio) { granted in
        continuation.resume(returning: granted)
      }
    }
  }

  func startRecording() throws -> URL {
    guard recorder == nil else {
      throw AudioRecorderError.alreadyRecording
    }

    let tempURL = FileManager.default.temporaryDirectory
      .appendingPathComponent("openwispr-\(UUID().uuidString).wav")

    let settings: [String: Any] = [
      AVFormatIDKey: kAudioFormatLinearPCM,
      AVSampleRateKey: 16_000,
      AVNumberOfChannelsKey: 1,
      AVLinearPCMBitDepthKey: 16,
      AVLinearPCMIsFloatKey: false,
      AVLinearPCMIsBigEndianKey: false,
    ]

    let recorder = try AVAudioRecorder(url: tempURL, settings: settings)
    recorder.isMeteringEnabled = false
    recorder.prepareToRecord()

    guard recorder.record() else {
      throw AudioRecorderError.couldNotStart
    }

    self.recorder = recorder
    outputURL = tempURL
    return tempURL
  }

  func stopRecording() throws -> URL {
    guard let recorder, let outputURL else {
      throw AudioRecorderError.notRecording
    }

    recorder.stop()
    self.recorder = nil
    self.outputURL = nil
    return outputURL
  }
}
