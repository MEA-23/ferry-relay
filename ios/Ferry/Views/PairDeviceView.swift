import CoreImage.CIFilterBuiltins
import FerryKit
import LocalAuthentication
import SwiftUI

/// Shows a pairing QR: §5.3 for another phone that reads messages (it contains the account key),
/// or §5.6 flow B for an Android phone that relays its SIMs. Either one lets whoever scans it join
/// the account, so it appears only after local authentication, hides while the screen is recorded
/// or the app is in the background, and disappears once a device pairs or the code expires.
struct PairDeviceView: View {
    private enum Kind { case relay, reader }
    private enum Phase { case choose, loading, reader(PairingLink, Date), relay(IntroLink, Date), paired, expired }

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var phase = Phase.choose
    @State private var kind = Kind.relay
    @State private var captured = UIScreen.main.isCaptured
    @State private var knownDevices: Set<String> = []

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                switch phase {
                case .choose:
                    Text("What will the new device do?").font(.title3.bold()).frame(maxWidth: .infinity, alignment: .leading)
                    ChoiceCard(title: "Relay its SIMs", subtitle: "An Android phone with SIM cards. It gets its own key.", icon: "simcard", selected: kind == .relay) { kind = .relay }
                    ChoiceCard(title: "Read messages", subtitle: "Another iPhone or Android phone you carry.", icon: "iphone", selected: kind == .reader) { kind = .reader }
                    Label(
                        kind == .relay
                            ? "Whoever scans the code first joins your account as a relaying phone. Only show it to your own phones."
                            : "The code contains your encryption key. Only show it to your own devices.",
                        systemImage: "lock.shield"
                    )
                    .font(.callout).foregroundStyle(.secondary)
                    Spacer()
                    Button("Show pairing code") { Task { await unlock() } }.buttonStyle(.borderedProminent).controlSize(.large)
                case .loading:
                    Spacer()
                    ProgressView()
                    Spacer()
                case .reader(let link, let expires):
                    codeView(link.uri, expires: expires, hint: "On the new device, open Ferry → “Pair with my device”, and scan this code.")
                case .relay(let link, let expires):
                    codeView(link.uri, expires: expires, hint: "On your Android phone, open Ferry → “It has the SIM cards” → “No, pair with my device”, and scan this code.")
                case .paired:
                    Spacer()
                    Image(systemName: "checkmark.circle.fill").font(.system(size: 64)).foregroundStyle(.green)
                    Text("Device paired").font(.title2.bold())
                    Text(kind == .relay
                         ? "Its name and SIM labels come from that phone. You can rename them in Devices."
                         : "Name it and its SIMs in Devices so notifications say where they came from.")
                        .multilineTextAlignment(.center).foregroundStyle(.secondary)
                    Button("Done") { dismiss() }.buttonStyle(.borderedProminent).controlSize(.large)
                    Spacer()
                case .expired:
                    Spacer()
                    Text("The code expired.").font(.title3)
                    Button("Make a new code") { Task { await load() } }.buttonStyle(.borderedProminent)
                    Spacer()
                }
            }
            .padding(24)
            .navigationTitle("Pair a device")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
                captured = UIScreen.main.isCaptured
            }
            .task(id: isShowing) { if isShowing { await watch() } }
        }
        .interactiveDismissDisabled(isShowing)
    }

    @ViewBuilder
    private func codeView(_ uri: String, expires: Date, hint: String) -> some View {
        Text(hint).multilineTextAlignment(.center).foregroundStyle(.secondary)
        ZStack {
            if captured || scenePhase != .active {
                RoundedRectangle(cornerRadius: 20).fill(.fill.secondary)
                Label("Hidden while the screen is being recorded", systemImage: "eye.slash").foregroundStyle(.secondary).padding()
            } else {
                QRCode(text: uri).padding(16).background(.white, in: RoundedRectangle(cornerRadius: 20))
            }
        }
        .frame(maxWidth: 300).aspectRatio(1, contentMode: .fit)
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            let left = max(0, Int(expires.timeIntervalSince(ctx.date)))
            Text("Expires in \(left / 60):\(String(format: "%02d", left % 60))").font(.callout.monospacedDigit()).foregroundStyle(.secondary)
        }
        Spacer()
    }

    private var isShowing: Bool {
        switch phase {
        case .reader, .relay: true
        default: false
        }
    }

    private var expiry: Date? {
        switch phase {
        case .reader(_, let d), .relay(_, let d): d
        default: nil
        }
    }

    private func unlock() async {
        let context = LAContext()
        do {
            guard try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Show a code that adds a device to your account") else { return }
        } catch {
            return
        }
        await load()
    }

    private func load() async {
        phase = .loading
        knownDevices = await model.deviceIds() ?? []
        do {
            // The server's TTL is 5 minutes; derive the deadline locally to avoid clock skew.
            let expires = Date().addingTimeInterval(5 * 60 - 5)
            switch kind {
            case .reader: phase = .reader(try await model.makeReaderCode(), expires)
            case .relay: phase = .relay(try await model.makeNodeCode(), expires)
            }
        } catch {
            model.error = error.localizedDescription
            dismiss()
        }
    }

    /// Polls until the new device has paired, then hides the code; or until it expires. A phone that
    /// reads shows up in the device list; a relaying phone answers through the mailbox (§5.6).
    private func watch() async {
        while !Task.isCancelled, let expires = expiry {
            try? await Task.sleep(for: .seconds(2))
            if Date() >= expires {
                phase = .expired
                return
            }
            switch phase {
            case .relay(let link, _):
                do {
                    if try await model.checkNodeReply(link) {
                        phase = .paired
                        return
                    }
                } catch is CancellationError {
                    return
                } catch let e as UserFacingError {
                    model.error = e.localizedDescription
                    phase = .expired
                    return
                } catch {
                    // Offline for a moment: keep polling.
                }
            default:
                if let ids = await model.deviceIds(), !ids.subtracting(knownDevices).isEmpty {
                    phase = .paired
                    await model.refresh()
                    return
                }
            }
        }
    }
}

private struct QRCode: View {
    let text: String

    var body: some View {
        if let image = Self.render(text) {
            Image(uiImage: image).interpolation(.none).resizable().scaledToFit().accessibilityLabel("Pairing QR code")
        }
    }

    private static func render(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: .init(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
