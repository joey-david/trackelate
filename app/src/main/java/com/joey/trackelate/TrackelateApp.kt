package com.joey.trackelate

import android.Manifest
import android.graphics.Paint
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joey.trackelate.ui.theme.TrackelateTheme
import kotlinx.coroutines.launch
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.window.Dialog
import android.view.Gravity
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.TextView
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val GradeColors = listOf(
    Color(0xFFE93127),
    Color(0xFFF47A7A),
    Color(0xFFF4A61D),
    Color(0xFFF0E34D),
    Color(0xFF38D642),
    Color(0xFF2E6EDB),
    Color(0xFF0F28E8),
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun TrackelateApp(
    viewModel: JournalViewModel,
    launchTarget: NotificationLaunchTarget?,
    onLaunchTargetConsumed: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showReminderTimeDialog by rememberSaveable { mutableStateOf(false) }
    var pendingLaunchTarget by remember { mutableStateOf<NotificationLaunchTarget?>(null) }
    val activityImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importActivityData)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(launchTarget) {
        launchTarget?.let {
            viewModel.selectDate(it.date)
            pagerState.scrollToPage(0)
            pendingLaunchTarget = it
        }
    }

    LaunchedEffect(state.loading, state.notificationTime) {
        if (!state.loading && isAllowedReminderTime(state.notificationTime)) {
            NotificationScheduler.scheduleDaily(context, state.notificationTime)
        }
    }

    TrackelateTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {},
                        navigationIcon = {
                            ModePill(
                                label = if (pagerState.currentPage == 0) "see analytics" else "logging",
                                onClick = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(if (pagerState.currentPage == 0) 1 else 0)
                                    }
                                },
                            )
                        },
                        actions = {
                            ReminderPill(
                                time = state.notificationTime,
                                onClick = { showReminderTimeDialog = true },
                            )
                        },
                    )
                },
            ) { padding ->
                val visibleMonth = remember { mutableStateOf(YearMonth.from(state.selectedDate)) }
                LaunchedEffect(state.selectedDate) {
                    visibleMonth.value = YearMonth.from(state.selectedDate)
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) { page ->
                    when (page) {
                        0 -> TrackelateJournalPage(
                            state = state,
                            month = visibleMonth.value,
                            onMonthBack = { visibleMonth.value = visibleMonth.value.minusMonths(1) },
                            onMonthForward = { visibleMonth.value = visibleMonth.value.plusMonths(1) },
                            onSelectDate = {
                                visibleMonth.value = YearMonth.from(it)
                                viewModel.selectDate(it)
                            },
                            onGradeSelected = viewModel::updateGrade,
                            onDescriptionChanged = viewModel::updateDescription,
                            onQuantityChanged = viewModel::updateQuantity,
                            onDeleteQuantity = viewModel::deleteQuantity,
                            onAddQuantity = viewModel::addQuantity,
                            onImportActivityData = {
                                activityImportLauncher.launch(
                                    arrayOf(
                                        "text/*",
                                        "application/zip",
                                        "application/octet-stream",
                                    ),
                                )
                            },
                            launchTarget = pendingLaunchTarget,
                            onLaunchTargetConsumed = {
                                pendingLaunchTarget = null
                                onLaunchTargetConsumed()
                            },
                        )
                        else -> AnalyticsPage(state)
                    }
                }
            }
        }
    }

    if (showReminderTimeDialog) {
        TimeWheelDialog(
            title = "Choose reminder time",
            initialTime = state.notificationTime,
            onDismiss = { showReminderTimeDialog = false },
            onConfirm = { time ->
                showReminderTimeDialog = false
                viewModel.setNotificationTime(time)
                if (isAllowedReminderTime(time)) {
                    val exactAlarmScheduled = NotificationScheduler.scheduleDaily(context, time)
                    if (!exactAlarmScheduled) {
                        NotificationScheduler.openExactAlarmSettings(context)
                    }
                }
            },
        )
    }
}

