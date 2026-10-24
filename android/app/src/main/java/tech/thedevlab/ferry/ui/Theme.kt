package tech.thedevlab.ferry.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Brand colours, shared with the iOS app: teal, with mint as the dark-mode accent. */
object Brand {
    val Teal = Color(0xFF1B7A8D)
    val DeepTeal = Color(0xFF0F5562)
    val Ocean = Color(0xFF1F6FA8)
    val Mint = Color(0xFF65D9C2)
    val Coral = Color(0xFFE5735C)
    val Sky = Color(0xFF5B8DEF)
}

private val Light = lightColorScheme(
    primary = Brand.Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFEDEF),
    onPrimaryContainer = Color(0xFF00363F),
    secondary = Color(0xFF2E8F7C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3F3EA),
    onSecondaryContainer = Color(0xFF00382F),
    tertiary = Brand.Ocean,
    error = Color(0xFFBA3B2A),
    background = Color(0xFFF5F9F9),
    onBackground = Color(0xFF151D1E),
    surface = Color(0xFFF5F9F9),
    onSurface = Color(0xFF151D1E),
    surfaceVariant = Color(0xFFDDE8E9),
    onSurfaceVariant = Color(0xFF465456),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEDF4F4),
    surfaceContainerHigh = Color(0xFFE6EFEF),
    surfaceContainerHighest = Color(0xFFDFE9EA),
    outline = Color(0xFF768587),
    outlineVariant = Color(0xFFC6D3D4),
)

private val Dark = darkColorScheme(
    primary = Brand.Mint,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0F4F5B),
    onPrimaryContainer = Color(0xFFCFEDEF),
    secondary = Color(0xFF8FD3C3),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF1C4A44),
    onSecondaryContainer = Color(0xFFD3F3EA),
    tertiary = Color(0xFF9CC7F0),
    error = Color(0xFFFFB4A6),
    background = Color(0xFF0E1517),
    onBackground = Color(0xFFDDE5E6),
    surface = Color(0xFF0E1517),
    onSurface = Color(0xFFDDE5E6),
    surfaceVariant = Color(0xFF3A4749),
    onSurfaceVariant = Color(0xFFB9C7C9),
    surfaceContainerLowest = Color(0xFF0A1012),
    surfaceContainerLow = Color(0xFF162022),
    surfaceContainer = Color(0xFF1A2426),
    surfaceContainerHigh = Color(0xFF212C2E),
    surfaceContainerHighest = Color(0xFF2B3739),
    outline = Color(0xFF849193),
    outlineVariant = Color(0xFF3A4749),
)

private val Base = Typography()

private val FerryTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private val FerryShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun FerryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = FerryTypography,
        shapes = FerryShapes,
        content = content,
    )
}
