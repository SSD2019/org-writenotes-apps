#ifndef KINDLE_WS_PROTOCOL_H
#define KINDLE_WS_PROTOCOL_H

#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace orgwritenotes::kindle {

struct OrgNodeSummary {
    std::string nodeId;
    std::string filePath;
    std::string title;
    std::string todoKeyword;
    std::string scheduled;
    int level       = 1;
    int sketchCount = 0;
};

struct InkBlockInfo {
    std::string blockId;
    std::string fileName;
    std::string linkText;
    std::string etag;
    int width          = 1200;
    int height         = 600;
    int xRuling        = 40;
    int yRuling        = 40;
    std::string svgzBase64;
};

struct OrgNodeSnapshotData {
    std::string nodeId;
    std::string etag;
    std::string filePath;
    std::string title;
    std::string todoKeyword;
    int level          = 1;
    std::vector<InkBlockInfo> inkBlocks;
};

struct InkCommitPayload {
    std::string nodeId;
    std::string fileName;
    int width          = 1200;
    int height         = 600;
    std::string baseEtag;
    std::string svgzBase64;
    std::string ocrText;
};

struct GoldenFixtureData {
    int protocolVersion = 2;
    std::string handshakeMethod;
    std::string clientName;
    std::string version;
    std::string token;
    InkCommitPayload inkCommit;
};

// Base64 helpers for .svgz wire transport
std::string base64Encode(const uint8_t* data, size_t len);
std::vector<uint8_t> base64Decode(const std::string& encoded);

// JSON-RPC 2.0 message builders matching docs/protocol-spec.md
std::string buildSessionHelloJson(
    int id,
    const std::string& clientName,
    const std::string& version,
    int protocolVersion,
    const std::string& token
);

std::string buildOutlineListJson(int id);
std::string buildNodeListJson(int id, const std::string& filter = "buffer_outline", const std::string& query = "");
std::string buildNodeGetJson(int id, const std::string& nodeId);
std::string buildInsertInkBlockJson(int id, const std::string& nodeId, int width = 1200, int height = 600, int xRuling = 0, int yRuling = 48);
std::string buildAgendaListJson(int id, const std::string& span = "week");
std::string buildInkGetJson(int id, const std::string& nodeId, const std::string& fileName);
std::string buildInkCommitJson(int id, const InkCommitPayload& payload);
std::string buildFlagFlashcardJson(int id, const std::string& nodeId, const std::string& fileName, bool flagged);

// Lightweight JSON extractors for golden fixture & server responses
std::string extractJsonStringField(const std::string& json, const std::string& key);
int extractJsonIntField(const std::string& json, const std::string& key, int defaultVal = 0);
std::string extractJsonObjectField(const std::string& json, const std::string& key);
std::vector<std::string> extractJsonArrayObjects(const std::string& json, const std::string& key);
bool parseNodeSnapshotJson(const std::string& snapshotObjJson, OrgNodeSnapshotData* outSnapshot);
std::vector<OrgNodeSummary> parseNodeSummariesJson(const std::string& responseJson);
bool parseGoldenFixtureFile(const std::string& filePath, GoldenFixtureData* outFixture);

// Persistent Offline Sync Queue + RFC 6455 WebSocket Client
class WriteNotesSyncClient {
public:
    explicit WriteNotesSyncClient(const std::string& queueDir = "/mnt/us/org-writenotes/queue");
    ~WriteNotesSyncClient();

    void setEndpoint(const std::string& host, int port, const std::string& path, const std::string& token);
    bool loadConfigFile(const std::string& configJsonPath);

    bool connectAndHandshake();
    void disconnect();
    bool isConnected() const { return m_sockFd >= 0 && m_authenticated; }

    // Fetch outline/pushed nodes and node snapshots from the Emacs server
    bool fetchNodeList(const std::string& filter, std::vector<OrgNodeSummary>* outNodes);
    bool fetchNodeSnapshot(const std::string& nodeId, OrgNodeSnapshotData* outSnapshot);
    bool insertInkBlock(
        const std::string& nodeId,
        int width,
        int height,
        int xRuling,
        int yRuling,
        OrgNodeSnapshotData* outSnapshot
    );

    // Polls for unsolicited server push notifications (emacs.focusNode, emacs.nodeModified, emacs.zoomInkBlock)
    bool pollServerNotification(std::string* outMethod, std::string* outParamsJson, int timeoutMs = 0);

    // Commits a sketch immediately if connected, or persists to the offline queue on disk
    bool commitOrEnqueueInk(const InkCommitPayload& payload);

    // Flushes any queued offline commits over the active connection (or via custom sender in tests)
    int replayOfflineQueue();

    size_t pendingQueueCount() const;
    std::vector<InkCommitPayload> readQueuedCommits() const;
    void clearOfflineQueue();

    const std::string& host() const { return m_host; }
    const std::string& wifiHost() const { return m_wifiHost; }
    void setWifiHost(const std::string& wifiHost) { m_wifiHost = wifiHost; }
    int port() const { return m_port; }
    const std::string& token() const { return m_token; }
    const std::string& serverActiveNodeId() const { return m_serverActiveNodeId; }
    const std::vector<std::string>& conflictedFiles() const { return m_conflicts; }
    void recordConflict(const std::string& fileName);
    void clearConflict(const std::string& fileName);
    std::string knownEtagForFile(const std::string& fileName) const;
    void setKnownEtagForFile(const std::string& fileName, const std::string& etag);

private:
    bool sendWebSocketTextFrame(const std::string& text);
    bool receiveWebSocketTextFrame(std::string* outText, int timeoutMs = 3000);
    bool sendRpcRequest(int id, const std::string& requestJson, std::string* outResponseJson, int timeoutMs = 3000);
    std::string queueFilePath() const;
    bool saveQueuedCommits(const std::vector<InkCommitPayload>& items) const;

    std::string m_queueDir;
    std::string m_host  = "127.0.0.1";
    std::string m_wifiHost;
    int m_port          = 9876;
    std::string m_path  = "/org-writenotes";
    std::string m_token;
    std::string m_serverActiveNodeId;
    int m_sockFd        = -1;
    bool m_authenticated = false;
    int m_nextRpcId     = 1;
    std::vector<std::string> m_conflicts;
    std::vector<std::string> m_pendingNotifications;
    std::map<std::string, std::string> m_knownEtags;
};

} // namespace orgwritenotes::kindle

#endif // KINDLE_WS_PROTOCOL_H

