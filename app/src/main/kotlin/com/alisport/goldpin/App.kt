package com.alisport.goldpin

import android.os.Handler
import android.os.Looper
import android.content.Context
import android.widget.Toast
import com.alisport.goldpin.core.*
import com.alisport.goldpin.data.Feed
import com.alisport.goldpin.data.FeedException
import com.alisport.goldpin.data.Storage
import com.alisport.goldpin.util.Alerts
import com.alisport.goldpin.util.Fa
import com.alisport.goldpin.util.Log
import java.io.File
import java.util.concurrent.Executors

// ═══════════════════════════════════════════════════════════════════════════════
//  وضعیت سراسری اپ — موتور، کارگزار، کندل‌ها، تنظیمات فید و ذخیره/بازیابی
// ═══════════════════════════════════════════════════════════════════════════════
class AppState {

    val cfg = Settings()
    val engine = Engine(cfg)
    val broker = PaperBroker(cfg)

    /** کندل‌های نمایشی تایم‌فریم چارت فعلی (chartTfSec) */
    /**
     * فهرست کندل‌ها. به‌صورت «تعویض اتمیک» به‌روزرسانی می‌شود تا نخ رابط
     * هرگز فهرست نیمه‌ساخته نبیند (منشأ قبلی فریز و پرش).
     */
    @Volatile
    var candles: List<Candle> = emptyList()
        private set

    /** کندل‌های تایم‌فریم تریگر ۲ در مود انتخاب‌شده (cfg.tf2) که موتور و باکس‌ها در حافظه روی آن ثابت‌اند */
    @Volatile
    var modeCandles: List<Candle> = emptyList()
        private set

    /** کش آخرین سری ریزدانه (۱ دقیقه) برای تجمیع آفلاین هنگام بازگشت از تایم‌فریم‌های بالاتر به پایین‌تر */
    @Volatile
    private var fineCandles: List<Candle> = emptyList()

    /** کش کندل‌های دانلودشدهٔ فید به تفکیک تایم‌فریم (ثانیه) در حافظه */
    private val tfFeedCache = java.util.concurrent.ConcurrentHashMap<Int, List<Candle>>()

    /** آیا دادهٔ مود فعلی از فید آنلاین دانلود شده است؟ */
    @Volatile
    private var modeFromFeed: Boolean = false

    // تنظیمات فید (اپ)
    var symbol: String = Feed.SYMBOL_DEFAULT
    var baseTfSec: Int = Tf.M1
    var chartTfSec: Int = Tf.M1
    var depth: Int = 2                 // ۱=کوتاه ، ۲=متوسط ، ۳=عمیق
    var useSyntheticVolume: Boolean = true
    var livePollMs: Long = 10000L
    var liveRunning: Boolean = false
    var lastLivePrice: Double = Double.NaN
    var lastFeedAt: Long = 0L
    var lastFeedError: String? = null
    var lastPriceSource: String = "—"
    var autosaveEveryMs: Long = 120000L

    /** تعداد تیک‌های پشت‌سرهم ناموفق — سرویس با آن فاصلهٔ تیک را به‌صورت نمایی عقب می‌اندازد. */
    @Volatile var liveFailStreak: Int = 0

    /** چند تیک به‌خاطر «تیک قبلی هنوز در جریان» حذف شده‌اند (نشانهٔ کندی فید). */
    @Volatile var lastLiveSkips: Long = 0L

    /** آخرین بازپخش تاریخچه چند کندل داشت (که ساکت اجرا شد) — برای شفافیت در نوتیفیکیشن */
    @Volatile var lastLiveSilentBars: Int = 0

    /**
     * حالت بک‌تست کامل: آخرین کندل هم بسته حساب می‌شود (دادهٔ تاریخی).
     * در حالت لایو، آخرین کندل «باز» می‌ماند و فقط پس از بسته شدن پردازش می‌شود
     * (سفارش‌ها اما در همان لحظه با قیمت زنده پر می‌شوند).
     */
    var backtestFull: Boolean = true
    /** در حال محاسبهٔ سنگین (اجرای موتور/دانلود) — رابط پیام «در حال محاسبه…» نشان می‌دهد */
    @Volatile var busy: Boolean = false
    @Volatile var busyText: String = ""
    /** ورود خودکار به تمام‌صفحه در تب چارت */
    var autoFullscreen: Boolean = false
    /** تنظیمات نمایش تک‌تک اجزای چارت (باکس‌ها، لیبل‌ها، خطوط و اجزای پایه به تفکیک نوع) */
    var showVolume: Boolean = true
    var showZeroVolumeHighlight: Boolean = true
    var showZones: Boolean = true
    var showActiveStructureBoxes: Boolean = true
    var showDeletedStructureBoxes: Boolean = true
    var showRejectedStructureBoxes: Boolean = true
    var showTrigger1Boxes: Boolean = true
    var showTrigger2Boxes: Boolean = true
    var showBoxLabels: Boolean = true

    var showMarkers: Boolean = true
    var showPinMarkers: Boolean = true
    var showTouchMarkers: Boolean = true
    var showMidMarkers: Boolean = true
    var showTrigger1Markers: Boolean = true
    var showTrigger2Markers: Boolean = true
    var showTradeMarkers: Boolean = true
    var showInvalidationMarkers: Boolean = true
    var showMarkerText: Boolean = true

    var showTradeLines: Boolean = true
    var showSetupZoneLines: Boolean = true
    var showMidLine: Boolean = true
    var showTrigger1Lines: Boolean = true
    var showTrigger2Lines: Boolean = true
    var showEntryLine: Boolean = true
    var showSlLine: Boolean = true
    var showTpStepsLines: Boolean = true
    var showTpFinalLine: Boolean = true
    var showPocLine: Boolean = true

    var showGrid: Boolean = true
    var showLastPriceLine: Boolean = true
    var showWatermark: Boolean = true
    var showLegend: Boolean = true
    var showStatusBadges: Boolean = true
    var showEventsPanel: Boolean = true

    private var appCtx: Context? = null
    @Volatile private var lastSyntheticVolumeNoticeAt: Long = 0L

    /**
     * نخ «تغییر وضعیت» — موتور، کارگزار و کندل‌ها فقط اینجا عوض می‌شوند (اجرای سریالی).
     *
     * ⚠ این executor تک‌نخی و صفش **بدون سقف** است. هر کاری که روی شبکه معطل بماند
     * و اینجا گذاشته شود، همهٔ کارهای بعدی (ذخیره، بازسازی موتور، خروجی لاگ…) را
     * پشت سر خود معطل می‌کند ⇒ رابط «فریز» به نظر می‌رسد. پس هیچ I/O شبکه‌ای
     * نباید روی [io] برود — برای شبکه از [net] استفاده می‌شود.
     */
    val io = Executors.newSingleThreadExecutor()

    /**
     * نخ «شبکه» — جدا از [io]. اگر فید کند یا قطع باشد، فقط این نخ معطل می‌شود
     * و کارهای رابط (ذخیره/بازسازی/خروجی لاگ) روی [io] بدون معطلی انجام می‌شوند.
     */
    val net = Executors.newSingleThreadExecutor { r ->
        Thread(r, "goldpin-net").apply { isDaemon = true }
    }

    /**
     * دروازهٔ تیک لایو: حداکثر **یک** تیک در جریان. تیک‌های اضافی حذف می‌شوند
     * (idempotent‌اند — تیک بعدی همان دنبالهٔ تازه را دوباره می‌گیرد).
     * بدون این دروازه، با فید کند صف [io] بی‌نهایت رشد می‌کرد.
     */
    val liveGate = com.alisport.goldpin.util.CoalescingGate("live")

    val main = Handler(Looper.getMainLooper())
    val listeners = ArrayList<() -> Unit>()

