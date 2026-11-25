package es.teloarreglo

import es.teloarreglo.chat.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #46: which screen the main activity shows, whether the bottom bar is there, and where Back goes. */
class NavigationTest {
    private val visitor = Session(connected = false)
    private val client = Session(connected = true)
    private val conversation = Conversation("conversation-1", "profile-7", "client-1", "professional-1", "Fontanería Nalón")

    @Test fun theAppOpensOnTheCatalogueWithTheBar() {
        val screen = Navigation().screen(visitor)
        assertEquals(MainScreen.CATALOGUE, screen)
        assertTrue(screen.showsBar)
    }

    @Test fun theConversationListShowsTheBarButAnOpenChatHidesIt() {
        val list = Navigation().open(Tab.CHATS)
        assertEquals(MainScreen.CHATS, list.screen(client))
        assertTrue(list.screen(client).showsBar)
        val chat = list.openConversation(conversation)
        assertEquals(MainScreen.CONVERSATION, chat.screen(client))
        assertFalse(chat.screen(client).showsBar)
    }

    @Test fun contactingAProfileOpensItsChatWithoutTheBar() {
        val contacting = Navigation().showProfile("profile-7").contact("profile-7")
        assertEquals(Tab.CHATS, contacting.tab)
        assertFalse(contacting.screen(client).showsBar)
    }

    @Test fun visitorsAreAskedToSignInFromConversationsAndNoticesKeepingTheBar() {
        for (tab in listOf(Tab.CHATS, Tab.NOTICES)) {
            val screen = Navigation().open(tab).screen(visitor)
            assertEquals(MainScreen.ACCESS, screen)
            assertTrue(screen.showsBar)
        }
        assertEquals(MainScreen.NOTICES, Navigation().open(Tab.NOTICES).screen(client))
    }

    @Test fun mandatoryStepsAndEditorsHideTheBar() {
        val anyTab = Navigation().open(Tab.ACCOUNT)
        assertEquals(MainScreen.NAME_STEP, anyTab.screen(client.copy(mustGiveName = true)))
        assertEquals(MainScreen.HOME_STEP, anyTab.screen(client.copy(mustGiveHome = true)))
        assertFalse(anyTab.editHome().screen(client).showsBar)
        assertFalse(anyTab.editProfile().screen(client).showsBar)
        for (screen in listOf(MainScreen.NAME_STEP, MainScreen.HOME_STEP)) assertFalse(screen.showsBar)
    }

    @Test fun theBarHasTheSingleSearchFirstAndNoSeparateNearbyTab() {
        // ADR 0001: «Buscar» replaces Inicio and Cerca.
        assertEquals(listOf(Tab.HOME, Tab.CHATS, Tab.NOTICES, Tab.ACCOUNT), Tab.entries)
        assertEquals(R.string.tab_search, Tab.HOME.label)
        assertEquals(MainScreen.CATALOGUE, Navigation().screen(client))
    }

    @Test fun backReturnsToTheTabAProfileOrConversationWasOpenedFrom() {
        // #46 story 21: a profile opened from Cuenta closes back into Cuenta, also after its conversation.
        val fromAccount = Navigation().open(Tab.ACCOUNT).showProfile("profile-7")
        assertEquals(Tab.HOME, fromAccount.tab)
        assertEquals(Navigation(Tab.ACCOUNT), fromAccount.back())
        assertEquals(fromAccount, fromAccount.contact("profile-7").openConversation(conversation).back())
        // A conversation opened from a notice goes back to the notices, not to the inbox.
        assertEquals(Navigation(Tab.NOTICES), Navigation().openFromNotice(conversation).back())
        // From the search itself nothing changes: the profile closes into the list.
        assertNull(Navigation().showProfile("profile-7").returnTo)
    }

    @Test fun backFromAnyTabGoesHomeAndBackAtHomeLeaves() {
        for (tab in listOf(Tab.CHATS, Tab.NOTICES, Tab.ACCOUNT)) {
            assertEquals(Navigation(), Navigation().open(tab).back())
        }
        assertNull(Navigation().back())
    }

    @Test fun backInsideASubscreenReturnsToWhereItCameFrom() {
        val list = Navigation().open(Tab.CHATS)
        assertEquals(list, list.openConversation(conversation).back())
        val fromProfile = Navigation().showProfile("profile-7").contact("profile-7").openConversation(conversation)
        assertEquals(Navigation().showProfile("profile-7"), fromProfile.back())
        val account = Navigation().open(Tab.ACCOUNT)
        assertEquals(account, account.editProfile().back())
        assertEquals(account, account.editHome().back())
    }
}
