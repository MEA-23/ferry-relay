import FerryKit
import SwiftUI

/// A thread to open; from inbox search, also the message to scroll to and the search to show in it.
/// `latest` opens at the newest message (from a notification) instead of the first unread one.
struct ThreadRoute: Hashable {
    let threadKey: String
    var focus: String?
    var query = ""
    var latest = false
}

/// One row of a conversation: a day header, the "New messages" divider, or a message.
private enum Entry: Identifiable {
    case day(Date)
    case newDivider
    case message(Message)

    var id: String {
        switch self {
        case .day(let d): "day:\(Int(d.timeIntervalSince1970))"
        case .newDivider: "new"
        case .message(let m): m.id
        }
    }
}

/// Opens at the first unread message (under a "New messages" divider), or at the newest one when
/// everything was read or it was opened from a notification, or at the message a search led to.
struct ThreadView: View {
    @Environment(AppModel.self) private var model
    let route: ThreadRoute
    @State private var messages: [Message] = []
    /// Unread when the thread opened. Kept while it's open, so the divider doesn't jump away.
    @State private var firstUnread: String?
    @State private var query: String
    /// The search bar shows only while searching, pinned under the header, instead of hiding in
    /// the navigation bar where pulling the conversation down would reveal it.
    @State private var searching: Bool
    @FocusState private var searchFocused: Bool
    @FocusState private var replyFocused: Bool
    @State private var hits: [String] = []
    @State private var current = -1
    /// Hidden until scrolled to where it opens, so it never flashes the oldest messages first.
    @State private var positioned = false
    @State private var confirmDelete = false
    @Environment(\.dismiss) private var dismiss

    private var archived: Bool { model.threads.contains { $0.threadKey == route.threadKey && $0.archived } }

    init(route: ThreadRoute) {
        self.route = route
        _query = State(initialValue: route.query)
        _searching = State(initialValue: !route.query.isEmpty)
    }

