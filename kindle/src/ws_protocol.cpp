#include "ws_protocol.h"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>

#if defined(__linux__) || defined(__APPLE__)
#include <arpa/inet.h>
#include <cerrno>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#endif

namespace orgwritenotes::kindle {

namespace {

static constexpr char kBase64Table[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static std::string jsonEscape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:   out += c;      break;
        }
    }
    return out;
}

static bool ensureDirectoryExists(const std::string& dirPath) {
    if (dirPath.empty()) return false;
#if defined(__linux__) || defined(__APPLE__)
    std::string accum;
    for (size_t i = 0; i < dirPath.size(); ++i) {
        accum += dirPath[i];
        if (dirPath[i] == '/' || i + 1 == dirPath.size()) {
            if (!accum.empty() && accum != "/") {
                mkdir(accum.c_str(), 0755);
            }
        }
    }
    struct stat st{};
    return stat(dirPath.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
#else
    return false;
#endif
}

static std::string readFileToString(const std::string& path) {
    std::ifstream ifs(path, std::ios::in | std::ios::binary);
    if (!ifs) return "";
    std::ostringstream oss;
    oss << ifs.rdbuf();
    return oss.str();
}

} // namespace

std::string base64Encode(const uint8_t* data, size_t len) {
    if (!data || len == 0) return "";
    std::string out;
    out.reserve(((len + 2) / 3) * 4);
    for (size_t i = 0; i < len; i += 3) {
        uint32_t b0 = data[i];
        uint32_t b1 = (i + 1 < len) ? data[i + 1] : 0;
        uint32_t b2 = (i + 2 < len) ? data[i + 2] : 0;
        uint32_t triple = (b0 << 16) | (b1 << 8) | b2;

        out.push_back(kBase64Table[(triple >> 18) & 0x3F]);
        out.push_back(kBase64Table[(triple >> 12) & 0x3F]);
        out.push_back((i + 1 < len) ? kBase64Table[(triple >> 6) & 0x3F] : '=');
        out.push_back((i + 2 < len) ? kBase64Table[triple & 0x3F] : '=');
    }
    return out;
}

std::vector<uint8_t> base64Decode(const std::string& encoded) {
    static int8_t decTable[256];
    static bool initialized = false;
    if (!initialized) {
        std::memset(decTable, -1, sizeof(decTable));
        for (int i = 0; i < 64; ++i) {
            decTable[static_cast<unsigned char>(kBase64Table[i])] = static_cast<int8_t>(i);
        }
        initialized = true;
    }

    std::vector<uint8_t> out;
    out.reserve((encoded.size() * 3) / 4);
    uint32_t val = 0;
    int valb = -8;
    for (unsigned char c : encoded) {
        if (c == '=') break;
        int8_t d = decTable[c];
        if (d == -1) continue;
        val = (val << 6) + static_cast<uint32_t>(d);
        valb += 6;
        if (valb >= 0) {
            out.push_back(static_cast<uint8_t>((val >> valb) & 0xFF));
            valb -= 8;
        }
    }
    return out;
}

std::string buildSessionHelloJson(
    int id,
    const std::string& clientName,
    const std::string& version,
    int protocolVersion,
    const std::string& token
) {
    std::ostringstream oss;
    oss << "{\"jsonrpc\":\"2.0\",\"id\":" << id
        << ",\"method\":\"session.hello\",\"params\":{"
        << "\"clientName\":\"" << jsonEscape(clientName) << "\","
        << "\"version\":\"" << jsonEscape(version) << "\","
        << "\"protocolVersion\":" << protocolVersion << ","
        << "\"token\":\"" << jsonEscape(token) << "\"}}";
    return oss.str();
}

std::string buildOutlineListJson(int id) {
    std::ostringstream oss;
    oss << "{\"jsonrpc\":\"2.0\",\"id\":" << id
        << ",\"method\":\"outline.list\",\"params\":{}}";
    return oss.str();
}

std::string buildNodeListJson(int id, const std::string& filter, const std::string& query) {
    std::ostringstream oss;
    oss << "{\"jsonrpc\":\"2.0\",\"id\":" << id
        << ",\"method\":\"node.list\",\"params\":{"
        << "\"filter\":\"" << jsonEscape(filter) << "\","
        << "\"query\":\"" << jsonEscape(query) << "\"}}";
    return oss.str();
}

std::string buildNodeGetJson(int id, const std::string& nodeId) {
    std::ostringstream oss;
    oss << "{\"jsonrpc\":\"2.0\",\"id\":" << id
        << ",\"method\":\"node.get\",\"params\":{"
        << "\"nodeId\":\"" << jsonEscape(nodeId) << "\"}}";
    return oss.str();
}

