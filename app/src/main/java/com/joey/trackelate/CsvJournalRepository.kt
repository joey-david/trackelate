package com.joey.trackelate

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

internal class CsvJournalRepository(context: Context) {
    private val appContext = context.applicationContext
    private val filesDir = appContext.filesDir
    private val entriesFile = File(filesDir, ENTRIES_FILE)
    private val daysFile = File(filesDir, DAYS_FILE)
    private val settingsFile = File(filesDir, SETTINGS_FILE)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSnapshot(): JournalSnapshot {
        ensureFiles()

        var entries = readEntries()
        val notificationTime = readNotificationTime()
        val days = readDays(entries.map { it.columnKey }).toMutableList()
        var changed = false

        val seededEntries = seedDefaultEntries(entries)
        if (seededEntries != entries) {
            entries = seededEntries
            changed = true
        }

        if (days.none { it.date == LocalDate.now() }) {
            days += JournalDay(date = LocalDate.now())
            changed = true
        }

        if (!prefs.getBoolean(KEY_CLEARED_IMPORTED_NEUTRAL_GRADES, false)) {
            days.replaceAll { day ->
                if (day.date.isBefore(IMPORTED_NEUTRAL_GRADE_CUTOFF)) day.copy(grade = null) else day
            }
            prefs.edit().putBoolean(KEY_CLEARED_IMPORTED_NEUTRAL_GRADES, true).apply()
            changed = true
        }

        val snapshot = JournalSnapshot(
            entries = entries,
            days = days.sortedBy { it.date },
            notificationTime = notificationTime,
        )
        if (changed) {
            saveSnapshot(snapshot)
        }

        return snapshot
    }

    fun saveSnapshot(snapshot: JournalSnapshot) {
        ensureFiles()

        writeEntries(snapshot.entries.sortedBy { it.order })
        writeDays(snapshot.entries.sortedBy { it.order }, snapshot.days.sortedBy { it.date })
        writeSettings(snapshot.notificationTime)
    }

    fun importActivityData(currentSnapshot: JournalSnapshot, imported: ImportedActivityData): Pair<JournalSnapshot, ActivityImportSummary> {
        val entries = ensureImportEntries(currentSnapshot.entries)
        val keyByField = ImportField.values().associateWith { field ->
            entries.first { entry ->
                entry.name.equals(field.displayName, ignoreCase = true) ||
                    field.aliases.any { alias -> entry.name.equals(alias, ignoreCase = true) }
            }.columnKey
        }

        val dayByDate = currentSnapshot.days.associateBy { it.date }.toMutableMap()
        var updatedValueCount = 0

        imported.days.forEach { (date, importedDay) ->
            val existingDay = dayByDate[date] ?: JournalDay(date = date)
            val values = existingDay.values.toMutableMap()

            importedDay.clears.forEach { field ->
                keyByField[field]?.let { key ->
                    if (values.remove(key) != null) {
                        updatedValueCount += 1
                    }
                }
            }

            importedDay.valuesByField().forEach { (field, value) ->
                keyByField[field]?.let { key ->
                    if (shouldWriteImportedValue(imported.kind, field, values[key]) && values[key] != value) {
                        values[key] = value
                        updatedValueCount += 1
                    }
                }
            }

            dayByDate[date] = existingDay.copy(values = values)
        }

        val next = JournalSnapshot(
            entries = entries,
            days = dayByDate.values.sortedBy { it.date },
            notificationTime = currentSnapshot.notificationTime,
        )
        return next to ActivityImportSummary(
            dayCount = imported.days.size,
            updatedValueCount = updatedValueCount,
            sourceSummary = imported.sourceSummary,
            warnings = imported.warnings,
        )
    }

    private fun ensureFiles() {
        if (!entriesFile.exists()) {
            entriesFile.writeText("id,name,unit,active,order\n")
        }
        if (!daysFile.exists()) {
            daysFile.writeText("date,grade,description\n")
        }
        if (!settingsFile.exists()) {
            settingsFile.writeText("key,value\nnotification_time,21:30\n")
        }
    }

    private fun readEntries(): List<QuantityEntry> {
        if (!entriesFile.exists()) return emptyList()
        val lines = entriesFile.readLines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyList()

        return lines.drop(1).mapNotNull { line ->
            val cells = parseCsvLine(line)
            if (cells.size < 5) return@mapNotNull null
            QuantityEntry(
                columnKey = cells[0],
                name = cells[1],
                unit = cells[2],
                active = cells[3].toBooleanStrictOrNull() ?: true,
                order = cells[4].toIntOrNull() ?: 0,
            )
        }.sortedBy { it.order }
    }

