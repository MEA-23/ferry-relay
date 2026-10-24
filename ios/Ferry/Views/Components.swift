import SwiftUI

/// A labeled input with an icon, a focus ring, and room for a hint or an error underneath.
struct FerryField<Accessory: View>: View {
    let label: String
    let icon: String
    @Binding var text: String
    var prompt = ""
    var error: String?
    var hint: String?
    var axis: Axis = .horizontal
    var monospaced = false
    /// Owned by the parent so the Return key can move focus between fields.
    var focus: FocusState<Bool>.Binding
    @ViewBuilder var accessory: Accessory

    private var focused: Bool { focus.wrappedValue }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.secondary)

            HStack(alignment: axis == .vertical ? .top : .center, spacing: 10) {
                Image(systemName: icon)
                    .font(.body.weight(.medium))
                    .foregroundStyle(focused ? Color.ferryAccent : .secondary)
                    .frame(width: 22)
                    .padding(.top, axis == .vertical ? 2 : 0)
                TextField(label, text: $text, prompt: Text(prompt), axis: axis)
                    .lineLimit(axis == .vertical ? 3...6 : 1...1)
                    .font(monospaced ? .callout.monospaced() : .body)
                    .focused(focus)
                if !text.isEmpty && focused {
                    Button("Clear", systemImage: "xmark.circle.fill") { text = "" }
                        .labelStyle(.iconOnly)
                        .foregroundStyle(.tertiary)
                        .buttonStyle(.plain)
                }
                accessory
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 14)
            .frame(minHeight: 54)
            .background(Color(.secondarySystemBackground), in: shape)
            .overlay {
                shape.strokeBorder(borderColor, lineWidth: focused || error != nil ? 1.5 : 0)
            }
            .contentShape(shape)
            .onTapGesture { focus.wrappedValue = true }
            .animation(.easeOut(duration: 0.15), value: focused)

            if let error {
                Label(error, systemImage: "exclamationmark.circle.fill")
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .transition(.opacity)
            } else if let hint {
                Text(hint).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private var shape: RoundedRectangle { RoundedRectangle(cornerRadius: 14, style: .continuous) }

    private var borderColor: Color { error != nil ? .red : (focused ? .ferryAccent : .clear) }
}

extension FerryField where Accessory == EmptyView {
    init(_ label: String, icon: String, text: Binding<String>, prompt: String = "", error: String? = nil, hint: String? = nil, axis: Axis = .horizontal, monospaced: Bool = false, focus: FocusState<Bool>.Binding) {
        self.init(label: label, icon: icon, text: text, prompt: prompt, error: error, hint: hint, axis: axis, monospaced: monospaced, focus: focus) { EmptyView() }
    }
}

/// Full-width primary action that swaps its title for a spinner while busy.
struct PrimaryButton: View {
    let title: String
    var systemImage: String?
    var busy = false
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                ProgressView().opacity(busy ? 1 : 0)
                Group {
                    if let systemImage { Label(title, systemImage: systemImage) } else { Text(title) }
                }
                .opacity(busy ? 0 : 1)
            }
            .font(.headline)
            .frame(maxWidth: .infinity, minHeight: 30)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(!enabled || busy)
    }
}

/// An error shown in place, instead of an alert that hides the form.
struct ErrorCard: View {
    let message: String
    var onDismiss: (() -> Void)?

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            Text(message).font(.callout).frame(maxWidth: .infinity, alignment: .leading)
            if let onDismiss {
                Button("Dismiss", systemImage: "xmark", action: onDismiss)
                    .labelStyle(.iconOnly)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .buttonStyle(.plain)
            }
        }
        .padding(14)
        .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .transition(.move(edge: .top).combined(with: .opacity))
    }
}

/// A selectable card for choosing between a few options.
struct ChoiceCard: View {
    let title: String
    let subtitle: String
    let icon: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.title3)
                    .foregroundStyle(selected ? Color.ferryAccent : .secondary)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body.weight(.semibold)).foregroundStyle(.primary)
                    Text(subtitle).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 8)
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(selected ? Color.ferryAccent : Color.secondary.opacity(0.5))
            }
            .padding(14)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(selected ? Color.ferryAccent : .clear, lineWidth: 1.5)
            }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// "—— or ——"
struct OrDivider: View {
    var body: some View {
        HStack(spacing: 12) {
            Rectangle().fill(.separator).frame(height: 1)
            Text("or").font(.footnote).foregroundStyle(.secondary)
            Rectangle().fill(.separator).frame(height: 1)
        }
    }
}
