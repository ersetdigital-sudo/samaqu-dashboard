package com.samaqu.keyboard.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.samaqu.keyboard.R
import com.samaqu.keyboard.data.Prefs
import com.samaqu.keyboard.network.RetrofitClient
import java.util.concurrent.TimeUnit

/**
 * Periodically checks the backend for new "pending" orders and posts a
 * notification when the count increases.
 */
class OrderPoller(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val url = prefs.supabaseUrl
        val anonKey = prefs.supabaseAnonKey
        if (url.isBlank() || anonKey.isBlank()) return Result.success()

        return try {
            RetrofitClient.init(url, anonKey)
            val pending = RetrofitClient.api
                .getOrders()
                .filter { it.status.equals("pending", ignoreCase = true) }

            val lastCount = prefs.lastPendingCount
            if (pending.size > lastCount) {
                showNotification(pending.size - lastCount)
            }
            prefs.lastPendingCount = pending.size
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun showNotification(count: Int) {
        val mgr = applicationContext.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Order Baru", NotificationManager.IMPORTANCE_HIGH)
        )
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle(applicationContext.getString(R.string.notif_orders_title, count))
            .setContentText(applicationContext.getString(R.string.notif_orders_text))
            .setSmallIcon(R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        mgr.notify(NOTIF_ID, notif)
    }

    companion object {
        private const val CHANNEL = "samaqu_orders"
        private const val NOTIF_ID = 2
        private const val WORK_NAME = "order_poll"

        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<OrderPoller>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        }
    }
}
