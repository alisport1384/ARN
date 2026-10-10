package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §۲-۴ — قاعدهٔ «ردیف نخست بزرگ‌ترین است» (افزودهٔ ۱٫۳٫۸، تصمیم کارفرما).
 *
 * اگر در پروفایل فیکس‌رنج، **ردیف نخست در جهت پیمایش** (لبهٔ کندل) از همان ابتدا
 * بزرگ‌تر از ردیف بعدیِ خودش باشد، یعنی قبلش ردیف بزرگ‌تری وجود ندارد؛ پس همان ردیف
 * قله است و **لبهٔ کندل** شروع ناحیه می‌شود:
 *   · کندل نزولی → **سقف کندل** شروع ناحیه
 *   · کندل صعودی → **کف کندل** شروع ناحیه
 *
 * پیش از این اصلاح، حلقهٔ قله‌یابی یک گام هم جلو نمی‌رفت (`j == i`) و `pk = -1`
 * می‌شد، یعنی **هیچ ناحیه‌ای** ساخته نمی‌شد.
 */
class ZoneFirstRowTest {

    private fun prof(rv: DoubleArray) =
        Profile(rv, 1000.0, 10.0, Double.NaN, Double.NaN, Double.NaN)

    @Test
    fun `bear - first row is the biggest, zone starts at the candle HIGH`() {
        // نزولی: قلهٔ ردیف ۴ است؛ درهٔ هم‌حجمِ ردیف‌های ۲ و ۳ پیش از لبهٔ پایین.
        // افتی که تا لبه ادامه پیدا کند دیگر درهٔ معتبر نیست.
        val rv = doubleArrayOf(10.0, 20.0, 30.0, 30.0, 900.0)
        val z = VolumeProfile.zones(prof(rv), 5, bull = false, cClose = 900.0, minRows = 1)
        assertEquals(1, z.size, "باید یک ناحیه ساخته شود")
        // سقف ناحیه = لبهٔ کندل = lo + rows*step = 1000 + 5*10 = 1050
        assertEquals(1050.0, z[0].top, 1e-9, "سقف ناحیه باید سقف کندل باشد")
        assertEquals(4, z[0].hiIdx, "ردیف قله باید ردیف نخست (سقف) باشد")
    }

    @Test
    fun `bull - first row is the biggest, zone starts at the candle LOW`() {
        // صعودی: ردیف ۰ بزرگ‌ترین است؛ درهٔ هم‌حجم پیش از لبهٔ بالایی قرار دارد.
        val rv = doubleArrayOf(900.0, 40.0, 30.0, 30.0, 20.0)
        val z = VolumeProfile.zones(prof(rv), 5, bull = true, cClose = 1100.0, minRows = 1)
        assertEquals(1, z.size, "باید یک ناحیه ساخته شود (قرینهٔ حالت نزولی)")
        // کف ناحیه = لبهٔ کندل = lo = 1000
        assertEquals(1000.0, z[0].bot, 1e-9, "کف ناحیه باید کف کندل باشد")
        assertEquals(0, z[0].loIdx, "ردیف قله باید ردیف نخست (کف) باشد")
    }

    @Test
    fun `the rule applies ONLY on the first row, not mid-table`() {
        // ردیف ۲ یک قلهٔ میانی است (۹۰۰) ولی ردیف نخست (ردیف ۴ = ۵۰۰) بزرگ‌ترین نیست.
        // اینجا باید همان الگوریتم معمولی کار کند، نه قاعدهٔ ردیف نخست.
        val rv = doubleArrayOf(10.0, 20.0, 900.0, 40.0, 500.0)
        val z = VolumeProfile.zones(prof(rv), 5, bull = false, cClose = 900.0, minRows = 1)
        assertEquals(1, z.size)
        assertEquals(4, z[0].hiIdx, "قله باید ردیف ۴ باشد (بزرگ‌ترین در پیمایش از سقف)")
    }

    @Test
    fun `decline reaching the table edge is not a valid valley`() {
        val rv = doubleArrayOf(10.0, 20.0, 30.0, 40.0, 900.0)
        val z = VolumeProfile.zones(prof(rv), 5, bull = false, cClose = 0.0, minRows = 1)
        assertEquals(0, z.size, "افت تا لبهٔ جدول درهٔ معتبر نیست")
    }

    @Test
    fun `rejected candidates also count toward the two-zone limit`() {
        val rv = doubleArrayOf(10.0, 20.0, 50.0, 30.0, 60.0, 40.0, 90.0, 70.0, 100.0)
        val rejected = VolumeProfile.zones(prof(rv), 9, bull = false, cClose = 2000.0, minRows = 1,
            wantRejected = true)
        assertEquals(2, rejected.size, "حداکثر دو نامزد، حتی اگر هر دو به‌علت کلوز رد شوند")
    }

    @Test
    fun `flat profile still yields no zone`() {
        // کنترل منفی: اگر ردیف نخست بزرگ‌تر از بعدی **نباشد**، ناحیه‌ای ساخته نمی‌شود.
        val rv = doubleArrayOf(100.0, 100.0, 100.0, 100.0, 100.0)
        val z = VolumeProfile.zones(prof(rv), 5, bull = false, cClose = 900.0, minRows = 1)
        assertEquals(0, z.size, "پروفایل تخت نباید ناحیه بدهد")
    }

    @Test
    fun `close inside the zone still rejects it`() {
        // شرط اعتبار دست‌نخورده: در نزولی باید zBot > close باشد.
        val rv = doubleArrayOf(10.0, 20.0, 30.0, 30.0, 900.0)
        val ok = VolumeProfile.zones(prof(rv), 5, bull = false, cClose = 900.0, minRows = 1)
        val rej = VolumeProfile.zones(prof(rv), 5, bull = false, cClose = 1040.0, minRows = 1,
            wantRejected = true)
        assertEquals(1, ok.size)
        assertEquals(1, rej.size, "کلوز داخل ناحیه ⇒ ردشده")
        assertTrue(rej[0].bot < 1010.0)
    }
}
