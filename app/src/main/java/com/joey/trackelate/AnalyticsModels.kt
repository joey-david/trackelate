package com.joey.trackelate

import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.sqrt

internal data class EntryInsight(
    val entry: QuantityEntry,
    val sampleCount: Int,
    val correlation: Double?,
    val mean: Double?,
    val standardDeviation: Double?,
    val lowHalfAverageGrade: Double?,
    val highHalfAverageGrade: Double?,
    val latestDate: LocalDate?,
    val latestValue: Double?,
)

internal data class RelatedTrait(
    val name: String,
    val correlation: Double,
)

private data class GradedSample(
    val date: LocalDate,
    val value: Double,
    val grade: Double,
)

internal fun buildEntryInsights(entries: List<QuantityEntry>, days: List<JournalDay>): List<EntryInsight> {
    val sortedEntries = entries.sortedBy { it.name.lowercase() }
    return sortedEntries.map { entry ->
        val samples = days.mapNotNull { day ->
            val grade = day.grade ?: return@mapNotNull null
            parseQuantityValue(entry.unit, day.values[entry.columnKey])?.let { value ->
                GradedSample(day.date, value, grade.toDouble())
            }
        }.sortedBy { it.date }
        val values = samples.map { it.value }
        val grades = samples.map { it.grade }
        val mean = values.meanOrNull()
        val stdDev = values.standardDeviationOrNull()
        val normalized = if (mean != null && stdDev != null && stdDev != 0.0) {
            values.map { (it - mean) / stdDev }
        } else {
            emptyList()
        }
        val correlation = if (normalized.isNotEmpty()) {
            pearson(normalized, grades)
        } else {
            null
        }
        val half = samples.size / 2
        val sortedByValue = samples.withIndex().sortedBy { it.value.value }
        val lowHalfAverage = if (samples.size >= 5) {
            sortedByValue.take(half).map { it.value.grade }.averageOrNull()
        } else {
            null
        }
        val highHalfAverage = if (samples.size >= 5) {
            sortedByValue.takeLast(half).map { it.value.grade }.averageOrNull()
        } else {
            null
        }

        EntryInsight(
            entry = entry,
            sampleCount = samples.size,
            correlation = correlation,
            mean = mean,
            standardDeviation = stdDev,
            lowHalfAverageGrade = lowHalfAverage,
            highHalfAverageGrade = highHalfAverage,
            latestDate = samples.lastOrNull()?.date,
            latestValue = samples.lastOrNull()?.value,
        )
    }
}

internal fun buildRelatedTraitCorrelations(
    target: QuantityEntry,
    entries: List<QuantityEntry>,
    days: List<JournalDay>,
): List<RelatedTrait> {
    val targetSamples = buildEntrySamples(target, days)
    return entries.asSequence()
        .filter { it.columnKey != target.columnKey }
        .mapNotNull { entry ->
            val otherSamples = buildEntrySamples(entry, days)
            val sharedDates = targetSamples.keys.intersect(otherSamples.keys).sorted()
            if (sharedDates.size < 3) return@mapNotNull null
            val x = sharedDates.mapNotNull { targetSamples[it] }
            val y = sharedDates.mapNotNull { otherSamples[it] }
            val correlation = pearson(x, y) ?: return@mapNotNull null
            RelatedTrait(entry.name, correlation)
        }
        .sortedByDescending { kotlin.math.abs(it.correlation) }
        .toList()
}

internal fun parseQuantityValue(unit: String, raw: String?): Double? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank() || value == "NaN") return null
    return when (unit.lowercase()) {
        "time" -> parseTimeToSeconds(value)
        "dimensionless" -> value.toDoubleOrNull()
        else -> value.toDoubleOrNull()
    }
}

private fun buildEntrySamples(entry: QuantityEntry, days: List<JournalDay>): Map<LocalDate, Double> =
    days.mapNotNull { day ->
        parseQuantityValue(entry.unit, day.values[entry.columnKey])?.let { value ->
            day.date to value
        }
    }.toMap()

