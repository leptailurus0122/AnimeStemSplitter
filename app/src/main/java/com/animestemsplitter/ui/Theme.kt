package com.animestemsplitter.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object StemColors {
    fun of(stem: String, dark: Boolean): Color = when (stem) {
        "dialogue" -> if (dark) Color(0xFFFF7A9C) else Color(0xFFE0456C)
        "music" -> if (dark) Color(0xFF6FB6FF) else Color(0xFF2C7FD8)
        else -> if (dark) Color(0xFFFFC35C) else Color(0xFFC98A10)
    }
}

val STEM_LABELS = mapOf(
    "dialogue" to ("セリフ" to "Dialogue"),
    "music" to ("BGM" to "Music"),
    "effects" to ("効果音" to "SFX"),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB8F36B), onPrimary = Color(0xFF10140A),
    background = Color(0xFF0F1115), onBackground = Color(0xFFE9ECF2),
    surface = Color(0xFF171A21), onSurface = Color(0xFFE9ECF2),
    surfaceVariant = Color(0xFF1E222B), onSurfaceVariant = Color(0xFF8B93A5),
    outline = Color(0xFF2A2F3A), error = Color(0xFFFF6B6B),
)

private val Light = lightColorScheme(
    primary = Color(0xFF4F8A12), onPrimary = Color.White,
    background = Color(0xFFF5F3EE), onBackground = Color(0xFF1D1F24),
    surface = Color.White, onSurface = Color(0xFF1D1F24),
    surfaceVariant = Color(0xFFF0EDE6), onSurfaceVariant = Color(0xFF6B7080),
    outline = Color(0xFFE1DDD3), error = Color(0xFFC62828),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