std::string buildInsertInkBlockJson(
    int id,
    const std::string& nodeId,
    int width,
    int height,
    int xRuling,
    int yRuling
) {
    std::ostringstream oss;
    oss << "{\"jsonrpc\":\"2.0\",\"id\":" << id
        << ",\"method\":\"node.insertInkBlock\",\"params\":{"
        << "\"nodeId\":\"" << jsonEscape(nodeId) << "\","
        << "\"width\":" << width << ","
        << "\"height\":" << height << ","
        << "\"xRuling\":" << xRuling << ","
        << "\"yRuling\":" << yRuling << "}}";
    return oss.str();
}

std::string buildAgendaListJson(int id, const std::string& span) {
    std::ostringstream oss;
    oss << "{\"jsonrpc\":\"2.0\",\"id\":" << id
        << ",\"method\":\"agenda.list\",\"params\":{\"span\":\""
        << jsonEscape(span) << "\"}}";
    return oss.str();
}

std::string buildInkGetJson(int id, const std::string& nodeId, const std::string& fileName) {
    std::ostringstream oss;
    oss << "{\"jsonrpc\":\"2.0\",\"id\":" << id
        << ",\"method\":\"ink.get\",\"params\":{"
        << "\"nodeId\":\"" << jsonEscape(nodeId) << "\","
        << "\"fileName\":\"" << jsonEscape(fileName) << "\"}}";
    return oss.str();
}

std::string buildInkCommitJson(int id, const InkCommitPayload& payload) {
    std::ostringstream oss;
    oss << "{\"jsonrpc\":\"2.0\",\"id\":" << id
        << ",\"method\":\"ink.commit\",\"params\":{"
        << "\"nodeId\":\"" << jsonEscape(payload.nodeId) << "\","
        << "\"fileName\":\"" << jsonEscape(payload.fileName) << "\","
        << "\"width\":" << payload.width << ","
        << "\"height\":" << payload.height << ",";
    if (!payload.baseEtag.empty()) {
        oss << "\"baseEtag\":\"" << jsonEscape(payload.baseEtag) << "\",";
    }
    oss << "\"svgzBase64\":\"" << jsonEscape(payload.svgzBase64) << "\"";
    if (!payload.ocrText.empty()) {
        oss << ",\"ocrText\":\"" << jsonEscape(payload.ocrText) << "\"";
    }
    oss << "}}";
    return oss.str();
}

std::string buildFlagFlashcardJson(int id, const std::string& nodeId, const std::string& fileName, bool flagged) {
    std::ostringstream oss;
    oss << "{\"jsonrpc\":\"2.0\",\"id\":" << id
        << ",\"method\":\"node.flagFlashcard\",\"params\":{"
        << "\"nodeId\":\"" << jsonEscape(nodeId) << "\","
        << "\"fileName\":\"" << jsonEscape(fileName) << "\","
        << "\"flagged\":" << (flagged ? "true" : "false") << "}}";
    return oss.str();
}

std::string extractJsonStringField(const std::string& json, const std::string& key) {
    std::string needle = "\"" + key + "\"";
    size_t pos = json.find(needle);
    while (pos != std::string::npos) {
        size_t colon = json.find(':', pos + needle.size());
        if (colon == std::string::npos) return "";
        size_t p = colon + 1;
        while (p < json.size() && std::isspace(static_cast<unsigned char>(json[p]))) ++p;
        if (p < json.size() && json[p] == '"') {
            ++p;
            std::string val;
            while (p < json.size()) {
                if (json[p] == '\\' && p + 1 < json.size()) {
                    char next = json[p + 1];
                    if (next == '"' || next == '\\' || next == '/') val.push_back(next);
                    else if (next == 'n') val.push_back('\n');
                    else if (next == 'r') val.push_back('\r');
                    else if (next == 't') val.push_back('\t');
                    else val.push_back(next);
                    p += 2;
                } else if (json[p] == '"') {
                    return val;
                } else {
                    val.push_back(json[p++]);
                }
            }
            return val;
        }
        pos = json.find(needle, pos + needle.size());
    }
    return "";
}

int extractJsonIntField(const std::string& json, const std::string& key, int defaultVal) {
    std::string needle = "\"" + key + "\"";
    size_t pos = json.find(needle);
    while (pos != std::string::npos) {
        size_t colon = json.find(':', pos + needle.size());
        if (colon == std::string::npos) return defaultVal;
        size_t p = colon + 1;
        while (p < json.size() && std::isspace(static_cast<unsigned char>(json[p]))) ++p;
        if (p < json.size() && (std::isdigit(static_cast<unsigned char>(json[p])) || json[p] == '-')) {
            return std::atoi(json.c_str() + p);
        }
        pos = json.find(needle, pos + needle.size());
    }
    return defaultVal;
}

