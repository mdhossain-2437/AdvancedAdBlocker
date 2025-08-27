\
#include <jni.h>
#include <string>
#include <thread>
#include <vector>
#include <atomic>
#include <android/log.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <netdb.h>

#define LOG_TAG "nativeproxy"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::thread* serverThread = nullptr;
static std::atomic_bool serverRunning(false);

static void relay_loop(int clientFd, const char* remoteHost, int remotePort) {
    ALOGD("relay_loop: clientFd=%d, remote=%s:%d", clientFd, remoteHost, remotePort);

    struct addrinfo hints{}, *res = nullptr;
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    char portBuf[16];
    snprintf(portBuf, sizeof(portBuf), "%d", remotePort);
    if (getaddrinfo(remoteHost, portBuf, &hints, &res) != 0) {
        ALOGE("getaddrinfo failed");
        close(clientFd);
        return;
    }

    int remoteSock = -1;
    for (struct addrinfo* p = res; p != nullptr; p = p->ai_next) {
        remoteSock = socket(p->ai_family, p->ai_socktype, p->ai_protocol);
        if (remoteSock == -1) continue;
        if (connect(remoteSock, p->ai_addr, p->ai_addrlen) == 0) break;
        close(remoteSock);
        remoteSock = -1;
    }
    freeaddrinfo(res);

    if (remoteSock == -1) {
        ALOGE("Could not connect to remote");
        close(clientFd);
        return;
    }

    auto forward = [](int inFd, int outFd) {
        std::vector<char> buf(4096);
        while (true) {
            ssize_t r = recv(inFd, buf.data(), buf.size(), 0);
            if (r <= 0) break;
            ssize_t s = send(outFd, buf.data(), r, 0);
            if (s <= 0) break;
        }
        shutdown(inFd, SHUT_RD);
        shutdown(outFd, SHUT_WR);
    };

    std::thread t1(forward, clientFd, remoteSock);
    std::thread t2(forward, remoteSock, clientFd);
    t1.join();
    t2.join();
    close(remoteSock);
    close(clientFd);
    ALOGD("relay_loop: finished for clientFd=%d", clientFd);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_adblocker_native_NativeProxy_startTcpProxy(JNIEnv* env, jclass clazz, jint listenPort, jstring remoteHost, jint remotePort) {
    const char* rh = env->GetStringUTFChars(remoteHost, 0);
    int lp = listenPort;
    int rp = remotePort;

    std::string remote(rh ? rh : "");
    env->ReleaseStringUTFChars(remoteHost, rh);

    if (serverRunning.load()) {
        ALOGD("TCP proxy already running");
        return 0;
    }
    serverRunning.store(true);

    serverThread = new std::thread([lp, remote, rp]() {
        int serverFd = socket(AF_INET, SOCK_STREAM, 0);
        if (serverFd < 0) {
            ALOGE("Failed to create server socket");
            serverRunning.store(false);
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
            serverRunning.store(false);
            return;
        }
        if (listen(serverFd, 8) != 0) {
            ALOGE("Listen failed");
            close(serverFd);
            serverRunning.store(false);
            return;
        }
        ALOGD("native proxy listening on %d", lp);

        while (serverRunning.load()) {
            int clientFd = accept(serverFd, nullptr, nullptr);
            if (clientFd < 0) break;
            std::thread(relay_loop, clientFd, remote.c_str(), rp).detach();
        }
        close(serverFd);
        ALOGD("native proxy exiting");
        serverRunning.store(false);
    });

    return reinterpret_cast<jlong>(serverThread);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_adblocker_native_NativeProxy_stopTcpProxy(JNIEnv* env, jclass clazz, jlong ptr) {
    if (!serverRunning.load()) return;
    serverRunning.store(false);
    // Create a dummy connection to wake accept()
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock >= 0) {
        struct sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        addr.sin_port = htons(8888); // assumes listen port; in production pass port
        connect(sock, (struct sockaddr*)&addr, sizeof(addr));
        close(sock);
    }
    std::thread* t = reinterpret_cast<std::thread*>(ptr);
    if (t) {
        if (t->joinable()) t->join();
        delete t;
    }
    ALOGD("TCP proxy stopped");
}
