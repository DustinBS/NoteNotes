package com.notenotes.util

import androidx.compose.ui.graphics.Color

object ColorUtils {
    const val DEFAULT_LIGHTEN_AMOUNT = 0.36f

    fun lightenColor(c: Color, amount: Float = DEFAULT_LIGHTEN_AMOUNT): Color {
        return Color(
            red = (c.red + (1f - c.red) * amount).coerceIn(0f, 1f),
            green = (c.green + (1f - c.green) * amount).coerceIn(0f, 1f),
            blue = (c.blue + (1f - c.blue) * amount).coerceIn(0f, 1f),
            alpha = c.alpha
        )
    }
}
