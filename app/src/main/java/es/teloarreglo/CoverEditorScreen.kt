package es.teloarreglo

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import android.net.Uri
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.teloarreglo.catalogue.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The owner changes the cover of their own profile. Since 2026-11-23 it is published as soon as the
 * server confirms the upload, without review; an unapproved profile shows it once it is published.
 */
@Composable
internal fun CoverEditorRoute(profile: PublicProfile, covers: ProfessionalCovers, onBack: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<CoverPhoto?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    var selectedUri by rememberSaveable(profile.id) { mutableStateOf<String?>(null) }
    var selectionId by rememberSaveable(profile.id) { mutableStateOf(UUID.randomUUID().toString()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            selected = null
            preparing = true
            invalid = false
            selectedUri = uri.toString()
            selectionId = UUID.randomUUID().toString()
        }
    }
    LaunchedEffect(selectedUri, selectionId) {
        selectedUri?.let { uri ->
            preparing = true
            invalid = false
            try { selected = prepareCoverPhoto(context.contentResolver, Uri.parse(uri)).copy(requestId = selectionId) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                invalid = true
            } finally { preparing = false }
        }
    }
    CoverEditorScreen(profile, covers, onBack, selected, preparing, invalid) {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

@Composable
internal fun CoverEditorScreen(
    profile: PublicProfile, covers: ProfessionalCovers, onBack: () -> Unit,
    selected: CoverPhoto? = null, preparing: Boolean = false, invalid: Boolean = false,
    onPickPhoto: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var request by remember(profile.id) { mutableStateOf<CoverRequest?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var leave by remember { mutableStateOf(false) }
    var confirmGeneric by remember { mutableStateOf(false) }
    var genericId by rememberSaveable(profile.id) { mutableStateOf<String?>(null) }
    var discardedSelection by rememberSaveable(profile.id) { mutableStateOf<String?>(null) }
    val unsent = selected != null && request?.id != selected.requestId && discardedSelection != selected.requestId
    val goBack = { if (unsent || busy || preparing) leave = true else onBack() }
    BackHandler(onBack = goBack)
    LaunchedEffect(profile.id, covers, refresh) {
        loaded = false
        failed = false
        try { request = covers.state(profile.id); loaded = true }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            failed = true
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(TeloTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.large),
        ) {
            TextButton(onClick = goBack, modifier = Modifier.testTag("cover_back")) {
                Text(stringResource(R.string.catalogue_back))
            }
            Text(stringResource(R.string.cover_edit), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() })
            Text(profile.name, style = MaterialTheme.typography.titleLarge)
            // The same crops the catalogue uses, so what is previewed is what visitors will see (#47, historia 16).
            val trade = profile.trades.firstOrNull().orEmpty()
            val shownPath = if (unsent) null else if (request != null) request?.objectPath else profile.coverPath
            val preview = if (unsent) selected?.jpeg else null
            Text(stringResource(R.string.cover_preview_detail), style = MaterialTheme.typography.titleMedium)
            ProfileCover(trade, shownPath, covers, Modifier.fillMaxWidth().height(CoverCrop.DETAIL_HEIGHT)
                .testTag("cover_preview_detail"), preview = preview)
            Text(stringResource(R.string.cover_preview_list), style = MaterialTheme.typography.titleMedium)
            ProfileCover(trade, shownPath, covers, Modifier.width(CoverCrop.LIST_WIDTH).height(CoverCrop.LIST_HEIGHT)
                .testTag("cover_preview_list"), preview = preview)
            Text(stringResource(R.string.cover_review_hint))
            Text(stringResource(R.string.cover_limits), style = MaterialTheme.typography.bodySmall)
            if ((!loaded && !failed) || busy || preparing) {
                Row(horizontalArrangement = Arrangement.spacedBy(TeloTheme.spacing.medium)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(stringResource(if (preparing) R.string.cover_preparing else R.string.working))
                }
            }
            if (unsent) Text(stringResource(R.string.cover_unsent), Modifier.testTag("cover_unsent")
                .semantics { liveRegion = LiveRegionMode.Polite })
            if (request != null && loaded && !unsent) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(TeloTheme.spacing.large).testTag("cover_state")
                        .semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(stringResource(when (request?.status) {
                            CoverStatus.PUBLISHED -> R.string.cover_published
                            CoverStatus.PENDING -> R.string.cover_pending
                            CoverStatus.APPROVED -> R.string.cover_approved
                            CoverStatus.REJECTED -> R.string.cover_rejected
                            else -> R.string.cover_review_hint
                        }))
                        request?.reason?.let { Text(it) }
                    }
                }
            }
            if (invalid) Text(stringResource(R.string.cover_invalid), color = MaterialTheme.colorScheme.error)
            if (failed) {
                Text(stringResource(R.string.cover_error), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("cover_error").semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (failed || request != null) {
                OutlinedButton(onClick = { refresh++ }, enabled = !busy && !preparing) {
                    Text(stringResource(R.string.cover_check))
                }
            }
            OutlinedButton(onClick = { genericId = null; onPickPhoto() },
                enabled = !busy && !preparing, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cover_choose))
            }
            Button(
                enabled = loaded && unsent && !busy && !preparing && !invalid,
                modifier = Modifier.fillMaxWidth().testTag("cover_submit"),
                onClick = {
                    genericId = null
                    selected?.let { photo -> scope.launch {
                        busy = true; failed = false
                        try { request = covers.photo(profile.id, photo.requestId, photo.jpeg) }
                        catch (error: Exception) {
                            if (error is CancellationException) throw error
                            failed = true
                        } finally { busy = false }
                    } }
                },
            ) { Text(stringResource(R.string.cover_submit)) }
            TextButton(modifier = Modifier.testTag("cover_generic"), enabled = loaded && !busy && !preparing &&
                !(request?.status in setOf(CoverStatus.PENDING, CoverStatus.PUBLISHED) && request?.objectPath == null && !unsent),
                onClick = { confirmGeneric = true }) { Text(stringResource(R.string.cover_use_generic)) }
        }
        // Publishing the trade image replaces the visible cover at once, so it is confirmed first (#47).
        if (confirmGeneric) DestructiveConfirmDialog(
            title = stringResource(R.string.cover_generic_confirm_title),
            text = { Text(stringResource(R.string.cover_generic_confirm_text)) },
            confirmLabel = stringResource(R.string.cover_generic_confirm),
            confirmTag = "cover_generic_confirm", dismissTag = "cover_generic_dismiss",
            onDismiss = { confirmGeneric = false },
            onConfirm = {
                confirmGeneric = false
                val id = genericId ?: UUID.randomUUID().toString().also { genericId = it }
                scope.launch {
                    busy = true; failed = false
                    try {
                        request = covers.generic(profile.id, id)
                        discardedSelection = selected?.requestId
                        genericId = null
                    }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        failed = true
                    } finally { busy = false }
                }
            },
        )
        if (leave) AlertDialog(
            onDismissRequest = { leave = false },
            title = { Text(stringResource(R.string.cover_leave_title)) },
            text = { Text(stringResource(R.string.cover_leave_hint)) },
            confirmButton = { TextButton(onClick = onBack) { Text(stringResource(R.string.cover_leave)) } },
            dismissButton = { TextButton(onClick = { leave = false }) { Text(stringResource(R.string.cover_stay)) } },
        )
    }
}
