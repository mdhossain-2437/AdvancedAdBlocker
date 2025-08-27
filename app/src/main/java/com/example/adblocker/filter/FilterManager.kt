 package com.example.adblocker.filter

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class Subscription(
    val url: String,
    var etag: String? = null,
    var lastModified: String? = null,
    var enabled: Boolean = true,
    var failCount: Int = 0,
    var lastSuccess: Long = 0L,
    var localPath: String? = null
)

class FilterManager(private val context: Context) {

    private val client = OkHttpClient()
    private val gson = Gson()

    private val subsFile = File(context.filesDir, "subscriptions.json")
    private val subsDir = File(context.filesDir, "subscriptions").apply { mkdirs() }
    private val mergedFile = File(context.filesDir, "filters_merged.txt")

    @Volatile
    var loaded = false
        private set

    private var runtimeCompiler: FilterCompiler? = null
    private val blockedHosts: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Defaults (uBO/Easylist family)
    private val defaultSubscriptions = listOf(
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt",
        "https://ublockorigin.github.io/uAssets/filters/filters.txt",
        "https://ublockorigin.github.io/uAssets/filters/privacy.txt"
    )

    fun loadFromAssets(assetPath: String) {
        val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
        loadFromText(text)
    }

    fun loadFromText(text: String) {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
        val comp = FilterCompiler()
        for (l in lines) {
            comp.add(l)
        }
        comp.build()
        val domains = extractDomainsFromLists(text)
        synchronized(this) {
            runtimeCompiler = comp
            blockedHosts.clear()
            blockedHosts.addAll(domains)
            loaded = true
        }
        Log.i("FilterManager", "Loaded ${lines.size} patterns")
    }

    fun matches(urlOrHost: String): Boolean {
        val rc = runtimeCompiler
        val host = extractHost(urlOrHost) ?: urlOrHost
        // Host-level check first
        if (blockedHosts.any { host == it || host.endsWith(".$it") }) return true
        // Fallback to substring engine (placeholder for full ABP/uBO semantics)
        return rc?.matches(urlOrHost) == true
    }

    // Export a host-only blocklist derived from enabled subscriptions
    fun exportBlockedDomains(destFile: File): Int {
        val snapshot = synchronized(this) { if (loaded && blockedHosts.isNotEmpty()) blockedHosts.toSet() else emptySet() }
        val domains: Set<String> = if (snapshot.isNotEmpty()) {
            snapshot
        } else {
            // Fallback: rebuild from cached subscription files
            val combined = buildString {
                val subs = readSubscriptions().filter { it.enabled && it.localPath != null }
                for (s in subs) {
                    try {
                        val p = s.localPath ?: continue
                        val t = File(p).takeIf { it.exists() }?.readText() ?: continue
                        append(t).append('\n')
                    } catch (_: Throwable) { }
                }
            }
            extractDomainsFromLists(combined)
        }
        destFile.writeText(domains.joinToString("\n"))
        Log.i("FilterManager", "Exported ${domains.size} domains to ${destFile.absolutePath}")
        return domains.size
    }

