#include <jni.h>
#include <string>
#include <vector>
#include <set>
#include <algorithm>
#include <cctype>
#include <android/log.h>

#define LOG_TAG "adblock_bridge"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

struct Engine {
    std::set<std::string> hosts;
    std::vector<std::string> rules;
};

static std::string tolower_str(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c){ return (char)std::tolower(c); });
    return s;
}

static std::string sanitize_host(const std::string& in) {
    std::string s = tolower_str(in);
    // strip protocol
    auto pos = s.find("://");
    if (pos != std::string::npos) s = s.substr(pos + 3);
    // strip leading ||
    if (s.rfind("||", 0) == 0) s = s.substr(2);
    // strip path / params / anchors
    for (char cut : {'/', '^', '$'}) {
        auto p = s.find(cut);
        if (p != std::string::npos) s = s.substr(0, p);
    }
    if (!s.empty() && s[0] == '.') s.erase(0, 1);
    // strip port
    auto c = s.find(':');
    if (c != std::string::npos) s = s.substr(0, c);
    // remove wildcards
    s.erase(std::remove(s.begin(), s.end(), '*'), s.end());
    return s;
}

static bool host_suffix_match(const std::set<std::string>& blocked, const std::string& host) {
    if (blocked.empty()) return false;
    std::string h = tolower_str(host);
    if (h.empty()) return false;
    if (blocked.find(h) != blocked.end()) return true;
    // Check suffixes
    auto dot = h.find('.');
    while (dot != std::string::npos) {
        std::string sub = h.substr(dot + 1);
        if (blocked.find(sub) != blocked.end()) return true;
        dot = h.find('.', dot + 1);
    }
    return false;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_adblocker_filter_AdblockEngine_nativeCreateEngine(JNIEnv* env, jclass clazz) {
    Engine* e = new (std::nothrow) Engine();
    if (!e) {
        ALOGE("Failed to allocate Engine");
        return 0;
    }
    ALOGI("Engine created %p", e);
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_adblocker_filter_AdblockEngine_nativeLoadRules(JNIEnv* env, jclass clazz, jlong ptr, jobjectArray rules) {
    Engine* e = reinterpret_cast<Engine*>(ptr);
    if (!e) return JNI_FALSE;
    e->rules.clear();
    e->hosts.clear();

    jsize len = env->GetArrayLength(rules);
    for (jsize i = 0; i < len; ++i) {
        jstring jstr = (jstring) env->GetObjectArrayElement(rules, i);
        if (!jstr) continue;
        const char* cstr = env->GetStringUTFChars(jstr, nullptr);
        if (!cstr) { env->DeleteLocalRef(jstr); continue; }
        std::string line(cstr);
        env->ReleaseStringUTFChars(jstr, cstr);
        env->DeleteLocalRef(jstr);

        // store rule
        e->rules.push_back(line);

        // very simple host extraction like Kotlin helper
        std::string l = line;
        // skip comments and exceptions
        if (!l.empty() && (l[0] == '!' || l[0] == '#')) continue;
        if (l.rfind("@@", 0) == 0) continue;

        // ||example.com^ or plain domain or http(s)://host/...
        std::string host = sanitize_host(l);
        if (!host.empty() && host.find('.') != std::string::npos) {
            e->hosts.insert(host);
        }
    }
    ALOGI("Loaded %d rules, %zu hosts", (int)len, e->hosts.size());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_adblocker_filter_AdblockEngine_nativeMatchHostname(JNIEnv* env, jclass clazz, jlong ptr, jstring jhost) {
    Engine* e = reinterpret_cast<Engine*>(ptr);
    if (!e || !jhost) return JNI_FALSE;
    const char* c = env->GetStringUTFChars(jhost, nullptr);
    if (!c) return JNI_FALSE;
    std::string host(c);
    env->ReleaseStringUTFChars(jhost, c);
    bool blocked = host_suffix_match(e->hosts, host);
    return blocked ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_adblocker_filter_AdblockEngine_nativeShouldBlock(JNIEnv* env, jclass clazz, jlong ptr, jstring jurl, jstring jsourceHost, jstring jtype) {
    Engine* e = reinterpret_cast<Engine*>(ptr);
    if (!e || !jurl) return JNI_FALSE;
    const char* curl = env->GetStringUTFChars(jurl, nullptr);
    if (!curl) return JNI_FALSE;
    std::string url(curl);
    env->ReleaseStringUTFChars(jurl, curl);

    // First: host-level decision
    std::string host = sanitize_host(url);
    if (host_suffix_match(e->hosts, host)) return JNI_TRUE;

    // Fallback: naive substring match of rules (placeholder)
    for (const auto& r : e->rules) {
        if (r.empty()) continue;
        if (r[0] == '!' || r[0] == '#') continue;
        if (r.find("##") != std::string::npos) continue; // cosmetic
        if (r.rfind("@@", 0) == 0) continue; // exception ignored here
        if (url.find(r) != std::string::npos) return JNI_TRUE;
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_adblocker_filter_AdblockEngine_nativeRelease(JNIEnv* env, jclass clazz, jlong ptr) {
    Engine* e = reinterpret_cast<Engine*>(ptr);
    if (e) {
        ALOGI("Releasing engine %p", e);
        delete e;
    }
}
