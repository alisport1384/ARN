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

    /** کندل‌های تایم‌فریم چارت (تریگر ۲) که موتور روی آن‌ها اجرا می‌شود */
    /**
     * فهرست کندل‌ها. به‌صورت «تعویض اتمیک» به‌روزرسانی می‌شود تا نخ رابط
     * هرگز فهرست نیمه‌ساخته نبیند (منشأ قبلی فریز و پرش).
     */
    @Volatile
    var candles: List<Candle> = emptyList()
        private set

    /** کش آخرین سری ریزدانه (۱ دقیقه) برای تجمیع آفلاین هنگام بازگشت از تایم‌فریم‌های بالاتر به پایین‌تر */
    @Volatile
    private var fineCandles: List<Candle> = emptyList()

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
        val lastResult: String
    )

    @Volatile
    private var snap: UiSnapshot = UiSnapshot(
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), null, null, emptyList(), emptyList(),
        Double.NaN, Double.NaN, Double.NaN, Double.NaN, "-"
    )

    /** ساخت و انتشار اتمیک snapshot — باید بلافاصله بعد از هر تغییر وضعیت صدا زده شود. */
    private fun publishSnapshot() {
        snap = UiSnapshot(
            candles.toList(), engine.zones.toList(), engine.markers.toList(), engine.setups.toList(),
            engine.events.toList(), broker.orders.toList(), broker.trades.toList(),
            broker.openTrade, broker.pendingOrder, broker.openTrades, broker.pendingOrders,
            engine.lastPocPx, engine.lastEntryPx,
            engine.lastSlPx, engine.lastTpPx, engine.lastResult
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
        Log.init(appCtx!!, "1.4.11")
        Alerts.init(appCtx!!)
        val p = appCtx!!.getSharedPreferences("goldpin", Context.MODE_PRIVATE)
        // مهاجرت ۱٫۱ → ۱٫۲: تمام‌صفحهٔ خودکار دیگر پیش‌فرض نیست؛ تنظیم قدیمی true را یک‌بار خاموش کن.
        val uiVersion = p.getInt("ui_prefs_version", 0)
        autoFullscreen = if (uiVersion < 2) false else p.getBoolean("auto_fullscreen", false)
        if (uiVersion < 2) p.edit().putBoolean("auto_fullscreen", false).putInt("ui_prefs_version", 2).apply()
        Feed.connectTimeoutMs = p.getInt("feed_connect_timeout_ms", 8000).coerceIn(1000, 120000)
        Feed.readTimeoutMs = p.getInt("feed_read_timeout_ms", 12000).coerceIn(1000, 120000)
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
            .apply()
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

    /** بعد از تغییر تنظیمات: موتور و کارگزار از صفر روی دادهٔ موجود اجرا می‌شوند. */
    fun rebuild(clearOrders: Boolean = true) {
        synchronized(stateLock) {
        val t0 = System.currentTimeMillis()
        Log.i(Log.CAT_ENGINE, "شروع اجرای مجدد موتور",
            "کندل=${candles.size} مود=${cfg.tfMode} چارت=${com.alisport.goldpin.core.Tf.label(chartTfSec)} بک‌تست‌کامل=$backtestFull پاک‌کردن‌سفارش=$clearOrders")
        engine.reset()
        engine.broker = broker
        broker.engine = engine
        if (clearOrders) broker.reset()
        engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
        val prev = Alerts.inBacktest
        Alerts.inBacktest = true
        try {
            if (candles.isNotEmpty()) engine.feed(candles, lastIsClosed = backtestFull)
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
        val curStep = medianStepSec(candles)
        val fineStep = medianStepSec(fineCandles)
        val src = when {
            candles.isNotEmpty() && curStep in 1L..targetTfSec.toLong() -> candles
            fineCandles.isNotEmpty() && fineStep in 1L..targetTfSec.toLong() -> fineCandles
            candles.isNotEmpty() -> candles
            else -> fineCandles
        }
        if (src.isEmpty()) return emptyList()
        val srcStep = medianStepSec(src)
        val agg = if (sameTimeframe(srcStep, targetTfSec.toLong())) src else Feed.aggregate(src, targetTfSec)
        return if (useSyntheticVolume) Feed.synthesizeVolumeIfMissing(agg) else agg
    }

    private fun syncHierarchyForChartTf(newTf: Int) {
        cfg.tf2 = newTf
        if (cfg.tfMode == 5) return
        val preset = TF_MODES.firstOrNull { !it.custom && it.t2 == newTf }
        if (preset != null) {
            cfg.tfMode = preset.idx
            cfg.tfS = preset.s; cfg.tfM = preset.m; cfg.tf1 = preset.t1; cfg.tf2 = preset.t2
            return
        }
        when {
            newTf <= Tf.M30 -> { cfg.tf1 = Tf.H1; cfg.tfM = Tf.H4; cfg.tfS = Tf.D1 }
            newTf <= Tf.H4  -> { cfg.tf1 = Tf.D1; cfg.tfM = Tf.W1; cfg.tfS = Tf.W1 * 4 }
            newTf <= Tf.D1  -> { cfg.tf1 = Tf.W1; cfg.tfM = Tf.W1 * 4; cfg.tfS = Tf.W1 * 12 }
            else            -> { cfg.tf1 = Tf.W1 * 4; cfg.tfM = Tf.W1 * 12; cfg.tfS = Tf.W1 * 52 }
        }
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
        val tfChanged = !m.custom && chartTfSec != m.t2
        if (!m.custom) {
            cfg.tfS = m.s; cfg.tfM = m.m; cfg.tf1 = m.t1; cfg.tf2 = m.t2
            chartTfSec = m.t2
        }
        io.execute {
            try {
                if (tfChanged || !sameTimeframe(medianStepSec(candles), chartTfSec.toLong())) {
                    val local = aggregateBestAvailable(chartTfSec)
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
        if (!m.custom && (tfChanged || !sameTimeframe(medianStepSec(candles), chartTfSec.toLong()))) {
            changeChartTf(chartTfSec, forceDownload = true)
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
            candles = copy                     // تعویض اتمیک
            if (rebuildNow) rebuild()
        } finally {
            busy = false
        }
        notifyUi()
    }

    // ── دانلود تاریخچه از فید ───────────────────────────────────────────────────
    fun downloadHistory(onDone: (String) -> Unit = {}) {
        val t0 = System.currentTimeMillis()
        val targetTf = chartTfSec
        busy = true
        busyText = "در حال دانلود تاریخچهٔ طلا (${com.alisport.goldpin.core.Tf.label(targetTf)})…"
        lastFeedError = null
        notifyUi()
        Log.i(Log.CAT_FEED, "شروع دانلود تاریخچه", "نماد=$symbol چارت=${com.alisport.goldpin.core.Tf.label(targetTf)} عمق=$depth")
        net.execute {
            try {
                val (chart, base) = fetchChartHistoryBlocking(targetTf)
                val n = chart.size
                Log.i(Log.CAT_FEED, "دانلود تاریخچه موفق",
                    "کندل=$n مدت=${System.currentTimeMillis() - t0}ms پایه=${com.alisport.goldpin.core.Tf.label(base)} چارت=${com.alisport.goldpin.core.Tf.label(targetTf)}")
                io.execute {
                    try {
                        baseTfSec = base
                        busyText = "در حال محاسبهٔ موتور روی ${com.alisport.goldpin.util.Fa.d(n.toString())} کندل…"
                        setCandles(chart)
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
     * به‌روزرسانی زنده: فقط دنبالهٔ داده را می‌گیریم و با سری موجود ادغام می‌کنیم.
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
        // ۲) شبکه روی نخ جدا
        net.execute {
            var handedOff = false
            try {
                val base = Feed.baseTfFor(chartTfSec)
                val (interval, range) = Feed.yahooSpec(base, 1)
                val raw = Feed.yahooChart(symbol, interval, range)
                var tail = if (base == chartTfSec) raw else Feed.aggregate(raw, chartTfSec)
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
                        // دروازه دقیقاً یک‌بار آزاد می‌شود — اینجا، بعد از پایان تغییر وضعیت.
                        // (پیش از این `handedOff = true` هم اینجا بود که لازم نیست: نخ net
                        //  بلافاصله پس از enqueue پرچم را true می‌کند.)
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
            val newClosed = (candles.size - 1) - engine.processed
            val prev = Alerts.inBacktest
            val silent = newClosed > LIVE_CATCHUP_SILENT_BARS
            if (silent) Alerts.inBacktest = true
            try {
                engine.broker = broker
                //  ⚠ barCommitHook فقط روی کندل بسته اجرا می‌شود (Engine آن را صدا می‌زند
                //  و کارگزار فقط روی کندل بسته سفارش پر می‌کند و SL/TP می‌بندد، مطابق
                //  docs/04 و docs/05 که می‌گویند قیمت زنده تصمیم‌ساز نیست).
                //  قبلاً در اینجا candles.lastOrNull()?.let { broker.onBar(it, live=true) }
                //  روی کندل **باز** صدا زده می‌شد و live اصلاً در PaperBroker.onBar
                //  استفاده نمی‌شد — باعث می‌شد سفارش/پوزیشن روی تیک‌های میانهٔ کندل
                //  پر/بسته شود، خلاف طراحی.
                engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
                engine.feed(candles, lastIsClosed = false)
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
        val before = candles.size
        if (candles.isEmpty()) { setCandles(tail, rebuildNow = true); return }
        // ⚠ محافظ تایم‌فریم: اگر فاصلهٔ کندل‌های سری موجود با دنبالهٔ تازه یکی نباشد،
        // ادغام یعنی ساختن یک سری **قاطی** (مثلاً ۶۶۸ کندل ۱H + ۶٬۰۰۰ کندل 1m).
        // دقیقاً همان چیزی که در لاگ کاربر رخ داد: مود ۲ سری ۱H دانلود کرده بود، مود ۱
        // chartTfSec را 1m کرد (تجمیع ۱H به 1m ممکن نیست) و تیک لایو پنج روز کندل 1m را
        // داخل سری ۱H ریخت ⇒ موتور ~۶٬۰۰۰ کندل «جدید» دید و برای هر کدام هشدار داد.
        val curStep = medianStepSec(candles)
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
        var cut = candles.size
        while (cut > 0 && candles[cut - 1].t >= firstNew) cut--
        val merged = ArrayList<Candle>(cut + tail.size)
        for (i in 0 until cut) merged.add(candles[i])
        for (c in tail) merged.add(c)
        candles = merged.mapIndexed { i, c -> Candle(i, c.t, c.o, c.h, c.l, c.c, c.v) }
        Log.d(Log.CAT_LIVE, "ادغام دنبالهٔ داده", "قبل=$before بعد=${candles.size} جدید=${candles.size - before}")
    }

    /** تغییر تایم‌فریم چارت (همراه با دانلود تاریخچهٔ مناسب و پشتیبان تجمیع داخلی) */
    fun changeChartTf(newTf: Int, forceDownload: Boolean = false) {
        val stepMatches = sameTimeframe(medianStepSec(candles), newTf.toLong()) && candles.size >= 30
        if (!forceDownload && newTf == chartTfSec && stepMatches) return
        Log.i(Log.CAT_CFG, "تغییر تایم‌فریم چارت", "${com.alisport.goldpin.core.Tf.label(chartTfSec)} → ${com.alisport.goldpin.core.Tf.label(newTf)}")
        chartTfSec = newTf
        syncHierarchyForChartTf(newTf)
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
                        setCandles(chart)
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
                // بدون شبکه: از بهترین دادهٔ ریزدانهٔ موجود تجمیع می‌کنیم
                io.execute {
                    try {
                        val chart = aggregateBestAvailable(newTf)
                        setCandles(chart)
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
        return synchronized(stateLock) { Store.save(cfg, engine, broker, candles, meta) }
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
            synchronized(stateLock) {
                copySettings(ld.cfg, cfg)
                engine.reset()
                broker.reset()
                // انتقال وضعیت بازیابی‌شده به نمونه‌های سراسری
                copyEngine(ld.eng, engine)
                copyBroker(ld.broker, broker)
                engine.broker = broker
                broker.engine = engine
                engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
                candles = ArrayList(ld.candles)
                publishSnapshot()
            }
            val m = ld.meta
            symbol = m["symbol"]?.toString() ?: symbol
            baseTfSec = (m["baseTfSec"] as? Long)?.toInt() ?: baseTfSec
            chartTfSec = (m["chartTfSec"] as? Long)?.toInt() ?: cfg.tf2
            depth = (m["depth"] as? Long)?.toInt() ?: depth
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
        dst.markers.clear(); dst.markers.addAll(src.markers)
        dst.events.clear(); dst.events.addAll(src.events)
        dst.cnt.setup = src.cnt.setup; dst.cnt.touch = src.cnt.touch; dst.cnt.mid = src.cnt.mid
        dst.cnt.lv = src.cnt.lv; dst.cnt.hv = src.cnt.hv; dst.cnt.entry = src.cnt.entry
        dst.cnt.done = src.cnt.done; dst.cnt.zoneCount = src.cnt.zoneCount; dst.cnt.zoneRejected = src.cnt.zoneRejected
        dst.trend = src.trend; dst.HH = src.HH; dst.HL = src.HL; dst.LL = src.LL; dst.LH = src.LH
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
            val list = if (chartTfSec <= Tf.M1) raw else Feed.aggregate(raw, chartTfSec)
            Log.i(Log.CAT_FEED, "دادهٔ نمونهٔ داخلی بارگذاری شد", "کندل=${list.size}")
            setCandles(list)
            list.size
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
                var list = Feed.parseCsv(text)
                baseTfSec = baseTfGuess
                if (baseTfGuess != chartTfSec) list = Feed.aggregate(list, chartTfSec)
                if (useSyntheticVolume) list = Feed.synthesizeVolumeIfMissing(list)
                setCandles(list)
                main.post {
                    onDone("وارد شد: ${com.alisport.goldpin.util.Fa.d(list.size.toString())} کندل")
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
