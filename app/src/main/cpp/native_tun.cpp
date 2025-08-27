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

#define LOG_TAG "native_tun"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::thread* tunThread = nullptr;
static std::atomic_bool tunRunning(false);

// Simple IPv4 header parser for demonstration purposes (no fragmentation handling)
struct ipv4_header {
    unsigned char ihl:4, version:4;
    unsigned char tos;
    unsigned short tot_len;
    unsigned short id;
    unsigned short frag_off;
    unsigned char ttl;
    unsigned char protocol;
    unsigned short check;
    unsigned int saddr;
    unsigned int daddr;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_adblocker_native_NativeProxy_startTun(JNIEnv* env, jclass clazz, jint tunFd) {
    if (tunRunning.load()) {
        ALOGI("tun already running");
        return 0;
    }
    tunRunning.store(true);

    tunThread = new std::thread([tunFd]() {
        ALOGI("TUN thread started, fd=%d", tunFd);
        const int bufSize = 32768;
        std::vector<unsigned char> buffer(bufSize);
        while (tunRunning.load()) {
            ssize_t n = read(tunFd, buffer.data(), bufSize);
            if (n <= 0) {
                // sleep briefly to avoid busy loop on errors
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
                continue;
            }
            if (n < (ssize_t)sizeof(ipv4_header)) {
                ALOGE("Short packet: %zd", n);
                continue;
            }
            // parse IPv4 header
            ipv4_header ih;
            memcpy(&ih, buffer.data(), sizeof(ipv4_header));
            // Convert network order fields
            unsigned int saddr = ih.saddr;
            unsigned int daddr = ih.daddr;
            char src[INET_ADDRSTRLEN];
            char dst[INET_ADDRSTRLEN];
            inet_ntop(AF_INET, &saddr, src, INET_ADDRSTRLEN);
            inet_ntop(AF_INET, &daddr, dst, INET_ADDRSTRLEN);
            ALOGI("Packet: proto=%d src=%s dst=%s len=%zd", ih.protocol, src, dst, n);

            // For demonstration, do not forward. Real implementation would reassemble TCP streams or proxy.
        }
        ALOGI("TUN thread exiting");
    });

    // return pointer to thread as jlong
    return reinterpret_cast<jlong>(tunThread);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_adblocker_native_NativeProxy_stopTun(JNIEnv* env, jclass clazz, jlong ptr) {
    if (!tunRunning.load()) return;
    tunRunning.store(false);
    std::thread* t = reinterpret_cast<std::thread*>(ptr);
    if (t) {
        if (t->joinable()) t->join();
        delete t;
    }
    ALOGI("TUN stopped");
}
