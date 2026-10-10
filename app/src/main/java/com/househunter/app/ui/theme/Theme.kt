package com.househunter.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B1B1F),
    onPrimary = Color.White,
    secondary = Color(0xFF3F5D50),
    surface = Color(0xFFFBFBFD),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE7E7EC),
    outline = Color(0xFFC6C6D0),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE4E1E6),
    onPrimary = Color(0xFF1B1B1F),
    secondary = Color(0xFFA8CBBB),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFE4E1E6),
    surfaceVariant = Color(0xFF2A2A30),
    outline = Color(0xFF45454F),
    error = Color(0xFFF2B8B5),
)

@Composable
fun HouseHunterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}