import Foundation
import OrgWriteNotesKit

#if canImport(SwiftUI)
import SwiftUI

@main
public struct OrgWriteNotesIPadApp: App {
    @StateObject private var syncClient = EmacsSyncClient()
    @State private var connectionConfig = ConnectionConfig(host: "127.0.0.1", port: 9876, token: "")
    @State private var showConnectSheet = false

    public init() {}

    public var body: some Scene {
        WindowGroup {
            IPadMainWorkspaceView(
                syncClient: syncClient,
                connectionConfig: $connectionConfig,
                showConnectSheet: $showConnectSheet
            )
            .onOpenURL { url in
                if let parsed = ConnectionConfig.parse(urlOrString: url.absoluteString) {
                    connectionConfig = parsed
                    syncClient.connect(config: parsed)
                }
            }
        }
    }
}

public struct IPadMainWorkspaceView: View {
    @ObservedObject var syncClient: EmacsSyncClient
    @Binding var connectionConfig: ConnectionConfig
    @Binding var showConnectSheet: Bool

    @State private var toolMode: WriteToolMode = .pen
    @State private var penColorArgb: UInt32 = 0xFF1E293B
    @State private var penWidth: Float = 2.8
    @State private var penTipType: WritePenTipType = .round
    @State private var stylusOnlyMode: Bool = true
    @State private var zoomedSketchNode: OrgBlockNode?
    @State private var standaloneSession = WriteSessionBridge(width: 1200, height: 800, xRuling: 0, yRuling: 40)
    @State private var showRadialPalette: Bool = false
    @State private var radialPalettePosition: CGPoint = CGPoint(x: 400, y: 300)

    private let colorPresets: [(String, UInt32)] = [
        ("Ink Black", 0xFF1E293B),
        ("Royal Blue", 0xFF1D4ED8),
        ("Crimson", 0xFFB91C1C),
        ("Forest", 0xFF15803D),
        ("Purple", 0xFF6D28D9),
        ("Amber", 0xFFD97706)
    ]

    public init(
        syncClient: EmacsSyncClient,
        connectionConfig: Binding<ConnectionConfig>,
        showConnectSheet: Binding<Bool>
    ) {
        self.syncClient = syncClient
        self._connectionConfig = connectionConfig
        self._showConnectSheet = showConnectSheet
    }

