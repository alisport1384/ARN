package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §4-1 سند نهایی استراتژی — «کندل مهم».
 *
 * سند (مرجع):
 *  • پایین‌ترین‌ها (BU): اولی نزولی و دومی صعودی؛ کندلی که **کمترین کف (`min(low(a, b))`)**
 *    دارد مهم است، و در تساوی کف، کندلی که **کمترین سقف (`min(high(a, b))`)** دارد.
 *  • بالاترین‌ها (BE): اولی صعودی و دومی نزولی؛ کندلی که **بیشترین سقف (`max(high(a, b))`)**
 *    دارد مهم است، و در تساوی سقف، کندلی که **کمترین کف (`min(low(a, b))`)** دارد.
 */
class ImportantCandleTest {

    private fun c(bi: Int, o: Double, h: Double, l: Double, cl: Double) =
        Candle(bi, bi.toLong() * 3600_000L, o, h, l, cl, 100.0)

    @Test
    fun `BU picks the candle with the SMALLER low as the doc requires`() {
        // a: کف ۱۰۰ (کمترین) · b: کف ۱۰۵ ⇒ کندل مهم باید a باشد
        val a = c(1, 110.0, 112.0, 100.0, 101.0)
        val b = c(2, 104.0, 108.0, 105.0, 107.0)
        val (_, lo, bi) = Engine(Settings()).pickLow(a, b)
        assertEquals(100.0, lo, 1e-9, "کمترین کف باید انتخاب شود")
        assertEquals(1, bi, "کندل مهم باید a باشد")
    }

    @Test
    fun `BU order of arguments does not change the result`() {
        val a = c(1, 110.0, 112.0, 100.0, 101.0)
        val b = c(2, 104.0, 108.0, 105.0, 107.0)
        val r1 = Engine(Settings()).pickLow(a, b)
        val r2 = Engine(Settings()).pickLow(b, a)
        assertEquals(r1.second, r2.second, 1e-9, "نتیجه نباید به ترتیب آرگومان‌ها وابسته باشد")
        assertEquals(r1.third, r2.third)
    }

    @Test
    fun `BU tie on the low is broken by the SMALLER high`() {
        // کف‌ها برابر (۱۰۰) · سقف a = ۱۲۰ · سقف b = ۱۱۰ ⇒ b (کمترین سقف) مهم است
        val a = c(1, 118.0, 120.0, 100.0, 101.0)
        val b = c(2, 108.0, 110.0, 100.0, 107.0)
        val (_, lo, bi) = Engine(Settings()).pickLow(a, b)
        assertEquals(100.0, lo, 1e-9)
        assertEquals(2, bi, "در تساوی کف، کندل با کمترین سقف مهم است")
    }

    @Test
    fun `BE picks the candle with the LARGER high`() {
        // a: سقف ۲۰۰ · b: سقف ۲۱۰ ⇒ b مهم است
        val a = c(1, 198.0, 200.0, 190.0, 199.0)
        val b = c(2, 205.0, 210.0, 202.0, 203.0)
        val (hi, _, bi) = Engine(Settings()).pickHigh(a, b)
        assertEquals(210.0, hi, 1e-9, "بیشترین سقف باید انتخاب شود")
        assertEquals(2, bi)
    }

    @Test
    fun `BE tie on the high is broken by the SMALLER low`() {
        val a = c(1, 198.0, 210.0, 190.0, 199.0)   // کف ۱۹۰ (کمترین)
        val b = c(2, 205.0, 210.0, 202.0, 203.0)   // کف ۲۰۲
        val (_, _, bi) = Engine(Settings()).pickHigh(a, b)
        assertEquals(1, bi, "در تساوی سقف، کندل با کمترین کف مهم است")
    }
}
