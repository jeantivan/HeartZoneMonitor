package com.example.heartzonemonitor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AthleticMonolithColorScheme = darkColorScheme(
    primary = AthleticLime,
    onPrimary = VoidBlack,
    secondary = TertiaryCyan,
    onSecondary = VoidBlack,
    tertiary = RequiredOrange,
    background = VoidBlack,
    onBackground = TextPrimary,
    surface = DarkSlate,
    onSurface = TextPrimary,
    surfaceVariant = DarkSlate,
    onSurfaceVariant = TextSecondary,
    outline = BorderSlate,
    error = ErrorRed
)

@Composable
fun HeartZoneMonitorTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AthleticMonolithColorScheme,
        typography = Typography,
        content = content
    )
}