    private fun writeEntries(entries: List<QuantityEntry>) {
        val output = buildString {
            appendLine("id,name,unit,active,order")
            entries.forEach { entry ->
                appendLine(
                    csvRow(
                        entry.columnKey,
                        entry.name,
                        entry.unit,
                        entry.active.toString(),
                        entry.order.toString(),
                    ),
                )
            }
        }
        entriesFile.writeText(output)
    }

    private fun readDays(entryKeys: List<String>): List<JournalDay> {
        if (!daysFile.exists()) return emptyList()
        val lines = daysFile.readLines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyList()

        val header = parseCsvLine(lines.first())
        val columnIndex = header.withIndex().associate { it.value to it.index }
        val customKeys = header.drop(3)
        val keySet = entryKeys.toSet()

        return lines.drop(1).mapNotNull { line ->
            val cells = parseCsvLine(line)
            if (cells.size < 3) return@mapNotNull null
            val date = LocalDate.parse(cells[0])
            val grade = cells.getOrNull(1)?.takeUnless { it.isBlank() || it == "NaN" }?.toIntOrNull()?.coerceIn(0, 6)
            val description = cells.getOrNull(2).orEmpty()
            val values = customKeys
                .filter { it in keySet }
                .associateWith { key ->
                    val raw = cells.getOrNull(columnIndex[key] ?: -1).orEmpty()
                    raw.takeUnless { it.isBlank() || it == "NaN" }
                }
            JournalDay(
                date = date,
                grade = grade,
                description = description,
                values = values,
            )
        }.sortedBy { it.date }
    }

    private fun writeDays(entries: List<QuantityEntry>, days: List<JournalDay>) {
        val entryKeys = entries.map { it.columnKey }
        val output = buildString {
            appendLine(buildDaysHeader(entryKeys))
            days.forEach { day ->
                val row = buildList {
                    add(day.date.toString())
                    add(day.grade?.coerceIn(0, 6)?.toString().orEmpty())
                    add(day.description.replace('\n', ' ').take(140))
                    entryKeys.forEach { key -> add(day.values[key].orNaN()) }
                }
                appendLine(csvRow(*row.toTypedArray()))
            }
        }
        daysFile.writeText(output)
    }

    private fun buildDaysHeader(entryKeys: List<String>): String =
        buildString {
            append("date,grade,description")
            entryKeys.forEach { append(",").append(csvEscape(it)) }
        }

    private fun readNotificationTime(): LocalTime {
        prefs.getString(KEY_NOTIFICATION_TIME, null)?.let { raw ->
            parseTimeOrNull(raw)?.let { return it }
        }
        if (!settingsFile.exists()) return LocalTime.of(21, 30)
        val lines = settingsFile.readLines().filter { it.isNotBlank() }
        if (lines.size <= 1) return LocalTime.of(21, 30)
        return lines.drop(1).mapNotNull { line ->
            val cells = parseCsvLine(line)
            if (cells.size < 2) return@mapNotNull null
            if (cells[0] == "notification_time") {
                parseTimeOrNull(cells[1])
            } else {
                null
            }
        }.firstOrNull() ?: LocalTime.of(21, 30)
    }

    private fun writeSettings(notificationTime: LocalTime) {
        prefs.edit().putString(KEY_NOTIFICATION_TIME, notificationTime.format(TIME_FORMATTER)).apply()
        settingsFile.writeText(
            buildString {
                appendLine("key,value")
                appendLine(csvRow("notification_time", notificationTime.format(TIME_FORMATTER)))
            },
        )
    }

