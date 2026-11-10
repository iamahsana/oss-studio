package com.iamahsana.ossstudio.scene.model

/**
 * Core-owned, declarative text styling shared by the editor and LIVE compositor.
 * Values are parsed defensively because projects may contain settings written by
 * older app versions or by an unavailable extension.
 */
data class SceneTextStyle(
    val fontSize: Float = 48f,
    val color: String = "#FFFFFFFF",
    val fontFamily: String = "sans-serif",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val align: Alignment = Alignment.CENTER,
    val outlineColor: String? = null,
    val outlineWidth: Float = 0f,
    val shadowColor: String? = null,
    val shadowRadius: Float = 0f,
    val shadowDx: Float = 0f,
    val shadowDy: Float = 0f,
    val letterSpacing: Float = 0f,
    val lineSpacing: Float = 1.15f,
    val underline: Boolean = false,
    val strikethrough: Boolean = false
) {
    enum class Alignment { LEFT, CENTER, RIGHT }

    init {
        require(fontSize in 8f..512f)
        require(isColor(color))
        require(fontFamily in FONT_FAMILIES)
        require(outlineColor == null || isColor(outlineColor))
        require(outlineWidth in 0f..24f)
        require(shadowColor == null || isColor(shadowColor))
        require(shadowRadius in 0f..32f)
        require(shadowDx in -64f..64f && shadowDy in -64f..64f)
        require(letterSpacing in -0.1f..1f)
        require(lineSpacing in 1f..2.5f)
    }

    companion object {
        val FONT_FAMILIES = setOf(
            "sans-serif", "serif", "monospace", "sans-serif-condensed",
            "sans-serif-light", "sans-serif-medium", "sans-serif-black"
        )

        private fun isColor(value: String): Boolean =
            value.matches(Regex("^#(?:[A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$"))

        private fun Map<String, String>.float(key: String, fallback: Float): Float =
            this[key]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: fallback

        fun fromSettings(settings: Map<String, String>): SceneTextStyle {
            val fontFamily = settings["fontFamily"]?.takeIf { it in FONT_FAMILIES } ?: "sans-serif"
            val alignment = when (settings["align"]) {
                "left" -> Alignment.LEFT
                "right" -> Alignment.RIGHT
                else -> Alignment.CENTER
            }
            val color = settings["color"]?.takeIf(::isColor) ?: "#FFFFFFFF"
            val outlineColor = settings["outlineColor"]?.takeIf(::isColor)
            val shadowColor = settings["shadowColor"]?.takeIf(::isColor)
            return SceneTextStyle(
                fontSize = settings.float("fontSize", 48f).coerceIn(8f, 512f),
                color = color,
                fontFamily = fontFamily,
                bold = settings["bold"] == "true",
                italic = settings["italic"] == "true",
                align = alignment,
                outlineColor = outlineColor,
                outlineWidth = settings.float("outlineWidth", 0f).coerceIn(0f, 24f),
                shadowColor = shadowColor,
                shadowRadius = settings.float("shadowRadius", 0f).coerceIn(0f, 32f),
                shadowDx = settings.float("shadowDx", 0f).coerceIn(-64f, 64f),
                shadowDy = settings.float("shadowDy", 0f).coerceIn(-64f, 64f),
                letterSpacing = settings.float("letterSpacing", 0f).coerceIn(-0.1f, 1f),
                lineSpacing = settings.float("lineSpacing", 1.15f).coerceIn(1f, 2.5f),
                underline = settings["underline"] == "true",
                strikethrough = settings["strikethrough"] == "true"
            )
        }
    }
}
