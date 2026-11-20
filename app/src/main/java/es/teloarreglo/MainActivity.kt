package es.teloarreglo

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var account by rememberSaveable { mutableStateOf(false) }
            var ownProfile by rememberSaveable { mutableStateOf<String?>(null) }
            val app = application as TeloarregloApplication
            val changeLanguage: (String) -> Unit = { tag ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
            BackHandler(enabled = account) { account = false }
            if (account) {
                AccessScreen(app.identity, changeLanguage,
                    onCatalogue = { account = false },
                    onProfile = { ownProfile = it; account = false })
            } else {
                CatalogueScreen(app.catalogue, onAccount = { ownProfile = null; account = true }, onLanguageChange = changeLanguage,
                    initialProfileId = ownProfile)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { (application as TeloarregloApplication).identity.restore() }
    }
}
