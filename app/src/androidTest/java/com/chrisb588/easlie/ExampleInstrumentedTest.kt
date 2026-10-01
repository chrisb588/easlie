package com.chrisb588.easlie

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.chrisb588.easlie", appContext.packageName)
    }

    @Test
    fun manifestDeclaresFloatingBoardContract() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo = appContext.packageManager.getPackageInfo(
            appContext.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()

        assertTrue(permissions.contains("android.permission.SYSTEM_ALERT_WINDOW"))
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))

        val service = packageInfo.services.orEmpty().single {
            it.name == FloatingBoardService::class.java.name
        }
        assertFalse(service.exported)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                service.foregroundServiceType
            )
        }
    }
}
