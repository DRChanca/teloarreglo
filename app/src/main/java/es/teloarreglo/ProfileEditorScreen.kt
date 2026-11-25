package es.teloarreglo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.teloarreglo.appointments.Agenda
import es.teloarreglo.appointments.parseEuroCents
import es.teloarreglo.catalogue.AgendaDay
import es.teloarreglo.catalogue.AgendaDraft
import es.teloarreglo.catalogue.Coverage
import es.teloarreglo.catalogue.MAX_PRICES
import es.teloarreglo.catalogue.OwnProfile
import es.teloarreglo.catalogue.PRICE_UNITS
import es.teloarreglo.catalogue.PriceDraft
import es.teloarreglo.catalogue.ProfessionalCovers
import es.teloarreglo.catalogue.PublicProfile
import es.teloarreglo.catalogue.ReferencePrice
import es.teloarreglo.catalogue.parseServices
import es.teloarreglo.catalogue.toDraft
import es.teloarreglo.catalogue.toPrices
import kotlinx.serialization.builtins.ListSerializer
import es.teloarreglo.catalogue.ProfessionalProfiles
import es.teloarreglo.catalogue.ProfileContent
import es.teloarreglo.catalogue.ProfileEditException
import es.teloarreglo.catalogue.ProfileEditFailure
import es.teloarreglo.catalogue.ProfileField
import es.teloarreglo.catalogue.ReviewSummary
import es.teloarreglo.catalogue.missingFields
import es.teloarreglo.catalogue.summary
import es.teloarreglo.search.ConcejoAnchor
import es.teloarreglo.search.ConcejoDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.UUID

private val TRADES = listOf("plumbing", "electrical", "carpentry", "painting", "masonry", "cleaning", "general")
private val LANGUAGES = listOf("es", "en", "fr", "de", "pt", "it")

/** Pending presentation: the request id is reused only while the same content is retried. */
@kotlinx.serialization.Serializable
private data class PendingSubmission(val requestId: String, val content: ProfileContent)

private enum class EditorSection { REVIEW, OFFER, ADDRESS }

/**
 * The owner creates, corrects or changes their professional profile (#30). Public data is presented
 * for review; until administration approves it, the catalogue keeps the approved version. Services,
 * reference prices and the cover are published directly, without review (David, 2026-11-23); an
 * unapproved profile shows them once it is published. The private address is saved apart and is
 * never reviewed or published. The weekly agenda is filled in here and sent with the presentation (#41),
 * so a new professional has hours before approval; it needs no review and can later be changed, with
 * blocks, from the account screen.
 */