    fun upsertQuantity(
        currentEntries: List<QuantityEntry>,
        currentDays: List<JournalDay>,
        currentNotificationTime: LocalTime,
        name: String,
        unit: String,
        defaultHistory: String?,
        referenceDate: LocalDate,
    ): JournalSnapshot {
        val cleanedName = name.trim()
        val cleanedUnit = unit.trim()
        require(cleanedName.isNotEmpty())
        require(cleanedUnit.isNotEmpty())

        val existing = currentEntries.firstOrNull { it.name.equals(cleanedName, ignoreCase = true) }
        val nextEntries = currentEntries.toMutableList()
        val nextDays = currentDays.map { it.copy(values = it.values.toMutableMap()) }.toMutableList()

        val entry = if (existing != null) {
            val index = nextEntries.indexOfFirst { it.columnKey == existing.columnKey }
            val updated = existing.copy(name = cleanedName, unit = cleanedUnit, active = true)
            nextEntries[index] = updated
            updated
        } else {
            val newKey = uniqueColumnKey(cleanedName, nextEntries.map { it.columnKey }.toSet())
            val newEntry = QuantityEntry(
                columnKey = newKey,
                name = cleanedName,
                unit = cleanedUnit,
                active = true,
                order = nextEntries.maxOfOrNull { it.order }?.plus(1) ?: 0,
            )
            nextEntries += newEntry
            newEntry
        }

        val backfill = defaultHistory?.takeUnless { it.isBlank() }

        return JournalSnapshot(
            entries = nextEntries.sortedBy { it.order },
            days = nextDays.map {
                if (it.date.isBefore(referenceDate)) {
                    it.copy(
                        values = it.values.toMutableMap().apply {
                            this[entry.columnKey] = backfill
                        },
                    )
                } else {
                    it
                }
            },
            notificationTime = currentNotificationTime,
        )
    }

    fun deactivateQuantity(
        currentEntries: List<QuantityEntry>,
        currentDays: List<JournalDay>,
        currentNotificationTime: LocalTime,
        columnKey: String,
    ): JournalSnapshot {
        val nextEntries = currentEntries.map {
            if (it.columnKey == columnKey) it.copy(active = false) else it
        }
        val nextDays = currentDays.map { day ->
            day.copy(
                values = day.values.toMutableMap().apply {
                    this[columnKey] = null
                },
            )
        }
        return JournalSnapshot(nextEntries, nextDays, currentNotificationTime)
    }

    fun updateDay(
        currentEntries: List<QuantityEntry>,
        currentDays: List<JournalDay>,
        currentNotificationTime: LocalTime,
        date: LocalDate,
        grade: Int? = null,
        description: String? = null,
        quantityKey: String? = null,
        quantityValue: String? = null,
    ): JournalSnapshot {
        val nextDays = currentDays.map { it.copy(values = it.values.toMutableMap()) }.toMutableList()
        val index = nextDays.indexOfFirst { it.date == date }
        val existing = if (index >= 0) nextDays[index] else JournalDay(date = date)
        val nextValues = existing.values.toMutableMap()

        if (quantityKey != null) {
            nextValues[quantityKey] = quantityValue?.takeUnless { it.isBlank() }
        }

        val nextDay = existing.copy(
            grade = grade?.coerceIn(0, 6) ?: existing.grade,
            description = description?.replace('\n', ' ')?.take(140) ?: existing.description,
            values = nextValues,
        )

        if (index >= 0) {
            nextDays[index] = nextDay
        } else {
            nextDays += nextDay
        }

        val normalizedDays = nextDays.map { day ->
            day.copy(
                values = currentEntries.associate { entry ->
                    entry.columnKey to day.values[entry.columnKey].takeUnless {
                        it.isNullOrBlank() || it == "NaN"
                    }
                },
            )
        }

        return JournalSnapshot(
            entries = currentEntries,
            days = normalizedDays.sortedBy { it.date },
            notificationTime = currentNotificationTime,
        )
    }

    fun updateNotificationTime(
        currentEntries: List<QuantityEntry>,
        currentDays: List<JournalDay>,
        time: LocalTime,
    ): JournalSnapshot {
        return JournalSnapshot(
            entries = currentEntries,
            days = currentDays,
            notificationTime = time,
        )
    }

    private fun parseTimeOrNull(raw: String): LocalTime? = try {
        LocalTime.parse(raw, TIME_FORMATTER)
    } catch (_: Exception) {
        null
    }

    private fun uniqueColumnKey(name: String, existing: Set<String>): String {
        val base = name.trim().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "entry" }
        if (base !in existing) return base

