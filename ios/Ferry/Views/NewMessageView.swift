import FerryKit
import SwiftUI

/// A text to any number, sent from a phone and SIM of the user's choosing (§6.6). `onSent` gets
/// the conversation it went into, to open it.
struct NewMessageView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let onSent: (String) -> Void
    @State private var number = ""
    @State private var text = ""
    @State private var from: SendOption?
    @State private var sending = false
    @FocusState private var numberFocused: Bool

    @AppStorage("ferry.lastSendOption") private var lastOption = ""

    /// Spaces, dashes and brackets dropped, so the number matches its conversation.
    private var cleanNumber: String { number.filter { !" -().".contains($0) } }

    private var canSend: Bool {
        cleanNumber.count >= 3 && cleanNumber.allSatisfy { "+0123456789".contains($0) } && cleanNumber.dropFirst().allSatisfy(\.isNumber)
            && !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && from != nil && !sending
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Phone number", text: $number)
                        .keyboardType(.phonePad)
                        .textContentType(.telephoneNumber)
                        .focused($numberFocused)
                } header: {
                    Text("To")
                }
                if model.sendOptions.count > 1 {
                    Section {
                        Picker("From", selection: $from) {
                            ForEach(model.sendOptions) { Text($0.label).tag(Optional($0)) }
                        }
                    }
                }
                Section {
                    TextField("Text message", text: $text, axis: .vertical)
                        .lineLimit(3...8)
                }
            }
            .navigationTitle("New message")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Send") { send() }.disabled(!canSend)
                }
            }
            .onAppear {
                from = model.sendOptions.first { $0.id == lastOption } ?? model.sendOptions.first
                numberFocused = true
            }
        }
    }

    private func send() {
        guard let from else { return }
        // Down before the conversation opens, or it lays out around a keyboard that's leaving.
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        sending = true
        lastOption = from.id
        Task {
            if let thread = await model.sendSms(text.trimmingCharacters(in: .whitespacesAndNewlines), to: cleanNumber, from: from) {
                onSent(thread)
            }
            sending = false
        }
    }
}
