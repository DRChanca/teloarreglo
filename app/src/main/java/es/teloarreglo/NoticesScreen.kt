package es.teloarreglo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.teloarreglo.notices.Notice
import es.teloarreglo.notices.NoticeTopic
import es.teloarreglo.notices.Notices
import es.teloarreglo.notices.PushRegistration
import es.teloarreglo.notices.PushStatus
import es.teloarreglo.notices.topic
import es.teloarreglo.notices.topicText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Persisted notices of the signed-in account (#32), read from the server every time the screen opens, so they
 * survive closing the app and missed pushes. Push settings for this device live at the top.
 */
@Composable
fun NoticesScreen(
    notices: Notices, push: PushRegistration?, accountId: String, operationScope: CoroutineScope,
    askPermission: suspend () -> Unit,
    onOpenConversation: (String) -> Unit, onOpenAccount: () -> Unit, onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var attempt by remember { mutableIntStateOf(0) }
    var items by remember(accountId) { mutableStateOf<List<Notice>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(accountId, attempt) {
        failed = false
        try { items = notices.list() } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
    }
    LaunchedEffect(accountId) { push?.sync(accountId) }
    TeloTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.catalogue_back)) }
                    Text(stringResource(R.string.notices_title), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { attempt++ }, Modifier.testTag("notices_refresh")) {
                        Text(stringResource(R.string.notices_refresh))
                    }
                }
                PushCard(push, operationScope, askPermission)
                Spacer(Modifier.size(12.dp))
                val current = items
                when {
                    failed && current == null -> Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(stringResource(R.string.notices_error))
                        Button(onClick = { attempt++ }) { Text(stringResource(R.string.retry)) }
                    }
                    current == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    current.isEmpty() -> Text(stringResource(R.string.notices_empty), Modifier.padding(vertical = 24.dp))
                    else -> LazyColumn(Modifier.testTag("notices_list")) {
                        if (failed) item { Text(stringResource(R.string.notices_error), color = MaterialTheme.colorScheme.error) }
                        items(current, key = { it.id }) { notice ->
                            NoticeRow(notice) {
                                items = current.map { if (it.id == notice.id) it.copy(read = true) else it }
                                operationScope.launch {
                                    try { notices.markRead(listOf(notice.id)) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                                }
                                val conversation = notice.conversationId
                                when {
                                    conversation != null -> onOpenConversation(conversation)
                                    notice.kind == "profile_review" -> onOpenAccount()
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeRow(notice: Notice, onClick: () -> Unit) {
    val time = remember(notice.createdAt) {
        runCatching {
            OffsetDateTime.parse(notice.createdAt).atZoneSameInstant(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
        }.getOrDefault("")
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp).testTag("notice_${notice.id}"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(if (notice.read) Color.Transparent else MaterialTheme.colorScheme.primary, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(topicText(notice.topic())),
                fontWeight = if (notice.read) FontWeight.Normal else FontWeight.SemiBold)
            if (notice.topic() == NoticeTopic.MESSAGE && !notice.read) {
                Text(pluralStringResource(R.plurals.notices_messages, notice.count, notice.count),
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        if (!notice.read) Text(stringResource(R.string.notices_unread), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PushCard(push: PushRegistration?, operationScope: CoroutineScope, askPermission: suspend () -> Unit) {
    val status by (push?.status ?: remember { MutableStateFlow(PushStatus.NOT_CONFIGURED) }).collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    fun run(action: suspend () -> Unit) {
        busy = true
        operationScope.launch { try { action() } finally { busy = false } }
    }
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF4F1)), shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("notices_push")) {
        Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(when (status) {
                PushStatus.ACTIVE -> R.string.notices_push_active
                PushStatus.OFF -> R.string.notices_push_off
                PushStatus.PERMISSION_NEEDED -> R.string.notices_push_permission
                PushStatus.FAILED -> R.string.notices_push_failed
                PushStatus.NOT_CONFIGURED -> R.string.notices_push_not_configured
                PushStatus.SIGNED_OUT -> R.string.notices_push_checking
            }), fontWeight = FontWeight.SemiBold)
            if (push != null && !busy) when (status) {
                PushStatus.ACTIVE -> OutlinedButton(onClick = { run { push.setEnabled(false) } }) {
                    Text(stringResource(R.string.notices_push_disable))
                }
                PushStatus.OFF -> Button(onClick = { run { askPermission(); push.setEnabled(true) } }) {
                    Text(stringResource(R.string.notices_push_enable))
                }
                PushStatus.PERMISSION_NEEDED -> Button(onClick = { run { askPermission(); push.setEnabled(true) } }) {
                    Text(stringResource(R.string.notices_push_allow))
                }
                PushStatus.FAILED -> Button(onClick = { run { push.setEnabled(true) } }) { Text(stringResource(R.string.retry)) }
                else -> Unit
            } else if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            Text(stringResource(R.string.notices_push_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}
