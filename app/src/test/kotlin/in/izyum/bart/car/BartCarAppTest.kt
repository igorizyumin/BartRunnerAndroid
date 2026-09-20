package `in`.izyum.bart.car

import org.junit.Assert.assertNotNull
import org.junit.Test

class BartCarAppTest {
    @Test
    fun carPreferencesIsNotNull() {
        assertNotNull(CarPreferences)
    }

    @Test
    fun carNotificationHelperIsNotNull() {
        assertNotNull(CarNotificationHelper)
    }
}

