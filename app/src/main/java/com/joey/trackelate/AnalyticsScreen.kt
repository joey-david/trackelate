package com.joey.trackelate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AnalyticsPage(state: JournalUiState) {
    val insights = remember(state.entries, state.days) {
        buildEntryInsights(state.entries, state.days)
    }
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "Analytics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (insights.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Add a tracked quantity to see insights here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@LazyVerticalGrid
        }

        items(insights, key = { it.entry.columnKey }) { insight ->
            val expanded = expandedKey == insight.entry.columnKey
            val relatedTraits = buildRelatedTraitCorrelations(insight.entry, state.entries, state.days)
            AnalyticsTile(
                insight = insight,
                expanded = expanded,
                relatedTraits = relatedTraits,
                onClick = { expandedKey = if (expanded) null else insight.entry.columnKey },
            )
        }
    }
}

@Composable
private fun AnalyticsTile(
    insight: EntryInsight,
    expanded: Boolean,
    relatedTraits: List<RelatedTrait>,
    onClick: () -> Unit,
) {
    val locked = insight.sampleCount < 5
    val correlation = insight.correlation
    val summary = when {
        locked -> "locked"
        correlation == null -> "flat"
        correlation >= 0 -> "+${formatNumber(correlation)}"
        else -> formatNumber(correlation)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (expanded) Modifier.heightIn(min = 180.dp) else Modifier.aspectRatio(1f))
            .animateContentSize(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = insight.entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        locked -> MaterialTheme.colorScheme.onSurfaceVariant
                        correlation == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        correlation >= 0 -> Color(0xFF2E8B57)
                        else -> Color(0xFF9E4B46)
                    },
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatLine("Tracked", insight.sampleCount.toString())
                    StatLine("Mean", insight.mean?.let(::formatNumber) ?: "—")
                    StatLine("Std dev", insight.standardDeviation?.let(::formatNumber) ?: "—")
                    StatLine(
                        "Low vs high",
                        if (insight.lowHalfAverageGrade != null && insight.highHalfAverageGrade != null) {
                            formatSigned(insight.highHalfAverageGrade - insight.lowHalfAverageGrade)
                        } else {
                            "—"
                        },
                    )
                    StatLine(
                        "Latest",
                        if (insight.latestDate != null && insight.latestValue != null) {
                            "${insight.latestDate.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))} · ${formatNumber(insight.latestValue)}"
                        } else {
                            "—"
                        },
                    )

                    if (!locked) {
                        Text(
                            text = "Related traits",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (relatedTraits.isEmpty()) {
                            Text(
                                text = "No strong pairwise matches yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            relatedTraits.take(3).forEach { trait ->
                                StatLine(trait.name, formatSigned(trait.correlation))
                            }
                        }
                    } else {
                        Text(
                            text = "You need at least 5 entries of this instance to establish a meaningful correlation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (value.startsWith("-")) Color(0xFF9E4B46) else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatSigned(value: Double): String = when {
    value.isNaN() -> "—"
    value >= 0 -> "+${formatNumber(value)}"
    else -> formatNumber(value)
}

private fun formatNumber(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)
