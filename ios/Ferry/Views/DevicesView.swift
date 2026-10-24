import FerryKit
import SwiftUI

struct DevicesView: View {
    @Environment(AppModel.self) private var model
    @State private var editing: DeviceRecord?
    @State private var revoking: DeviceRecord?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(model.devices) { device in
                        Button { editing = device } label: {
                            DeviceRow(device: device, status: model.statuses[device.id], isSelf: device.id == model.account?.deviceId)
                        }
                        .tint(.primary)
                        .swipeActions {
                            Button("Remove", systemImage: "xmark.circle", role: .destructive) { revoking = device }
                        }
                    }
                } footer: {
                    Text("Every device shares your encryption key. If a phone is lost, remove it here, then re-pair the rest from a fresh account.")
                }
            }
            .navigationTitle("Devices")
            .navigationBarTitleDisplayMode(.inline)
            .refreshable { await model.refresh() }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Pair a device", systemImage: "plus") { model.showPairing = true }
                }
            }
            .sheet(isPresented: Binding(get: { model.showPairing }, set: { model.showPairing = $0 })) { PairDeviceView() }
            .sheet(item: $editing) { EditDeviceView(device: $0, status: model.statuses[$0.id]) }
            .confirmationDialog(
                "Remove \(revoking?.displayName ?? "")?", isPresented: Binding(get: { revoking != nil }, set: { if !$0 { revoking = nil } }), titleVisibility: .visible,
                presenting: revoking
            ) { device in
                Button(device.id == model.account?.deviceId ? "Remove this iPhone" : "Remove", role: .destructive) {
                    Task { await model.revoke(device) }
                }
            } message: { device in
                Text(device.id == model.account?.deviceId
                     ? "This iPhone stops receiving messages and its inbox is deleted."
                     : "It stops sending and receiving through your account. It still knows your key — re-pairing everything under a new account is the only way to fully lock it out.")
            }
        }
    }
}

private struct DeviceRow: View {
    let device: DeviceRecord
    let status: StatusEvent?
    let isSelf: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: device.platform == "ios" ? "iphone" : "smartphone")
                .font(.title2)
                .foregroundStyle(.ferryAccent)
                .frame(width: 36)
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(device.displayName).font(.headline)
                    if isSelf { Text("This iPhone").font(.caption).padding(.horizontal, 6).padding(.vertical, 2).background(.fill.secondary, in: Capsule()) }
                }
                Text(device.roles.contains("source") ? "Relays texts, calls & notifications" : "Receives").font(.caption).foregroundStyle(.secondary)
                if !isSelf { Text(health).font(.caption).foregroundStyle(stale ? .orange : .secondary) }
            }
            Spacer()
            if let battery = status?.battery {
                Label("\(battery)%", systemImage: status?.charging == true ? "battery.100.bolt" : "battery.50")
                    .font(.caption).foregroundStyle(.secondary).labelStyle(.titleAndIcon)
            }
        }
        .padding(.vertical, 4)
    }

    private var lastSeen: Date { Date(timeIntervalSince1970: TimeInterval(device.lastSeenAt) / 1000) }

    /// Sources heartbeat at least every 6 hours (§4.5); warn after 12 hours of silence.
    private var stale: Bool { device.roles.contains("source") && Date.now.timeIntervalSince(lastSeen) > 12 * 3600 }

    private var health: String {
        let seen = "Last seen \(lastSeen.formatted(.relative(presentation: .named)))"
        guard let perms = status?.permissions else { return seen }
        // The source reports permissions by internal key; name them the way people know them.
        let names = ["sms": "texts", "phone": "calls", "notifications": "notifications"]
        let missing = perms.filter { !$0.value }.map { names[$0.key] ?? $0.key }.sorted()
        return missing.isEmpty ? seen : "\(seen) · can't relay \(missing.formatted(.list(type: .and)))"
    }
}

private struct EditDeviceView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let device: DeviceRecord
    let status: StatusEvent?
    @State private var name = ""
    @State private var sims: [String: String] = [:]
    @State private var busy = false

    private var slots: [Int] {
        let known = Set((status?.sims ?? []).map(\.slot)).union(device.profile?.sims.keys.compactMap { Int($0) } ?? [])
        return known.sorted()
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField(device.platform == "ios" ? "iPhone" : "Android phone", text: $name)
                }
                if !slots.isEmpty {
                    Section {
                        ForEach(slots, id: \.self) { slot in
                            LabeledContent {
                                TextField("SIM \(slot + 1)", text: Binding(get: { sims[String(slot)] ?? "" }, set: { sims[String(slot)] = $0 }))
                                    .multilineTextAlignment(.trailing)
                            } label: {
                                VStack(alignment: .leading) {
                                    Text("SIM \(slot + 1)")
                                    if let carrier = status?.sims?.first(where: { $0.slot == slot })?.carrier {
                                        Text(carrier).font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                    } header: {
                        Text("SIM labels")
                    } footer: {
                        Text("Names are encrypted. The server never sees them.")
                    }
                }
                if let status {
                    Section("Status") {
                        if let v = status.appVersion { LabeledContent("Ferry version", value: v) }
                        if let b = status.battery { LabeledContent("Battery", value: "\(b)%\(status.charging == true ? " · charging" : "")") }
                        LabeledContent("Reported", value: Date(timeIntervalSince1970: TimeInterval(status.occurredAt) / 1000).formatted(.relative(presentation: .named)))
                    }
                }
            }
            .navigationTitle(device.displayName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        busy = true
                        Task {
                            await model.rename(device, name: name.trimmingCharacters(in: .whitespaces), sims: sims)
                            dismiss()
                        }
                    }
                    .disabled(busy || name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .onAppear {
                name = device.profile?.name ?? ""
                sims = device.profile?.sims ?? [:]
            }
        }
    }
}
