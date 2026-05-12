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
    val stepsOutsideRunning: Int? = null,
    val sleepSeconds: Long? = null,
    val wakeUpSeconds: Long? = null,
    val cardioSeconds: Long? = null,
    val strengthSeconds: Long? = null,
    val extraEvents: Int? = null,
    val runningSteps: Int? = null,
    val clears: Set<ImportField> = emptySet(),
) {
    fun mergedWith(other: ImportedActivityDay): ImportedActivityDay = ImportedActivityDay(
        stepsOutsideRunning = other.stepsOutsideRunning ?: stepsOutsideRunning,
        sleepSeconds = other.sleepSeconds ?: sleepSeconds,
        wakeUpSeconds = other.wakeUpSeconds ?: wakeUpSeconds,
        cardioSeconds = sumLong(cardioSeconds, other.cardioSeconds),
        strengthSeconds = sumLong(strengthSeconds, other.strengthSeconds),
        extraEvents = sumInt(extraEvents, other.extraEvents),
        runningSteps = sumInt(runningSteps, other.runningSteps),
        clears = clears + other.clears,
    )

    fun valuesByField(): Map<ImportField, String> = buildMap {
        stepsOutsideRunning?.takeIf { it > 0 }?.let { put(ImportField.STEPS_OUTSIDE_RUNNING, it.toString()) }
        sleepSeconds?.takeIf { it > 0 }?.let { put(ImportField.SLEEP, secondsToDuration(it)) }
        wakeUpSeconds?.takeIf { it >= 0 }?.let { put(ImportField.WAKE_UP_TIME, secondsToDuration(it)) }
        cardioSeconds?.takeIf { it > 0 }?.let { put(ImportField.CARDIO_TIME, secondsToDuration(it)) }
        strengthSeconds?.takeIf { it > 0 }?.let { put(ImportField.STRENGTH_TIME, secondsToDuration(it)) }
        extraEvents?.takeIf { it > 0 }?.let { put(ImportField.EXTRA_EVENTS, it.toString()) }
    }

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

}

