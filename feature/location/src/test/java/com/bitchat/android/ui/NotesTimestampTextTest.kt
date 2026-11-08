package com.bitchat.android.ui

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins a note row's timestamp text (inventory N14, Q-N4, Q-N12) against a fixed clock. */
class NotesTimestampTextTest {

    private val now = 1_700_000_000L // 2023-11-14 22:13:20 UTC
    private val utc = TimeZone.getTimeZone("UTC")

    private fun text(createdAt: Long, nowSeconds: Long = now, timeZone: TimeZone = utc) =
        notesTimestampText(createdAt.toInt(), nowSeconds, Locale.US, timeZone)

    @Test
    fun underAMinute_showsNothing() {
        assertEquals("", text(now))
        assertEquals("", text(now - 59))
    }

    @Test
    fun futureNote_showsNothing_QN12() {
        assertEquals("", text(now + 120))
    }

    @Test
    fun underAnHour_showsMinutes() {
        assertEquals("1m ago", text(now - 60))
        assertEquals("59m ago", text(now - 3_599))
    }

    @Test
    fun underADay_showsHours() {
        assertEquals("1h ago", text(now - 3_600))
        assertEquals("23h ago", text(now - 86_399))
    }

    @Test
    fun underSevenCalendarDays_showsDays() {
        assertEquals("1d ago", text(now - 86_400))
        assertEquals("6d ago", text(now - 6 * 86_400))
    }

    @Test
    fun sevenCalendarDays_showsDate_evenUnderSevenElapsedDays() {
        // 2023-11-07 23:00 UTC: 6.97 days ago, but seven calendar days back.
        assertEquals("Nov 7", text(1_699_398_000))
    }

    @Test
    fun otherYear_showsDateWithYear() {
        // 2022-12-31 12:00 UTC
        assertEquals("Dec 31, 2022", text(1_672_488_000))
    }

    @Test
    fun acrossNewYear_usesElapsedDays() {
        // 2023-12-30 00:00 UTC seen from 2024-01-02 00:00 UTC.
        assertEquals("3d ago", text(1_703_894_400, nowSeconds = 1_704_153_600))
    }

    @Test
    fun calendarDaysFollowTheTimeZone() {
        // 2023-11-08 10:00 UTC: six calendar days back in UTC. At UTC+5 "now" is already
        // 2023-11-15 while the note is still 2023-11-08, so it is seven there.
        val plusFive = TimeZone.getTimeZone("GMT+05:00")
        assertEquals("6d ago", text(1_699_437_600))
        assertEquals("Nov 8", text(1_699_437_600, timeZone = plusFive))
    }
}
