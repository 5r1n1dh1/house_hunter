package com.househunter.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Palette: warm ink + deep teal primary + amber accent.
private val Teal900 = Color(0xFF0F3B33)
private val Teal700 = Color(0xFF16695A)
private val Teal400 = Color(0xFF4FB39B)
private val Teal200 = Color(0xFFA8DCCE)
private val Amber600 = Color(0xFFC2703A)
private val Amber300 = Color(0xFFE9B27C)
private val Ink = Color(0xFF16181B)
private val Paper = Color(0xFFFBF9F7)
private val Cloud = Color(0xFFF1EDEA)

private val LightColors = lightColorScheme(
    primary = Teal700,
    onPrimary = Color.White,
    primaryContainer = Teal200,
    onPrimaryContainer = Teal900,
    secondary = Amber600,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7E4D3),
    onSecondaryContainer = Color(0xFF4A2A10),
    tertiary = Teal900,
    onTertiary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Cloud,
    onSurfaceVariant = Color(0xFF5B5F63),
    outline = Color(0xFFCFC9C3),
    outlineVariant = Color(0xFFE4DFDA),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Teal400,
    onPrimary = Color(0xFF06231D),
    primaryContainer = Color(0xFF11463C),
    onPrimaryContainer = Teal200,
    secondary = Amber300,
    onSecondary = Color(0xFF452109),
    secondaryContainer = Color(0xFF5C3418),
    onSecondaryContainer = Color(0xFFF7E4D3),
    tertiary = Teal200,
    onTertiary = Color(0xFF06231D),
    background = Color(0xFF111214),
    onBackground = Color(0xFFEAE7E4),
    surface = Color(0xFF191A1D),
    onSurface = Color(0xFFEAE7E4),
    surfaceVariant = Color(0xFF26282C),
    onSurfaceVariant = Color(0xFFB9B6B2),
    outline = Color(0xFF4A4C50),
    outlineVariant = Color(0xFF323438),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

private val HouseShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val base = Typography()

private val HouseTypography = base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

/** Shared gradients so surfaces stay consistent across screens. */
object HouseBrush {
    val header = Brush.verticalGradient(
        listOf(Color(0xFF16695A), Color(0xFF0F3B33)),
    )
    val headerDark = Brush.verticalGradient(
        listOf(Color(0xFF11463C), Color(0xFF0A2420)),
    )
    val price = Brush.horizontalGradient(
        listOf(Color(0xFF16695A), Color(0xFF2F8A76)),
    )
    val priceDark = Brush.horizontalGradient(
        listOf(Color(0xFF1E5C4E), Color(0xFF11463C)),
    )
}

@Composable
fun HouseHunterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = HouseShapes,
        typography = HouseTypography,
        content = content,
    )
}

val HeroTitle: TextStyle
    @Composable get() = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Start)