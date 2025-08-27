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
        synchronized(this) {
            runtimeCompiler = comp
            loaded = true
        }
        Log.i("FilterManager", "Loaded ${lines.size} patterns")
    }

    fun matches(urlOrHost: String): Boolean {
        val rc = runtimeCompiler ?: return false
        return rc.matches(urlOrHost)
    }

    // Export a host-only blocklist derived from enabled subscriptions
    fun exportBlockedDomains(destFile: File): Int {
        val all = StringBuilder()
        val subs = readSubscriptions().filter { it.enabled && it.localPath != null }
        for (s in subs) {
            try {
                val p = s.localPath ?: continue
