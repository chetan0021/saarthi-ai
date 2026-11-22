package com.bharatai.learning.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Indigo = Color(0xFF4338CA)
val Saffron = Color(0xFFF59E0B)
val Leaf = Color(0xFF059669)
val Rose = Color(0xFFE11D48)
val Sky = Color(0xFF0284C7)

private val Light = lightColorScheme(
    primary = Indigo, onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF), onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Saffron, onSecondary = Color(0xFF231400),
    secondaryContainer = Color(0xFFFEF3C7), onSecondaryContainer = Color(0xFF451A03),
    tertiary = Leaf, tertiaryContainer = Color(0xFFD1FAE5), onTertiaryContainer = Color(0xFF022C22),
    background = Color(0xFFF8F7FC), surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F0F7), onSurfaceVariant = Color(0xFF4B4A5A),
    outline = Color(0xFFD4D3DE), error = Rose,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA5B4FC), onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF312E81), onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFFFCD34D), secondaryContainer = Color(0xFF78350F), onSecondaryContainer = Color(0xFFFEF3C7),
    tertiary = Color(0xFF6EE7B7), tertiaryContainer = Color(0xFF064E3B), onTertiaryContainer = Color(0xFFD1FAE5),
    background = Color(0xFF0F0E17), surface = Color(0xFF17161F),
    surfaceVariant = Color(0xFF24232E), onSurfaceVariant = Color(0xFFC9C8D6), outline = Color(0xFF3A3946),
    error = Color(0xFFFDA4AF),
)

private val Type = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun SaarthiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
}

/** green >= 0.75, amber >= 0.5, red below */
fun masteryColor(m: Double): Color = when {
    m >= 0.75 -> Leaf
    m >= 0.5 -> Saffron
    else -> Rose
}
