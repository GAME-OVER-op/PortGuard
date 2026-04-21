package com.android.portguard.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = PgPrimary,
    onPrimary = PgBackground,
    secondary = PgSecondary,
    onSecondary = PgBackground,
    tertiary = PgWarning,
    background = PgBackground,
    onBackground = PgOnDark,
    surface = PgSurface,
    onSurface = PgOnDark,
    surfaceVariant = PgSurfaceHigh,
    onSurfaceVariant = PgOnDarkMuted,
    error = PgError,
    onError = PgBackground,
    errorContainer = PgError.copy(alpha = 0.18f),
    onErrorContainer = PgOnDark,
)


@Composable
fun PortGuardAppTheme(
    content: @Composable () -> Unit,
) {
    val colors = DarkColors
    MaterialTheme(
        colorScheme = colors,
        typography = Typography,
        content = content
    )
}
