package com.arthatis.wallet.reminder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.arthatis.wallet.MainActivity
import com.arthatis.wallet.R
import com.arthatis.wallet.WalletApplication
import com.arthatis.wallet.core.EntryKind
import com.arthatis.wallet.core.Tx
import com.arthatis.wallet.core.dueToday
import com.arthatis.wallet.core.dayOf
import com.arthatis.wallet.core.epochMillis
import com.arthatis.wallet.core.formatMinor
import com.arthatis.wallet.core.reminderDue
import com.arthatis.wallet.data.newId
import java.time.LocalDate

/** Daily, fully local: posts recurring entries that are due today and notifies the day before (H-1). Nothing leaves the phone. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as WalletApplication).graph
        val repo = graph.repo
        val today = LocalDate.now()
        val ledger = repo.snapshot()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)

        ledger.recurring.filter { it.deletedAt == null }.forEach { r ->
            val lastRun = r.lastRunDay?.let { dayOf(it) }
            if (dueToday(r, today, lastRun)) {
                repo.saveTx(Tx(newId(), r.walletId, null, r.categoryId, r.kind, r.amountMinor, note = r.name, occurredAt = epochMillis(today), recurringId = r.id, updatedAt = 0))
                repo.saveRecurring(r.copy(lastRunDay = epochMillis(today)))
            }
        }
        ledger.recurring.filter { reminderDue(it, today) }.forEach { r ->
            val cur = ledger.wallet(r.walletId)?.currency ?: "IDR"
            val intent = PendingIntent.getActivity(applicationContext, r.id.hashCode(), Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val note = NotificationCompat.Builder(applicationContext, "reminders")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(r.name)
                .setContentText((if (com.arthatis.wallet.data.Prefs(applicationContext).language == "en") "Tomorrow" else "Besok") + " · ${formatMinor(r.amountMinor, cur)}")
                .setContentIntent(intent).setAutoCancel(true).build()
            runCatching { manager.notify(r.id.hashCode(), note) }
        }
        return Result.success()
    }
}
