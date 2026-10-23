package com.vrec.app.ui.theme

import androidx.compose.runtime.Composable
import dev.n2k02.theme.N2K02Theme

/** Strict shared N2K02 Material 3 Expressive theme. */
@Composable
fun VrecTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    N2K02Theme(content = content)
}
