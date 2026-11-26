package es.teloarreglo

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle

/**
 * Sentence for a «Tiempo de respuesta» band from the server (#76); null, so nothing is shown, without data
 * or for a band this version of the app does not know.
 */
@StringRes
fun responseBandLabel(band: String?): Int? = when (band) {
    "instant" -> R.string.response_band_instant
    "under_30m" -> R.string.response_band_under_30m
    "under_1h" -> R.string.response_band_under_1h
    "under_3h" -> R.string.response_band_under_3h
    "same_day" -> R.string.response_band_same_day
    "over_day" -> R.string.response_band_over_day
    else -> null
}

/** A profile's «Tiempo de respuesta», shown next to its Valoración; nothing when [band] has no sentence. */
@Composable
fun ResponseTime(band: String?, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    val label = responseBandLabel(band) ?: return
    Text(stringResource(label), modifier, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
