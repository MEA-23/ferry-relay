import FerryKit
import UserNotifications

/// Replaces the placeholder push text with the decrypted event (§7.3). On any failure the
/// placeholder stays; the app catches up on its next pull.
final class NotificationService: UNNotificationServiceExtension {
    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var content: UNMutableNotificationContent?
    private var task: Task<Void, Never>?

    override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        self.contentHandler = contentHandler
        let content = (request.content.mutableCopy() as? UNMutableNotificationContent) ?? UNMutableNotificationContent()
        self.content = content

        guard let account = Keychain.load(), let sr = request.content.userInfo["sr"] as? [String: Any], sr["v"] as? Int == 1, sr["u"] as? String == account.userId
        else { return contentHandler(content) }

        task = Task {
            do {
                let event = try await Self.storedEvent(from: sr, account: account)
                switch try Receiver(account: account).ingest(event) {
                case .new(let message, let summary):
                    content.title = summary.title
                    content.subtitle = summary.subtitle
                    content.body = summary.body
                    content.threadIdentifier = message.threadKey
                    content.userInfo["thread"] = message.threadKey
                    if message.type == "notification" { content.sound = nil }
                case .introduced(let deviceId, let summary):
                    // A node added a node (§4.7): say so, with a Remove button.
                    content.title = summary.title
                    content.body = summary.body
                    content.categoryIdentifier = "introduction"
                    content.userInfo["removeDevice"] = deviceId
                case .duplicate, .quiet, .filed, .profile:
                    break
                }
                if let unread = try? Inbox.shared.unreadCount() { content.badge = NSNumber(value: unread) }
            } catch {
                // Leave the placeholder.
            }
            self.finish()
        }
    }

    override func serviceExtensionTimeWillExpire() {
        task?.cancel()
        finish()
    }

    private func finish() {
        guard let handler = contentHandler, let content else { return }
        contentHandler = nil
        handler(content)
    }

    /// Inline mode carries the whole envelope; fetch mode only its id (§7.2).
    private static func storedEvent(from sr: [String: Any], account: Account) async throws -> StoredEvent {
        if let e = sr["e"] {
            return try JSONDecoder().decode(StoredEvent.self, from: JSONSerialization.data(withJSONObject: e))
        }
        guard let id = sr["id"] as? String else { throw URLError(.cannotParseResponse) }
        return try await account.api.event(id)
    }
}
