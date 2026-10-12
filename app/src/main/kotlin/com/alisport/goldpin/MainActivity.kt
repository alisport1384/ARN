package com.alisport.goldpin

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.alisport.goldpin.core.Order
import com.alisport.goldpin.core.OrderStatus
import com.alisport.goldpin.core.Reports
import com.alisport.goldpin.core.TF_MODES
import com.alisport.goldpin.core.Tf
import com.alisport.goldpin.core.Trade
import com.alisport.goldpin.core.ZoneStatus
import com.alisport.goldpin.data.Feed
import com.alisport.goldpin.data.Storage
import com.alisport.goldpin.svc.EngineService
import com.alisport.goldpin.ui.ChartView
import com.alisport.goldpin.ui.EquityChartView
import com.alisport.goldpin.ui.SetupOverlay
import com.alisport.goldpin.util.Alerts
import com.alisport.goldpin.util.Fa
import com.alisport.goldpin.util.Log
import com.alisport.goldpin.util.Palette
import com.alisport.goldpin.util.Ui
import kotlin.math.abs
import kotlin.math.max

class MainActivity : Activity() {

    private val s get() = AppState.instance
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var content: FrameLayout

    /** دسترسی آزمایش به ظرف محتوا (برای سنجش padding تمام‌صفحه). */
    internal val contentForTest: FrameLayout get() = content
    private lateinit var tabButtons: LinearLayout
    private lateinit var headerPrice: TextView
    private lateinit var headerStatus: TextView
    private var tab = 0

    // چارت
    private var chart: ChartView? = null
    private var chartInfo: TextView? = null
    private var eventsBox: LinearLayout? = null
    private var fullscreen = false
    private var bannerView: TextView? = null
    private var bannerHide: Runnable? = null

    private var reportKind = Reports.WEEKLY
    private var refreshing = false
    private var pendingReport: String? = null
    private var lastRefreshAt = 0L
    private var refreshQueued = false
    private var tabSignature = ""
    private val uiListener: () -> Unit = { refreshCurrent() }

    private val REQ_SAVE = 101
    private val REQ_LOAD = 102
    private val REQ_CSV = 103
    private val REQ_REPORT = 104
    private val REQ_LOG_MD = 105
    private val REQ_LOG_TXT = 106

    private val tfChoices = arrayOf(Tf.M1, Tf.M5, Tf.M15, Tf.M30, Tf.H1, Tf.H4, Tf.D1, Tf.W1)

    // ══════════════════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(Log.CAT_APP, "MainActivity ساخته شد", "نسخهٔ ${appVersionName()}")
        AppState.init(applicationContext)
        // سگ نگهبان نخ رابط: اگر اپ فریز کرد، پشتهٔ نخ اصلی را قبل از هر چیز لاگ می‌کند.
        // بدون این، در لحظهٔ فریز «نمی‌شود لاگ گرفت» و تشخیص غیرممکن می‌ماند.
        com.alisport.goldpin.util.AnrWatchdog.start()
        Alerts.bannerSink = { text, color, kind -> showBanner(text, color, kind) }
        buildShell()
        s.onChange(uiListener)
        showTab(0)
        // بازیابی وضعیت در پس‌زمینه — پیش‌تر همگام روی نخ رابط بود و بالا آمدن اپ را فریز می‌کرد
        s.restoreOrSample(applicationContext) { m ->
            toast(m)
            refreshCurrent(force = true)
        }
        // تمام‌صفحه دیگر اجباری نیست؛ فقط اگر کاربر در تنظیمات ⓪ روشن کرده باشد
        if (s.autoFullscreen) {
            Log.i(Log.CAT_UI, "ورود خودکار به تمام‌صفحه (تنظیمات ⓪ روشن است)")
            setFullscreen(true, keepTab = true)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(Log.CAT_APP, "onPause → ذخیرهٔ خودکار")
        s.io.execute { s.saveToAutoFile(applicationContext) }
    }

