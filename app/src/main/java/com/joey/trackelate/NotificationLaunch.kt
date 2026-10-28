package com.joey.trackelate

import java.time.LocalDate

internal const val ACTION_SHOW_REMINDER = "com.joey.trackelate.action.SHOW_REMINDER"
internal const val ACTION_OPEN_GRADING = "com.joey.trackelate.action.OPEN_GRADING"
internal const val ACTION_QUICK_GRADE = "com.joey.trackelate.action.QUICK_GRADE"
internal const val ACTION_ADD_NOTE = "com.joey.trackelate.action.ADD_NOTE"
internal const val ACTION_SNOOZE = "com.joey.trackelate.action.SNOOZE"
internal const val EXTRA_TARGET_DATE = "extra_target_date"
internal const val EXTRA_GRADE = "extra_grade"
internal const val EXTRA_NOTE_TEXT = "extra_note_text"
internal const val EXTRA_SOURCE = "extra_source"
internal const val SOURCE_NOTIFICATION = "notification"
internal const val SOURCE_WIDGET = "widget"

internal data class NotificationLaunchTarget(
    val date: LocalDate,
)
