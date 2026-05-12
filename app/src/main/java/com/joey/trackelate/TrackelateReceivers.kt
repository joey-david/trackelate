package com.joey.trackelate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalTime

internal class JournalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_GRADING -> handleOpenGrading(context, intent)
            else -> handleShowReminder(context)
        }
    }

    private fun handleShowReminder(context: Context) {
        NotificationScheduler.showNotification(context)
        rescheduleReminder(context)
    }

    private fun handleOpenGrading(context: Context, intent: Intent) {
        val targetDate = intent.getStringExtra(EXTRA_TARGET_DATE)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        } ?: LocalDate.now()
        NotificationScheduler.markAcknowledged(context, targetDate)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_TARGET_DATE, targetDate.toString())
        }
        context.startActivity(launchIntent)
    }
}

internal class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        rescheduleReminder(context)
    }
}

private fun rescheduleReminder(context: Context) {
    val snapshot = runCatching { CsvJournalRepository(context).loadSnapshot() }.getOrNull()
    val time = snapshot?.notificationTime ?: LocalTime.of(21, 30)
    if (isAllowedReminderTime(time)) {
        NotificationScheduler.scheduleDaily(context, time)
    }
}
