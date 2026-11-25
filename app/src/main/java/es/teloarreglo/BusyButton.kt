package es.teloarreglo

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** A full-width primary action that shows a spinner instead of its label while the action runs (#47). */
@Composable
internal fun BusyButton(busy: Boolean, label: Int, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(enabled = enabled && !busy, onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = LocalContentColor.current)
        else Text(stringResource(label))
    }
}
