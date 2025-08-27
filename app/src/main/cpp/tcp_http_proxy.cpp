\
#include <jni.h>
#include <string>
#include <thread>
#include <vector>
#include <atomic>
#include <android/log.h>
#include <unistd.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <fcntl.h>
#include <sstream>
#include <set>
#include <fstream>

#define LOG_TAG "tcp_http_proxy"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static std::thread* proxyThread = nullptr;
static std::atomic_bool proxyRunning(false);
static std::set<std::string> blockedDomains;

static void load_blocklist(const std::string& path) {
    blockedDomains.clear();
    std::ifstream ifs(path);
    if (!ifs.good()) {
        ALOGI("Proxy blocklist file not found: %s", path.c_str());
        return;
    }
    std::string line;
    while (std::getline(ifs, line)) {
        std::string s;
        for (char c : line) {
            if (c == '\r' || c == '\n') break;
            s += c;
        }
        if (s.empty()) continue;
        for (auto &ch : s) ch = tolower(ch);
        blockedDomains.insert(s);
    }
    ALOGI("Proxy loaded %zu blocked domains", blockedDomains.size());
}

static bool host_blocked(const std::string& host) {
    std::string h = host;
    for (auto &c : h) c = tolower(c);
    if (blockedDomains.find(h) != blockedDomains.end()) return true;
    for (const auto& bd : blockedDomains) {
        if (bd.size() > 0 && h.size() >= bd.size()) {
            if (h.compare(h.size()-bd.size(), bd.size(), bd) == 0) return true;
        }
    }
    return false;
}

// Read a line from socket until CRLF
static bool read_line(int fd, std::string& out) {
    out.clear();
    char c;
    bool gotCR = false;
    while (true) {
        ssize_t n = recv(fd, &c, 1, 0);
        if (n <= 0) return false;
        out.push_back(c);
        if (out.size() >= 2 && out[out.size()-2] == '\r' && out[out.size()-1] == '\n') break;
    }
    return true;
}

// Simple function to parse Host header and the request line for HTTP proxying
static bool parse_http_request(int clientFd, std::string& method, std::string& target, std::string& host, std::vector<char>& initialBuffer) {
    // read request headers into buffer until empty line
    std::string line;
    std::string headers;
    while (true) {
        if (!read_line(clientFd, line)) return false;
        headers += line;
        if (line == "\r\n") break;
        if (headers.size() > 64*1024) return false; // too large
    }
    // parse first line
    std::istringstream ss(headers);
    ss >> method;
    ss >> target;
    // find Host header
    std::string hdrLine;
    while (std::getline(ss, hdrLine)) {
        if (hdrLine.size() >= 5) {
            std::string lower = hdrLine;
            for (auto &c : lower) c = tolower(c);
            if (lower.find("host:") == 0) {
                // extract value
                size_t colon = hdrLine.find(':');
                if (colon != std::string::npos) {
                    host = hdrLine.substr(colon+1);
                    // trim spaces and CRLF
                    while (!host.empty() && (host.back() == '\r' || host.back() == '\n' || host.back() == ' ')) host.pop_back();
                    while (!host.empty() && host.front() == ' ') host.erase(0,1);
                }
                break;
            }
        }
    }
    // save headers into initialBuffer to forward later
    initialBuffer.assign(headers.begin(), headers.end());
    return true;
}

