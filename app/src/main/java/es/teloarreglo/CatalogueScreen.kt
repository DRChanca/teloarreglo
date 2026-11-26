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
import androidx.compose.ui.graphics.RectangleShape
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import es.teloarreglo.catalogue.Catalogue
import es.teloarreglo.catalogue.PublicProfile
import es.teloarreglo.catalogue.Coverage
import androidx.compose.ui.semantics.heading
import es.teloarreglo.catalogue.ProfessionalCovers
import es.teloarreglo.catalogue.ReferencePrice
import es.teloarreglo.catalogue.ProfileSection
import es.teloarreglo.catalogue.profileSections
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
    /** Distance order of the single search (ADR 0001); null keeps the catalogue order. */
    proximitySource: ProximitySource? = null,
    /** The app's search (#59): when given, the screen shows it instead of starting its own from home. */
    searchSession: AppSearchSession? = null,
    /** From Ajustes (#61): the results show the map above the list. */
    showMap: Boolean = false,
    /** Closes [initialProfileId] when it was opened from another tab; null closes it into the list. */
    onCloseProfile: (() -> Unit)? = null,
) {
    // With the app's search (#59) the trade is kept there, so returning to the tab neither loses nor repeats it.
    val savedTrade = rememberSaveable { mutableStateOf<String?>(null) }
    var trade by (searchSession?.trade ?: savedTrade)
    var selected by rememberSaveable(initialProfileId) { mutableStateOf(initialProfileId) }
    var retry by remember { mutableIntStateOf(0) }
    var profiles by remember { mutableStateOf<List<PublicProfile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    // Search filters (#52): kept for the app session, applied on the device over the ordered list.
    var filters by rememberSaveable(stateSaver = SearchFiltersSaver) { mutableStateOf(SearchFilters()) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
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
    val closeProfile: () -> Unit = { if (onCloseProfile != null && selected == initialProfileId) { onCloseProfile() } else { selected = null } }
    BackHandler(enabled = selected != null) { closeProfile() }
    val profile = profiles.singleOrNull()?.takeIf { selected == it.id && completedRequest == request }
    if (editing && profile != null && profile.id == ownedProfileId && covers != null) {
        CoverEditorRoute(profile, covers, onBack = { editing = false; retry++ })
        return
    }
    val proximity = rememberProximity(proximitySource, catalogue, trade, searchSession)
    val ranked = proximity?.results
    // The single search (ADR 0001): the distance order when there is one, the catalogue order otherwise.
    val rows = ranked?.rows ?: profiles.map { SearchRow(it, null, true) }
    val hasPoint = ranked != null
    // Filters first, then the text (#52); both only remove rows from the ordered list.
    val matching: (List<SearchRow>, SearchFilters) -> List<SearchRow> = { candidates, appliedFilters ->
        candidates.filteredBy(appliedFilters, hasPoint).filter { row ->
            searchable(row.profile.name + " " + row.profile.services.joinToString(" ")).contains(searchable(search.trim()))
        }
    }
    val visible = matching(rows, filters)
    // «Ver N profesionales»: another trade is only countable here when the list holds every trade.
    val countFor: (String?, SearchFilters) -> Int? = { draftTrade, draftFilters ->
        when {
            draftTrade == trade -> matching(rows, draftFilters).size
            trade == null -> matching(rows.filter { draftTrade in it.profile.trades }, draftFilters).size
            else -> null
        }
    }
    // As the sheet's «Restablecer»: trade, distance and rating go back to their defaults; the text stays.
    val resetFilters: () -> Unit = { trade = null; filters = SearchFilters() }
    val catalogueWaiting = loading || completedRequest != request
    // The concejo the search is about, for «No suele trabajar en …» on the profile.
    val searchConcejo = ranked?.concejo?.let { Coverage(it.ineCode, it.name) }
        ?: proximitySource?.home?.let { h -> h.ineCode?.let { Coverage(it, h.concejoName ?: it) } }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            // Sections live in the bottom bar (#46); the header keeps the brand or the way back.
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected != null) {
                    TextButton(onClick = closeProfile, Modifier.testTag("back_catalogue")) {
                        Text(stringResource(R.string.catalogue_back))
                    }
                } else {
                    Text("teloarreglo", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleLarge)
                }
            }
            if (selected == null) {
                Text(
                    stringResource(R.string.catalogue_title),
                    Modifier.padding(horizontal = TeloTheme.spacing.screen, vertical = TeloTheme.spacing.medium)
                        .semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
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
                    FiltersButton(filters.activeCount(trade, hasPoint)) { filtersOpen = true }
                    FilterChip(selected = trade == null, onClick = { trade = null },
                        label = { Text(stringResource(R.string.trade_all)) })
                    catalogueTrades.forEach { key ->
                        FilterChip(selected = trade == key, onClick = { trade = key },
                            modifier = Modifier.testTag("trade_$key"), label = { Text(stringResource(tradeLabel(key))) })
                    }
                }
                if (filtersOpen) FiltersSheet(trade, filters, hasPoint, countFor,
                    onApply = { newTrade, newFilters -> trade = newTrade; filters = newFilters; filtersOpen = false }, onDismiss = { filtersOpen = false })
                LazyColumn(
                    state = listState, modifier = Modifier.testTag("catalogue_list"),
                    contentPadding = PaddingValues(TeloTheme.spacing.screen),
                    verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.medium),
                ) {
                    if (proximity != null) item(key = "proximity") { ProximityHeader(proximity) }
                    when {
                        // Skeleton until the order is known: cards never jump once shown.
                        proximity?.busy == true || (ranked == null && catalogueWaiting) -> items(4) { SkeletonCard() }
                        ranked == null && failed -> item {
                            Column(Modifier.padding(8.dp)) {
                                Text(stringResource(R.string.catalogue_error), Modifier.testTag("catalogue_error"))
                                Button(onClick = { retry++ }, Modifier.testTag("catalogue_retry")) { Text(stringResource(R.string.retry)) }
                            }
                        }
                        // Never an empty list that looks like there are no professionals (#52).
                        visible.isEmpty() && filters.activeCount(null, hasPoint) > 0 -> item(key = "filters_empty") {
                            FiltersEmpty(onReset = resetFilters)
                        }
                        visible.isEmpty() -> item {
                            Column(Modifier.padding(8.dp)) {
                                Text(stringResource(if (rows.isNotEmpty()) R.string.catalogue_filtered_empty else R.string.catalogue_empty),
                                    Modifier.testTag("catalogue_empty"))
                                if (trade != null || search.isNotEmpty()) {
                                    TextButton(onClick = { trade = null; search = ""; keyboard?.hide() }, Modifier.testTag("catalogue_reset")) {
                                        Text(stringResource(R.string.catalogue_reset_filters))
                                    }
                                }
                            }
                        }
                        else -> {
                            item(key = "count") {
                                Text(pluralStringResource(R.plurals.catalogue_results, visible.size, visible.size),
                                    style = MaterialTheme.typography.labelLarge)
                            }
                            if (showMap && ranked != null) item(key = "map") { ProximityMap(proximity, ranked, visible) { selected = it } }
                            items(visible, key = { it.profile.id }) { row ->
                                val highlighted = if (ranked != null) row.profile.id in ranked.featured else row.profile.featured
                                ProfileCard(row, highlighted, ranked?.concejo?.name, covers) { selected = row.profile.id }
                            }
                        }
                    }
                }
            } else when {
                catalogueWaiting -> Column(Modifier.padding(24.dp).testTag("catalogue_loading")) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.catalogue_loading))
                }
                failed -> Column(Modifier.padding(24.dp)) {
                    Text(stringResource(R.string.catalogue_error), Modifier.testTag("catalogue_error"))
                    Button(onClick = { retry++ }, Modifier.testTag("catalogue_retry")) { Text(stringResource(R.string.retry)) }
                }
                profiles.isEmpty() -> Column(Modifier.padding(24.dp)) {
                    Text(stringResource(R.string.catalogue_unpublished), Modifier.testTag("catalogue_empty"))
                }
                else -> ProfileDetail(profiles.single(), covers, catalogue,
                    onEdit = if (selected == ownedProfileId && covers != null) ({ editing = true }) else null,
                    onContact = if (selected != ownedProfileId) onContact?.let { contact -> { contact(profiles.single().id) } } else null,
                    reviews = reviews,
                    outsideZoneOf = searchConcejo?.takeIf { c -> selected != ownedProfileId && profiles.single().coverage.none { it.code == c.code } })
            }
        }
    }
}

