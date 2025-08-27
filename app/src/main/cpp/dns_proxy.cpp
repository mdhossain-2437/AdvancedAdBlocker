\
#include <jni.h>
#include <string>
#include <thread>
#include <vector>
#include <atomic>
#include <android/log.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <fcntl.h>
#include <set>
#include <fstream>
#include <sstream>

#define LOG_TAG "dns_proxy"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::thread* dnsThread = nullptr;
static std::atomic_bool dnsRunning(false);

static std::set<std::string> blockedDomains;

static void load_blocklist(const std::string& path) {
    blockedDomains.clear();
    std::ifstream ifs(path);
    if (!ifs.good()) {
        ALOGI("Blocklist file not found: %s", path.c_str());
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
        // normalize: lowercase and trim
        for (auto &ch : s) ch = tolower(ch);
        blockedDomains.insert(s);
    }
    ALOGI("Loaded %zu blocked domains", blockedDomains.size());
}

// Very small DNS packet parser to extract the queried name (assumes standard queries)
static std::string parse_query_name(const unsigned char* buf, ssize_t len) {
    if (len < 12) return "";
    int pos = 12;
    std::string name;
    while (pos < len) {
        unsigned char l = buf[pos++];
        if (l == 0) break;
        if (pos + l > len) return "";
        if (!name.empty()) name += '.';
        name.append((const char*)(buf + pos), l);
        pos += l;
    }
    return name;
}

