package com.joey.trackelate

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

internal object NotificationScheduler {
    private const val CHANNEL_ID = "trackelate_daily_journal_v2"
    private const val NOTIFICATION_ID = 2406
    private const val REQUEST_CODE_ALARM = 2406
    private const val REQUEST_CODE_OPEN = 2407
    private const val PREFS_NAME = "trackelate_notification_state"
    private const val KEY_LAST_ACK_DATE = "last_ack_date"

    fun scheduleDaily(context: Context, time: LocalTime): Boolean {
        ensureChannel(context)
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        val pendingIntent = reminderPendingIntent(context)
        alarmManager.cancel(pendingIntent)
        val triggerAtMillis = nextTriggerMillis(time)
        return if (canScheduleExactAlarm(alarmManager)) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            true
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            false
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(reminderPendingIntent(context))
    }

    fun showNotification(context: Context) {
        if (isAcknowledgedToday(context)) return
        ensureChannel(context)
        val targetDate = LocalDate.now()
        val contentIntent = Intent(context, JournalReminderReceiver::class.java).apply {
            action = ACTION_OPEN_GRADING
            putExtra(EXTRA_TARGET_DATE, targetDate.toString())
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Trackelate")
            .setContentText("Time to log today before the day slips away.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Time to log today before the day slips away."),
            )
            .setContentIntent(
                PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE_OPEN,
                    contentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSound(notificationSoundUri(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun markAcknowledged(context: Context, date: LocalDate) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_ACK_DATE, date.toString())
            .apply()
    }

    fun shouldSuppressToday(context: Context): Boolean = isAcknowledgedToday(context)

    fun canScheduleExact(context: Context): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        return canScheduleExactAlarm(alarmManager)
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleExact(context)) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Daily journal reminders",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Reminds you to add today's Trackelate entry."
            setSound(
                notificationSoundUri(context),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        manager.createNotificationChannel(channel)
    }

    private fun notificationSoundUri(context: Context): Uri =
        Uri.parse("android.resource://${context.packageName}/${R.raw.trackelate_notification}")

    private fun reminderPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, JournalReminderReceiver::class.java)
            .setAction(ACTION_SHOW_REMINDER)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_ALARM,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun isAcknowledgedToday(context: Context): Boolean {
        val today = LocalDate.now().toString()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_ACK_DATE, null) == today
    }

    private fun canScheduleExactAlarm(alarmManager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun nextTriggerMillis(time: LocalTime): Long {
        val now = LocalDateTime.now()
        var trigger = now.withHour(time.hour).withMinute(time.minute).withSecond(0).withNano(0)
        if (!trigger.isAfter(now)) {
            trigger = trigger.plusDays(1)
        }
        return trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
