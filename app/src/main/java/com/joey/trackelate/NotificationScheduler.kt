package com.joey.trackelate

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

internal object NotificationScheduler {
    private const val CHANNEL_ID = "trackelate_daily_journal"
    private const val NOTIFICATION_ID = 2406
    private const val REQUEST_CODE = 2406

    fun scheduleDaily(context: Context, time: LocalTime) {
        ensureChannel(context)
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = reminderPendingIntent(context)
        alarmManager.cancel(pendingIntent)
        val triggerAtMillis = nextTriggerMillis(time)
        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            AlarmManager.INTERVAL_DAY,
            pendingIntent,
        )
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(reminderPendingIntent(context))
    }

    fun showNotification(context: Context) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Trackelate")
            .setContentText("Time to log today before the day slips away.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Time to log today before the day slips away."),
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Daily journal reminders",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Reminds you to add today's Trackelate entry."
        }
        manager.createNotificationChannel(channel)
    }

    private fun reminderPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, JournalReminderReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun nextTriggerMillis(time: LocalTime): Long {
        val now = LocalDateTime.now()
        var trigger = now.withHour(time.hour).withMinute(time.minute).withSecond(0).withNano(0)
        if (trigger.isBefore(now)) {
            trigger = trigger.plusDays(1)
        }
        return trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
