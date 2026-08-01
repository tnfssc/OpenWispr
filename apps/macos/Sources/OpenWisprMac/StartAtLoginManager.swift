import Foundation
import ServiceManagement

enum StartAtLoginError: LocalizedError {
  case unsupportedRuntime
  case unavailableStatus
  case operationFailed(String)

  var errorDescription: String? {
    switch self {
    case .unsupportedRuntime:
      "Start at login is only available from a bundled app."
    case .unavailableStatus:
      "Could not determine start-at-login status."
    case .operationFailed(let message):
      message
    }
  }
}

@MainActor
final class StartAtLoginManager {
  var isSupported: Bool {
    Bundle.main.bundleURL.pathExtension == "app" && Bundle.main.bundleIdentifier != nil
  }

  func currentEnabled() throws -> Bool {
    guard isSupported else {
      throw StartAtLoginError.unsupportedRuntime
    }

    switch SMAppService.mainApp.status {
    case .enabled:
      return true
    case .notRegistered, .notFound:
      return false
    case .requiresApproval:
      return false
    @unknown default:
      throw StartAtLoginError.unavailableStatus
    }
  }

  func setEnabled(_ enabled: Bool) throws {
    guard isSupported else {
      throw StartAtLoginError.unsupportedRuntime
    }

    let service = SMAppService.mainApp
    let status = service.status

    if enabled {
      if status == .enabled {
        return
      }

      do {
        try service.register()
      } catch {
        throw StartAtLoginError.operationFailed(
          "Could not enable start at login: \(error.localizedDescription)"
        )
      }
      return
    }

    if status == .notRegistered || status == .notFound {
      return
    }

    do {
      try service.unregister()
    } catch {
      throw StartAtLoginError.operationFailed(
        "Could not disable start at login: \(error.localizedDescription)"
      )
    }
  }
}
