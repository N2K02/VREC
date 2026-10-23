@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.vrec.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// VREC palette: a deep teal-green with a warm record-red accent. Used only on Android 11 and
// below; Android 12+ draws from the wallpaper (dynamic color) unless turned off.
private val Teal80 = Color(0xFF7FD8C0)
private val Teal40 = Color(0xFF1B5E4A)
private val TealContainerDark = Color(0xFF00382B)
private val TealContainerLight = Color(0xFFB6F2DF)
private val RecordRed = Color(0xFFE2574C)
private val Grey80 = Color(0xFFBFC9C3)
private val Grey40 = Color(0xFF4F625A)
private val DarkSurface = Color(0xFF191C1B)
private val LightSurface = Color(0xFFFBFDFA)
private val Outline = Color(0xFF8A938D)

private val DarkColors = darkColorScheme(
    primary = Teal80,
    onPrimary = Color(0xFF00382B),
    primaryContainer = TealContainerDark,
    onPrimaryContainer = TealContainerLight,
    secondary = Grey80,
    tertiary = RecordRed,
    error = RecordRed,
    surface = DarkSurface,
    onSurface = Color(0xFFE1E3E0),
    outline = Outline,
)

private val LightColors = lightColorScheme(
    primary = Teal40,
    onPrimary = Color.White,
    primaryContainer = TealContainerLight,
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Grey40,
    tertiary = RecordRed,
    error = RecordRed,
    surface = LightSurface,
    onSurface = Color(0xFF191C1B),
    outline = Outline,
)

private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun VrecTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialExpressiveTheme(
        motionScheme = MotionScheme.expressive(),
        colorScheme = colorScheme,
        typography = Typography(),
        shapes = ExpressiveShapes.copy(extraSmall = RoundedCornerShape(28.dp), large = RoundedCornerShape(32.dp)),
        content = content,
    )
}
