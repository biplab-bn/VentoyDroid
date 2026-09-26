package com.ventoydroid.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VentoyGreen = Color(0xFF2E7D32)
private val VentoyGreenDark = Color(0xFF81C784)

private val LightColors = lightColorScheme(
    primary = VentoyGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F0C2),
    onPrimaryContainer = Color(0xFF00210A),
)

private val DarkColors = darkColorScheme(
    primary = VentoyGreenDark,
    onPrimary = Color(0xFF003913),
    primaryContainer = Color(0xFF005322),
    onPrimaryContainer = Color(0xFFB7F0C2),
)

@Composable
fun VentoyDroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
