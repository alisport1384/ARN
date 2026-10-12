package com.alisport.goldpin.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** سند §روند: HH/HL و LL/LH فقط یک‌بار تعریف می‌شوند و فقط با ابطال یا کلوز بیرون از آن‌ها برمی‌گردند؛ pin پاک‌شده دیگر در توالی حساب نمی‌شود. */
class TrendLabelInvariantTest {

    private fun walk(seed: Int, n: Int): List<Candle> {
        val r = Random(seed)
        var p = 2000.0
        return (0 until n).map { i ->
            val o = p
            val c = o + (r.nextDouble() - 0.5) * 6
            p = c
            Candle(i, 1_700_000_000_000L + i * 60_000L, o,
                maxOf(o, c) + r.nextDouble() * 2, minOf(o, c) - r.nextDouble() * 2, c, 100 + r.nextDouble() * 100)
        }
    }

    @Test
    fun `HH HL LL LH never change without being cleared first and cleared pins reset their sequence`() {
        val cfg = Settings().also { it.tfS = Tf.M1; it.tfM = Tf.M1; it.tf1 = Tf.M1; it.tf2 = Tf.M1 }
        for (seed in 1..2) {
            val cs = walk(seed, 400)
            val e = Engine(cfg)
            var pHH = Double.NaN; var pLL = Double.NaN; var pTrend = 0
            for (i in 1..cs.size) {
                e.runAll(cs.subList(0, i))
                if (e.trend == pTrend) {
                    if (!pHH.isNaN() && !e.HH.isNaN()) assertEquals(pHH, e.HH, 0.0, "HH بدون پاک شدن عوض شد (seed=$seed i=$i)")
                    if (!pLL.isNaN() && !e.LL.isNaN()) assertEquals(pLL, e.LL, 0.0, "LL بدون پاک شدن عوض شد (seed=$seed i=$i)")
                }
                assertTrue(!(e.buActive && !e.beActive && e.beSeq > e.buSeq), "beSeq کهنه بعد از پاک شدن pinBE (seed=$seed i=$i)")
                assertTrue(!(e.beActive && !e.buActive && e.buSeq > e.beSeq), "buSeq کهنه بعد از پاک شدن pinBU (seed=$seed i=$i)")
                pHH = e.HH; pLL = e.LL; pTrend = e.trend
            }
        }
    }
}
