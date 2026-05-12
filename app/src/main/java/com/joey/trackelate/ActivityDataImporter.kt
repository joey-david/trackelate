package com.joey.trackelate

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class ActivityImportSummary(
    val dayCount: Int,
    val updatedValueCount: Int,
    val sourceSummary: String,
    val warnings: List<String> = emptyList(),
)

internal data class ImportedActivityData(
    val days: Map<LocalDate, ImportedActivityDay>,
    val sourceSummary: String,
    val warnings: List<String>,
    val kind: ActivityImportKind,
)

internal enum class ActivityImportKind {
    WORKOUT_CSV,
    GOOGLE_FIT_TAKEOUT,
}

internal data class ImportedActivityDay(
    val steps: Int? = null,
    val sleepSeconds: Long? = null,
    val runningSeconds: Long? = null,
    val runningKilometers: Double? = null,
    val gymSeconds: Long? = null,
    val gymSets: Int? = null,
    val gymVolume: Double? = null,
    val bikingSeconds: Long? = null,
    val walkingSeconds: Long? = null,
    val tennisSeconds: Long? = null,
    val clears: Set<ImportField> = emptySet(),
) {
    fun mergedWith(other: ImportedActivityDay): ImportedActivityDay = ImportedActivityDay(
        steps = other.steps ?: steps,
        sleepSeconds = other.sleepSeconds ?: sleepSeconds,
        runningSeconds = sumLong(runningSeconds, other.runningSeconds),
        runningKilometers = sumDouble(runningKilometers, other.runningKilometers),
        gymSeconds = sumLong(gymSeconds, other.gymSeconds),
        gymSets = sumInt(gymSets, other.gymSets),
        gymVolume = sumDouble(gymVolume, other.gymVolume),
        bikingSeconds = sumLong(bikingSeconds, other.bikingSeconds),
        walkingSeconds = sumLong(walkingSeconds, other.walkingSeconds),
        tennisSeconds = sumLong(tennisSeconds, other.tennisSeconds),
        clears = clears + other.clears,
    )

    fun valuesByField(): Map<ImportField, String> = buildMap {
        steps?.takeIf { it > 0 }?.let { put(ImportField.STEPS, it.toString()) }
        sleepSeconds?.takeIf { it > 0 }?.let { put(ImportField.SLEEP, secondsToDuration(it)) }
        runningSeconds?.takeIf { it > 0 }?.let { put(ImportField.RUNNING_TIME, secondsToDuration(it)) }
        runningKilometers?.takeIf { it > 0.0 }?.let { put(ImportField.RUNNING_DISTANCE, formatNumber(it)) }
        gymSeconds?.takeIf { it > 0 }?.let { put(ImportField.GYM_TIME, secondsToDuration(it)) }
        gymSets?.takeIf { it > 0 }?.let { put(ImportField.GYM_SETS, it.toString()) }
        gymVolume?.takeIf { it > 0.0 }?.let { put(ImportField.GYM_VOLUME, formatNumber(it)) }
        exerciseSeconds().takeIf { it > 0 }?.let { put(ImportField.EXERCISE_TIME, secondsToDuration(it)) }
        bikingSeconds?.takeIf { it > 0 }?.let { put(ImportField.BIKING_TIME, secondsToDuration(it)) }
        walkingSeconds?.takeIf { it > 0 }?.let { put(ImportField.WALKING_TIME, secondsToDuration(it)) }
        tennisSeconds?.takeIf { it > 0 }?.let { put(ImportField.TENNIS_TIME, secondsToDuration(it)) }
    }

    private fun exerciseSeconds(): Long =
        listOfNotNull(runningSeconds, gymSeconds, bikingSeconds, tennisSeconds).sum()

    private fun sumLong(left: Long?, right: Long?): Long? = when {
        left == null -> right
        right == null -> left
        else -> left + right
    }

    private fun sumInt(left: Int?, right: Int?): Int? = when {
        left == null -> right
        right == null -> left
        else -> left + right
    }

    private fun sumDouble(left: Double?, right: Double?): Double? = when {
        left == null -> right
        right == null -> left
        else -> left + right
    }
}