std::string extractJsonObjectField(const std::string& json, const std::string& key) {
    std::string needle = "\"" + key + "\"";
    size_t pos = json.find(needle);
    while (pos != std::string::npos) {
        size_t colon = json.find(':', pos + needle.size());
        if (colon == std::string::npos) return "";
        size_t p = colon + 1;
        while (p < json.size() && std::isspace(static_cast<unsigned char>(json[p]))) ++p;
        if (p < json.size() && json[p] == '{') {
            size_t start = p;
            int depth = 0;
            bool inStr = false;
            while (p < json.size()) {
                char c = json[p];
                if (inStr) {
                    if (c == '\\' && p + 1 < json.size()) {
                        p += 2;
                        continue;
                    } else if (c == '"') {
                        inStr = false;
                    }
                } else {
                    if (c == '"') {
                        inStr = true;
                    } else if (c == '{') {
                        ++depth;
                    } else if (c == '}') {
                        --depth;
                        if (depth == 0) {
                            return json.substr(start, p - start + 1);
                        }
                    }
                }
                ++p;
            }
            return "";
        }
        pos = json.find(needle, pos + needle.size());
    }
    return "";
}

std::vector<std::string> extractJsonArrayObjects(const std::string& json, const std::string& key) {
    std::vector<std::string> items;
    std::string needle = "\"" + key + "\"";
    size_t pos = json.find(needle);
    while (pos != std::string::npos) {
        size_t colon = json.find(':', pos + needle.size());
        if (colon == std::string::npos) return items;
        size_t p = colon + 1;
        while (p < json.size() && std::isspace(static_cast<unsigned char>(json[p]))) ++p;
        if (p < json.size() && json[p] == '[') {
            ++p;
            int arrDepth = 1;
            int objDepth = 0;
            size_t objStart = 0;
            bool inStr = false;
            while (p < json.size() && arrDepth > 0) {
                char c = json[p];
                if (inStr) {
                    if (c == '\\' && p + 1 < json.size()) {
                        p += 2;
                        continue;
                    } else if (c == '"') {
                        inStr = false;
                    }
                } else {
                    if (c == '"') {
                        inStr = true;
                    } else if (c == '[' && objDepth == 0) {
                        ++arrDepth;
                    } else if (c == ']' && objDepth == 0) {
                        --arrDepth;
                        if (arrDepth == 0) break;
                    } else if (c == '{') {
                        if (objDepth == 0) objStart = p;
                        ++objDepth;
                    } else if (c == '}') {
                        --objDepth;
                        if (objDepth == 0) {
                            items.push_back(json.substr(objStart, p - objStart + 1));
                        }
                    }
                }
                ++p;
            }
            return items;
        }
        pos = json.find(needle, pos + needle.size());
    }
    return items;
}

bool parseNodeSnapshotJson(const std::string& snapshotObjJson, OrgNodeSnapshotData* outSnapshot) {
    if (!outSnapshot || snapshotObjJson.empty()) return false;
    std::string nodeId = extractJsonStringField(snapshotObjJson, "nodeId");
    if (nodeId.empty() || nodeId == "null") return false;

    outSnapshot->nodeId   = nodeId;
    outSnapshot->etag     = extractJsonStringField(snapshotObjJson, "etag");
    outSnapshot->filePath = extractJsonStringField(snapshotObjJson, "filePath");

    std::string hdr = extractJsonObjectField(snapshotObjJson, "header");
    if (!hdr.empty()) {
        outSnapshot->title       = extractJsonStringField(hdr, "title");
        outSnapshot->todoKeyword = extractJsonStringField(hdr, "todoKeyword");
        if (outSnapshot->todoKeyword == "null") outSnapshot->todoKeyword.clear();
        outSnapshot->level       = extractJsonIntField(hdr, "level", 1);
    }
    if (outSnapshot->title.empty()) {
        outSnapshot->title = "Untitled";
    }

    outSnapshot->inkBlocks.clear();
    auto blockObjs = extractJsonArrayObjects(snapshotObjJson, "blocks");
    for (const auto& blk : blockObjs) {
        if (extractJsonStringField(blk, "type") != "ink_canvas") continue;
        InkBlockInfo info;
        info.blockId    = extractJsonStringField(blk, "blockId");
        info.fileName   = extractJsonStringField(blk, "fileName");
        info.linkText   = extractJsonStringField(blk, "linkText");
        info.etag       = extractJsonStringField(blk, "etag");
        if (info.etag == "null") info.etag.clear();
        info.width      = extractJsonIntField(blk, "width", 1200);
        info.height     = extractJsonIntField(blk, "height", 600);
        info.xRuling    = extractJsonIntField(blk, "xRuling", 0);
        info.yRuling    = extractJsonIntField(blk, "yRuling", 48);
        info.svgzBase64 = extractJsonStringField(blk, "svgzBase64");
        if (info.svgzBase64 == "null") info.svgzBase64.clear();
        if (!info.fileName.empty()) {
            outSnapshot->inkBlocks.push_back(std::move(info));
        }
    }
    return true;
}