    public var body: some View {
        NavigationSplitView {
            List {
                Section("Connection") {
                    HStack {
                        Circle()
                            .fill(syncClient.state == .connected ? Color.green : Color.orange)
                            .frame(width: 10, height: 10)
                        Text(syncClient.statusMessage)
                            .font(.caption)
                    }
                    Button("Connect to Emacs…") {
                        showConnectSheet = true
                    }
                    if syncClient.offlineStore.pendingCount > 0 {
                        Text("\(syncClient.offlineStore.pendingCount) offline sketch edit(s) queued")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }

                Section("Outline (\(syncClient.snapshot.title))") {
                    ForEach(syncClient.snapshot.blocks.filter { $0.type == "heading" }) { heading in
                        HStack {
                            Text(String(repeating: "• ", count: max(0, (heading.level ?? 1) - 1)) + (heading.title ?? ""))
                                .font(.subheadline)
                            Spacer()
                            if let todo = heading.todoState {
                                Text(todo)
                                    .font(.caption2.bold())
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(Color.blue.opacity(0.15))
                                    .cornerRadius(4)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Org-WriteNotes")
        } detail: {
            ZStack {
                VStack(spacing: 0) {
                    pencilToolbar
                    Divider()
                    if syncClient.snapshot.blocks.isEmpty {
                        // Standalone / Quick Scratchpad canvas when not yet connected to an Org buffer
                        #if canImport(UIKit)
                        WriteCanvasSwiftUIView(
                            session: standaloneSession,
                            toolMode: $toolMode,
                            penColorArgb: $penColorArgb,
                            penWidth: $penWidth,
                            penTipType: $penTipType,
                            stylusOnlyMode: $stylusOnlyMode,
                            onCommit: { _ in },
                            onRadialMenu: { pt in
                                radialPalettePosition = pt
                                showRadialPalette.toggle()
                            }
                        )
                        #else
                        Text("Standalone Write Session Ready (\(standaloneSession.width)x\(standaloneSession.height))")
                        #endif
                    } else {
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 16) {
                                ForEach(syncClient.snapshot.blocks) { block in
                                    blockRow(for: block)
                                }
                            }
                            .padding(20)
                        }
                    }
                }

                if showRadialPalette {
                    radialMenuOverlay
                }
            }
            .sheet(isPresented: $showConnectSheet) {
                connectSheetView
            }
            .sheet(item: Binding(
                get: { syncClient.activeConflict.map { ConflictWrapper(info: $0) } },
                set: { _ in }
            )) { wrapper in
                conflictResolutionSheet(wrapper.info)
            }
        }
    }

    private var pencilToolbar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 12) {
                Group {
                    Button(action: { toolMode = .pen }) {
                        Label("Pen", systemImage: "pencil.tip")
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(toolMode == .pen ? .blue : .gray)

                    Menu {
                        Button("Round Nib") { penTipType = .round; toolMode = .pen }
                        Button("Flat Calligraphy") { penTipType = .flat; toolMode = .pen }
                        Button("Chisel (Pencil Pro Roll)") { penTipType = .chisel; toolMode = .pen }
                    } label: {
                        Label("Nib", systemImage: "scribble.variable")
                    }

                    Menu {
                        Button("Stroke Eraser") { toolMode = .eraserStroke }
                        Button("Ruled Line Eraser") { toolMode = .eraserRuledLine }
                        Button("Free Segment Eraser") { toolMode = .eraserFreeSegment }
                    } label: {
                        Label("Eraser", systemImage: "eraser")
                    }

                    Menu {
                        Button("Lasso Select") { toolMode = .selectLasso }
                        Button("Rectangle Select") { toolMode = .selectRect }
                        Button("Ruled Line Select") { toolMode = .selectRuled }
                    } label: {
                        Label("Select", systemImage: "lasso")
                    }

                    Menu {
                        Button("Vertical Space") { toolMode = .insertSpaceVertical }
                        Button("Horizontal Space") { toolMode = .insertSpaceHorizontal }
                        Button("Ruled Space (Word Reflow)") { toolMode = .insertSpaceRuled }
                    } label: {
                        Label("Space", systemImage: "arrow.up.and.down.text.horizontal")
                    }
                }

                Divider().frame(height: 24)

                ForEach(colorPresets, id: \.0) { name, argb in
                    Button(action: {
                        penColorArgb = argb
                        toolMode = .pen
                    }) {
                        Text(name)
                            .font(.caption)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(penColorArgb == argb ? Color.accentColor.opacity(0.2) : Color.clear)
                            .cornerRadius(6)
                    }
                }

                Divider().frame(height: 24)

                Button(action: { _ = standaloneSession.undo() }) {
                    Image(systemName: "arrow.uturn.backward")
                }
                Button(action: { _ = standaloneSession.redo() }) {
                    Image(systemName: "arrow.uturn.forward")
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
    }

    @ViewBuilder
    private func blockRow(for block: OrgBlockNode) -> some View {
        switch block.type {
        case "heading":
            Text(block.title ?? "")
                .font(block.level == 1 ? .title : (block.level == 2 ? .title2 : .headline))
                .padding(.top, 8)
        case "ink":
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Label(block.fileName ?? "sketch.svgz", systemImage: "ApplePencil")
                        .font(.caption.monospaced())
                        .foregroundColor(.secondary)
                    Spacer()
                }
                let blockSession = makeSession(for: block)
                #if canImport(UIKit)
                WriteCanvasSwiftUIView(
                    session: blockSession,
                    toolMode: $toolMode,
                    penColorArgb: $penColorArgb,
                    penWidth: $penWidth,
                    penTipType: $penTipType,
                    stylusOnlyMode: $stylusOnlyMode,
                    onCommit: { svgzData in
                        syncClient.commitInk(
                            nodeId: block.nodeId,
                            fileName: block.fileName ?? "sketch.svgz",
                            svgzData: svgzData,
                            baseEtag: block.etag,
                            width: blockSession.width,
                            height: blockSession.height
                        )
                    },
                    onLivePreview: { svgzData in
                        syncClient.sendLivePreview(
                            nodeId: block.nodeId,
                            fileName: block.fileName ?? "sketch.svgz",
                            svgzData: svgzData
                        )
                    },
                    onRadialMenu: { pt in
                        radialPalettePosition = pt
                        showRadialPalette.toggle()
                    }
                )
                .frame(height: CGFloat(block.height ?? 420))
                .cornerRadius(10)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.secondary.opacity(0.25), lineWidth: 1))
                #endif
            }
        default:
            Text(block.text ?? "")
                .font(.body)
        }
    }

    private func makeSession(for block: OrgBlockNode) -> WriteSessionBridge {
        let s = WriteSessionBridge(
            width: block.width ?? 1080,
            height: block.height ?? 420,
            xRuling: 0,
            yRuling: 40
        )
        if let b64 = block.svgzBase64, let data = Data(base64Encoded: b64) {
            _ = s.loadSvgzData(data)
        }
        return s
    }

    private var radialMenuOverlay: some View {
        VStack(spacing: 10) {
            Text("Apple Pencil Quick Palette")
                .font(.caption.bold())
            HStack(spacing: 12) {
                Button("Pen") { toolMode = .pen; showRadialPalette = false }
                Button("Chisel") { penTipType = .chisel; toolMode = .pen; showRadialPalette = false }
                Button("Eraser") { toolMode = .eraserStroke; showRadialPalette = false }
                Button("Lasso") { toolMode = .selectLasso; showRadialPalette = false }
                Button("Space") { toolMode = .insertSpaceRuled; showRadialPalette = false }
            }
            .buttonStyle(.bordered)
        }
        .padding(14)
        .background(.regularMaterial)
        .cornerRadius(14)
        .shadow(radius: 8)
        .position(radialPalettePosition)
    }

    private var connectSheetView: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Connect iPad to Emacs")
                .font(.title2.bold())
            Text("Connect via USB (`iproxy 9876 9876` -> 127.0.0.1:9876) or Wi-Fi / Tailscale LAN IP.")
                .font(.caption)
                .foregroundColor(.secondary)

            TextField("Host (e.g. 127.0.0.1 or 192.168.1.50)", text: $connectionConfig.host)
                .textFieldStyle(.roundedBorder)
            TextField("Token (from M-x org-writenotes-connect-ipad)", text: $connectionConfig.token)
                .textFieldStyle(.roundedBorder)

            HStack {
                Button("Cancel") { showConnectSheet = false }
                Spacer()
                Button("Connect") {
                    syncClient.connect(config: connectionConfig)
                    showConnectSheet = false
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(24)
        .frame(minWidth: 380)
    }

    private func conflictResolutionSheet(_ info: SketchConflictInfo) -> some View {
        VStack(spacing: 16) {
            Text("Sketch Sync Conflict: \(info.fileName)")
                .font(.headline)
            Text("Both Emacs and this iPad modified \(info.fileName). Choose which version to keep:")
                .font(.subheadline)
                .foregroundColor(.secondary)
            HStack(spacing: 16) {
                Button("Keep Emacs Version") {
                    syncClient.resolveConflict(nodeId: info.nodeId, fileName: info.fileName, choice: "emacs")
                }
                .buttonStyle(.bordered)

                Button("Keep iPad Version") {
                    syncClient.resolveConflict(nodeId: info.nodeId, fileName: info.fileName, choice: "tablet")
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(24)
    }
}

private struct ConflictWrapper: Identifiable {
    let info: SketchConflictInfo
    var id: String { "\(info.nodeId):\(info.fileName)" }
}
#else
@main
public struct OrgWriteNotesIPadApp {
    public static func main() {
        let session = WriteSessionBridge(width: 1080, height: 540, xRuling: 0, yRuling: 40)
        print("Org-WriteNotes iPadOS Engine CLI initialized: \(session.width)x\(session.height)")
    }
}
#endif