    // موتور در نخ IO بازسازی می‌شود و رابط در نخ اصلی می‌خواند.
    //
    // ⚠ خواندن وضعیت **هرگز** نباید قفل بگیرد. `stateLock` گاهی صدها میلی‌ثانیه نگه داشته
    // می‌شود (ساخت JSON ذخیرهٔ خودکار روی ۴۰٬۰۰۰ کندل ≈ ۲۵۰ms روی JVM دسکتاپ، روی گوشی بیشتر)
    // و اگر `uiSnapshot()` هم پشت همان قفل منتظر بماند، رابط در هر ذخیرهٔ دوره‌ای فریز می‌شود.
    // پس: قفل فقط برای *نوشتن* است؛ بعد از هر تغییر، یک snapshot تغییرناپذیر ساخته و به‌صورت
    // اتمیک (@Volatile) منتشر می‌شود و رابط همان را بدون هیچ انتظار می‌خواند.
    private val stateLock = Any()
    data class UiSnapshot(
        val candles: List<Candle>,
        val zones: List<ZoneBox>,
        val markers: List<Marker>,
        val setups: List<Setup>,
        val events: List<String>,
        val orders: List<Order>,
        val trades: List<Trade>,
        val openTrade: Trade?,
        val pendingOrder: Order?,
        val openTrades: List<Trade>,
        val pendingOrders: List<Order>,
        val lastPocPx: Double,
        val lastEntryPx: Double,
        val lastSlPx: Double,
        val lastTpPx: Double,
        val lastResult: String,
        val t1Zones: List<ZoneBox> = emptyList(),
        val t2Zones: List<ZoneBox> = emptyList()
    )