    // Schedule periodic updates using WorkManager for multiple subscriptions
    fun scheduleUpdates(remoteUrls: List<String>, intervalMinutes: Long = 60) {
        // Merge with existing subscriptions (add/enable)
        val current = readSubscriptions().toMutableList()
        val existing = current.associateBy { it.url }.toMutableMap()
        for (u in remoteUrls) {
            val sub = existing[u]
            if (sub == null) {
                existing[u] = Subscription(url = u, enabled = true)
            } else {
                sub.enabled = true
            }
        }
        writeSubscriptions(existing.values.toList())

        val data = workDataOf("updateAll" to true)
        val req = PeriodicWorkRequestBuilder<FilterUpdateWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setInputData(data)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("filter_update", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    // Backwards-compatible: single URL
    fun scheduleUpdates(remoteUrl: String, intervalMinutes: Long = 60) {
        scheduleUpdates(listOf(remoteUrl), intervalMinutes)
    }

    // Ensure defaults present if none configured
    fun ensureDefaultSubscriptions() {
        val current = readSubscriptions()
        if (current.isEmpty()) {
            writeSubscriptions(defaultSubscriptions.map { Subscription(url = it, enabled = true) })
        }
    }

    // Worker that updates all enabled subscriptions and rebuilds engine once
    inner class ManagerUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            try {
                val subs = readSubscriptions().toMutableList()
                if (subs.isEmpty()) {
                    // initialize defaults if none
                    writeSubscriptions(defaultSubscriptions.map { Subscription(url = it, enabled = true) })
                }
                var anySuccess = false
                val updated = readSubscriptions().toMutableList()
                for (i in updated.indices) {
                    val s = updated[i]
                    if (!s.enabled) continue
                    try {
                        val builder = Request.Builder().url(s.url)
                            .header("User-Agent", "AdBlocker/0.1 Android")
                        s.etag?.let { builder.header("If-None-Match", it) }
                        s.lastModified?.let { builder.header("If-Modified-Since", it) }
                        val req = builder.build()
                        client.newCall(req).execute().use { resp ->
                            when {
                                resp.code == 304 -> {
                                    s.lastSuccess = System.currentTimeMillis()
                                    s.failCount = 0
                                    anySuccess = true
                                }
                                resp.isSuccessful -> {
                                    val body = resp.body?.string() ?: ""
                                    if (body.isEmpty() || body.length > 10_000_000) {
                                        throw IOException("Invalid body size")
                                    }
                                    val fileName = sha1(s.url) + ".txt"
                                    val outFile = File(subsDir, fileName)
                                    outFile.writeText(body)
                                    s.localPath = outFile.absolutePath
                                    s.etag = resp.header("ETag") ?: s.etag
                                    s.lastModified = resp.header("Last-Modified") ?: s.lastModified
                                    s.lastSuccess = System.currentTimeMillis()
                                    s.failCount = 0
                                    anySuccess = true
                                }
                                else -> {
                                    s.failCount += 1
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        s.failCount += 1
                        Log.e("FilterManager", "Update failed for ${s.url}: ${t.message}")
                    }
                }
                writeSubscriptions(updated)

                // Rebuild engine from all enabled local files
                val text = buildString {
                    for (s in updated) {
                        if (!s.enabled) continue
                        val p = s.localPath ?: continue
                        val f = File(p)
                        if (f.exists()) {
                            append(f.readText()).append('\n')
                        }
                    }
                }
                if (text.isNotBlank()) {
                    mergedFile.writeText(text)
                    loadFromText(text)
                    // Export host-only blocklist file for native DNS proxy to consume
                    try {
                        this@FilterManager.exportBlockedDomains(File(applicationContext.filesDir, "blocked_domains.txt"))
                    } catch (_: Throwable) { }
                }
                return if (anySuccess) Result.success() else Result.retry()
            } catch (t: Throwable) {
                t.printStackTrace()
                return Result.retry()
            }
        }
    }

    // Subscriptions persistence
    private fun readSubscriptions(): List<Subscription> {
        return try {
            if (!subsFile.exists()) return emptyList()
            val type = object : TypeToken<List<Subscription>>() {}.type
            gson.fromJson(subsFile.readText(), type) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun writeSubscriptions(list: List<Subscription>) {
        subsFile.writeText(gson.toJson(list))
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(s.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun extractDomainsFromLists(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        val lines = text.lines()
        for (raw in lines) {
            var l = raw.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("!") || l.startsWith("#") || l.startsWith("[")) continue // comments/headers
            if (l.startsWith("@@")) continue // exceptions not supported in host export
            // Element hiding/cosmetic
            if (l.contains("##") || l.contains("#@#") || l.contains("##+js")) continue
            // uBO/ABP style ||domain^...
            if (l.startsWith("||")) {
                l = l.removePrefix("||")
                val cut = l.indexOfFirst { it == '^' || it == '/' }
                val d = if (cut > 0) l.substring(0, cut) else l
                val dom = sanitizeDomain(d)
                if (dom.isNotEmpty()) out.add(dom)
                continue
            }
            // plain hostname rule (heuristic)
            if (!l.contains('/') && !l.contains('*') && l.any { it == '.' }) {
                val dom = sanitizeDomain(l)
                if (dom.isNotEmpty()) out.add(dom)
                continue
            }
            // |http:// or http://host/path -> extract host
            val idx = l.indexOf("://")
            if (idx > 0) {
                val rest = l.substring(idx + 3)
                val host = rest.takeWhile { it != '/' && it != '^' && it != '$' }
                val dom = sanitizeDomain(host)
                if (dom.isNotEmpty()) out.add(dom)
                continue
            }
        }
        return out
    }

    private fun sanitizeDomain(d: String): String {
        var x = d.lowercase().trim()
        if (x.startsWith(".")) x = x.removePrefix(".")
        x = x.replace("*", "")
        // very simple validation
        return if (x.any { it == '.' } && x.all { it.isLetterOrDigit() || it == '.' || it == '-' }) x else ""
    }
}