internal enum class ImportField(
    val displayName: String,
    val unit: String,
    val aliases: Set<String> = emptySet(),
) {
    STEPS("steps", "dimensionless", setOf("number of steps")),
    SLEEP("sleep", "time", setOf("time slept previous night")),
    EXERCISE_TIME("time exercising", "time"),
    RUNNING_TIME("time spent running", "time"),
    RUNNING_DISTANCE("running distance", "km"),
    GYM_TIME("time spent working out in gym", "time"),
    GYM_SETS("gym sets", "dimensionless"),
    GYM_VOLUME("gym volume", "kg reps"),
    BIKING_TIME("time biking", "time"),
    WALKING_TIME("time walking", "time"),
    TENNIS_TIME("time playing tennis", "time"),
}

internal object ActivityDataImporter {
    private val workoutFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
    private val zone: ZoneId = ZoneId.systemDefault()

    fun parse(fileName: String, bytes: ByteArray): ImportedActivityData {
        val lowerName = fileName.lowercase(Locale.US)
        return if (lowerName.endsWith(".zip") || bytes.take(2) == listOf(0x50.toByte(), 0x4b.toByte())) {
            parseTakeoutZip(bytes)
        } else {
            parseWorkoutCsv(bytes.toString(Charsets.UTF_8))
        }
    }

    private fun parseWorkoutCsv(text: String): ImportedActivityData {
        val rows = parseCsv(text)
        if (rows.isEmpty()) {
            return ImportedActivityData(emptyMap(), "workout CSV", listOf("No workout rows found."), ActivityImportKind.WORKOUT_CSV)
        }
        val headers = rows.first()
        val grouped = linkedMapOf<WorkoutKey, MutableList<Map<String, String>>>()
        rows.drop(1).forEach { cells ->
            val row = headers.withIndex().associate { (index, name) -> name to cells.getOrElse(index) { "" } }
            val start = row["start_time"].orEmpty()
            val end = row["end_time"].orEmpty()
            if (start.isBlank() || end.isBlank()) return@forEach
            grouped.getOrPut(WorkoutKey(row["title"].orEmpty(), start, end)) { mutableListOf() } += row
        }

        val days = mutableMapOf<LocalDate, ImportedActivityDay>()
        grouped.forEach { (key, workoutRows) ->
            val start = parseWorkoutTime(key.startTime) ?: return@forEach
            val end = parseWorkoutTime(key.endTime) ?: start
            val date = start.toLocalDate()
            var runningSeconds = 0L
            var runningKm = 0.0
            var gymSets = 0
            var gymVolume = 0.0
            var hasGymWork = false

            workoutRows.forEach { row ->
                val exercise = row["exercise_title"].orEmpty()
                if (exercise.equals("Running", ignoreCase = true)) {
                    runningSeconds += row["duration_seconds"].orEmpty().toDoubleOrNull()?.roundToLong() ?: 0L
                    runningKm += row["distance_km"].orEmpty().toDoubleOrNull() ?: 0.0
                } else {
                    hasGymWork = true
                    if (!row["set_type"].orEmpty().equals("warmup", ignoreCase = true)) {
                        gymSets += 1
                    }
                    val weight = row["weight_kg"].orEmpty().toDoubleOrNull()
                    val reps = row["reps"].orEmpty().toDoubleOrNull()
                    if (weight != null && reps != null) {
                        gymVolume += weight * reps
                    }
                }
            }

            if (runningSeconds == 0L && runningKm > 0.0) {
                runningSeconds = (end.atZone(zone).toEpochSecond() - start.atZone(zone).toEpochSecond()).coerceAtLeast(0)
            }
            val gymSeconds = if (hasGymWork) {
                (end.atZone(zone).toEpochSecond() - start.atZone(zone).toEpochSecond()).coerceAtLeast(0)
            } else {
                0L
            }

            val imported = ImportedActivityDay(
                runningSeconds = runningSeconds.takeIf { it > 0 },
                runningKilometers = runningKm.takeIf { it > 0.0 },
                gymSeconds = gymSeconds.takeIf { it > 0 },
                gymSets = gymSets.takeIf { it > 0 },
                gymVolume = gymVolume.takeIf { it > 0.0 },
                clears = if (runningSeconds > 0 && !hasGymWork) {
                    setOf(ImportField.GYM_TIME, ImportField.GYM_SETS, ImportField.GYM_VOLUME)
                } else {
                    emptySet()
                },
            )
            days[date] = days[date]?.mergedWith(imported) ?: imported
        }
        return ImportedActivityData(
            days = days,
            sourceSummary = "workout CSV: ${grouped.size} workouts across ${days.size} days",
            warnings = listOf(
                "Workout CSV start/end times are used only to choose the day and estimate gym duration; running duration uses duration_seconds when present.",
            ),
            kind = ActivityImportKind.WORKOUT_CSV,
        )
    }

