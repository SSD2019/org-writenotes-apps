import Foundation
#if canImport(Network)
import Network
#endif

/// Newline-delimited JSON-RPC 2.0 client for communicating with `org-writenotes.el` in Emacs.
/// Supports both USB (`iproxy 9876 9876`) and LAN / Tailscale TCP connections on iPadOS.
public final class EmacsSyncClient: ObservableObject, @unchecked Sendable {
    public enum ConnectionState: String, Equatable, Sendable {
        case disconnected
        case connecting
        case connected
        case error
    }

    @Published public private(set) var state: ConnectionState = .disconnected
    @Published public private(set) var snapshot: OrgDocumentSnapshot = OrgDocumentSnapshot()
    @Published public private(set) var serverCapabilities: [String] = []
    @Published public private(set) var activeConflict: SketchConflictInfo?
    @Published public private(set) var statusMessage: String = "Disconnected"

    public let offlineStore: OfflineSyncStore
    private var nextRequestId: Int = 1
    private let lock = NSLock()
    private var receiveBuffer = Data()
    private var pendingCallbacks: [Int: (Result<[String: Any], Error>) -> Void] = [:]

    #if canImport(Network)
    private var connection: NWConnection?
    private let networkQueue = DispatchQueue(label: "com.orgwritenotes.ios.network", qos: .userInteractive)
    #endif

    public init(offlineStore: OfflineSyncStore = OfflineSyncStore()) {
        self.offlineStore = offlineStore
        if let cached = offlineStore.loadSnapshot() {
            self.snapshot = cached
        }
    }

    // MARK: - JSON-RPC Framing (Testable across all platforms)

    public func encodeRpcRequest(id: Int, method: String, params: [String: Any]) -> Data {
        let payload: [String: Any] = [
            "jsonrpc": "2.0",
            "id": id,
            "method": method,
            "params": params
        ]
        guard var data = try? JSONSerialization.data(withJSONObject: payload, options: []) else {
            return Data()
        }
        data.append(0x0A) // newline delimiter
        return data
    }

    public func processIncomingLine(_ lineData: Data) {
        guard !lineData.isEmpty,
              let json = try? JSONSerialization.jsonObject(with: lineData, options: []) as? [String: Any] else {
            return
        }

        // Response to a client request
        if let id = json["id"] as? Int {
            lock.lock()
            let cb = pendingCallbacks.removeValue(forKey: id)
            lock.unlock()

            if let errObj = json["error"] as? [String: Any] {
                let msg = (errObj["message"] as? String) ?? "RPC Error"
                cb?(.failure(NSError(domain: "OrgWriteNotesRPC", code: -1, userInfo: [NSLocalizedDescriptionKey: msg])))
            } else if let result = json["result"] as? [String: Any] {
                cb?(.success(result))
            } else {
                cb?(.success([:]))
            }
            return
        }

        // Server-initiated notification
        if let method = json["method"] as? String,
           let params = json["params"] as? [String: Any] {
            handleServerNotification(method: method, params: params)
        }
    }

    private func handleServerNotification(method: String, params: [String: Any]) {
        switch method {
        case "doc.snapshotChanged":
            if let snap = Self.parseSnapshot(from: params) {
                offlineStore.saveSnapshot(snap)
                DispatchQueue.main.async {
                    self.snapshot = snap
                }
            }
        case "ink.externalChange":
            requestSnapshot()
        default:
            break
        }
    }

    public static func parseSnapshot(from dict: [String: Any]) -> OrgDocumentSnapshot? {
        let filePath = (dict["filePath"] as? String) ?? (dict["file"] as? String) ?? ""
        let title = (dict["title"] as? String) ?? URL(fileURLWithPath: filePath).lastPathComponent
        let rawBlocks = (dict["blocks"] as? [[String: Any]]) ?? []
        var blocks: [OrgBlockNode] = []
        blocks.reserveCapacity(rawBlocks.count)
        for b in rawBlocks {
            let nodeId = (b["nodeId"] as? String) ?? (b["id"] as? String) ?? UUID().uuidString
            let type = (b["type"] as? String) ?? "paragraph"
            blocks.append(OrgBlockNode(
                nodeId: nodeId,
                type: type,
                level: b["level"] as? Int,
                title: b["title"] as? String,
                todoState: b["todoState"] as? String,
                tags: b["tags"] as? [String],
                text: b["text"] as? String,
                fileName: b["fileName"] as? String,
                svgzBase64: b["svgzBase64"] as? String,
                etag: b["etag"] as? String,
                width: b["width"] as? Int,
                height: b["height"] as? Int
            ))
        }
        return OrgDocumentSnapshot(filePath: filePath, title: title.isEmpty ? "Org Buffer" : title, blocks: blocks)
    }

    // MARK: - Network Connection Lifecycle

    public func connect(config: ConnectionConfig) {
        #if canImport(Network)
        disconnect()
        DispatchQueue.main.async {
            self.state = .connecting
            self.statusMessage = "Connecting to \(config.host):\(config.port)…"
        }
        let host = NWEndpoint.Host(config.host)
        guard let port = NWEndpoint.Port(rawValue: config.port) else { return }
        let conn = NWConnection(host: host, port: port, using: .tcp)
        self.connection = conn

        conn.stateUpdateHandler = { [weak self] newState in
            guard let self = self else { return }
            switch newState {
            case .ready:
                DispatchQueue.main.async {
                    self.state = .connected
                    self.statusMessage = "Connected to \(config.host):\(config.port)"
                }
                self.receiveLoop(on: conn)
                self.sendHello(token: config.token)
            case .failed(let err):
                DispatchQueue.main.async {
                    self.state = .error
                    self.statusMessage = "Connection failed: \(err.localizedDescription)"
                }
            case .cancelled:
                DispatchQueue.main.async {
                    self.state = .disconnected
                    self.statusMessage = "Disconnected"
                }
            default:
                break
            }
        }
        conn.start(queue: networkQueue)
        #endif
    }

