import FerryKit
import LocalAuthentication
import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Group {
            if model.account == nil {
                OnboardingView()
            } else {
                TabView(selection: $model.tab) {
                    Tab("Inbox", systemImage: "tray", value: AppTab.inbox) { InboxView() }
                        .badge(model.threads.reduce(0) { $0 + ($1.archived ? 0 : $1.loudUnreadCount) })
                    Tab("Devices", systemImage: "iphone.gen3.radiowaves.left.and.right", value: AppTab.devices) { DevicesView() }
                    Tab("Settings", systemImage: "gearshape", value: AppTab.settings) { SettingsView() }
                }
            }
        }
        .tint(.ferryAccent)
        .overlay {
            if model.locked && model.account != nil { LockView() }
        }
        // Onboarding shows its errors inline; once set up, problems surface as an alert.
        .alert("Something went wrong", isPresented: Binding(get: { model.error != nil && model.account != nil }, set: { if !$0 { model.error = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.error ?? "")
        }
        .sheet(item: Binding(get: { model.pendingLink.map(LinkBox.init) }, set: { model.pendingLink = $0?.link })) { box in
            JoinConfirmView(link: box.link)
        }
    }
}

/// Covers the app until Face ID / passcode succeeds, when the app lock is on (§9.1).
private struct LockView: View {
    @Environment(AppModel.self) private var model
    @State private var failed = false

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "lock.fill").font(.system(size: 44)).foregroundStyle(.ferryAccent)
            Text("Ferry is locked").font(.title2.bold())
            Button("Unlock", systemImage: "faceid") { Task { await unlock() } }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            if failed { Text("Try again to see your messages.").font(.callout).foregroundStyle(.secondary) }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.background)
        .task { await unlock() }
    }

    private func unlock() async {
        let context = LAContext()
        let ok = (try? await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Unlock your messages")) ?? false
        if ok { model.locked = false } else { failed = true }
    }
}

/// Hides the inbox in the app switcher.
struct PrivacyCover: View {
    var body: some View {
        Image(systemName: "lock.fill")
            .font(.system(size: 44))
            .foregroundStyle(.ferryAccent)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(.background)
    }
}

private struct LinkBox: Identifiable {
    let link: ScannedCode
    var id: String { link.code }
}

/// Shown when a pairing link is opened from outside the app.
private struct JoinConfirmView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let link: ScannedCode
    @State private var busy = false

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "link.badge.plus").font(.system(size: 48)).foregroundStyle(.ferryAccent)
            Text("Pair with this account?").font(.title2.bold())
            Text(URL(string: link.server)?.host() ?? link.server).font(.callout.monospaced()).foregroundStyle(.secondary)
            if model.account != nil {
                Label("This iPhone is already paired. Joining replaces its current account and deletes its inbox.", systemImage: "exclamationmark.triangle")
                    .font(.callout).foregroundStyle(.orange)
            }
            Spacer()
            Button {
                busy = true
                Task {
                    if model.account != nil { await model.leave() }
                    await model.join(link)
                    busy = false
                    dismiss()
                }
            } label: {
                Group { if busy { ProgressView() } else { Text("Pair") } }.frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent).controlSize(.large).disabled(busy)
            Button("Cancel", role: .cancel) { dismiss() }
        }
        .padding(24)
        .presentationDetents([.medium])
    }
}

extension ShapeStyle where Self == Color {
    static var ferryAccent: Color { Color("AccentColor") }
}
