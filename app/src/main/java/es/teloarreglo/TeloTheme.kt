package es.teloarreglo

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
internal fun TeloTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF246451),
        onPrimary = Color.White,
        secondary = Color(0xFF4D665A),
        secondaryContainer = Color(0xFFE2EFE5),
        onSecondaryContainer = Color(0xFF173E2E),
        background = Color(0xFFF7F8F5),
        surface = Color(0xFFF7F8F5),
        surfaceContainer = Color(0xFFF0F2EC),
    ), content = content)
}
