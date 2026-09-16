import Foundation

#if canImport(FoundationNetworking)
  import FoundationNetworking
#endif

public enum TranscriptionTimeout: Error {
  case exceeded
}

/// The operation must cooperate with cancellation (URLSession does).
public func withTimeout<Value: Sendable>(
  seconds: Double,
  operation: @escaping @Sendable () async throws -> Value
) async throws -> Value {
  try await withThrowingTaskGroup(of: Value.self) { group in
    group.addTask { try await operation() }
    group.addTask {
      try await Task.sleep(for: .seconds(seconds))
      throw TranscriptionTimeout.exceeded
    }
    defer { group.cancelAll() }
    let value = try await group.next()!
    try Task.checkCancellation()
    return value
  }
}

public enum RetryPolicy {
  public static func isTransient(_ error: Error) -> Bool {
    if error is TranscriptionTimeout { return true }
    guard let error = error as? URLError else { return false }
    return [
      .timedOut, .networkConnectionLost, .notConnectedToInternet,
      .cannotConnectToHost, .cannotFindHost, .dnsLookupFailed,
    ].contains(error.code)
  }

  public static func isTransient(statusCode: Int) -> Bool {
    [408, 429, 500, 502, 503, 504].contains(statusCode)
  }

  public static func run<Value: Sendable>(
    delays: [Double] = [1, 3],
    shouldRetry: @Sendable (Error) -> Bool = isTransient,
    onRetry: @Sendable (Int) async -> Void = { _ in },
    operation: @Sendable () async throws -> Value
  ) async throws -> Value {
    var attempt = 0
    while true {
      try Task.checkCancellation()
      do {
        let value = try await operation()
        try Task.checkCancellation()
        return value
      } catch {
        try Task.checkCancellation()
        guard attempt < delays.count, shouldRetry(error) else { throw error }
        await onRetry(attempt + 2)
        try await Task.sleep(for: .seconds(delays[attempt]))
        attempt += 1
      }
    }
  }
}
