package com.streamflixreborn.streamflix.utils

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.streamflixreborn.streamflix.BuildConfig
import java.util.concurrent.TimeUnit

object CatalogRefreshScheduler {
    private const val UNIQUE_WORK_NAME = "background_mobile_catalog_refresh"

    fun scheduleIfNeeded(context: Context) {
        if (BuildConfig.APP_LAYOUT != "mobile") return
        if (!CatalogRefreshUtils.shouldRefreshInBackground()) return

        val request = OneTimeWorkRequestBuilder<CatalogRefreshWorker>()
            .setInitialDelay(20, TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
