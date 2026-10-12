package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AggHistoryTest {
    @Test
    fun `first available candle is closed at the next timeframe boundary`() {
        val agg = Agg()
        agg.step(false, 0, 60_000L, 10.0, 12.0, 9.0, 11.0, 5.0)
        assertFalse(agg.closedNow)
        assertEquals(1, agg.periods)

        agg.step(true, 1, 120_000L, 11.0, 13.0, 10.0, 12.0, 7.0)
        assertTrue(agg.closedNow)
        assertEquals(0, agg.cl!!.bi)
        assertEquals(60_000L, agg.cl!!.t)
        assertEquals(5.0, agg.cl!!.v, 1e-9)
    }
}
