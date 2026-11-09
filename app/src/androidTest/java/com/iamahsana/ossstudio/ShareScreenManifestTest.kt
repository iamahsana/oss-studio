package com.iamahsana.ossstudio

import android.content.ComponentName
import android.content.pm.ServiceInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.iamahsana.ossstudio.scene.source.screen.ShareScreenSessionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ShareScreenManifestTest {

    @Test
    fun projectionServiceIsPrivateAndUsesMediaProjectionForegroundType() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context, ShareScreenSessionService::class.java)
        @Suppress("DEPRECATION")
        val serviceInfo = context.packageManager.getServiceInfo(component, 0)

        assertFalse("Projection service must not be exported", serviceInfo.exported)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            serviceInfo.foregroundServiceType
        )
    }
}
