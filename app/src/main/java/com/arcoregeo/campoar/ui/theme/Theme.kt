package com.arcoregeo.campoar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Navy = Color(0xFF0F172A)
private val Slate = Color(0xFF1E293B)
private val Sky = Color(0xFF38BDF8)
private val Sand = Color(0xFFF8FAFC)

private val Colors = darkColorScheme(
    primary = Sky,
    onPrimary = Navy,
    background = Navy,
    surface = Slate,
    onBackground = Sand,
    onSurface = Sand,
    secondary = Color(0xFF34D399),
    error = Color(0xFFF87171),
)

@Composable
fun CampoArTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        content = content,
    )
}
