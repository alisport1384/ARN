package com.alisport.goldpin.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * آزمون حیاتی «ذخیره/بازیابی دقیق»:
 *   ۱) موتور روی داده اجرا می‌شود
 *   ۲) وضعیت در فایل ذخیره می‌شود
 *   ۳) از فایل بازگردانی می‌شود و ادامهٔ اجرا با نسخهٔ اصلی مقایسه می‌شود
 * نتیجهٔ مورد انتظار: خروجی هر دو **بی‌کم‌وکاست** یکسان باشد.
 */
class StoreTest {

    /** داده مصنوعی قطعی (random walk با حجم) */
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

    private fun build(candles: List<Candle>, upTo: Int): Triple<Engine, PaperBroker, Settings> {
        val cfg = Settings()
        val e = Engine(cfg)
        val b = PaperBroker(cfg)
        e.broker = b; b.engine = e
        e.barCommitHook = { cd -> b.onBar(cd, live = false) }
        e.feed(candles.subList(0, upTo), lastIsClosed = true)
        return Triple(e, b, cfg)
    }

    @Test
    fun `saved state restores exactly and continues identically`() {
        val candles = synth(20_000)
        val cut = 12_000

        // نسخهٔ مرجع: کامل تا انتها
        val (eRef, bRef, _) = build(candles, cut)
        eRef.feed(candles, lastIsClosed = true)

        // نسخهٔ ذخیره‌شده: تا نقطهٔ برش، ذخیره، بازیابی، ادامه
        val (eSave, bSave, cfgSave) = build(candles, cut)
        val json = Store.save(cfgSave, eSave, bSave, candles.subList(0, cut), meta = mapOf("chartTf" to 60))

        val ld = Store.load(json)
        ld.eng.barCommitHook = { cd -> ld.broker.onBar(cd, live = false) }
        // کندل‌های بازگردانی‌شده باید با اصل یکی باشند
        assertEquals(cut, ld.candles.size, "تعداد کندل‌های بازگردانی‌شده")
        assertEquals(candles[cut - 1].t, ld.candles[cut - 1].t, "زمان آخرین کندل بازگردانی‌شده")

        // ادامهٔ اجرا روی داده‌ای که کندل‌های اولش از فایل آمده
        val rest = ArrayList<Candle>(candles.size)
        rest.addAll(ld.candles)
        for (i in cut until candles.size) rest.add(candles[i])
        ld.eng.feed(rest, lastIsClosed = true)

        // ── مقایسهٔ بی‌کم‌وکاست ──
        assertEquals(eRef.trend, ld.eng.trend, "روند")
        assertEquals(eRef.cnt.setup, ld.eng.cnt.setup, "تعداد ستاپ")
        assertEquals(eRef.cnt.touch, ld.eng.cnt.touch, "تعداد برخورد")
        assertEquals(eRef.cnt.mid, ld.eng.cnt.mid, "تعداد تایید میانی")
        assertEquals(eRef.cnt.lv, ld.eng.cnt.lv, "تعداد ناحیهٔ ولوم کم")
        assertEquals(eRef.cnt.hv, ld.eng.cnt.hv, "تعداد ناحیهٔ ولوم زیاد")
        assertEquals(eRef.cnt.entry, ld.eng.cnt.entry, "تعداد ورود")
        assertEquals(eRef.cnt.done, ld.eng.cnt.done, "تعداد پایان")

        assertEquals(bRef.orders.size, ld.broker.orders.size, "تعداد سفارش‌ها")
        assertEquals(bRef.trades.size, ld.broker.trades.size, "تعداد معاملات")
        assertEquals(bRef.balance, ld.broker.balance, 1e-6, "موجودی نهایی")
        assertEquals(bRef.maxDrawdown, ld.broker.maxDrawdown, 1e-6, "بیشترین افت سرمایه")

        // باکس‌های ناحیهٔ ساختار، تریگر ۱ و تریگر ۲: همان تعداد، همان قیمت‌ها، همان وضعیت‌ها
        assertEquals(eRef.zones.size, ld.eng.zones.size, "تعداد باکس‌های ناحیهٔ ساختار")
        for (i in eRef.zones.indices) {
            val a = eRef.zones[i]; val b = ld.eng.zones[i]
            assertEquals(a.top, b.top, 1e-9, "سقف باکس $i")
            assertEquals(a.bot, b.bot, 1e-9, "کف باکس $i")
            assertEquals(a.status, b.status, "وضعیت باکس $i")
            assertEquals(a.endBi, b.endBi, "کندل پایانی باکس $i")
        }
        assertTrue(eRef.t1Zones.isNotEmpty(), "باید باکس تریگر ۱ در حافظه ثبت شده باشد")
        assertEquals(eRef.t1Zones.size, ld.eng.t1Zones.size, "تعداد باکس‌های تریگر ۱ در حافظه")
        for (i in eRef.t1Zones.indices) {
            val a = eRef.t1Zones[i]; val b = ld.eng.t1Zones[i]
            assertEquals(a.top, b.top, 1e-9, "سقف تریگر ۱ $i")
            assertEquals(a.bot, b.bot, 1e-9, "کف تریگر ۱ $i")
            assertEquals(a.createdT, b.createdT, "زمان شروع تریگر ۱ $i")
            assertEquals(a.status, b.status, "وضعیت تریگر ۱ $i")
        }
        assertTrue(eRef.t2Zones.isNotEmpty(), "باید باکس تریگر ۲ در حافظه ثبت شده باشد")
        assertEquals(eRef.t2Zones.size, ld.eng.t2Zones.size, "تعداد باکس‌های تریگر ۲ در حافظه")
        for (i in eRef.t2Zones.indices) {
            val a = eRef.t2Zones[i]; val b = ld.eng.t2Zones[i]
            assertEquals(a.top, b.top, 1e-9, "سقف تریگر ۲ $i")
            assertEquals(a.bot, b.bot, 1e-9, "کف تریگر ۲ $i")
            assertEquals(a.createdT, b.createdT, "زمان شروع تریگر ۲ $i")
            assertEquals(a.status, b.status, "وضعیت تریگر ۲ $i")
        }

        // معاملات: تک‌تک ریزجزئیات
        assertTrue(bRef.trades.isNotEmpty(), "باید معامله‌ای ثبت شده باشد")
        for (i in bRef.trades.indices) {
            val a = bRef.trades[i]; val b = ld.broker.trades[i]
            assertEquals(a.entry, b.entry, 1e-9, "ورود معامله $i")
            assertEquals(a.sl0, b.sl0, 1e-9, "حدضرر معامله $i")
            assertEquals(a.tpX, b.tpX, 1e-9, "حدسود معامله $i")
            assertEquals(a.qty, b.qty, 1e-9, "حجم معامله $i")
            assertEquals(a.reason, b.reason, "دلیل خروج معامله $i")
            assertEquals(a.pnl(), b.pnl(), 1e-6, "سود معامله $i")
            assertEquals(a.fills.size, b.fills.size, "تعداد پله‌های معامله $i")
        }
    }