std::vector<OrgNodeSummary> parseNodeSummariesJson(const std::string& responseJson) {
    std::vector<OrgNodeSummary> list;
    auto nodeObjs = extractJsonArrayObjects(responseJson, "nodes");
    for (const auto& obj : nodeObjs) {
        OrgNodeSummary s;
        s.nodeId      = extractJsonStringField(obj, "nodeId");
        s.filePath    = extractJsonStringField(obj, "filePath");
        s.title       = extractJsonStringField(obj, "title");
        s.todoKeyword = extractJsonStringField(obj, "todoKeyword");
        if (s.todoKeyword == "null") s.todoKeyword.clear();
        s.scheduled   = extractJsonStringField(obj, "scheduled");
        if (s.scheduled == "null") s.scheduled.clear();
        s.level       = extractJsonIntField(obj, "level", 1);
        s.sketchCount = extractJsonIntField(obj, "sketchCount", 0);
        if (!s.nodeId.empty()) {
            list.push_back(std::move(s));
        }
    }
    return list;
}

bool parseGoldenFixtureFile(const std::string& filePath, GoldenFixtureData* outFixture) {
    if (!outFixture) return false;
    std::string content = readFileToString(filePath);
    if (content.empty()) return false;

    outFixture->protocolVersion = extractJsonIntField(content, "protocolVersion", 2);

    size_t hsPos = content.find("\"handshake\"");
    size_t icPos = content.find("\"inkCommit\"");
    if (hsPos == std::string::npos || icPos == std::string::npos) return false;

    std::string hsSection = content.substr(hsPos, icPos - hsPos);
    std::string icSection = content.substr(icPos);

    outFixture->handshakeMethod = extractJsonStringField(hsSection, "method");
    outFixture->clientName      = extractJsonStringField(hsSection, "clientName");
    outFixture->version         = extractJsonStringField(hsSection, "version");
    outFixture->token           = extractJsonStringField(hsSection, "token");

    outFixture->inkCommit.nodeId     = extractJsonStringField(icSection, "nodeId");
    outFixture->inkCommit.fileName   = extractJsonStringField(icSection, "fileName");
    outFixture->inkCommit.width      = extractJsonIntField(icSection, "width", 1200);
    outFixture->inkCommit.height     = extractJsonIntField(icSection, "height", 600);
    outFixture->inkCommit.baseEtag   = extractJsonStringField(icSection, "baseEtag");
    outFixture->inkCommit.svgzBase64 = extractJsonStringField(icSection, "svgzBase64");
    outFixture->inkCommit.ocrText    = extractJsonStringField(icSection, "ocrText");

    return !outFixture->handshakeMethod.empty() && !outFixture->inkCommit.nodeId.empty();
}

WriteNotesSyncClient::WriteNotesSyncClient(const std::string& queueDir)
    : m_queueDir(queueDir) {}

WriteNotesSyncClient::~WriteNotesSyncClient() {
    disconnect();
}

void WriteNotesSyncClient::setEndpoint(
    const std::string& host,
    int port,
    const std::string& path,
    const std::string& token
) {
    m_host  = host.empty() ? "127.0.0.1" : host;
    m_port  = port > 0 ? port : 9876;
    m_path  = path.empty() ? "/org-writenotes" : path;
    m_token = token;
}

bool WriteNotesSyncClient::loadConfigFile(const std::string& configJsonPath) {
    std::string content = readFileToString(configJsonPath);
    if (content.empty()) return false;
    std::string h    = extractJsonStringField(content, "host");
    std::string wh   = extractJsonStringField(content, "wifiHost");
    if (wh.empty()) {
        wh = extractJsonStringField(content, "fallbackHost");
    }
    int p            = extractJsonIntField(content, "port", 9876);
    std::string path = extractJsonStringField(content, "path");
    std::string tok  = extractJsonStringField(content, "token");
    if (!h.empty()) m_host = h;
    if (!wh.empty()) m_wifiHost = wh;
    if (p > 0) m_port = p;
    if (!path.empty()) m_path = path;
    if (!tok.empty()) m_token = tok;
    return true;
}

