import Foundation

public struct RecordingStore: Sendable {
  public let directory: URL

  public init(directory: URL) {
    self.directory = directory
  }

  public func recordings() throws -> [URL] {
    guard FileManager.default.fileExists(atPath: directory.path) else { return [] }
    return try FileManager.default.contentsOfDirectory(
      at: directory, includingPropertiesForKeys: nil
    ).filter { $0.pathExtension == "wav" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
  }

  public func save(_ source: URL) throws -> URL {
    try FileManager.default.createDirectory(
      at: directory, withIntermediateDirectories: true,
      attributes: [.posixPermissions: 0o700])
    let destination = directory.appendingPathComponent(
      "\(Date().timeIntervalSince1970)-\(UUID().uuidString).wav")
    // Atomic writing leaves either a complete recoverable recording or no recording.
    try Data(contentsOf: source).write(to: destination, options: .atomic)
    try? FileManager.default.removeItem(at: source)
    return destination
  }

  public func remove(_ recording: URL) throws {
    try FileManager.default.removeItem(at: recording)
  }
}
