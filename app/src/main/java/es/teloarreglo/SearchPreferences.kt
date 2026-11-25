package es.teloarreglo

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Search options chosen in Ajustes (#61) and kept on the device: whether the results show the map above the list. */
class SearchPreferences(private val store: SharedPreferences) {
    var showMap by mutableStateOf(store.getBoolean(SHOW_MAP, false))
        private set

    fun showMap(on: Boolean) {
        showMap = on
        store.edit().putBoolean(SHOW_MAP, on).apply()
    }

    private companion object { const val SHOW_MAP = "show_map" }
}