void WriteNotesSyncClient::disconnect() {
#if defined(__linux__) || defined(__APPLE__)
    if (m_sockFd >= 0) {
        close(m_sockFd);
    }
#endif
    m_sockFd        = -1;
    m_authenticated = false;
    m_pendingNotifications.clear();
}

bool WriteNotesSyncClient::connectAndHandshake() {
    disconnect();
#if defined(__linux__) || defined(__APPLE__)
    std::vector<std::string> candidateHosts;
    auto addHosts = [&](const std::string& raw) {
        std::stringstream ss(raw);
        std::string item;
        while (std::getline(ss, item, ',')) {
            size_t start = item.find_first_not_of(" \t\r\n");
            size_t end   = item.find_last_not_of(" \t\r\n");
            if (start == std::string::npos) continue;
            std::string trimmed = item.substr(start, end - start + 1);
            if (!trimmed.empty() &&
                std::find(candidateHosts.begin(), candidateHosts.end(), trimmed) == candidateHosts.end()) {
                candidateHosts.push_back(trimmed);
            }
        }
    };
    addHosts(m_host);
    addHosts(m_wifiHost);
    if (candidateHosts.empty()) {
        candidateHosts.push_back("127.0.0.1");
    }

    int fd = -1;
    std::string connectedHost = m_host;
    std::string portStr = std::to_string(m_port);

    for (const auto& candidate : candidateHosts) {
        struct addrinfo hints{};
        hints.ai_family   = AF_UNSPEC;
        hints.ai_socktype = SOCK_STREAM;

        struct addrinfo* res = nullptr;
        if (getaddrinfo(candidate.c_str(), portStr.c_str(), &hints, &res) != 0 || !res) {
            continue;
        }

        for (struct addrinfo* rp = res; rp != nullptr; rp = rp->ai_next) {
            fd = socket(rp->ai_family, rp->ai_socktype, rp->ai_protocol);
            if (fd < 0) continue;
            int flags = fcntl(fd, F_GETFL, 0);
            if (flags >= 0) {
                (void)fcntl(fd, F_SETFL, flags | O_NONBLOCK);
            }
            int rc = connect(fd, rp->ai_addr, rp->ai_addrlen);
            bool connected = (rc == 0);
            if (!connected && errno == EINPROGRESS) {
                struct pollfd pfd{};
                pfd.fd     = fd;
                pfd.events = POLLOUT;
                if (poll(&pfd, 1, 500) > 0 && (pfd.revents & POLLOUT)) {
                    int soErr = 0;
                    socklen_t errLen = sizeof(soErr);
                    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &soErr, &errLen) == 0 && soErr == 0) {
                        connected = true;
                    }
                }
            }
            if (flags >= 0) {
                (void)fcntl(fd, F_SETFL, flags);
            }
            if (connected) {
                connectedHost = candidate;
                break;
            }
            close(fd);
            fd = -1;
        }
        freeaddrinfo(res);
        if (fd >= 0) break;
    }
    if (fd < 0) return false;

    m_sockFd = fd;
    m_host   = connectedHost;

    // RFC 6455 HTTP Upgrade Handshake
    std::ostringstream req;
    req << "GET " << m_path << " HTTP/1.1\r\n"
        << "Host: " << m_host << ":" << m_port << "\r\n"
        << "Upgrade: websocket\r\n"
        << "Connection: Upgrade\r\n"
        << "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
        << "Sec-WebSocket-Version: 13\r\n\r\n";
    std::string reqStr = req.str();
    if (send(m_sockFd, reqStr.data(), reqStr.size(), 0) != static_cast<ssize_t>(reqStr.size())) {
        disconnect();
        return false;
    }

    char respBuf[1024] = {0};
    ssize_t n = recv(m_sockFd, respBuf, sizeof(respBuf) - 1, 0);
    if (n <= 0 || std::strstr(respBuf, "101") == nullptr) {
        disconnect();
        return false;
    }

    int helloId = m_nextRpcId++;
    std::string hello = buildSessionHelloJson(helloId, "Kindle-Scribe", "0.2.0", 2, m_token);
    std::string reply;
    if (!sendRpcRequest(helloId, hello, &reply, 3000) || reply.find("\"error\"") != std::string::npos) {
        disconnect();
        return false;
    }

    std::string activeId = extractJsonStringField(reply, "activeNodeId");
    if (activeId == "null") activeId.clear();
    m_serverActiveNodeId = activeId;

    m_authenticated = true;
    replayOfflineQueue();
    return true;
