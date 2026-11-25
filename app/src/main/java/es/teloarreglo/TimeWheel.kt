package es.teloarreglo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlinx.coroutines.launch

/** Minutes between two options of the minute wheel (#64). */
internal const val WHEEL_MINUTE_STEP = 5

/** A time of day as the wheels show it; [text] is how the cita forms keep it ("HH:mm"). */
internal data class WheelTime(val hour: Int, val minute: Int) {
    val text: String get() = "%02d:%02d".format(Locale.ROOT, hour, minute)
}

/** Where the wheels open for a form time; one that cannot be read opens at 09:00. */
internal fun wheelTime(text: String): WheelTime = try {
    LocalTime.parse(text.trim()).let { WheelTime(it.hour, it.minute) }
} catch (_: DateTimeParseException) { WheelTime(9, 0) }

/**
 * The minute wheel: every [step] minutes, plus the [current] minute when it falls between two steps, so
 * opening and confirming the wheels never moves a time already in force (for example a requested 10:07).
 */
internal fun wheelMinutes(current: Int, step: Int = WHEEL_MINUTE_STEP): List<Int> =
    ((0 until 60 step step) + current).distinct().sorted()

/**
 * The time of a cita (#64): it looks like the other form fields and, when tapped, opens hour and minute wheels
 * (like a phone alarm). The confirmed time reaches the form as "HH:mm", as when it was typed; the server still
 * checks it against the professional's agenda.
 */
@Composable
internal fun TimeField(time: String, label: String, enabled: Boolean, tag: String, modifier: Modifier = Modifier,
                       isError: Boolean = false, supportingText: (@Composable () -> Unit)? = null,
                       onTime: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Column(modifier) {
        Box {
            OutlinedTextField(time, {}, readOnly = true, enabled = enabled, singleLine = true, label = { Text(label) },
                isError = isError, modifier = Modifier.fillMaxWidth().clearAndSetSemantics {})
            Box(Modifier.matchParentSize().testTag(tag)
                .semantics { contentDescription = label; stateDescription = time }
                .clickable(enabled = enabled, role = Role.Button) { picking = true })
        }
        // Outside the cleared field so the message stays readable by TalkBack and findable by its tag (#63).
        if (supportingText != null) CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.error) {
            ProvideTextStyle(MaterialTheme.typography.bodySmall) {
                Box(Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp)) { supportingText() }
            }
        }
    }
    if (picking) TimeWheelDialog(wheelTime(time), label, onDismiss = { picking = false }) { onTime(it.text); picking = false }
}

@Composable
private fun TimeWheelDialog(initial: WheelTime, title: String, onDismiss: () -> Unit, onConfirm: (WheelTime) -> Unit) {
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    val minutes = remember(initial) { wheelMinutes(initial.minute) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Wheel((0..23).toList(), initial.hour, "time_wheel_hour") { hour = it }
                Text(":", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 8.dp))
                Wheel(minutes, initial.minute, "time_wheel_minute") { minute = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(WheelTime(hour, minute)) }, modifier = Modifier.testTag("time_wheel_confirm")) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("time_wheel_dismiss")) { Text(stringResource(R.string.cancel)) } },
    )
}

private val WheelItemHeight = 44.dp
private const val WHEEL_SIDE_ITEMS = 2

/** One snapping column; the option in the middle band is the chosen one. Tapping an option also brings it there. */
@Composable
private fun Wheel(values: List<Int>, initial: Int, tag: String, onSelected: (Int) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = values.indexOf(initial).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    val itemPx = with(LocalDensity.current) { WheelItemHeight.toPx() }
    // With one band of padding per side item, the first visible item is the one in the middle band.
    val centered by remember {
        derivedStateOf {
            val next = if (state.firstVisibleItemScrollOffset > itemPx / 2) 1 else 0
            (state.firstVisibleItemIndex + next).coerceIn(0, values.lastIndex)
        }
    }
    val latest by rememberUpdatedState(onSelected)
    LaunchedEffect(state, values) { snapshotFlow { centered }.collect { latest(values[it]) } }
    Box(Modifier.width(72.dp).height(WheelItemHeight * (2 * WHEEL_SIDE_ITEMS + 1)), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(WheelItemHeight)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)))
        LazyColumn(state = state, flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(vertical = WheelItemHeight * WHEEL_SIDE_ITEMS),
            horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.matchParentSize().testTag(tag)) {
            itemsIndexed(values) { index, value ->
                val chosen = index == centered
                Box(Modifier.fillMaxWidth().height(WheelItemHeight).testTag("${tag}_$value")
                    .semantics { selected = chosen }
                    .clickable { scope.launch { state.animateScrollToItem(index) } },
                    contentAlignment = Alignment.Center) {
                    Text("%02d".format(Locale.ROOT, value),
                        style = if (chosen) MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                            else MaterialTheme.typography.titleMedium,
                        color = if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
