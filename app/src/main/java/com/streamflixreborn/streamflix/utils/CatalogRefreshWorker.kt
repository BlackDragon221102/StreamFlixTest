package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.streamflixreborn.streamflix.BuildConfig

class CatalogRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (BuildConfig.APP_LAYOUT != "mobile") {
            return Result.success()
        }

        if (!CatalogRefreshUtils.shouldRefreshInBackground()) {
            return Result.success()
        }

        return runCatching {
            CatalogRefreshUtils.refreshMobileCatalogCaches()
            Result.success()
        }.getOrElse { error ->
            Log.e("CatalogRefreshWorker", "Background catalog refresh failed", error)
            Result.retry()
        }
    }
}