#else
    return false;
#endif
}

bool WriteNotesSyncClient::sendWebSocketTextFrame(const std::string& text) {
#if defined(__linux__) || defined(__APPLE__)
    if (m_sockFd < 0) return false;
    std::vector<uint8_t> frame;
    frame.reserve(text.size() + 14);

    // FIN = 1, Opcode = 0x1 (Text)
    frame.push_back(0x81);

    // Client-to-server frames MUST have MASK bit (0x80) set per RFC 6455
    size_t len = text.size();
    if (len <= 125) {
        frame.push_back(static_cast<uint8_t>(0x80 | len));
    } else if (len <= 65535) {
        frame.push_back(0x80 | 126);
        frame.push_back(static_cast<uint8_t>((len >> 8) & 0xFF));
        frame.push_back(static_cast<uint8_t>(len & 0xFF));
    } else {
        frame.push_back(0x80 | 127);
        for (int b = 7; b >= 0; --b) {
            frame.push_back(static_cast<uint8_t>((len >> (b * 8)) & 0xFF));
        }
    }

    const uint8_t maskKey[4] = { 0x37, 0xFA, 0x21, 0x3D };
    frame.insert(frame.end(), maskKey, maskKey + 4);
    for (size_t i = 0; i < len; ++i) {
        frame.push_back(static_cast<uint8_t>(text[i]) ^ maskKey[i & 3]);
    }

    size_t sent = 0;
    while (sent < frame.size()) {
        ssize_t n = send(m_sockFd, frame.data() + sent, frame.size() - sent, 0);
        if (n <= 0) return false;
        sent += static_cast<size_t>(n);
    }
    return true;
#else
    (void)text;
    return false;
#endif
}

bool WriteNotesSyncClient::receiveWebSocketTextFrame(std::string* outText, int timeoutMs) {
#if defined(__linux__) || defined(__APPLE__)
    if (m_sockFd < 0 || !outText) return false;
    while (m_sockFd >= 0) {
        struct pollfd pfd{};
        pfd.fd     = m_sockFd;
        pfd.events = POLLIN;
        if (poll(&pfd, 1, timeoutMs) <= 0) return false;

        uint8_t hdr[2] = {0};
        if (recv(m_sockFd, hdr, 2, MSG_WAITALL) != 2) return false;

        uint8_t opcode = hdr[0] & 0x0F;
        bool masked = (hdr[1] & 0x80) != 0;
        uint64_t payloadLen = hdr[1] & 0x7F;
        if (payloadLen == 126) {
            uint8_t ext[2] = {0};
            if (recv(m_sockFd, ext, 2, MSG_WAITALL) != 2) return false;
            payloadLen = (static_cast<uint64_t>(ext[0]) << 8) | ext[1];
        } else if (payloadLen == 127) {
            uint8_t ext[8] = {0};
            if (recv(m_sockFd, ext, 8, MSG_WAITALL) != 8) return false;
            payloadLen = 0;
            for (int i = 0; i < 8; ++i) payloadLen = (payloadLen << 8) | ext[i];
        }

        uint8_t maskKey[4] = {0};
        if (masked && recv(m_sockFd, maskKey, 4, MSG_WAITALL) != 4) return false;

        if (payloadLen > 32 * 1024 * 1024) return false;
        std::string buf(static_cast<size_t>(payloadLen), '\0');
        if (payloadLen > 0) {
            if (recv(m_sockFd, buf.data(), static_cast<size_t>(payloadLen), MSG_WAITALL) !=
                static_cast<ssize_t>(payloadLen)) {
                return false;
            }
            if (masked) {
                for (size_t i = 0; i < buf.size(); ++i) {
                    buf[i] = static_cast<char>(static_cast<uint8_t>(buf[i]) ^ maskKey[i & 3]);
                }
            }
        }

        if (opcode == 0x8) {
            // Connection Close
            disconnect();
            return false;
        } else if (opcode == 0x9) {
            // Ping -> send masked Pong
            uint8_t pongFrame[6] = { 0x8A, 0x80, 0x37, 0xFA, 0x21, 0x3D };
            (void)send(m_sockFd, pongFrame, sizeof(pongFrame), 0);
            continue;
        } else if (opcode == 0xA) {
            // Pong
            continue;
        }

        *outText = std::move(buf);
        return true;
    }
    return false;
#else
    (void)outText;
    (void)timeoutMs;
    return false;
#endif
}

