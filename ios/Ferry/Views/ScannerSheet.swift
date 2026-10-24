import AVFoundation
import SwiftUI

/// Full-screen camera that reports the first QR code it reads.
struct ScannerSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var denied = false
    let onResult: (String) -> Void

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if denied {
                    ContentUnavailableView("Camera access needed", systemImage: "camera", description: Text("Allow camera access in Settings, or paste the pairing link instead."))
                        .foregroundStyle(.white)
                } else {
                    QRScanner(onResult: onResult).ignoresSafeArea()
                    RoundedRectangle(cornerRadius: 24).strokeBorder(.white.opacity(0.9), lineWidth: 3).frame(width: 260, height: 260)
                }
            }
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .toolbarBackground(.hidden, for: .navigationBar)
            .task {
                if AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined {
                    denied = !(await AVCaptureDevice.requestAccess(for: .video))
                } else {
                    denied = AVCaptureDevice.authorizationStatus(for: .video) != .authorized
                }
            }
        }
    }
}

private struct QRScanner: UIViewRepresentable {
    let onResult: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onResult: onResult) }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        let session = context.coordinator.session
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        if let camera = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: camera), session.canAddInput(input) {
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            if session.canAddOutput(output) {
                session.addOutput(output)
                output.setMetadataObjectsDelegate(context.coordinator, queue: .main)
                output.metadataObjectTypes = [.qr]
            }
            DispatchQueue.global(qos: .userInitiated).async { session.startRunning() }
        }
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}

    static func dismantleUIView(_ uiView: PreviewView, coordinator: Coordinator) {
        let session = coordinator.session
        DispatchQueue.global(qos: .userInitiated).async { session.stopRunning() }
    }

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        let session = AVCaptureSession()
        private let onResult: (String) -> Void
        private var done = false

        init(onResult: @escaping (String) -> Void) {
            self.onResult = onResult
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard !done, let text = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue else { return }
            done = true
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            onResult(text)
        }
    }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}
