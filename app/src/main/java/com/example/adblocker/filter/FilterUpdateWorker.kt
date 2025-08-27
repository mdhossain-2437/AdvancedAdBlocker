package com.example.adblocker.filter

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

// Entry-point WorkManager task that delegates to FilterManager's own worker
class FilterUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val fm = FilterManager(applicationContext)
        return fm.ManagerUpdateWorker(applicationContext, workerParams).doWork()
    }
}
