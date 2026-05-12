package com.joey.trackelate

import java.time.LocalDate
import java.time.LocalTime

internal data class QuantityEntry(
    val columnKey: String,
    val name: String,
    val unit: String,
    val active: Boolean,
    val order: Int,
)

internal data class JournalDay(
    val date: LocalDate,
    val grade: Int? = null,
    val description: String = "",
    val values: Map<String, String?> = emptyMap(),
)

internal data class JournalSnapshot(
    val entries: List<QuantityEntry> = emptyList(),
    val days: List<JournalDay> = emptyList(),
    val notificationTime: LocalTime = LocalTime.of(21, 30),
)

internal data class JournalUiState(
    val entries: List<QuantityEntry> = emptyList(),
    val days: List<JournalDay> = emptyList(),
    val selectedDate: LocalDate = LocalDate.now(),
    val notificationTime: LocalTime = LocalTime.of(21, 30),
    val loading: Boolean = true,
    val message: String? = null,
)

internal fun gradeLabel(grade: Int): String = when (grade.coerceIn(0, 6)) {
    6 -> "good core memory"
    5 -> "very positive"
    4 -> "positive"
    3 -> "neutral"
    2 -> "bad"
    1 -> "very bad"
    else -> "bad core memory"
}
