import Foundation

/// A queued offline commit waiting to be flushed when the iPad reconnects to Emacs.
public struct PendingInkCommit: Codable, Equatable, Sendable {
    public var nodeId: String
    public var fileName: String
    public var svgzBase64: String
    public var baseEtag: String?
    public var width: Int
    public var height: Int
    public var timestamp: TimeInterval

    public init(
        nodeId: String,
        fileName: String,
        svgzBase64: String,
        baseEtag: String?,
        width: Int,
        height: Int,
        timestamp: TimeInterval = Date().timeIntervalSince1970
    ) {
        self.nodeId = nodeId
        self.fileName = fileName
        self.svgzBase64 = svgzBase64
        self.baseEtag = baseEtag
        self.width = width
        self.height = height
        self.timestamp = timestamp
    }
}

/// Persistent offline storage for iPadOS (Application Support directory + standalone `.svgz` files).
public final class OfflineSyncStore: @unchecked Sendable {
    private let baseDir: URL
    private let lock = NSLock()

    public init(customDirectory: URL? = nil) {
        if let customDirectory = customDirectory {
            self.baseDir = customDirectory
        } else {
            let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? URL(fileURLWithPath: NSTemporaryDirectory())
            self.baseDir = appSupport.appendingPathComponent("OrgWriteNotes", isDirectory: true)
        }
        try? FileManager.default.createDirectory(at: baseDir, withIntermediateDirectories: true)
    }

    private var snapshotURL: URL { baseDir.appendingPathComponent("cached_snapshot.json") }
    private var pendingQueueURL: URL { baseDir.appendingPathComponent("pending_commits.json") }

    public func saveSnapshot(_ snapshot: OrgDocumentSnapshot) {
        lock.lock()
        defer { lock.unlock() }
        if let data = try? JSONEncoder().encode(snapshot) {
            try? data.write(to: snapshotURL, options: .atomic)
        }
    }

    public func loadSnapshot() -> OrgDocumentSnapshot? {
        lock.lock()
        defer { lock.unlock() }
        guard let data = try? Data(contentsOf: snapshotURL) else { return nil }
        return try? JSONDecoder().decode(OrgDocumentSnapshot.self, from: data)
    }

    public func enqueueCommit(_ commit: PendingInkCommit) {
        lock.lock()
        defer { lock.unlock() }
        var queue = loadPendingCommitsUnlocked()
        // Coalesce multiple offline edits to the same (nodeId, fileName)
        if let idx = queue.firstIndex(where: { $0.nodeId == commit.nodeId && $0.fileName == commit.fileName }) {
            var updated = commit
            if updated.baseEtag == nil {
                updated.baseEtag = queue[idx].baseEtag
            }
            queue[idx] = updated
        } else {
            queue.append(commit)
        }
        savePendingCommitsUnlocked(queue)
    }

    public func dequeueAllCommits() -> [PendingInkCommit] {
        lock.lock()
        defer { lock.unlock() }
        let queue = loadPendingCommitsUnlocked()
        savePendingCommitsUnlocked([])
        return queue
    }

    public var pendingCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return loadPendingCommitsUnlocked().count
    }

    private func loadPendingCommitsUnlocked() -> [PendingInkCommit] {
        guard let data = try? Data(contentsOf: pendingQueueURL),
              let list = try? JSONDecoder().decode([PendingInkCommit].self, from: data) else {
            return []
        }
        return list
    }

    private func savePendingCommitsUnlocked(_ list: [PendingInkCommit]) {
        if let data = try? JSONEncoder().encode(list) {
            try? data.write(to: pendingQueueURL, options: .atomic)
        }
    }
}