    override fun onDestroy() {
        s.offChange(uiListener)                     // جلوگیری از انباشت شنونده‌ها
        if (isFinishing) {
            com.alisport.goldpin.util.AnrWatchdog.stop()
            Alerts.bannerSink = null
            Log.i(Log.CAT_APP, "خروج از اپ")
            Log.flush()
            s.io.execute { s.saveToAutoFile(applicationContext) }   // ذخیره در پس‌زمینه
        }
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && fullscreen) {
            setFullscreen(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  اسکلت
    // ══════════════════════════════════════════════════════════════════════════
    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.bg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.panel)
            setPadding(Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 7f),
                Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 6f))
        }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(Ui.tv(this, "GoldPin · طلا", 15.5f, Palette.gold, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
        headerPrice = Ui.tv(this, "—", 15.5f, Palette.txt, true)
        titleRow.addView(headerPrice, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
        val liveBtn = Ui.btn(this, "لایو", Palette.panel2, Palette.up, 12f)
        liveBtn.setOnClickListener { toggleLive() }
        titleRow.addView(liveBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(titleRow)
        headerStatus = Ui.tv(this, "آماده", 11f, Palette.dim).apply {
            // لایهٔ دفاعی: حتی اگر رشتهٔ بزرگی به اینجا برسد، TextView کل آن را
            // صفحه‌آرایی نکند (همان کاری که فریز را ساخت).
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(headerStatus)
        root.addView(header)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        tabButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.panel)
        }
        val names = arrayOf("چارت", "سفارش‌ها", "سود و زیان", "گزارش‌ها", "تنظیمات")
        names.forEachIndexed { i, n ->
            val b = Ui.tv(this, n, 12f, Palette.dim, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(this@MainActivity, 11f), 0, Ui.dp(this@MainActivity, 11f))
                setOnClickListener { showTab(i) }
            }
            tabButtons.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabButtons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    private fun showTab(i: Int) {
        if (fullscreen) setFullscreen(false, keepTab = true)
        tab = i
        chart = null; chartInfo = null; eventsBox = null
        Log.d(Log.CAT_UI, "تب ${arrayOf("چارت", "سفارش‌ها", "سود و زیان", "گزارش‌ها", "تنظیمات").getOrElse(i) { "?" }} باز شد")
        for (k in 0 until tabButtons.childCount) {
            (tabButtons.getChildAt(k) as TextView).setTextColor(if (k == i) Palette.gold else Palette.dim)
        }
        content.removeAllViews()
        when (i) {
            0 -> content.addView(buildChartScreen())
            1 -> content.addView(buildOrdersScreen())
            2 -> content.addView(buildPnlScreen())
            3 -> content.addView(buildReportsScreen())
            4 -> content.addView(buildSettingsScreen())
        }
        tabSignature = "$i|$reportKind|${s.tradesSnapshot().size}|${s.ordersSnapshot().size}|${s.tradesSnapshot().count { !it.open }}"
        refreshCurrent(force = true)
    }

    /**
     * تازه‌سازی رابط. ضد‌سیل (debounce) دارد تا سیل رخدادهای موتور،
     * نخ رابط را قفل نکند؛ و هر تب فقط وقتی داده‌اش عوض شده بازسازی می‌شود.
     */
    private fun refreshCurrent(force: Boolean = false) {
        // محافظ ورود تودرتو: بازسازی تب از داخل خودش (showTab → refreshCurrent) ممکن است
        if (refreshing) return
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastRefreshAt < 300) {
            if (!refreshQueued) {
                refreshQueued = true
                content.postDelayed({ refreshQueued = false; refreshCurrent(true) }, 320)
            }
            return
        }
        lastRefreshAt = now
        refreshing = true
        try {
            headerPrice.text = if (s.lastLivePrice.isNaN()) "—" else Fa.n(s.lastLivePrice, 2)
            val trend = when (s.engine.trend) { 1 -> "صعودی"; -1 -> "نزولی"; else -> "بدون روند" }
            headerStatus.text = buildString {
                append(if (s.liveRunning) "● لایو" else "○ لایو خاموش")
                append("  |  ${Tf.label(s.chartTfSec)}  |  روند: $trend")
                append("  |  معاملات: ${Fa.d(s.tradesSnapshot().count { !it.open }.toString())}")
                append("  |  موجودی: ${Fa.n(s.broker.equity, 2)}")
                if (Log.enabled) append("  |  لاگر روشن (${Fa.d(Log.count().toString())})")
                if (s.busy) append("  |  ⏳ ${s.busyText.ifEmpty { "در حال محاسبه…" }}")
                // ⚠ مهار دوباره: حتی اگر رشتهٔ بلندی تا اینجا رسید، هرگز کامل داخل
                // TextView نمی‌رود — صفحه‌آرایی متنِ یک مگابایت نخ رابط را قفل می‌کند.
                s.lastFeedError?.takeIf { it.isNotEmpty() }
                    ?.let { append("  |  ⚠ " + Fa.short(it, 90)) }
            }
            when (tab) {
                0 -> refreshChartTab()
                1, 2, 3 -> {
                    // فقط وقتی محتوای تب عوض شده باشد بازسازی می‌کنیم (نه در هر تیک)
                    val sig = "${tab}|${reportKind}|${s.tradesSnapshot().size}|${s.ordersSnapshot().size}|${s.tradesSnapshot().count { !it.open }}"
                    if (force || sig != tabSignature) {
                        tabSignature = sig
                        content.removeAllViews()
                        content.addView(
                            when (tab) {
                                1 -> buildOrdersScreen()
                                2 -> buildPnlScreen()
                                else -> buildReportsScreen()
                            }
                        )
                    }
                }
                4 -> Unit // تنظیمات خودش ساخته شده؛ هیچ‌وقت با تب گزارش جایگزین نشود
            }
        } finally {
            refreshing = false
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  تمام‌صفحه
    // ══════════════════════════════════════════════════════════════════════════
    private fun setFullscreen(on: Boolean, keepTab: Boolean = false) {
        fullscreen = on
        Log.i(Log.CAT_UI, if (on) "ورود به تمام‌صفحه" else "خروج از تمام‌صفحه")
        header.visibility = if (on) View.GONE else View.VISIBLE
        tabButtons.visibility = if (on) View.GONE else View.VISIBLE
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let {
                    it.hide(android.view.WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.show(android.view.WindowInsets.Type.systemBars())
                window.setDecorFitsSystemWindows(true)
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
        applyFullscreenTopInset(on)
        if (tab == 0) {
            // نوار پایین/بالا و پنل رخدادها باید با حالت تمام‌صفحه هم‌گام شوند
            content.removeAllViews()
            content.addView(buildChartScreen())
            refreshCurrent(force = true)
        }
    }

    /**
     * در تمام‌صفحه، `header` و `tabButtons` پنهان می‌شوند و `content` کل صفحه را
     * می‌گیرد؛ چون پنجره هم‌زمان edge-to-edge می‌شود، نوار ابزار چارت
     * («آخرین کندل»، «تنظیم نما»، «تنظیمات») زیر **نوار وضعیت** می‌رفت و دیده
     * نمی‌شد — روی برخی دستگاه‌ها نوار وضعیت کاملاً پنهان هم نمی‌شود و با کشیدن
     * انگشت موقتاً برمی‌گردد.
     *
     * حالا به اندازهٔ ارتفاع نوار وضعیت به بالای `content` padding داده می‌شود تا
     * نوار ابزار همیشه پایین‌تر از آن بماند، و در خروج از تمام‌صفحه صفر می‌شود.
     */
    internal fun applyFullscreenTopInset(on: Boolean) {
        val top = if (on) statusBarHeightPx() else 0
        content.setPadding(content.paddingLeft, top, content.paddingRight, content.paddingBottom)
    }

    /** ارتفاع نوار وضعیت از منابع سیستم؛ اگر پیدا نشد صفر (یعنی بدون تغییر). */
    internal fun statusBarHeightPx(): Int {
        return try {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else 0
        } catch (t: Throwable) {
            0
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  بنر هشدار داخل اپ
    // ══════════════════════════════════════════════════════════════════════════
    /**
     * بنر هشدار داخل اپ — **تک‌نمونه و ادغام‌شونده**.
     *
     * ⚠ پیش‌تر به ازای *هر* هشدار یک `runOnUiThread` اجرا می‌شد که یک View می‌ساخت،
     * View قبلی را حذف می‌کرد و `postDelayed` جدید می‌گذاشت. هر `addView`/`removeView`
     * یک `requestLayout()` روی کل سلسله‌مراتب است. وقتی موتور در بازپخش تاریخچه
     * چند هزار هشدار می‌داد (لاگ کاربر)، چند هزار Runnable روی نخ رابط صف می‌شد و
     * اپ کامل فریز می‌شد — و هر لمس بعدی پشت آن‌ها می‌ماند.
     *
     * حالا: یک بنر ثابت که فقط متنش عوض می‌شود، و با `AtomicBoolean` تضمین می‌شود
     * **در هر لحظه حداکثر یک Runnable در صف نخ رابط باشد** (بقیه در آخرین متن
     * ادغام می‌شوند).
     */
    private val bannerScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var bannerPendingText: String? = null
    @Volatile private var bannerPendingColor: Int = 0
    private var bannerLastShownAt = 0L

    private fun showBanner(text: String, color: Int, kind: String) {
        bannerPendingText = text
        bannerPendingColor = color
        // فقط اگر Runnable دیگری در صف نیست یکی بگذار — وگرنه همان قبلی این متن را هم می‌برد
        if (bannerScheduled.compareAndSet(false, true)) {
            runOnUiThread {
                bannerScheduled.set(false)
                drainBanner()
            }
        }
    }

    private fun drainBanner() {
        val text = bannerPendingText ?: return
        bannerPendingText = null
        val color = bannerPendingColor

        var tv = bannerView
        if (tv == null) {
            tv = Ui.tv(this, text, 12f, Color.WHITE, true).apply {
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 9f),
                    Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 9f))
                setOnClickListener { showAlertHistory() }
            }
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP }
            content.addView(tv, lp)
            bannerView = tv
        }
        tv.text = text
        tv.setBackgroundColor((color and 0x00FFFFFF) or 0xE0000000.toInt())
        tv.visibility = View.VISIBLE
        bannerLastShownAt = android.os.SystemClock.uptimeMillis()

        // تایمر پنهان‌سازی فقط یک‌بار تنظیم می‌شود (نه به ازای هر هشدار)
        bannerHide?.let { content.removeCallbacks(it) }
        val h = Runnable {
            bannerView?.visibility = View.GONE
            bannerHide = null
        }
        bannerHide = h
        content.postDelayed(h, 6000)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ۱) تب چارت  (چارت تمام‌صفحه + نوارهای شناور)
    // ══════════════════════════════════════════════════════════════════════════
    /** خط جداکنندهٔ نازک برای مرزبندی نوارها */
    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.parseColor("#243040"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    /** دکمهٔ نواری توپر (هم‌اندازه و منظم — بدون همپوشانی با چارت) */
    private fun barChip(label: String, on: Boolean = false, action: () -> Unit): View {
        val b = Ui.btn(this, label, if (on) Palette.accent else Palette.panel2,
            if (on) Palette.txt else Palette.dim, 11.5f)
        b.setPadding(Ui.dp(this, 10f), 0, Ui.dp(this, 10f), 0)
        b.setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 32f))
        lp.marginEnd = Ui.dp(this, 4f)
        b.layoutParams = lp
        b.minimumWidth = 0
        b.minWidth = 0
        return b
    }

    /**
     * صفحهٔ چارت: نوار ابزار و تایم‌فریم «بخشی از چیدمان» هستند (توپر و بالای چارت)،
     * بنابراین هیچ دکمه‌ای روی چارت نمی‌افتد و رابط تمیز می‌ماند.
     * در تمام‌صفحه فقط هدر/تب‌های اپ و پنل رخدادها پنهان می‌شوند.
     */
    private fun buildChartScreen(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.bg)
        }

        // ── ۱) نوار ابزار (توپر، خارج از چارت) ──
        val topBar = HorizontalScrollView(this).apply {
            setBackgroundColor(Palette.panel)
            isHorizontalScrollBarEnabled = false
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@MainActivity, 6f), Ui.dp(this@MainActivity, 5f),
                Ui.dp(this@MainActivity, 6f), Ui.dp(this@MainActivity, 5f))
        }
        topBar.addView(topRow)
        topRow.addView(barChip(if (fullscreen) "⤡ خروج" else "⛶ تمام‌صفحه") { setFullscreen(!fullscreen) })
        topRow.addView(barChip("＋") { chart?.zoomIn() })
        topRow.addView(barChip("−") { chart?.zoomOut() })
        topRow.addView(barChip("◀") { chart?.scrollByBars(-10f) })
        topRow.addView(barChip("▶") { chart?.scrollByBars(10f) })
        topRow.addView(barChip("آخرین کندل") { chart?.goLive() })
        topRow.addView(barChip("تنظیم نما") { chart?.resetView() })
        topRow.addView(barChip("⬇ دانلود تاریخچه") {
            toast("دانلود تاریخچه (${Tf.label(s.chartTfSec)}) شروع شد…")
            s.downloadHistory { m -> toast(m) }
        })
        topRow.addView(barChip("⋮ بیشتر") { showChartContextMenu() })
        col.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(divider())

        // ── ۲) ردیف تایم‌فریم (توپر، خارج از چارت) ──
        val tfScroll = HorizontalScrollView(this).apply {
            setBackgroundColor(Palette.panel)
            isHorizontalScrollBarEnabled = false
        }
        val tfBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@MainActivity, 6f), Ui.dp(this@MainActivity, 4f),
                Ui.dp(this@MainActivity, 6f), Ui.dp(this@MainActivity, 4f))
        }
        tfBox.addView(Ui.tv(this, "تایم‌فریم چارت:", 11f, Palette.dim).apply {
            setPadding(0, 0, Ui.dp(this@MainActivity, 6f), 0)
        })
        for (c in tfChoices) {
            val sel = c == s.chartTfSec
            tfBox.addView(barChip(Tf.label(c), on = sel) {
                Log.i(Log.CAT_UI, "تغییر تایم‌فریم چارت به ${Tf.label(c)} (تجمیع داخلی)")
                s.changeChartTf(c)
                showTab(0)
            })
        }
        tfScroll.addView(tfBox)
        col.addView(tfScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(divider())

        // ── ۳) چارت: تمام فضای باقی‌مانده ──
        val cv = ChartView(this)
        chart = cv
        cv.chartTfSec = s.chartTfSec
        cv.symbolName = s.symbol
        cv.onInfo = { txt -> chartInfo?.text = txt }
        cv.onGesture = { g -> Log.d(Log.CAT_UI, "ژست چارت: $g") }
        cv.onNeedOlder = { Log.d(Log.CAT_FEED, "به ابتدای داده رسیدیم — درخواست تاریخچهٔ بیشتر") }
        cv.onContextMenu = { showChartContextMenu() }
        col.addView(cv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── ۴) نوار پایین: راهنما + رخدادها (در تمام‌صفحه پنهان می‌شود) ──
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.panel)
            visibility = if (fullscreen) View.GONE else View.VISIBLE
        }
        col.addView(divider())
        chartInfo = Ui.tv(this,
            "دو انگشت: زوم هم‌زمان زمان و قیمت · یک انگشت: جابه‌جایی · محور قیمت: کشیدن = فشرده/باز، دو ضربه = خودکار · " +
                "دو ضربه روی چارت: بازنشانی · نگه‌داشتن: کراس‌هیر و منو",
            10f, Palette.dim).apply {
            setPadding(Ui.dp(this@MainActivity, 8f), Ui.dp(this@MainActivity, 5f),
                Ui.dp(this@MainActivity, 8f), Ui.dp(this@MainActivity, 5f))
        }
        bottom.addView(chartInfo)
        eventsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val evScroll = ScrollView(this)
        evScroll.addView(eventsBox)
        bottom.addView(evScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 118f)))
        if (!fullscreen) {
            col.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return col
    }

    private fun showChartContextMenu() {
        val items = arrayOf(
            "تنظیم نما (بازنشانی)", "مقیاس قیمت خودکار", "رفتن به آخرین کندل",
            "زوم به داخل", "زوم به بیرون", if (fullscreen) "خروج از تمام‌صفحه" else "تمام‌صفحه",
            "نمایش حجم", "نمایش باکس‌های ناحیه", "نمایش برچسب‌ها و خطوط معامله",
            "رخدادهای موتور", "لاگ زنده (۳۰۰ خط آخر)", "تنظیمات چارت"
        )
        AlertDialog.Builder(this).setTitle("منوی چارت").setItems(items) { _, i ->
            when (i) {
                0 -> chart?.resetView()
                1 -> chart?.resetPrice()
                2 -> chart?.goLive()
                3 -> chart?.zoomIn()
                4 -> chart?.zoomOut()
                5 -> setFullscreen(!fullscreen)
                6 -> chart?.let { it.showVolume = !it.showVolume; it.invalidate() }
                7 -> chart?.let { it.showZones = !it.showZones; it.invalidate() }
                8 -> chart?.let { it.showMarkers = !it.showMarkers; it.showTradeLines = !it.showTradeLines; it.invalidate() }
                9 -> showEventsDialog()
                10 -> showLogViewer()
                11 -> { showTab(4) }
            }
        }.show()
    }

    /** رخدادهای موتور به‌صورت دیالوگ — در تمام‌صفحه هم قابل دسترسی */
    private fun showEventsDialog() {
        val e = s.engine
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 8f),
                Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 8f))
        }
        box.addView(Ui.tv(this,
            "ستاپ: ${Fa.d(e.setups.size.toString())} · باکس فعال ${Fa.d(e.zones.count { it.status == ZoneStatus.ACTIVE }.toString())}" +
                " / پاک‌شده ${Fa.d(e.zones.count { it.status == ZoneStatus.DELETED }.toString())}" +
                " / ردشده ${Fa.d(e.zones.count { it.status == ZoneStatus.REJECTED }.toString())}",
            12f, Palette.gold, true))
        box.addView(Ui.label(this, "آخرین ورود", Fa.n(e.lastEntryPx)))
        box.addView(Ui.label(this, "آخرین حد ضرر", Fa.n(e.lastSlPx)))
        box.addView(Ui.label(this, "آخرین حد سود", Fa.n(e.lastTpPx)))
        box.addView(Ui.label(this, "نتیجهٔ آخرین ستاپ", e.lastResult))
        box.addView(Ui.spacer(this, 6f))
        for (ev in e.events.takeLast(25).reversed()) {
            box.addView(Ui.tv(this, "• $ev", 10.5f, Palette.dim))
        }
        AlertDialog.Builder(this).setTitle("رخدادهای موتور")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("بستن", null).show()
    }

    private fun refreshChartTab() {
        val cv = chart ?: return
        val snap = s.uiSnapshot()
        cv.candles = snap.candles
        cv.zones = snap.zones
        cv.t1Zones = snap.t1Zones
        cv.t2Zones = snap.t2Zones
        cv.markers = snap.markers
        cv.trades = snap.trades
        cv.pocPrice = snap.lastPocPx
        cv.livePulse = s.liveRunning
        cv.symbolName = s.symbol
        cv.chartTfSec = s.chartTfSec
        cv.overlays = snap.setups.map { st ->
            SetupOverlay(
                st.id, st.dir, st.stage, st.zTop, st.zBot, st.lvH, st.lvL, st.hvH, st.hvL,
                st.entry, st.sl, st.tp1, st.tp2, st.tpx, st.midRef, st.zBi, st.lvUsed, st.lvConfBi,
                st.zT, st.midT, st.lvT, st.hvT, st.armT
            )
        }
        cv.refresh()

        eventsBox?.let { box ->
            box.removeAllViews()
            box.addView(Ui.tv(this,
                "ستاپ: ${Fa.d(snap.setups.size.toString())} · باکس فعال ${Fa.d(snap.zones.count { it.status == ZoneStatus.ACTIVE }.toString())}" +
                    " / پاک‌شده ${Fa.d(snap.zones.count { it.status == ZoneStatus.DELETED }.toString())}" +
                    " / ردشده ${Fa.d(snap.zones.count { it.status == ZoneStatus.REJECTED }.toString())}" +
                    " · آخرین ورود ${Fa.n(snap.lastEntryPx)} · SL ${Fa.n(snap.lastSlPx)} · TP ${Fa.n(snap.lastTpPx)} · نتیجه: ${snap.lastResult}",
                10.5f, Palette.txt).apply {
                setPadding(Ui.dp(this@MainActivity, 8f), Ui.dp(this@MainActivity, 3f), 0, Ui.dp(this@MainActivity, 3f))
            })
            for (ev in snap.events.takeLast(12).reversed()) {
                box.addView(Ui.tv(this, "• $ev", 10.5f, Palette.dim).apply {
                    setPadding(Ui.dp(this@MainActivity, 8f), Ui.dp(this@MainActivity, 1f),
                        Ui.dp(this@MainActivity, 8f), Ui.dp(this@MainActivity, 1f))
                })
            }
            val opensList = snap.openTrades
            val pensList = snap.pendingOrders
            if (opensList.isNotEmpty()) {
                for (t in opensList) {
                    box.addView(Ui.tv(this, "پوزیشن #${t.id}: ${if (t.dir == 1) "خرید" else "فروش"} @ ${Fa.n(t.entry)} · سود/زیان ${Fa.signed(t.pnl())}",
                        11f, Palette.gold))
                }
            }
            if (pensList.isNotEmpty()) {
                for (o in pensList) {
                    box.addView(Ui.tv(this, "سفارش آماده #${o.id}: ${if (o.dir == 1) "خرید لیمیت" else "فروش لیمیت"} @ ${Fa.n(o.price)}",
                        11f, Palette.accent))
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ۲) سفارش‌ها
    // ══════════════════════════════════════════════════════════════════════════
    private fun buildOrdersScreen(): View {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val orders = s.ordersSnapshot()
        v.addView(Ui.tv(this, "سفارش‌ها (${Fa.d(orders.size.toString())})", 12.5f, Palette.gold, true).apply {
            setPadding(Ui.dp(this@MainActivity, 8f), Ui.dp(this@MainActivity, 6f), 0, Ui.dp(this@MainActivity, 4f))
        })
        val lv1 = ListView(this)
        lv1.adapter = object : BaseAdapter() {
            override fun getCount() = orders.size
            override fun getItem(position: Int) = orders[orders.size - 1 - position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?) = orderRow(getItem(position) as Order)
        }
        lv1.setOnItemClickListener { _, _, p, _ -> showOrderDialog(orders[orders.size - 1 - p]) }
        v.addView(lv1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        v.addView(Ui.hline(this))
        val trades = s.tradesSnapshot()
        v.addView(Ui.tv(this, "معاملات (${Fa.d(trades.size.toString())})", 12.5f, Palette.gold, true).apply {
            setPadding(Ui.dp(this@MainActivity, 8f), Ui.dp(this@MainActivity, 6f), 0, Ui.dp(this@MainActivity, 4f))
        })
        val lv2 = ListView(this)
        lv2.adapter = object : BaseAdapter() {
            override fun getCount() = trades.size
            override fun getItem(position: Int) = trades[trades.size - 1 - position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?) = tradeRow(getItem(position) as Trade)
        }
        lv2.setOnItemClickListener { _, _, p, _ -> showTradeDialog(trades[trades.size - 1 - p]) }
        v.addView(lv2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return v
    }

    private fun orderRow(o: Order): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 7f),
                Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 7f))
        }
        val st = when (o.status) {
            OrderStatus.PENDING -> "آماده / در انتظار پر شدن"
            OrderStatus.FILLED -> "پر شده"
            OrderStatus.CANCELLED_EXPIRED -> "منقضی"
            OrderStatus.CANCELLED_INVALID -> "لغو" + (o.cancelReason?.let { " ($it)" } ?: "")
            else -> "لغو دستی"
        }
        val col = when (o.status) {
            OrderStatus.PENDING -> Palette.accent
            OrderStatus.FILLED -> Palette.up
            else -> Palette.dim
        }
        row.addView(Ui.tv(this, "#${Fa.d(o.id.toString())}  ${if (o.dir == 1) "خرید لیمیت" else "فروش لیمیت"}  @ ${Fa.n(o.price)}   [$st]",
            12.5f, col, true))
        row.addView(Ui.tv(this, "حدضرر ${Fa.n(o.sl)} · TP1 ${Fa.n(o.tp1)} · TP2 ${Fa.n(o.tp2)} · TP ${Fa.n(o.tpX)} · حجم ${Fa.n(o.qty, 3)}",
            11f, Palette.dim))
        row.addView(Ui.tv(this, "ناحیه: ${Fa.n(o.zoneBot)} تا ${Fa.n(o.zoneTop)} · ثبت ${Fa.jalali(o.placedT)}" +
            (o.filledT?.let { " · پر شد ${Fa.jalali(it)}" } ?: ""), 10.5f, Palette.dim))
        return row
    }

    private fun tradeRow(t: Trade): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 7f),
                Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 7f))
        }
        val pnl = t.pnl()
        val col = if (t.open) Palette.accent else if (pnl >= 0) Palette.up else Palette.down
        row.addView(Ui.tv(this, "#${Fa.d(t.id.toString())}  ${if (t.dir == 1) "خرید" else "فروش"}  " +
            "ورود ${Fa.n(t.entry)} → ${t.exitPx?.let { Fa.n(it) } ?: "باز"}   ${Fa.signed(pnl)} دلار (${Fa.n(t.rMultiple(), 2)}R)",
            12.5f, col, true))
        row.addView(Ui.tv(this, "حدضرر اولیه ${Fa.n(t.sl0)} · TP ${Fa.n(t.tpX)} · حجم ${Fa.n(t.qty, 3)}" +
            (if (t.open) "" else " · دلیل: ${t.reason}"), 11f, Palette.dim))
        row.addView(Ui.tv(this, "ورود ${Fa.jalali(t.entryT)}" + (t.exitT?.let { " · خروج ${Fa.jalali(it)}" } ?: ""),
            10.5f, Palette.dim))
        return row
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ۳) سود و زیان
    // ══════════════════════════════════════════════════════════════════════════
    private fun buildPnlScreen(): View {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pnlTrades = s.tradesSnapshot()
        val sum = Reports.summary(pnlTrades)
        val box = Ui.box(this)
        box.addView(Ui.tv(this, "خلاصهٔ عملکرد", 13f, Palette.gold, true))
        box.addView(Ui.label(this, "سود/زیان خالص (دلار)", Fa.signed(sum.net), vColor = if (sum.net >= 0) Palette.up else Palette.down))
        box.addView(Ui.label(this, "تعداد معاملات", Fa.d(sum.trades.toString())))
        box.addView(Ui.label(this, "برد / باخت", "${Fa.d(sum.wins.toString())} / ${Fa.d(sum.losses.toString())}  (${Fa.pct(sum.winRate)})"))
        box.addView(Ui.label(this, "ضریب سود", Fa.n(sum.profitFactor, 2)))
        box.addView(Ui.label(this, "میانگین R", Fa.n(sum.avgR, 2)))
        box.addView(Ui.label(this, "بیشترین افت سرمایه", Fa.n(sum.maxDD, 2)))
        box.addView(Ui.label(this, "موجودی نهایی", Fa.n(s.broker.balance, 2)))
        v.addView(box)
        val eq = EquityChartView(this)
        eq.setData(pnlTrades, s.cfg.initialEquity)
        eq.onPick = { t -> showTradeDialog(t) }
        v.addView(eq, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 185f)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.btn(this, "بازگشت زوم", Palette.panel2, Palette.txt, 11f).apply { setOnClickListener { eq.resetZoom() } })
        row.addView(Ui.btn(this, "CSV معاملات", Palette.panel2, Palette.txt, 11f).apply { setOnClickListener { exportTradesCsv() } })
        v.addView(row)
        val list = pnlTrades.reversed()
        val lv = ListView(this)
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = list.size
            override fun getItem(position: Int) = list[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?) = tradeRow(list[position])
        }
        lv.setOnItemClickListener { _, _, p, _ -> showTradeDialog(list[p]) }
        v.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return v
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ۴) گزارش‌ها
    // ══════════════════════════════════════════════════════════════════════════
    private fun buildReportsScreen(): View {
        val kindAtStart = reportKind
        val kinds = arrayOf("روزانه", "هفتگی", "ماهانه", "سالانه")
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 6f), Ui.dp(this@MainActivity, 4f),
                Ui.dp(this@MainActivity, 6f), Ui.dp(this@MainActivity, 6f))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        kinds.forEachIndexed { i, n ->
            row.addView(Ui.btn(this, n, if (kindAtStart == i) Palette.accent else Palette.panel2,
                if (kindAtStart == i) Palette.txt else Palette.dim, 12f).apply {
                setOnClickListener { reportKind = i; showTab(3) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        v.addView(row)

        // محاسبهٔ گروه‌ها/خلاصه روی نخ پس‌زمینه؛ تب فوراً پاسخ‌گو می‌ماند.
        val summaryBox = Ui.box(this)
        summaryBox.addView(Ui.tv(this, "${kinds[kindAtStart]} — در حال محاسبه…", 12.5f, Palette.gold, true))
        v.addView(summaryBox)
        val lv = ListView(this)
        v.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val export = Ui.btn(this, "در حال آماده‌سازی خروجی…", Palette.panel2, Palette.dim, 11f).apply {
            isEnabled = false
        }
        v.addView(export)

        s.io.execute {
            val snapshot = s.tradesSnapshot()
            val rows = Reports.group(snapshot, kindAtStart)
            val sum = Reports.summary(snapshot)
            runOnUiThread {
                if (isFinishing || v.parent == null) return@runOnUiThread
                summaryBox.removeAllViews()
                summaryBox.addView(Ui.tv(this, "${kinds[kindAtStart]} — ${Fa.d(rows.size.toString())} دوره",
                    12.5f, Palette.gold, true))
                summaryBox.addView(Ui.label(this, "جمع سود/زیان", Fa.signed(sum.net),
                    vColor = if (sum.net >= 0) Palette.up else Palette.down))
                summaryBox.addView(Ui.label(this, "نرخ برد کل", Fa.pct(sum.winRate)))
                summaryBox.addView(Ui.label(this, "ضریب سود کل", Fa.n(sum.profitFactor, 2)))
                lv.adapter = object : BaseAdapter() {
                    override fun getCount() = rows.size
                    override fun getItem(position: Int) = rows[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                        val r = rows[position]
                        return LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 6f),
                                Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 6f))
                            addView(Ui.tv(this@MainActivity, "${r.label}   ${Fa.signed(r.net)} دلار", 12.5f,
                                if (r.net >= 0) Palette.up else Palette.down, true))
                            addView(Ui.tv(this@MainActivity,
                                "معامله ${Fa.d(r.trades.toString())} · برد ${Fa.d(r.wins.toString())} · باخت ${Fa.d(r.losses.toString())} · " +
                                    "نرخ برد ${Fa.pct(r.winRate)} · PF ${Fa.n(r.profitFactor, 2)} · میانگین R ${Fa.n(r.avgR, 2)} · افت ${Fa.n(r.maxDD, 1)}",
                                10.5f, Palette.dim))
                        }
                    }
                }
                export.isEnabled = true
                export.text = "خروجی CSV این گزارش"
                export.setTextColor(Palette.txt)
                export.setOnClickListener { exportReportCsv(rows, kinds[kindAtStart]) }
            }
        }
        return v
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ۵) تنظیمات (شامل لاگر و هشدارها)
    // ══════════════════════════════════════════════════════════════════════════
    private fun buildSettingsScreen(): View {
        val scroll = ScrollView(this)
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 6f),
                Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 24f))
        }
        scroll.addView(v)

        // ── نمایش چارت ──
        v.addView(Ui.section(this, "⓪ نمایش چارت"))
        v.addView(switchRow("ورود خودکار به تمام‌صفحه در تب چارت", s.autoFullscreen) { b ->
            s.autoFullscreen = b; s.persistPrefs(this)
            Log.i(Log.CAT_CFG, "تمام‌صفحهٔ خودکار = $b")
        })
        v.addView(Ui.tv(this, "ژست‌های چارت: دو انگشت = زوم همزمان زمان و قیمت · یک انگشت = جابه‌جایی · " +
            "کشیدن روی محور قیمت = فشرده/باز کردن مقیاس · دو ضربه = بازنشانی نما · دو ضربه روی محور قیمت = مقیاس خودکار · " +
            "نگه‌داشتن = کراس‌هیر و منوی چارت", 10.5f, Palette.dim))

        // ── هشدارها ──
        v.addView(Ui.section(this, "① هشدار و نوتیفیکیشن"))
        v.addView(switchRow("حالت هشدار (کلی)", Alerts.enabled) { b -> Alerts.setEnabled(this, b) })
        v.addView(switchRow("نوتیفیکیشن سیستمی", Alerts.notifyEnabled) { b -> Alerts.setNotify(this, b) })
        v.addView(switchRow("صدای هشدار", Alerts.sound) { b -> Alerts.setSound(this, b) })
        v.addView(switchRow("لرزش", Alerts.vibrate) { b -> Alerts.setVibrate(this, b) })
        v.addView(switchRow("بنر هشدار داخل اپ", Alerts.banner) { b -> Alerts.setBanner(this, b) })
        v.addView(switchRow("هشدار در حالت بک‌تست هم بدهد", Alerts.notifyInBacktest) { b -> Alerts.setInBacktest(this, b) })
        v.addView(Ui.tv(this, "انتخاب رخدادهایی که هشدار می‌دهند:", 11.5f, Palette.dim).apply {
            setPadding(0, Ui.dp(this@MainActivity, 6f), 0, 0)
        })
        val kindsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        kindsRow.addView(Ui.btn(this, "انتخاب رخدادها…", Palette.panel2, Palette.txt, 11f).apply {
            setOnClickListener { pickAlertKinds() }
        })
        kindsRow.addView(Ui.btn(this, "تست هشدار", Palette.panel2, Palette.up, 11f).apply {
            setOnClickListener { Alerts.test(this@MainActivity) }
        })
        kindsRow.addView(Ui.btn(this, "تاریخچه", Palette.panel2, Palette.txt, 11f).apply {
            setOnClickListener { showAlertHistory() }
        })
        v.addView(kindsRow)
        v.addView(Ui.tv(this, "هشدار «آماده شدن سفارش» (Armed) پیش‌فرض روشن است: لحظه‌ای که ناحیهٔ ولوم زیاد تایید و سفارش لیمیت ثبت می‌شود، " +
            "هشدار می‌گیرید؛ سپس پر شدن سفارش، TP1، TP2، حدسود ۱٫۲۷۲، حدضرر، سر‌به‌سر و پایان معامله هم هشدار دارند.", 10.5f, Palette.dim))

        // ── لاگر ──
        v.addView(Ui.section(this, "② لاگر (پیش‌فرض خاموش)"))
        v.addView(switchRow("روشن بودن لاگر", Log.enabled) { b ->
            Log.setEnabled(this, b)
            s.wireLogging()
            toast(if (b) "لاگر روشن شد — همهٔ رخدادها ثبت می‌شود" else "لاگر خاموش شد")
            showTab(4)
        })
        v.addView(switchRow("نوشتن در فایل (.md و .txt)", Log.writeToFile) { b -> Log.setWriteToFile(this, b) })
        v.addView(switchRow("نمایش در Logcat", Log.logcat) { b -> Log.setLogcat(this, b) })
        v.addView(numRow("حداقل سطح (۰ ریز · ۱ اشکال‌زدایی · ۲ اطلاع · ۳ هشدار · ۴ خطا)", Log.minLevel.toDouble()) { d ->
            Log.setLevel(this, d.toInt().coerceIn(0, 4)); showTab(4)
        })
        val logRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        logRow.addView(Ui.btn(this, "نمایش لاگ", Palette.panel2, Palette.txt, 11f).apply { setOnClickListener { showLogViewer() } })
        logRow.addView(Ui.btn(this, "ذخیره .md", Palette.panel2, Palette.txt, 11f).apply { setOnClickListener { exportLog(REQ_LOG_MD) } })
        logRow.addView(Ui.btn(this, "ذخیره .txt", Palette.panel2, Palette.txt, 11f).apply { setOnClickListener { exportLog(REQ_LOG_TXT) } })
        v.addView(logRow)
        val logRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        logRow2.addView(Ui.btn(this, "کپی متن لاگ", Palette.panel2, Palette.txt, 11f).apply { setOnClickListener { copyLog() } })
        logRow2.addView(Ui.btn(this, "فایل‌های لاگ", Palette.panel2, Palette.txt, 11f).apply { setOnClickListener { showLogFiles() } })
        logRow2.addView(Ui.btn(this, "پاک کردن بافر", Palette.panel2, Palette.down, 11f).apply { setOnClickListener { Log.clearBuffer(); toast("بافر پاک شد") } })
        v.addView(logRow2)
        v.addView(Ui.tv(this, "پوشهٔ لاگ: ${Log.dirPath()}", 10f, Palette.dim))
        v.addView(Ui.tv(this, "لاگر به موتور استراتژی، کارگزار، فید، ذخیره‌سازی، سرویس لایو، هشدارها، رابط کاربری و تنظیمات وصل است.", 10f, Palette.dim))

        // ── تایم‌فریم ──
        v.addView(Ui.section(this, "③ تایم‌فریم‌ها"))
        for (m in TF_MODES) {
            v.addView(Ui.btn(this, "مود ${Fa.d(m.idx.toString())} — ${m.pretty()}",
                if (s.cfg.tfMode == m.idx) Palette.accent else Palette.panel2,
                if (s.cfg.tfMode == m.idx) Palette.txt else Palette.dim, 11.5f).apply {
                setOnClickListener {
                    Log.i(Log.CAT_CFG, "مود تایم‌فریمی ${m.idx} انتخاب شد", m.pretty())
                    s.setMode(m.idx); toast("مود ${Fa.d(m.idx.toString())} انتخاب شد")
                    showTab(4)
                }
            })
            v.addView(Ui.spacer(this, 3f))
        }
        if (s.cfg.tfMode == 5) {
            v.addView(pickRow("ساختار", s.cfg.tfS) { s.cfg.tfS = it; s.rebuildAsync() })
            v.addView(pickRow("میانی", s.cfg.tfM) { s.cfg.tfM = it; s.rebuildAsync() })
            v.addView(pickRow("تریگر ۱", s.cfg.tf1) { s.cfg.tf1 = it; s.rebuildAsync() })
            v.addView(pickRow("تریگر ۲ (چارت)", s.cfg.tf2) { s.setCustomTf2(it) })
        }

        // ── پروفایل حجم ──
        v.addView(Ui.section(this, "④ پروفایل حجم و ناحیه‌ها (الگوریتم v2)"))
        v.addView(numRow("تعداد ردیف‌ها", s.cfg.vpRows.toDouble()) { s.cfg.vpRows = it.toInt(); s.rebuildAsync() })
        v.addView(numRow("هموارسازی (۰-۳)", s.cfg.vpSmooth.toDouble()) { s.cfg.vpSmooth = it.toInt(); s.rebuildAsync() })
        v.addView(numRow("حداقل ردیف یک ناحیه", s.cfg.minZoneRows.toDouble()) { s.cfg.minZoneRows = it.toInt(); s.rebuildAsync() })
        // §v1.4.9: باکس‌ها با «کلوز از سمت دور» پاک می‌شوند؛ محدودیت تعداد غیرفعال (نامحدود).
        v.addView(Ui.tv(this, "حداکثر باکس روی چارت: نامحدود (پاک‌سازی با کلوز از سمت دور)", 11f, Palette.dim))
        v.addView(switchRow("نمایش ناحیه‌های ردشده", s.cfg.showRejectedZones) { s.cfg.showRejectedZones = it; s.rebuildAsync() })
        v.addView(switchRow("پاک کردن باکس با کلوز از سمت دور", s.cfg.clearUsedZones) { s.cfg.clearUsedZones = it; s.rebuildAsync() })
        val dists = arrayOf("مثلثی حول Close", "مثلثی حول Typical", "یکنواخت")
        dists.forEachIndexed { i, n ->
            v.addView(Ui.btn(this, "توزیع حجم: $n", if (s.cfg.distMode == i) Palette.accent else Palette.panel2,
                if (s.cfg.distMode == i) Palette.txt else Palette.dim, 11f).apply {
                setOnClickListener { s.cfg.distMode = i; s.rebuildAsync(); showTab(4) }
            })
        }

        // ── موتور ──
        v.addView(Ui.section(this, "⑤ موتور استراتژی"))
        v.addView(numRow("حداکثر ستاپ هم‌زمان", s.cfg.maxSetups.toDouble()) { s.cfg.maxSetups = it.toInt(); s.rebuildAsync() })
        v.addView(switchRow("شکست تایید میانی با کلوز (خاموش = سایه)", s.cfg.midInvalidClose) { s.cfg.midInvalidClose = it; s.rebuildAsync() })
        v.addView(switchRow("اسکن ولوم زیاد از اولین برگشت به باکس", s.cfg.hvScanFirstTouch) { s.cfg.hvScanFirstTouch = it; s.rebuildAsync() })
        v.addView(switchRow("کندل ولوم زیاد = اولین افزایش حجم", s.cfg.hvMarkFirstIncrease) { s.cfg.hvMarkFirstIncrease = it; s.rebuildAsync() })

        // ── معامله ──
        v.addView(Ui.section(this, "⑥ معامله و بک‌تست"))
        v.addView(numRow("بافر حدضرر (تیک)", s.cfg.slBufTicks) { s.cfg.slBufTicks = it; s.rebuildAsync() })
        v.addView(numRow("حداقل فاصلهٔ حدسود (واحد)", s.cfg.minTPunits) { s.cfg.minTPunits = it; s.rebuildAsync() })
        v.addView(switchRow("حجم بر اساس ریسک ثابت (خاموش = درصد سرمایه)", s.cfg.useRiskPct) { s.cfg.useRiskPct = it; s.rebuildAsync() })
        v.addView(numRow("ریسک هر معامله (٪)", s.cfg.riskPct) { s.cfg.riskPct = it; s.rebuildAsync() })
        v.addView(numRow("حداکثر اهرم", s.cfg.maxLeverage) { s.cfg.maxLeverage = it; s.rebuildAsync() })
        v.addView(numRow("سرمایهٔ اولیه", s.cfg.initialEquity) { s.cfg.initialEquity = it; s.rebuildAsync() })
        v.addView(numRow("اندازهٔ قرارداد", s.cfg.contractSize) { s.cfg.contractSize = it; s.rebuildAsync() })

        // ── فید ──
        v.addView(Ui.section(this, "⑦ فید داده (طلا)"))
        v.addView(Ui.tv(this, "نماد ${s.symbol} · چارت ${Tf.label(s.chartTfSec)} · کندل‌ها ${Fa.d(s.candles.size.toString())} · منبع قیمت ${s.lastPriceSource}", 11f, Palette.txt))
        v.addView(editRow("نماد (Yahoo)", s.symbol) { s.symbol = it })
        val depths = arrayOf("کوتاه", "متوسط", "عمیق")
        depths.forEachIndexed { i, n ->
            v.addView(Ui.btn(this, "عمق تاریخچه: $n", if (s.depth == i + 1) Palette.accent else Palette.panel2,
                if (s.depth == i + 1) Palette.txt else Palette.dim, 11f).apply {
                setOnClickListener { s.depth = i + 1; showTab(4) }
            })
        }
        v.addView(switchRow("ساخت حجم تقریبی اگر فید حجم نداشت", s.useSyntheticVolume) { s.useSyntheticVolume = it })
        v.addView(switchRow("حالت بک‌تست کامل (آخرین کندل هم بسته)", s.backtestFull) { s.backtestFull = it; s.rebuildAsync(clearOrders = true) })
        v.addView(numRow("فاصلهٔ به‌روزرسانی لایو (ثانیه)", s.livePollMs / 1000.0) { s.livePollMs = (it * 1000).toLong().coerceAtLeast(2000) })
        v.addView(numRow("مهلت اتصال فید (ثانیه)", Feed.connectTimeoutMs / 1000.0) {
            Feed.connectTimeoutMs = (it * 1000).toInt().coerceIn(1000, 120000); s.persistPrefs(this)
        })
        v.addView(numRow("مهلت خواندن فید (ثانیه)", Feed.readTimeoutMs / 1000.0) {
            Feed.readTimeoutMs = (it * 1000).toInt().coerceIn(1000, 120000); s.persistPrefs(this)
        })
        val feedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        feedRow.addView(Ui.btn(this, "دانلود تاریخچه", Palette.panel2, Palette.txt, 11f).apply {
            setOnClickListener {
                toast("دانلود تاریخچه شروع شد… وضعیت در نوار بالای اپ نمایش داده می‌شود")
                s.downloadHistory { m -> toast(m) }
            }
        })
        feedRow.addView(Ui.btn(this, "دادهٔ نمونه", Palette.panel2, Palette.txt, 11f).apply {
            setOnClickListener {
                toast("در حال بارگذاری دادهٔ نمونه…")
                s.loadSample(applicationContext) { m -> toast(m) }
            }
        })
        feedRow.addView(Ui.btn(this, "ورود CSV", Palette.panel2, Palette.txt, 11f).apply { setOnClickListener { pickCsv() } })
        v.addView(feedRow)

        // ── ذخیره ──
        v.addView(Ui.section(this, "⑧ ذخیره و بازیابی"))
        v.addView(Ui.tv(this, "فایل خودکار: ${Storage.autoFile(this).absolutePath}", 10f, Palette.dim))
        val saveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveRow.addView(Ui.btn(this, "ذخیرهٔ فوری", Palette.panel2, Palette.txt, 11f).apply {
            setOnClickListener {
                toast("در حال ذخیره…")
                s.io.execute {
                    val msg = s.saveToAutoFile(applicationContext)
                    runOnUiThread { toast(msg) }
                }
            }
        })
        saveRow.addView(Ui.btn(this, "ذخیره در فایل…", Palette.panel2, Palette.txt, 11f).apply { setOnClickListener { pickSave() } })
        saveRow.addView(Ui.btn(this, "بازیابی از فایل…", Palette.panel2, Palette.txt, 11f).apply { setOnClickListener { pickLoad() } })
        v.addView(saveRow)
        val wipeBtn = Ui.btn(this, "پاک کردن همه چیز", Palette.panel2, Palette.down, 11.5f)
        wipeBtn.setOnClickListener {
            AlertDialog.Builder(this).setTitle("پاک کردن همه چیز؟").setMessage("کندل‌ها، سفارش‌ها، معاملات و باکس‌ها پاک می‌شود.")
                .setPositiveButton("بله") { _, _ -> s.wipe(); toast("پاک شد") }.setNegativeButton("نه", null).show()
        }
        v.addView(wipeBtn)

        // ── درباره ──
        v.addView(Ui.section(this, "⑨ دربارهٔ اپ"))
        v.addView(Ui.tv(this, "GoldPin نسخهٔ ${Fa.d(appVersionName())}\n" +
            "موتور: کندل مهم → Ready → pin → روند → ناحیهٔ فیکس‌رنج (الگوریتم v2) → تایید میانی → ولوم کم (تریگر۱) → ولوم زیاد (تریگر۲) → ورود/حدضرر/TP1 ۳۸٪/TP2 ۵۰٪/حدسود ۱٫۲۷۲\n" +
            "معاملات کاغذی است؛ به کارگزار وصل نیست. همهٔ محاسبات داخل گوشی انجام می‌شود.\n" +
            "مستندات کامل پروژه در پوشهٔ docs مخزن گیت‌هاب است.", 10.5f, Palette.dim))
        return scroll
    }

    private fun pickAlertKinds() {
        val kinds = Alerts.ALL_KINDS.toTypedArray()
        val labels = kinds.map { Alerts.kindFa(it) }.toTypedArray()
        val checked = kinds.map { Alerts.isOn(it) }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("رخدادهایی که هشدار بدهند")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                Alerts.setKind(this, kinds[which], isChecked)
            }
            .setPositiveButton("بستن", null)
            .show()
    }

    private fun showAlertHistory() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 8f),
                Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 8f))
        }
        val h = Alerts.history().takeLast(60).reversed()
        if (h.isEmpty()) box.addView(Ui.tv(this, "هنوز هشداری ثبت نشده.", 12f, Palette.dim))
        for (r in h) {
            box.addView(Ui.tv(this, "${Alerts.kindFa(r.kind)} · ${Fa.jalali(r.t)}\n${r.title}\n${r.body}",
                11.5f, Palette.txt).apply {
                setPadding(0, Ui.dp(this@MainActivity, 5f), 0, Ui.dp(this@MainActivity, 5f))
            })
        }
        AlertDialog.Builder(this).setTitle("تاریخچهٔ هشدارها").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("بستن", null)
            .setNeutralButton("پاک کردن") { _, _ -> Alerts.clearHistory() }
            .show()
    }

    private fun showLogViewer() {
        val lines = Log.tail(300)
        val text = if (lines.isEmpty()) {
            "لاگر خاموش است یا هنوز خطی ثبت نشده.\nبرای فعال‌سازی: تنظیمات ← بخش لاگر ← «روشن بودن لاگر»."
        } else lines.joinToString("\n")
        val tv = Ui.tv(this, text, 9.5f, Palette.txt).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 8f),
                Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 8f))
        }
        AlertDialog.Builder(this)
            .setTitle("لاگ زنده (${Fa.d(lines.size.toString())} خط آخر)")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("بستن", null)
            .setNeutralButton("تازه‌سازی") { _, _ -> showLogViewer() }
            .setNegativeButton("کپی") { _, _ -> copyLog() }
            .show()
    }

    private fun showLogFiles() {
        val files = Log.files()
        if (files.isEmpty()) { toast("فایل لاگی ساخته نشده"); return }
        val names = files.map { "${it.name}  (${Fa.n(it.length() / 1024.0, 1)} کیلوبایت)" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("فایل‌های لاگ · ${Log.dirPath()}")
            .setItems(names) { _, i -> toast("مسیر: ${files[i].absolutePath}") }
            .setPositiveButton("بستن", null)
            .show()
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("GoldPin log", Log.reportTxt()))
        toast("متن لاگ کپی شد")
    }

    private fun exportLog(req: Int) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (req == REQ_LOG_MD) "text/markdown" else "text/plain"
            putExtra(Intent.EXTRA_TITLE, if (req == REQ_LOG_MD) "goldpin_log.md" else "goldpin_log.txt")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, req)
    }

    // ── ردیف‌های کمکی تنظیمات ──────────────────────────────────────────────────
    private fun pickRow(label: String, current: Int, onPick: (Int) -> Unit): View =
        Ui.btn(this, "$label : ${Tf.label(current)}", Palette.panel2, Palette.txt, 11.5f).apply {
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle(label)
                    .setItems(tfChoices.map { Tf.label(it) }.toTypedArray()) { _, w -> onPick(tfChoices[w]); showTab(4) }
                    .show()
            }
        }

    private fun numRow(label: String, value: Double, apply: (Double) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val e = Ui.edit(this, if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString())
        row.addView(Ui.tv(this, label, 11.5f, Palette.dim), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.7f))
        row.addView(e, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.btn(this, "ثبت", Palette.panel2, Palette.accent, 11f).apply {
            setOnClickListener {
                val d = e.text.toString().trim().toDoubleOrNull()
                if (d == null) toast("عدد نامعتبر") else {
                    apply(d); Log.i(Log.CAT_CFG, "تنظیم «$label» = $d")
                    toast("$label ثبت شد"); showTab(4)
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun editRow(label: String, value: String, apply: (String) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val e = Ui.edit(this, value)
        e.inputType = android.text.InputType.TYPE_CLASS_TEXT
        row.addView(Ui.tv(this, label, 11.5f, Palette.dim), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.7f))
        row.addView(e, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.btn(this, "ثبت", Palette.panel2, Palette.accent, 11f).apply {
            setOnClickListener { apply(e.text.toString().trim()); Log.i(Log.CAT_CFG, "«$label» = ${e.text}"); showTab(4) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun switchRow(label: String, value: Boolean, apply: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(Ui.tv(this, label, 11.5f, Palette.dim), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Switch(this).apply {
            isChecked = value
            setOnCheckedChangeListener { _, b -> apply(b) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  دیالوگ‌ها
    // ══════════════════════════════════════════════════════════════════════════
    private fun showTradeDialog(t: Trade) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 10f),
                Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 10f))
        }
        fun add(k: String, v: String, c: Int = Palette.txt) { box.addView(Ui.label(this, k, v, vColor = c)) }
        val pnl = t.pnl()
        add("شناسه", "#${Fa.d(t.id.toString())}")
        add("جهت", if (t.dir == 1) "خرید (Long)" else "فروش (Short)")
        add("وضعیت", if (t.open) "باز" else "بسته — ${t.reason}")
        add("زمان ورود", Fa.jalali(t.entryT))
        add("قیمت ورود", Fa.n(t.entry))
        add("حجم", "${Fa.n(t.qty, 3)} اونس")
        add("حد ضرر اولیه", Fa.n(t.sl0))
        add("TP1 / TP2", "${Fa.n(t.tp1)} / ${Fa.n(t.tp2)}")
        add("حد سود نهایی (۱٫۲۷۲)", Fa.n(t.tpX))
        add("ناحیهٔ ساختار", "${Fa.n(t.zoneBot)} تا ${Fa.n(t.zoneTop)}")
        if (!t.open) {
            add("زمان خروج", Fa.jalali(t.exitT ?: 0))
            add("قیمت خروج", Fa.n(t.exitPx ?: Double.NaN))
            add("مدت", duration(t.entryT, t.exitT ?: 0))
        }
        add("سر‌به‌سر", if (t.be) "فعال (پس از TP1)" else "فعال نشد")
        add("سود/زیان", "${Fa.signed(pnl)} دلار", if (pnl >= 0) Palette.up else Palette.down)
        add("بازده بر ریسک", "${Fa.n(t.rMultiple(), 2)} R")
        box.addView(Ui.spacer(this, 6f))
        box.addView(Ui.tv(this, "پله‌های اجرا", 12f, Palette.gold, true))
        for (f in t.fills) {
            box.addView(Ui.tv(this, "• ${f.kind}  ${Fa.n(f.qty, 3)} @ ${Fa.n(f.price)} · ${Fa.signed(f.pnl)} دلار · ${Fa.jalali(f.t)}",
                11f, if (f.pnl >= 0) Palette.txt else Palette.down))
        }
        AlertDialog.Builder(this).setTitle("جزئیات معامله").setView(box).setPositiveButton("بستن", null).show()
    }

    private fun showOrderDialog(o: Order) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 10f),
                Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 10f))
        }
        fun add(k: String, v: String) { box.addView(Ui.label(this, k, v)) }
        add("شناسه", "#${Fa.d(o.id.toString())}")
        add("نوع", "سفارش لیمیت ${if (o.dir == 1) "خرید" else "فروش"} (کاغذی)")
        add("وضعیت", when (o.status) {
            OrderStatus.PENDING -> "آماده · در انتظار پر شدن"
            OrderStatus.FILLED -> "پر شده"
            OrderStatus.CANCELLED_EXPIRED -> "منقضی شده"
            else -> "لغو شده" + (o.cancelReason?.let { " — $it" } ?: "")
        })
        add("قیمت سفارش", Fa.n(o.price))
        add("حد ضرر", Fa.n(o.sl))
        add("TP1 / TP2 / TP نهایی", "${Fa.n(o.tp1)} / ${Fa.n(o.tp2)} / ${Fa.n(o.tpX)}")
        add("حجم", "${Fa.n(o.qty, 3)} اونس")
        add("ناحیهٔ ساختار", "${Fa.n(o.zoneBot)} تا ${Fa.n(o.zoneTop)}")
        add("زمان ثبت", Fa.jalali(o.placedT))
        o.filledT?.let { add("زمان پر شدن", Fa.jalali(it)) }
        o.closedT?.let { add("زمان لغو/انقضا", Fa.jalali(it)) }
        AlertDialog.Builder(this).setTitle("جزئیات سفارش").setView(box).setPositiveButton("بستن", null).show()
    }

    private fun duration(a: Long, b: Long): String {
        val d = abs(b - a) / 1000
        return "${Fa.d((d / 3600).toString())} ساعت و ${Fa.d(((d % 3600) / 60).toString())} دقیقه"
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  فایل‌ها
    // ══════════════════════════════════════════════════════════════════════════
    private fun pickSave() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/gzip"
            putExtra(Intent.EXTRA_TITLE, "goldpin_state_${System.currentTimeMillis() / 1000}.json.gz")
        }, REQ_SAVE)
    }

    private fun pickLoad() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_LOAD)
    }

    private fun pickCsv() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_CSV)
    }

    private fun exportTradesCsv() {
        pendingReport = tradesCsv()
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "goldpin_trades.csv")
        }, REQ_REPORT)
    }

    private fun exportReportCsv(rows: List<Reports.Row>, title: String) {
        val sb = StringBuilder("دوره,معاملات,برد,باخت,نرخ برد,سود خالص,ضریب سود,میانگین R,بیشترین افت\n")
        for (r in rows) {
            sb.append(r.label).append(',').append(r.trades).append(',').append(r.wins).append(',').append(r.losses)
                .append(',').append(String.format("%.1f", r.winRate)).append(',').append(String.format("%.2f", r.net))
                .append(',').append(String.format("%.2f", r.profitFactor)).append(',').append(String.format("%.2f", r.avgR))
                .append(',').append(String.format("%.2f", r.maxDD)).append('\n')
        }
        pendingReport = sb.toString()
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "goldpin_report_$title.csv")
        }, REQ_REPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            when (requestCode) {
                REQ_SAVE -> {
                    toast("در حال ذخیره…")
                    val u = uri
                    s.io.execute {
                        val msg = try {
                            Storage.writeUri(this, u, s.buildJson())
                            "ذخیره شد: ${u.lastPathSegment}"
                        } catch (e: Exception) { "خطای ذخیره: ${e.message}" }
                        Log.i(Log.CAT_STORE, "ذخیره در مسیر انتخابی", u.toString())
                        runOnUiThread { toast(msg) }
                    }
                }
                REQ_LOAD -> {
                    Log.i(Log.CAT_STORE, "بازیابی از مسیر انتخابی", uri.toString())
                    toast("در حال بازیابی… (پروندهٔ بزرگ چند لحظه طول می‌کشد)")
                    val u = uri
                    s.io.execute {
                        val msg = try {
                            s.loadFromText(Storage.readUri(this, u))
                        } catch (e: Exception) { "خطای بازیابی: ${e.message}" }
                        runOnUiThread { toast(msg); showTab(0) }
                    }
                }
                REQ_CSV -> s.importCsv(this, uri, s.baseTfSec) { m -> toast(m); showTab(0) }
                REQ_REPORT -> { Storage.writeReport(this, uri, pendingReport ?: tradesCsv()); toast("گزارش ذخیره شد") }
                REQ_LOG_MD -> {
                    val u = uri
                    s.io.execute {
                        val text = Log.reportMd()
                        Storage.writeReport(this, u, text)
                        Log.i(Log.CAT_APP, "خروجی .md لاگ ذخیره شد", u.toString())
                        runOnUiThread { toast("لاگ با فرمت .md ذخیره شد") }
                    }
                }
                REQ_LOG_TXT -> {
                    val u = uri
                    s.io.execute {
                        val text = Log.reportTxt()
                        Storage.writeReport(this, u, text)
                        Log.i(Log.CAT_APP, "خروجی .txt لاگ ذخیره شد", u.toString())
                        runOnUiThread { toast("لاگ با فرمت .txt ذخیره شد") }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(Log.CAT_STORE, "خطا در عملیات فایل", e)
            toast("خطا: ${e.message}")
        }
    }

    private fun tradesCsv(): String {
        val sb = StringBuilder("id,dir,entryT,entry,exitT,exit,sl,tpX,qty,pnl,R,reason\n")
        for (t in s.tradesSnapshot()) {
            sb.append(t.id).append(',').append(if (t.dir == 1) "BUY" else "SELL").append(',')
                .append(t.entryT).append(',').append(t.entry).append(',')
                .append(t.exitT ?: 0).append(',').append(t.exitPx ?: 0.0).append(',')
                .append(t.sl0).append(',').append(t.tpX).append(',').append(t.qty).append(',')
                .append(String.format("%.2f", t.pnl())).append(',').append(String.format("%.2f", t.rMultiple()))
                .append(',').append(t.reason).append('\n')
        }
        return sb.toString()
    }

    // ══════════════════════════════════════════════════════════════════════════
    private fun toggleLive() {
        if (s.liveRunning) {
            EngineService.stop(this)
            Log.i(Log.CAT_LIVE, "ارزیابی زنده متوقف شد")
            toast("ارزیابی زنده متوقف شد")
        } else {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 77)
            EngineService.start(this)
            Log.i(Log.CAT_LIVE, "ارزیابی زنده روشن شد")
            Alerts.fire(this, Alerts.K_INFO, "ارزیابی زنده روشن شد",
                "شرایط استراتژی دائماً بررسی می‌شود و سفارش‌های کاغذی به‌صورت خودکار ثبت می‌شوند.")
            toast("ارزیابی زنده روشن شد")
        }
        refreshCurrent()
    }

    /** نسخهٔ واقعی APK — تا متن «دربارهٔ اپ» هیچ‌وقت از versionName عقب نماند. */
    private fun appVersionName(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    private fun toast(msg: String) {
        // Toast هم یک TextView است؛ رشتهٔ چندصدکیلوبایتی همان‌جا نخ رابط را می‌گیرد.
        android.widget.Toast.makeText(this, Fa.short(msg, 220), android.widget.Toast.LENGTH_LONG).show()
    }
}
