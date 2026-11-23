package es.teloarreglo

import androidx.activity.compose.BackHandler
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
private val green = Color(0xFF246451)
private val canvas = Color(0xFFF7F8F5)

@Composable
fun CatalogueScreen(
    catalogue: Catalogue, onAccount: () -> Unit, onLanguageChange: (String) -> Unit,
    initialProfileId: String? = null,
    covers: ProfessionalCovers? = null,
    ownedProfileId: String? = null,
    onContact: ((String) -> Unit)? = null,
    onChats: (() -> Unit)? = null,
    onNearby: (() -> Unit)? = null,
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
    TeloTheme {
        Surface(Modifier.fillMaxSize(), color = canvas) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                     if (selected != null) {
                        TextButton(onClick = { selected = null }, Modifier.testTag("back_catalogue")) {
                            Text(stringResource(R.string.catalogue_back))
                        }
                     } else {
                        Text("teloarreglo", modifier = Modifier.weight(1f), color = green,
                            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                     }
                    LanguageMenu(onLanguageChange)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                    if (onNearby != null) TextButton(onClick = onNearby, modifier = Modifier.testTag("catalogue_nearby")) {
                        Text(stringResource(R.string.catalogue_nearby))
                    }
                    if (onChats != null) TextButton(onClick = onChats) { Text(stringResource(R.string.chat_title)) }
                    TextButton(onClick = onAccount) { Text(stringResource(R.string.catalogue_account)) }
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
                    selected != null -> ProfileDetail(profiles.single(), covers,
                        onEdit = if (selected == ownedProfileId && covers != null) ({ editing = true }) else null,
                        onContact = if (selected != ownedProfileId) onContact?.let { contact -> { contact(profiles.single().id) } } else null)
                    else -> {
                        Text(pluralStringResource(R.plurals.catalogue_results, visible.size, visible.size),
                            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge)
                        LazyColumn(
                            state = listState, modifier = Modifier.testTag("catalogue_list"),
                            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                        items(visible, key = { it.id }) { profile ->
                            Card(
                                onClick = { selected = profile.id }, modifier = Modifier.fillMaxWidth().testTag("profile_${profile.id}"),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                shape = RoundedCornerShape(20.dp),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    ProfileCover(profile.trades.firstOrNull().orEmpty(), profile.coverPath, covers,
                                        Modifier.width(100.dp).height(120.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(profile.services.firstOrNull() ?: profile.trades.map { stringResource(tradeLabel(it)) }.joinToString(),
                                            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(profile.coverage.joinToString { it.name }, style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        profile.prices.firstOrNull()?.let { price ->
                                            Text(stringResource(R.string.catalogue_reference, priceText(price)),
                                                color = green, style = MaterialTheme.typography.labelLarge)
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
}

@Composable
private fun ProfileDetail(profile: PublicProfile, covers: ProfessionalCovers?, onEdit: (() -> Unit)?, onContact: (() -> Unit)?) {
    val locale = LocalConfiguration.current.locales[0]
    LazyColumn(
        modifier = Modifier.testTag("profile_detail"),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(profile.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(profile.trades.map { stringResource(tradeLabel(it)) }.joinToString(), color = green)
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
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(price.service, fontWeight = FontWeight.Bold)
                    Text(priceText(price),
                        style = MaterialTheme.typography.titleLarge, color = green)
                    Text(price.scope)
                    Text(stringResource(R.string.catalogue_includes, price.includes))
                    Text(stringResource(R.string.catalogue_excludes, price.excludes))
                }
            }
        }
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
private fun priceText(price: ReferencePrice): String {
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

private fun unitLabel(key: String): Int = when (key) {
    "hour" -> R.string.price_hour
    "sqm" -> R.string.price_sqm
    "unit" -> R.string.price_unit
    else -> R.string.price_job
}
