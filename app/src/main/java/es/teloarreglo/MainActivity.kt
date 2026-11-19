package es.teloarreglo

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AccessScreen(
                identity = (application as TeloarregloApplication).identity,
                onLanguageChange = { tag ->
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { (application as TeloarregloApplication).identity.restore() }
    }
}
