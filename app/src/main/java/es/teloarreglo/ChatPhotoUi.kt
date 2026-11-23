package es.teloarreglo

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import es.teloarreglo.chat.*
import kotlinx.coroutines.CancellationException

/** Attach button plus compression/upload progress, cancellation and explained failures. */
@Composable
internal fun ChatPhotoControls(
    attachments: ChatAttachments, accountId: String, conversationId: String, enabled: Boolean,
    onSent: (ChatMessage) -> Unit,
) {
    val context = LocalContext.current
    val sender = viewModel(key = "chat-photo/$accountId/$conversationId") { ChatPhotoSender(attachments, conversationId) }
    val state by sender.state.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) sender.pick { preparePhoto(context.contentResolver, uri) }
    }
    LaunchedEffect(state) {
        (state as? PhotoSendState.Sent)?.let { onSent(it.message); sender.dismiss() }
    }
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        when (val current = state) {
            PhotoSendState.Preparing, is PhotoSendState.Uploading -> {
                val fraction = (current as? PhotoSendState.Uploading)?.fraction
                Text(if (fraction == null) stringResource(R.string.photo_preparing)
                    else stringResource(R.string.photo_uploading, (fraction * 100).toInt()),
                    Modifier.testTag("photo_progress"))
                if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = sender::cancel, modifier = Modifier.testTag("photo_cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            }
            is PhotoSendState.Failed -> {
                Text(stringResource(when (current.failure) {
                    AttachmentFailure.UNSUPPORTED -> R.string.photo_error_unsupported
                    AttachmentFailure.TOO_LARGE -> R.string.photo_error_too_large
                    AttachmentFailure.QUOTA -> R.string.photo_error_quota
                    AttachmentFailure.ACCESS -> R.string.photo_error_access
                    AttachmentFailure.CONFLICT -> R.string.photo_error_conflict
                    null -> R.string.photo_error_network
                }), Modifier.testTag("photo_error"), color = MaterialTheme.colorScheme.error)
                Row {
                    if (sender.retryable) TextButton(onClick = sender::retry, modifier = Modifier.testTag("photo_retry")) {
                        Text(stringResource(R.string.retry))
                    }
                    TextButton(onClick = sender::cancel, modifier = Modifier.testTag("photo_discard")) {
                        Text(stringResource(R.string.photo_discard))
                    }
                }
            }
            else -> {}
        }
        if (state == PhotoSendState.Idle || (state is PhotoSendState.Failed && !sender.retryable)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = enabled, modifier = Modifier.testTag("photo_attach"), onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(stringResource(R.string.photo_attach)) }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.photo_limits), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Opens a confirmed private photo through a short signed URL; tap shows it full screen. */
@Composable
internal fun ChatPhotoBubble(path: String, attachments: ChatAttachments) {
    var url by remember(path) { mutableStateOf<String?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    var retry by remember(path) { mutableIntStateOf(0) }
    var open by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path, retry) {
        failed = false
        try { url = attachments.url(path) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            failed = true
        }
    }
    val context = LocalContext.current
    val description = stringResource(R.string.photo_message)
    val model = url?.let { ImageRequest.Builder(context).data(it).memoryCacheKey(path).diskCacheKey(path).build() }
    if (failed || model == null) {
        Box(Modifier.size(220.dp, 160.dp).background(MaterialTheme.colorScheme.surface).testTag("chat_photo"),
            contentAlignment = Alignment.Center) {
            if (failed) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.photo_open_error)) }
            else CircularProgressIndicator()
        }
    } else {
        SubcomposeAsyncImage(model = model, contentDescription = description, contentScale = ContentScale.Crop,
            modifier = Modifier.size(220.dp, 160.dp).clickable { open = true }.testTag("chat_photo"),
            loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
            error = { TextButton(onClick = { url = null; retry++ }) { Text(stringResource(R.string.photo_open_error)) } })
    }
    if (open && model != null) Dialog(onDismissRequest = { open = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("chat_photo_viewer")) {
            SubcomposeAsyncImage(model = model, contentDescription = description, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
                error = { Text(stringResource(R.string.photo_open_error), color = Color.White) })
            TextButton(onClick = { open = false }, modifier = Modifier.align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
                Text(stringResource(R.string.photo_close), color = Color.White)
            }
        }
    }
}
