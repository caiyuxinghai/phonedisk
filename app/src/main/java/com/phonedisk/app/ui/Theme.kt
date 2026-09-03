package com.phonedisk.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = darkColorScheme(
    primary = Color(0xFFFFB74D),
    onPrimary = Color(0xFF3E2723),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF00332F),
    background = Color(0xFF121418),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF1B1F26),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF252A33),
    onSurfaceVariant = Color(0xFFB0B6C0),
    error = Color(0xFFEF9A9A),
)

@Composable
fun PhoneDiskTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        content = content,
    )
}
