package com.arthatis.wallet

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.arthatis.wallet.reminder.ReminderWorker
import java.util.concurrent.TimeUnit

class WalletApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        val lang = com.arthatis.wallet.data.Prefs(this).language
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags(if (lang == "en") "en" else "id"))
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("reminders", if (lang == "en") "Reminders" else "Pengingat", NotificationManager.IMPORTANCE_DEFAULT))
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("reminders", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
