package com.example.adblocker.filter

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

class SimpleDnsBlocker(context: Context) : FilterEngine {
    private val blocked = ConcurrentHashMap.newKeySet<String>()
    private val stats = ConcurrentHashMap<String, Long>()
    private val manager = FilterManager(context)

    init {
        // load default
        try {
            manager.loadFromAssets("filters/basic_blocklist.txt")
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    override fun loadFilterLists(lists: List<String>) {
        // deprecated in favor of FilterManager
        for (l in lists) {
            blocked.add(l.trim().lowercase())
        }
    }

    override fun decide(context: RequestContext): FilterDecision {
        val d = context.domain.lowercase()
        if (manager.matches(d)) {
            stats["blocked"] = stats.getOrDefault("blocked", 0L) + 1
            Log.i("SimpleDnsBlocker", "Blocking domain via manager: $d")
            return FilterDecision.Block
        }
        stats["allowed"] = stats.getOrDefault("allowed", 0L) + 1
        return FilterDecision.Allow
    }

    override fun getStats(): Map<String, Long> = stats
}
