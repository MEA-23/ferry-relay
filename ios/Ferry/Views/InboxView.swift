import FerryKit
import SwiftUI

struct InboxView: View {
    private enum Filter: String, CaseIterable, Identifiable {
        case all = "All", texts = "Texts", calls = "Calls", apps = "Apps"
        var id: Self { self }
    }

    @Environment(AppModel.self) private var model
    @State private var filter = Filter.all
    @State private var search = ""
    /// Messages whose text matches `search`, from every thread, newest first.
    @State private var results: [Message] = []
    @State private var path = NavigationPath()

    private var query: String { search.trimmingCharacters(in: .whitespaces) }

    private var visible: [ThreadSummary] {
        model.threads.filter { t in
            switch filter {
            case .all: true
            case .texts: t.last.type == "sms"
            case .calls: t.last.type == "call"
            case .apps: t.last.type == "notification"
            }
        }
    }

    /// Threads whose name matches the search.
    private var matchingThreads: [ThreadSummary] {
        model.threads.filter { !TextTools.matches(in: $0.last.title, of: query).isEmpty }
    }

    var body: some View {
        @Bindable var model = model
        NavigationStack(path: $path) {
            List {
                if query.isEmpty {
                    Section {
                        ForEach(visible) { thread in threadLink(thread) }
                    } header: {
                        if !model.threads.isEmpty { filterChips }
                    }
                } else {
                    if !matchingThreads.isEmpty {
                        Section("Conversations") {
                            ForEach(matchingThreads) { thread in threadLink(thread) }
                        }
                    }
                    if !results.isEmpty {
                        Section("Messages") {
                            ForEach(results) { m in
                                NavigationLink(value: ThreadRoute(threadKey: m.threadKey, focus: m.id, query: query)) {
                                    SearchResultRow(message: m, query: query)
                                }
                            }
                        }
                    }
                }
            }
            .listStyle(.plain)
            .overlay {
                if query.isEmpty ? visible.isEmpty : (matchingThreads.isEmpty && results.isEmpty) { emptyState }
            }
            .navigationTitle("Inbox")
            // A fixed header: pulling to refresh moves the list, not the title and search bar.
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: ThreadRoute.self) { ThreadView(route: $0) }
            .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search messages")
            .task(id: query) {
                // Wait for a pause in typing before searching the whole inbox.
                try? await Task.sleep(for: .milliseconds(150))
                guard !Task.isCancelled else { return }
                results = (try? Inbox.shared.search(query)) ?? []
            }
            .refreshable { await model.refresh() }
            .onChange(of: model.openThread, initial: true) { _, thread in
                guard let thread else { return }
                path = NavigationPath([ThreadRoute(threadKey: thread, latest: true)])
                model.openThread = nil
            }
        }
    }

    private func threadLink(_ thread: ThreadSummary) -> some View {
        NavigationLink(value: ThreadRoute(threadKey: thread.threadKey)) {
            ThreadRow(thread: thread, source: model.devices.first { $0.id == thread.last.sourceDeviceId }, query: query)
        }
        .swipeActions {
            Button("Delete", systemImage: "trash", role: .destructive) { model.delete(thread.threadKey) }
        }
        .swipeActions(edge: .leading) {
            if thread.unreadCount > 0 {
                Button("Mark read", systemImage: "envelope.open") { model.markRead(thread.threadKey) }.tint(.ferryAccent)
            }
        }
    }

    private var filterChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Filter.allCases) { f in
                    Button {
                        withAnimation(.snappy) { filter = f }
                    } label: {
                        Text(f.rawValue)
                            .font(.subheadline.weight(.medium))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 7)
                            .foregroundStyle(filter == f ? Color.white : .primary)
                            .background(filter == f ? AnyShapeStyle(Color.ferryAccent) : AnyShapeStyle(.fill.tertiary), in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(filter == f ? .isSelected : [])
                }
            }
            .padding(.vertical, 6)
        }
        .textCase(nil)
    }

    @ViewBuilder
    private var emptyState: some View {
        if !query.isEmpty {
            ContentUnavailableView.search(text: query)
        } else if model.sources.isEmpty {
            ContentUnavailableView {
                Label("No phones yet", systemImage: "iphone.gen3.badge.plus")
            } description: {
                Text("Pair an Android phone and its texts, calls and notifications show up here.")
            } actions: {
                Button("Pair a phone") {
                    model.tab = .devices
                    model.showPairing = true
                }
                .buttonStyle(.borderedProminent)
            }
        } else if filter != .all {
            ContentUnavailableView("No \(filter.rawValue.lowercased()) yet", systemImage: "line.3.horizontal.decrease.circle", description: Text("Nothing matches this filter."))
        } else {
            ContentUnavailableView("All caught up", systemImage: "tray", description: Text("New messages from your phones appear here."))
        }
    }
}


struct ThreadRow: View {
    let thread: ThreadSummary
    let source: DeviceRecord?
    var query = ""

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            KindIcon(type: thread.last.type)
            VStack(alignment: .leading, spacing: 3) {
                HStack(alignment: .firstTextBaseline) {
                    Text(Highlight.text(thread.last.title, query: query)).font(.headline).lineLimit(1)
                    Spacer()
                    Text(thread.last.date, format: .relativeShort).font(.caption).foregroundStyle(.secondary)
                }
                Text(thread.last.isOutgoing ? "You: \(thread.last.body)" : thread.last.body).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                if let label = sourceLabel {
                    Text(label).font(.caption2.weight(.medium)).foregroundStyle(.ferryAccent)
                }
            }
            if thread.unreadCount > 0 {
                Circle().fill(.ferryAccent).frame(width: 9, height: 9).padding(.top, 6).accessibilityLabel("Unread")
            }
        }
        .padding(.vertical, 4)
    }

    private var sourceLabel: String? {
        if let slot = thread.last.simSlot { return "\(source?.simLabel(slot) ?? "SIM \(slot + 1)") · \(source?.displayName ?? "Android phone")" }
        return source?.displayName
    }
}

/// A message found by inbox search, with the match shown in context.
private struct SearchResultRow: View {
    let message: Message
    let query: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            KindIcon(type: message.type)
            VStack(alignment: .leading, spacing: 3) {
                HStack(alignment: .firstTextBaseline) {
                    Text(message.title).font(.headline).lineLimit(1)
                    Spacer()
                    Text(message.date, format: .relativeShort).font(.caption).foregroundStyle(.secondary)
                }
                Text(Highlight.snippet(message.isOutgoing ? "You: \(message.body)" : message.body, query: query))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, 4)
    }
}

struct KindIcon: View {
    let type: String

    var body: some View {
        let (symbol, color): (String, Color) = switch type {
        case "sms": ("message.fill", .green)
        case "call": ("phone.fill", .blue)
        case "notification": ("app.badge.fill", .purple)
        default: ("questionmark", .gray)
        }
        Image(systemName: symbol)
            .font(.system(size: 16, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: 38, height: 38)
            .background(color.gradient, in: Circle())
            .accessibilityHidden(true)
    }
}

extension FormatStyle where Self == RelativeShortDate {
    static var relativeShort: RelativeShortDate { .init() }
}

/// "14:02" today, "Mon" this week, "12 Oct" otherwise.
struct RelativeShortDate: FormatStyle {
    func format(_ date: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(date) { return date.formatted(date: .omitted, time: .shortened) }
        if let days = cal.dateComponents([.day], from: date, to: .now).day, days < 7 { return date.formatted(.dateTime.weekday(.abbreviated)) }
        return date.formatted(.dateTime.day().month(.abbreviated))
    }
}
