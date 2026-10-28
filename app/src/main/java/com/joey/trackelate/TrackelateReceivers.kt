package com.joey.trackelate

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.LocalDate
import java.time.LocalTime

/** Lets an open app refresh after a notification or widget wrote to the journal. */
internal object JournalChanges {
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> = events.asSharedFlow()

    fun notifyChanged(context: Context) {
        events.tryEmit(Unit)
        MoodWidgetProvider.updateAll(context)
    }
}

internal class JournalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_QUICK_GRADE -> handleQuickGrade(context, intent)
            ACTION_ADD_NOTE -> handleAddNote(context, intent)
            ACTION_SNOOZE -> NotificationScheduler.snooze(context)
            else -> handleShowReminder(context)
        }
    }

    private fun handleShowReminder(context: Context) {
        NotificationScheduler.showNotification(context)
        rescheduleReminder(context)
    }

    private fun handleQuickGrade(context: Context, intent: Intent) {
        val date = intent.targetDate()
        val grade = intent.getIntExtra(EXTRA_GRADE, -1).takeIf { it in 0..6 } ?: return
        val pending = goAsync()
        Thread {
            try {
                CsvJournalRepository(context).edit { snapshot ->
                    CsvJournalRepository(context).updateDay(
                        currentEntries = snapshot.entries,
                        currentDays = snapshot.days,
                        currentNotificationTime = snapshot.notificationTime,
                        date = date,
                        grade = grade,
                    )
                }
                NotificationScheduler.markAcknowledged(context, date)
                if (intent.getStringExtra(EXTRA_SOURCE) == SOURCE_NOTIFICATION) {
                    NotificationScheduler.showLoggedNotification(context, date, grade)
                }
                JournalChanges.notifyChanged(context)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handleAddNote(context: Context, intent: Intent) {
        val date = intent.targetDate()
        val note = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(EXTRA_NOTE_TEXT)
            ?.toString()
            ?.trim()
            .orEmpty()
        val pending = goAsync()
        Thread {
            try {
                if (note.isNotEmpty()) {
                    CsvJournalRepository(context).edit { snapshot ->
                        val existing = snapshot.days.firstOrNull { it.date == date }?.description.orEmpty()
                        CsvJournalRepository(context).updateDay(
                            currentEntries = snapshot.entries,
                            currentDays = snapshot.days,
                            currentNotificationTime = snapshot.notificationTime,
                            date = date,
                            description = listOf(existing, note).filter { it.isNotBlank() }.joinToString(" · ").take(140),
                        )
                    }
                    JournalChanges.notifyChanged(context)
                }
                NotificationScheduler.dismiss(context)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun Intent.targetDate(): LocalDate =
        getStringExtra(EXTRA_TARGET_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.takeUnless { it.isAfter(LocalDate.now()) }
            ?: LocalDate.now()
}

internal class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        rescheduleReminder(context)
        MoodWidgetProvider.updateAll(context)
    }
}

private fun rescheduleReminder(context: Context) {
    val snapshot = runCatching { CsvJournalRepository(context).loadSnapshot() }.getOrNull()
    val time = snapshot?.notificationTime ?: LocalTime.of(21, 30)
    if (isAllowedReminderTime(time)) {
        NotificationScheduler.scheduleDaily(context, time)
    }
}
