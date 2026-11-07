#ifndef KINDLE_WS_PROTOCOL_H
#define KINDLE_WS_PROTOCOL_H

#include <cstdint>
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
std::string buildAgendaListJson(int id, const std::string& span = "week");
std::string buildInkGetJson(int id, const std::string& nodeId, const std::string& fileName);
std::string buildInkCommitJson(int id, const InkCommitPayload& payload);
std::string buildFlagFlashcardJson(int id, const std::string& nodeId, const std::string& fileName, bool flagged);

// Lightweight JSON extractors for golden fixture & server responses
std::string extractJsonStringField(const std::string& json, const std::string& key);
int extractJsonIntField(const std::string& json, const std::string& key, int defaultVal = 0);
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

    // Commits a sketch immediately if connected, or persists to the offline queue on disk
    bool commitOrEnqueueInk(const InkCommitPayload& payload);

    // Flushes any queued offline commits over the active connection (or via custom sender in tests)
    int replayOfflineQueue();

    size_t pendingQueueCount() const;
    std::vector<InkCommitPayload> readQueuedCommits() const;
    void clearOfflineQueue();

    const std::string& host() const { return m_host; }
    int port() const { return m_port; }
    const std::string& token() const { return m_token; }
    const std::vector<std::string>& conflictedFiles() const { return m_conflicts; }
    void recordConflict(const std::string& fileName);

private:
    bool sendWebSocketTextFrame(const std::string& text);
    bool receiveWebSocketTextFrame(std::string* outText, int timeoutMs = 3000);
    std::string queueFilePath() const;
    bool saveQueuedCommits(const std::vector<InkCommitPayload>& items) const;

    std::string m_queueDir;
    std::string m_host  = "127.0.0.1";
    int m_port          = 9876;
    std::string m_path  = "/org-writenotes";
    std::string m_token;
    int m_sockFd        = -1;
    bool m_authenticated = false;
    int m_nextRpcId     = 1;
    std::vector<std::string> m_conflicts;
};

} // namespace orgwritenotes::kindle

#endif // KINDLE_WS_PROTOCOL_H