private fun parseTimeToSeconds(value: String): Double? {
    val parts = value.split(":")
    return when (parts.size) {
        2 -> {
            val minutes = parts[0].toLongOrNull() ?: return null
            val seconds = parts[1].toLongOrNull() ?: return null
            (minutes * 60 + seconds).toDouble()
        }
        3 -> {
            val hours = parts[0].toLongOrNull() ?: return null
            val minutes = parts[1].toLongOrNull() ?: return null
            val seconds = parts[2].toLongOrNull() ?: return null
            (hours * 3600 + minutes * 60 + seconds).toDouble()
        }
        else -> value.toDoubleOrNull()
    }
}

private fun List<Double>.meanOrNull(): Double? = if (isEmpty()) null else sum() / size

private fun List<Double>.standardDeviationOrNull(): Double? {
    val mean = meanOrNull() ?: return null
    if (size < 2) return 0.0
    val variance = sumOf { (it - mean).pow(2) } / (size - 1)
    return sqrt(variance)
}

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

private fun pearson(x: List<Double>, y: List<Double>): Double? {
    if (x.size != y.size || x.size < 2) return null
    val meanX = x.meanOrNull() ?: return null
    val meanY = y.meanOrNull() ?: return null
    val numerator = x.zip(y).sumOf { (xValue, yValue) -> (xValue - meanX) * (yValue - meanY) }
    val sumSquaresX = x.sumOf { (it - meanX).pow(2) }
    val sumSquaresY = y.sumOf { (it - meanY).pow(2) }
    val denominator = sqrt(sumSquaresX * sumSquaresY)
    return if (denominator == 0.0) null else numerator / denominator
}

internal data class MoodSummary(
    val streak: Int,
    val longestStreak: Int,
    val gradedDays: Int,
    val last30Average: Double?,
    val previous30Average: Double?,
    val weekdayAverages: Map<java.time.DayOfWeek, Double>,
    val bestWeekday: java.time.DayOfWeek?,
    val trend: List<Pair<LocalDate, Double>>,
)

/** Consecutive graded days ending today (or yesterday, so tonight's entry isn't required yet). */
internal fun moodStreak(days: List<JournalDay>, today: LocalDate = LocalDate.now()): Int {
    val graded = days.filter { it.grade != null }.mapTo(HashSet()) { it.date }
    var cursor = if (today in graded) today else today.minusDays(1)
    var streak = 0
    while (cursor in graded) {
        streak += 1
        cursor = cursor.minusDays(1)
    }
    return streak
}

internal fun buildMoodSummary(days: List<JournalDay>, today: LocalDate = LocalDate.now()): MoodSummary {
    val graded = days.filter { it.grade != null && !it.date.isAfter(today) }.sortedBy { it.date }
    var longest = 0
    var run = 0
    var previous: LocalDate? = null
    graded.forEach { day ->
        run = if (previous != null && previous!!.plusDays(1) == day.date) run + 1 else 1
        longest = maxOf(longest, run)
        previous = day.date
    }
    fun averageBetween(fromInclusive: LocalDate, toExclusive: LocalDate): Double? =
        graded.filter { !it.date.isBefore(fromInclusive) && it.date.isBefore(toExclusive) }
            .mapNotNull { it.grade?.toDouble() }
            .averageOrNull()
    val weekday = graded.groupBy { it.date.dayOfWeek }
        .filterValues { it.size >= 2 }
        .mapValues { (_, list) -> list.mapNotNull { it.grade?.toDouble() }.average() }
    val gradeByDate = graded.associate { it.date to it.grade!!.toDouble() }
    val trendStart = today.minusDays(89)
    val trend = generateSequence(trendStart) { it.plusDays(1) }
        .takeWhile { !it.isAfter(today) }
        .mapNotNull { date ->
            val window = (0L..6L).mapNotNull { gradeByDate[date.minusDays(it)] }
            if (window.isEmpty() || gradeByDate[date] == null) null else date to window.average()
        }
        .toList()
    return MoodSummary(
        streak = moodStreak(days, today),
        longestStreak = longest,
        gradedDays = graded.size,
        last30Average = averageBetween(today.minusDays(29), today.plusDays(1)),
        previous30Average = averageBetween(today.minusDays(59), today.minusDays(29)),
        weekdayAverages = weekday,
        bestWeekday = weekday.maxByOrNull { it.value }?.key,
        trend = trend,
    )
}