@Composable
private fun ModePill(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        shadowElevation = 1.dp,
        modifier = Modifier
            .padding(start = 12.dp)
            .shadow(1.dp, RectangleShape, clip = false),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ShowChart, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ReminderPill(time: LocalTime, onClick: () -> Unit) {
    val label = time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
    Surface(
        onClick = onClick,
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        shadowElevation = 2.dp,
        modifier = Modifier
            .padding(end = 12.dp)
            .shadow(2.dp, RectangleShape, clip = false),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Notifications, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "tap to edit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackelateJournalPage(
    state: JournalUiState,
    month: YearMonth,
    onMonthBack: () -> Unit,
    onMonthForward: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onGradeSelected: (LocalDate, Int) -> Unit,
    onDescriptionChanged: (LocalDate, String) -> Unit,
    onQuantityChanged: (LocalDate, String, String) -> Unit,
    onDeleteQuantity: (String) -> Unit,
    onAddQuantity: (String, String, String?) -> Unit,
    onImportActivityData: () -> Unit,
    launchTarget: NotificationLaunchTarget?,
    onLaunchTargetConsumed: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val selectedDay = state.days.firstOrNull { it.date == state.selectedDate } ?: JournalDay(state.selectedDate)
    var showQuantityDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(launchTarget?.date) {
        if (launchTarget != null) {
            bringIntoViewRequester.bringIntoView()
            onLaunchTargetConsumed()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TrackelateCalendar(
            month = month,
            selectedDate = state.selectedDate,
            days = state.days,
            onBack = onMonthBack,
            onForward = onMonthForward,
            onSelectDate = onSelectDate,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

        TrackelateDayDetails(
            state = state,
            selectedDay = selectedDay,
            onGradeSelected = onGradeSelected,
            onDescriptionChanged = onDescriptionChanged,
            onQuantityChanged = onQuantityChanged,
            onDeleteQuantity = onDeleteQuantity,
            modifier = Modifier.bringIntoViewRequester(bringIntoViewRequester),
        )

        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onImportActivityData,
                shape = RectangleShape,
                modifier = Modifier.weight(1f),
            ) {
                Text("Import activity")
            }
            Button(
                onClick = { showQuantityDialog = true },
                shape = RectangleShape,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add quantity")
            }
        }
    }

    if (showQuantityDialog) {
        QuantityDialog(
            entries = state.entries,
            onDismiss = { showQuantityDialog = false },
            onConfirm = { name, unit, backfill ->
                onAddQuantity(name, unit, backfill)
                showQuantityDialog = false
            },
        )
    }
}

@Composable
private fun TrackelateCalendar(
    month: YearMonth,
    selectedDate: LocalDate,
    days: List<JournalDay>,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
) {
    val daysByDate = remember(days) { days.associateBy { it.date } }
    val formatter = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()) }
    val cells = remember(month) { buildMonthCells(month) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = month.atDay(1).format(formatter),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Previous month")
                }
                IconButton(onClick = onForward) {
                    Icon(Icons.Rounded.ArrowForward, contentDescription = "Next month")
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    val day = daysByDate[date]
                    val isCurrentMonth = YearMonth.from(date) == month
                    val isSelected = date == selectedDate
                    val isFuture = date.isAfter(LocalDate.now())
                    val grade = day?.grade ?: 3
                    val background = when {
                        isFuture -> MaterialTheme.colorScheme.background
                        day == null -> MaterialTheme.colorScheme.background
                        else -> gradeColor(grade)
                    }
                    val contentColor = when {
                        isFuture -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f)
                        grade >= 4 -> Color.Black
                        else -> Color.White
                    }
                    val labelColor = if (isFuture) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f) else if (isCurrentMonth) contentColor else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(68.dp)
                            .background(background)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            )
                            .clickable(enabled = !isFuture) { onSelectDate(date) }
                            .padding(horizontal = 5.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = date.dayOfMonth.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = labelColor,
                        )
                        Text(
                            text = if (day != null) gradeLabel(day.grade) else " ",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (day != null) contentColor else labelColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackelateDayDetails(
    state: JournalUiState,
    selectedDay: JournalDay,
    onGradeSelected: (LocalDate, Int) -> Unit,
    onDescriptionChanged: (LocalDate, String) -> Unit,
    onQuantityChanged: (LocalDate, String, String) -> Unit,
    onDeleteQuantity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selectedDay.date.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault())),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            GradeBadge(selectedDay.grade)
        }

        GradePickerFlat(
            selected = selectedDay.grade,
            onSelect = { onGradeSelected(state.selectedDate, it) },
        )

        OutlinedTextField(
            value = selectedDay.description,
            onValueChange = { onDescriptionChanged(state.selectedDate, it.take(140)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("How was your day?") },
            placeholder = { Text("Optional, up to 140 characters") },
            shape = RectangleShape,
            singleLine = false,
            maxLines = 3,
        )
        Text(
            text = "${selectedDay.description.length.coerceAtMost(140)}/140",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        QuantitySectionFlat(
            state = state,
            selectedDay = selectedDay,
            onQuantityChanged = onQuantityChanged,
            onDeleteQuantity = onDeleteQuantity,
        )
    }
}

