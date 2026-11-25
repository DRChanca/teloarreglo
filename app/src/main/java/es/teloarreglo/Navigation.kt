package es.teloarreglo

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.saveable.Saver
import es.teloarreglo.chat.Conversation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Destinations of the bottom bar (#46), always in this order. HOME is the single search (ADR 0001,
 * labelled «Buscar»): the separate «Cerca» tab was merged into it.
 */
enum class Tab(@DrawableRes val icon: Int, @StringRes val label: Int) {
    HOME(R.drawable.ic_tab_search, R.string.tab_search),
    CHATS(R.drawable.ic_tab_chats, R.string.tab_chats),
    NOTICES(R.drawable.ic_tab_notices, R.string.tab_notices),
    ACCOUNT(R.drawable.ic_tab_account, R.string.tab_account),
}

/** What the main activity shows; only the main screens keep the bottom bar. */
enum class MainScreen(val showsBar: Boolean) {
    CATALOGUE(true), CHATS(true), NOTICES(true), ACCESS(true),
    CONVERSATION(false), NAME_STEP(false), HOME_STEP(false), HOME_EDITOR(false), PROFILE_EDITOR(false),
    /** An email link (new password or invalid link) that must be answered before navigating. */
    EMAIL_LINK(false),
}

/** What the account allows to show, read from the identity and the mandatory steps. */
data class Session(
    val connected: Boolean,
    val mustGiveName: Boolean = false,
    val mustGiveHome: Boolean = false,
    val emailLinkPending: Boolean = false,
)

/**
 * Navigation of the main activity (#46): the selected tab and the subscreen open inside it.
 * Saved across recreation; no navigation library.
 */
@Serializable
data class Navigation(
    val tab: Tab = Tab.HOME,
    /** Profile shown in the catalogue. */
    val profile: String? = null,
    /** Profile whose conversation is being opened. */
    val contactProfile: String? = null,
    val conversation: Conversation? = null,
    val profileEditor: Boolean = false,
    val editingHome: Boolean = false,
) {
    fun open(tab: Tab) = Navigation(tab)
    fun showProfile(id: String) = Navigation(Tab.HOME, profile = id)
    fun contact(profileId: String) = Navigation(Tab.CHATS, profile = profile, contactProfile = profileId)
    fun openConversation(conversation: Conversation) =
        copy(tab = Tab.CHATS, contactProfile = null, conversation = conversation)
    fun editProfile() = copy(profileEditor = true)
    fun editHome() = copy(editingHome = true)
    fun doneEditingHome() = copy(editingHome = false)

    fun screen(session: Session): MainScreen = when {
        session.mustGiveName -> MainScreen.NAME_STEP
        session.mustGiveHome -> MainScreen.HOME_STEP
        editingHome && session.connected -> MainScreen.HOME_EDITOR
        profileEditor -> MainScreen.PROFILE_EDITOR
        session.emailLinkPending -> MainScreen.EMAIL_LINK
        tab == Tab.HOME -> MainScreen.CATALOGUE
        !session.connected -> MainScreen.ACCESS
        tab == Tab.NOTICES -> MainScreen.NOTICES
        tab == Tab.CHATS -> if (conversation != null || contactProfile != null) MainScreen.CONVERSATION else MainScreen.CHATS
        else -> MainScreen.ACCESS
    }

    /** Where Back goes; null leaves the app. A profile open in the catalogue closes itself first. */
    fun back(): Navigation? = when {
        editingHome -> doneEditingHome()
        profileEditor -> copy(profileEditor = false)
        tab == Tab.CHATS && conversation != null && profile == null -> copy(conversation = null)
        tab == Tab.CHATS && profile != null -> showProfile(profile)
        tab != Tab.HOME -> Navigation()
        else -> null
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        val saver = Saver<Navigation, String>(
            save = { json.encodeToString(serializer(), it) },
            restore = { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() },
        )
    }
}
