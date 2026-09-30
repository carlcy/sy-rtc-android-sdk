package com.sy.rtc.sdk

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScreenCaptureServiceTest {

    @Test
    fun foregroundServiceIsRequiredFromAndroid10() {
        assertFalse(ScreenCaptureService.isRequired(28))
        assertTrue(ScreenCaptureService.isRequired(29))
        assertTrue(ScreenCaptureService.isRequired(35))
        assertEquals(0, ScreenCaptureService.foregroundServiceType(28))
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            ScreenCaptureService.foregroundServiceType(34)
        )
        assertTrue(ScreenCaptureService.enabled)
    }

    @Test
    fun libraryManifestDeclaresServiceAndPermissions() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("../src/main/AndroidManifest.xml")
        ).first { it.exists() }.readText()
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE\""))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"))
        assertTrue(manifest.contains("com.sy.rtc.sdk.ScreenCaptureService"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"mediaProjection\""))
        assertTrue(manifest.contains("android:exported=\"false\""))
    }
}
