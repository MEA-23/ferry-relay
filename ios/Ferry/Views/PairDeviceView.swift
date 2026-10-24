import CoreImage.CIFilterBuiltins
import FerryKit
import LocalAuthentication
import SwiftUI

/// Shows a pairing QR (§5.3). It contains the account key, so it appears only after local
/// authentication, hides while the screen is recorded or the app is in the background,
/// and disappears once a device pairs or the code expires.
struct PairDeviceView: View {
    private enum Phase { case locked, loading, showing(PairingLink, Date), paired, expired }

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var phase = Phase.locked
    @State private var captured = UIScreen.main.isCaptured
    @State private var knownDevices: Set<String> = []

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                switch phase {
                case .locked:
                    Spacer()
                    Image(systemName: "lock.shield").font(.system(size: 56)).foregroundStyle(.ferryAccent)
                    Text("The pairing code contains your encryption key. Only show it to your own devices.")
                        .multilineTextAlignment(.center).foregroundStyle(.secondary)
                    Button("Show pairing code") { Task { await unlock() } }.buttonStyle(.borderedProminent).controlSize(.large)
                    Spacer()
                case .loading:
                    Spacer()
                    ProgressView()
                    Spacer()
                case .showing(let link, let expires):
                    Text("On your Android phone, open Ferry → Pair with my other device, and scan this code.")
                        .multilineTextAlignment(.center).foregroundStyle(.secondary)
                    ZStack {
                        if captured || scenePhase != .active {
                            RoundedRectangle(cornerRadius: 20).fill(.fill.secondary)
                            Label("Hidden while the screen is being recorded", systemImage: "eye.slash").foregroundStyle(.secondary).padding()
                        } else {
                            QRCode(text: link.uri).padding(16).background(.white, in: RoundedRectangle(cornerRadius: 20))
                        }
                    }
                    .frame(maxWidth: 300).aspectRatio(1, contentMode: .fit)
                    TimelineView(.periodic(from: .now, by: 1)) { ctx in
                        let left = max(0, Int(expires.timeIntervalSince(ctx.date)))
                        Text("Expires in \(left / 60):\(String(format: "%02d", left % 60))").font(.callout.monospacedDigit()).foregroundStyle(.secondary)
                    }
                    Spacer()
                case .paired:
                    Spacer()
                    Image(systemName: "checkmark.circle.fill").font(.system(size: 64)).foregroundStyle(.green)
                    Text("Device paired").font(.title2.bold())
                    Text("Name it and its SIMs in Devices so notifications say where they came from.").multilineTextAlignment(.center).foregroundStyle(.secondary)
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

    private var isShowing: Bool { if case .showing = phase { true } else { false } }

    private func unlock() async {
        let context = LAContext()
        do {
            guard try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Show the code that contains your encryption key") else { return }
        } catch {
            return
        }
        await load()
    }

    private func load() async {
        phase = .loading
        knownDevices = await model.deviceIds() ?? []
        do {
            let link = try await model.makePairingCode()
            // The server's TTL is 5 minutes; derive the deadline locally to avoid clock skew.
            phase = .showing(link, Date().addingTimeInterval(5 * 60 - 5))
        } catch {
            model.error = error.localizedDescription
            dismiss()
        }
    }

    /// Polls for a new device; hides the code as soon as one appears or it expires.
    private func watch() async {
        while !Task.isCancelled, case .showing(_, let expires) = phase {
            try? await Task.sleep(for: .seconds(2))
            if Date() >= expires {
                phase = .expired
                return
            }
            if let ids = await model.deviceIds(), !ids.subtracting(knownDevices).isEmpty {
                phase = .paired
                await model.refresh()
                return
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
