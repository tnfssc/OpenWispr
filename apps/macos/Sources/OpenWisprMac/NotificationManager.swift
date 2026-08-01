import Foundation
import UserNotifications

final class NotificationManager {
  private let center: UNUserNotificationCenter?

  init() {
    guard Self.canUseUserNotifications else {
      center = nil
      return
    }

    let center = UNUserNotificationCenter.current()
    self.center = center
    center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
  }

  func notify(title: String, body: String, enabled: Bool) {
    guard enabled, let center else {
      return
    }

    let content = UNMutableNotificationContent()
    content.title = title
    content.body = body

    let request = UNNotificationRequest(
      identifier: UUID().uuidString,
      content: content,
      trigger: nil
    )
    center.add(request)
  }

  private static var canUseUserNotifications: Bool {
    Bundle.main.bundleURL.pathExtension == "app" && Bundle.main.bundleIdentifier != nil
  }
}
