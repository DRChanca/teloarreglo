package es.teloarreglo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil3.compose.SubcomposeAsyncImage
import es.teloarreglo.catalogue.ProfessionalCovers
import kotlinx.coroutines.CancellationException

@Composable
internal fun ProfileCover(
    trade: String, path: String?, covers: ProfessionalCovers?, modifier: Modifier,
    preview: ByteArray? = null,
) {
    var url by remember(path, covers) { mutableStateOf<String?>(null) }
    var unavailable by remember(path, covers) { mutableStateOf(false) }
    LaunchedEffect(path, covers) {
        if (path != null && covers != null) {
            try { url = covers.imageUrl(path) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                unavailable = true
            }
        }
    }
    Box(modifier.clipToBounds()) {
        if (preview != null || url != null) {
            SubcomposeAsyncImage(
                model = preview ?: url, contentDescription = stringResource(R.string.cover_photo_description),
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                loading = { TradeArtwork(trade, Modifier.fillMaxSize()) },
                error = {
                    TradeArtwork(trade, Modifier.fillMaxSize())
                    UnavailableLabel(Modifier.align(Alignment.BottomStart))
                },
            )
        } else {
            TradeArtwork(trade, Modifier.fillMaxSize())
            if (unavailable) UnavailableLabel(Modifier.align(Alignment.BottomStart))
        }
    }
}

