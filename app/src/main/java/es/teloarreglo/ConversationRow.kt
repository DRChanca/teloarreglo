package es.teloarreglo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import es.teloarreglo.chat.Conversation
import es.teloarreglo.chat.counterpartName

/** Who is on the other side, without leaking the email when the client declared no name. */
@Composable
fun conversationTitle(item: Conversation, accountId: String): String =
    item.counterpartName(accountId) ?: stringResource(R.string.chat_client, item.clientId.takeLast(8))

/**
 * Inbox row (#39): the other participant's name as the item title, and on the right the server's
 * count of their unread messages. The badge is announced as a full sentence.
 */
@Composable
fun ConversationRow(item: Conversation, accountId: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("conversation_${item.id}")) {
        Row(Modifier.padding(16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(conversationTitle(item, accountId), Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (item.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.unreadCount > 0) {
                val description = pluralStringResource(R.plurals.chat_unread, item.unreadCount, item.unreadCount)
                Spacer(Modifier.width(12.dp))
                Badge(Modifier.semantics { contentDescription = description }.testTag("unread_${item.id}"),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Text(if (item.unreadCount > 99) "99+" else item.unreadCount.toString(),
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}
