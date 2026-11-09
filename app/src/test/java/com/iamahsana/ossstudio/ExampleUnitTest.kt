package com.iamahsana.ossstudio

import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun appVersionStartsAtInitialFoundation() {
        assertEquals("0.1.0", BuildConfig.VERSION_NAME)
    }
}
