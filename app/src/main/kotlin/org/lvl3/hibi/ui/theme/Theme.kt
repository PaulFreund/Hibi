package org.lvl3.hibi.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lvl3.hibi.R

/** Change typography, dimensions and shapes here. Palette: res/values/colors.xml. */
object HibiDesign {
    val pagePadding = 18.dp
    val cardRadius = 20.dp
    // System families: sans-serif, serif, monospace. Shared with TextView and WebView.
    const val bodyFamily = "sans-serif"
    const val headingFamily = "serif"
    const val bodySize = 16
    private fun family(value: String) = when (value) {
        "serif" -> FontFamily.Serif
        "monospace" -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }
    val bodyFont get() = family(bodyFamily)
    val headingFont get() = family(headingFamily)
}

@Composable
fun HibiTheme(content: @Composable () -> Unit) {
    val ink = colorResource(R.color.hibi_ink)
    val colors = lightColorScheme(
        primary = colorResource(R.color.hibi_accent), onPrimary = colorResource(R.color.hibi_surface),
        primaryContainer = colorResource(R.color.hibi_accent_soft), onPrimaryContainer = ink,
        background = colorResource(R.color.hibi_background), onBackground = ink,
        surface = colorResource(R.color.hibi_surface), onSurface = ink,
        surfaceVariant = colorResource(R.color.hibi_background), onSurfaceVariant = colorResource(R.color.hibi_muted),
        outline = colorResource(R.color.hibi_outline), outlineVariant = colorResource(R.color.hibi_outline_soft),
        secondary = colorResource(R.color.hibi_secondary), onSecondary = colorResource(R.color.hibi_surface),
        secondaryContainer = colorResource(R.color.hibi_secondary_soft), onSecondaryContainer = ink,
        tertiary = colorResource(R.color.hibi_accent), onTertiary = colorResource(R.color.hibi_surface),
        tertiaryContainer = colorResource(R.color.hibi_accent_soft), onTertiaryContainer = ink,
        error = colorResource(R.color.hibi_error), onError = colorResource(R.color.hibi_surface),
        errorContainer = colorResource(R.color.hibi_error_soft), onErrorContainer = ink,
        inverseSurface = colorResource(R.color.hibi_inverse_surface), inverseOnSurface = colorResource(R.color.hibi_inverse_ink),
        inversePrimary = colorResource(R.color.hibi_accent_soft), surfaceTint = colorResource(R.color.hibi_accent),
        scrim = colorResource(R.color.hibi_scrim),
        surfaceDim = colorResource(R.color.hibi_surface_highest), surfaceBright = colorResource(R.color.hibi_surface),
        surfaceContainerLowest = colorResource(R.color.hibi_surface), surfaceContainerLow = colorResource(R.color.hibi_surface_low),
        surfaceContainer = colorResource(R.color.hibi_background), surfaceContainerHigh = colorResource(R.color.hibi_surface_high),
        surfaceContainerHighest = colorResource(R.color.hibi_surface_highest),
    )
    val typography = Typography(
        headlineLarge = TextStyle(fontFamily = HibiDesign.headingFont, fontSize = 34.sp, lineHeight = 40.sp),
        headlineMedium = TextStyle(fontFamily = HibiDesign.headingFont, fontSize = 28.sp, lineHeight = 34.sp),
        titleLarge = TextStyle(fontFamily = HibiDesign.headingFont, fontSize = 23.sp, lineHeight = 30.sp),
        titleMedium = TextStyle(fontFamily = HibiDesign.bodyFont, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp),
        bodyLarge = TextStyle(fontFamily = HibiDesign.bodyFont, fontSize = HibiDesign.bodySize.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = HibiDesign.bodyFont, fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = TextStyle(fontFamily = HibiDesign.bodyFont, fontSize = 12.sp, lineHeight = 18.sp),
        labelSmall = TextStyle(fontFamily = HibiDesign.bodyFont, fontSize = 11.sp, lineHeight = 16.sp),
        labelMedium = TextStyle(fontFamily = HibiDesign.bodyFont, fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = TextStyle(fontFamily = HibiDesign.bodyFont, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    )
    MaterialTheme(colorScheme = colors, typography = typography,
        shapes = Shapes(medium = RoundedCornerShape(HibiDesign.cardRadius), large = RoundedCornerShape(24.dp)), content = content)
}