@Composable
fun ProfileEditorScreen(
    profiles: ProfessionalProfiles,
    concejos: ConcejoDirectory?,
    scope: CoroutineScope,
    onBack: () -> Unit,
    onStarted: suspend () -> Unit,
    covers: ProfessionalCovers? = null,
    agenda: Agenda? = null,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var own by remember { mutableStateOf<OwnProfile?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var draftJson by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingJson by rememberSaveable { mutableStateOf<String?>(null) }
    var privateAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Int?>(null) }
    var failure by remember { mutableStateOf<Int?>(null) }
    var showMissing by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(EditorSection.REVIEW) }
    var servicesText by rememberSaveable { mutableStateOf<String?>(null) }
    var pricesJson by rememberSaveable { mutableStateOf<String?>(null) }
    var editingCover by rememberSaveable { mutableStateOf(false) }
    var agendaJson by rememberSaveable { mutableStateOf<String?>(null) }
    var agendaFailed by remember { mutableStateOf(false) }
    var agendaAttempt by remember { mutableIntStateOf(0) }
    var showAgendaProblem by remember { mutableStateOf(false) }

    // The saved agenda is where the form starts; until it loads, nothing would replace it by mistake.
    LaunchedEffect(agenda, agendaAttempt, own != null) {
        if (agenda == null || own == null || agendaJson != null) return@LaunchedEffect
        agendaFailed = false
        try {
            val saved = agenda.load()
            agendaJson = Json.encodeToString(AgendaDraft.serializer(), AgendaDraft.from(saved.defaultDurationMinutes, saved.windows))
        } catch (e: CancellationException) { throw e } catch (_: Exception) { agendaFailed = true }
    }

    LaunchedEffect(attempt) {
        loadFailed = false
        try {
            val result = profiles.mine()
            own = result; loaded = true
            if (result != null) {
                if (draftJson == null) draftJson = Json.encodeToString(ProfileContent.serializer(), result.editable)
                if (privateAddress == null) privateAddress = result.privateAddress.orEmpty()
                if (servicesText == null) servicesText = result.editable.services.joinToString("\n")
                if (pricesJson == null) pricesJson = Json.encodeToString(PRICE_DRAFTS, result.editable.prices.map { it.toDraft() })
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { loadFailed = true }
    }
    val current = own
    if (editingCover && covers != null && current != null) {
        val content = draftJson?.let { Json.decodeFromString(ProfileContent.serializer(), it) } ?: current.editable
        CoverEditorRoute(content.asCoverTarget(current.profileId), covers, onBack = { editingCover = false })
        return
    }
    val draft = draftJson?.let { Json.decodeFromString(ProfileContent.serializer(), it) }
    fun edit(change: (ProfileContent) -> ProfileContent) {
        draft?.let { draftJson = Json.encodeToString(ProfileContent.serializer(), change(it)) }
        outcome = null
    }
    fun perform(at: EditorSection, action: suspend () -> Int) {
        section = at
        busy = true; outcome = null; failure = null
        scope.launch {
            try { outcome = action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                failure = when ((e as? ProfileEditException)?.failure) {
                    ProfileEditFailure.INVALID -> R.string.profile_edit_error_invalid
                    ProfileEditFailure.INVALID_AGENDA -> R.string.profile_edit_error_agenda
                    ProfileEditFailure.CONFLICT -> R.string.profile_edit_error_conflict
                    ProfileEditFailure.ACCESS, ProfileEditFailure.NO_PROFILE -> R.string.profile_edit_error_access
                    null -> R.string.profile_edit_error_network
                }
            } finally { busy = false }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp).testTag("profile_editor"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack, Modifier.testTag("profile_edit_back")) { Text(stringResource(R.string.catalogue_back)) }
            Text(stringResource(R.string.profile_edit_title), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            when {
                loadFailed -> {
                    Text(stringResource(R.string.profile_edit_load_error), color = MaterialTheme.colorScheme.error)
                    Button(onClick = { attempt++ }, Modifier.testTag("profile_edit_retry")) { Text(stringResource(R.string.retry)) }
                }
                !loaded -> LinearProgressIndicator(Modifier.fillMaxWidth())
                current == null -> StartProfile(busy) {
                    perform(EditorSection.REVIEW) { profiles.start(); onStarted(); attempt++; R.string.profile_edit_started }
                }
                draft != null -> {
                    ReviewState(current)
                    if (covers != null) {
                        OutlinedButton(onClick = { editingCover = true }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().testTag("profile_edit_cover")) {
                            Text(stringResource(R.string.profile_edit_cover))
                        }
                    }
                    ContentForm(draft, !busy, concejos, ::edit, showMissing)
                    val missing = draft.missingFields()
                    if (showMissing && missing.isNotEmpty()) {
                        Text(stringResource(R.string.profile_edit_missing), color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("profile_edit_missing"))
                    }
                    val agendaDraft = agendaJson?.let { Json.decodeFromString(AgendaDraft.serializer(), it) }
                    if (agenda != null) AgendaSection(agendaDraft, agendaFailed, !busy, showAgendaProblem,
                        onRetry = { agendaAttempt++ },
                        onChange = { agendaJson = Json.encodeToString(AgendaDraft.serializer(), it); outcome = null })
                    Text(stringResource(if (current.review.published) R.string.profile_edit_submit_hint_published
                        else R.string.profile_edit_submit_hint_first), style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("profile_edit_submit"), onClick = {
                        showMissing = true
                        // A first presentation carries the agenda (#41); later ones send it if it is complete.
                        val weekly = agendaDraft?.toAgenda()
                        showAgendaProblem = agenda != null && weekly == null && !current.review.published
                        if (missing.isNotEmpty() || showAgendaProblem) return@Button
                        val previous = pendingJson?.let { Json.decodeFromString(PendingSubmission.serializer(), it) }
                        val pending = previous?.takeIf { it.content == draft } ?: PendingSubmission(UUID.randomUUID().toString(), draft)
                        pendingJson = Json.encodeToString(PendingSubmission.serializer(), pending)
                        perform(EditorSection.REVIEW) {
                            profiles.submit(pending.requestId, pending.content, weekly)
                            pendingJson = null; attempt++
                            R.string.profile_edit_submitted
                        }
                    }) { Text(stringResource(if (busy) R.string.working else R.string.profile_edit_submit)) }
                    if (section == EditorSection.REVIEW) Status(busy, outcome, failure)
                    HorizontalDivider()
                    val drafts = pricesJson?.let { Json.decodeFromString(PRICE_DRAFTS, it) } ?: emptyList()
                    OfferSection(servicesText.orEmpty(), drafts, current.review.published, !busy,
                        onServices = { servicesText = it; outcome = null },
                        onPrices = { pricesJson = Json.encodeToString(PRICE_DRAFTS, it); outcome = null },
                        onSave = { prices ->
                            perform(EditorSection.OFFER) {
                                own = profiles.saveOffer(parseServices(servicesText.orEmpty()), prices)
                                R.string.profile_offer_saved
                            }
                        })
                    if (section == EditorSection.OFFER) Status(busy, outcome, failure)
                    HorizontalDivider()
                    PrivateAddress(privateAddress.orEmpty(), !busy, { privateAddress = it; outcome = null }) {
                        perform(EditorSection.ADDRESS) { profiles.savePrivateAddress(privateAddress); R.string.profile_edit_private_saved }
                    }
                    if (section == EditorSection.ADDRESS) Status(busy, outcome, failure)
                    Text(stringResource(R.string.profile_edit_agenda_note), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (current == null) Status(busy, outcome, failure)
        }
    }
}

/** Weekly hours and usual duration, sent with the presentation (#41). */
@Composable
private fun AgendaSection(
    draft: AgendaDraft?, failed: Boolean, enabled: Boolean, showProblem: Boolean,
    onRetry: () -> Unit, onChange: (AgendaDraft) -> Unit,
) {
    HorizontalDivider()
    Text(stringResource(R.string.profile_edit_agenda_title), style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.profile_edit_agenda_hint), style = MaterialTheme.typography.bodySmall)
    if (failed) {
        Text(stringResource(R.string.agenda_load_error), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry, modifier = Modifier.testTag("profile_agenda_retry")) { Text(stringResource(R.string.retry)) }
    }
    if (draft == null) { if (!failed) LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    OutlinedTextField(draft.duration, { onChange(draft.copy(duration = it.filter(Char::isDigit).take(4))) }, enabled = enabled,
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        label = { Text(stringResource(R.string.agenda_duration)) }, modifier = Modifier.fillMaxWidth().testTag("profile_agenda_duration"))
    draft.days.forEachIndexed { index, day ->
        fun change(updated: AgendaDay) = onChange(draft.copy(days = draft.days.toMutableList().also { it[index] = updated }))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Checkbox(day.open, { change(day.copy(open = it)) }, enabled = enabled, modifier = Modifier.testTag("profile_agenda_day_${index + 1}"))
            Text(java.time.DayOfWeek.of(index + 1).getDisplayName(java.time.format.TextStyle.SHORT, locale), Modifier.width(48.dp))
            OutlinedTextField(day.from, { change(day.copy(from = it.take(5))) }, enabled = enabled && day.open, singleLine = true,
                label = { Text(stringResource(R.string.appointment_from)) }, modifier = Modifier.weight(1f).testTag("profile_agenda_from_${index + 1}"))
            OutlinedTextField(day.to, { change(day.copy(to = it.take(5))) }, enabled = enabled && day.open, singleLine = true,
                label = { Text(stringResource(R.string.appointment_to)) }, modifier = Modifier.weight(1f).testTag("profile_agenda_to_${index + 1}"))
        }
    }
    if (showProblem && draft.toAgenda() == null) Text(stringResource(R.string.profile_edit_agenda_missing),
        color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("profile_agenda_missing"))
}

@Composable
private fun Status(busy: Boolean, outcome: Int?, failure: Int?) {
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        outcome?.let { Text(stringResource(it), modifier = Modifier.testTag("profile_edit_outcome")) }
        failure?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("profile_edit_error")) }
    }
}

