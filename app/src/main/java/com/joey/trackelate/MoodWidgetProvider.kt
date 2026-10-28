package com.joey.trackelate

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Home-screen widget: this month as a mood grid, your streak, and seven chips to grade today
 * without opening the app. Plain RemoteViews + a Canvas bitmap, so it adds almost nothing to the APK.
 */
internal class MoodWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context, manager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        render(context, manager, intArrayOf(appWidgetId))
    }

    companion object {
        private val gradeIds = intArrayOf(
            R.id.widget_grade_0,
            R.id.widget_grade_1,
            R.id.widget_grade_2,
            R.id.widget_grade_3,
            R.id.widget_grade_4,
            R.id.widget_grade_5,
            R.id.widget_grade_6,
        )

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, MoodWidgetProvider::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val days = runCatching { CsvJournalRepository(context).loadSnapshot().days }.getOrDefault(emptyList())
            val today = LocalDate.now()
            val month = YearMonth.from(today)
            val grades = days.filter { it.grade != null }.associate { it.date to it.grade!! }
            val todayGrade = grades[today]
            val streak = moodStreak(days, today)
            val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val grid = drawMonth(month, grades, today, dark)
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_mood).apply {
                    setTextViewText(R.id.widget_title, month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()))
                    setTextViewText(
                        R.id.widget_streak,
                        when {
                            todayGrade != null -> "today ${gradeLabel(todayGrade)} · ${streak}d streak"
                            streak > 0 -> "${streak}d streak · grade today"
                            else -> "grade today"
                        },
                    )
                    setImageViewBitmap(R.id.widget_grid, grid)
                    setOnClickPendingIntent(R.id.widget_grid, NotificationScheduler.openPendingIntent(context, today))
                    setOnClickPendingIntent(R.id.widget_header, NotificationScheduler.openPendingIntent(context, today))
                    gradeIds.forEachIndexed { grade, viewId ->
                        val selected = todayGrade == grade
                        setInt(viewId, "setBackgroundColor", NotificationScheduler.gradeColors[grade])
                        setTextColor(viewId, if (grade in 1..4) Color.BLACK else Color.WHITE)
                        setTextViewText(viewId, if (selected) "✓" else grade.toString())
                        setOnClickPendingIntent(viewId, NotificationScheduler.gradePendingIntent(context, today, grade, SOURCE_WIDGET))
                    }
                }
                manager.updateAppWidget(id, views)
            }
        }

        private fun drawMonth(month: YearMonth, grades: Map<LocalDate, Int>, today: LocalDate, dark: Boolean): Bitmap {
            val cell = 64f
            val gap = 8f
            val first = month.atDay(1)
            val offset = first.dayOfWeek.value - 1
            val rows = (offset + month.lengthOfMonth() + 6) / 7
            val width = (7 * cell + 6 * gap).toInt()
            val height = (rows * cell + (rows - 1) * gap).toInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val empty = if (dark) 0xFF1E1E1E.toInt() else 0xFFF2E8DA.toInt()
            val ink = if (dark) 0xFFEDE6DB.toInt() else 0xFF191611.toInt()
            for (day in 1..month.lengthOfMonth()) {
                val date = month.atDay(day)
                val index = offset + day - 1
                val left = (index % 7) * (cell + gap)
                val top = (index / 7) * (cell + gap)
                val rect = RectF(left, top, left + cell, top + cell)
                val grade = grades[date]
                val future = date.isAfter(today)
                fill.color = when {
                    grade != null -> NotificationScheduler.gradeColors[grade]
                    else -> empty
                }
                fill.alpha = if (future) 90 else 255
                canvas.drawRoundRect(rect, 14f, 14f, fill)
                if (date == today) {
                    stroke.color = ink
                    canvas.drawRoundRect(RectF(rect.left + 2, rect.top + 2, rect.right - 2, rect.bottom - 2), 12f, 12f, stroke)
                }
                text.color = when {
                    grade == null -> ink
                    grade in 1..4 -> Color.BLACK
                    else -> Color.WHITE
                }
                text.alpha = if (future) 110 else 255
                canvas.drawText(day.toString(), rect.centerX(), rect.centerY() + 8f, text)
            }
            return bitmap
        }
    }
}

