package com.alisport.goldpin.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * آزمون‌های بازگشتی برای باگ‌هایی که در نسخهٔ ۱٫۳ پیدا و رفع شدند.
 * هر آزمون روی کد قدیمی **شکست می‌خورد** و روی کد اصلاح‌شده پاس می‌شود.
 */
class RegressionTest {

    /** دادهٔ مصنوعی قطعی (همان random walk آزمون Store) */
    private fun synth(n: Int, seed: Int = 12345): List<Candle> {
        val r = Random(seed)
        val out = ArrayList<Candle>(n)
        var px = 2000.0
        var t = 1_700_000_000_000L
        for (i in 0 until n) {
            val drift = r.nextDouble(-0.9, 0.9)
            val o = px
            val c = px + drift
            val h = maxOf(o, c) + r.nextDouble(0.0, 0.6)
            val l = minOf(o, c) - r.nextDouble(0.0, 0.6)
            val v = 50.0 + r.nextDouble(0.0, 400.0) + if (i % 97 == 0) 900.0 else 0.0
            out.add(Candle(i, t, o, h, l, c, v))
            px = c
            t += 60_000
        }
        return out
    }

    private fun wire(cfg: Settings): Pair<Engine, PaperBroker> {
        val e = Engine(cfg)
        val b = PaperBroker(cfg)
        e.broker = b; b.engine = e
        e.barCommitHook = { cd -> b.onBar(cd, live = false) }
        return e to b
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ۱) پیش‌نمایش کندل باز نباید وضعیت موتور (به‌ویژه حجم تجمیع‌شده) را تغییر دهد
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `repeated live preview of the open candle must not change engine state`() {
        val candles = synth(3_000)

        // مرجع: یک پاس تمیز که همهٔ کندل‌ها بسته‌اند
        val (eRef, bRef) = wire(Settings())
        eRef.feed(candles, lastIsClosed = true)

        // لایو: آخرین کندل ۲۰ بار «پیش‌نمایش» می‌شود (۲۰ تیک فید روی همان کندل باز)
        val (eLive, bLive) = wire(Settings())
        eLive.feed(candles.subList(0, candles.size - 1), lastIsClosed = true)
        repeat(20) { eLive.feed(candles, lastIsClosed = false) }
        eLive.feed(candles, lastIsClosed = true)

        val vRef = eRef.aggS.cur?.v ?: Double.NaN
        val vLive = eLive.aggS.cur?.v ?: Double.NaN
        assertEquals(vRef, vLive, 1e-9,
            "حجم تجمیع‌شدهٔ کندل ساختار با تیک‌های زنده باد شد (تمیز=$vRef لایو=$vLive)")

        assertEquals(eRef.aggM.cur?.v ?: -1.0, eLive.aggM.cur?.v ?: -1.0, 1e-9, "حجم کندل میانی")
        assertEquals(eRef.agg1.cur?.v ?: -1.0, eLive.agg1.cur?.v ?: -1.0, 1e-9, "حجم کندل تریگر۱")
        assertEquals(eRef.prevT1Vol, eLive.prevT1Vol, 1e-9,
            "prevT1Vol خراب شد → شرط «کندل ولوم کم» دیگر قابل اتکا نیست")

        assertEquals(eRef.cnt.setup, eLive.cnt.setup, "تعداد ستاپ")
        assertEquals(eRef.cnt.lv, eLive.cnt.lv, "تعداد ناحیهٔ ولوم کم")
        assertEquals(eRef.cnt.hv, eLive.cnt.hv, "تعداد ناحیهٔ ولوم زیاد")
        assertEquals(eRef.cnt.entry, eLive.cnt.entry, "تعداد ورود")
        assertEquals(bRef.trades.size, bLive.trades.size, "تعداد معاملات")
        assertEquals(bRef.balance, bLive.balance, 1e-6, "موجودی نهایی")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ۲) سقف «تعداد باکس روی چارت» نباید باکس‌های زنده را بی‌دلیل نابود کند
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `zone box cap only hides the oldest visible boxes, never live ones early`() {
        val candles = synth(20_000)

        val (eCapped, _) = wire(Settings().apply { maxZoneBoxes = 24 })
        eCapped.feed(candles, lastIsClosed = true)

        // همان داده با سقف عملاً بی‌نهایت → هیچ باکسی نباید CAP شود
        val (eFree, _) = wire(Settings().apply { maxZoneBoxes = Int.MAX_VALUE })
        eFree.feed(candles, lastIsClosed = true)

        val capStat = eCapped.zones.groupingBy { it.status }.eachCount()
        println("REGRESSION zones total=${eCapped.zones.size} byStatus=$capStat")

        // الف) سقف فقط روی باکس‌های *نمایان* اعمال می‌شود
        val visible = eCapped.zones.count {
            it.status == ZoneStatus.ACTIVE || it.status == ZoneStatus.REJECTED
        }
        assertTrue(visible <= 24, "باکس نمایان بیشتر از سقف: $visible")
        // پیش از رفع باگ این عدد **صفر** بود: همهٔ ۳۳۷ باکس CAP/DELETED بودند و روی چارت
        // هیچ ناحیه‌ای دیده نمی‌شد.
        assertTrue(visible in 1..24,
            "تعداد باکس نمایان باید بین ۱ و سقف باشد (visible=$visible) — پیش از رفع باگ صفر بود")

        // ب) وضعیت باکس‌ها نباید به سقف وابسته باشد (CAP فقط یک برچسب نمایشی است)
        val freeStatus = eFree.zones.map { it.status }
        val cappedStatus = eCapped.zones.map {
            // باکس‌هایی که فقط به‌خاطر سقف CAP شده‌اند، در حالت آزاد هم همان وضعیت اصلی را دارند
            if (it.status == ZoneStatus.CAP) {
                eFree.zones.first { z -> z.id == it.id }.status
            } else it.status
        }
        assertContentEquals(freeStatus, cappedStatus, "وضعیت باکس‌ها با تغییر سقف عوض شد")

        // پ) هیچ باکس CAP نباید وجود داشته باشد وقتی سقف بی‌نهایت است
        assertEquals(0, eFree.zones.count { it.status == ZoneStatus.CAP },
            "با سقف بی‌نهایت هیچ باکسی نباید CAP شود")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ۳) مسیر انقضای قطعی باید وضعیت، زمان پایان و هشدار را ثبت کند.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `expired order stores expiry status time reason and alert`() {
        val (_, b) = wire(Settings())
        val s = Setup(1L, 1, 6, 1950.0, 1850.0, 1, 0)
        s.hvH = 1850.0; s.hvL = 1840.0; s.sl = 1839.0
        s.tp1 = 1860.0; s.tp2 = 1870.0; s.tpx = 1880.0
        val placed = Candle(0, 1_700_000_000_000L, 1900.0, 1901.0, 1899.0, 1900.0, 100.0)
        b.registerLimit(s, placed)
        var alertKind = ""
        b.alertSink = { kind, _, _ -> alertKind = kind }

        val expiryTime = placed.t + 5 * 60_000L
        val reason = "گذشت یک کندل ساختار کامل"
        b.onSetupExpired(s, expiryTime, 5, reason)
        val o = b.orders.first()
        assertEquals(OrderStatus.CANCELLED_EXPIRED, o.status)
        assertEquals(expiryTime, o.closedT, "زمان انقضا باید زمان واقعی رخداد باشد")
        assertTrue(o.cancelReason?.contains(reason) == true, "دلیل انقضا ثبت نشده")
        assertEquals(AlertKind.CANCEL, alertKind, "انقضای واقعی باید هشدار لغو/انقضا بفرستد")
    }