@Composable
private fun StartProfile(busy: Boolean, onStart: () -> Unit) {
    Text(stringResource(R.string.profile_edit_start_hint))
    Button(onClick = onStart, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("profile_edit_start")) {
        Text(stringResource(if (busy) R.string.working else R.string.profile_edit_start))
    }
}

@Composable
private fun ReviewState(own: OwnProfile) {
    val summary = own.review.summary()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(reviewMessage(summary)), modifier = Modifier.testTag("profile_edit_review"))
            if (summary == ReviewSummary.REJECTED_FIRST || summary == ReviewSummary.REJECTED_CHANGE) {
                // The administrator's reason is content: shown as written, never translated.
                own.review.reason?.let { Text(stringResource(R.string.profile_review_reason, it), fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("profile_edit_reason")) }
                Text(stringResource(R.string.profile_edit_correct_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal fun reviewMessage(summary: ReviewSummary): Int = when (summary) {
    ReviewSummary.NOT_SUBMITTED -> R.string.profile_review_not_submitted
    ReviewSummary.PENDING_FIRST -> R.string.profile_review_pending_first
    ReviewSummary.PENDING_CHANGE -> R.string.profile_review_pending_change
    ReviewSummary.PUBLISHED -> R.string.profile_review_published
    ReviewSummary.REJECTED_FIRST -> R.string.profile_review_rejected_first
    ReviewSummary.REJECTED_CHANGE -> R.string.profile_review_rejected_change
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContentForm(
    draft: ProfileContent, enabled: Boolean, concejos: ConcejoDirectory?,
    edit: ((ProfileContent) -> ProfileContent) -> Unit, showMissing: Boolean,
) {
    val missing = if (showMissing) draft.missingFields() else emptySet()
    OutlinedTextField(draft.name, { v -> edit { it.copy(name = v.take(120)) } }, enabled = enabled, singleLine = true,
        isError = ProfileField.NAME in missing, label = { Text(stringResource(R.string.profile_edit_name)) },
        modifier = Modifier.fillMaxWidth().testTag("profile_edit_name"))
    OutlinedTextField(draft.description, { v -> edit { it.copy(description = v.take(4000)) } }, enabled = enabled,
        minLines = 3, label = { Text(stringResource(R.string.profile_edit_description)) },
        modifier = Modifier.fillMaxWidth().testTag("profile_edit_description"))

    Label(R.string.profile_edit_trades, ProfileField.TRADES in missing)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TRADES.forEach { key ->
            FilterChip(selected = key in draft.trades, enabled = enabled, modifier = Modifier.testTag("profile_edit_trade_$key"),
                onClick = { edit { it.copy(trades = if (key in it.trades) it.trades - key else it.trades + key) } },
                label = { Text(stringResource(tradeLabel(key))) })
        }
    }
    Label(R.string.profile_edit_coverage, ProfileField.COVERAGE in missing)
    CoveragePicker(draft.coverage, enabled, concejos) { coverage -> edit { it.copy(coverage = coverage) } }

    Label(R.string.profile_edit_languages, ProfileField.LANGUAGES in missing)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (LANGUAGES + draft.languages.filter { it !in LANGUAGES }).forEach { code ->
            val name = Locale.forLanguageTag(code).let { it.getDisplayLanguage(it) }.replaceFirstChar { it.titlecase() }
            FilterChip(selected = code in draft.languages, enabled = enabled, modifier = Modifier.testTag("profile_edit_language_$code"),
                onClick = { edit { it.copy(languages = if (code in it.languages) it.languages - code else it.languages + code) } },
                label = { Text(name) })
        }
    }
    OutlinedTextField(draft.phone, { v -> edit { it.copy(phone = v.filter { c -> c.isDigit() || c == ' ' || c == '+' }.take(20)) } },
        enabled = enabled, singleLine = true, isError = ProfileField.PHONE in missing,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        label = { Text(stringResource(R.string.profile_edit_phone)) }, modifier = Modifier.fillMaxWidth().testTag("profile_edit_phone"))

    // Privacy: a business address is published only if the owner chooses to.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.profile_edit_publish_address), Modifier.weight(1f))
        Switch(draft.businessAddress != null, { on -> edit { it.copy(businessAddress = if (on) "" else null) } },
            enabled = enabled, modifier = Modifier.testTag("profile_edit_publish_address"))
    }
    if (draft.businessAddress != null) {
        OutlinedTextField(draft.businessAddress, { v -> edit { it.copy(businessAddress = v.take(240)) } }, enabled = enabled,
            singleLine = true, label = { Text(stringResource(R.string.profile_edit_business_address)) },
            modifier = Modifier.fillMaxWidth().testTag("profile_edit_business_address"))
    }
    Text(stringResource(R.string.profile_edit_business_hint), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Label(text: Int, error: Boolean) = Text(stringResource(text), style = MaterialTheme.typography.titleSmall,
    color = if (error) MaterialTheme.colorScheme.error else Color.Unspecified)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoveragePicker(selected: List<Coverage>, enabled: Boolean, concejos: ConcejoDirectory?, onChange: (List<Coverage>) -> Unit) {
    var anchors by remember { mutableStateOf<List<ConcejoAnchor>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(concejos) {
        try { anchors = concejos?.anchors()?.sortedBy { it.name }; failed = concejos == null }
        catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        selected.forEach { zone ->
            InputChip(selected = true, enabled = enabled, onClick = { onChange(selected - zone) }, label = { Text(zone.name) },
                trailingIcon = { Text("×") }, modifier = Modifier.testTag("profile_edit_zone_${zone.code}"))
        }
    }
    if (failed) Text(stringResource(R.string.profile_edit_coverage_unavailable), color = MaterialTheme.colorScheme.error)
    val all = anchors ?: return
    OutlinedTextField(query, { query = it }, enabled = enabled, singleLine = true,
        label = { Text(stringResource(R.string.profile_edit_coverage_search)) },
        modifier = Modifier.fillMaxWidth().testTag("profile_edit_zone_search"))
    if (query.isNotBlank()) {
        val codes = selected.map { it.code }.toSet()
        all.filter { it.ineCode !in codes && it.name.contains(query.trim(), ignoreCase = true) }.take(5).forEach { anchor ->
            TextButton(enabled = enabled, onClick = { onChange(selected + Coverage(anchor.ineCode, anchor.name)); query = "" },
                modifier = Modifier.testTag("profile_edit_zone_add_${anchor.ineCode}")) {
                Text(stringResource(R.string.profile_edit_coverage_add, anchor.name))
            }
        }
    }
}

private val PRICE_DRAFTS = ListSerializer(PriceDraft.serializer())

/** What the profile editor hands to the cover editor; the cover is shown once the profile is public. */
private fun ProfileContent.asCoverTarget(profileId: String) = PublicProfile(profileId, name, description, trades,
    services, coverage, languages, phone, images, prices, businessAddress = businessAddress)

/** Services and reference prices: published at once, without review (David, 2026-11-23). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfferSection(
    services: String, prices: List<PriceDraft>, published: Boolean, enabled: Boolean,
    onServices: (String) -> Unit, onPrices: (List<PriceDraft>) -> Unit, onSave: (List<ReferencePrice>) -> Unit,
) {
    var showInvalid by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.profile_offer_title), style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.profile_offer_hint), style = MaterialTheme.typography.bodySmall)
    if (!published) Text(stringResource(R.string.profile_offer_unpublished_hint), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(services, { onServices(it) }, enabled = enabled, minLines = 2,
        label = { Text(stringResource(R.string.profile_edit_services)) },
        supportingText = { Text(stringResource(R.string.profile_edit_services_hint)) },
        modifier = Modifier.fillMaxWidth().testTag("profile_edit_services"))
    prices.forEachIndexed { index, price ->
        fun change(update: (PriceDraft) -> PriceDraft) = onPrices(prices.toMutableList().also { it[index] = update(price) })
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp).testTag("profile_offer_price_$index"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.profile_offer_price, index + 1), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(price.service, { v -> change { it.copy(service = v.take(120)) } }, enabled = enabled,
                    singleLine = true, isError = showInvalid && price.service.isBlank(),
                    label = { Text(stringResource(R.string.profile_offer_service)) },
                    modifier = Modifier.fillMaxWidth().testTag("profile_offer_service_$index"))
                OutlinedTextField(price.scope, { v -> change { it.copy(scope = v.take(300)) } }, enabled = enabled,
                    label = { Text(stringResource(R.string.profile_offer_scope)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(price.amount, { v -> change { it.copy(amount = v.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(10)) } },
                    enabled = enabled, singleLine = true, isError = showInvalid && parseEuroCents(price.amount) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text(stringResource(R.string.profile_offer_amount)) },
                    modifier = Modifier.fillMaxWidth().testTag("profile_offer_amount_$index"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRICE_UNITS.forEach { unit ->
                        FilterChip(selected = price.unit == unit, enabled = enabled, onClick = { change { it.copy(unit = unit) } },
                            label = { Text(stringResource(unitLabel(unit))) }, modifier = Modifier.testTag("profile_offer_unit_${index}_$unit"))
                    }
                }
                OutlinedTextField(price.includes, { v -> change { it.copy(includes = v.take(300)) } }, enabled = enabled,
                    label = { Text(stringResource(R.string.profile_offer_includes)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(price.excludes, { v -> change { it.copy(excludes = v.take(300)) } }, enabled = enabled,
                    label = { Text(stringResource(R.string.profile_offer_excludes)) }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { onPrices(prices.filterIndexed { i, _ -> i != index }) }, enabled = enabled,
                    modifier = Modifier.testTag("profile_offer_remove_$index")) { Text(stringResource(R.string.profile_offer_remove_price)) }
            }
        }
    }
    if (prices.size < MAX_PRICES) {
        OutlinedButton(onClick = { onPrices(prices + PriceDraft()) }, enabled = enabled,
            modifier = Modifier.testTag("profile_offer_add")) { Text(stringResource(R.string.profile_offer_add_price)) }
    }
    val valid = prices.toPrices()
    if (showInvalid && valid == null) {
        Text(stringResource(R.string.profile_offer_invalid), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("profile_offer_invalid"))
    }
    Button(enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("profile_offer_save"), onClick = {
        showInvalid = valid == null
        valid?.let(onSave)
    }) { Text(stringResource(if (enabled) R.string.profile_offer_save else R.string.working)) }
}

@Composable
private fun PrivateAddress(value: String, enabled: Boolean, onChange: (String) -> Unit, onSave: () -> Unit) {
    Text(stringResource(R.string.profile_edit_private_title), style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(value, { onChange(it.take(240)) }, enabled = enabled, singleLine = true,
        label = { Text(stringResource(R.string.profile_edit_private_address)) },
        supportingText = { Text(stringResource(R.string.profile_edit_private_hint)) },
        modifier = Modifier.fillMaxWidth().testTag("profile_edit_private_address"))
    OutlinedButton(onClick = onSave, enabled = enabled, modifier = Modifier.testTag("profile_edit_private_save")) {
        Text(stringResource(R.string.profile_edit_private_save))
    }
}
