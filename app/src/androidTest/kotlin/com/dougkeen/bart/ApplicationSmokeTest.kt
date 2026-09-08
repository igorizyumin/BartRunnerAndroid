package com.dougkeen.bart

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApplicationSmokeTest {
    @Test
    fun applicationExposesTheLauncherActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.dougkeen.bart", context.packageName)
        val launcher: Intent? = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull(launcher)
    }
}
