import Foundation

#if canImport(Darwin)
  import Darwin
#else
  import Glibc
#endif

struct CommandOutput {
  let stdout: String
  let stderr: String
  let exitCode: Int32
}

enum CommandRunnerError: LocalizedError {
  case launchFailed(String)
  case nonZeroExit(String)

  var errorDescription: String? {
    switch self {
    case .launchFailed(let message), .nonZeroExit(let message):
      message
    }
  }
}

// Serializes launch and cancellation so a canceled task cannot launch a late process.
private final class RunningCommand: @unchecked Sendable {
  private let lock = NSLock()
  private let process = Process()
  private var cancelled = false

  func launch(_ configure: (Process) -> Void) throws {
    lock.lock()
    defer { lock.unlock() }
    guard !cancelled else { throw CancellationError() }
    configure(process)
    try process.run()
  }

  func cancel() {
    lock.lock()
    defer { lock.unlock() }
    cancelled = true
    if process.isRunning {
      // These are disposable audio-processing children; cancellation must be bounded.
      kill(process.processIdentifier, SIGKILL)
    }
  }
}

enum CommandRunner {
  static func run(
    executable: String,
    arguments: [String],
    extraEnvironment: [String: String] = [:]
  ) async throws -> CommandOutput {
    let running = RunningCommand()
    return try await withTaskCancellationHandler {
      try Task.checkCancellation()
      return try await withCheckedThrowingContinuation { continuation in
        do {
          try running.launch { process in
            let stdoutPipe = Pipe()
            let stderrPipe = Pipe()

            process.executableURL = URL(fileURLWithPath: executable)
            process.arguments = arguments
            process.standardOutput = stdoutPipe
            process.standardError = stderrPipe

            var environment = ProcessInfo.processInfo.environment
            environment["PATH"] = [
              "/opt/homebrew/bin",
              "/usr/local/bin",
              environment["PATH"] ?? "",
            ].joined(separator: ":")

            for (key, value) in extraEnvironment {
              environment[key] = value
            }
            process.environment = environment

            process.terminationHandler = { proc in
              let stdoutData = stdoutPipe.fileHandleForReading.readDataToEndOfFile()
              let stderrData = stderrPipe.fileHandleForReading.readDataToEndOfFile()
              let stdout = String(data: stdoutData, encoding: .utf8) ?? ""
              let stderr = String(data: stderrData, encoding: .utf8) ?? ""
              let output = CommandOutput(
                stdout: stdout, stderr: stderr, exitCode: proc.terminationStatus)

              if proc.terminationStatus == 0 {
                continuation.resume(returning: output)
              } else {
                let message =
                  "Command failed (\(proc.terminationStatus)): \(executable) \(arguments.joined(separator: " "))\n\(stderr.trimmingCharacters(in: .whitespacesAndNewlines))"
                continuation.resume(throwing: CommandRunnerError.nonZeroExit(message))
              }
            }

          }
        } catch {
          continuation.resume(
            throwing: CommandRunnerError.launchFailed(
              "Failed to launch \(executable): \(error.localizedDescription)"))
        }
      }
    } onCancel: {
      running.cancel()
    }
  }
}
