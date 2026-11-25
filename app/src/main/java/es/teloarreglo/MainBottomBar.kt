package es.teloarreglo

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow

private val Tab.icon: Int @DrawableRes get() = when (this) {
    Tab.HOME -> R.drawable.ic_tab_home
    Tab.NEARBY -> R.drawable.ic_tab_nearby
    Tab.CHATS -> R.drawable.ic_tab_chats
    Tab.NOTICES -> R.drawable.ic_tab_notices
    Tab.ACCOUNT -> R.drawable.ic_tab_account
}

private val Tab.label: Int @StringRes get() = when (this) {
    Tab.HOME -> R.string.tab_home
    Tab.NEARBY -> R.string.tab_nearby
    Tab.CHATS -> R.string.chat_title
    Tab.NOTICES -> R.string.notices_title
    Tab.ACCOUNT -> R.string.catalogue_account
}

/**
 * Bottom bar of the main screens (#46): fixed icons, only the selected tab shows its label.
 * Nearby is left out when the nearby search is not available.
 */
@Composable
fun MainBottomBar(selected: Tab, nearbyAvailable: Boolean, unreadNotices: Int, onSelect: (Tab) -> Unit) {
    NavigationBar(Modifier.testTag("bottom_bar")) {
        Tab.entries.filter { it != Tab.NEARBY || nearbyAvailable }.forEach { tab ->
            val label = stringResource(tab.label)
            val unread = if (tab == Tab.NOTICES) unreadNotices else 0
            // The icon carries the spoken name; the label is only drawn for the selected tab.
            val description = if (unread > 0) "$label, " + pluralStringResource(R.plurals.tab_notices_unread, unread, unread)
                else label
            NavigationBarItem(
                selected = tab == selected,
                onClick = { if (tab != selected) onSelect(tab) },
                icon = {
                    if (unread > 0) BadgedBox(badge = {
                        Badge(Modifier.testTag("tab_notices_badge")) {
                        Text(if (unread > 99) "99+" else "$unread", Modifier.clearAndSetSemantics { })
                    }
                    }) { Icon(painterResource(tab.icon), contentDescription = description) }
                    else Icon(painterResource(tab.icon), contentDescription = description)
                },
                label = { Text(label, Modifier.clearAndSetSemantics { }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                alwaysShowLabel = false,
                modifier = Modifier.testTag("tab_${tab.name.lowercase()}"),
            )
        }
    }
}
