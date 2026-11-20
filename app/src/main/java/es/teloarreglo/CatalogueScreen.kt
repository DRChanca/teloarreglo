package es.teloarreglo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import es.teloarreglo.catalogue.Catalogue
import es.teloarreglo.catalogue.PublicProfile
import kotlinx.coroutines.CancellationException
import java.text.NumberFormat
import java.util.Locale
import androidx.compose.ui.platform.LocalConfiguration

private val trades = listOf("plumbing", "electrical", "carpentry", "painting", "masonry", "general")
private val green = Color(0xFF246451)
private val canvas = Color(0xFFF7F8F5)

@Composable
fun CatalogueScreen(
    catalogue: Catalogue, onAccount: () -> Unit, onLanguageChange: (String) -> Unit,
    initialProfileId: String? = null,
) {
    var trade by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable(initialProfileId) { mutableStateOf(initialProfileId) }
    var retry by remember { mutableIntStateOf(0) }
    var profiles by remember { mutableStateOf<List<PublicProfile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
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
    MaterialTheme(colorScheme = lightColorScheme(primary = green, background = canvas)) {
        Surface(Modifier.fillMaxSize(), color = canvas) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
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
                        Text("teloarreglo", color = green, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    }
                    Row {
                        TextButton(onClick = onAccount) { Text(stringResource(R.string.catalogue_account)) }
                        LanguageMenu(onLanguageChange)
                    }
                }
                if (selected == null) {
                    Text(
                        stringResource(R.string.catalogue_title), Modifier.padding(20.dp),
                        style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(selected = trade == null, onClick = { trade = null },
                            label = { Text(stringResource(R.string.trade_all)) })
                        trades.forEach { key ->
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
                    profiles.isEmpty() -> Text(
                        stringResource(if (selected == null) R.string.catalogue_empty else R.string.catalogue_unpublished),
                        Modifier.padding(24.dp).testTag("catalogue_empty"),
                    )
                    selected != null -> ProfileDetail(profiles.single())
                    else -> LazyColumn(
                        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(profiles, key = { it.id }) { profile ->
                            Card(
                                onClick = { selected = profile.id }, modifier = Modifier.fillMaxWidth().testTag("profile_${profile.id}"),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                shape = RoundedCornerShape(20.dp),
                            ) {
                                TradeIllustration(profile.images.firstOrNull().orEmpty(), Modifier.fillMaxWidth().height(128.dp))
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(profile.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text(profile.trades.map { stringResource(tradeLabel(it)) }.joinToString())
                                    Text(profile.coverage.joinToString { it.name })
                                    Rating(profile)
                                    Text(stringResource(R.string.catalogue_open), color = green, fontWeight = FontWeight.Bold)
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
private fun ProfileDetail(profile: PublicProfile) {
    val locale = LocalConfiguration.current.locales[0]
    LazyColumn(
        modifier = Modifier.testTag("profile_detail"),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(profile.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Rating(profile)
        }
        items(profile.images) { key ->
            TradeIllustration(key, Modifier.fillMaxWidth().height(180.dp))
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
                    val money = NumberFormat.getCurrencyInstance(locale).apply { currency = java.util.Currency.getInstance("EUR") }
                    Text("${money.format(java.math.BigDecimal(price.amountCents).movePointLeft(2))} / ${stringResource(unitLabel(price.unit))}",
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
        else stringResource(R.string.catalogue_rating, profile.rating, profile.reviewCount))
}

/** Original procedural illustrations, bundled in the APK; no third-party photos or private URLs. */
@Composable
private fun TradeIllustration(key: String, modifier: Modifier) {
    val label = stringResource(R.string.catalogue_illustration, stringResource(tradeLabel(key)))
    val accent = when (key) {
        "electrical" -> Color(0xFFE4B84C)
        "carpentry" -> Color(0xFFBD8757)
        "painting" -> Color(0xFFD58A83)
        "masonry" -> Color(0xFFB46C50)
        "general" -> Color(0xFF7D91B3)
        else -> Color(0xFF5A9DA2)
    }
    Canvas(modifier.clipToBounds().background(accent.copy(alpha = 0.16f)).semantics { contentDescription = label }) {
        val w = size.width
        val h = size.height
        drawCircle(Color.White.copy(alpha = 0.7f), h * .75f, Offset(w * .72f, h * .35f))
        drawRect(green, Offset(w * .28f, h * .40f), Size(w * .44f, h * .46f))
        drawPath(Path().apply {
            moveTo(w * .22f, h * .43f); lineTo(w * .50f, h * .12f)
            lineTo(w * .78f, h * .43f); close()
        }, accent)
        drawRect(Color.White, Offset(w * .46f, h * .60f), Size(w * .09f, h * .26f))
        when (key) {
            "plumbing" -> {
                drawLine(accent, Offset(w * .12f, h * .65f), Offset(w * .23f, h * .65f), 12.dp.toPx())
                drawLine(accent, Offset(w * .18f, h * .65f), Offset(w * .18f, h * .9f), 12.dp.toPx())
            }
            "electrical" -> drawPath(Path().apply {
                moveTo(w * .83f, h * .18f); lineTo(w * .73f, h * .55f); lineTo(w * .83f, h * .55f)
                lineTo(w * .76f, h * .90f); lineTo(w * .94f, h * .43f); lineTo(w * .84f, h * .43f); close()
            }, accent)
            "painting" -> {
                drawRect(accent, Offset(w * .76f, h * .25f), Size(w * .16f, h * .17f))
                drawLine(accent, Offset(w * .83f, h * .42f), Offset(w * .83f, h * .86f), 6.dp.toPx())
            }
            "masonry" -> repeat(3) { row -> repeat(2) { col ->
                drawRect(accent, Offset(w * (.77f + col * .09f), h * (.50f + row * .12f)),
                    Size(w * .08f, h * .10f))
            } }
            else -> {
                drawLine(accent, Offset(w * .80f, h * .40f), Offset(w * .80f, h * .88f), 9.dp.toPx())
                drawRect(accent, Offset(w * .73f, h * .31f), Size(w * .19f, h * .14f))
            }
        }
    }
}

private fun tradeLabel(key: String): Int = when (key) {
    "plumbing" -> R.string.trade_plumbing
    "electrical" -> R.string.trade_electrical
    "carpentry" -> R.string.trade_carpentry
    "painting" -> R.string.trade_painting
    "masonry" -> R.string.trade_masonry
    else -> R.string.trade_general
}

private fun unitLabel(key: String): Int = when (key) {
    "hour" -> R.string.price_hour
    "sqm" -> R.string.price_sqm
    "unit" -> R.string.price_unit
    else -> R.string.price_job
}