@Composable
private fun GradePickerFlat(selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Mood grade",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            (0..6).forEach { grade ->
                FilterChip(
                    selected = grade == selected,
                    onClick = { onSelect(grade) },
                    label = {
                        Text(
                            text = grade.toString(),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    },
                    shape = RectangleShape,
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        containerColor = gradeColor(grade).copy(alpha = 0.18f),
                        selectedContainerColor = gradeColor(grade),
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        selectedLabelColor = if (grade >= 4) Color.Black else Color.White,
                    ),
                )
            }
        }
        Text(
            text = gradeLabel(selected),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GradeBadge(grade: Int) {
    Surface(
        shape = RectangleShape,
        color = gradeColor(grade),
        contentColor = if (grade >= 4) Color.Black else Color.White,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
    ) {
        Text(
            text = "$grade · ${gradeLabel(grade)}",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun QuantitySectionFlat(
    state: JournalUiState,
    selectedDay: JournalDay,
    onQuantityChanged: (LocalDate, String, String) -> Unit,
    onDeleteQuantity: (String) -> Unit,
) {
    val activeEntries = state.entries.filter { it.active }
    var editingTimeEntry by rememberSaveable { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = "Optional study fields",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (activeEntries.isEmpty()) {
            Text(
                text = "Add a quantity below, then fill it in for any day.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            activeEntries.forEachIndexed { index, entry ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                }
                val value = selectedDay.values[entry.columnKey].orEmpty()
                Column(
                    modifier = Modifier.padding(vertical = 3.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                entry.name,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = entry.unit,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { onDeleteQuantity(entry.columnKey) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = "Delete quantity",
                            )
                        }
                    }

                    when (editorKind(entry.unit)) {
                        QuantityEditorKind.TIME -> {
                            Surface(
                                onClick = { editingTimeEntry = entry.columnKey },
                                shape = RectangleShape,
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = value.takeIf { it.isNotBlank() } ?: "tap to pick time",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        QuantityEditorKind.NUMBER -> {
                            OutlinedTextField(
                                value = value,
                                onValueChange = { onQuantityChanged(selectedDay.date, entry.columnKey, it.take(16)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("0") },
                                shape = RectangleShape,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                        }
                        QuantityEditorKind.TEXT -> {
                            OutlinedTextField(
                                value = value,
                                onValueChange = { onQuantityChanged(selectedDay.date, entry.columnKey, it.take(48)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Enter value") },
                                shape = RectangleShape,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                singleLine = true,
                            )
                        }
                    }
                }
                if (editingTimeEntry == entry.columnKey) {
                    TimeWheelDialog(
                        title = "Choose time",
                        initialTime = value.toTimeOrNull() ?: LocalTime.of(21, 30),
                        onDismiss = { editingTimeEntry = null },
                        onConfirm = { time ->
                            editingTimeEntry = null
                            onQuantityChanged(
                                selectedDay.date,
                                entry.columnKey,
                                time.format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())),
                            )
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuantityDialog(
    entries: List<QuantityEntry>,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String?) -> Unit,
) {
    val knownNames = remember(entries) { entries.map { it.name }.distinct().sorted() }
    var name by rememberSaveable { mutableStateOf("") }
    var selectedPreset by rememberSaveable { mutableStateOf(UnitPreset.DIMENSIONLESS) }
    var customUnit by rememberSaveable { mutableStateOf("") }
    var useCustomBackfill by rememberSaveable { mutableStateOf(false) }
    var backfill by rememberSaveable { mutableStateOf("") }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("New quantity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    placeholder = { Text("Sleep, coffee, focus...") },
                    shape = RectangleShape,
                    singleLine = true,
                )

                Text(
                    text = "Unit presets",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    UnitPreset.values().forEach { preset ->
                        AssistChip(
                            onClick = { selectedPreset = preset },
                            label = { Text(preset.label) },
                        )
                    }
                }
                if (selectedPreset == UnitPreset.CUSTOM) {
                    OutlinedTextField(
                        value = customUnit,
                        onValueChange = { customUnit = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom unit") },
                        placeholder = { Text("Your own metric") },
                        shape = RectangleShape,
                        singleLine = true,
                    )
                    Text(
                        text = "Type the unit name exactly once; it will be reused for analytics.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (knownNames.isNotEmpty()) {
                    Text(
                        text = "Known names",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        knownNames.forEach { suggestion ->
                            AssistChip(
                                onClick = { name = suggestion },
                                label = { Text(suggestion) },
                            )
                        }
                    }
                }

                Text(
                    text = "Previous days default",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = !useCustomBackfill,
                        onClick = {
                            useCustomBackfill = false
                            backfill = ""
                        },
                        label = { Text("None") },
                        shape = RectangleShape,
                    )
                    FilterChip(
                        selected = useCustomBackfill,
                        onClick = { useCustomBackfill = true },
                        label = { Text("Custom") },
                        shape = RectangleShape,
                    )
                }

                if (useCustomBackfill) {
                    OutlinedTextField(
                        value = backfill,
                        onValueChange = { backfill = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Previous-day value") },
                        placeholder = { Text("Leave blank for NaN") },
                        shape = RectangleShape,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        singleLine = true,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    val resolvedUnit = when (selectedPreset) {
                        UnitPreset.CUSTOM -> customUnit.trim()
                        else -> selectedPreset.value
                    }
                    val cleanedName = name.trim()
                    val canConfirm = cleanedName.isNotEmpty() && resolvedUnit.isNotEmpty()
                    TextButton(
                        enabled = canConfirm,
                        onClick = {
                            onConfirm(
                                cleanedName,
                                resolvedUnit,
                                backfill.trim().takeIf { useCustomBackfill && it.isNotEmpty() },
                            )
                        },
                    ) {
                        Text("Add")
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeWheelDialog(
    title: String,
    initialTime: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    var hour by rememberSaveable { mutableStateOf(initialTime.hour) }
    var minute by rememberSaveable { mutableStateOf(initialTime.minute) }
    var second by rememberSaveable { mutableStateOf(initialTime.second) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeNumberPicker(
                        label = "Hour",
                        value = hour,
                        values = (0..23).toList(),
                        onValueChange = { hour = it },
                        modifier = Modifier.weight(1f),
                    )
                    TimeNumberPicker(
                        label = "Min",
                        value = minute,
                        values = (0..59).toList(),
                        onValueChange = { minute = it },
                        modifier = Modifier.weight(1f),
                    )
                    TimeNumberPicker(
                        label = "Sec",
                        value = second,
                        values = (0..59).toList(),
                        onValueChange = { second = it },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onConfirm(LocalTime.of(hour, minute, second)) }) {
                        Text("Set")
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeNumberPicker(
    label: String,
    value: Int,
    values: List<Int>,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AndroidView(
            factory = { context ->
                NumberPicker(context).apply {
                    wrapSelectorWheel = true
                    setBackgroundColor(android.graphics.Color.WHITE)
                    minValue = values.first()
                    maxValue = values.last()
                    displayedValues = values.map { it.toString().padStart(2, '0') }.toTypedArray()
                    setOnValueChangedListener { _, _, newVal ->
                        onValueChange(newVal)
                    }
                    gravity = Gravity.CENTER
                    applyPickerTextColor(android.graphics.Color.BLACK)
                }
            },
            update = { picker ->
                if (picker.minValue != values.first()) picker.minValue = values.first()
                if (picker.maxValue != values.last()) picker.maxValue = values.last()
                val displayed = values.map { it.toString().padStart(2, '0') }.toTypedArray()
                if (picker.displayedValues?.contentEquals(displayed) != true) {
                    picker.displayedValues = displayed
                }
                if (picker.value != value) picker.value = value
                picker.applyPickerTextColor(android.graphics.Color.BLACK)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun NumberPicker.applyPickerTextColor(color: Int) {
    runCatching {
        val selectorWheelPaintField = NumberPicker::class.java.getDeclaredField("mSelectorWheelPaint")
        selectorWheelPaintField.isAccessible = true
        val paint = selectorWheelPaintField.get(this) as? Paint
        paint?.color = color
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child is TextView) {
                child.setTextColor(color)
                child.setHintTextColor(color)
            }
        }
        val inputText = runCatching {
            val inputTextField = NumberPicker::class.java.getDeclaredField("mInputText")
            inputTextField.isAccessible = true
            inputTextField.get(this) as? EditText
        }.getOrNull()
        inputText?.setTextColor(color)
        inputText?.setHintTextColor(color)
        invalidate()
    }
}

private fun editorKind(unit: String): QuantityEditorKind = when (unit.trim().lowercase()) {
    "time" -> QuantityEditorKind.TIME
    "dimensionless", "calories", "grams" -> QuantityEditorKind.NUMBER
    else -> QuantityEditorKind.TEXT
}

private fun String.toTimeOrNull(): LocalTime? = runCatching {
    val parts = trim().split(":")
    when (parts.size) {
        2 -> LocalTime.of(parts[0].toInt(), parts[1].toInt(), 0)
        3 -> LocalTime.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        else -> null
    }
}.getOrNull()

private enum class QuantityEditorKind {
    TIME,
    NUMBER,
    TEXT,
}

private enum class UnitPreset(val label: String, val value: String) {
    TIME("Time", "time"),
    CALORIES("Calories", "calories"),
    DIMENSIONLESS("Dimensionless", "dimensionless"),
    GRAMS("Grams", "grams"),
    CUSTOM("Custom", ""),
}

private fun buildMonthCells(month: YearMonth): List<LocalDate> {
    val firstOfMonth = month.atDay(1)
    val shift = (firstOfMonth.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
    val start = firstOfMonth.minusDays(shift.toLong())
    return List(42) { index -> start.plusDays(index.toLong()) }
}

private fun gradeColor(grade: Int): Color = GradeColors[grade.coerceIn(0, 6)]
