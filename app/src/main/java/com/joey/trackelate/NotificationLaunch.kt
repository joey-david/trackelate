package com.joey.trackelate

import java.time.LocalDate

internal const val ACTION_SHOW_REMINDER = "com.joey.trackelate.action.SHOW_REMINDER"
internal const val ACTION_OPEN_GRADING = "com.joey.trackelate.action.OPEN_GRADING"
internal const val EXTRA_TARGET_DATE = "extra_target_date"

internal data class NotificationLaunchTarget(
    val date: LocalDate,
)