    @Volatile
    private var snap: UiSnapshot = UiSnapshot(
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), null, null, emptyList(), emptyList(),
        Double.NaN, Double.NaN, Double.NaN, Double.NaN, "-",
        emptyList(), emptyList()
    )

    /** ساخت و انتشار اتمیک snapshot — باید بلافاصله بعد از هر تغییر وضعیت صدا زده شود. */
    private fun publishSnapshot() {
        snap = UiSnapshot(
            candles.toList(), engine.zones.toList(), engine.markers.toList(), engine.setups.toList(),
            engine.events.toList(), broker.orders.toList(), broker.trades.toList(),
            broker.openTrade, broker.pendingOrder, broker.openTrades, broker.pendingOrders,
            engine.lastPocPx, engine.lastEntryPx,
            engine.lastSlPx, engine.lastTpPx, engine.lastResult,
            engine.t1Zones.toList(), engine.t2Zones.toList()
        )
    }

    /** بدون قفل — رابط هرگز برای خواندن وضعیت منتظر موتور نمی‌ماند. */
    fun uiSnapshot(): UiSnapshot = snap
    fun tradesSnapshot(): List<Trade> = snap.trades
    fun ordersSnapshot(): List<Order> = snap.orders

    init {
        engine.broker = broker
        broker.engine = engine
        engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
        wireLogging()
    }

    /** راه‌اندازی لاگر و هشدارها + وصل کردن آن‌ها به موتور و کارگزار */
    fun initApp(ctx: Context) {
        appCtx = ctx.applicationContext
        Log.init(appCtx!!, "1.4.13")
        Alerts.init(appCtx!!)
        val p = appCtx!!.getSharedPreferences("goldpin", Context.MODE_PRIVATE)
        // مهاجرت ۱٫۱ → ۱٫۲: تمام‌صفحهٔ خودکار دیگر پیش‌فرض نیست؛ تنظیم قدیمی true را یک‌بار خاموش کن.
        val uiVersion = p.getInt("ui_prefs_version", 0)
        autoFullscreen = if (uiVersion < 2) false else p.getBoolean("auto_fullscreen", false)
        if (uiVersion < 2) p.edit().putBoolean("auto_fullscreen", false).putInt("ui_prefs_version", 2).apply()
        Feed.connectTimeoutMs = p.getInt("feed_connect_timeout_ms", 8000).coerceIn(1000, 120000)
        Feed.readTimeoutMs = p.getInt("feed_read_timeout_ms", 12000).coerceIn(1000, 120000)
        showVolume = p.getBoolean("cv_show_volume", true)
        showZeroVolumeHighlight = p.getBoolean("cv_show_zero_vol", true)
        showZones = p.getBoolean("cv_show_zones", true)
        showActiveStructureBoxes = p.getBoolean("cv_show_active_s_boxes", true)
        showDeletedStructureBoxes = p.getBoolean("cv_show_deleted_s_boxes", true)
        showRejectedStructureBoxes = p.getBoolean("cv_show_rejected_s_boxes", true)
        showTrigger1Boxes = p.getBoolean("cv_show_t1_boxes", true)
        showTrigger2Boxes = p.getBoolean("cv_show_t2_boxes", true)
        showBoxLabels = p.getBoolean("cv_show_box_labels", true)
        showMarkers = p.getBoolean("cv_show_markers", true)
        showPinMarkers = p.getBoolean("cv_show_pin_markers", true)
        showTouchMarkers = p.getBoolean("cv_show_touch_markers", true)
        showMidMarkers = p.getBoolean("cv_show_mid_markers", true)
        showTrigger1Markers = p.getBoolean("cv_show_t1_markers", true)
        showTrigger2Markers = p.getBoolean("cv_show_t2_markers", true)
        showTradeMarkers = p.getBoolean("cv_show_trade_markers", true)
        showInvalidationMarkers = p.getBoolean("cv_show_inv_markers", true)
        showMarkerText = p.getBoolean("cv_show_marker_text", true)
        showTradeLines = p.getBoolean("cv_show_trade_lines", true)
        showSetupZoneLines = p.getBoolean("cv_show_setup_zone_lines", true)
        showMidLine = p.getBoolean("cv_show_mid_line", true)
        showTrigger1Lines = p.getBoolean("cv_show_t1_lines", true)
        showTrigger2Lines = p.getBoolean("cv_show_t2_lines", true)
        showEntryLine = p.getBoolean("cv_show_entry_line", true)
        showSlLine = p.getBoolean("cv_show_sl_line", true)
        showTpStepsLines = p.getBoolean("cv_show_tp_steps_lines", true)
        showTpFinalLine = p.getBoolean("cv_show_tp_final_line", true)
        showPocLine = p.getBoolean("cv_show_poc_line", true)
        showGrid = p.getBoolean("cv_show_grid", true)
        showLastPriceLine = p.getBoolean("cv_show_last_price_line", true)
        showWatermark = p.getBoolean("cv_show_watermark", true)
        showLegend = p.getBoolean("cv_show_legend", true)
        showStatusBadges = p.getBoolean("cv_show_status_badges", true)
        showEventsPanel = p.getBoolean("cv_show_events_panel", true)
        Feed.syntheticVolumeWarning = { count -> announceSyntheticVolume(count) }
        Log.i(Log.CAT_APP, "AppState راه‌اندازی شد",
            "autoFullscreen=$autoFullscreen logEnabled=${Log.enabled} alerts=${Alerts.enabled}")
        wireLogging()
    }

    fun persistPrefs(ctx: Context) {
        ctx.getSharedPreferences("goldpin", Context.MODE_PRIVATE).edit()
            .putBoolean("auto_fullscreen", autoFullscreen)
            .putInt("feed_connect_timeout_ms", Feed.connectTimeoutMs)
            .putInt("feed_read_timeout_ms", Feed.readTimeoutMs)
            .putBoolean("cv_show_volume", showVolume)
            .putBoolean("cv_show_zero_vol", showZeroVolumeHighlight)
            .putBoolean("cv_show_zones", showZones)
            .putBoolean("cv_show_active_s_boxes", showActiveStructureBoxes)
            .putBoolean("cv_show_deleted_s_boxes", showDeletedStructureBoxes)
            .putBoolean("cv_show_rejected_s_boxes", showRejectedStructureBoxes)
            .putBoolean("cv_show_t1_boxes", showTrigger1Boxes)
            .putBoolean("cv_show_t2_boxes", showTrigger2Boxes)
            .putBoolean("cv_show_box_labels", showBoxLabels)
            .putBoolean("cv_show_markers", showMarkers)
            .putBoolean("cv_show_pin_markers", showPinMarkers)
            .putBoolean("cv_show_touch_markers", showTouchMarkers)
            .putBoolean("cv_show_mid_markers", showMidMarkers)
            .putBoolean("cv_show_t1_markers", showTrigger1Markers)
            .putBoolean("cv_show_t2_markers", showTrigger2Markers)
            .putBoolean("cv_show_trade_markers", showTradeMarkers)
            .putBoolean("cv_show_inv_markers", showInvalidationMarkers)
            .putBoolean("cv_show_marker_text", showMarkerText)
            .putBoolean("cv_show_trade_lines", showTradeLines)
            .putBoolean("cv_show_setup_zone_lines", showSetupZoneLines)
            .putBoolean("cv_show_mid_line", showMidLine)
            .putBoolean("cv_show_t1_lines", showTrigger1Lines)
            .putBoolean("cv_show_t2_lines", showTrigger2Lines)
            .putBoolean("cv_show_entry_line", showEntryLine)
            .putBoolean("cv_show_sl_line", showSlLine)
            .putBoolean("cv_show_tp_steps_lines", showTpStepsLines)
            .putBoolean("cv_show_tp_final_line", showTpFinalLine)
            .putBoolean("cv_show_poc_line", showPocLine)
            .putBoolean("cv_show_grid", showGrid)
            .putBoolean("cv_show_last_price_line", showLastPriceLine)
            .putBoolean("cv_show_watermark", showWatermark)
            .putBoolean("cv_show_legend", showLegend)
            .putBoolean("cv_show_status_badges", showStatusBadges)
            .putBoolean("cv_show_events_panel", showEventsPanel)
            .apply()
    }

    fun applyChartVis(cv: com.alisport.goldpin.ui.ChartView) {
        cv.showVolume = showVolume
        cv.showZeroVolumeHighlight = showZeroVolumeHighlight
        cv.showZones = showZones
        cv.showActiveStructureBoxes = showActiveStructureBoxes
        cv.showDeletedStructureBoxes = showDeletedStructureBoxes
        cv.showRejectedStructureBoxes = showRejectedStructureBoxes
        cv.showTrigger1Boxes = showTrigger1Boxes
        cv.showTrigger2Boxes = showTrigger2Boxes
        cv.showBoxLabels = showBoxLabels
        cv.showMarkers = showMarkers
        cv.showPinMarkers = showPinMarkers
        cv.showTouchMarkers = showTouchMarkers
        cv.showMidMarkers = showMidMarkers
        cv.showTrigger1Markers = showTrigger1Markers
        cv.showTrigger2Markers = showTrigger2Markers
        cv.showTradeMarkers = showTradeMarkers
        cv.showInvalidationMarkers = showInvalidationMarkers
        cv.showMarkerText = showMarkerText
        cv.showTradeLines = showTradeLines
        cv.showSetupZoneLines = showSetupZoneLines
        cv.showMidLine = showMidLine
        cv.showTrigger1Lines = showTrigger1Lines
        cv.showTrigger2Lines = showTrigger2Lines
        cv.showEntryLine = showEntryLine
        cv.showSlLine = showSlLine
        cv.showTpStepsLines = showTpStepsLines
        cv.showTpFinalLine = showTpFinalLine
        cv.showPocLine = showPocLine
        cv.showGrid = showGrid
        cv.showLastPriceLine = showLastPriceLine
        cv.showWatermark = showWatermark
        cv.showLegend = showLegend
        cv.showStatusBadges = showStatusBadges
    }

    @Synchronized
    private fun announceSyntheticVolume(candleCount: Int) {
        val now = System.currentTimeMillis()
        if (now - lastSyntheticVolumeNoticeAt < 10 * 60_000L) return
        lastSyntheticVolumeNoticeAt = now
        val ctx = appCtx ?: return
        val body = "این داده حجم واقعی ندارد؛ برای ادامه، حجم تقریبی از دامنهٔ کندل‌ها ساخته شد (${Fa.d(candleCount.toString())} کندل)."
        Alerts.fire(ctx, AlertKind.FEED, "حجم تقریبی جایگزین شد", body)
        main.post { Toast.makeText(ctx, body, Toast.LENGTH_LONG).show() }
    }

    /**
     * لاگر و هشدار را به موتور، کارگزار و همهٔ بخش‌ها وصل می‌کند.
     * اگر لاگر خاموش باشد، قلاب‌ها null می‌شوند تا هیچ هزینه‌ای تحمیل نشود.
     */
    fun wireLogging() {
        if (Log.enabled) {
            engine.logSink = { cat, msg, data -> Log.i(cat, msg, data) }
            broker.logSink = { cat, msg, data -> Log.i(cat, msg, data) }
        } else {
            engine.logSink = null
            broker.logSink = null
        }
        val c = appCtx
        engine.alertSink = { kind, title, body -> Alerts.fire(c, kind, title, body) }
        broker.alertSink = { kind, title, body -> Alerts.fire(c, kind, title, body) }
    }

    fun onChange(f: () -> Unit) { listeners.add(f) }

    fun notifyUi() {
        val run = Runnable {
            // هر شنونده جدا محافظت می‌شود تا یک خطا کل رابط را از کار نیندازد
            for (f in ArrayList(listeners)) {
                try { f() } catch (t: Throwable) { Log.e(Log.CAT_UI, "خطا در شنوندهٔ رابط", t) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    fun offChange(f: () -> Unit) { listeners.remove(f) }

    // ── تنظیمات و بازسازی ──────────────────────────────────────────────────────
    /** بازسازی غیرهمزمان؛ تغییرات تنظیمات هرگز نخ رابط را قفل نمی‌کنند. */
    fun rebuildAsync(clearOrders: Boolean = true) {
        busy = true
        busyText = "در حال بازسازی موتور…"
        notifyUi()
        io.execute {
            try {
                rebuild(clearOrders)
            } catch (e: Throwable) {
                Log.e(Log.CAT_ENGINE, "بازسازی موتور ناموفق بود", e)
                lastFeedError = "خطای بازسازی موتور: ${e.message}"
            } finally {
                busy = false
                busyText = ""
                notifyUi()
            }
        }
    }

    /** بعد از تغییر تنظیمات: موتور و کارگزار از صفر روی دادهٔ مود انتخاب‌شده (modeCandles) اجرا می‌شوند. */
    fun rebuild(clearOrders: Boolean = true) {
        synchronized(stateLock) {
        val execCandles = if (modeCandles.isNotEmpty()) modeCandles else candles
        val t0 = System.currentTimeMillis()
        Log.i(Log.CAT_ENGINE, "شروع اجرای مجدد موتور",
            "کندل‌مود=${execCandles.size} مود=${cfg.tfMode} (tf2=${com.alisport.goldpin.core.Tf.label(cfg.tf2)}) چارت=${com.alisport.goldpin.core.Tf.label(chartTfSec)} بک‌تست‌کامل=$backtestFull پاک‌کردن‌سفارش=$clearOrders")
        engine.reset()
        engine.broker = broker
        broker.engine = engine
        if (clearOrders) broker.reset()
        engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
        val prev = Alerts.inBacktest
        Alerts.inBacktest = true
        try {
            if (execCandles.isNotEmpty()) engine.feed(execCandles, lastIsClosed = backtestFull)
        } finally {
            // ⚠ بدون finally، هر استثنا داخل feed این پرچم را برای همیشه true می‌گذاشت
            // و از آن لحظه به بعد هیچ بنر/نوتیفیکیشن/صدایی پخش نمی‌شد.
            Alerts.inBacktest = prev
        }
        engine.broker = broker
        Log.i(Log.CAT_ENGINE, "اجرای موتور تمام شد",
            "مدت=${System.currentTimeMillis() - t0}ms پردازش‌شده=${engine.processed} ستاپ=${engine.cnt.setup} برخورد=${engine.cnt.touch} " +
                "میانی=${engine.cnt.mid} ولوم‌کم=${engine.cnt.lv} ولوم‌زیاد=${engine.cnt.hv} ورود=${engine.cnt.entry} پایان=${engine.cnt.done} " +
                "باکس=${engine.zones.size} سفارش=${broker.orders.size} معامله=${broker.trades.size} موجودی=${Fa.n(broker.balance, 2)}")
        publishSnapshot()
        notifyUi()
        }
    }

    private fun aggregateBestAvailable(targetTfSec: Int): List<Candle> {
        if (targetTfSec == cfg.tf2 && modeCandles.isNotEmpty()) return modeCandles
        val fineStep = medianStepSec(fineCandles)
        val modeStep = medianStepSec(modeCandles)
        val curStep = medianStepSec(candles)
        val src = when {
            !modeFromFeed && fineCandles.isNotEmpty() && fineStep in 1L..targetTfSec.toLong() -> fineCandles
            modeCandles.isNotEmpty() && modeStep in 1L..targetTfSec.toLong() -> modeCandles
            fineCandles.isNotEmpty() && fineStep in 1L..targetTfSec.toLong() -> fineCandles
            candles.isNotEmpty() && curStep in 1L..targetTfSec.toLong() -> candles
            modeCandles.isNotEmpty() -> modeCandles
            fineCandles.isNotEmpty() -> fineCandles
            else -> candles
        }
        if (src.isEmpty()) return emptyList()
        val srcStep = medianStepSec(src)
        val agg = if (sameTimeframe(srcStep, targetTfSec.toLong())) src else Feed.aggregate(src, targetTfSec)
        return if (useSyntheticVolume) Feed.synthesizeVolumeIfMissing(agg) else agg
    }

    private fun mergeDisplayWithMode(chart: List<Candle>, targetTfSec: Int): List<Candle> {
        if (chart.isEmpty()) return aggregateBestAvailable(targetTfSec)
        if (modeCandles.isEmpty() || targetTfSec < cfg.tf2) return chart
        if (targetTfSec == cfg.tf2) return modeCandles
        val aggMode = Feed.aggregate(modeCandles, targetTfSec)
        if (aggMode.isEmpty()) return chart
        val firstModeT = aggMode.first().t
        val older = chart.filter { it.t < firstModeT }
        if (older.isEmpty()) return aggMode
        val combined = ArrayList<Candle>(older.size + aggMode.size)
        combined.addAll(older)
        combined.addAll(aggMode)
        return combined.mapIndexed { idx, c -> Candle(idx, c.t, c.o, c.h, c.l, c.c, c.v) }
    }

    private fun fetchChartHistoryBlocking(targetTfSec: Int): Pair<List<Candle>, Int> {
        var base = Feed.baseTfFor(targetTfSec)
        val (interval, range) = Feed.yahooSpec(base, depth)
        var raw = Feed.yahooChart(symbol, interval, range)
        if (raw.size < 30 && base != Tf.M1 && targetTfSec < Tf.H1) {
            try {
                val r1m = Feed.yahooChart(symbol, "1m", "7d")
                if (r1m.size > raw.size) {
                    raw = r1m
                    base = Tf.M1
                }
            } catch (_: Exception) { }
        }
        var chart = if (base == targetTfSec) raw else Feed.aggregate(raw, targetTfSec)
        if (useSyntheticVolume) chart = Feed.synthesizeVolumeIfMissing(chart)
        return chart to base
    }

    fun setMode(modeIdx: Int) {
        Log.i(Log.CAT_CFG, "تغییر مود تایم‌فریمی", "مود=$modeIdx")
        val m = TF_MODES.firstOrNull { it.idx == modeIdx } ?: TF_MODES[0]
        busy = true
        busyText = "در حال تغییر مود تایم‌فریمی…"
        notifyUi()
        cfg.tfMode = m.idx
        val tfChanged = !m.custom && (chartTfSec != m.t2 || !sameTimeframe(medianStepSec(modeCandles), m.t2.toLong()))
        if (!m.custom) {
            cfg.tfS = m.s; cfg.tfM = m.m; cfg.tf1 = m.t1; cfg.tf2 = m.t2
            chartTfSec = m.t2
        }
        io.execute {
            try {
                val cached = tfFeedCache[cfg.tf2]
                if (modeFromFeed && cached != null && cached.isNotEmpty()) {
                    setCandles(cached, rebuildNow = false)
                } else if (tfChanged || !sameTimeframe(medianStepSec(modeCandles), cfg.tf2.toLong())) {
                    val local = aggregateBestAvailable(cfg.tf2)
                    if (local.isNotEmpty()) setCandles(local, rebuildNow = false)
                }
                rebuild()
            } catch (e: Throwable) {
                Log.e(Log.CAT_ENGINE, "تغییر مود ناموفق بود", e)
                lastFeedError = "خطای تغییر مود: ${e.message}"
            } finally {
                busy = false
                busyText = ""
                notifyUi()
            }
        }
        if (!m.custom && modeFromFeed && tfFeedCache[cfg.tf2] == null) {
            downloadHistory()
        }
    }

    /** تغییر تایم‌فریم تریگر ۲ در مود ۵ (سفارشی) و بازسازی موتور مود */
    fun setCustomTf2(newTf2: Int) {
        if (newTf2 <= 0) return
        cfg.tf2 = newTf2
        chartTfSec = newTf2
        io.execute {
            val cached = tfFeedCache[newTf2]
            val local = if (modeFromFeed && cached != null && cached.isNotEmpty()) cached else aggregateBestAvailable(newTf2)
            if (local.isNotEmpty()) setCandles(local, rebuildNow = true) else rebuild()
        }
        if (modeFromFeed && tfFeedCache[newTf2] == null) {
            downloadHistory()
        }
    }

    fun setCandles(list: List<Candle>, rebuildNow: Boolean = true) {
        Log.i(Log.CAT_FEED, "داده در اپ گذاشته شد", "کندل=${list.size}")
        busy = true
        try {
            val copy = ArrayList(list)
            val step = medianStepSec(copy)
            if (copy.isNotEmpty() && (fineCandles.isEmpty() || (step in 1L..90L && copy.size >= fineCandles.size))) {
                fineCandles = copy
            }
            modeCandles = copy
            candles = if (chartTfSec == cfg.tf2 || sameTimeframe(step, chartTfSec.toLong()) || copy.isEmpty()) {
                copy
            } else {
                aggregateBestAvailable(chartTfSec).ifEmpty { copy }
            }
            if (rebuildNow) rebuild() else synchronized(stateLock) { publishSnapshot() }
        } finally {
            busy = false
        }
        notifyUi()
    }

    /** تعویض فقط کندل‌های نمایشی چارت، بدون تغییر مود و بدون بازسازی/تغییر باکس‌های ساختار و تریگر ۱ و ۲ در حافظه */
    fun setDisplayCandles(list: List<Candle>) {
        val copy = ArrayList(list)
        val step = medianStepSec(copy)
        if (copy.isNotEmpty() && (fineCandles.isEmpty() || (step in 1L..90L && copy.size >= fineCandles.size))) {
            fineCandles = copy
        }
        synchronized(stateLock) {
            candles = copy
            publishSnapshot()
        }
        notifyUi()
    }

    // ── دانلود تاریخچه از فید ───────────────────────────────────────────────────
    fun downloadHistory(onDone: (String) -> Unit = {}) {
        val t0 = System.currentTimeMillis()
        val targetTf = chartTfSec
        val modeTf = cfg.tf2
        busy = true
        busyText = "در حال دانلود تاریخچهٔ طلا (${com.alisport.goldpin.core.Tf.label(targetTf)})…"
        lastFeedError = null
        notifyUi()
        Log.i(Log.CAT_FEED, "شروع دانلود تاریخچه", "نماد=$symbol چارت=${com.alisport.goldpin.core.Tf.label(targetTf)} مود=${com.alisport.goldpin.core.Tf.label(modeTf)} عمق=$depth")
        net.execute {
            try {
                val needModeDownload = (targetTf == modeTf || !modeFromFeed || modeCandles.isEmpty() || !sameTimeframe(medianStepSec(modeCandles), modeTf.toLong()))
                val (modeChart, modeBase) = if (needModeDownload) fetchChartHistoryBlocking(modeTf) else (modeCandles to baseTfSec)
                val (chart, base) = if (targetTf == modeTf) (modeChart to modeBase) else fetchChartHistoryBlocking(targetTf)
                val n = chart.size
                Log.i(Log.CAT_FEED, "دانلود تاریخچه موفق",
                    "کندل=$n مدت=${System.currentTimeMillis() - t0}ms پایه=${com.alisport.goldpin.core.Tf.label(base)} چارت=${com.alisport.goldpin.core.Tf.label(targetTf)}")
                io.execute {
                    try {
                        baseTfSec = base
                        modeFromFeed = true
                        if (modeChart.isNotEmpty()) tfFeedCache[modeTf] = modeChart
                        if (chart.isNotEmpty()) tfFeedCache[targetTf] = chart
                        if (needModeDownload) {
                            busyText = "در حال محاسبهٔ موتور روی ${com.alisport.goldpin.util.Fa.d(modeChart.size.toString())} کندل…"
                            setCandles(modeChart, rebuildNow = true)
                        }
                        if (targetTf != modeTf) {
                            setDisplayCandles(mergeDisplayWithMode(chart, targetTf))
                        }
                    } finally {
                        main.post {
                            lastFeedAt = System.currentTimeMillis()
                            lastFeedError = null
                            busy = false
                            busyText = ""
                            notifyUi()
                            onDone("دانلود شد: ${com.alisport.goldpin.util.Fa.d(n.toString())} کندل (${com.alisport.goldpin.core.Tf.label(targetTf)})")
                        }
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "خطای نامشخص"
                Log.e(Log.CAT_FEED, "دانلود تاریخچه ناموفق", e)
                main.post {
                    lastFeedError = com.alisport.goldpin.util.Fa.short(msg, 160)
                    busy = false
                    busyText = ""
                    notifyUi()
                    onDone("خطای دانلود: ${com.alisport.goldpin.util.Fa.short(msg, 160)}")
                }
            }
        }
    }

    /**
     * به‌روزرسانی زنده: فقط دنبالهٔ دادهٔ مود (cfg.tf2) را می‌گیریم و با سری مود ادغام می‌کنیم.
     *
     * سه لایهٔ محافظ در برابر فریز:
     *  ۱) [liveGate] — حداکثر یک تیک در جریان؛ تیک‌های اضافی **حذف** می‌شوند تا صف رشد نکند.
     *  ۲) شبکه روی [net] است نه [io] ⇒ socket معطل، کارهای رابط را گروگان نمی‌گیرد.
     *  ۳) تغییر وضعیت روی [io] و زیر [stateLock] ⇒ با rebuild() هم‌زمان نمی‌شود.
     */
    fun updateLiveOnce(notifyDone: Boolean = false, onDone: (String) -> Unit = {}) {
        // ۱) اگر تیک قبلی هنوز تمام نشده، این تیک حذف می‌شود (نه اینکه در صف بایستد).
        if (!liveGate.tryAcquire()) {
            Log.w(Log.CAT_LIVE, "تیک لایو حذف شد — تیک قبلی هنوز در جریان است",
                "حذف‌شده تاکنون=${liveGate.skipped} (فید کند است؛ تیک بعدی همان دنباله را می‌گیرد)")
            lastLiveSkips = liveGate.skipped
            if (notifyDone) onDone("فید کند است — تیک قبلی هنوز در جریان است")
            return
        }
        val t0 = System.currentTimeMillis()
        // ۲) شبکه روی نخ جدا (همواره روی تایم‌فریم تریگر ۲ مود انتخاب‌شده تا باکس‌ها در حافظه ثابت بمانند)
        net.execute {
            var handedOff = false
            try {
                val modeTf = cfg.tf2
                val base = Feed.baseTfFor(modeTf)
                val (interval, range) = Feed.yahooSpec(base, 1)
                val raw = Feed.yahooChart(symbol, interval, range)
                var tail = if (base == modeTf) raw else Feed.aggregate(raw, modeTf)
                if (useSyntheticVolume) tail = Feed.synthesizeVolumeIfMissing(tail)
                val (spot, spotSrc) = Feed.spotPrice()
                val last = tail.lastOrNull()?.c ?: Double.NaN
                Log.d(Log.CAT_LIVE, "به‌روزرسانی زنده",
                    "دنباله=${tail.size} قیمت=${if (spot != null) Fa.n(spot, 2) else "-"} منبع=$spotSrc مدت=${System.currentTimeMillis() - t0}ms")
                // ۳) تغییر وضعیت روی نخ سریالی [io]
                io.execute {
                    try {
                        feedLiveTail(tail)
                    } finally {
                        liveGate.release()
                    }
                    liveFailStreak = 0
                    main.post {
                        lastLivePrice = if (spot != null && !spot.isNaN()) spot else last
                        lastPriceSource = spotSrc
                        lastFeedAt = System.currentTimeMillis()
                        lastFeedError = null
                        notifyUi()
                        if (notifyDone) onDone("به‌روزرسانی شد · ${com.alisport.goldpin.util.Fa.n(lastLivePrice, 2)}")
                    }
                }
                handedOff = true   // واگذار شد؛ آزادی دروازه به عهدهٔ finally داخل io است
            } catch (e: Exception) {
                liveFailStreak++
                Log.w(Log.CAT_LIVE, "به‌روزرسانی زنده ناموفق",
                    "${e.message ?: ""} · پشت‌سرهم=$liveFailStreak")
                main.post {
                    lastFeedError = com.alisport.goldpin.util.Fa.short(e.message, 160)
                        .ifEmpty { "خطای فید" }
                    notifyUi()
                    if (notifyDone) onDone("خطای فید: ${lastFeedError}")
                }
            } finally {
                // اگر هیچ‌وقت به io واگذار نشد (خطای شبکه یا صف رد شد)، همین‌جا آزاد کن
                if (!handedOff) liveGate.release()
            }
        }
    }

    /** ادغام دنبالهٔ تازه در سری چارت (بر اساس زمان — بدون تکرار و بدون ریپینت) */
    /** فاصلهٔ میانی کندل‌ها بر حسب ثانیه (برای تشخیص تایم‌فریم واقعی یک سری) */
    private fun medianStepSec(list: List<Candle>): Long {
        if (list.size < 2) return 0L
        val steps = ArrayList<Long>(list.size - 1)
        for (i in 1 until list.size) {
            val d = (list[i].t - list[i - 1].t) / 1000L
            if (d > 0) steps.add(d)
        }
        if (steps.isEmpty()) return 0L
        steps.sort()
        return steps[steps.size / 2]
    }

    /** دو فاصلهٔ زمانی «یک تایم‌فریم» حساب می‌شوند؟ (تحمل ۱٫۵ برابر برای لرزش داده) */
    private fun sameTimeframe(a: Long, b: Long): Boolean =
        a > 0 && b > 0 && minOf(a, b) * 3 >= maxOf(a, b) * 2

    /**
     * حداکثر کندل بسته‌ای که یک تیک لایو «طبیعی» ممکن است جدید ببیند. بیشتر از این
     * یعنی بازپخش تاریخچه (اپ بسته بوده یا سری عوض شده) و باید ساکت بماند.
     */
    private val LIVE_CATCHUP_SILENT_BARS = 3

    /**
     * هستهٔ تیک لایو: ادغام دنباله + اجرای موتور، زیر [stateLock] و روی نخ [io].
     *
     * ⚠ **محافظ سیل هشدار** — همین اپ را فریز می‌کرد: سرویس لایو `Alerts.inBacktest`
     * را `false` می‌گذارد تا هشدارها واقعاً پخش شوند. ولی اگر همان تیک اول هزاران کندل
     * **تاریخی** را بازپخش کند، همه «زنده» حساب می‌شدند و برای هر کدام یک
     * `runOnUiThread` (ساخت/حذف View + requestLayout) و یک نوتیفیکیشن ساخته می‌شد ⇒
     * چند هزار کار روی نخ رابط صف می‌شد و اپ کامل فریز می‌شد.
     *
     * حالا اگر کندل‌های بستهٔ جدید از [LIVE_CATCHUP_SILENT_BARS] بیشتر باشد، آن بسته
     * به‌صورت بک‌تست **ساکت** (فقط لاگ، بدون بنر/نوتیفیکیشن/صدا/لرزش) اجرا می‌شود.
     */
    fun feedLiveTail(tail: List<Candle>) {
        synchronized(stateLock) {
            mergeTail(tail)
            val execCandles = if (modeCandles.isNotEmpty()) modeCandles else candles
            val newClosed = (execCandles.size - 1) - engine.processed
            val prev = Alerts.inBacktest
            val silent = newClosed > LIVE_CATCHUP_SILENT_BARS
            if (silent) Alerts.inBacktest = true
            try {
                engine.broker = broker
                engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
                engine.feed(execCandles, lastIsClosed = false)
                publishSnapshot()
            } finally {
                Alerts.inBacktest = prev
            }
            if (silent) {
                lastLiveSilentBars = newClosed
                Log.w(Log.CAT_LIVE, "catch-up replayed silently",
                    "newClosedBars=$newClosed > $LIVE_CATCHUP_SILENT_BARS — inha rokh-dade zende nistand")
            }
        }
    }

    fun mergeTail(tail: List<Candle>) {
        if (tail.isEmpty()) return
        val baseSeries = if (modeCandles.isNotEmpty()) modeCandles else candles
        val before = baseSeries.size
        if (baseSeries.isEmpty()) { setCandles(tail, rebuildNow = true); return }
        val curStep = medianStepSec(baseSeries)
        val tailStep = medianStepSec(tail)
        if (!sameTimeframe(curStep, tailStep)) {
            Log.w(Log.CAT_LIVE, "series timeframe mismatch -> replacing series",
                "sari=$before ba faseleye ${curStep}s · donbale=${tail.size} ba faseleye ${tailStep}s · " +
                    "chart=${com.alisport.goldpin.core.Tf.label(chartTfSec)}")
            setCandles(tail, rebuildNow = true)   // rebuild() خودش ساکت اجرا می‌شود
            return
        }
        val firstNew = tail.first().t
        // حذف بخش هم‌پوشان از انتهای سری فعلی
        var cut = baseSeries.size
        while (cut > 0 && baseSeries[cut - 1].t >= firstNew) cut--
        val merged = ArrayList<Candle>(cut + tail.size)
        for (i in 0 until cut) merged.add(baseSeries[i])
        for (c in tail) merged.add(c)
        val nextMode = merged.mapIndexed { i, c -> Candle(i, c.t, c.o, c.h, c.l, c.c, c.v) }
        modeCandles = nextMode
        candles = if (chartTfSec == cfg.tf2 || sameTimeframe(tailStep, chartTfSec.toLong())) {
            nextMode
        } else {
            val cached = tfFeedCache[chartTfSec]
            if (cached != null && cached.isNotEmpty()) mergeDisplayWithMode(cached, chartTfSec)
            else aggregateBestAvailable(chartTfSec).ifEmpty { nextMode }
        }
        Log.d(Log.CAT_LIVE, "ادغام دنبالهٔ داده", "قبل=$before بعد=${modeCandles.size} جدید=${modeCandles.size - before}")
    }

    /**
     * تغییر تایم‌فریم نمایشی چارت.
     *
     * ⚠ در مود انتخاب‌شده، با تغییر تایم‌فریم چارت:
     *  • سلسله‌مراتب تایم‌فریمی مود (`cfg.tfMode/tfS/tfM/tf1/tf2`) تغییر نمی‌کند؛
     *  • موتور و باکس‌های ساختار (`engine.zones`) و محدوده‌های تریگر ۱ (`t1Zones`/`lvH..lvL`)
     *    و تریگر ۲ (`t2Zones`/`hvH..hvL`) در حافظه ثابت می‌مانند و بازسازی نمی‌شوند؛
     *  • فقط کندل‌های نمایشی چارت (`candles`) به تایم‌فریم انتخاب‌شده تغییر می‌کنند.
     */
    fun changeChartTf(newTf: Int, forceDownload: Boolean = false) {
        if (newTf <= 0) return
        val stepMatches = sameTimeframe(medianStepSec(candles), newTf.toLong()) && candles.size >= 30
        if (!forceDownload && newTf == chartTfSec && stepMatches) return
        Log.i(Log.CAT_CFG, "تغییر تایم‌فریم چارت (باکس‌های مود ثابت در حافظه)",
            "${com.alisport.goldpin.core.Tf.label(chartTfSec)} → ${com.alisport.goldpin.core.Tf.label(newTf)} · مود=${cfg.tfMode} (tf2=${com.alisport.goldpin.core.Tf.label(cfg.tf2)})")
        chartTfSec = newTf

        // ۱) اگر دادهٔ تایم‌فریم مقصد از حافظه (کش فید، کندل‌های مود یا نمونهٔ ریزدانه) قابل ساخت است، بلافاصله نمایش بده
        val cached = tfFeedCache[newTf]
        val canAggregateLocally = (!modeFromFeed && fineCandles.isNotEmpty() && medianStepSec(fineCandles) in 1L..newTf.toLong()) ||
            (modeCandles.isNotEmpty() && medianStepSec(modeCandles) in 1L..newTf.toLong())
        if (!forceDownload) {
            if (newTf == cfg.tf2 && modeCandles.isNotEmpty()) {
                setDisplayCandles(modeCandles)
                return
            }
            if (cached != null && cached.isNotEmpty()) {
                setDisplayCandles(mergeDisplayWithMode(cached, newTf))
                return
            }
            if (canAggregateLocally) {
                val local = aggregateBestAvailable(newTf)
                if (local.isNotEmpty()) {
                    setDisplayCandles(local)
                    return
                }
            }
        }

        // ۲) در صورت نیاز به دریافت از شبکه (مثلاً تایم‌فریم ریزتر در حالت آنلاین یا forceDownload):
        //    فقط کندل‌های نمایشی چارت به‌روز می‌شوند و موتور/باکس‌های مود در حافظه دست‌نخورده می‌مانند.
        busy = true
        busyText = "در حال دریافت تاریخچهٔ ${com.alisport.goldpin.core.Tf.label(newTf)}…"
        lastFeedError = null
        notifyUi()
        net.execute {
            try {
                val (chart, base) = fetchChartHistoryBlocking(newTf)
                io.execute {
                    try {
                        baseTfSec = base
                        if (chart.isNotEmpty()) tfFeedCache[newTf] = chart
                        if (modeCandles.isEmpty()) {
                            modeFromFeed = true
                            setCandles(chart, rebuildNow = true)
                        } else {
                            setDisplayCandles(mergeDisplayWithMode(chart, newTf))
                        }
                    } finally {
                        main.post {
                            lastFeedAt = System.currentTimeMillis()
                            lastFeedError = null
                            busy = false
                            busyText = ""
                            notifyUi()
                        }
                    }
                }
            } catch (e: Exception) {
                // بدون شبکه: از بهترین دادهٔ موجود در حافظه تجمیع می‌کنیم (بدون بازسازی موتور)
                io.execute {
                    try {
                        val chart = aggregateBestAvailable(newTf)
                        if (modeCandles.isEmpty() && chart.isNotEmpty()) {
                            setCandles(chart, rebuildNow = true)
                        } else if (chart.isNotEmpty()) {
                            setDisplayCandles(chart)
                        }
                    } finally {
                        main.post {
                            lastFeedError = com.alisport.goldpin.util.Fa.short(
                                "تجمیع داخلی از دادهٔ موجود: ${e.message}", 160
                            )
                            busy = false
                            busyText = ""
                            notifyUi()
                        }
                    }
                }
            }
        }
    }

    // ── ذخیره / بازیابی ────────────────────────────────────────────────────────
    fun buildJson(): String {
        val meta = linkedMapOf<String, Any?>(
            "symbol" to symbol,
            "baseTfSec" to baseTfSec,
            "chartTfSec" to chartTfSec,
            "depth" to depth,
            "appVersion" to 2
        )
        // ⚠ موتور/کارگزار در نخ IO تغییر می‌کنند؛ بدون قفل، Store.save روی فهرست‌های در حال
        // تغییر می‌افتاد و ConcurrentModificationException می‌داد (ذخیرهٔ خودکار حین لایو).
        val persistedCandles = if (modeCandles.isNotEmpty()) modeCandles else candles
        return synchronized(stateLock) { Store.save(cfg, engine, broker, persistedCandles, meta) }
    }

    fun saveToAutoFile(ctx: Context): String {
        val t0 = System.currentTimeMillis()
        return try {
            busy = true
            val json = buildJson()
            val main = Storage.autoFile(ctx)
            try {   // پشتیبان از آخرین نسخهٔ سالم
                if (main.exists()) {
                    val tmp = java.io.File(main.parentFile, main.name + ".tmp")
                    main.copyTo(tmp, overwrite = true)
                    val bak = java.io.File(main.parentFile, main.name + ".bak")
                    if (bak.exists()) bak.delete()
                    tmp.renameTo(bak)
                }
            } catch (e: Exception) { Log.w(Log.CAT_STORE, "نسخهٔ پشتیبان ساخته نشد", e.message ?: "") }
            Storage.writeText(main, json)
            Log.i(Log.CAT_STORE, "ذخیرهٔ خودکار انجام شد",
                "بایت=${json.length} کندل=${candles.size} باکس=${engine.zones.size} سفارش=${broker.orders.size} معامله=${broker.trades.size} مدت=${System.currentTimeMillis() - t0}ms")
            "ذخیره شد: ${Storage.autoFile(ctx).absolutePath}"
        } catch (e: Exception) {
            Log.e(Log.CAT_STORE, "ذخیرهٔ خودکار ناموفق", e)
            "خطای ذخیره: ${e.message}"
        } finally {
            busy = false
        }
    }

    fun loadFromAutoFile(ctx: Context): String {
        val f: File = Storage.autoFile(ctx)
        if (!f.exists()) return "فایل ذخیره‌ای پیدا نشد"
        return try {
            loadFromText(Storage.readText(f))
        } catch (e: Exception) {
            // هرگز اپ را با فایل خراب از کار نینداز؛ فقط اطلاع بده
            Log.e(Log.CAT_STORE, "بازیابی خودکار ناموفق بود", e)
            "بازیابی نشد: ${e.message}"
        }
    }

    /** بارگذاری کامل — وضعیت دقیقاً همان‌طور که ذخیره شده بود برمی‌گردد. */
    fun loadFromText(json: String): String {
        val t0 = System.currentTimeMillis()
        Log.i(Log.CAT_STORE, "شروع بازیابی وضعیت", "بایت=${json.length}")
        return try {
            // تجزیهٔ JSON (سنگین) بیرون از قفل؛ کپی وضعیت داخل قفل
            val ld = Store.load(json)
            val m = ld.meta
            symbol = m["symbol"]?.toString() ?: symbol
            baseTfSec = (m["baseTfSec"] as? Long)?.toInt() ?: baseTfSec
            depth = (m["depth"] as? Long)?.toInt() ?: depth
            synchronized(stateLock) {
                copySettings(ld.cfg, cfg)
                chartTfSec = (m["chartTfSec"] as? Long)?.toInt() ?: cfg.tf2
                engine.reset()
                broker.reset()
                // انتقال وضعیت بازیابی‌شده به نمونه‌های سراسری
                copyEngine(ld.eng, engine)
                copyBroker(ld.broker, broker)
                engine.broker = broker
                broker.engine = engine
                engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
                val restoredMode = ArrayList(ld.candles)
                val step = medianStepSec(restoredMode)
                if (restoredMode.isNotEmpty() && (fineCandles.isEmpty() || (step in 1L..90L && restoredMode.size >= fineCandles.size))) {
                    fineCandles = restoredMode
                }
                modeCandles = restoredMode
                candles = if (chartTfSec == cfg.tf2 || sameTimeframe(step, chartTfSec.toLong()) || restoredMode.isEmpty()) {
                    restoredMode
                } else {
                    aggregateBestAvailable(chartTfSec).ifEmpty { restoredMode }
                }
                publishSnapshot()
            }
            notifyUi()
            Log.i(Log.CAT_STORE, "بازیابی موفق",
                "کندل=${candles.size} باکس=${engine.zones.size} (فعال=${engine.zones.count { it.status == ZoneStatus.ACTIVE }} " +
                    "پاک‌شده=${engine.zones.count { it.status == ZoneStatus.DELETED }} ردشده=${engine.zones.count { it.status == ZoneStatus.REJECTED }}) " +
                    "سفارش=${broker.orders.size} معامله=${broker.trades.size} موجودی=${Fa.n(broker.balance, 2)} مدت=${System.currentTimeMillis() - t0}ms")
            "بازیابی شد: ${com.alisport.goldpin.util.Fa.d(candles.size.toString())} کندل · " +
                    "${com.alisport.goldpin.util.Fa.d(broker.trades.size.toString())} معامله · " +
                    "${com.alisport.goldpin.util.Fa.d(engine.zones.size.toString())} باکس ناحیه"
        } catch (e: Exception) {
            Log.e(Log.CAT_STORE, "بازیابی ناموفق", e)
            "خطای بازیابی: ${e.message}"
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun copySettings(from: Settings, to: Settings) {
        val c = from.copy()
        to.tfMode = c.tfMode; to.tfS = c.tfS; to.tfM = c.tfM; to.tf1 = c.tf1; to.tf2 = c.tf2
        to.vpRows = c.vpRows; to.vpVA = c.vpVA; to.vpSmooth = c.vpSmooth; to.distMode = c.distMode
        to.minZoneRows = c.minZoneRows; to.drawZones = c.drawZones; to.showRejectedZones = c.showRejectedZones
        to.clearUsedZones = c.clearUsedZones; to.maxZoneBoxes = c.maxZoneBoxes; to.showPocVA = c.showPocVA
        to.maxSetups = c.maxSetups; to.midInvalidClose = c.midInvalidClose
        to.hvScanFirstTouch = c.hvScanFirstTouch; to.hvMarkFirstIncrease = c.hvMarkFirstIncrease
        to.rejectCandleNext = c.rejectCandleNext
        to.mintick = c.mintick; to.slBufTicks = c.slBufTicks; to.minTPunits = c.minTPunits
        to.useRiskPct = c.useRiskPct; to.riskPct = c.riskPct; to.equityPct = c.equityPct
        to.maxLeverage = c.maxLeverage; to.roundQty = c.roundQty
        to.contractSize = c.contractSize; to.initialEquity = c.initialEquity
    }

    /** کپی وضعیت موتورِ بازگردانی‌شده به موتور سراسری (تا ارجاع‌ها یکسان بماند) */
    private fun copyEngine(src: Engine, dst: Engine) {
        dst.aggS.reset(); dst.aggM.reset(); dst.agg1.reset()
        copyAgg(src.aggS, dst.aggS); copyAgg(src.aggM, dst.aggM); copyAgg(src.agg1, dst.agg1)
        dst.structBars.clear(); dst.structBars.addAll(src.structBars)
        dst.microS.clear(); dst.microS.addAll(src.microS)
        dst.pins.clear(); dst.pins.addAll(src.pins)
        dst.setups.clear(); dst.setups.addAll(src.setups)
        dst.zones.clear(); dst.zones.addAll(src.zones)
        dst.t1Zones.clear(); dst.t1Zones.addAll(src.t1Zones)
        dst.t2Zones.clear(); dst.t2Zones.addAll(src.t2Zones)
        dst.markers.clear(); dst.markers.addAll(src.markers)
        dst.events.clear(); dst.events.addAll(src.events)
        dst.cnt.setup = src.cnt.setup; dst.cnt.touch = src.cnt.touch; dst.cnt.mid = src.cnt.mid
        dst.cnt.lv = src.cnt.lv; dst.cnt.hv = src.cnt.hv; dst.cnt.entry = src.cnt.entry
        dst.cnt.done = src.cnt.done; dst.cnt.zoneCount = src.cnt.zoneCount; dst.cnt.zoneRejected = src.cnt.zoneRejected
        dst.trend = src.trend; dst.HH = src.HH; dst.HL = src.HL; dst.LL = src.LL; dst.LH = src.LH
        dst.tempBoxTop = src.tempBoxTop; dst.tempBoxBot = src.tempBoxBot
        dst.hlPinLo = src.hlPinLo; dst.hlPinRef = src.hlPinRef; dst.hlPinHi = src.hlPinHi; dst.hlPinSeq = src.hlPinSeq
        dst.hhPinHi = src.hhPinHi; dst.hhPinRef = src.hhPinRef; dst.hhPinLo = src.hhPinLo; dst.hhPinSeq = src.hhPinSeq
        dst.lhPinHi = src.lhPinHi; dst.lhPinRef = src.lhPinRef; dst.lhPinLo = src.lhPinLo; dst.lhPinSeq = src.lhPinSeq
        dst.llPinLo = src.llPinLo; dst.llPinRef = src.llPinRef; dst.llPinHi = src.llPinHi; dst.llPinSeq = src.llPinSeq
        dst.seqStart = src.seqStart
        dst.bullBU1Low = src.bullBU1Low; dst.bullBEHigh = src.bullBEHigh
        dst.bullBU2Low = src.bullBU2Low; dst.bullBU2Bi = src.bullBU2Bi
        dst.bearBE1High = src.bearBE1High; dst.bearBULow = src.bearBULow
        dst.bearBE2High = src.bearBE2High; dst.bearBE2Bi = src.bearBE2Bi
        dst.bullSetup = src.bullSetup; dst.bearSetup = src.bearSetup
        dst.expectedReady = src.expectedReady
        dst.lowestBUpin = src.lowestBUpin; dst.highestBE = src.highestBE
        dst.lastBUpin = src.lastBUpin; dst.lastBEpin = src.lastBEpin
        dst.buActive = src.buActive; dst.beActive = src.beActive
        dst.pinSeq = src.pinSeq; dst.buSeq = src.buSeq; dst.beSeq = src.beSeq
        dst.lastBUpinRef = src.lastBUpinRef; dst.lastBUpinHi = src.lastBUpinHi
        dst.lastBEpinRef = src.lastBEpinRef; dst.lastBEpinLo = src.lastBEpinLo
        dst.lastZoneTop = src.lastZoneTop; dst.lastZoneBot = src.lastZoneBot
        dst.lastPocPx = src.lastPocPx; dst.lastProfBars = src.lastProfBars
        dst.lastEntryPx = src.lastEntryPx; dst.lastSlPx = src.lastSlPx; dst.lastTpPx = src.lastTpPx
        dst.lastResult = src.lastResult
        dst.prevT1Vol = src.prevT1Vol; dst.prevChartVol = src.prevChartVol
        dst.zoneSeq = src.zoneSeq; dst.setupSeq = src.setupSeq; dst.processed = src.processed
        dst.curBi = src.curBi; dst.curT = src.curT
    }

    private fun copyAgg(s: Agg, d: Agg) {
        d.reset()
        d.periods = s.periods
        d.cur = s.cur?.let { Candle(it.bi, it.t, it.o, it.h, it.l, it.c, it.v) }
        d.cl = s.cl?.let { Candle(it.bi, it.t, it.o, it.h, it.l, it.c, it.v) }
        d.closedNow = false
    }

    private fun copyBroker(src: PaperBroker, dst: PaperBroker) {
        dst.orders.clear(); dst.orders.addAll(src.orders)
        dst.trades.clear(); dst.trades.addAll(src.trades)
        dst.balance = src.balance; dst.equity = src.equity
        dst.maxEquity = src.maxEquity; dst.maxDrawdown = src.maxDrawdown
        dst.orderSeq = src.orderSeq; dst.tradeSeq = src.tradeSeq
        // v1.4.9: همهٔ سفارش‌ها/پوزیشن‌های فعال کپی شوند.
        for (o in src.pendingOrders) dst.restorePending(o)
        for (t in src.openTrades) dst.restoreOpen(t)
    }

    /** پاک کردن کامل */
    fun wipe() {
        Log.w(Log.CAT_APP, "پاک کردن همهٔ داده‌ها و وضعیت")
        synchronized(stateLock) {
            candles = emptyList()
            modeCandles = emptyList()
            tfFeedCache.clear()
            modeFromFeed = false
            broker.reset()
            engine.reset()
            engine.broker = broker; broker.engine = engine
            engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
            publishSnapshot()
        }
        notifyUi()
    }

    /** هستهٔ همگام بارگذاری نمونهٔ داخلی — باید روی نخ پس‌زمینه صدا زده شود. */
    private fun loadSampleBlocking(ctx: Context): Int {
        val n = Feed.openSampleAsset(ctx).use { ins ->
            val text = Feed.readCsvStream(ins)
            val raw = Feed.parseCsv(text)
            fineCandles = ArrayList(raw)
            modeFromFeed = false
            tfFeedCache.clear()
            val modeList = if (cfg.tf2 <= Tf.M1) raw else Feed.aggregate(raw, cfg.tf2)
            Log.i(Log.CAT_FEED, "دادهٔ نمونهٔ داخلی بارگذاری شد", "کندل‌مود=${modeList.size} مود=${cfg.tfMode} (tf2=${com.alisport.goldpin.core.Tf.label(cfg.tf2)})")
            setCandles(modeList, rebuildNow = true)
            candles.size
        }
        return n
    }

    /** بارگذاری دادهٔ نمونهٔ داخلی (آفلاین) */
    fun loadSample(ctx: Context, onDone: (String) -> Unit) {
        io.execute {
            try {
                val n = loadSampleBlocking(ctx)
                main.post {
                    onDone("نمونهٔ داخلی بارگذاری شد: ${com.alisport.goldpin.util.Fa.d(n.toString())} کندل")
                }
            } catch (e: Exception) {
                Log.e(Log.CAT_FEED, "خطا در دادهٔ نمونه", e)
                main.post { onDone("خطا در نمونهٔ داخلی: ${e.message}") }
            }
        }
    }

    /**
     * بازیابی وضعیت هنگام بالا آمدن اپ — **روی نخ پس‌زمینه**.
     * پیش‌تر در onCreate به‌صورت همگام اجرا می‌شد: خواندن و تجزیهٔ یک فایل چند مگابایتی
     * روی نخ رابط = صفحهٔ شروع فریز و خطر ANR.
     * اگر فایل ذخیره‌ای نباشد، دادهٔ نمونه بارگذاری می‌شود.
     */
    fun restoreOrSample(ctx: Context, onDone: (String) -> Unit) {
        busy = true
        busyText = "در حال بازیابی وضعیت…"
        notifyUi()
        io.execute {
            val msg = try {
                val f = Storage.autoFile(ctx)
                if (f.exists()) {
                    loadFromText(Storage.readText(f))
                } else {
                    Log.w(Log.CAT_APP, "فایل ذخیره پیدا نشد → بارگذاری دادهٔ نمونه")
                    loadSampleBlocking(ctx)
                    "فایل ذخیره‌ای نبود — دادهٔ نمونه بارگذاری شد"
                }
            } catch (e: Exception) {
                // هرگز اپ را با فایل خراب از کار نینداز؛ فقط اطلاع بده
                Log.e(Log.CAT_STORE, "بازیابی خودکار ناموفق بود", e)
                "بازیابی نشد: ${e.message}"
            }
            main.post {
                busy = false
                busyText = ""
                onDone(msg)
            }
        }
    }

    /** ورود CSV از حافظهٔ دستگاه */
    fun importCsv(ctx: Context, uri: android.net.Uri, baseTfGuess: Int, onDone: (String) -> Unit) {
        Log.i(Log.CAT_FEED, "ورود CSV", "uri=$uri پایه=${com.alisport.goldpin.core.Tf.label(baseTfGuess)}")
        io.execute {
            try {
                val text = Storage.readStream(ctx.contentResolver.openInputStream(uri)!!, gz = false)
                val raw = Feed.parseCsv(text)
                baseTfSec = baseTfGuess
                modeFromFeed = false
                tfFeedCache.clear()
                if (baseTfGuess <= 90 && raw.isNotEmpty()) fineCandles = ArrayList(raw)
                var modeList = if (baseTfGuess != cfg.tf2) Feed.aggregate(raw, cfg.tf2) else raw
                if (useSyntheticVolume) modeList = Feed.synthesizeVolumeIfMissing(modeList)
                setCandles(modeList, rebuildNow = true)
                main.post {
                    onDone("وارد شد: ${com.alisport.goldpin.util.Fa.d(candles.size.toString())} کندل")
                }
            } catch (e: Exception) {
                Log.e(Log.CAT_FEED, "خطای ورود فایل CSV", e)
                main.post { onDone("خطای ورود فایل: ${e.message}") }
            }
        }
    }

    companion object {
        val instance = AppState()

        /** از MainActivity فراخوانی می‌شود: لاگر + هشدارها را آماده و وصل می‌کند */
        fun init(ctx: Context) = instance.initApp(ctx)
    }
}
