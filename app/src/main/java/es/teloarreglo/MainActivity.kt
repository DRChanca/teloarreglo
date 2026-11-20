package es.teloarreglo

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.teloarreglo.identity.AccessStage
import es.teloarreglo.chat.Conversation
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var account by rememberSaveable { mutableStateOf(false) }
            var ownProfile by rememberSaveable { mutableStateOf<String?>(null) }
            var chatVisible by rememberSaveable { mutableStateOf(false) }
            var contactProfile by rememberSaveable { mutableStateOf<String?>(null) }
            var conversationJson by rememberSaveable { mutableStateOf<String?>(null) }
            val app = application as TeloarregloApplication
            val access by app.identity.state.collectAsStateWithLifecycle()
            var knownOwner by rememberSaveable { mutableStateOf<String?>(null) }
            LaunchedEffect(access) {
                if (access.stage != AccessStage.CHECKING) knownOwner = access.account?.professionalProfileId
            }
            val owner = if (access.stage == AccessStage.CHECKING) knownOwner else access.account?.professionalProfileId
            val changeLanguage: (String) -> Unit = { tag ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
            BackHandler(enabled = account || (chatVisible && access.stage != AccessStage.CONNECTED)) {
                account = false; chatVisible = false; contactProfile = null; conversationJson = null
            }
            if (chatVisible && access.stage == AccessStage.CONNECTED && app.chat != null) {
                key(access.account!!.userId) {
                    ChatRoute(app.chat!!, access.account!!.userId, contactProfile,
                        conversationJson?.let { Json.decodeFromString<Conversation>(it) },
                        onOpened = { contactProfile = null; conversationJson = Json.encodeToString(it) },
                        onBack = {
                            if (conversationJson != null && ownProfile == null) conversationJson = null
                            else { chatVisible = false; contactProfile = null; conversationJson = null }
                        })
                }
            } else if (account || chatVisible) {
                AccessScreen(app.identity, changeLanguage,
                    onCatalogue = { account = false; chatVisible = false; contactProfile = null; conversationJson = null },
                    onProfile = { ownProfile = it; account = false; chatVisible = false; conversationJson = null },
                    operationScope = app.applicationScope)
            } else {
                CatalogueScreen(app.catalogue, onAccount = { ownProfile = null; account = true }, onLanguageChange = changeLanguage,
                    initialProfileId = ownProfile, covers = app.covers, ownedProfileId = owner,
                    onContact = { ownProfile = it; contactProfile = it; conversationJson = null; chatVisible = true; account = false },
                    onChats = { ownProfile = null; contactProfile = null; conversationJson = null; chatVisible = true; account = false })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as TeloarregloApplication
        app.applicationScope.launch { app.identity.restore() }
    }
}
