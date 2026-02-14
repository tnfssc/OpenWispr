import Foundation

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

enum CommandRunner {
  static func run(
    executable: String,
    arguments: [String],
    extraEnvironment: [String: String] = [:]
  ) async throws -> CommandOutput {
    try await withCheckedThrowingContinuation { continuation in
      let process = Process()
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
        let output = CommandOutput(stdout: stdout, stderr: stderr, exitCode: proc.terminationStatus)

        if proc.terminationStatus == 0 {
          continuation.resume(returning: output)
        } else {
          let message =
            "Command failed (\(proc.terminationStatus)): \(executable) \(arguments.joined(separator: " "))\n\(stderr.trimmingCharacters(in: .whitespacesAndNewlines))"
          continuation.resume(throwing: CommandRunnerError.nonZeroExit(message))
        }
      }

      do {
        try process.run()
      } catch {
        continuation.resume(
          throwing: CommandRunnerError.launchFailed(
            "Failed to launch \(executable): \(error.localizedDescription)"))
      }
    }
  }
}
