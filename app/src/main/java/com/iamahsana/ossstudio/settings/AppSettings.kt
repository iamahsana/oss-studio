package com.iamahsana.ossstudio.settings

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

enum class OssThemeMode(val label: String) {
    SYSTEM("Sistem"),
    LIGHT("Terang"),
    DARK("Gelap");

    companion object {
        fun fromStorage(value: String?): OssThemeMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

enum class AppAccent(val label: String, val argb: Long) {
    BLUE("Biru", 0xFF2563EB),
    GREEN("Hijau", 0xFF16804A),
    PURPLE("Ungu", 0xFF7048E8),
    ORANGE("Oranye", 0xFFC45A18);

    val color: Color get() = Color(argb)

    companion object {
        fun fromStorage(value: String?): AppAccent =
            entries.firstOrNull { it.name == value } ?: BLUE
    }
}

data class AppSettings(
    val themeMode: OssThemeMode = OssThemeMode.SYSTEM,
    val accent: AppAccent = AppAccent.BLUE
)

fun ossColorScheme(darkTheme: Boolean, accent: AppAccent): ColorScheme {
    val primary = accent.color
    return if (darkTheme) {
        darkColorScheme(primary = primary, secondary = primary, tertiary = primary)
    } else {
        lightColorScheme(primary = primary, secondary = primary, tertiary = primary)
    }
}
