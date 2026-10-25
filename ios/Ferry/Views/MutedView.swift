import FerryKit
import SwiftUI

/// Mute or unmute a conversation's sender or app, for a conversation's menus.
struct MuteButton: View {
    @Environment(AppModel.self) private var model
    let threadKey: String
    let label: String

    var body: some View {
        if let target = Filters.target(threadKey) {
            if model.isMuted(threadKey) {
                Button("Unmute", systemImage: "bell") { model.unmute(target) }
            } else {
                Button("Mute notifications", systemImage: "bell.slash") { model.mute(threadKey, label: label) }
            }
        }
    }
}

/// Settings → Muted: suggestions and automatic muting, muted senders and apps, muted words, and words to suggest.
struct MutedView: View {
    @Environment(AppModel.self) private var model
    @State private var word = ""
    @State private var suggestions: [String] = []

    private var muted: [Mute] { model.mutes.filter { $0.kind == Filters.sender || $0.kind == Filters.app } }
    private var words: [Mute] { model.mutes.filter { $0.kind == Filters.word } }

    var body: some View {
        Form {
            Section {
                Toggle("Suggest muting", isOn: Binding(get: { model.suggestMuting }, set: { model.setSuggestMuting($0) }))
                Toggle("Mute likely marketing", isOn: Binding(get: { model.autoMute }, set: { model.setAutoMute($0) }))
            } footer: {
                Text("Muted messages still arrive in your inbox, without sound or a banner.")
            }

            Section {
                if muted.isEmpty {
                    Text("Nothing muted").foregroundStyle(.secondary)
                }
                ForEach(muted) { m in
                    HStack {
                        Text(m.label).lineLimit(1)
                        Spacer()
                        Button("Unmute") { model.unmute(m.target) }.buttonStyle(.borderless)
                    }
                }
            } header: {
                Text("Senders and apps")
            } footer: {
                if muted.isEmpty { Text("Mute a conversation from its menu.") }
            }

            Section {
                HStack {
                    TextField("Add a word or phrase", text: $word)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .submitLabel(.done)
                        .onSubmit(addWord)
                    Button("Add", systemImage: "plus.circle.fill", action: addWord)
                        .labelStyle(.iconOnly)
                        .buttonStyle(.borderless)
                        .disabled(word.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                ForEach(words) { w in Text(w.label) }
                    .onDelete { offsets in
                        for i in offsets { model.removeMutedWord(words[i].value) }
                    }
            } header: {
                Text("Words")
            } footer: {
                Text("Texts containing these arrive muted, except from your contacts and conversations you've replied to.")
            }

            if !suggestions.isEmpty {
                Section {
                    ForEach(suggestions, id: \.self) { s in
                        HStack {
                            Text(s)
                            Spacer()
                            Button("Dismiss", systemImage: "xmark") { model.skipSuggestion(s) }
                                .labelStyle(.iconOnly)
                                .foregroundStyle(.secondary)
                            Button("Add") { model.addMutedWord(s) }
                        }
                        .buttonStyle(.borderless)
                    }
                } header: {
                    Text("Suggested words")
                } footer: {
                    Text("Common in the senders you muted.")
                }
            }
        }
        .navigationTitle("Muted")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: model.mutes) { suggestions = (try? Inbox.shared.suggestions()) ?? [] }
    }

    private func addWord() {
        let w = word.trimmingCharacters(in: .whitespaces)
        word = ""
        if !w.isEmpty { model.addMutedWord(w) }
    }
}