// Basic SNI parsing from TLS ClientHello (not fully robust but works for many cases)
// Returns server name or empty
static std::string parse_tls_sni(const unsigned char* data, size_t len) {
    if (len < 5) return "";
    // TLS record header: type(1), version(2), length(2)
    if (data[0] != 0x16) return ""; // handshake
    size_t pos = 5;
    if (pos + 4 > len) return "";
    // Handshake: msg_type(1), length(3)
    if (data[pos] != 0x01) return ""; // ClientHello
    // skip handshake header
    pos += 4;
    if (pos + 34 > len) return ""; // minimal ClientHello length
    // skip: version(2), random(32)
    pos += 34;
    // session id
    if (pos + 1 > len) return "";
    size_t sid_len = data[pos];
    pos += 1 + sid_len;
    if (pos + 2 > len) return "";
    // cipher suites
    size_t cs_len = (data[pos] << 8) | data[pos+1];
    pos += 2 + cs_len;
    if (pos + 1 > len) return "";
    // compression
    size_t comp_len = data[pos];
    pos += 1 + comp_len;
    if (pos + 2 > len) return "";
    // extensions length
    size_t ext_len = (data[pos] << 8) | data[pos+1];
    pos += 2;
    size_t end = pos + ext_len;
    while (pos + 4 <= end && pos + 4 <= len) {
        uint16_t ext_type = (data[pos] << 8) | data[pos+1];
        uint16_t ext_len = (data[pos+2] << 8) | data[pos+3];
        pos += 4;
        if (ext_type == 0x00) { // server_name
            size_t sn_pos = pos;
            if (sn_pos + 2 > len) return "";
            size_t sn_list_len = (data[sn_pos] << 8) | data[sn_pos+1];
            sn_pos += 2;
            while (sn_pos + 3 <= pos + ext_len && sn_pos + 3 <= len) {
                uint8_t name_type = data[sn_pos];
                uint16_t name_len = (data[sn_pos+1] << 8) | data[sn_pos+2];
                sn_pos += 3;
                if (name_type == 0 && sn_pos + name_len <= len) {
                    std::string s((const char*)(data + sn_pos), name_len);
                    return s;
                }
                sn_pos += name_len;
            }
        }
        pos += ext_len;
    }
    return "";
}

