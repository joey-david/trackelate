package com.joey.trackelate

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private val MoodPalette = NotificationScheduler.gradeColors.map { Color(it) }

/** Streak, 90-day trend, a year in pixels and weekday rhythm, all drawn with Canvas. */
@Composable
internal fun MoodOverview(days: List<JournalDay>, onOpenDate: (LocalDate) -> Unit) {
    val summary = remember(days) { buildMoodSummary(days) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("streak", "${summary.streak}d", "best ${summary.longestStreak}d", Modifier.weight(1f))
            val average = summary.last30Average
            val delta = if (average != null && summary.previous30Average != null) average - summary.previous30Average else null
            StatTile(
                "30-day mood",
                average?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—",
                delta?.let { (if (it >= 0) "▲ " else "▼ ") + String.format(Locale.getDefault(), "%.1f", kotlin.math.abs(it)) + " vs prior" }
                    ?: "${summary.gradedDays} days graded",
                Modifier.weight(1f),
                accent = delta?.let { if (it >= 0) Color(0xFF2E8B57) else Color(0xFF9E4B46) },
            )
            StatTile(
                "best day",
                summary.bestWeekday?.getDisplayName(TextStyle.SHORT, Locale.getDefault()) ?: "—",
                summary.bestWeekday?.let { summary.weekdayAverages[it] }?.let { String.format(Locale.getDefault(), "avg %.1f", it) } ?: "needs data",
                Modifier.weight(1f),
            )
        }
        OverviewCard("Mood trend · 90 days, 7-day average") {
            TrendChart(summary.trend)
        }
        OverviewCard("Year in pixels · tap a day to open it") {
            YearInPixels(days, onOpenDate)
        }
        OverviewCard("Weekday rhythm") {
            WeekdayBars(summary.weekdayAverages, summary.bestWeekday)
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, detail: String, modifier: Modifier, accent: Color? = null) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = accent ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun OverviewCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun TrendChart(trend: List<Pair<LocalDate, Double>>) {
    if (trend.size < 2) {
        Text("Grade a few days to see your trend.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val start = LocalDate.now().minusDays(89)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        fun x(date: LocalDate) = ChronoUnit.DAYS.between(start, date).toFloat() / 89f * size.width
        fun y(value: Double) = size.height - (value / 6.0).toFloat() * size.height
        listOf(0.0, 3.0, 6.0).forEach { level ->
            drawLine(grid, Offset(0f, y(level)), Offset(size.width, y(level)), strokeWidth = 1.dp.toPx())
        }
        val path = Path()
        trend.forEachIndexed { index, (date, value) ->
            if (index == 0) path.moveTo(x(date), y(value)) else path.lineTo(x(date), y(value))
        }
        // Colour the line by height: red at 0 up to deep blue at 6, like the calendar.
        val brush = Brush.verticalGradient(MoodPalette.reversed(), startY = 0f, endY = size.height)
        drawPath(path, brush, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        trend.lastOrNull()?.let { (date, value) ->
            drawCircle(brush, radius = 5.dp.toPx(), center = Offset(x(date), y(value)))
        }
    }
}

@Composable
private fun YearInPixels(days: List<JournalDay>, onOpenDate: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val grades = remember(days) { days.filter { it.grade != null }.associate { it.date to it.grade!! } }
    val weeks = 53
    // Columns are weeks (Monday first), the last column holds this week.
    val firstMonday = today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks((weeks - 1).toLong())
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val todayRing = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(weeks / 7f)
            .pointerInput(firstMonday) {
                detectTapGestures { offset ->
                    val cell = size.width / weeks.toFloat()
                    val week = (offset.x / cell).toInt().coerceIn(0, weeks - 1)
                    val day = (offset.y / cell).toInt().coerceIn(0, 6)
                    val date = firstMonday.plusDays(week * 7L + day)
                    if (!date.isAfter(today)) onOpenDate(date)
                }
            },
    ) {
        val cell = size.width / weeks
        val gap = cell * 0.14f
        for (week in 0 until weeks) {
            for (day in 0 until 7) {
                val date = firstMonday.plusDays(week * 7L + day)
                if (date.isAfter(today)) continue
                val color = grades[date]?.let { MoodPalette[it] } ?: empty
                val topLeft = Offset(week * cell + gap / 2, day * cell + gap / 2)
                val cellSize = Size(cell - gap, cell - gap)
                drawRoundRect(color, topLeft, cellSize, CornerRadius(cell * 0.2f))
                if (date == today) {
                    drawRoundRect(todayRing, topLeft, cellSize, CornerRadius(cell * 0.2f), style = Stroke(width = gap))
                }
            }
        }
    }
}

@Composable
private fun WeekdayBars(averages: Map<DayOfWeek, Double>, best: DayOfWeek?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DayOfWeek.entries.forEach { day ->
            val value = averages[day]
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Bottom,
            ) {
                val barColor = value?.let { MoodPalette[it.toInt().coerceIn(0, 6)] } ?: MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                val emphasis = MaterialTheme.colorScheme.onSurface
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    val fraction = ((value ?: 0.3) / 6.0).toFloat().coerceIn(0.05f, 1f)
                    val barHeight = size.height * fraction
                    drawRoundRect(
                        barColor,
                        topLeft = Offset(0f, size.height - barHeight),
                        size = Size(size.width, barHeight),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                    if (day == best) {
                        drawRoundRect(
                            emphasis,
                            topLeft = Offset(0f, size.height - barHeight),
                            size = Size(size.width, barHeight),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
                Text(
                    day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally),
                )
            }
        }
    }
}
