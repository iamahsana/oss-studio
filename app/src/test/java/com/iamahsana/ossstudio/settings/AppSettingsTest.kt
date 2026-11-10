package com.iamahsana.ossstudio.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test
    fun unknownThemeValueFallsBackToSystem() {
        assertEquals(OssThemeMode.SYSTEM, OssThemeMode.fromStorage("unexpected"))
    }

    @Test
    fun unknownAccentValueFallsBackToBlue() {
        assertEquals(AppAccent.BLUE, AppAccent.fromStorage("unexpected"))
    }

    @Test
    fun allAccentPresetsHaveDistinctColors() {
        assertEquals(AppAccent.entries.size, AppAccent.entries.map { it.argb }.distinct().size)
    }
}