    @Test
    fun `settings survive the round trip`() {
        val candles = synth(3_000)
        val cfg = Settings().apply {
            vpRows = 31; vpSmooth = 2; minZoneRows = 2; riskPct = 0.75; maxLeverage = 3.0
            slBufTicks = 4.0; minTPunits = 12.0; hvMarkFirstIncrease = false; midInvalidClose = false
            maxSetups = 4; maxZoneBoxes = 12; tfMode = 4; tfS = Tf.H4; tfM = Tf.H1; tf1 = Tf.M15; tf2 = Tf.M5
        }
        val (e, b, _) = build(candles, 2_000)
        val json = Store.save(cfg, e, b, candles)
        val ld = Store.load(json)
        val c2 = ld.cfg
        assertEquals(31, c2.vpRows); assertEquals(2, c2.vpSmooth); assertEquals(2, c2.minZoneRows)
        assertEquals(0.75, c2.riskPct, 1e-9); assertEquals(3.0, c2.maxLeverage, 1e-9)
        assertEquals(4.0, c2.slBufTicks, 1e-9); assertEquals(12.0, c2.minTPunits, 1e-9)
        assertEquals(false, c2.hvMarkFirstIncrease); assertEquals(false, c2.midInvalidClose)
        assertEquals(4, c2.maxSetups); assertEquals(12, c2.maxZoneBoxes)
        assertEquals(Tf.H4, c2.tfS); assertEquals(Tf.M5, c2.tf2)
    }
}
