package com.clipit.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Palette comes from the app icon ("Layered Cards"): cool-grey paper, ink, one muted indigo.
// No dynamic (wallpaper) colour on purpose, so the app always matches the icon.

private val LightColors = lightColorScheme(
    primary = Color(0xFF5560CC),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEBEDFB),        // icon tiles
    onPrimaryContainer = Color(0xFF5560CC),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF20242B),
    surface = Color(0xFFFFFFFF),                 // cards
    onSurface = Color(0xFF20242B),
    surfaceVariant = Color(0xFFF1F3F8),          // secondary buttons
    onSurfaceVariant = Color(0xFF5B6472),        // captions
    outline = Color(0xFF8A93A3),                 // switch track (off)
    outlineVariant = Color(0xFFE3E7EE),          // hairlines
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8B94F0),
    onPrimary = Color(0xFF12151A),
    primaryContainer = Color(0xFF2A2F52),
    onPrimaryContainer = Color(0xFFA5ACFF),
    background = Color(0xFF12151A),
    onBackground = Color(0xFFECEFF4),
    surface = Color(0xFF1B1F26),
    onSurface = Color(0xFFECEFF4),
    surfaceVariant = Color(0xFF242933),
    onSurfaceVariant = Color(0xFF9AA3B2),
    outline = Color(0xFF6B7485),
    outlineVariant = Color(0xFF2C323D),
)

/** Colours Material 3 has no slot for (the "Connected" pill). */
@Immutable
data class ClipItExtras(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
)

private val LightExtras = ClipItExtras(
    success = Color(0xFF1F9D6B),
    successContainer = Color(0xFFE6F5EE),
    onSuccessContainer = Color(0xFF0F6B47),
)

private val DarkExtras = ClipItExtras(
    success = Color(0xFF3DD68C),
    successContainer = Color(0xFF17302A),
    onSuccessContainer = Color(0xFF7FD8A8),
)

private val LocalClipItExtras = staticCompositionLocalOf { LightExtras }

/** Use as `MaterialTheme.clipIt.success`. */
val MaterialTheme.clipIt: ClipItExtras
    @Composable
    @ReadOnlyComposable
    get() = LocalClipItExtras.current

private val ClipItShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

// Uses the system font.
private val ClipItTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
)

@Composable
fun ClipItTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalClipItExtras provides if (darkTheme) DarkExtras else LightExtras,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = ClipItTypography,
            shapes = ClipItShapes,
            content = content,
        )
    }
}
