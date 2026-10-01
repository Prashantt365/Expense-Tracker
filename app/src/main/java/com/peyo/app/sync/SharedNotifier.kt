package com.peyo.app.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.peyo.app.AppCurrency
import com.peyo.app.MainActivity
import com.peyo.app.R
import com.peyo.app.data.SharedShare
import java.util.concurrent.TimeUnit

/**
 * Tells the user when somebody else has put them on an expense.
 *
 * There is no push service behind this: the app asks the server itself, from [SharedCheckWorker]
 * in the background and whenever it is open, and raises a local notification for anything new.
 * Android runs periodic background work at most every fifteen minutes, and later when the phone
 * is saving battery, so a notification can trail the expense by a while.
 */
object SharedNotifier {

    private const val CHANNEL = "shared_expenses"
    private const val SUMMARY_ID = 4100

    fun notify(context: Context, fresh: List<SharedShare>) {
        if (fresh.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_BALANCES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // A handful are each worth a line of their own; a first check that finds a long backlog
        // gets one summary rather than a wall of notifications.
        if (fresh.size > 3) {
            val owe = fresh.filter { it.iOwe }.sumOf { it.amountPaise }
            val owed = fresh.filterNot { it.iOwe }.sumOf { it.amountPaise }
            val text = listOfNotNull(
                if (owe > 0) "You owe ${amount(owe, fresh.first().currency)}" else null,
                if (owed > 0) "you're owed ${amount(owed, fresh.first().currency)}" else null
            ).joinToString(", ")
            post(
                manager, context, SUMMARY_ID,
                "${fresh.size} expenses shared with you",
                text.replaceFirstChar { it.uppercase() },
                open
            )
            return
        }
        fresh.forEach { share ->
            val what = share.merchant.ifBlank { share.category.ifBlank { "an expense" } }
            val title = if (share.iOwe) "${share.ownerName} added you to $what"
            else "${share.ownerName} recorded that you paid for $what"
            val body = if (share.iOwe) "You owe ${amount(share.amountPaise, share.currency)}."
            else "${share.ownerName} owes you ${amount(share.amountPaise, share.currency)}."
            post(manager, context, share.splitId.hashCode(), title, body, open)
        }
    }

    private fun post(
        manager: NotificationManagerCompat,
        context: Context,
        id: Int,
        title: String,
        body: String,
        open: PendingIntent
    ) {
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_widget_sprout)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // Checked again by the caller; the try is for a permission revoked in between.
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Shared expenses", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a friend adds you to an expense they recorded."
            }
        )
    }

    /** The owner's currency, which may not be this phone's. */
    private fun amount(minor: Long, currency: String): String {
        val major = "%,.2f".format(minor / 100.0)
        return if (currency == AppCurrency.code) AppCurrency.currency.symbol + major else "$currency $major"
    }
}

/** The background check behind [SharedNotifier]. Does nothing unless signed in with a number. */
class SharedCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppCurrency.load(applicationContext)
        val account = Account(applicationContext)
        val session = account.stored() ?: return Result.success()
        if (session.phone.isBlank()) return Result.success()
        // Offline or the server struggling: the next period tries again, so nothing is retried
        // here on top of it. Notifying is the ledger's own job, whoever asks it to refresh.
        SharedLedger(applicationContext).refresh()
        return Result.success()
    }

    companion object {
        private const val NAME = "shared-check"

        /** Safe to call on every launch: an existing schedule is kept, not restarted. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SharedCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
