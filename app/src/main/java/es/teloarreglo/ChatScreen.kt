package es.teloarreglo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import es.teloarreglo.chat.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ChatRoute(
    chat: Chat, accountId: String, profileId: String?, conversation: Conversation?,
    onOpened: (Conversation) -> Unit, onBack: () -> Unit,
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
    if (profileId == null) ChatScreen(chat, accountId, conversation, onOpened, onBack)
    else {
        BackHandler(onBack = onBack)
        TeloTheme {
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
}

/** Poll only while visible. Every refresh reloads persisted history, including reconnect gaps. */
@Composable
fun ChatScreen(
    chat: Chat, accountId: String, conversation: Conversation?,
    onSelect: (Conversation) -> Unit, onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var conversations by remember(chat, accountId) { mutableStateOf(emptyList<Conversation>()) }
    var messages by remember(chat, accountId, conversation?.id) { mutableStateOf(emptyList<ChatMessage>()) }
    var loading by remember(chat, accountId, conversation?.id) { mutableStateOf(true) }
    var failed by remember(chat, accountId, conversation?.id) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var text by rememberSaveable(accountId, conversation?.id) { mutableStateOf("") }
    var requestId by rememberSaveable(accountId, conversation?.id) { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sendFailed by rememberSaveable(accountId, conversation?.id) { mutableStateOf(false) }
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
    LaunchedEffect(chat, accountId, conversation?.id, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    if (conversation == null) conversations = chat.conversations()
                    else {
                        val history = chat.messages(conversation.id)
                        // A concurrent poll must not erase a just-confirmed send.
                        messages = (history + messages).distinctBy { it.id }.sortedWith(compareBy({ it.createdAt }, { it.id }))
                    }
                    failed = false
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failed = true
                } finally { loading = false }
                delay(2500)
            }
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
                    sendFailed = true
                } finally { sending = false }
            }
        }
    }
    TeloTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.catalogue_back)) }
                    Text(conversation?.name ?: stringResource(R.string.chat_title), style = MaterialTheme.typography.titleLarge)
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
                            OutlinedCard(onClick = { onSelect(item) }, modifier = Modifier.fillMaxWidth().testTag("conversation_${item.id}")) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                                    if (item.professionalId == accountId) {
                                        Text(stringResource(R.string.chat_client, item.clientId.takeLast(8)))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text(stringResource(R.string.chat_privacy), style = MaterialTheme.typography.bodySmall)
                    if (!loading && !failed && messages.isEmpty()) Text(stringResource(R.string.chat_first))
                    LazyColumn(Modifier.weight(1f).testTag("chat_messages"), state = list,
                        contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(messages, key = { it.id }) { message ->
                            Column(Modifier.fillMaxWidth(),
                                horizontalAlignment = if (message.authorId == accountId) Alignment.End else Alignment.Start) {
                                Surface(color = if (message.authorId == accountId) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                                    Column(Modifier.widthIn(max = 300.dp).padding(12.dp).testTag("message_${message.id}")) {
                                        Text(stringResource(if (message.authorId == accountId) R.string.chat_you else R.string.chat_other),
                                            style = MaterialTheme.typography.labelSmall)
                                        Text(message.body)
                                        Text(message.createdAt, style = MaterialTheme.typography.labelSmall)
                                        if (message.authorId == accountId) Text(stringResource(R.string.chat_sent),
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    if (sending) Text(stringResource(R.string.chat_sending), Modifier.testTag("chat_sending"))
                    if (sendFailed || (requestId != null && !sending)) {
                        Text(stringResource(R.string.chat_send_error), Modifier.testTag("chat_send_error"),
                            color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = send, enabled = !sending, modifier = Modifier.testTag("chat_retry_send")) {
                            Text(stringResource(R.string.chat_retry_send))
                        }
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
    }
}
