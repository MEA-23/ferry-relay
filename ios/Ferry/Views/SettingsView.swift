import FerryKit
import LocalAuthentication
import SwiftUI
import UserNotifications

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @State private var notifications: UNAuthorizationStatus = .notDetermined
    @State private var confirmLeave = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    switch notifications {
                    case .authorized, .provisional, .ephemeral:
                        Label("Notifications on", systemImage: "bell.badge.fill").foregroundStyle(.green)
                    case .denied:
                        Button("Turn on notifications in Settings", systemImage: "bell.slash") {
                            openURL(URL(string: UIApplication.openNotificationSettingsURLString)!)
                        }
                    default:
                        Button("Allow notifications", systemImage: "bell") { model.requestNotifications() }
                    }
                } footer: {
                    Text("Notifications are decrypted on this iPhone. Apple and the server only ever see “New message”.")
                }

                Section {
                    @Bindable var model = model
                    Toggle(isOn: $model.appLock) { Label("Require \(Self.biometryName) to open", systemImage: "lock") }
                } header: {
                    Text("Privacy")
                } footer: {
                    Text("Messages on this iPhone are stored encrypted and are never included in iCloud or computer backups. Notification previews follow your iPhone's Notifications settings.")
                }

                Section("App icon") { IconPicker() }

                if let account = model.account {
                    Section("Account") {
                        // The hosted server by the name it had at setup; a self-hosted one by its address.
                        LabeledContent("Server", value: account.server.trimmingCharacters(in: ["/"]) == Ferry.defaultServer
                                       ? "Ferry Relay" : URL(string: account.server)?.host() ?? account.server)
                        LabeledContent("Account", value: String(account.userId.suffix(12)))
                        LabeledContent("Key ID") { Text(account.currentKeyId).font(.caption.monospaced()) }
                    }
                }

                Section {
                    Button("Remove this iPhone from the account", role: .destructive) { confirmLeave = true }
                } footer: {
                    Text("Ferry \(Ferry.appVersion) · end-to-end encrypted")
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .task { notifications = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus }
            .confirmationDialog("Remove this iPhone?", isPresented: $confirmLeave, titleVisibility: .visible) {
                Button("Remove and delete inbox", role: .destructive) { Task { await model.leave() } }
            } message: {
                Text("Its key and every decrypted message are deleted from this iPhone. You can pair again later.")
            }
        }
    }
}

extension SettingsView {
    static var biometryName: String {
        let context = LAContext()
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        return switch context.biometryType {
        case .faceID: "Face ID"
        case .touchID: "Touch ID"
        case .opticID: "Optic ID"
        default: "passcode"
        }
    }
}

/// Switches between the bundled icons. Each also has dark and tinted variants iOS picks automatically.
private struct IconPicker: View {
    private static let options: [(name: String?, label: String, preview: String)] = [
        (nil, "Teal", "IconPreview"),
        ("AppIcon-Ocean", "Ocean", "IconPreview-Ocean"),
        ("AppIcon-Paper", "Paper", "IconPreview-Paper"),
    ]

    @State private var current = UIApplication.shared.alternateIconName

    var body: some View {
        HStack(spacing: 20) {
            ForEach(Self.options, id: \.label) { option in
                Button {
                    Task {
                        try? await UIApplication.shared.setAlternateIconName(option.name)
                        current = UIApplication.shared.alternateIconName
                    }
                } label: {
                    VStack(spacing: 6) {
                        Image(option.preview)
                            .resizable()
                            .frame(width: 60, height: 60)
                            .clipShape(RoundedRectangle(cornerRadius: 13.4, style: .continuous))
                            .overlay {
                                RoundedRectangle(cornerRadius: 13.4, style: .continuous)
                                    .strokeBorder(current == option.name ? Color.ferryAccent : .secondary.opacity(0.25), lineWidth: current == option.name ? 3 : 1)
                            }
                        Text(option.label).font(.caption).foregroundStyle(current == option.name ? .primary : .secondary)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(current == option.name ? .isSelected : [])
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 6)
    }
}
