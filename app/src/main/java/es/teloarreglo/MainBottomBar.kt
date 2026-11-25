package es.teloarreglo

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

/**
 * Bottom bar of the main screens (#46): fixed icons, only the selected tab shows its label.
 */
@Composable
fun MainBottomBar(selected: Tab, unreadNotices: Int, onSelect: (Tab) -> Unit) {
    NavigationBar(Modifier.testTag("bottom_bar")) {
        Tab.entries.forEach { tab ->
            val label = stringResource(tab.label)
            val unread = if (tab == Tab.NOTICES) unreadNotices else 0
            // The icon carries the spoken name; the label is only drawn for the selected tab.
            val description = if (unread > 0) stringResource(R.string.tab_with_unread, label,
                pluralStringResource(R.plurals.tab_notices_unread, unread, unread)) else label
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
