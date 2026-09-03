/*
 * Copyright 2023 Miklos Vajna
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for functions in GraphActivity. */
class GraphsActivityUnitTest {
    private val data = listOf(
        0 to 0f,
        1 to 1f,
        2 to 2f,
        3 to 3f,
        4 to 4f
    )

    @Test
    fun `test cumulativeAverage()`() {
        val expected = listOf(
            0 to 0f,
            1 to 0.5f,
            2 to 1f,
            3 to 1.5f,
            4 to 2f
        )
        assertEquals(expected, data.cumulativeAverage())
    }

    @Test
    fun `test cumulativeSum()`() {
        val expected = listOf(
            0 to 0f,
            1 to 1f,
            2 to 3f,
            3 to 6f,
            4 to 10f
        )
        assertEquals(expected, data.cumulativeSum())
    }

    @Test
    fun `test stripTime()`() {
        val dateWithDate = Calendar.getInstance().apply {
            set(2034, 7, 7, 7, 7, 7)
            set(Calendar.MILLISECOND, 0)
        }.time
        val dateWithoutTime = Calendar.getInstance().apply {
            set(2034, 7, 7, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

        assertEquals(dateWithoutTime, dateWithDate.stripTime())
        assertEquals(dateWithoutTime.time, dateWithDate.time.stripTime())
    }

    @Test
    fun `test variance()`() {
        // The formulas are written explicitly
        // so the test does not have to care about the float precision
        val expected = listOf(
            0 to 0f,
            1 to (1f * 1f) / 2 - (1f).div(2).let { it * it },
            2 to (1f * 1f + 2f * 2f) / 3 - (1f + 2f).div(3).let { it * it },
            3 to (1f * 1f + 2f * 2f + 3f * 3f) / 4 - (1f + 2f + 3f).div(4).let { it * it },
            4 to (1f * 1f + 2f * 2f + 3f * 3f + 4f * 4f) / 5 -
                (1f + 2f + 3f + 4f).div(5).let { it * it },
        )
        assertEquals(expected, data.asSequence().cumulativeVariance().toList())
    }

    @Test
    fun `test rolling7dDebt()`() {
        val daysData = listOf(
            1L to 8f,
            2L to 7f,
            3L to 6f,
            4L to 9f,
            5L to 8f,
            6L to 8f,
            7L to 8f,
            8L to 10f
        )
        // ideal = 8f
        // day 1: (8) - (1*8) = 0
        // day 2: (8+7) - (2*8) = -1
        // day 3: (8+7+6) - (3*8) = -3
        // day 4: (8+7+6+9) - (4*8) = -2
        // day 5: (8+7+6+9+8) - (5*8) = -2
        // day 6: (8+7+6+9+8+8) - (6*8) = -2
        // day 7: (8+7+6+9+8+8+8) - (7*8) = -2
        // day 8: (7+6+9+8+8+8+10) - (7*8) = 56 - 56 = 0
        val expected = listOf(
            1L to 0f,
            2L to -1f,
            3L to -3f,
            4L to -2f,
            5L to -2f,
            6L to -2f,
            7L to -2f,
            8L to 0f
        )
        assertEquals(expected, daysData.rolling7dDebt(8f))
    }

    @Test
    fun `test FloatAxisFormatter`() {
        val formatter = FloatAxisFormatter()
        assertEquals("7.5", formatter.getFormattedValue(7.5f))
        assertEquals("0", formatter.getFormattedValue(0f))
        assertEquals("-2.3", formatter.getFormattedValue(-2.3f))
    }

    @Test
    fun `test TimeAxisFormatter`() {
        val formatter = TimeAxisFormatter()
        assertEquals("00:00", formatter.getFormattedValue(0f))
        assertEquals("01:30", formatter.getFormattedValue((90 * 60 * 1000).toFloat()))
    }
}
