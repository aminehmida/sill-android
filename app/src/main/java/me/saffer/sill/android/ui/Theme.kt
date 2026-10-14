package me.saffer.sill.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Sill's look: dark, quiet, the app icon's blue as the accent (design/AppIcon.svg's #9bb7ff). */
object SillColors {
    val accent = Color(0xFF8FAEFF)
    val background = Color(0xFF000000)
    val surface = Color(0xFF151618)
    val surfaceHigh = Color(0xFF1F2124)
    val bar = Color(0xFF111214)
    val outline = Color(0x33FFFFFF)
    val secondaryText = Color(0x99FFFFFF)
    val tertiaryText = Color(0x66FFFFFF)
    val danger = Color(0xFFFF6B5E)
    val close = Color(0xFFFF5F57)
    val minimize = Color(0xFFFEBC2E)
    val zoom = Color(0xFF28C840)
}

@Composable
fun SillTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SillColors.accent,
            onPrimary = Color(0xFF0B1A3D),
            background = SillColors.background,
            surface = SillColors.surface,
            surfaceVariant = SillColors.surfaceHigh,
            surfaceContainer = SillColors.surface,
            surfaceContainerHigh = SillColors.surfaceHigh,
            surfaceContainerHighest = Color(0xFF2A2C30),
            outline = SillColors.outline,
            error = SillColors.danger,
        ),
        typography = Typography(),
        content = content,
    )
}