internal enum class ImportField(
    val displayName: String,
    val unit: String,
    val aliases: Set<String> = emptySet(),
) {
    STEPS_OUTSIDE_RUNNING("steps outside of running", "dimensionless", setOf("steps", "number of steps")),
    CARDIO_TIME("cardio time", "time", setOf("time exercising", "time spent running", "time biking")),
    STRENGTH_TIME("strength training time", "time", setOf("time spent working out in gym")),
    SLEEP("time slept", "time", setOf("sleep", "time slept previous night")),
    WAKE_UP_TIME("wake up time", "time"),
    SOCIAL_TIME("time spent socializing", "time", setOf("time spent socializing irl")),
    EXTRA_EVENTS("extra events", "dimensionless", setOf("number of extra events")),
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
            var cardioSeconds = 0L
            var hasGymWork = false

            workoutRows.forEach { row ->
                val exercise = row["exercise_title"].orEmpty()
                if (exercise.equals("Running", ignoreCase = true)) {
                    cardioSeconds += row["duration_seconds"].orEmpty().toDoubleOrNull()?.roundToLong() ?: 0L
                } else {
                    hasGymWork = true
                }
            }

            if (cardioSeconds == 0L && workoutRows.any { it["exercise_title"].orEmpty().equals("Running", ignoreCase = true) }) {
                cardioSeconds = (end.atZone(zone).toEpochSecond() - start.atZone(zone).toEpochSecond()).coerceAtLeast(0)
            }
            val strengthSeconds = if (hasGymWork) {
                (end.atZone(zone).toEpochSecond() - start.atZone(zone).toEpochSecond()).coerceAtLeast(0)
            } else {
                0L
            }

            val imported = ImportedActivityDay(
                cardioSeconds = cardioSeconds.takeIf { it > 0 },
                strengthSeconds = strengthSeconds.takeIf { it > 0 },
                clears = if (cardioSeconds > 0 && !hasGymWork) {
                    setOf(ImportField.STRENGTH_TIME)
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
            val existing = days[date]
            val outsideRunning = (steps - (existing?.runningSteps ?: 0)).coerceAtLeast(0)
            days[date] = existing?.copy(stepsOutsideRunning = outsideRunning) ?: ImportedActivityDay(stepsOutsideRunning = outsideRunning)
        }
        val sleepChoice = sleepCandidates.minByOrNull { it.priority }
        sleepChoice?.days?.forEach { (date, sleep) ->
            days[date] = days[date]?.copy(
                sleepSeconds = sleep.sleepSeconds,
                wakeUpSeconds = sleep.wakeUpSeconds,
            ) ?: ImportedActivityDay(
                sleepSeconds = sleep.sleepSeconds,
                wakeUpSeconds = sleep.wakeUpSeconds,
            )
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
                val daily = mutableMapOf<LocalDate, SleepDay>()
                for (index in 0 until points.length()) {
                    val point = points.optJSONObject(index) ?: continue
                    val state = point.intFitValue()
                    if (state == 1) continue
                    val start = instantFromNanos(point.optLong("startTimeNanos")).atZone(zone)
                    val end = instantFromNanos(point.optLong("endTimeNanos")).atZone(zone)
                    val date = if (end.hour < 12) end.toLocalDate() else start.toLocalDate()
                    val seconds = (end.toEpochSecond() - start.toEpochSecond()).coerceAtLeast(0)
                    val previous = daily[date]
                    val wakeUpSeconds = if (end.hour < 12) end.toLocalTime().toSecondOfDay().toLong() else previous?.wakeUpSeconds
                    daily[date] = SleepDay(
                        sleepSeconds = (previous?.sleepSeconds ?: 0L) + seconds,
                        wakeUpSeconds = listOfNotNull(previous?.wakeUpSeconds, wakeUpSeconds).maxOrNull(),
                    )
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
        val hasRunningSegment = activity == "running" || json.hasSegmentActivity("running")
        val runningSteps = if (hasRunningSegment) json.aggregateIntValue("com.google.step_count.delta") else null
        val day = when (activity) {
            "running" -> ImportedActivityDay(cardioSeconds = seconds, runningSteps = runningSteps)
            "biking" -> ImportedActivityDay(cardioSeconds = seconds)
            "strength_training" -> ImportedActivityDay(strengthSeconds = seconds)
            "walking", "walking.paced", "sleep" -> return null
            else -> ImportedActivityDay(extraEvents = 1)
        }
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

    private fun JSONObject.aggregateIntValue(metricName: String): Int? {
        val aggregate = optJSONArray("aggregate") ?: return null
        for (index in 0 until aggregate.length()) {
            val metric = aggregate.optJSONObject(index) ?: continue
            if (metric.optString("metricName") == metricName) {
                return when {
                    metric.has("intValue") -> metric.optInt("intValue")
                    metric.has("floatValue") -> metric.optDouble("floatValue").roundToInt()
                    else -> null
                }
            }
        }
        return null
    }

    private fun JSONObject.hasSegmentActivity(activity: String): Boolean {
        val segments = optJSONArray("segment") ?: return false
        for (index in 0 until segments.length()) {
            if (segments.optJSONObject(index)?.optString("fitnessActivity") == activity) return true
        }
        return false
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
    private data class SleepCandidate(val priority: Int, val source: String, val days: Map<LocalDate, SleepDay>)
    private data class SleepDay(val sleepSeconds: Long, val wakeUpSeconds: Long?)

    private sealed interface ParsedAllData {
        data class Steps(val source: String, val days: Map<LocalDate, Int>) : ParsedAllData
        data class Sleep(val source: String, val days: Map<LocalDate, SleepDay>) : ParsedAllData
    }
}

private fun secondsToDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return "%02d:%02d:%02d".format(Locale.US, hours, minutes, secs)
}
