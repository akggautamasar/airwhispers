package com.airwhispers.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Indigo = Color(0xFF5B6CFF)
private val IndigoDeep = Color(0xFF3730A3)
private val Cyan = Color(0xFF22D3EE)
private val Navy = Color(0xFF0B1020)
private val NavySurface = Color(0xFF131A2E)
private val NavyElevated = Color(0xFF1B2340)

private val DarkScheme = darkColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = IndigoDeep,
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Cyan,
    onSecondary = Color(0xFF00272E),
    secondaryContainer = Color(0xFF0E3A44),
    onSecondaryContainer = Color(0xFFB6F1FA),
    background = Navy,
    onBackground = Color(0xFFE5E7F2),
    surface = NavySurface,
    onSurface = Color(0xFFE5E7F2),
    surfaceVariant = NavyElevated,
    onSurfaceVariant = Color(0xFFB9C0DA),
    outline = Color(0xFF3B4472),
    error = Color(0xFFFF6B81),
    onError = Color(0xFF2A0410),
)

private val LightScheme = lightColorScheme(
    primary = IndigoDeep,
    onPrimary = Color.White,
    secondary = Color(0xFF0E7490),
    onSecondary = Color.White,
    background = Color(0xFFF7F8FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE6E9F5),
    onSurfaceVariant = Color(0xFF414A6B),
    outline = Color(0xFFB9C0DA),
)

/**
 * AirWhispers theme. Dark by default (it is used while looking at a call screen,
 * often at night) but respects a light system theme.
 */
@Composable
fun AirWhispersTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography.copy(
            headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp),
            labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        ),
        content = content,
    )
}
