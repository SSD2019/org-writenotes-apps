import Foundation

/// Represents a block in the Emacs Org-mode buffer snapshot (`doc.getSnapshot`).
public struct OrgBlockNode: Identifiable, Codable, Equatable, Sendable {
    public var id: String { "\(nodeId):\(type):\(fileName ?? "")" }
    public var nodeId: String
    public var type: String // "heading", "paragraph", "ink", "code", "latex"
    public var level: Int?
    public var title: String?
    public var todoState: String?
    public var tags: [String]?
    public var text: String?
    public var fileName: String?
    public var svgzBase64: String?
    public var etag: String?
    public var width: Int?
    public var height: Int?

    public init(
        nodeId: String,
        type: String,
        level: Int? = nil,
        title: String? = nil,
        todoState: String? = nil,
        tags: [String]? = nil,
        text: String? = nil,
        fileName: String? = nil,
        svgzBase64: String? = nil,
        etag: String? = nil,
        width: Int? = nil,
        height: Int? = nil
    ) {
        self.nodeId = nodeId
        self.type = type
        self.level = level
        self.title = title
        self.todoState = todoState
        self.tags = tags
        self.text = text
        self.fileName = fileName
        self.svgzBase64 = svgzBase64
        self.etag = etag
        self.width = width
        self.height = height
    }
}

/// Snapshot of an active Org-mode buffer from Emacs.
public struct OrgDocumentSnapshot: Codable, Equatable, Sendable {
    public var filePath: String
    public var title: String
    public var blocks: [OrgBlockNode]

    public init(filePath: String = "", title: String = "Untitled.org", blocks: [OrgBlockNode] = []) {
        self.filePath = filePath
        self.title = title
        self.blocks = blocks
    }
}

/// Conflict details returned by `ink.conflictInfo` (A3 ETag conflict resolution).
public struct SketchConflictInfo: Codable, Equatable, Sendable {
    public var nodeId: String
    public var fileName: String
    public var conflictFile: String
    public var conflictCount: Int
    public var emacsSvgzBase64: String
    public var tabletSvgzBase64: String
    public var etag: String
}

/// Parses `orgwritenotes://connect?host=<ip>&port=<port>&token=<token>` QR / deep-link URLs.
public struct ConnectionConfig: Equatable, Codable, Sendable {
    public var host: String
    public var port: UInt16
    public var token: String

    public init(host: String = "127.0.0.1", port: UInt16 = 9876, token: String = "") {
        self.host = host
        self.port = port
        self.token = token
    }

    public static func parse(urlOrString: String) -> ConnectionConfig? {
        let trimmed = urlOrString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if let comps = URLComponents(string: trimmed),
           let scheme = comps.scheme?.lowercased(),
           scheme == "orgwritenotes" {
            let items = comps.queryItems ?? []
            let host = items.first(where: { $0.name == "host" })?.value ?? comps.host ?? "127.0.0.1"
            let portStr = items.first(where: { $0.name == "port" })?.value
            let port = portStr.flatMap { UInt16($0) } ?? comps.port.map { UInt16($0) } ?? 9876
            let token = items.first(where: { $0.name == "token" })?.value ?? ""
            return ConnectionConfig(host: host, port: port, token: token)
        }
        // Fallback: "host:port" or "host:port:token"
        let parts = trimmed.split(separator: ":").map(String.init)
        if parts.count == 1 {
            return ConnectionConfig(host: parts[0], port: 9876, token: "")
        } else if parts.count >= 2, let p = UInt16(parts[1]) {
            let tok = parts.count >= 3 ? parts[2] : ""
            return ConnectionConfig(host: parts[0], port: p, token: tok)
        }
        return nil
    }
}

