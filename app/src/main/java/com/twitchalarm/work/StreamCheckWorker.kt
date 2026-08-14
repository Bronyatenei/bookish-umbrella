package com.twitchalarm.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Совместимость с задачами WorkManager, которые могли сохраниться от версии 1.0.
 * Новые проверки выполняет StreamCheckService; контроллер отменяет старые задачи.
 */
class StreamCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Log.i("StreamCheckWorker", "Legacy work skipped; StreamCheckService owns monitoring")
        return Result.success()
    }
}
