package com.mythos.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun MythosTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = MythosColors.Ink,
            onPrimary = Color.White,
            background = MythosColors.Background,
            onBackground = MythosColors.Ink,
            surface = MythosColors.Card,
            onSurface = MythosColors.Ink,
            surfaceVariant = Color(0xFFF1EFEA),
            onSurfaceVariant = MythosColors.Hint,
            outline = MythosColors.Border,
            error = Color(0xFFB3261E),
        ),
        // Semua gaya teks Material memakai satu keluarga font pilihan; teks tanpa fontFamily juga mewarisinya lewat ProvideTextStyle,
        // jadi tidak ada lagi teks yang jatuh ke font sistem dan "bercampur".
        typography = MaterialTheme.typography.withFamily(FontTheme.family),
        content = { ProvideTextStyle(TextStyle(fontFamily = FontTheme.family), content) }
    )
}

private fun Typography.withFamily(f: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f), displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f), headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f), titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f), bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f), labelSmall = labelSmall.copy(fontFamily = f),
)
