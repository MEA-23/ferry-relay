import FerryKit
import SwiftUI

struct OnboardingView: View {
    private enum Step: Hashable { case join, create }

    @Environment(AppModel.self) private var model
    @State private var path: [Step] = []

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(spacing: 28) {
                    if model.wasRemoved {
                        ErrorCard(message: "This iPhone was removed from your Ferry account, so its messages and keys were deleted from it. Pair again to keep receiving.") {
                            model.wasRemoved = false
                        }
                    }
                    Image("Hero")
                        .resizable()
                        .scaledToFit()
                        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                        .frame(maxHeight: 230)
                        .accessibilityHidden(true)
                    VStack(spacing: 10) {
                        Text("Ferry").font(.largeTitle.bold())
                        Text("Your phones' texts, calls and notifications, carried to one inbox. Encrypted on your phones, readable only by your devices.")
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)
                    }
                    VStack(spacing: 12) {
                        PrimaryButton(title: "Pair with my Android phone", systemImage: "qrcode.viewfinder") { open(.join) }
                        Button { open(.create) } label: {
                            Text("Start a new account here").font(.headline).frame(maxWidth: .infinity, minHeight: 30)
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.large)
                    }
                    .padding(.top, 4)
                }
                .padding(24)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity)
            }
            .scrollBounceBehavior(.basedOnSize)
            .navigationDestination(for: Step.self) { step in
                switch step {
                case .join: JoinView()
                case .create: CreateAccountView()
                }
            }
        }
    }

    private func open(_ step: Step) {
        model.error = nil
        path.append(step)
    }
}

// ── Join ─────────────────────────────────────────────────────────────────────

private struct JoinView: View {
    @Environment(AppModel.self) private var model
    @State private var link = ""
    @State private var linkError: String?
    @State private var scanning = false
    @State private var manual = false
    @State private var busy = false
    @FocusState private var linkFocused: Bool

    var body: some View {
        FormPage(title: "Pair this iPhone", subtitle: "On your Android phone, open Ferry → Devices → Pair a device. It shows a code to scan.") {
            if let error = model.error {
                ErrorCard(message: error) { model.error = nil }
            }

            PrimaryButton(title: "Scan pairing code", systemImage: "qrcode.viewfinder", busy: busy && !manual) { scanning = true }

            OrDivider()

            PasteButton(payloadType: String.self) { strings in
                guard let text = strings.first else { return }
                Task { @MainActor in submit(text) }
            }
            .buttonBorderShape(.capsule)
            .labelStyle(.titleAndIcon)
            .frame(maxWidth: .infinity)
            .disabled(busy)

            Text("Copied the pairing link on your other phone? Paste it here.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
                .multilineTextAlignment(.center)

            DisclosureGroup("Type or edit the link", isExpanded: $manual) {
                VStack(spacing: 14) {
                    FerryField("Pairing link", icon: "link", text: $link, prompt: "ferry://pair?…", error: linkError, axis: .vertical, monospaced: true, focus: $linkFocused)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .onChange(of: link) { linkError = nil }
                    PrimaryButton(title: "Pair", busy: busy, enabled: !link.isEmpty) { submit(link) }
                }
                .padding(.top, 12)
            }
            .font(.subheadline.weight(.medium))
            .tint(.ferryAccent)
        }
        .sheet(isPresented: $scanning) {
            ScannerSheet { text in
                scanning = false
                submit(text)
            }
        }
    }

    private func submit(_ text: String) {
        model.error = nil
        guard let parsed = PairingLink.parse(text) else {
            link = text
            manual = true
            linkError = "That isn't a Ferry pairing link. It starts with ferry://pair"
            return
        }
        linkFocused = false
        busy = true
        Task {
            await model.join(parsed)
            busy = false
        }
    }
}

// ── Create account ───────────────────────────────────────────────────────────

private struct CreateAccountView: View {
    @Environment(AppModel.self) private var model
    @State private var ownServer = false
    @State private var server = ""
    @State private var invite = ""
    @State private var busy = false
    @FocusState private var serverFocused: Bool
    @FocusState private var inviteFocused: Bool

    private var serverURL: URL? { ownServer ? Ferry.normalizeServer(server) : URL(string: Ferry.defaultServer) }

    private var serverError: String? {
        guard ownServer, !server.isEmpty, !serverFocused, serverURL == nil else { return nil }
        return server.lowercased().hasPrefix("http://")
            ? "Use https:// — plain http:// only works for servers on your local network."
            : "Enter an address like ferry.example.com"
    }

    var body: some View {
        FormPage(title: "New account", subtitle: "Then pair your Android phones from the Devices tab.") {
            if let error = model.error {
                ErrorCard(message: error) { model.error = nil }
            }

            VStack(alignment: .leading, spacing: 10) {
                Text("Server").font(.subheadline.weight(.medium)).foregroundStyle(.secondary)
                ChoiceCard(title: "Ferry server", subtitle: "Recommended. Run by Ferry; it only ever sees encrypted data.", icon: "checkmark.shield", selected: !ownServer) {
                    withAnimation(.snappy) { ownServer = false }
                }
                ChoiceCard(title: "My own server", subtitle: "A Ferry server you host yourself.", icon: "server.rack", selected: ownServer) {
                    withAnimation(.snappy) { ownServer = true }
                    serverFocused = server.isEmpty
                }
            }

            if ownServer {
                FerryField("Server address", icon: "globe", text: $server, prompt: "ferry.example.com", error: serverError,
                           hint: "https:// is added for you.", focus: $serverFocused)
                    .keyboardType(.URL)
                    .textContentType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.next)
                    .onSubmit { inviteFocused = true }
                    .transition(.move(edge: .top).combined(with: .opacity))
            }

            FerryField("Invite code", icon: "key", text: $invite, prompt: "Optional", hint: "Only needed if the server asks for one.", monospaced: true, focus: $inviteFocused)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.go)
                .onSubmit(create)

            PrimaryButton(title: "Create account", busy: busy, enabled: serverURL != nil, action: create)
                .padding(.top, 4)

            Label("A new encryption key is made on this iPhone. It never leaves your devices.", systemImage: "lock.fill")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    private func create() {
        guard let url = serverURL, !busy else { return }
        serverFocused = false
        inviteFocused = false
        model.error = nil
        busy = true
        Task {
            await model.createAccount(server: url.absoluteString, invite: invite.trimmingCharacters(in: .whitespaces))
            busy = false
        }
    }
}

/// Scrollable page with a large title and a stack of form content.
private struct FormPage<Content: View>: View {
    let title: String
    let subtitle: String
    @ViewBuilder let content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text(subtitle).foregroundStyle(.secondary).padding(.bottom, 4)
                content
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 12)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
            .animation(.snappy, value: title)
        }
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.large)
    }
}
