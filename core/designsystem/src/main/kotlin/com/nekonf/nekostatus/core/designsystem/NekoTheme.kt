package com.nekonf.nekostatus.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF161719)
private val Paper = Color(0xFFF7F8FA)
private val PureWhite = Color(0xFFFFFFFF)
private val Aqua = Color(0xFF007D8A)
private val Mint = Color(0xFF16856B)
private val Amber = Color(0xFF9B6200)
private val Rose = Color(0xFFBA1A1A)

@Immutable
data class NekoStatusColors(
    val primary: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val neutral: Color,
)

val LocalNekoStatusColors =
    staticCompositionLocalOf {
        NekoStatusColors(
            primary = Aqua,
            success = Mint,
            warning = Amber,
            error = Rose,
            neutral = Color(0xFF5D6065),
        )
    }

private val LightStatusColors =
    NekoStatusColors(
        primary = Aqua,
        success = Mint,
        warning = Amber,
        error = Rose,
        neutral = Color(0xFF5D6065),
    )

private val DarkStatusColors =
    NekoStatusColors(
        primary = Color(0xFF73D8E2),
        success = Color(0xFF7CD7BA),
        warning = Color(0xFFFFB951),
        error = Color(0xFFFFB4AB),
        neutral = Color(0xFFC7C6CA),
    )

private val LightColors =
    lightColorScheme(
        primary = Aqua,
        onPrimary = PureWhite,
        primaryContainer = Color(0xFFD2F4F7),
        onPrimaryContainer = Color(0xFF00363C),
        secondary = Mint,
        onSecondary = PureWhite,
        secondaryContainer = Color(0xFFB8F2DC),
        onSecondaryContainer = Color(0xFF003A2E),
        tertiary = Amber,
        onTertiary = PureWhite,
        tertiaryContainer = Color(0xFFFFE2B3),
        onTertiaryContainer = Color(0xFF301400),
        error = Rose,
        onError = PureWhite,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Paper,
        onBackground = Ink,
        surface = PureWhite,
        onSurface = Ink,
        surfaceVariant = Color(0xFFE9EAEC),
        onSurfaceVariant = Color(0xFF5D6065),
        outline = Color(0xFFC6C8CC),
        outlineVariant = Color(0xFFE0E2E6),
        surfaceDim = Color(0xFFDDE0E3),
        surfaceBright = PureWhite,
        surfaceContainerLowest = PureWhite,
        surfaceContainerLow = Color(0xFFF1F3F5),
        surfaceContainer = Color(0xFFECEEF0),
        surfaceContainerHigh = Color(0xFFE7E9EB),
        surfaceContainerHighest = Color(0xFFE1E3E6),
        inverseSurface = Ink,
        inverseOnSurface = PureWhite,
        inversePrimary = Color(0xFF73D8E2),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF73D8E2),
        onPrimary = Color(0xFF00363C),
        primaryContainer = Color(0xFF004F58),
        onPrimaryContainer = Color(0xFFA6EEF3),
        secondary = Color(0xFF7CD7BA),
        onSecondary = Color(0xFF00382B),
        secondaryContainer = Color(0xFF00513F),
        onSecondaryContainer = Color(0xFF98F4D6),
        tertiary = Color(0xFFFFB951),
        onTertiary = Color(0xFF452B00),
        tertiaryContainer = Color(0xFF633F00),
        onTertiaryContainer = Color(0xFFFFEEDC),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF111214),
        onBackground = Color(0xFFE5E2E6),
        surface = Color(0xFF1A1B1E),
        onSurface = Color(0xFFE5E2E6),
        surfaceVariant = Color(0xFF292B2F),
        onSurfaceVariant = Color(0xFFC7C6CA),
        outline = Color(0xFF484A4F),
        outlineVariant = Color(0xFF303237),
        surfaceDim = Color(0xFF111214),
        surfaceBright = Color(0xFF38393D),
        surfaceContainerLowest = Color(0xFF0D0E10),
        surfaceContainerLow = Color(0xFF16171A),
        surfaceContainer = Color(0xFF1A1B1E),
        surfaceContainerHigh = Color(0xFF202226),
        surfaceContainerHighest = Color(0xFF292B2F),
        inverseSurface = Color(0xFFE5E2E6),
        inverseOnSurface = Color(0xFF2F3033),
        inversePrimary = Aqua,
    )

private val NekoTypography =
    androidx.compose.material3.Typography(
        displaySmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 36.sp,
                lineHeight = 42.sp,
                letterSpacing = 0.sp,
            ),
        headlineLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 30.sp,
                lineHeight = 36.sp,
                letterSpacing = 0.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 30.sp,
                letterSpacing = 0.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 26.sp,
                letterSpacing = 0.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                letterSpacing = 0.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.sp,
            ),
    )

@Composable
fun NekoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
            darkTheme -> DarkColors
            else -> LightColors
        }
    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors
    CompositionLocalProvider(LocalNekoStatusColors provides statusColors) {
        MaterialTheme(colorScheme = colors, typography = NekoTypography, content = content)
    }
}
