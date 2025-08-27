package com.example.adblocker.filter

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class FilterUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // Delegate to FilterManager's inner worker logic by creating a manager and calling update via URL
        val url = inputData.getString("url") ?: return Result.failure()
        val fm = FilterManager(applicationContext)
        return fm.FilterUpdateWorker(applicationContext, workerParams).doWork()
    }
}
