package es.teloarreglo

import es.teloarreglo.search.publicLocation
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import es.teloarreglo.catalogue.Catalogue
import es.teloarreglo.catalogue.PublicProfile
import es.teloarreglo.catalogue.ProfessionalCovers
import es.teloarreglo.catalogue.ReferencePrice
import kotlinx.coroutines.CancellationException
import java.text.NumberFormat
import java.util.Locale
import androidx.compose.ui.platform.LocalConfiguration

/** Trade keys offered in the catalogue and nearby search; must match the DB constraint and Edge Function. */
internal val catalogueTrades = listOf("plumbing", "electrical", "carpentry", "painting", "masonry", "general", "cleaning")

@Composable
fun CatalogueScreen(
    catalogue: Catalogue,
    initialProfileId: String? = null,
    covers: ProfessionalCovers? = null,
    ownedProfileId: String? = null,
    onContact: ((String) -> Unit)? = null,
    /** Published reviews shown on the profile (#28); null hides them. */
    reviews: es.teloarreglo.appointments.Reviews? = null,
) {
    var trade by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable(initialProfileId) { mutableStateOf(initialProfileId) }
    var retry by remember { mutableIntStateOf(0) }
    var profiles by remember { mutableStateOf<List<PublicProfile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val tradeScroll = rememberScrollState()
    var previousTrade by rememberSaveable { mutableStateOf(trade) }
    var previousSearch by rememberSaveable { mutableStateOf(search) }
    val keyboard = LocalSoftwareKeyboardController.current
    val clearSearchLabel = stringResource(R.string.catalogue_clear_search)
    LaunchedEffect(trade) {
        if (trade != previousTrade) { listState.scrollToItem(0); previousTrade = trade }
    }
    LaunchedEffect(search) {
        if (search != previousSearch) { listState.scrollToItem(0); previousSearch = search }
    }
    val request = Triple(trade, selected, retry)
    var completedRequest by remember { mutableStateOf<Triple<String?, String?, Int>?>(null) }
    LaunchedEffect(catalogue, trade, selected, retry) {
        loading = true
        failed = false
        profiles = emptyList()
        try {
            profiles = selected?.let { id -> listOfNotNull(catalogue.profile(id)) } ?: catalogue.list(trade)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failed = true
        } finally { loading = false }
        completedRequest = request
    }
    BackHandler(enabled = selected != null) { selected = null }
    val profile = profiles.singleOrNull()?.takeIf { selected == it.id && completedRequest == request }
    if (editing && profile != null && profile.id == ownedProfileId && covers != null) {
        CoverEditorRoute(profile, covers, onBack = { editing = false; retry++ })
        return
    }
    val visible = profiles.filter { p ->
        searchable(p.name + " " + p.services.joinToString(" ")).contains(searchable(search.trim()))
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            // Sections live in the bottom bar (#46); the header keeps the brand or the way back.
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected != null) {
                    TextButton(onClick = { selected = null }, Modifier.testTag("back_catalogue")) {
                        Text(stringResource(R.string.catalogue_back))
                    }
                } else {
                    Text("teloarreglo", color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                }
            }
            if (selected == null) {
                Text(
                    stringResource(R.string.catalogue_title), Modifier.padding(20.dp),
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                )
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    label = { Text(stringResource(R.string.catalogue_search)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("catalogue_search"),
                    trailingIcon = {
                        if (search.isNotEmpty()) IconButton(onClick = { search = "" },
                            modifier = Modifier.semantics { contentDescription = clearSearchLabel }) {
                            Text("×")
                        }
                    },
                )
                Row(
                    Modifier.horizontalScroll(tradeScroll).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = trade == null, onClick = { trade = null },
                        label = { Text(stringResource(R.string.trade_all)) })
                    catalogueTrades.forEach { key ->
                        FilterChip(selected = trade == key, onClick = { trade = key },
                            modifier = Modifier.testTag("trade_$key"), label = { Text(stringResource(tradeLabel(key))) })
                    }
                }
            }
            when {
                loading || completedRequest != request -> Column(Modifier.padding(24.dp).testTag("catalogue_loading")) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.catalogue_loading))
                }
                failed -> Column(Modifier.padding(24.dp)) {
                    Text(stringResource(R.string.catalogue_error), Modifier.testTag("catalogue_error"))
                    Button(onClick = { retry++ }, Modifier.testTag("catalogue_retry")) {
                        Text(stringResource(R.string.retry))
                    }
                }
                profiles.isEmpty() || (selected == null && visible.isEmpty()) -> Column(Modifier.padding(24.dp)) {
                    Text(stringResource(when {
                        selected != null -> R.string.catalogue_unpublished
                        profiles.isNotEmpty() -> R.string.catalogue_filtered_empty
                        else -> R.string.catalogue_empty
                    }), Modifier.testTag("catalogue_empty"))
                    if (selected == null && (trade != null || search.isNotEmpty())) {
                        TextButton(onClick = { trade = null; search = ""; keyboard?.hide() }, Modifier.testTag("catalogue_reset")) {
                            Text(stringResource(R.string.catalogue_reset_filters))
                        }
                    }
                }
                selected != null -> ProfileDetail(profiles.single(), covers, catalogue,
                    onEdit = if (selected == ownedProfileId && covers != null) ({ editing = true }) else null,
                    onContact = if (selected != ownedProfileId) onContact?.let { contact -> { contact(profiles.single().id) } } else null,
                    reviews = reviews)
                else -> {
                    Text(pluralStringResource(R.plurals.catalogue_results, visible.size, visible.size),
                        Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge)
                    LazyColumn(
                        state = listState, modifier = Modifier.testTag("catalogue_list"),
                        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                    items(visible, key = { it.id }) { profile ->
                        // «Perfil destacado» (2026-11-24): the server lists these first; the card stands out
                        // with the theme's secondary container and primary outline, and always says why.
                        Card(
                            onClick = { selected = profile.id }, modifier = Modifier.fillMaxWidth().testTag("profile_${profile.id}"),
                            colors = CardDefaults.cardColors(containerColor =
                                if (profile.featured) MaterialTheme.colorScheme.secondaryContainer else TeloTheme.extended.card),
                            border = if (profile.featured) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                ProfileCover(profile.trades.firstOrNull().orEmpty(), profile.coverPath, covers,
                                    Modifier.width(100.dp).height(120.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    if (profile.featured) FeaturedBadge(Modifier.testTag("catalogue_featured_${profile.id}"),
                                        R.string.catalogue_featured_profile)
                                    Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(profile.services.firstOrNull() ?: profile.trades.map { stringResource(tradeLabel(it)) }.joinToString(),
                                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(profile.coverage.joinToString { it.name }, style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    profile.prices.firstOrNull()?.let { price ->
                                        Text(stringResource(R.string.catalogue_reference, priceText(price)),
                                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                    }
                                    Rating(profile)
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileDetail(
    profile: PublicProfile, covers: ProfessionalCovers?, catalogue: Catalogue,
    onEdit: (() -> Unit)?, onContact: (() -> Unit)?, reviews: es.teloarreglo.appointments.Reviews? = null,
) {
    val locale = LocalConfiguration.current.locales[0]
    // «Destacado» (#33) from the server rule; a failed check shows no badge rather than a false one.
    val featured by produceState(false, profile.id) {
        value = try { catalogue.featured(profile.id) } catch (error: Exception) {
            if (error is CancellationException) throw error
            false
        }
    }
    LazyColumn(
        modifier = Modifier.testTag("profile_detail"),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            if (featured) FeaturedBadge(Modifier.testTag("profile_featured"))
            Text(profile.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(profile.trades.map { stringResource(tradeLabel(it)) }.joinToString(), color = MaterialTheme.colorScheme.primary)
            Rating(profile)
            if (onContact != null) Button(onClick = onContact, modifier = Modifier.fillMaxWidth().testTag("chat_contact")) {
                Text(stringResource(R.string.chat_contact))
            }
        }
        item {
            ProfileCover(profile.trades.firstOrNull().orEmpty(), profile.coverPath, covers, Modifier.fillMaxWidth().height(180.dp))
            if (onEdit != null) OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().testTag("edit_cover")) {
                Text(stringResource(R.string.cover_edit))
            }
        }
        item { Text(profile.description, style = MaterialTheme.typography.bodyLarge) }
        profile.businessAddress?.takeIf { it.isNotBlank() }?.let { address ->
            item {
                Section(R.string.catalogue_address)
                androidx.compose.foundation.text.selection.SelectionContainer { Text(address) }
                profile.publicLocation()?.let { point -> MapDirectionsButton(point) }
            }
        }
        item {
            Section(R.string.catalogue_services)
            profile.services.forEach { Text("• $it") }
        }
        item {
            Section(R.string.catalogue_coverage)
            Text(profile.coverage.joinToString { it.name })
        }
        if (profile.languages.any { Locale.forLanguageTag(it).language != "es" }) {
            item {
                Text(stringResource(R.string.catalogue_languages), Modifier.testTag("profile_languages"),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(profile.languages.joinToString { Locale.forLanguageTag(it).getDisplayLanguage(locale) })
            }
        }
        item {
            Section(R.string.catalogue_phone)
            SelectionContainerCompat(profile.phone)
        }
        item {
            Text(stringResource(R.string.catalogue_prices), Modifier.testTag("reference_prices"),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.catalogue_prices_hint))
            if (profile.prices.isEmpty()) Text(stringResource(R.string.catalogue_no_prices))
        }
        items(profile.prices) { price ->
            Card(colors = CardDefaults.cardColors(containerColor = TeloTheme.extended.card)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(price.service, fontWeight = FontWeight.Bold)
                    Text(priceText(price),
                        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    // Owners publish prices directly (2026-11-23); optional texts may be empty.
                    if (price.scope.isNotBlank()) Text(price.scope)
                    if (price.includes.isNotBlank()) Text(stringResource(R.string.catalogue_includes, price.includes))
                    if (price.excludes.isNotBlank()) Text(stringResource(R.string.catalogue_excludes, price.excludes))
                }
            }
        }
        if (reviews != null) item { ProfileReviewList(profile.id, reviews) }
    }
}

@Composable
private fun SelectionContainerCompat(phone: String) {
    androidx.compose.foundation.text.selection.SelectionContainer { Text(phone) }
}

@Composable
private fun Section(label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun Rating(profile: PublicProfile) {
    Text(if (profile.rating == null) stringResource(R.string.catalogue_no_reviews)
        else stringResource(R.string.catalogue_rating, profile.rating, profile.reviewCount),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun priceText(price: ReferencePrice): String {
    if (price.amountCents == 0) return stringResource(R.string.catalogue_free)
    val locale = LocalConfiguration.current.locales[0]
    val money = NumberFormat.getCurrencyInstance(locale).apply { currency = java.util.Currency.getInstance("EUR") }
    return "${money.format(java.math.BigDecimal(price.amountCents).movePointLeft(2))} / ${stringResource(unitLabel(price.unit))}"
}

private fun searchable(value: String): String =
    java.text.Normalizer.normalize(value.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replace("\\p{M}".toRegex(), "")

internal fun tradeLabel(key: String): Int = when (key) {
    "plumbing" -> R.string.trade_plumbing
    "electrical" -> R.string.trade_electrical
    "carpentry" -> R.string.trade_carpentry
    "painting" -> R.string.trade_painting
    "masonry" -> R.string.trade_masonry
    "cleaning" -> R.string.trade_cleaning
    else -> R.string.trade_general
}

internal fun unitLabel(key: String): Int = when (key) {
    "hour" -> R.string.price_hour
    "sqm" -> R.string.price_sqm
    "unit" -> R.string.price_unit
    else -> R.string.price_job
}

/** Directions to an approved public commercial point only (#17); a missing app is reported. */
@Composable
private fun MapDirectionsButton(point: es.teloarreglo.search.GeoPoint) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var failed by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { failed = !openExternalMaps(context, point) }, modifier = Modifier.testTag("map_directions")) {
        Text(stringResource(R.string.map_directions))
    }
    if (failed) Text(stringResource(R.string.map_no_app), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("map_no_app"))
}
