package com.example.adblocker.native

object NativeProxy {
    init {
        System.loadLibrary("nativeproxy")
    }

    external fun startTcpProxy(listenPort: Int, remoteHost: String, remotePort: Int): Long
    external fun stopTcpProxy(ptr: Long)

    external fun startTun(tunFd: Int): Long
    external fun stopTun(ptr: Long)

    external fun startDnsProxy(listenPort: Int, blocklistPath: String, upstreamDns: String): Long
    external fun stopDnsProxy(ptr: Long)

    external fun startAdvancedProxy(listenPort: Int, blocklistPath: String): Long
    external fun stopAdvancedProxy(ptr: Long)
}
