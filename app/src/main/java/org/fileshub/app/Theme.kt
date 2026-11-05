package org.fileshub.app

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

val ACC: List<Pair<String, Long>> = listOf(
    "Orange" to 0xFFFF8F4DL, "Blue" to 0xFF4DA3FFL, "Green" to 0xFF5CD68AL, "Purple" to 0xFFB388FFL,
    "Pink" to 0xFFFF7EB6L, "Teal" to 0xFF3DD6C6L, "Red" to 0xFFFF6B6BL, "Material You" to 0L
)

fun appScheme(ctx: Context): ColorScheme {
    val you = S.accent == ACC.lastIndex && Build.VERSION.SDK_INT >= 31
    val acc = Color(ACC[if (S.accent == ACC.lastIndex) 0 else S.accent.coerceIn(0, ACC.lastIndex)].second)
    if (S.dark) {
        val base = darkColorScheme(
            primary = acc, onPrimary = Color.Black, onBackground = Color.White, onSurface = Color.White,
            background = Color(0xFF151515), surface = Color(0xFF1E1E1E), surfaceContainer = Color(0xFF1E1E1E),
            secondaryContainer = acc.copy(alpha = .22f).compositeOver(Color(0xFF1E1E1E)), onSecondaryContainer = Color.White
        )
        return if (you) dynamicDarkColorScheme(ctx).copy(
            onBackground = Color.White, onSurface = Color.White, background = base.background, surface = base.surface, surfaceContainer = base.surfaceContainer
        ) else base
    }
    return if (you) dynamicLightColorScheme(ctx) else lightColorScheme(primary = acc, onPrimary = Color.White, surfaceContainer = Color(0xFFE4ECEB))
}
