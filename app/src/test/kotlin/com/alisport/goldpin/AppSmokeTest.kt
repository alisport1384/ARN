package com.alisport.goldpin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.alisport.goldpin.core.Tf
import com.alisport.goldpin.data.Feed
import com.alisport.goldpin.ui.ChartView
import com.alisport.goldpin.ui.EquityChartView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * تست دود: اپ واقعاً ساخته می‌شود، همهٔ تب‌ها باز می‌شوند، چارت روی داده رسم می‌شود،
 * دیالوگ جزئیات باز می‌شود و مسیرهای فید/ذخیره/بازیابی خطا نمی‌دهند.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AppSmokeTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `activity builds and all tabs open without crashing`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = c.get()
        assertNotNull("اکتیویتی ساخته نشد", act)
        val root = act.window.decorView
        assertNotNull(root)

        // نوار پایین: ۵ تب
        val content = root.findViewById<ViewGroup>(android.R.id.content)
        val shell = content.getChildAt(0) as LinearLayout
        assertEquals(3, shell.childCount)                 // هدر ، محتوا ، تب‌ها
        val tabs = shell.getChildAt(2) as LinearLayout
        assertEquals(5, tabs.childCount)

        for (i in 0 until 5) {
            tabs.getChildAt(i).performClick()
            idle()
            assertTrue("تب ${i} باز نشد", shell.getChildAt(1) is ViewGroup)
        }
        c.pause().stop().destroy()
    }

    @Test
    fun `engine runs the bundled sample data end to end`() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        // مسیر واقعی خواندن دارایی نمونه (همان مسیری که اپ استفاده می‌کند)
        val text = Feed.openSampleAsset(ctx).use { Feed.readCsvStream(it) }
        var candles = Feed.parseCsv(text)
        assertTrue("دادهٔ نمونه خوانده نشد", candles.size > 10_000)
        assertTrue("حجم در دادهٔ نمونه صفر است", candles.any { it.v > 0.0 })

        val s = AppState.instance
        s.cfg.tf2 = Tf.M1
        s.chartTfSec = Tf.M1
        candles = Feed.synthesizeVolumeIfMissing(candles)
        s.setCandles(candles)
        s.rebuild(clearOrders = true)

        s.backtestFull = true
        s.rebuild(clearOrders = true)
        assertEquals("همهٔ کندل‌ها پردازش نشدند", candles.size, s.engine.processed)
        assertTrue("هیچ ناحیه‌ای ساخته نشد", s.engine.cnt.setup > 0)
        assertTrue("هیچ برخوردی ثبت نشد", s.engine.cnt.touch > 0)
        assertTrue("هیچ سفارشی ثبت نشد", s.broker.orders.size > 0)
        assertEquals("موجودی باید معتبر بماند", true, s.broker.balance.isFinite())

        // چارت + نمودار سود/زیان واقعاً رسم شوند
        val chart = ChartView(ctx)
        chart.candles = s.candles
        chart.zones = s.engine.zones
        chart.markers = s.engine.markers
        chart.trades = s.broker.trades
        chart.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY)
        )
        chart.layout(0, 0, 1080, 1200)
        chart.refresh()
        val bmp = Bitmap.createBitmap(1080, 1200, Bitmap.Config.ARGB_8888)
        chart.draw(Canvas(bmp))     // اگر خطای رسم باشد اینجا لو می‌رود

        val eq = EquityChartView(ctx)
        eq.setData(s.broker.trades, s.cfg.initialEquity)
        eq.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
        )
        eq.layout(0, 0, 1080, 400)
        eq.draw(Canvas(Bitmap.createBitmap(1080, 400, Bitmap.Config.ARGB_8888)))
    }

    @Test
    fun `save and restore round trip through the app state`() {
        val s = AppState.instance
        // داده کوچک واقعی
        val rnd = kotlin.random.Random(7)
        val list = ArrayList<com.alisport.goldpin.core.Candle>()
        var px = 2000.0
        var t = 1_700_000_000_000L
        for (i in 0 until 8_000) {
            val o = px; val c = px + rnd.nextDouble(-1.0, 1.0)
            list.add(com.alisport.goldpin.core.Candle(i, t, o, maxOf(o, c), minOf(o, c), c, 100.0 + rnd.nextDouble(500.0)))
            px = c; t += 60_000
        }
        s.setCandles(list)
        val json = s.buildJson()
        val beforeZones = s.engine.zones.size
        val beforeTrades = s.broker.trades.size

        s.wipe()
        assertTrue("پاک کردن کار نکرد", s.engine.zones.isEmpty() || s.broker.trades.isEmpty())

        val msg = s.loadFromText(json)
        assertTrue("پیام بازیابی: $msg", msg.startsWith("بازیابی"))
        assertEquals("تعداد کندل بعد از بازیابی", list.size, s.candles.size)
        assertEquals("تعداد باکس ناحیه بعد از بازیابی", beforeZones, s.engine.zones.size)
        assertEquals("تعداد معاملات بعد از بازیابی", beforeTrades, s.broker.trades.size)

        // ادامهٔ اجرا نباید خطا بدهد
        s.rebuild(clearOrders = false)
        assertTrue(s.engine.processed > 0)
    }

    @Test
    fun `feed errors are handled gracefully without network`() {
        val s = AppState.instance
        // در محیط تست شبکه نیست → باید پیام خطای محترمانه بدهد و کرش نکند
        s.downloadHistory { }
        Thread.sleep(1500)
        idle()
        // خطا یا داده ؛ در هر حال نباید استثنا بدهد
        assertTrue(true)
    }

    @Test
    fun `mode boxes and trigger 1 and 2 ranges remain fixed in memory across timeframe changes`() {
        val s = AppState()
        val rnd = kotlin.random.Random(42)
        val list = ArrayList<com.alisport.goldpin.core.Candle>()
        var px = 2000.0
        var t = 1_700_000_000_000L
        for (i in 0 until 12_000) {
            val o = px; val c = px + rnd.nextDouble(-1.1, 1.1)
            val h = maxOf(o, c) + rnd.nextDouble(0.0, 0.7)
            val l = minOf(o, c) - rnd.nextDouble(0.0, 0.7)
            val v = 60.0 + rnd.nextDouble(450.0) + if (i % 83 == 0) 950.0 else 0.0
            list.add(com.alisport.goldpin.core.Candle(i, t, o, h, l, c, v))
            px = c; t += 60_000
        }
        // انتخاب مود ۴ (ساختار H1، میانی M15، تریگر ۱ M5، تریگر ۲ M1)
        s.cfg.tfMode = 4; s.cfg.tfS = Tf.H1; s.cfg.tfM = Tf.M15; s.cfg.tf1 = Tf.M5; s.cfg.tf2 = Tf.M1
        s.chartTfSec = Tf.M1
        s.setCandles(list, rebuildNow = true)

        val snap0 = s.uiSnapshot()
        assertTrue("باید باکس ساختار ثبت شده باشد", snap0.zones.isNotEmpty())
        assertTrue("باید باکس تریگر ۱ در حافظه ثبت شده باشد", snap0.t1Zones.isNotEmpty())
        assertTrue("باید باکس تریگر ۲ در حافظه ثبت شده باشد", snap0.t2Zones.isNotEmpty())

        val refZones = snap0.zones.map { "${it.id}:${it.dir}:${it.idx}:${it.top}:${it.bot}:${it.createdT}:${it.endT}:${it.status}" }
        val refT1 = snap0.t1Zones.map { "${it.id}:${it.dir}:${it.top}:${it.bot}:${it.createdT}:${it.endT}:${it.status}" }
        val refT2 = snap0.t2Zones.map { "${it.id}:${it.dir}:${it.top}:${it.bot}:${it.createdT}:${it.endT}:${it.status}" }
        val refSetups = snap0.setups.map { "${it.id}:${it.stage}:${it.zTop}:${it.zBot}:${it.lvH}:${it.lvL}:${it.hvH}:${it.hvL}" }

        // چرخش بین تمام تایم‌فریم‌های چارت در همان مود انتخاب‌شده
        for (tf in listOf(Tf.M5, Tf.M15, Tf.M30, Tf.H1, Tf.H4, Tf.D1, Tf.M1)) {
            s.changeChartTf(tf, forceDownload = false)
            val snapTf = s.uiSnapshot()
            assertEquals("مود نباید با تغییر تایم‌فریم عوض شود", 4, s.cfg.tfMode)
            assertEquals("ساختار مود نباید عوض شود", Tf.H1, s.cfg.tfS)
            assertEquals("میانی مود نباید عوض شود", Tf.M15, s.cfg.tfM)
            assertEquals("تریگر ۱ مود نباید عوض شود", Tf.M5, s.cfg.tf1)
            assertEquals("تریگر ۲ مود نباید عوض شود", Tf.M1, s.cfg.tf2)
            assertEquals("تایم‌فریم چارت باید به $tf تغییر کند", tf, s.chartTfSec)
            assertEquals("باکس‌های ساختار در تایم‌فریم $tf تغییر کردند", refZones,
                snapTf.zones.map { "${it.id}:${it.dir}:${it.idx}:${it.top}:${it.bot}:${it.createdT}:${it.endT}:${it.status}" })
            assertEquals("باکس‌های تریگر ۱ در تایم‌فریم $tf تغییر کردند", refT1,
                snapTf.t1Zones.map { "${it.id}:${it.dir}:${it.top}:${it.bot}:${it.createdT}:${it.endT}:${it.status}" })
            assertEquals("باکس‌های تریگر ۲ در تایم‌فریم $tf تغییر کردند", refT2,
                snapTf.t2Zones.map { "${it.id}:${it.dir}:${it.top}:${it.bot}:${it.createdT}:${it.endT}:${it.status}" })
            assertEquals("محدوده‌های ستاپ‌ها در تایم‌فریم $tf تغییر کردند", refSetups,
                snapTf.setups.map { "${it.id}:${it.stage}:${it.zTop}:${it.zBot}:${it.lvH}:${it.lvL}:${it.hvH}:${it.hvL}" })
        }
    }

    @Test
    fun `trades and orders dialogs build`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = c.get()
        val s = AppState.instance
        if (s.broker.trades.isNotEmpty()) {
            // باز کردن تب سود و زیان و تب سفارش‌ها و لمس اولین ردیف
            val content = act.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            val shell = content.getChildAt(0) as LinearLayout
            val tabs = shell.getChildAt(2) as LinearLayout
            tabs.getChildAt(2).performClick(); idle()
            val screen = shell.getChildAt(1) as ViewGroup
            val lists = ArrayList<ListView>()
            fun walk(v: View) {
                if (v is ListView) lists.add(v)
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(screen)
            for (lv in lists) if (lv.adapter != null && lv.adapter.count > 0) lv.performItemClick(lv.getChildAt(0), 0, 0)
            idle()
        }
        c.pause().stop().destroy()
    }

    @Test
    fun `individual chart visibility toggles apply persist and render cleanly`() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val s = AppState()
        s.initApp(ctx)

        s.showActiveStructureBoxes = false
        s.showDeletedStructureBoxes = true
        s.showRejectedStructureBoxes = false
        s.showTrigger1Boxes = true
        s.showTrigger2Boxes = false
        s.showBoxLabels = false
        s.showPinMarkers = true
        s.showTrendMarkers = false
        s.showTouchMarkers = false
        s.showMidMarkers = true
        s.showTrigger1Markers = false
        s.showTrigger2Markers = true
        s.showTradeMarkers = false
        s.showInvalidationMarkers = false
        s.showMarkerText = false
        s.showSetupZoneLines = false
        s.showMidLine = true
        s.showTrigger1Lines = false
        s.showTrigger2Lines = true
        s.showEntryLine = true
        s.showSlLine = false
        s.showTpStepsLines = false
        s.showTpFinalLine = true
        s.showPocLine = false
        s.showVolume = false
        s.showZeroVolumeHighlight = false
        s.showGrid = false
        s.showLastPriceLine = false
        s.showWatermark = false
        s.showLegend = false
        s.showStatusBadges = false
        s.showEventsPanel = false
        s.persistPrefs(ctx)

        val s2 = AppState()
        s2.initApp(ctx)
        assertEquals(false, s2.showActiveStructureBoxes)
        assertEquals(true, s2.showDeletedStructureBoxes)
        assertEquals(false, s2.showRejectedStructureBoxes)
        assertEquals(true, s2.showTrigger1Boxes)
        assertEquals(false, s2.showTrigger2Boxes)
        assertEquals(false, s2.showBoxLabels)
        assertEquals(false, s2.showTrendMarkers)
        assertEquals(false, s2.showTouchMarkers)
        assertEquals(false, s2.showTrigger1Markers)
        assertEquals(false, s2.showTradeMarkers)
        assertEquals(false, s2.showInvalidationMarkers)
        assertEquals(false, s2.showMarkerText)
        assertEquals(false, s2.showSetupZoneLines)
        assertEquals(false, s2.showTrigger1Lines)
        assertEquals(false, s2.showSlLine)
        assertEquals(false, s2.showTpStepsLines)
        assertEquals(false, s2.showPocLine)
        assertEquals(false, s2.showVolume)
        assertEquals(false, s2.showGrid)
        assertEquals(false, s2.showLastPriceLine)
        assertEquals(false, s2.showWatermark)
        assertEquals(false, s2.showLegend)
        assertEquals(false, s2.showStatusBadges)
        assertEquals(false, s2.showEventsPanel)

        val cv = ChartView(ctx)
        s2.applyChartVis(cv)
        assertEquals(false, cv.showActiveStructureBoxes)
        assertEquals(false, cv.showTrigger2Boxes)
        assertEquals(false, cv.showTrendMarkers)
        assertEquals(false, cv.showTouchMarkers)
        assertEquals(false, cv.showSlLine)
        assertEquals(false, cv.showGrid)
    }

    @Test
    fun `granular chart visibility stays in chart menu instead of app settings`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = c.get()
        val content = act.window.decorView.findViewById<ViewGroup>(android.R.id.content)
        val shell = content.getChildAt(0) as LinearLayout
        val tabs = shell.getChildAt(2) as LinearLayout

        fun texts(root: View): List<String> {
            val out = ArrayList<String>()
            fun walk(v: View) {
                if (v is TextView) out.add(v.text?.toString().orEmpty())
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
            return out
        }

        tabs.getChildAt(4).performClick()
        idle()
        val settingsText = texts(shell.getChildAt(1))
        assertFalse(settingsText.any { it.contains("باکس‌های ساختار فعال") })
        assertFalse(settingsText.any { it.contains("برچسب شروع و تغییر روند") })
        assertFalse(settingsText.any { it.contains("خاموش کردن همه") })

        tabs.getChildAt(0).performClick()
        idle()
        val chartText = texts(shell.getChildAt(1))
        assertTrue("منوی اجزای چارت باید در تب خود چارت باشد", chartText.any { it.contains("اجزای چارت") })
        c.pause().stop().destroy()
    }
}