/** Original category illustrations. No network dependency, third-party identity or claim of completed work. */
@Composable
private fun TradeArtwork(trade: String, modifier: Modifier) {
    val description = stringResource(R.string.cover_generic_description, stringResource(tradeLabel(trade)))
    val accent = when (trade) {
        "electrical" -> Color(0xFFB88A2C)
        "carpentry" -> Color(0xFF9F7048)
        "painting" -> Color(0xFFB66D76)
        "masonry" -> Color(0xFFB37351)
        "general" -> Color(0xFF667CA0)
        "cleaning" -> Color(0xFF4F93B8)
        else -> Color(0xFF428D92)
    }
    Canvas(modifier.background(Color(0xFFEEF2EB)).semantics { contentDescription = description }) {
        val w = size.width
        val h = size.height
        val unit = minOf(w, h)
        drawCircle(accent.copy(alpha = .12f), unit * .52f, Offset(w * .58f, h * .45f))
        drawCircle(Color.White.copy(alpha = .65f), unit * .32f, Offset(w * .35f, h * .25f))
        drawRoundRect(accent.copy(alpha = .16f), Offset(w * .16f, h * .78f),
            Size(w * .68f, h * .08f), androidx.compose.ui.geometry.CornerRadius(unit * .04f))
        val dark = Color(0xFF214C40)
        when (trade) {
            "plumbing" -> {
                drawLine(dark, Offset(w * .35f, h * .24f), Offset(w * .35f, h * .66f), unit * .10f)
                drawLine(dark, Offset(w * .35f, h * .65f), Offset(w * .68f, h * .65f), unit * .10f)
                drawPath(Path().apply {
                    moveTo(w * .66f, h * .22f)
                    cubicTo(w * .61f, h * .34f, w * .53f, h * .40f, w * .53f, h * .48f)
                    cubicTo(w * .53f, h * .66f, w * .80f, h * .66f, w * .80f, h * .48f)
                    cubicTo(w * .80f, h * .40f, w * .71f, h * .31f, w * .66f, h * .22f)
                    close()
                }, accent)
            }
            "electrical" -> {
                drawRoundRect(dark, Offset(w * .26f, h * .26f), Size(w * .34f, h * .43f),
                    androidx.compose.ui.geometry.CornerRadius(unit * .05f))
                drawCircle(Color.White, unit * .025f, Offset(w * .38f, h * .42f))
                drawCircle(Color.White, unit * .025f, Offset(w * .49f, h * .42f))
                drawPath(Path().apply {
                    moveTo(w * .72f, h * .20f); lineTo(w * .59f, h * .50f)
                    lineTo(w * .69f, h * .50f); lineTo(w * .63f, h * .77f)
                    lineTo(w * .83f, h * .40f); lineTo(w * .72f, h * .40f); close()
                }, accent)
            }
            "carpentry" -> {
                repeat(3) { i ->
                    drawRect(accent.copy(alpha = .65f + i * .1f),
                        Offset(w * .21f, h * (.30f + i * .14f)), Size(w * .58f, h * .11f))
                    drawLine(Color.White.copy(alpha = .5f), Offset(w * .27f, h * (.35f + i * .14f)),
                        Offset(w * .68f, h * (.35f + i * .14f)), unit * .008f)
                }
                drawLine(dark, Offset(w * .43f, h * .22f), Offset(w * .59f, h * .72f), unit * .07f)
                drawRect(dark, Offset(w * .35f, h * .20f), Size(w * .23f, h * .10f))
            }
            "painting" -> {
                drawLine(dark, Offset(w * .55f, h * .41f), Offset(w * .55f, h * .74f), unit * .06f)
                drawLine(dark, Offset(w * .35f, h * .43f), Offset(w * .57f, h * .43f), unit * .035f)
                drawRoundRect(accent, Offset(w * .25f, h * .21f), Size(w * .46f, h * .22f),
                    androidx.compose.ui.geometry.CornerRadius(unit * .05f))
                drawLine(Color.White.copy(alpha = .6f), Offset(w * .31f, h * .27f),
                    Offset(w * .62f, h * .27f), unit * .025f)
            }
            "masonry" -> {
                repeat(3) { row -> repeat(2) { col ->
                    drawRoundRect(accent, Offset(w * (.23f + col * .28f), h * (.30f + row * .14f)),
                        Size(w * .26f, h * .12f), androidx.compose.ui.geometry.CornerRadius(unit * .02f))
                } }
                drawPath(Path().apply {
                    moveTo(w * .57f, h * .18f); lineTo(w * .45f, h * .48f)
                    lineTo(w * .76f, h * .43f); close()
                }, dark)
            }
            "cleaning" -> {
                // Spray bottle and bubbles.
                drawRoundRect(dark, Offset(w * .30f, h * .40f), Size(w * .26f, h * .34f),
                    androidx.compose.ui.geometry.CornerRadius(unit * .05f))
                drawRect(dark, Offset(w * .36f, h * .28f), Size(w * .14f, h * .13f))
                drawRect(dark, Offset(w * .26f, h * .26f), Size(w * .24f, h * .05f))
                drawRoundRect(Color.White.copy(alpha = .6f), Offset(w * .35f, h * .50f), Size(w * .16f, h * .12f),
                    androidx.compose.ui.geometry.CornerRadius(unit * .02f))
                drawCircle(accent, unit * .07f, Offset(w * .68f, h * .30f))
                drawCircle(accent.copy(alpha = .75f), unit * .045f, Offset(w * .76f, h * .46f))
                drawCircle(accent.copy(alpha = .55f), unit * .03f, Offset(w * .63f, h * .55f))
            }
            else -> {
                drawRoundRect(dark, Offset(w * .23f, h * .39f), Size(w * .54f, h * .32f),
                    androidx.compose.ui.geometry.CornerRadius(unit * .04f))
                drawRoundRect(accent, Offset(w * .39f, h * .22f), Size(w * .23f, h * .18f),
                    androidx.compose.ui.geometry.CornerRadius(unit * .03f), style = Stroke(unit * .045f))
                drawLine(accent, Offset(w * .25f, h * .52f), Offset(w * .75f, h * .52f), unit * .035f)
                drawRect(Color.White, Offset(w * .46f, h * .47f), Size(w * .08f, h * .11f))
            }
        }
    }
}

/** Over the trade artwork (same colours in both themes), so the label carries its own themed background. */
@Composable
private fun UnavailableLabel(modifier: Modifier) {
    Text(stringResource(R.string.cover_image_unavailable), color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = .9f)).padding(6.dp))
}
