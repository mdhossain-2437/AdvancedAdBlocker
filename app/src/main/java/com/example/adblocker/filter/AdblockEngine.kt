package com.example.adblocker.filter

/**
 * Optional native adblock engine bridge.
 * This is a placeholder JNI wrapper that will call into a C++ stub (adblock_bridge.cpp).
 * If the native layer is not available, all calls are no-ops and return false.
 *
 * Later, this bridge can be wired to a Rust-based engine (adblock-rust) via FFI.
 */
object AdblockEngine {
    init {
        // Load the same library used by other native components.
        // If the library isn't present at runtime, calls will be no-ops.
        try {
            System.loadLibrary("nativeproxy")
        } catch (_: Throwable) {
            // Ignore: running without native engine is supported
        }
    }

    @Volatile
    private var ptr: Long = 0L

    private external fun nativeCreateEngine(): Long
    private external fun nativeLoadRules(ptr: Long, rules: Array<String>): Boolean
    private external fun nativeMatchHostname(ptr: Long, host: String): Boolean
    private external fun nativeShouldBlock(ptr: Long, url: String, sourceHost: String, resourceType: String): Boolean
    private external fun nativeRelease(ptr: Long)

    fun isReady(): Boolean = ptr != 0L

    @Synchronized
    fun tryInit(rules: List<String>): Boolean {
        return try {
            if (ptr == 0L) {
                ptr = nativeCreateEngine()
            }
            nativeLoadRules(ptr, rules.toTypedArray())
        } catch (_: Throwable) {
            false
        }
    }

    fun matchHost(host: String): Boolean {
        val p = ptr
        if (p == 0L) return false
        return try {
            nativeMatchHostname(p, host)
        } catch (_: Throwable) {
            false
        }
    }

    fun shouldBlock(url: String, sourceHost: String, resourceType: String = "OTHER"): Boolean {
        val p = ptr
        if (p == 0L) return false
        return try {
            nativeShouldBlock(p, url, sourceHost, resourceType)
        } catch (_: Throwable) {
            false
        }
    }

    @Synchronized
    fun release() {
        if (ptr != 0L) {
            try {
                nativeRelease(ptr)
            } catch (_: Throwable) {
                // ignore
            } finally {
                ptr = 0L
            }
        }
    }
}