    public func disconnect() {
        #if canImport(Network)
        connection?.cancel()
        connection = nil
        #endif
        DispatchQueue.main.async {
            self.state = .disconnected
            self.statusMessage = "Disconnected"
        }
    }

    #if canImport(Network)
    private func receiveLoop(on conn: NWConnection) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            if let data = data, !data.isEmpty {
                self.receiveBuffer.append(data)
                while let nlIndex = self.receiveBuffer.firstIndex(of: 0x0A) {
                    let line = self.receiveBuffer.subdata(in: 0..<nlIndex)
                    self.receiveBuffer.removeSubrange(0...nlIndex)
                    self.processIncomingLine(line)
                }
            }
            if !isComplete && error == nil {
                self.receiveLoop(on: conn)
            }
        }
    }
    #endif

    // MARK: - Protocol v2 RPC Calls

    public func call(
        method: String,
        params: [String: Any],
        completion: ((Result<[String: Any], Error>) -> Void)? = nil
    ) {
        lock.lock()
        let id = nextRequestId
        nextRequestId += 1
        if let completion = completion {
            pendingCallbacks[id] = completion
        }
        lock.unlock()

        let frame = encodeRpcRequest(id: id, method: method, params: params)
        #if canImport(Network)
        connection?.send(content: frame, completion: .contentProcessed { _ in })
        #endif
    }

    private func sendHello(token: String) {
        call(
            method: "session.hello",
            params: [
                "client": "org-writenotes-ipados",
                "protocolVersion": 2,
                "token": token
            ]
        ) { [weak self] result in
            guard let self = self else { return }
            if case .success(let res) = result {
                let caps = (res["capabilities"] as? [String]) ?? []
                DispatchQueue.main.async {
                    self.serverCapabilities = caps
                }
                self.flushOfflineQueue()
                self.requestSnapshot()
            }
        }
    }

    public func requestSnapshot() {
        call(method: "doc.getSnapshot", params: [:]) { [weak self] result in
            guard let self = self else { return }
            if case .success(let res) = result, let snap = Self.parseSnapshot(from: res) {
                self.offlineStore.saveSnapshot(snap)
                DispatchQueue.main.async {
                    self.snapshot = snap
                }
            }
        }
    }

    public func commitInk(
        nodeId: String,
        fileName: String,
        svgzData: Data,
        baseEtag: String?,
        width: Int,
        height: Int
    ) {
        let b64 = svgzData.base64EncodedString()
        guard state == .connected else {
            offlineStore.enqueueCommit(PendingInkCommit(
                nodeId: nodeId,
                fileName: fileName,
                svgzBase64: b64,
                baseEtag: baseEtag,
                width: width,
                height: height
            ))
            return
        }

        var params: [String: Any] = [
            "nodeId": nodeId,
            "fileName": fileName,
            "svgzBase64": b64,
            "width": width,
            "height": height
        ]
        if let baseEtag = baseEtag {
            params["baseEtag"] = baseEtag
        }
        call(method: "ink.commit", params: params) { [weak self] result in
            guard let self = self else { return }
            if case .success(let res) = result {
                if let conflict = res["conflict"] as? Bool, conflict {
                    self.fetchConflictInfo(nodeId: nodeId, fileName: fileName)
                }
            }
        }
    }

    public func sendLivePreview(nodeId: String, fileName: String, svgzData: Data) {
        guard state == .connected else { return }
        call(
            method: "ink.livePreview",
            params: [
                "nodeId": nodeId,
                "fileName": fileName,
                "svgzBase64": svgzData.base64EncodedString()
            ]
        )
    }

    public func fetchConflictInfo(nodeId: String, fileName: String) {
        call(method: "ink.conflictInfo", params: ["nodeId": nodeId, "fileName": fileName]) { [weak self] result in
            guard let self = self, case .success(let res) = result else { return }
            let info = SketchConflictInfo(
                nodeId: nodeId,
                fileName: (res["fileName"] as? String) ?? fileName,
                conflictFile: (res["conflictFile"] as? String) ?? "",
                conflictCount: (res["conflictCount"] as? Int) ?? 1,
                emacsSvgzBase64: (res["emacsSvgzBase64"] as? String) ?? "",
                tabletSvgzBase64: (res["tabletSvgzBase64"] as? String) ?? "",
                etag: (res["etag"] as? String) ?? ""
            )
            DispatchQueue.main.async {
                self.activeConflict = info
            }
        }
    }

    public func resolveConflict(nodeId: String, fileName: String, choice: String, mergedSvgzBase64: String? = nil) {
        var params: [String: Any] = [
            "nodeId": nodeId,
            "fileName": fileName,
            "choice": choice
        ]
        if let merged = mergedSvgzBase64 {
            params["svgzBase64"] = merged
        }
        call(method: "ink.resolveConflict", params: params) { [weak self] _ in
            DispatchQueue.main.async {
                self?.activeConflict = nil
                self?.requestSnapshot()
            }
        }
    }

    public func flushOfflineQueue() {
        let pending = offlineStore.dequeueAllCommits()
        for item in pending {
            if let data = Data(base64Encoded: item.svgzBase64) {
                commitInk(
                    nodeId: item.nodeId,
                    fileName: item.fileName,
                    svgzData: data,
                    baseEtag: item.baseEtag,
                    width: item.width,
                    height: item.height
                )
            }
        }
    }
}

