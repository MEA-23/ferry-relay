import FerryKit
import SwiftUI

struct DevicesView: View {
    @Environment(AppModel.self) private var model
    @State private var editing: DeviceRecord?
    @State private var revoking: DeviceRecord?
    /// Set by the edit sheet's Remove button; the confirmation opens once the sheet is gone.
    @State private var pendingRemove: DeviceRecord?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(model.devices) { device in
                        Button { editing = device } label: {
                            DeviceRow(
                                device: device, status: model.statuses[device.id], isSelf: device.id == model.account?.deviceId,
                                addedBy: device.profile?.addedBy.flatMap { id in model.devices.first { $0.id == id }?.displayName }
                            )
                        }
                        .tint(.primary)
                        .swipeActions {
                            Button("Remove", systemImage: "xmark.circle", role: .destructive) { revoking = device }
                        }
                    }
                } footer: {
                    Text("Tap a device to rename or remove it.")
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
            .sheet(item: $editing, onDismiss: {
                revoking = pendingRemove
                pendingRemove = nil
            }) { device in
                EditDeviceView(device: device, status: model.statuses[device.id]) { pendingRemove = device }
            }
            .task(id: "\(model.removeRequest ?? "")\(model.devices.count)") {
                // From an introduction's Remove button (§4.7).
                if let id = model.removeRequest, let device = model.devices.first(where: { $0.id == id }) {
                    revoking = device
                    model.removeRequest = nil
                }
            }
            .confirmationDialog(
                "Remove \(revoking?.displayName ?? "")?", isPresented: Binding(get: { revoking != nil }, set: { if !$0 { revoking = nil } }), titleVisibility: .visible,
                presenting: revoking
            ) { device in
                // Removing a node also cancels its introductions; offer to remove the phones it added (§5.4).
                let added = model.added(by: device)
                if !added.isEmpty {
                    Button("Remove all \(added.count + 1)", role: .destructive) { Task { await model.revoke([device] + added) } }
                }
                Button(device.id == model.account?.deviceId ? "Remove this iPhone" : added.isEmpty ? "Remove" : "Remove only \(device.displayName)", role: .destructive) {
                    Task { await model.revoke([device]) }
                }
            } message: { device in
                let added = model.added(by: device)
                Text(device.id == model.account?.deviceId
                     ? "This iPhone stops receiving messages and its inbox is deleted."
                     : !device.roles.contains("receiver")
                     ? "It stops relaying to your phones." + (added.isEmpty ? "" : " It added \(added.map(\.displayName).formatted(.list(type: .and))).")
                     : "It stops receiving your messages. If the phone is lost, also set up a new account and pair your phones again.")
            }
        }
    }
}

private struct DeviceRow: View {
    let device: DeviceRecord
    let status: StatusEvent?
    let isSelf: Bool
    let addedBy: String?

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
                if let addedBy { Text("Added by \(addedBy)").font(.caption).foregroundStyle(.secondary) }
                if !isSelf { Text(health).font(.caption).foregroundStyle(stale ? .orange : .secondary) }
            }
            Spacer()
            // A level outside 0–100 is a phone misreporting, not something to show.
            if let battery = status?.battery, (0...100).contains(battery) {
                Label("\(battery)%", systemImage: status?.charging == true ? "battery.100.bolt" : "battery.50")
                    .font(.caption).foregroundStyle(.secondary).labelStyle(.titleAndIcon)
            }
        }
        .padding(.vertical, 4)
    }

    private var lastSeen: Date { Date(timeIntervalSince1970: TimeInterval(device.lastSeenAt) / 1000) }

    /// Sources heartbeat at least every hour (§4.5); warn after 3 hours of silence.
    private var stale: Bool { device.roles.contains("source") && Date.now.timeIntervalSince(lastSeen) > 3 * 3600 }

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
    let onRemove: () -> Void
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
                Section {
                    Button("Remove device", role: .destructive) {
                        onRemove()
                        dismiss()
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
