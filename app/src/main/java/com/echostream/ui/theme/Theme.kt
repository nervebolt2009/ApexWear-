package com.echostream.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

@Composable
fun EchoStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = Colors(
            primary = SpotifyGreen,
            secondary = SpotifyPurple,
            background = SpotifyDarkBG,
            surface = SpotifySurface,
            onPrimary = TextWhite,
            onSecondary = TextWhite,
            onBackground = TextWhite,
            onSurface = TextWhite,
            error = ErrorRed,
            onError = TextWhite
        ),
        typography = EchoTypography,
        content = content
    )
}
