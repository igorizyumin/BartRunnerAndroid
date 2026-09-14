package `in`.izyum.bart.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationTextFormatterTest {
    @Test
    fun formatsMinutesAndSecondsWithoutAnHour() {
        assertEquals("0:00", DurationTextFormatter.clock(0))
        assertEquals("5:03", DurationTextFormatter.clock(5 * 60L + 3))
        assertEquals("59:59", DurationTextFormatter.clock(59 * 60L + 59))
    }

    @Test
    fun formatsHoursMinutesAndSecondsWhenNeeded() {
        assertEquals("1:00:00", DurationTextFormatter.clock(60 * 60L))
        assertEquals("1:05:09", DurationTextFormatter.clock(60 * 60L + 5 * 60L + 9))
    }
}
