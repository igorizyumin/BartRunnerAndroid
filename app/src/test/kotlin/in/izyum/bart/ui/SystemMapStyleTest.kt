package `in`.izyum.bart.ui

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemMapStyleTest {
    @Test
    fun weekdayDaytimeUsesDayMap() {
        assertEquals(SystemMapStyle.DAY, styleFor(Calendar.MONDAY, 3))
        assertEquals(SystemMapStyle.DAY, styleFor(Calendar.FRIDAY, 20))
    }

    @Test
    fun weekdayNightUsesNightMapAtBothBoundaries() {
        assertEquals(SystemMapStyle.NIGHT, styleFor(Calendar.MONDAY, 2))
        assertEquals(SystemMapStyle.NIGHT, styleFor(Calendar.FRIDAY, 21))
    }

    @Test
    fun weekendsFollowTheSameSchedule() {
        assertEquals(SystemMapStyle.DAY, styleFor(Calendar.SATURDAY, 12))
        assertEquals(SystemMapStyle.NIGHT, styleFor(Calendar.SUNDAY, 2))
    }

    private fun styleFor(dayOfWeek: Int, hour: Int): SystemMapStyle {
        return defaultSystemMapStyle(Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, hour)
        })
    }
}