static void handle_client(int clientFd, const std::string& blocklistPath) {
    // set a recv timeout
    struct timeval tv; tv.tv_sec = 5; tv.tv_usec = 0;
    setsockopt(clientFd, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tv, sizeof(tv));

    // peek initial bytes
    unsigned char buf[8192];
    ssize_t n = recv(clientFd, buf, sizeof(buf), MSG_PEEK);
    if (n <= 0) { close(clientFd); return; }

    // Check if TLS ClientHello (record type 0x16) -> parse SNI
    std::string sni = parse_tls_sni(buf, n);
    if (!sni.empty()) {
        if (host_blocked(sni)) {
            ALOGI("Blocking TLS by SNI: %s", sni.c_str());
            close(clientFd);
            return;
        }
        // Not blocked: establish direct tunnel to remote (client will use CONNECT through proxy, but this handles direct TLS)
    }

    // Try to parse as HTTP request line and host header
    std::string method, target, host;
    std::vector<char> initialBuf;
    if (parse_http_request(clientFd, method, target, host, initialBuf)) {
        ALOGI("HTTP proxy request: method=%s target=%s host=%s", method.c_str(), target.c_str(), host.c_str());
        std::string hostOnly = host;
        // remove possible port
        size_t colon = hostOnly.find(':');
        if (colon != std::string::npos) hostOnly = hostOnly.substr(0, colon);
        if (host_blocked(hostOnly)) {
            ALOGI("Blocking HTTP host: %s", hostOnly.c_str());
            close(clientFd);
            return;
        }
        // For simplicity, treat as forward proxy: resolve host and connect
        struct addrinfo hints{}, *res = nullptr;
        hints.ai_family = AF_UNSPEC;
        hints.ai_socktype = SOCK_STREAM;
        char portBuf[16];
        // decide port based on target or default 80
        const char* port = "80";
        if (method == "CONNECT") port = "443";
        int rv = getaddrinfo(hostOnly.c_str(), port, &hints, &res);
        if (rv != 0 || res == nullptr) {
            ALOGE("getaddrinfo failed for host %s", hostOnly.c_str());
            close(clientFd);
            return;
        }
        int remoteSock = -1;
        for (struct addrinfo* p = res; p != nullptr; p = p->ai_next) {
            remoteSock = socket(p->ai_family, p->ai_socktype, p->ai_protocol);
            if (remoteSock < 0) continue;
            if (connect(remoteSock, p->ai_addr, p->ai_addrlen) == 0) break;
            close(remoteSock);
            remoteSock = -1;
        }
        freeaddrinfo(res);
        if (remoteSock < 0) { close(clientFd); return; }

        if (method == "CONNECT") {
            // Proxy CONNECT: respond 200 OK and then tunnel
            const char* ok = "HTTP/1.1 200 Connection Established\r\n\r\n";
            send(clientFd, ok, strlen(ok), 0);
        } else {
            // forward the initial request bytes we already read (headers)
            send(remoteSock, initialBuf.data(), initialBuf.size(), 0);
        }

        // relay loop
        std::thread t1([clientFd, remoteSock]() {
            char buffer[4096];
            ssize_t r;
            while ((r = recv(clientFd, buffer, sizeof(buffer), 0)) > 0) {
                if (send(remoteSock, buffer, r, 0) <= 0) break;
            }
            shutdown(remoteSock, SHUT_WR);
        });
        std::thread t2([clientFd, remoteSock]() {
            char buffer[4096];
            ssize_t r;
            while ((r = recv(remoteSock, buffer, sizeof(buffer), 0)) > 0) {
                if (send(clientFd, buffer, r, 0) <= 0) break;
            }
            shutdown(clientFd, SHUT_WR);
        });
        t1.join(); t2.join();
        close(remoteSock);
        close(clientFd);
        return;
    } else {
        // Not an HTTP request; close
        close(clientFd);
        return;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_adblocker_native_NativeProxy_startAdvancedProxy(JNIEnv* env, jclass clazz, jint listenPort, jstring blocklistPath) {
    const char* blPath = env->GetStringUTFChars(blocklistPath, 0);
    int lp = listenPort;
    std::string blp(blPath ? blPath : "");
    env->ReleaseStringUTFChars(blocklistPath, blPath);

    if (proxyRunning.load()) {
        ALOGI("Advanced proxy already running");
        return 0;
    }
    load_blocklist(blp);
    proxyRunning.store(true);

    proxyThread = new std::thread([lp, blp]() {
        int serverFd = socket(AF_INET, SOCK_STREAM, 0);
        if (serverFd < 0) {
            ALOGE("Failed to create proxy server socket");
            proxyRunning.store(false);
            return;
        }
        int opt = 1;
        setsockopt(serverFd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
        struct sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = INADDR_ANY;
        addr.sin_port = htons(lp);
        if (bind(serverFd, (struct sockaddr*)&addr, sizeof(addr)) != 0) {
            ALOGE("Bind failed");
            close(serverFd);
            proxyRunning.store(false);
            return;
        }
        if (listen(serverFd, 16) != 0) {
            ALOGE("Listen failed");
            close(serverFd);
            proxyRunning.store(false);
            return;
        }
        ALOGI("Advanced proxy listening on %d", lp);
        while (proxyRunning.load()) {
            int clientFd = accept(serverFd, nullptr, nullptr);
            if (clientFd < 0) break;
            std::thread(handle_client, clientFd, blp).detach();
        }
        close(serverFd);
        proxyRunning.store(false);
        ALOGI("Advanced proxy exiting");
    });

    return reinterpret_cast<jlong>(proxyThread);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_adblocker_native_NativeProxy_stopAdvancedProxy(JNIEnv* env, jclass clazz, jlong ptr) {
    if (!proxyRunning.load()) return;
    proxyRunning.store(false);
    // wake accept by connecting
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock >= 0) {
        struct sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        addr.sin_port = htons(8888);
        connect(sock, (struct sockaddr*)&addr, sizeof(addr));
        close(sock);
    }
    std::thread* t = reinterpret_cast<std::thread*>(ptr);
    if (t) {
        if (t->joinable()) t->join();
        delete t;
    }
    ALOGI("Advanced proxy stopped");
}
