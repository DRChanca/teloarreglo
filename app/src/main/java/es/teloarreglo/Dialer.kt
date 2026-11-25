package es.teloarreglo

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * `tel:` URI for [phone] as typed by a person: keeps the digits and a leading `+`, drops spaces, dashes, dots and
 * brackets. Null when there is nothing to dial.
 */
fun dialUri(phone: String): String? {
    val trimmed = phone.trim()
    val digits = trimmed.filter { it in '0'..'9' }
    if (digits.isEmpty()) return null
    return "tel:" + (if (trimmed.startsWith('+')) "+" else "") + digits
}

/** Opens the dialer with [phone] filled in (ACTION_DIAL: never calls, needs no permission). False when it could not. */
fun openDialer(context: Context, phone: String): Boolean = dialUri(phone)?.let { openDialUri(context, it) } ?: false

/** Opens the dialer on a `tel:` [uri] from [dialUri]. False when the device has no dialer. */
fun openDialUri(context: Context, uri: String): Boolean {
    return try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(uri)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** «Llamar» button showing [phone]; opens the dialer and explains when the device has none. */
@Composable
fun CallButton(phone: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    val description = stringResource(R.string.call_description, phone)
    Column(modifier) {
        OutlinedButton(
            onClick = { failed = !openDialer(context, phone) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("call_button")
                .semantics { contentDescription = description },
        ) {
            Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.call_button))
            Spacer(Modifier.width(8.dp))
            Text(phone)
        }
        if (failed) Text(stringResource(R.string.call_no_app), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("call_no_app"))
    }
}