    private fun parseTakeoutZip(bytes: ByteArray): ImportedActivityData {
        val stepCandidates = mutableListOf<StepCandidate>()
        val sleepCandidates = mutableListOf<SleepCandidate>()
        val sessionDays = mutableMapOf<LocalDate, ImportedActivityDay>()

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (entry.isDirectory || !entry.name.endsWith(".json")) return@forEach
                val text = zip.readBytes().toString(Charsets.UTF_8)
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return@forEach
                when {
                    entry.name.contains("/All Data/") -> parseAllDataJson(json)?.let { parsed ->
                        when (parsed) {
                            is ParsedAllData.Steps -> stepCandidates += StepCandidate(stepPriority(parsed.source), parsed.source, parsed.days)
                            is ParsedAllData.Sleep -> sleepCandidates += SleepCandidate(sleepPriority(parsed.source), parsed.source, parsed.days)
                        }
                    }
                    entry.name.contains("/All Sessions/") -> parseSessionJson(json)?.let { (date, day) ->
                        sessionDays[date] = sessionDays[date]?.mergedWith(day) ?: day
                    }
                }
            }
        }

        val days = sessionDays.toMutableMap()
        val stepChoice = stepCandidates.minByOrNull { it.priority }
        stepChoice?.days?.forEach { (date, steps) ->
            days[date] = days[date]?.copy(steps = steps) ?: ImportedActivityDay(steps = steps)
        }
        val sleepChoice = sleepCandidates.minByOrNull { it.priority }
        sleepChoice?.days?.forEach { (date, seconds) ->
            days[date] = days[date]?.copy(sleepSeconds = seconds) ?: ImportedActivityDay(sleepSeconds = seconds)
        }

        val warnings = buildList {
            if (stepCandidates.size > 1) {
                add("Takeout contains ${stepCandidates.size} step streams; imported ${stepChoice?.source.orEmpty()} only to avoid double-counting phone/watch/synced streams.")
            }
            if (sleepCandidates.size > 1) {
                add("Takeout contains duplicate raw and merged sleep streams; imported ${sleepChoice?.source.orEmpty()} only.")
            }
        }
        return ImportedActivityData(
            days = days,
            sourceSummary = "Google Fit Takeout: ${days.size} days, ${stepChoice?.source ?: "no step stream"}, ${sleepChoice?.source ?: "no sleep stream"}",
            warnings = warnings,
            kind = ActivityImportKind.GOOGLE_FIT_TAKEOUT,
        )
    }

    private fun parseAllDataJson(json: JSONObject): ParsedAllData? {
        val source = json.optString("Data Source")
        val points = json.optJSONArray("Data Points") ?: return null
        return when {
            source.contains("step_count.delta") -> {
                val daily = mutableMapOf<LocalDate, Int>()
                for (index in 0 until points.length()) {
                    val point = points.optJSONObject(index) ?: continue
                    val date = instantFromNanos(point.optLong("endTimeNanos")).atZone(zone).toLocalDate()
                    daily[date] = (daily[date] ?: 0) + point.intFitValue()
                }
                ParsedAllData.Steps(source, daily)
            }
            source.contains("sleep.segment") -> {
                val daily = mutableMapOf<LocalDate, Long>()
                for (index in 0 until points.length()) {
                    val point = points.optJSONObject(index) ?: continue
                    val state = point.intFitValue()
                    if (state == 1) continue
                    val start = instantFromNanos(point.optLong("startTimeNanos")).atZone(zone)
                    val end = instantFromNanos(point.optLong("endTimeNanos")).atZone(zone)
                    val date = if (end.hour < 12) end.toLocalDate() else start.toLocalDate()
                    val seconds = (end.toEpochSecond() - start.toEpochSecond()).coerceAtLeast(0)
                    daily[date] = (daily[date] ?: 0L) + seconds
                }
                ParsedAllData.Sleep(source, daily)
            }
            else -> null
        }
    }

    private fun parseSessionJson(json: JSONObject): Pair<LocalDate, ImportedActivityDay>? {
        val activity = json.optString("fitnessActivity").lowercase(Locale.US)
        val start = parseInstant(json.optString("startTime")) ?: return null
        val end = parseInstant(json.optString("endTime"))
        val seconds = parseDurationSeconds(json.optString("duration"))
            ?: end?.let { (it.epochSecond - start.epochSecond).coerceAtLeast(0) }
            ?: return null
        val date = start.atZone(zone).toLocalDate()
        val day = when (activity) {
            "running" -> ImportedActivityDay(runningSeconds = seconds)
            "strength_training" -> ImportedActivityDay(gymSeconds = seconds)
            "biking" -> ImportedActivityDay(bikingSeconds = seconds)
            "walking", "walking.paced" -> ImportedActivityDay(walkingSeconds = seconds)
            "tennis" -> ImportedActivityDay(tennisSeconds = seconds)
            else -> null
        } ?: return null
        return date to day
    }

    private fun stepPriority(source: String): Int = when {
        source.contains("merge_step_deltas") -> 0
        source.contains("estimated_steps") -> 1
        source.contains("top_level") -> 2
        source.contains("com.xiaomi.wearable") -> 3
        source.contains(":android:") -> 4
        else -> 5
    }

    private fun sleepPriority(source: String): Int = when {
        source.contains(":merged") -> 0
        source.contains("com.xiaomi.wearable") -> 1
        else -> 2
    }

    private fun JSONObject.intFitValue(): Int {
        val fitValue = optJSONArray("fitValue")?.optJSONObject(0)?.optJSONObject("value") ?: return 0
        return when {
            fitValue.has("intVal") -> fitValue.optInt("intVal")
            fitValue.has("fpVal") -> fitValue.optDouble("fpVal").roundToInt()
            else -> 0
        }
    }

    private fun parseWorkoutTime(raw: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(raw, workoutFormatter) }.getOrNull()

    private fun parseInstant(raw: String): Instant? =
        runCatching { Instant.parse(raw) }.getOrNull()

    private fun parseDurationSeconds(raw: String): Long? {
        if (!raw.endsWith("s")) return null
        return raw.dropLast(1).toDoubleOrNull()?.roundToLong()
    }

    private fun instantFromNanos(nanos: Long): Instant =
        Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L)

    private fun parseCsv(text: String): List<List<String>> =
        text.lineSequence().filter { it.isNotBlank() }.map(::parseCsvLine).toList()

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

    private data class WorkoutKey(val title: String, val startTime: String, val endTime: String)
    private data class StepCandidate(val priority: Int, val source: String, val days: Map<LocalDate, Int>)
    private data class SleepCandidate(val priority: Int, val source: String, val days: Map<LocalDate, Long>)

    private sealed interface ParsedAllData {
        data class Steps(val source: String, val days: Map<LocalDate, Int>) : ParsedAllData
        data class Sleep(val source: String, val days: Map<LocalDate, Long>) : ParsedAllData
    }
}

private fun secondsToDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return "%02d:%02d:%02d".format(Locale.US, hours, minutes, secs)
}

private fun formatNumber(value: Double): String {
    val rounded = (value * 100.0).roundToInt() / 100.0
    return if (rounded % 1.0 == 0.0) {
        rounded.toInt().toString()
    } else {
        "%.2f".format(Locale.US, rounded).trimEnd('0').trimEnd('.')
    }
}
