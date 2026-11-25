package es.teloarreglo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import es.teloarreglo.chat.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import java.util.UUID
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ChatRoute(
    chat: Chat, accountId: String, profileId: String?, conversation: Conversation?,
    onOpened: (Conversation) -> Unit, onBack: () -> Unit,
    appointmentsSlot: (@Composable (Conversation) -> Unit)? = null,
    attachments: ChatAttachments? = null,
    evidence: ChatEvidenceLink? = null,
) {
    var failed by remember(profileId) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(chat, accountId, profileId, retry) {
        if (profileId != null) {
            failed = false
            try { onOpened(chat.open(profileId)) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                failed = true
            }
        }
    }
    if (profileId == null) ChatScreen(chat, accountId, conversation, onOpened, onBack, appointmentsSlot, attachments, evidence)
    else {
        BackHandler(onBack = onBack)
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.catalogue_back)) }
                if (failed) {
                    Text(stringResource(R.string.chat_open_error))
                    Button(onClick = { retry++ }) { Text(stringResource(R.string.retry)) }
                } else CircularProgressIndicator()
            }
        }
    }
}

/**
 * Poll only while visible. Every refresh reloads persisted history, including reconnect gaps. A text search finds
 * messages; with an open incident ([evidence]), a long press selects messages to add to it (#26).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    chat: Chat, accountId: String, conversation: Conversation?,
    onSelect: (Conversation) -> Unit, onBack: () -> Unit,
    appointmentsSlot: (@Composable (Conversation) -> Unit)? = null,
    attachments: ChatAttachments? = null,
    evidence: ChatEvidenceLink? = null,
) {
    BackHandler(onBack = onBack)
    var selection by rememberSaveable(accountId, conversation?.id, stateSaver = MessageSelectionSaver) {
        mutableStateOf(MessageSelection())
    }
    // The incident whose card started the selection; it decides what is already contributed.
    var preferred by rememberSaveable(accountId, conversation?.id) { mutableStateOf<String?>(null) }
    var confirming by rememberSaveable(accountId, conversation?.id) { mutableStateOf(false) }
    var justAdded by remember(accountId, conversation?.id) { mutableStateOf(emptySet<String>()) }
    var addedNotice by remember { mutableStateOf<Int?>(null) }
    var searching by rememberSaveable(accountId, conversation?.id) { mutableStateOf(false) }
    var query by rememberSaveable(accountId, conversation?.id) { mutableStateOf("") }
    var matchIndex by rememberSaveable(accountId, conversation?.id) { mutableIntStateOf(-1) }
    // Registered after the screen's own handler, so it runs first: back leaves selection or search before the chat.
    BackHandler(enabled = selection.active || searching) {
        if (selection.active) selection = selection.cancel() else { searching = false; query = "" }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var conversations by remember(chat, accountId) { mutableStateOf(emptyList<Conversation>()) }
    var messages by remember(chat, accountId, conversation?.id) { mutableStateOf(emptyList<ChatMessage>()) }
    var loading by remember(chat, accountId, conversation?.id) { mutableStateOf(true) }
    var failed by remember(chat, accountId, conversation?.id) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var receipts by remember(chat, accountId, conversation?.id) { mutableStateOf(Receipts()) }
    var text by rememberSaveable(accountId, conversation?.id) { mutableStateOf("") }
    var requestId by rememberSaveable(accountId, conversation?.id) { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sendFailed by rememberSaveable(accountId, conversation?.id) { mutableStateOf(false) }
    var rejection by remember(accountId, conversation?.id) { mutableStateOf<SendFailure?>(null) }
    val context = LocalContext.current
    val outbox = remember { ChatOutbox(context.applicationContext) }
    var outboxReady by remember(accountId, conversation?.id) { mutableStateOf(conversation == null) }
    var outboxFailed by remember { mutableStateOf(false) }
    LaunchedEffect(accountId, conversation?.id, refresh) {
        if (conversation != null && !outboxReady) {
            try {
                outbox.load(accountId, conversation.id)?.let {
                    text = it.body
                    requestId = it.requestId
                }
                outboxReady = true
                outboxFailed = false
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                outboxFailed = true
            }
        }
    }
    val list = rememberLazyListState()
    val locale = LocalConfiguration.current.locales[0]
    val timestampFormat = remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(ZoneId.systemDefault())
    }
    LaunchedEffect(messages, requestId, outboxReady, sending) {
        val pending = requestId
        if (conversation != null && outboxReady && !sending && pending != null &&
            messages.any { it.authorId == accountId && it.requestId == pending && it.body == text.trim(' ') }) {
            try {
                outbox.clear(accountId, conversation.id)
                requestId = null
                text = ""
                sendFailed = false
                rejection = null
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                outboxFailed = true
            }
        }
    }
    LaunchedEffect(chat, accountId, conversation?.id, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // While visible, a message push for this conversation is not shown: it refreshes the chat instead.
            if (conversation != null) OpenConversation.id = conversation.id
            try {
                while (true) {
                    try {
                        if (conversation == null) {
                            conversations = chat.conversations()
                            // Fetching the inbox delivers every conversation: the senders see two ticks.
                            try { chat.markAllDelivered() } catch (error: Exception) { if (error is CancellationException) throw error }
                        } else {
                            val history = chat.messages(conversation.id)
                            // A concurrent poll must not erase a just-confirmed send.
                            messages = (history + messages).distinctBy { it.id }.sortedWith(compareBy({ it.createdAt }, { it.id }))
                            receipts = chat.receipts(conversation.id)
                        }
                        failed = false
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        failed = true
                    } finally { loading = false }
                    withTimeoutOrNull(2500) { OpenConversation.pushes.first { it == conversation?.id } }
                }
            } finally {
                if (conversation != null && OpenConversation.id == conversation.id) OpenConversation.id = null
            }
        }
    }
    // Opening, and every newly seen message while open, marks the conversation read (#39).
    val newest = messages.lastOrNull()?.id
    LaunchedEffect(chat, accountId, conversation?.id, newest, loading) {
        if (conversation != null && !loading) {
            try { chat.markRead(conversation.id) }
            catch (error: Exception) { if (error is CancellationException) throw error }
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex)
    }
    val send: () -> Unit = {
        val current = conversation
        if (current != null && !sending && text.isNotBlank()) {
            val key = requestId ?: UUID.randomUUID().toString().also { requestId = it }
            val body = text
            sending = true
            sendFailed = false
            rejection = null
            scope.launch {
                try {
                    outbox.save(accountId, current.id, PendingText(key, body))
                    val confirmed = chat.send(current.id, key, body)
                    messages = (messages + confirmed).distinctBy { it.id }
                    outbox.clear(accountId, current.id)
                    text = ""
                    requestId = null
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    rejection = (error as? ChatSendException)?.failure
                    if (rejection == SendFailure.INVALID_TEXT) {
                        try {
                            outbox.clear(accountId, current.id)
                            requestId = null
                        } catch (storageError: Exception) {
                            if (storageError is CancellationException) throw storageError
                            outboxFailed = true
                        }
                    }
                    sendFailed = rejection == null
                } finally { sending = false }
            }
        }
    }
    // Where messages can go (published by the appointments panel) and what each incident already holds.
    val targets = evidence?.targets.orEmpty().filter { it.conversationId == conversation?.id }
    val focus = targets.firstOrNull { it.incidentId == preferred } ?: targets.singleOrNull()
    val inIncident = targets.flatMap { it.contributed }.toSet() + justAdded
    val unavailable = (focus?.contributed ?: targets.map { it.contributed }.reduceOrNull { a, b -> a intersect b }.orEmpty()) +
        justAdded
    val canSelect = conversation != null && targets.isNotEmpty()
    LaunchedEffect(evidence?.requested, conversation?.id) {
        val requested = evidence?.requested ?: return@LaunchedEffect
        if (conversation != null) { preferred = requested; selection = selection.start() }
        evidence.requested = null
    }
    LaunchedEffect(addedNotice) { if (addedNotice != null) { delay(5000); addedNotice = null } }
    val search = remember(messages, query) { MessageSearch(messages, query) }
    LaunchedEffect(query) { matchIndex = search.initial }
    val currentMatch = search.idAt(matchIndex)
    LaunchedEffect(currentMatch) {
        val index = messages.indexOfFirst { it.id == currentMatch }
        if (index >= 0) list.animateScrollToItem(index)
    }
    CompositionLocalProvider(LocalChatEvidence provides evidence) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = TeloTheme.spacing.screen)) {
            if (conversation != null && selection.active) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("chat_selection_bar")) {
                    IconButton(onClick = { selection = selection.cancel() }, modifier = Modifier.testTag("chat_selection_cancel")) {
                        Icon(Icons.Default.Close, stringResource(R.string.chat_select_cancel))
                    }
                    Text(stringResource(R.string.chat_select_count, selection.ids.size), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).testTag("chat_selection_count"))
                    if (!searching) IconButton(onClick = { searching = true }, modifier = Modifier.testTag("chat_search_open")) {
                        Icon(Icons.Default.Search, stringResource(R.string.chat_search))
                    }
                }
                if (canSelect) {
                    if (selection.ids.isEmpty()) Text(stringResource(R.string.chat_select_hint), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { confirming = true }, enabled = selection.ids.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().testTag("chat_selection_add")) {
                        Text(stringResource(R.string.chat_select_add))
                    }
                }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                // The list is a bottom-bar tab (#47): only an open conversation has its own «Volver».
                if (conversation != null) TextButton(onClick = onBack) { Text(stringResource(R.string.catalogue_back)) }
                Text(conversation?.let { conversationTitle(it, accountId) } ?: stringResource(R.string.chat_title),
                    style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (conversation != null && !searching) IconButton(onClick = { searching = true },
                    modifier = Modifier.testTag("chat_search_open")) {
                    Icon(Icons.Default.Search, stringResource(R.string.chat_search))
                }
            }
            if (conversation != null && searching) ChatSearchBar(query, { query = it }, matchIndex, search.ids.size,
                onPrevious = { matchIndex = search.step(matchIndex, -1) }, onNext = { matchIndex = search.step(matchIndex, +1) },
                onClose = { searching = false; query = "" })
            addedNotice?.let {
                Text(stringResource(R.string.chat_select_done, it), color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("chat_selection_done"))
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed || outboxFailed) {
                Text(stringResource(R.string.chat_error), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.retry)) }
            }
            if (conversation == null) {
                if (!loading && !failed && conversations.isEmpty()) Text(stringResource(R.string.chat_empty))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(conversations, key = { it.id }) { item ->
                        ConversationRow(item, accountId) { onSelect(item) }
                    }
                }
            } else {
                Text(stringResource(R.string.chat_privacy), style = MaterialTheme.typography.bodySmall)
                appointmentsSlot?.invoke(conversation)
                if (!loading && !failed && messages.isEmpty()) Text(stringResource(R.string.chat_first))
                LazyColumn(Modifier.weight(1f).testTag("chat_messages"), state = list,
                    contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(messages, key = { it.id }) { message ->
                        val mine = message.authorId == accountId
                        val selected = message.id in selection.ids
                        val locked = message.id in unavailable
                        val toggle = { selection = selection.tap(message.id, unavailable) }
                        Row(Modifier.fillMaxWidth()
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                                else Color.Transparent, MaterialTheme.shapes.small)
                            .then(if (canSelect) Modifier.combinedClickable(
                                interactionSource = null, indication = if (selection.active) ripple() else null,
                                onLongClickLabel = stringResource(R.string.chat_select_long_press),
                                onLongClick = { selection = selection.longPress(message.id, unavailable) },
                                onClick = toggle) else Modifier)
                            .testTag("message_row_${message.id}"),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (selection.active) Checkbox(selected || locked, onCheckedChange = null, enabled = !locked,
                                modifier = Modifier.testTag("message_select_${message.id}"))
                            Column(Modifier.weight(1f), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                                Box {
                                    Surface(color = if (mine) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                                        Column(Modifier.widthIn(max = 300.dp).padding(12.dp).testTag("message_${message.id}")) {
                                            Text(stringResource(if (mine) R.string.chat_you else R.string.chat_other),
                                                style = MaterialTheme.typography.labelSmall)
                                            if (message.attachmentPath != null) {
                                                if (attachments != null) ChatAttachmentBubble(message, attachments)
                                                else Text(stringResource(if (message.attachmentKind == "video")
                                                    R.string.video_message else R.string.photo_message))
                                            }
                                            if (message.body.isNotEmpty()) Text(highlighted(message.body,
                                                search.ranges(message.body), current = message.id == currentMatch))
                                            Text(timestampFormat.format(Instant.parse(message.createdAt)),
                                                style = MaterialTheme.typography.labelSmall)
                                            if (mine) MessageTicks(receipts.tickOf(message))
                                            if (message.id in inIncident) Text(stringResource(R.string.chat_select_contributed),
                                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary,
                                                modifier = Modifier.testTag("message_in_incident_${message.id}"))
                                        }
                                    }
                                    // While selecting, a tap on a photo or video selects it instead of opening it.
                                    if (selection.active) Box(Modifier.matchParentSize().clickable(onClick = toggle))
                                }
                            }
                        }
                    }
                }
                if (sending) Text(stringResource(R.string.chat_sending), Modifier.testTag("chat_sending"))
                if (sendFailed || rejection != null || (requestId != null && !sending)) {
                    Text(stringResource(when (rejection) {
                        SendFailure.ACCESS -> R.string.chat_send_access
                        SendFailure.INVALID_TEXT -> R.string.chat_send_invalid
                        SendFailure.CONFLICT -> R.string.chat_send_conflict
                        null -> R.string.chat_send_error
                    }), Modifier.testTag("chat_send_error"),
                        color = MaterialTheme.colorScheme.error)
                    if (requestId != null) TextButton(onClick = send, enabled = outboxReady && !sending,
                        modifier = Modifier.testTag("chat_retry_send")) {
                        Text(stringResource(R.string.chat_retry_send))
                    }
                }
                if (attachments != null) ChatAttachmentControls(attachments, accountId, conversation.id, outboxReady) { sent ->
                    messages = (messages + sent).distinctBy { it.id }.sortedWith(compareBy({ it.createdAt }, { it.id }))
                }
                OutlinedTextField(text, { if (it.length <= 4000) text = it },
                    enabled = outboxReady && requestId == null && !sending,
                    label = { Text(stringResource(R.string.chat_input)) },
                    modifier = Modifier.fillMaxWidth().testTag("chat_input"), maxLines = 4)
                Button(onClick = send, enabled = outboxReady && !sending && requestId == null && text.isNotBlank(),
                    modifier = Modifier.align(Alignment.End).testTag("chat_send")) {
                    Text(stringResource(R.string.chat_send))
                }
            }
        }
    }
    if (confirming && evidence != null && canSelect) AddToIncidentDialog(evidence, targets, preferred, selection.ids,
        onFinished = { added, complete ->
            justAdded = justAdded + added
            selection = selection.withoutAdded(added)
            if (complete) { confirming = false; addedNotice = added.size }
        }, onDismiss = { confirming = false })
    }
}

/** One grey tick = on the server, two grey = delivered to the other phone, two blue = read (described for TalkBack). */
@Composable
private fun ColumnScope.MessageTicks(tick: Tick) {
    val description = stringResource(when (tick) {
        Tick.SENT -> R.string.chat_sent
        Tick.DELIVERED -> R.string.chat_tick_delivered
        Tick.READ -> R.string.chat_tick_read
    })
    Text(if (tick == Tick.SENT) "\u2713" else "\u2713\u2713",
        Modifier.align(Alignment.End).semantics { contentDescription = description }.testTag("tick_${tick.name.lowercase()}"),
        color = if (tick == Tick.READ) TeloTheme.extended.readTick else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
}