bool WriteNotesSyncClient::sendRpcRequest(
    int id,
    const std::string& requestJson,
    std::string* outResponseJson,
    int timeoutMs
) {
    if (!sendWebSocketTextFrame(requestJson)) return false;
    for (int attempts = 0; attempts < 16; ++attempts) {
        std::string frame;
        if (!receiveWebSocketTextFrame(&frame, timeoutMs)) {
            return false;
        }
        int frameId = extractJsonIntField(frame, "id", -1);
        std::string method = extractJsonStringField(frame, "method");
        if (frameId == id || (frameId > 0 && method.empty())) {
            if (outResponseJson) *outResponseJson = std::move(frame);
            return true;
        }
        if (!method.empty()) {
            // Unsolicited server push notification (e.g. emacs.focusNode, presence.update)
            m_pendingNotifications.push_back(std::move(frame));
        }
    }
    return false;
}

bool WriteNotesSyncClient::fetchNodeList(
    const std::string& filter,
    std::vector<OrgNodeSummary>* outNodes
) {
    if (!isConnected() || !outNodes) return false;
    int rpcId = m_nextRpcId++;
    std::string req = buildNodeListJson(rpcId, filter, "");
    std::string resp;
    if (!sendRpcRequest(rpcId, req, &resp, 3000) || resp.find("\"error\"") != std::string::npos) {
        return false;
    }
    *outNodes = parseNodeSummariesJson(resp);
    return true;
}

bool WriteNotesSyncClient::fetchNodeSnapshot(
    const std::string& nodeId,
    OrgNodeSnapshotData* outSnapshot
) {
    if (!isConnected() || !outSnapshot) return false;
    int rpcId = m_nextRpcId++;
    std::string req = buildNodeGetJson(rpcId, nodeId);
    std::string resp;
    if (!sendRpcRequest(rpcId, req, &resp, 3000) || resp.find("\"error\"") != std::string::npos) {
        return false;
    }
    std::string resObj = extractJsonObjectField(resp, "result");
    if (resObj.empty()) return false;
    return parseNodeSnapshotJson(resObj, outSnapshot);
}

bool WriteNotesSyncClient::insertInkBlock(
    const std::string& nodeId,
    int width,
    int height,
    int xRuling,
    int yRuling,
    OrgNodeSnapshotData* outSnapshot
) {
    if (!isConnected() || nodeId.empty()) return false;
    int rpcId = m_nextRpcId++;
    std::string req = buildInsertInkBlockJson(rpcId, nodeId, width, height, xRuling, yRuling);
    std::string resp;
    if (!sendRpcRequest(rpcId, req, &resp, 3000) || resp.find("\"error\"") != std::string::npos) {
        return false;
    }
    if (outSnapshot) {
        std::string resObj = extractJsonObjectField(resp, "result");
        if (!resObj.empty()) {
            parseNodeSnapshotJson(resObj, outSnapshot);
        }
    }
    return true;
}

bool WriteNotesSyncClient::pollServerNotification(
    std::string* outMethod,
    std::string* outParamsJson,
    int timeoutMs
) {
    if (!isConnected()) return false;

    while (true) {
        std::string frame;
        if (!m_pendingNotifications.empty()) {
            frame = std::move(m_pendingNotifications.front());
            m_pendingNotifications.erase(m_pendingNotifications.begin());
        } else {
            if (!receiveWebSocketTextFrame(&frame, timeoutMs)) {
                return false;
            }
        }
        std::string method = extractJsonStringField(frame, "method");
        if (method.empty()) continue;
        std::string params = extractJsonObjectField(frame, "params");
        if (outMethod) *outMethod = std::move(method);
        if (outParamsJson) *outParamsJson = std::move(params);
        return true;
    }
}

std::string WriteNotesSyncClient::queueFilePath() const {
    return m_queueDir + "/offline_commits.jsonl";
}

std::vector<InkCommitPayload> WriteNotesSyncClient::readQueuedCommits() const {
    std::vector<InkCommitPayload> items;
    std::ifstream ifs(queueFilePath());
    if (!ifs) return items;

    std::string line;
    while (std::getline(ifs, line)) {
        if (line.empty()) continue;
        InkCommitPayload p;
        p.nodeId     = extractJsonStringField(line, "nodeId");
        p.fileName   = extractJsonStringField(line, "fileName");
        p.width      = extractJsonIntField(line, "width", 1200);
        p.height     = extractJsonIntField(line, "height", 600);
        p.baseEtag   = extractJsonStringField(line, "baseEtag");
        p.svgzBase64 = extractJsonStringField(line, "svgzBase64");
        p.ocrText    = extractJsonStringField(line, "ocrText");
        if (!p.nodeId.empty() && !p.fileName.empty()) {
            items.push_back(std::move(p));
        }
    }
    return items;
}

