package com.joey.trackelate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalTime

internal class JournalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        NotificationScheduler.showNotification(context)
    }
}

internal class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val snapshot = runCatching { CsvJournalRepository(context).loadSnapshot() }.getOrNull()
        val time = snapshot?.notificationTime ?: LocalTime.of(21, 30)
        if (isAllowedReminderTime(time)) {
            NotificationScheduler.scheduleDaily(context, time)
        }
    }
}
