package com.calmlib.reader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val EinkColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    secondary = Color(0xFF666666),
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF333333),
    outline = Color(0xFFDDDDDD),
    outlineVariant = Color(0xFFEEEEEE),
)

@Composable
fun CalmLibTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EinkColorScheme,
        content = content,
    )
}