/**
 * One result: «Perfil destacado» (2026-11-24) stands out with the theme's secondary container and primary
 * outline, rounded corners, and always says why; other cards are square (#51). Only a «Destacado» of the
 * searched concejo's zone is [highlighted] (ADR 0001).
 * With a distance order the card shows the rounded band and, out of zone, «No suele trabajar en …».
 * Under the Valoración, its «Tiempo de respuesta» when there is data (#80).
 */
@Composable
private fun ProfileCard(row: SearchRow, highlighted: Boolean, concejo: String?, covers: ProfessionalCovers?, onOpen: () -> Unit) {
    val profile = row.profile
    Card(
        onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("profile_${profile.id}"),
        colors = CardDefaults.cardColors(containerColor =
            if (highlighted) MaterialTheme.colorScheme.secondaryContainer else TeloTheme.extended.card),
        border = if (highlighted) BorderStroke(1.5.dp, TeloTheme.extended.featuredBorder) else null,
        shape = if (highlighted) MaterialTheme.shapes.large else RectangleShape,
    ) {
        Row(Modifier.fillMaxWidth().padding(TeloTheme.spacing.medium), horizontalArrangement = Arrangement.spacedBy(TeloTheme.spacing.medium)) {
            ProfileCover(profile.trades.firstOrNull().orEmpty(), profile.coverPath, covers,
                Modifier.width(CoverCrop.LIST_WIDTH).height(CoverCrop.LIST_HEIGHT))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (highlighted) FeaturedBadge(Modifier.testTag("catalogue_featured_${profile.id}"),
                    R.string.catalogue_featured_profile)
                Text(profile.name, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(profile.services.firstOrNull() ?: profile.trades.map { stringResource(tradeLabel(it)) }.joinToString(),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                row.bandM?.let { band ->
                    Text(distanceText(band), Modifier.testTag("nearby_distance_${profile.id}"),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!row.inZone && concejo != null) {
                    Text(stringResource(R.string.search_out_of_zone, concejo), Modifier.testTag("out_of_zone_${profile.id}"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(profile.coverage.joinToString { it.name }, style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                profile.prices.firstOrNull()?.let { price ->
                    Text(stringResource(R.string.catalogue_reference, priceText(price)),
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
                ProfileRating(profile.rating, profile.reviewCount, compact = true, Modifier.testTag("rating_${profile.id}"))
                ResponseTime(row.responseBand, Modifier.testTag("response_time_${profile.id}"),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ProfileDetail(
    profile: PublicProfile, covers: ProfessionalCovers?, catalogue: Catalogue,
    onEdit: (() -> Unit)?, onContact: (() -> Unit)?, reviews: es.teloarreglo.appointments.Reviews? = null,
    /** Concejo of the search when it is outside this profile's work zone: informs, never blocks (ADR 0001). */
    outsideZoneOf: Coverage? = null,
) {
    val locale = LocalConfiguration.current.locales[0]
    // «Destacado» (#33) from the server rule; a failed check shows no badge rather than a false one.
    val featured by produceState(false, profile.id) {
        value = try { catalogue.featured(profile.id) } catch (error: Exception) {
            if (error is CancellationException) throw error
            false
        }
    }
    val spacing = TeloTheme.spacing
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).testTag("profile_detail"),
            contentPadding = PaddingValues(horizontal = spacing.screen, vertical = spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.extraLarge),
        ) {
            profileSections(profile, showReviews = reviews != null).forEach { section ->
                when (section) {
                    ProfileSection.COVER -> item {
                        SectionBlock {
                            ProfileCover(profile.trades.firstOrNull().orEmpty(), profile.coverPath, covers,
                                Modifier.fillMaxWidth().height(CoverCrop.DETAIL_HEIGHT))
                            if (onEdit != null) OutlinedButton(onClick = onEdit,
                                modifier = Modifier.fillMaxWidth().testTag("edit_cover")) {
                                Text(stringResource(R.string.cover_edit))
                            }
                        }
                    }
                    ProfileSection.IDENTITY -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.extraSmall)) {
                            if (featured) FeaturedBadge(Modifier.testTag("profile_featured"))
                            Text(profile.name, style = MaterialTheme.typography.headlineMedium)
                            Text(profile.trades.map { stringResource(tradeLabel(it)) }.joinToString(),
                                color = MaterialTheme.colorScheme.primary)
                            ProfileRating(profile.rating, profile.reviewCount, compact = false, Modifier.testTag("profile_rating"))
                            ResponseTime(profile.responseBand, Modifier.testTag("profile_response_time"))
                            outsideZoneOf?.let { concejo ->
                                Text(stringResource(R.string.profile_out_of_zone, concejo.name),
                                    Modifier.testTag("profile_out_of_zone"), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    ProfileSection.DESCRIPTION -> item {
                        Text(profile.description, style = MaterialTheme.typography.bodyLarge)
                    }
                    ProfileSection.PRICES -> {
                        item {
                            SectionBlock {
                                Section(R.string.catalogue_prices, Modifier.testTag("reference_prices"))
                                Text(stringResource(R.string.catalogue_prices_hint))
                                if (profile.prices.isEmpty()) Text(stringResource(R.string.catalogue_no_prices))
                            }
                        }
                        items(profile.prices) { price -> PriceCard(price) }
                    }
                    ProfileSection.REVIEWS -> item { reviews?.let { ProfileReviewList(profile.id, it) } }
                    ProfileSection.SERVICES -> item {
                        SectionBlock {
                            Section(R.string.catalogue_services)
                            profile.services.forEach { Text("• $it") }
                        }
                    }
                    ProfileSection.COVERAGE -> item {
                        SectionBlock {
                            Section(R.string.catalogue_coverage)
                            Text(profile.coverage.joinToString { it.name })
                        }
                    }
                    ProfileSection.ADDRESS -> item {
                        SectionBlock {
                            Section(R.string.catalogue_address)
                            SelectionContainerCompat(profile.businessAddress.orEmpty())
                            profile.publicLocation()?.let { point -> MapDirectionsButton(point) }
                        }
                    }
                    ProfileSection.LANGUAGES -> item {
                        SectionBlock {
                            Section(R.string.catalogue_languages, Modifier.testTag("profile_languages"))
                            Text(profile.languages.joinToString { Locale.forLanguageTag(it).getDisplayLanguage(locale) })
                        }
                    }
                    ProfileSection.PHONE -> item {
                        SectionBlock {
                            Section(R.string.catalogue_phone)
                            CallButton(profile.phone)
                        }
                    }
                }
            }
        }
        // The main action stays in reach while reading prices and reviews (#47); onContact is null on one's
        // own profile and without a session, so the bar is absent there.
        if (onContact != null) Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
            Button(onClick = onContact,
                modifier = Modifier.padding(horizontal = spacing.screen, vertical = spacing.small)
                    .fillMaxWidth().heightIn(min = 48.dp).testTag("chat_contact")) {
                Text(stringResource(R.string.chat_contact))
            }
        }
    }
}

/** One profile section: title and content grouped with a small gap; sections are spaced further apart. */
@Composable
private fun SectionBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.small), content = content)
}

@Composable
private fun PriceCard(price: ReferencePrice) {
    Card(colors = CardDefaults.cardColors(containerColor = TeloTheme.extended.card)) {
        Column(Modifier.fillMaxWidth().padding(TeloTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.small)) {
            Text(price.service, style = MaterialTheme.typography.titleMedium)
            Text(priceText(price),
                style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            // Owners publish prices directly (2026-11-23); optional texts may be empty.
            if (price.scope.isNotBlank()) Text(price.scope)
            if (price.includes.isNotBlank()) Text(stringResource(R.string.catalogue_includes, price.includes))
            if (price.excludes.isNotBlank()) Text(stringResource(R.string.catalogue_excludes, price.excludes))
        }
    }
}

@Composable
private fun SelectionContainerCompat(text: String) {
    androidx.compose.foundation.text.selection.SelectionContainer { Text(text) }
}

@Composable
private fun Section(label: Int, modifier: Modifier = Modifier) {
    Text(stringResource(label), modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
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
