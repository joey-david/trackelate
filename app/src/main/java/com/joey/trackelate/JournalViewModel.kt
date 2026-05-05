package com.joey.trackelate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

internal class JournalViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CsvJournalRepository(application)
    private val _state = MutableStateFlow(JournalUiState())
    val state: StateFlow<JournalUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.loadSnapshot() }
                .onSuccess { snapshot ->
                    _state.value = snapshot.toUiState(_state.value)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        loading = false,
                        message = error.message ?: "Could not load journal",
                    )
                }
        }
    }

    fun selectDate(date: LocalDate) {
        if (date.isAfter(LocalDate.now())) return
        _state.value = _state.value.copy(selectedDate = date, message = null)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun updateGrade(date: LocalDate, grade: Int) {
        if (date.isAfter(LocalDate.now())) return
        commitChange { snapshot ->
            repository.updateDay(
                currentEntries = snapshot.entries,
                currentDays = snapshot.days,
                currentNotificationTime = snapshot.notificationTime,
                date = date,
                grade = grade,
            )
        }
    }

    fun updateDescription(date: LocalDate, description: String) {
        if (date.isAfter(LocalDate.now())) return
        commitChange { snapshot ->
            repository.updateDay(
                currentEntries = snapshot.entries,
                currentDays = snapshot.days,
                currentNotificationTime = snapshot.notificationTime,
                date = date,
                description = description,
            )
        }
    }

    fun updateQuantity(date: LocalDate, columnKey: String, value: String) {
        if (date.isAfter(LocalDate.now())) return
        commitChange { snapshot ->
            repository.updateDay(
                currentEntries = snapshot.entries,
                currentDays = snapshot.days,
                currentNotificationTime = snapshot.notificationTime,
                date = date,
                quantityKey = columnKey,
                quantityValue = value,
            )
        }
    }

    fun addQuantity(name: String, unit: String, backfillOlderDays: String?) {
        val selected = _state.value.selectedDate
        if (selected.isAfter(LocalDate.now())) return
        commitChange { snapshot ->
            repository.upsertQuantity(
                currentEntries = snapshot.entries,
                currentDays = snapshot.days,
                currentNotificationTime = snapshot.notificationTime,
                name = name,
                unit = unit,
                defaultHistory = backfillOlderDays,
                referenceDate = selected,
            )
        }
    }

    fun deleteQuantity(columnKey: String) {
        commitChange { snapshot ->
            repository.deactivateQuantity(
                currentEntries = snapshot.entries,
                currentDays = snapshot.days,
                currentNotificationTime = snapshot.notificationTime,
                columnKey = columnKey,
            )
        }
    }

    fun setNotificationTime(time: LocalTime) {
        if (!isAllowedReminderTime(time)) {
            _state.value = _state.value.copy(
                message = "Choose a reminder between 6:00 PM and 4:00 AM.",
            )
            return
        }
        commitChange { snapshot ->
            repository.updateNotificationTime(
                currentEntries = snapshot.entries,
                currentDays = snapshot.days,
                time = time,
            )
        }
    }

private fun commitChange(transform: (JournalSnapshot) -> JournalSnapshot) {
        val snapshot = _state.value.toSnapshot()
        val next = transform(snapshot)
        _state.value = next.toUiState(_state.value)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.saveSnapshot(next) }
                .onFailure { error ->
                    _state.value = _state.value.copy(message = error.message ?: "Save failed")
                }
        }
    }

    private fun JournalSnapshot.toUiState(previous: JournalUiState): JournalUiState {
        val selected = previous.selectedDate.takeIf { days.any { day -> day.date == it } } ?: previous.selectedDate
        return previous.copy(
            entries = entries,
            days = days,
            selectedDate = selected,
            notificationTime = notificationTime,
            loading = false,
            message = null,
        )
    }

    private fun JournalUiState.toSnapshot(): JournalSnapshot = JournalSnapshot(
        entries = entries,
        days = days,
        notificationTime = notificationTime,
    )
}

internal fun isAllowedReminderTime(time: LocalTime): Boolean {
    val afterSixPm = !time.isBefore(LocalTime.of(18, 0))
    val beforeOrAtFourAm = !time.isAfter(LocalTime.of(4, 0))
    return afterSixPm || beforeOrAtFourAm
}