bool WriteNotesSyncClient::saveQueuedCommits(const std::vector<InkCommitPayload>& items) const {
    if (!ensureDirectoryExists(m_queueDir)) return false;
    std::ofstream ofs(queueFilePath(), std::ios::out | std::ios::trunc);
    if (!ofs) return false;
    int id = 1;
    for (const auto& item : items) {
        ofs << buildInkCommitJson(id++, item) << "\n";
    }
    return true;
}

bool WriteNotesSyncClient::commitOrEnqueueInk(const InkCommitPayload& payload) {
    InkCommitPayload effective = payload;
    if (effective.baseEtag.empty()) {
        effective.baseEtag = knownEtagForFile(effective.fileName);
    }

    if (isConnected()) {
        int rpcId = m_nextRpcId++;
        std::string msg = buildInkCommitJson(rpcId, effective);
        std::string resp;
        if (sendRpcRequest(rpcId, msg, &resp, 3000)) {
            if (resp.find("\"conflict\":true") != std::string::npos ||
                resp.find("\"conflict\": true") != std::string::npos) {
                recordConflict(effective.fileName);
            } else {
                clearConflict(effective.fileName);
            }
            std::string newEtag = extractJsonStringField(resp, "etag");
            if (!newEtag.empty() && newEtag != "null") {
                setKnownEtagForFile(effective.fileName, newEtag);
            }
            return true;
        }
        disconnect();
    }

    // Deduplicate existing queued entry for the same (nodeId, fileName) and append latest
    auto queued = readQueuedCommits();
    queued.erase(
        std::remove_if(queued.begin(), queued.end(), [&](const InkCommitPayload& existing) {
            return existing.nodeId == effective.nodeId && existing.fileName == effective.fileName;
        }),
        queued.end()
    );
    queued.push_back(effective);
    return saveQueuedCommits(queued);
}

int WriteNotesSyncClient::replayOfflineQueue() {
    auto queued = readQueuedCommits();
    if (queued.empty() || !isConnected()) return 0;

    std::vector<InkCommitPayload> remaining;
    int sentCount = 0;
    for (const auto& item : queued) {
        // Drop legacy offline placeholder node commits so we never push fake inbox.org to Emacs
        if (item.nodeId == "file:inbox.org::#scribe-quick-note" ||
            item.fileName == "scribe-note.svgz") {
            continue;
        }
        InkCommitPayload effective = item;
        if (effective.baseEtag == "initial") {
            effective.baseEtag.clear();
        }
        int rpcId = m_nextRpcId++;
        std::string msg = buildInkCommitJson(rpcId, effective);
        std::string resp;
        if (sendRpcRequest(rpcId, msg, &resp, 3000)) {
            if (resp.find("\"conflict\":true") != std::string::npos ||
                resp.find("\"conflict\": true") != std::string::npos) {
                recordConflict(effective.fileName);
            } else {
                clearConflict(effective.fileName);
            }
            std::string newEtag = extractJsonStringField(resp, "etag");
            if (!newEtag.empty() && newEtag != "null") {
                setKnownEtagForFile(effective.fileName, newEtag);
            }
            ++sentCount;
            continue;
        }
        remaining.push_back(item);
    }
    saveQueuedCommits(remaining);
    return sentCount;
}

size_t WriteNotesSyncClient::pendingQueueCount() const {
    return readQueuedCommits().size();
}

void WriteNotesSyncClient::clearOfflineQueue() {
    saveQueuedCommits({});
}

void WriteNotesSyncClient::recordConflict(const std::string& fileName) {
    if (std::find(m_conflicts.begin(), m_conflicts.end(), fileName) == m_conflicts.end()) {
        m_conflicts.push_back(fileName);
    }
}

void WriteNotesSyncClient::clearConflict(const std::string& fileName) {
    m_conflicts.erase(
        std::remove(m_conflicts.begin(), m_conflicts.end(), fileName),
        m_conflicts.end()
    );
}

std::string WriteNotesSyncClient::knownEtagForFile(const std::string& fileName) const {
    auto it = m_knownEtags.find(fileName);
    return (it != m_knownEtags.end()) ? it->second : "";
}

void WriteNotesSyncClient::setKnownEtagForFile(const std::string& fileName, const std::string& etag) {
    if (!fileName.empty() && !etag.empty()) {
        m_knownEtags[fileName] = etag;
    }
}

} // namespace orgwritenotes::kindle