// Build a simple DNS response that returns 127.0.0.1 for blocked domains
static ssize_t build_block_response(const unsigned char* req, ssize_t req_len, unsigned char* out, ssize_t out_size) {
    if (req_len > out_size) return -1;
    // Copy transaction ID + flags from request, set response flag (0x8180)
    memcpy(out, req, req_len);
    out[2] = 0x81; out[3] = 0x80; // standard query response, no error
    // QDCOUNT - leave as is. ANCOUNT = 1
    out[6] = 0x00; out[7] = 0x01;
    // NSCOUNT = 0, ARCOUNT = 0 already in copied
    // Append answer: pointer to name (0xc0 0x0c), type A (0x00 0x01), class IN (0x00 0x01), TTL 60, RDLENGTH 4, RDATA 127.0.0.1
    ssize_t pos = req_len;
    if (pos + 16 > out_size) return -1;
    out[pos++] = 0xc0; out[pos++] = 0x0c;
    out[pos++] = 0x00; out[pos++] = 0x01;
    out[pos++] = 0x00; out[pos++] = 0x01;
    out[pos++] = 0x00; out[pos++] = 0x00; out[pos++] = 0x00; out[pos++] = 0x3c; // TTL = 60
    out[pos++] = 0x00; out[pos++] = 0x04; // RDLENGTH
    out[pos++] = 127; out[pos++] = 0; out[pos++] = 0; out[pos++] = 1;
    return pos;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_adblocker_native_NativeProxy_startDnsProxy(JNIEnv* env, jclass clazz, jint listenPort, jstring blocklistPath, jstring upstreamDns) {
    const char* blPath = env->GetStringUTFChars(blocklistPath, 0);
    const char* upDns = env->GetStringUTFChars(upstreamDns, 0);
    int lp = listenPort;
    std::string blp(blPath ? blPath : "");
    std::string upstream(upDns ? upDns : "8.8.8.8:53");
    env->ReleaseStringUTFChars(blocklistPath, blPath);
    env->ReleaseStringUTFChars(upstreamDns, upDns);

    if (dnsRunning.load()) {
        ALOGI("DNS proxy already running");
        return 0;
    }
    dnsRunning.store(true);
    load_blocklist(blp);

    dnsThread = new std::thread([lp, blp, upstream]() {
        int sock = socket(AF_INET, SOCK_DGRAM, 0);
        if (sock < 0) {
            ALOGE("failed to create udp socket");
            dnsRunning.store(false);
            return;
        }
        int opt = 1;
        setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

        struct sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = INADDR_ANY;
        addr.sin_port = htons(lp);

        if (bind(sock, (struct sockaddr*)&addr, sizeof(addr)) != 0) {
            ALOGE("bind failed for dns proxy");
            close(sock);
            dnsRunning.store(false);
            return;
        }
        ALOGI("dns proxy listening on %d", lp);

        // parse upstream dns
        std::string upHost = upstream;
        int upPort = 53;
        size_t colon = upstream.find(':');
        if (colon != std::string::npos) {
            upHost = upstream.substr(0, colon);
            upPort = atoi(upstream.substr(colon+1).c_str());
        }

        struct addrinfo hints{}, *res = nullptr;
        hints.ai_family = AF_UNSPEC;
        hints.ai_socktype = SOCK_DGRAM;
        char portBuf[16]; snprintf(portBuf, sizeof(portBuf), "%d", upPort);
        if (getaddrinfo(upHost.c_str(), portBuf, &hints, &res) != 0) {
            ALOGE("upstream getaddrinfo failed");
            close(sock);
            dnsRunning.store(false);
            return;
        }
        // choose first
        struct sockaddr_storage upstreamAddr{};
        socklen_t upstreamAddrLen = 0;
        if (res) {
            memcpy(&upstreamAddr, res->ai_addr, res->ai_addrlen);
            upstreamAddrLen = res->ai_addrlen;
            freeaddrinfo(res);
        }

        unsigned char buf[4096];
        unsigned char out[4096];
        while (dnsRunning.load()) {
            struct sockaddr_storage clientAddr{};
            socklen_t clientLen = sizeof(clientAddr);
            ssize_t n = recvfrom(sock, buf, sizeof(buf), 0, (struct sockaddr*)&clientAddr, &clientLen);
            if (n <= 0) continue;
            std::string qname = parse_query_name(buf, n);
            std::string qlow = qname;
            for (auto &c : qlow) c = tolower(c);
            ALOGI("DNS query for %s", qname.c_str());

            // reload blocklist occasionally (simple strategy: every 30s)
            static uint64_t counter = 0;
            if (++counter % 100 == 0) {
                load_blocklist(blp);
            }

            bool blocked = false;
            // direct match or suffix match (block subdomains)
            if (blockedDomains.find(qlow) != blockedDomains.end()) blocked = true;
            else {
                // suffix match
                for (const auto& bd : blockedDomains) {
                    if (bd.size() > 0 && qlow.size() >= bd.size()) {
                        if (qlow.compare(qlow.size()-bd.size(), bd.size(), bd) == 0) {
                            blocked = true; break;
                        }
                    }
                }
            }

            if (blocked) {
                ssize_t respLen = build_block_response(buf, n, out, sizeof(out));
                if (respLen > 0) {
                    sendto(sock, out, respLen, 0, (struct sockaddr*)&clientAddr, clientLen);
                }
            } else {
                // forward to upstream and relay back
                int upstreamSock = socket(upstreamAddr.ss_family, SOCK_DGRAM, 0);
                if (upstreamSock < 0) continue;
                sendto(upstreamSock, buf, n, 0, (struct sockaddr*)&upstreamAddr, upstreamAddrLen);
                struct timeval tv; tv.tv_sec = 5; tv.tv_usec = 0;
                setsockopt(upstreamSock, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tv, sizeof tv);
                ssize_t r = recvfrom(upstreamSock, out, sizeof(out), 0, nullptr, nullptr);
                if (r > 0) {
                    sendto(sock, out, r, 0, (struct sockaddr*)&clientAddr, clientLen);
                }
                close(upstreamSock);
            }
        }

        close(sock);
        ALOGI("dns proxy exiting");
    });

    return reinterpret_cast<jlong>(dnsThread);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_adblocker_native_NativeProxy_stopDnsProxy(JNIEnv* env, jclass clazz, jlong ptr) {
    if (!dnsRunning.load()) return;
    dnsRunning.store(false);
    std::thread* t = reinterpret_cast<std::thread*>(ptr);
    if (t) {
        if (t->joinable()) t->join();
        delete t;
    }
    ALOGI("DNS proxy stopped");
}