        val counter = AtomicInteger(2)
        while (true) {
            val candidate = "${base}_${counter.getAndIncrement()}"
            if (candidate !in existing) return candidate
        }
    }

    private fun csvRow(vararg cells: String): String = cells.joinToString(",") { csvEscape(it) }

    private fun csvEscape(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }

    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                quoted && char == '"' && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index += 1
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    cells += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            index += 1
        }
        cells += current.toString()
        return cells
    }

    private fun String?.orNaN(): String = this?.takeIf { it.isNotBlank() } ?: "NaN"

    private fun seedDefaultEntries(entries: List<QuantityEntry>): List<QuantityEntry> {
        val existingNames = entries.map { it.name.lowercase(Locale.US) }.toSet()
        var nextOrder = entries.maxOfOrNull { it.order }?.plus(1) ?: 0
        val nextEntries = entries.toMutableList()

        DEFAULT_ENTRIES.forEach { seed ->
            if (seed.name.lowercase(Locale.US) !in existingNames) {
                val key = uniqueColumnKey(seed.name, nextEntries.map { it.columnKey }.toSet())
                nextEntries += QuantityEntry(
                    columnKey = key,
                    name = seed.name,
                    unit = seed.unit,
                    active = true,
                    order = nextOrder++,
                )
            }
        }

        return nextEntries.sortedBy { it.order }
    }

    private fun ensureImportEntries(entries: List<QuantityEntry>): List<QuantityEntry> {
        val nextEntries = entries.toMutableList()
        var nextOrder = nextEntries.maxOfOrNull { it.order }?.plus(1) ?: 0

        ImportField.values().forEach { field ->
            val existingIndex = nextEntries.indexOfFirst { entry ->
                entry.name.equals(field.displayName, ignoreCase = true) ||
                    field.aliases.any { alias -> entry.name.equals(alias, ignoreCase = true) }
            }
            if (existingIndex >= 0) {
                val existing = nextEntries[existingIndex]
                nextEntries[existingIndex] = existing.copy(
                    name = field.displayName,
                    unit = field.unit,
                    active = true,
                )
            } else {
                val key = uniqueColumnKey(field.displayName, nextEntries.map { it.columnKey }.toSet())
                nextEntries += QuantityEntry(
                    columnKey = key,
                    name = field.displayName,
                    unit = field.unit,
                    active = true,
                    order = nextOrder++,
                )
            }
        }

        val activeImportNames = ImportField.values()
            .flatMap { listOf(it.displayName) + it.aliases }
            .map { it.lowercase(Locale.US) }
            .toSet()
        nextEntries.replaceAll { entry ->
            val normalized = entry.name.lowercase(Locale.US)
            if (normalized in OBSOLETE_IMPORT_FIELD_NAMES && normalized !in activeImportNames) {
                entry.copy(active = false)
            } else {
                entry
            }
        }

        return nextEntries.sortedBy { it.order }
    }

    private fun shouldWriteImportedValue(kind: ActivityImportKind, field: ImportField, existingValue: String?): Boolean {
        if (kind != ActivityImportKind.GOOGLE_FIT_TAKEOUT) return true
        if (existingValue.isNullOrBlank() || existingValue == "NaN") return true
        return field !in MANUAL_WORKOUT_DETAIL_FIELDS
    }

    companion object {
        private const val ENTRIES_FILE = "journal_entries.csv"
        private const val DAYS_FILE = "journal_days.csv"
        private const val SETTINGS_FILE = "journal_settings.csv"
        private const val PREFS_NAME = "trackelate_settings"
        private const val KEY_NOTIFICATION_TIME = "notification_time"
        private const val KEY_CLEARED_IMPORTED_NEUTRAL_GRADES = "cleared_imported_neutral_grades_before_2026_05_01"
        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val IMPORTED_NEUTRAL_GRADE_CUTOFF: LocalDate = LocalDate.of(2026, 5, 1)
        private val DEFAULT_ENTRIES = listOf(
            SeedEntry("number of steps", "dimensionless"),
            SeedEntry("time slept previous night", "time"),
            SeedEntry("time exercising", "time"),
            SeedEntry("time spent socializing irl", "time"),
            SeedEntry("wake up time", "time"),
        )
        private val MANUAL_WORKOUT_DETAIL_FIELDS = setOf(
            ImportField.CARDIO_TIME,
            ImportField.STRENGTH_TIME,
        )
        private val OBSOLETE_IMPORT_FIELD_NAMES = setOf(
            "running distance",
            "gym sets",
            "gym volume",
            "time spent running",
            "time spent working out in gym",
            "time biking",
            "time walking",
            "time playing tennis",
        )
    }
}

private data class SeedEntry(
    val name: String,
    val unit: String,
)
