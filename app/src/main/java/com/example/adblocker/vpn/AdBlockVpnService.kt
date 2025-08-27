package com.example.adblocker.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.adblocker.filter.SimpleDnsBlocker
import com.example.adblocker.filter.FilterManager
import com.example.adblocker.native.NativeProxy
import kotlinx.coroutines.*
import java.io.File

class AdBlockVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var dnsBlocker: SimpleDnsBlocker? = null
    private var nativeServerPtr: Long = 0
    private var tunPtr: Long = 0
    private var dnsPtr: Long = 0

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        dnsBlocker = SimpleDnsBlocker(this)
        // schedule updates from a central list (example URL, replace with real)
        dnsBlocker?.let {
            it.apply {
                // Schedule periodic updates (default 60 minutes). Replace URL with a list provider.
                // For demo this is a placeholder
                scheduleExampleUpdates()
            }
        }
    }

    private fun SimpleDnsBlocker.scheduleExampleUpdates() {
        // no-op: in production call FilterManager.scheduleUpdates(remoteUrl)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, makeNotification())
        scope.launch {
            startTun()
        }
        return START_STICKY
    }

    private fun makeNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, com.example.adblocker.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, "adblock_channel")
            .setContentTitle("AdBlock VPN running")
            .setContentText("Tap to open")
            .setContentIntent(pendingIntent)
            .setSmallIcon(android.R.drawable.stat_sys_vpn)
            .setOngoing(true)
            .build()
    }

    private fun startTun() {
        try {
            val builder = Builder()
            builder.addAddress("10.0.0.2", 32)
            builder.addRoute("0.0.0.0", 0)
            builder.setSession("AdBlockVPN")
            builder.setBlocking(true)
            // Important note:
            // Setting DNS to localhost (127.0.0.1) and running a DNS proxy inside the app is a common technique.
            // However, routing DNS through the VPN to the app's local listener requires careful routing and privileges.
            // Here we set a public DNS server as a placeholder.
            builder.addDnsServer("127.0.0.1") // route DNS to local proxy (dns proxy listens on 5353)

            vpnInterface = builder.establish()
            Log.i("AdBlockVpnService", "VPN established: $vpnInterface")

            // Start a reference native TCP proxy for testing (listens on localhost:8888 and forwards to remote.example.com:80)
            try {
                nativeServerPtr = NativeProxy.startTcpProxy(8888, "example.com", 80)
                Log.i("AdBlockVpnService", "Started native TCP proxy: ptr=" + nativeServerPtr)
            } catch (t: Throwable) {
                t.printStackTrace()
            }

            // Pass the TUN file descriptor to native code to begin reading packets
            try {
                vpnInterface?.fileDescriptor?.let { fd ->
                    val intFd = fd.fd
                    tunPtr = NativeProxy.startTun(intFd)
                    Log.i("AdBlockVpnService", "Started native TUN reader: ptr=$tunPtr fd=$intFd")
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }

            // Write current blocklist to app files and start native DNS proxy
            try {
                val fm = FilterManager(this)
                // Ensure default subscriptions and schedule periodic updates (6 hours)
                fm.ensureDefaultSubscriptions()
                fm.scheduleUpdates(
                    listOf(
                        "https://easylist.to/easylist/easylist.txt",
                        "https://easylist.to/easylist/easyprivacy.txt",
                        "https://ublockorigin.github.io/uAssets/filters/filters.txt",
                        "https://ublockorigin.github.io/uAssets/filters/privacy.txt"
                    ),
                    360
                )
                // Export a host-only blocklist for the native proxies
                val blockFile = File(filesDir, "blocked_domains.txt")
                var exported = 0
                try {
                    exported = fm.exportBlockedDomains(blockFile)
                } catch (_: Throwable) { }
                if (exported == 0 || !blockFile.exists() || blockFile.length() == 0L) {
                    // fallback to bundled asset on first run
                    assets.open("filters/basic_blocklist.txt").use { ins ->
                        blockFile.outputStream().use { out -> ins.copyTo(out) }
                    }
                }

                // Start DNS proxy on 5353 listening on all interfaces; VPN will use 127.0.0.1 (note: user-space apps typically cannot bind to port 53)
                dnsPtr = NativeProxy.startDnsProxy(5353, blockFile.absolutePath, "8.8.8.8:53")
                Log.i("AdBlockVpnService", "Started native DNS proxy: ptr=$dnsPtr")

                // Start advanced HTTP proxy for request-level blocking (listens on 8888)
                try {
                    val advPtr = NativeProxy.startAdvancedProxy(8888, blockFile.absolutePath)
                    Log.i("AdBlockVpnService", "Started advanced HTTP proxy: ptr=$advPtr")
                } catch (t: Throwable) { t.printStackTrace() }
            } catch (t: Throwable) {
                t.printStackTrace()
            }

            vpnInterface?.fileDescriptor?.let { fd ->
                // ***** PRODUCTION NOTE *****
                // Real packet handling and request-level blocking requires:
                //  - reading packets from the file descriptor
                //  - reassembling TCP streams or implementing a user-space TCP proxy
                //  - parsing HTTP requests and applying the filter decisions
                //  - handling UDP (DNS) requests and responding appropriately
                //
                // This is non-trivial and typically implemented in native code (NDK) for performance.
                // This starter keeps the service alive and integrates the FilterManager + scheduler.
                while (!scope.isCancelled) {
                    Thread.sleep(1000)
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            stopSelf()
        }
    }

    override fun onRevoke() {
        Log.i("AdBlockVpnService", "VPN revoked by system")
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        vpnInterface?.close()
        if (nativeServerPtr != 0L) {
            try { NativeProxy.stopTcpProxy(nativeServerPtr) } catch (t: Throwable) { t.printStackTrace() }
        }
        if (tunPtr != 0L) {
            try { NativeProxy.stopTun(tunPtr) } catch (t: Throwable) { t.printStackTrace() }
        }
        if (dnsPtr != 0L) {
            try { NativeProxy.stopDnsProxy(dnsPtr) } catch (t: Throwable) { t.printStackTrace() }
        }
        vpnInterface = null
        super.onDestroy()
    }


    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                "adblock_channel",
                "AdBlock VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of the VPN-based ad blocker"
                setShowBadge(false)
            }
            mgr?.createNotificationChannel(channel)
        }
    }
}
