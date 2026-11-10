package com.iamahsana.ossstudio.scene.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTextStyleTest {
    @Test
    fun parsesCompleteTypographySettings() {
        val style = SceneTextStyle.fromSettings(
            mapOf(
                "fontSize" to "72",
                "color" to "#FF12ABEF",
                "fontFamily" to "serif",
                "bold" to "true",
                "italic" to "true",
                "align" to "right",
                "outlineColor" to "#FF000000",
                "outlineWidth" to "4",
                "shadowColor" to "#99000000",
                "shadowRadius" to "8",
                "shadowDx" to "2",
                "shadowDy" to "3",
                "letterSpacing" to "0.2",
                "lineSpacing" to "1.5",
                "underline" to "true",
                "strikethrough" to "true"
            )
        )

        assertEquals(72f, style.fontSize)
        assertEquals("#FF12ABEF", style.color)
        assertEquals("serif", style.fontFamily)
        assertTrue(style.bold)
        assertTrue(style.italic)
        assertEquals(SceneTextStyle.Alignment.RIGHT, style.align)
        assertEquals(4f, style.outlineWidth)
        assertEquals(8f, style.shadowRadius)
        assertEquals(0.2f, style.letterSpacing)
        assertEquals(1.5f, style.lineSpacing)
        assertTrue(style.underline)
        assertTrue(style.strikethrough)
    }

    @Test
    fun clampsOutOfRangeNumbersAndFallsBackFromUnknownValues() {
        val style = SceneTextStyle.fromSettings(
            mapOf(
                "fontSize" to "9999",
                "fontFamily" to "untrusted-font-name",
                "color" to "not-a-color",
                "outlineWidth" to "-8",
                "shadowRadius" to "999",
                "letterSpacing" to "NaN",
                "lineSpacing" to "0.1",
                "align" to "diagonal"
            )
        )

        assertEquals(512f, style.fontSize)
        assertEquals("sans-serif", style.fontFamily)
        assertEquals("#FFFFFFFF", style.color)
        assertEquals(0f, style.outlineWidth)
        assertEquals(32f, style.shadowRadius)
        assertEquals(0f, style.letterSpacing)
        assertEquals(1f, style.lineSpacing)
        assertEquals(SceneTextStyle.Alignment.CENTER, style.align)
        assertFalse(style.bold)
    }

    @Test
    fun rejectsInvalidDirectStyleConstruction() {
        val error = runCatching { SceneTextStyle(fontSize = -1f) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