    @Test
    fun `entry candle touching TP1 closes at breakeven without a TP1 fill`() {
        val (_, b) = wire(Settings())
        val s = Setup(1L, 1, 6, 1950.0, 1850.0, 1, 0)
        s.hvH = 1850.0; s.hvL = 1840.0; s.sl = 1839.0
        s.tp1 = 1860.0; s.tp2 = 1870.0; s.tpx = 1880.0
        b.registerLimit(s, Candle(0, 1_700_000_000_000L, 1900.0, 1901.0, 1899.0, 1900.0, 100.0))
        b.onBar(Candle(1, 1_700_000_060_000L, 1850.0, 1865.0, 1845.0, 1855.0, 100.0))

        val t = b.trades.single()
        assertEquals(false, t.open)
        assertTrue(t.reason.contains("سر‌به‌سر"))
        assertTrue(t.fills.any { it.kind == "BE" && it.qty > 0 })
        assertEquals(false, t.fills.any { it.kind == "TP1" }, "TP1 در کندل ورود نباید ثبت شود")
    }

    @Test
    fun `stop loss wins when the entry candle also touches final target`() {
        val (_, b) = wire(Settings())
        val s = Setup(1L, 1, 6, 1950.0, 1850.0, 1, 0)
        s.hvH = 1850.0; s.hvL = 1840.0; s.sl = 1839.0
        s.tp1 = 1860.0; s.tp2 = 1870.0; s.tpx = 1880.0
        b.registerLimit(s, Candle(0, 1_700_000_000_000L, 1900.0, 1901.0, 1899.0, 1900.0, 100.0))
        b.onBar(Candle(1, 1_700_000_060_000L, 1850.0, 1890.0, 1835.0, 1860.0, 100.0))

        val t = b.trades.single()
        assertEquals(false, t.open)
        assertEquals("حد ضرر", t.reason)
        assertTrue(t.fills.any { it.kind == "SL" })
        assertEquals(false, t.fills.any { it.kind == "TPX" })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ۴) لغو دستی وضعیت مخصوص خودش را می‌گیرد
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `manual cancel keeps its own status`() {
        val (_, b) = wire(Settings())
        val s = Setup(1L, 1, 6, 1950.0, 1850.0, 1, 0)
        s.hvH = 1850.0; s.hvL = 1840.0; s.sl = 1839.0
        s.tp1 = 1860.0; s.tp2 = 1870.0; s.tpx = 1880.0
        b.registerLimit(s, Candle(0, 1_700_000_000_000L, 1900.0, 1901.0, 1899.0, 1900.0, 100.0))
        b.cancelPendingManually(1_700_000_060_000L, 1)
        assertEquals(OrderStatus.CANCELLED_MANUAL, b.orders.first().status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ۵) همهٔ تنظیمات (از جمله rejectCandleNext) از ذخیره/بازیابی سالم برمی‌گردند
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `every setting survives the round trip`() {
        val cfg = Settings().apply { rejectCandleNext = true; vpRows = 30 }
        val (e, b) = wire(cfg)
        val json = Store.save(cfg, e, b, synth(500))
        val back = Store.load(json).cfg
        assertEquals(true, back.rejectCandleNext, "rejectCandleNext ذخیره نمی‌شد")
        assertEquals(30, back.vpRows)
    }

    @Test
    fun `pin sequence and armed setup state survive save and restore`() {
        val cfg = Settings()
        val e = Engine(cfg)
        val b = PaperBroker(cfg)
        e.buActive = true; e.beActive = true
        e.pinSeq = 9; e.buSeq = 8; e.beSeq = 7
        e.lastBUpinRef = 100.5; e.lastBUpinHi = 102.0
        e.lastBEpinRef = 120.5; e.lastBEpinLo = 118.0
        val s = Setup(4L, 1, 6, 110.0, 100.0, 1, 12).apply {
            armBi = 13; armsSinceS = 2; prevEntry = 105.0
            rearmAllowed = true; entryBi = 14
        }
        e.setups.add(s)
        val json = Store.save(cfg, e, b, listOf(Candle(0, 1_700_000_000_000L, 100.0, 101.0, 99.0, 100.5, 50.0)))
        val back = Store.load(json).eng

        assertEquals(true, back.buActive); assertEquals(true, back.beActive)
        assertEquals(9, back.pinSeq); assertEquals(8, back.buSeq); assertEquals(7, back.beSeq)
        assertEquals(100.5, back.lastBUpinRef); assertEquals(102.0, back.lastBUpinHi)
        assertEquals(120.5, back.lastBEpinRef); assertEquals(118.0, back.lastBEpinLo)
        val restored = back.setups.single()
        assertEquals(13, restored.armBi); assertEquals(2, restored.armsSinceS)
        assertEquals(105.0, restored.prevEntry); assertEquals(true, restored.rearmAllowed)
        assertEquals(14, restored.entryBi)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ۶) فایل ذخیرهٔ بریده نباید اپ را با استثنا زمین بزند
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `truncated json with a broken unicode escape does not throw`() {
        val broken = """{"a":"x\u12","b":[1,2],"c":"y\uZZ34"}"""
        val m = Json.parse(broken).asMap()
        assertEquals(2, m["b"].asList().size)
        // هیچ استثنایی پرتاب نشد = پاس
        assertTrue(m.containsKey("a"))
    }
}
