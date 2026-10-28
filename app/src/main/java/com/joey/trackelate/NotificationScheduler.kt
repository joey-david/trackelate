package com.joey.trackelate

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

internal object NotificationScheduler {
    private const val CHANNEL_ID = "trackelate_daily_journal_v2"
    private const val NOTIFICATION_ID = 2406
    private const val REQUEST_CODE_ALARM = 2406
    private const val REQUEST_CODE_OPEN = 2407
    private const val REQUEST_CODE_SNOOZE = 2408
    private const val REQUEST_CODE_SNOOZE_ALARM = 2409
    private const val REQUEST_CODE_NOTE = 2410
    private const val REQUEST_CODE_GRADE_BASE = 2420
    private const val PREFS_NAME = "trackelate_notification_state"
    private const val KEY_LAST_ACK_DATE = "last_ack_date"
    private const val SNOOZE_MS = 60L * 60L * 1000L

    internal val gradeColors = intArrayOf(
        0xFFE93127.toInt(),
        0xFFF47A7A.toInt(),
        0xFFF4A61D.toInt(),
        0xFFF0E34D.toInt(),
        0xFF38D642.toInt(),
        0xFF2E6EDB.toInt(),
        0xFF0F28E8.toInt(),
    )
    private val gradeButtonIds = intArrayOf(
        R.id.grade_0,
        R.id.grade_1,
        R.id.grade_2,
        R.id.grade_3,
        R.id.grade_4,
        R.id.grade_5,
        R.id.grade_6,
    )

    fun scheduleDaily(context: Context, time: LocalTime): Boolean {
        ensureChannel(context)
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        val pendingIntent = reminderPendingIntent(context, REQUEST_CODE_ALARM)
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
        alarmManager.cancel(reminderPendingIntent(context, REQUEST_CODE_ALARM))
    }

    fun snooze(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val trigger = System.currentTimeMillis() + SNOOZE_MS
        val pendingIntent = reminderPendingIntent(context, REQUEST_CODE_SNOOZE_ALARM)
        if (canScheduleExactAlarm(alarmManager)) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
        }
    }

    /**
     * The reminder: tap opens the day in Trackelate, or grade it right from the shade
     * with the seven colour chips, without opening the app at all.
     */
    fun showNotification(context: Context) {
        if (isAcknowledgedToday(context)) return
        ensureChannel(context)
        val targetDate = LocalDate.now()
        val gradeRow = gradeRowViews(context, targetDate, R.layout.notification_grade)
        val gradeRowExpanded = gradeRowViews(context, targetDate, R.layout.notification_grade_expanded)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_trackelate)
            .setColor(0xFF2E6EDB.toInt())
            .setContentTitle("How was today?")
            .setContentText("Tap a grade to log it, or open Trackelate.")
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(gradeRow)
            .setCustomBigContentView(gradeRowExpanded)
            .setContentIntent(openPendingIntent(context, targetDate))
            .addAction(
                Notification.Action.Builder(null, "Remind me in 1 h", snoozePendingIntent(context)).build(),
            )
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    /** After a quick grade: confirm it and offer an inline note, still without opening the app. */
    fun showLoggedNotification(context: Context, date: LocalDate, grade: Int) {
        ensureChannel(context)
        val noteInput = RemoteInput.Builder(EXTRA_NOTE_TEXT)
            .setLabel("What made it a ${gradeLabel(grade)} day?")
            .build()
        val noteIntent = Intent(context, JournalReminderReceiver::class.java)
            .setAction(ACTION_ADD_NOTE)
            .putExtra(EXTRA_TARGET_DATE, date.toString())
        val notePendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_NOTE,
            noteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val streak = runCatching { moodStreak(CsvJournalRepository(context).loadSnapshot().days) }.getOrDefault(0)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_trackelate)
            .setColor(gradeColors[grade.coerceIn(0, 6)])
            .setContentTitle("Logged $grade · ${gradeLabel(grade)}")
            .setContentText(if (streak > 1) "$streak-day streak. Add a note?" else "Add a note?")
            .setContentIntent(openPendingIntent(context, date))
            .addAction(
                Notification.Action.Builder(null, "Add note", notePendingIntent)
                    .addRemoteInput(noteInput)
                    .setAllowGeneratedReplies(false)
                    .build(),
            )
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(30L * 60L * 1000L)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
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

    /**
     * Opens the activity directly. The old version bounced through a BroadcastReceiver,
     * which Android 12+ blocks ("notification trampoline"), so taps did nothing.
     */
    internal fun openPendingIntent(context: Context, date: LocalDate): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_GRADING)
                .putExtra(EXTRA_TARGET_DATE, date.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    internal fun gradePendingIntent(context: Context, date: LocalDate, grade: Int, source: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_GRADE_BASE + grade + if (source == SOURCE_WIDGET) 10 else 0,
            Intent(context, JournalReminderReceiver::class.java)
                .setAction(ACTION_QUICK_GRADE)
                .putExtra(EXTRA_TARGET_DATE, date.toString())
                .putExtra(EXTRA_GRADE, grade)
                .putExtra(EXTRA_SOURCE, source),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun gradeRowViews(context: Context, date: LocalDate, layout: Int): RemoteViews =
        RemoteViews(context.packageName, layout).apply {
            gradeButtonIds.forEachIndexed { grade, id ->
                setInt(id, "setBackgroundColor", gradeColors[grade])
                setTextColor(id, if (grade in 1..4) Color.BLACK else Color.WHITE)
                setOnClickPendingIntent(id, gradePendingIntent(context, date, grade, SOURCE_NOTIFICATION))
            }
        }

    private fun snoozePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_SNOOZE,
            Intent(context, JournalReminderReceiver::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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

    private fun reminderPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, JournalReminderReceiver::class.java)
            .setAction(ACTION_SHOW_REMINDER)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
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