    var body: some View {
        ScrollViewReader { proxy in
            GeometryReader { geo in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        ForEach(entries) { entry in
                            switch entry {
                            case .day(let date):
                                DayHeader(date: date)
                            case .newDivider:
                                NewDivider()
                            case .message(let m):
                                MessageBubble(message: m, source: source(of: m), query: query, isCurrent: hits.indices.contains(current) && hits[current] == m.id)
                                    // Texts sent from the source phone sit on the right, like any messaging app.
                                    .padding(m.isOutgoing ? .leading : .trailing, 40)
                            }
                        }
                    }
                    .padding()
                    // At least as tall as what's visible, messages at the bottom: a short conversation then
                    // rises with the keyboard in the same motion, with nothing to scroll.
                    .frame(minHeight: geo.size.height, alignment: .bottom)
                }
                .defaultScrollAnchor(.bottom)
                // Stays at the newest message when the keyboard shrinks the view.
                .defaultScrollAnchor(.bottom, for: .sizeChanges)
                // The reply box is multi-line, so Return can't close the keyboard: dragging or tapping the messages does.
                .scrollDismissesKeyboard(.interactively)
                .onTapGesture {
                    replyFocused = false
                    searchFocused = false
                }
                .opacity(positioned ? 1 : 0)
                .onAppear {
                    guard !positioned else { return }
                    messages = (try? Inbox.shared.messages(thread: route.threadKey)) ?? []
                    firstUnread = messages.first(where: \.unread)?.id
                    hits = Self.hits(in: messages, for: query)
                    // One layout pass later, so the lazy stack has the rows to scroll to.
                    DispatchQueue.main.async {
                        if let focus = route.focus {
                            proxy.scrollTo(focus, anchor: .center)
                            current = hits.firstIndex(of: focus) ?? -1
                        } else if firstUnread != nil, !route.latest {
                            proxy.scrollTo("new", anchor: .top)
                        } else if let last = messages.last {
                            proxy.scrollTo(last.id, anchor: .bottom)
                        }
                        guard route.latest, let last = messages.last else {
                            positioned = true
                            markReadIfNeeded()
                            return
                        }
                        // From a notification the app is still coming to the foreground, and that
                        // first scroll gets dropped. Scroll again once it has settled.
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                            proxy.scrollTo(last.id, anchor: .bottom)
                            positioned = true
                            markReadIfNeeded()
                        }
                    }
                }
                .onChange(of: messages.last?.id) { _, id in
                    if positioned, query.isEmpty, let id { withAnimation { proxy.scrollTo(id, anchor: .bottom) } }
                }
                .onChange(of: current) { _, i in
                    if positioned, hits.indices.contains(i) { withAnimation { proxy.scrollTo(hits[i], anchor: .center) } }
                }
            }
        }
        .onChange(of: query) {
            hits = Self.hits(in: messages, for: query)
            // A new search starts at its newest match; the arrows step through the rest.
            current = hits.count - 1
        }
        .safeAreaInset(edge: .top) {
            if searching { searchBar }
        }
        .safeAreaInset(edge: .bottom) {
            if !query.trimmingCharacters(in: .whitespaces).isEmpty {
                matchBar
            } else if !searching, let last = lastText, let number = phoneNumber, model.canSend(via: last.sourceDeviceId) {
                if TextTools.acceptsReplies(number) {
                    // Replies go out through the node the conversation came from (§6.6).
                    ReplyBar(focus: $replyFocused) { text in
                        Task {
                            await model.sendSms(text, to: number, thread: route.threadKey, replyingTo: last)
                            reload()
                        }
                    }
                } else {
                    Text("This sender doesn't accept replies.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(.bar)
                }
            }
        }
        .navigationTitle(messages.last?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Search in conversation", systemImage: "magnifyingglass") {
                    searching = true
                    searchFocused = true
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    if let number = phoneNumber, TextTools.acceptsReplies(number) {
                        Button("Copy number", systemImage: "doc.on.doc") { UIPasteboard.general.string = number }
                        if let url = URL(string: "tel:\(number.filter { "+0123456789".contains($0) })") {
                            Link(destination: url) { Label("Call from this iPhone", systemImage: "phone") }
                        }
                    }
                    MuteButton(threadKey: route.threadKey, label: messages.last?.title ?? "")
                    Button(archived ? "Unarchive" : "Archive", systemImage: archived ? "tray.and.arrow.up" : "archivebox") {
                        model.archive(route.threadKey, !archived)
                        if !archived { dismiss() }
                    }
                    Button("Delete conversation", systemImage: "trash", role: .destructive) { confirmDelete = true }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .confirmationDialog("Delete conversation?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                model.delete(route.threadKey)
                dismiss()
            }
        } message: {
            Text("Removes it from this iPhone only. The original messages stay on the phone they came from.")
        }
        .onChange(of: model.threads) { reload() }
    }

    private var searchBar: some View {
        HStack(spacing: 10) {
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Search in conversation", text: $query)
                    .focused($searchFocused)
                    .submitLabel(.search)
                    .autocorrectionDisabled()
                if !query.isEmpty {
                    Button("Clear", systemImage: "xmark.circle.fill") { query = "" }
                        .labelStyle(.iconOnly)
                        .foregroundStyle(.tertiary)
                        .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(.fill.tertiary, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            Button("Cancel") {
                query = ""
                searching = false
                searchFocused = false
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.bar)
    }

    private var matchBar: some View {
        HStack(spacing: 16) {
            Text(hits.isEmpty ? "No matches" : "\(current + 1) of \(hits.count)")
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(.secondary)
            Spacer()
            Button("Older match", systemImage: "chevron.up") { current -= 1 }
                .disabled(current <= 0)
            Button("Newer match", systemImage: "chevron.down") { current += 1 }
                .disabled(current < 0 || current >= hits.count - 1)
        }
        .labelStyle(.iconOnly)
        .font(.body.weight(.semibold))
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(.bar)
    }

    private var entries: [Entry] {
        var out = [Entry]()
        var day: Date?
        let calendar = Calendar.current
        for m in messages {
            let d = calendar.startOfDay(for: m.date)
            if d != day {
                day = d
                out.append(.day(d))
            }
            if m.id == firstUnread { out.append(.newDivider) }
            out.append(.message(m))
        }
        return out
    }

    private static func hits(in messages: [Message], for query: String) -> [String] {
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else { return [] }
        return messages.filter { !TextTools.matches(in: $0.body, of: query).isEmpty }.map(\.id)
    }

    private func reload() {
        messages = (try? Inbox.shared.messages(thread: route.threadKey)) ?? []
        if !query.isEmpty { hits = Self.hits(in: messages, for: query) }
        markReadIfNeeded()
    }

    private func markReadIfNeeded() {
        if positioned, messages.contains(where: \.unread) { model.markRead(route.threadKey) }
    }

    private func source(of m: Message) -> DeviceRecord? { model.devices.first { $0.id == m.sourceDeviceId } }

    /// The newest text, sent or received, whose SIM a reply goes out on.
    private var lastText: Message? {
        messages.last { if case .sms = $0.event { true } else { false } }
    }

    private var phoneNumber: String? {
        switch messages.last?.event {
        case .sms(let e): e.from
        case .call(let e): e.from
        default: nil
        }
    }
}

/// A message box that sends through the node; `onSend` gets the trimmed text.
private struct ReplyBar: View {
    var focus: FocusState<Bool>.Binding
    let onSend: (String) -> Void
    @State private var text = ""

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            TextField("Text message", text: $text, axis: .vertical)
                .lineLimit(1...5)
                .focused(focus)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(.fill.tertiary, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            Button("Send", systemImage: "arrow.up.circle.fill") {
                onSend(text.trimmingCharacters(in: .whitespacesAndNewlines))
                text = ""
            }
            .labelStyle(.iconOnly)
            .font(.title)
            .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
    }
}

private struct DayHeader: View {
    let date: Date

    var body: some View {
        Text(label)
            .font(.caption.weight(.medium))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(.fill.tertiary, in: Capsule())
            .frame(maxWidth: .infinity)
            .padding(.vertical, 4)
    }

    private var label: String {
        let cal = Calendar.current
        if cal.isDateInToday(date) { return "Today" }
        if cal.isDateInYesterday(date) { return "Yesterday" }
        return date.formatted(date: .abbreviated, time: .omitted)
    }
}

private struct NewDivider: View {
    var body: some View {
        HStack(spacing: 10) {
            Rectangle().fill(Color.ferryAccent.opacity(0.5)).frame(height: 1)
            Text("New messages").font(.caption.weight(.semibold)).foregroundStyle(.ferryAccent).fixedSize()
            Rectangle().fill(Color.ferryAccent.opacity(0.5)).frame(height: 1)
        }
        .padding(.vertical, 4)
        .id("new")
    }
}

private struct MessageBubble: View {
    let message: Message
    let source: DeviceRecord?
    let query: String
    let isCurrent: Bool

    /// Right-to-left text (Arabic, Hebrew) lines up on the right inside its bubble, wherever the bubble sits.
    private var rtl: Bool { TextTools.isRTL(message.body) }

    var body: some View {
        BubbleColumn(spacing: 6) {
            switch message.event {
            case .call(let call):
                Label(Summary.callText(call), systemImage: callSymbol(call.state))
                    .font(.callout.weight(.medium))
                    .foregroundStyle(call.state == "missed" ? .red : .primary)
            case .notification(let n):
                if let title = n.title { Text(title).font(.callout.weight(.semibold)).layoutValue(key: TrailingRow.self, value: rtl) }
                bodyText
            default:
                bodyText
            }
            HStack(spacing: 6) {
                if message.isOutgoing { Image(systemName: "arrow.up.right").accessibilityLabel("Sent") }
                if let status = message.status {
                    Text(Self.statusText(status)).foregroundStyle(status.hasPrefix("failed") ? .red : .secondary)
                    Text("·")
                }
                Text(message.date.formatted(date: .omitted, time: .shortened))
                Text("·")
                Text(origin)
                if let code = oneTimeCode {
                    Spacer(minLength: 8)
                    Button("Copy \(code)", systemImage: "doc.on.doc") { UIPasteboard.general.string = code }
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)
                        .fixedSize()
                        .buttonStyle(.bordered)
                        .controlSize(.mini)
                }
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
        }
        .padding(12)
        .background(bubble, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            if isCurrent { RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Color.ferryAccent, lineWidth: 2) }
        }
        .frame(maxWidth: .infinity, alignment: message.isOutgoing ? .trailing : .leading)
        .id(message.id)
    }

    /// Where a text sent through a node is (§4.8).
    static func statusText(_ status: String) -> String {
        switch status {
        case "sending": return "Sending…"
        case "sent": return "Sent"
        case "delivered": return "Delivered"
        default:
            let reason = switch status.split(separator: ":").last.map(String.init) {
            case "expired": "took too long"
            case "rate_limited": "too many texts"
            case "unknown_number": "turn on Allow new numbers on that phone"
            case "no_permission": "no permission on that phone"
            case "no_sim": "that SIM isn't in"
            case "not_queued": "couldn't reach the server"
            default: "the network refused it"
            }
            return "Not sent: \(reason)"
        }
    }

    private var bodyText: some View {
        Text(Highlight.text(message.body, query: query, strong: isCurrent))
            .multilineTextAlignment(rtl ? .trailing : .leading)
            .textSelection(.enabled)
            .layoutValue(key: TrailingRow.self, value: rtl)
    }

    private var bubble: AnyShapeStyle {
        message.isOutgoing ? AnyShapeStyle(Color.ferryAccent.opacity(0.18)) : AnyShapeStyle(.fill.tertiary)
    }

    private var origin: String {
        let device = source?.displayName ?? "Android phone"
        guard let slot = message.simSlot else { return device }
        return "\(source?.simLabel(slot) ?? "SIM \(slot + 1)") · \(device)"
    }

    /// A 4–8 digit code in an SMS, Arabic-Indic digits included, offered as a one-tap copy.
    private var oneTimeCode: String? {
        guard message.type == "sms", !message.isOutgoing else { return nil }
        let match = message.body.firstMatch(of: #/\b(\d{4,8})\b/#)
        return match.map { TextTools.normalize(String($0.1)) }
    }

    private func callSymbol(_ state: String) -> String {
        switch state {
        case "missed": "phone.arrow.down.left"
        case "rejected": "phone.down"
        case "ringing": "phone.badge.waveform"
        default: "phone.arrow.down.left.fill"
        }
    }
}

/// A bubble's rows, stacked as wide as the widest one. Rows marked `TrailingRow` (right-to-left
/// text) sit on the right; the rest, the time and SIM footer included, stay on the left.
private struct BubbleColumn: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let sizes = subviews.map { $0.sizeThatFits(ProposedViewSize(width: proposal.width, height: nil)) }
        let gaps = spacing * CGFloat(max(sizes.count - 1, 0))
        return CGSize(width: sizes.map(\.width).max() ?? 0, height: sizes.map(\.height).reduce(gaps, +))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in subviews {
            let size = row.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
            let x = row[TrailingRow.self] ? bounds.maxX - size.width : bounds.minX
            row.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            y += size.height + spacing
        }
    }
}

private struct TrailingRow: LayoutValueKey {
    static let defaultValue = false
}

/// Search matches, highlighted in place.
enum Highlight {
    static func text(_ text: String, query: String, strong: Bool = false) -> AttributedString {
        var out = AttributedString(text)
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else { return out }
        for r in TextTools.matches(in: text, of: query) {
            guard let lower = AttributedString.Index(r.lowerBound, within: out),
                  let upper = AttributedString.Index(r.upperBound, within: out) else { continue }
            out[lower..<upper].backgroundColor = Color.ferryAccent.opacity(strong ? 0.45 : 0.25)
            out[lower..<upper].inlinePresentationIntent = .stronglyEmphasized
        }
        return out
    }

    /// The part of `text` around its first match, so the match shows within two lines.
    static func snippet(_ text: String, query: String) -> AttributedString {
        guard let first = TextTools.matches(in: text, of: query).first,
              text.distance(from: text.startIndex, to: first.lowerBound) > 40 else { return Self.text(text, query: query) }
        var start = text.index(first.lowerBound, offsetBy: -30)
        if let space = text[..<start].lastIndex(of: " "), text.distance(from: space, to: start) < 12 {
            start = text.index(after: space)
        }
        return Self.text("…" + text[start...], query: query)
    }
}
