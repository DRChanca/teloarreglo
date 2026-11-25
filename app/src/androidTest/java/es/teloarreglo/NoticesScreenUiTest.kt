package es.teloarreglo

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import es.teloarreglo.notices.Notice
import es.teloarreglo.notices.NoticeOpening
import es.teloarreglo.notices.Notices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Notices list (#47): each row says where it leads; rows without a destination are not tappable. */
class NoticesScreenUiTest {
    @get:Rule val compose = createComposeRule()

    private fun notice(id: String, kind: String, payload: String) =
        Notice(id, kind, Json.parseToJsonElement(payload).jsonObject, "2026-11-23T10:00:00+00:00")

    private class FakeNotices(private val list: List<Notice>) : Notices {
        val marked = mutableListOf<List<String>>()
        override suspend fun list() = list
        override suspend fun markRead(ids: List<String>) { marked += ids }
    }

    private val list = listOf(
        notice("chat", "message", """{"conversation_id":"c1"}"""),
        notice("review", "profile_review", """{"status":"approved"}"""),
        notice("other", "other", """{}"""),
    )

    private fun show(notices: Notices, opening: NoticeOpening, opened: MutableList<String>) {
        compose.setContent {
            NoticesScreen(notices, push = null, accountId = "user-a", operationScope = CoroutineScope(Dispatchers.Main),
                askPermission = {}, onOpenConversation = { opened += it; opening },
                onOpenAccount = { opened += "account" }, onBack = {})
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("notices_list")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun rowsSayWhereTheyLeadAndRowsWithoutDestinationAreNotTappable() {
        val notices = FakeNotices(list)
        show(notices, opening = NoticeOpening.OPENED, opened = mutableListOf())
        compose.onNodeWithTag("notice_action_chat", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("notice_action_review", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasTestTag("notice_action_other"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("notice_other").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        // Seeing a notice that leads nowhere is reading it.
        compose.waitUntil(5_000) { notices.marked.isNotEmpty() }
        assertEquals(listOf(listOf("other")), notices.marked)
    }

    @Test fun aConversationThatIsNotFoundIsReported() {
        val opened = mutableListOf<String>()
        show(FakeNotices(list), opening = NoticeOpening.NOT_FOUND, opened = opened)
        compose.onNodeWithTag("notice_chat").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("notices_conversation_missing")).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf("c1"), opened)
    }

    @Test fun aConversationThatCannotBeReachedIsReportedAsAConnectionProblem() {
        val opened = mutableListOf<String>()
        show(FakeNotices(list), opening = NoticeOpening.UNREACHABLE, opened = opened)
        compose.onNodeWithTag("notice_chat").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("notices_conversation_unreachable")).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodes(hasTestTag("notices_conversation_missing")).fetchSemanticsNodes().isEmpty())
        assertEquals(listOf("c1"), opened)
    }

    @Test fun aProfileReviewOpensTheAccount() {
        val opened = mutableListOf<String>()
        show(FakeNotices(list), opening = NoticeOpening.OPENED, opened = opened)
        compose.onNodeWithTag("notice_review").performClick()
        assertEquals(listOf("account"), opened)
    }
}
