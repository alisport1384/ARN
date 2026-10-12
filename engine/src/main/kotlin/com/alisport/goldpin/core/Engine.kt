package com.alisport.goldpin.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// ═══════════════════════════════════════════════════════════════════════════════
//  موتور PinReady — پورت کامل اسکریپت Pine «PinReady_FRVP_Strategy»
//  زنجیره: کندل مهم → Ready(BU/BE) → pinBU/pinBE → روند(HH/HL/LL/LH)
//          → ناحیهٔ فیکس‌رنج (الگوریتم v2) → تایید میانی → ناحیهٔ ولوم کم (تریگر۱)
//          → ناحیهٔ ولوم زیاد (تریگر۲ / چارت) → ورود / حدضرر / حدسود / سر‌به‌سر
//
//  ⚠ موتور فقط با کلوز کندل‌ها تصمیم می‌گیرد (بدون ریپینت). کندل باز فقط پیش‌نمایش است.
// ═══════════════════════════════════════════════════════════════════════════════

class Settings {
    var tfMode: Int = 1
    var tfS: Int = Tf.H1
    var tfM: Int = Tf.M15
    var tf1: Int = Tf.M5
    var tf2: Int = Tf.M1

    var vpRows: Int = 24
    var vpVA: Double = 10.0
    var vpSmooth: Int = 1
    var distMode: Int = VolumeProfile.DIST_TRI_CLOSE
    var minZoneRows: Int = 1
    var drawZones: Boolean = true
    var showRejectedZones: Boolean = true
    var clearUsedZones: Boolean = true
    var maxZoneBoxes: Int = Int.MAX_VALUE              // §7: نامحدود (از ۱٫۴٫۹)
    var showPocVA: Boolean = true

    var maxSetups: Int = 6
    /**
     * `true`  ⇒ تایید میانی فقط با **بسته شدن** کندل میانی آن‌طرف سطح باطل می‌شود.
     * `false` ⇒ با **عبور شمع** باطل می‌شود، حتی یک تیک و حتی اگر برگردد و آن‌طرف بسته نشود.
     *
     * پیش‌فرض: `false` (عبور شمع کافی است) — مطابق سند نهایی.
     */
    var midInvalidClose: Boolean = false       // true=شکست با کلوز ، false=با سایه
    var hvScanFirstTouch: Boolean = true      // true=از اولین برگشت به باکس ولوم کم
    var hvMarkFirstIncrease: Boolean = true   // true=اولین افزایش حجم نسبت به کندل قبل ، false=RunningMax
    var rejectCandleNext: Boolean = false

    var mintick: Double = 0.01
    var slBufTicks: Double = 2.0
    var minTPunits: Double = 10.0

    var useRiskPct: Boolean = true
    var riskPct: Double = 1.0
    var equityPct: Double = 100.0
    var maxLeverage: Double = 10.0            // §5: اهرم پیش‌فرض ۱۰ (تصمیم نهایی مالک ۱٫۴٫۹)
    var roundQty: Boolean = false
    // §4-8/§۱۴ سند نهایی: انقضای سفارش معلق با عبور باز/کلوز از آخرین pinBE/pinBU
    // (سطح هنگام مسلح‌شدن ذخیره می‌شود) یا گذشت یک کندل ساختار کامل بعد از مسلح‌شدن.
    // سقف زمانی «لغو پس از N کندل» طبق تصمیم مالک حذف شد (سند §۱۴: لازم نیست).
    var contractSize: Double = 1.0
    var initialEquity: Double = 100000.0     // §5: سرمایهٔ اولیه ۱۰۰٬۰۰۰ (تصمیم نهایی)

    fun copy(): Settings = Settings().also {
        it.tfMode = tfMode; it.tfS = tfS; it.tfM = tfM; it.tf1 = tf1; it.tf2 = tf2
        it.vpRows = vpRows; it.vpVA = vpVA; it.vpSmooth = vpSmooth; it.distMode = distMode
        it.minZoneRows = minZoneRows; it.drawZones = drawZones; it.showRejectedZones = showRejectedZones
        it.clearUsedZones = clearUsedZones; it.maxZoneBoxes = maxZoneBoxes; it.showPocVA = showPocVA
        it.maxSetups = maxSetups; it.midInvalidClose = midInvalidClose
        it.hvScanFirstTouch = hvScanFirstTouch; it.hvMarkFirstIncrease = hvMarkFirstIncrease
        it.rejectCandleNext = rejectCandleNext
        it.mintick = mintick; it.slBufTicks = slBufTicks; it.minTPunits = minTPunits
        it.useRiskPct = useRiskPct; it.riskPct = riskPct; it.equityPct = equityPct
        it.maxLeverage = maxLeverage; it.roundQty = roundQty
        it.contractSize = contractSize; it.initialEquity = initialEquity
    }
}

class Counters {
    var setup = 0; var touch = 0; var mid = 0; var lv = 0; var hv = 0; var entry = 0; var done = 0
    var zoneCount = 0; var zoneRejected = 0
}

class Engine(val cfg: Settings) {

    // ── وضعیت ──────────────────────────────────────────────────────────────────
    val aggS = Agg(); val aggM = Agg(); val agg1 = Agg()
    val structBars = ArrayList<Candle>()
    val pins = ArrayList<Pin>()
    val setups = ArrayList<Setup>()
    val microS = ArrayList<Candle>()

    val zones = ArrayList<ZoneBox>()          // همهٔ باکس‌های ساختار با چرخهٔ عمر (برای ذخیره/بازیابی دقیق)
    val t1Zones = ArrayList<ZoneBox>()        // باکس‌های تاییدشدهٔ تریگر ۱ (ولوم کم) در حافظه
    val t2Zones = ArrayList<ZoneBox>()        // باکس‌های تاییدشدهٔ تریگر ۲ (ولوم زیاد) در حافظه
    val markers = ArrayList<Marker>()         // برچسب‌های استراتژی روی چارت
    val events = ArrayList<String>()          // گزارش رویدادها
    val cnt = Counters()

    var trend = 0
    var HH = Double.NaN; var HL = Double.NaN; var LL = Double.NaN; var LH = Double.NaN
    var tempBoxTop = Double.NaN; var tempBoxBot = Double.NaN
    var hlPinLo = Double.NaN; var hlPinRef = Double.NaN; var hlPinHi = Double.NaN; var hlPinSeq = -1
    var hhPinHi = Double.NaN; var hhPinRef = Double.NaN; var hhPinLo = Double.NaN; var hhPinSeq = -1
    var lhPinHi = Double.NaN; var lhPinRef = Double.NaN; var lhPinLo = Double.NaN; var lhPinSeq = -1
    var llPinLo = Double.NaN; var llPinRef = Double.NaN; var llPinHi = Double.NaN; var llPinSeq = -1
    var seqStart = 0
    var bullBU1Low = Double.NaN; var bullBEHigh = Double.NaN; var bullBU2Low = Double.NaN; var bullBU2Bi = -1
    var bearBE1High = Double.NaN; var bearBULow = Double.NaN; var bearBE2High = Double.NaN; var bearBE2Bi = -1
    var bullSetup = false; var bearSetup = false
    var expectedReady = 0
    var lowestBUpin = Double.NaN; var highestBE = Double.NaN
    var lastBUpin = Double.NaN; var lastBEpin = Double.NaN

    // ── چرخهٔ عمر پین‌ها و روندِ توالی‌محور (سند نهایی §4-3/§4-4) ────────────────
    /** `lastBUpin`/`lastBEpin` همراه با ref و لبهٔ دیگر، هنگام ابطال کامل پاک می‌شوند؛
     *  `buActive`/`beActive` مشخص می‌کنند پین ثبت‌شده هنوز معتبر است یا نه. */
    var buActive = false; var beActive = false
    /** شمارهٔ توالی: با هر پینِ تاییدشده یکی زیاد می‌شود تا بدانیم کدام بعد از کدام آمده. */
    var pinSeq = 0; var buSeq = -1; var beSeq = -1
    /** `ref` و لبهٔ دیگرِ کندل مهم — برای بند ۳ (ابطال بعد از Ready). */
    var lastBUpinRef = Double.NaN; var lastBUpinHi = Double.NaN
    var lastBEpinRef = Double.NaN; var lastBEpinLo = Double.NaN

    var lastZoneTop = Double.NaN; var lastZoneBot = Double.NaN
    var lastPocPx = Double.NaN; var lastProfBars = 0
    var profileOnLastS: Profile? = null

    var lastEntryPx = Double.NaN; var lastSlPx = Double.NaN; var lastTpPx = Double.NaN
    var lastResult = "-"
    var stageFa = "-"

    var prevT1Vol = Double.NaN
    var zoneSeq = 0L
    var setupSeq = 0L
    var processed = 0
    var curBi = -1
    var curT = 0L
    var curVol = 0.0
    var prevChartVol = Double.NaN

    // پیش‌نمایش زنده (کندل باز) — فقط برای نمایش، بدون تغییر وضعیت
    val livePreview = ArrayList<String>()
    var liveCandle: Candle? = null

    var broker: PaperBroker? = null

    /** قلاب همگام‌سازی: بعد از پردازش هر کندل بسته فراخوانی می‌شود (برای کارگزار کاغذی). */
    var barCommitHook: ((Candle) -> Unit)? = null

    // ── قلاب‌های لاگر و هشدار (اپ آن‌ها را وصل می‌کند؛ موتور خالص می‌ماند) ──
    /** (دسته ، پیام ، داده) */
    var logSink: ((String, String, String?) -> Unit)? = null
    /** (نوع هشدار AlertKind ، عنوان ، توضیح) */
    var alertSink: ((String, String, String) -> Unit)? = null

    private fun lg(cat: String, msg: String, data: String? = null) {
        logSink?.invoke(cat, msg, data)
    }

    private fun alarm(kind: String, title: String, body: String) {
        alertSink?.invoke(kind, title, body)
    }

    val tf2: Int get() = cfg.tf2

    fun reset() {
        aggS.reset(); aggM.reset(); agg1.reset()
        structBars.clear(); pins.clear(); setups.clear(); microS.clear()
        zones.clear(); t1Zones.clear(); t2Zones.clear(); markers.clear(); events.clear()
        trend = 0; HH = Double.NaN; HL = Double.NaN; LL = Double.NaN; LH = Double.NaN
        tempBoxTop = Double.NaN; tempBoxBot = Double.NaN
        hlPinLo = Double.NaN; hlPinRef = Double.NaN; hlPinHi = Double.NaN; hlPinSeq = -1
        hhPinHi = Double.NaN; hhPinRef = Double.NaN; hhPinLo = Double.NaN; hhPinSeq = -1
        lhPinHi = Double.NaN; lhPinRef = Double.NaN; lhPinLo = Double.NaN; lhPinSeq = -1
        llPinLo = Double.NaN; llPinRef = Double.NaN; llPinHi = Double.NaN; llPinSeq = -1
        seqStart = 0
        bullBU1Low = Double.NaN; bullBEHigh = Double.NaN; bullBU2Low = Double.NaN; bullBU2Bi = -1
        bearBE1High = Double.NaN; bearBULow = Double.NaN; bearBE2High = Double.NaN; bearBE2Bi = -1
        bullSetup = false; bearSetup = false; expectedReady = 0
        lowestBUpin = Double.NaN; highestBE = Double.NaN; lastBUpin = Double.NaN; lastBEpin = Double.NaN
        buActive = false; beActive = false
        pinSeq = 0; buSeq = -1; beSeq = -1
        lastBUpinRef = Double.NaN; lastBUpinHi = Double.NaN
        lastBEpinRef = Double.NaN; lastBEpinLo = Double.NaN
        lastZoneTop = Double.NaN; lastZoneBot = Double.NaN; lastPocPx = Double.NaN; lastProfBars = 0
        profileOnLastS = null
        lastEntryPx = Double.NaN; lastSlPx = Double.NaN; lastTpPx = Double.NaN
        lastResult = "-"; stageFa = "-"
        prevT1Vol = Double.NaN; zoneSeq = 0; setupSeq = 0
        processed = 0; curBi = -1; curT = 0; curVol = 0.0; prevChartVol = Double.NaN
        livePreview.clear(); liveCandle = null
        times.clear()
    }

    // ── ورود داده ──────────────────────────────────────────────────────────────
    /**
     * پردازش لیست کندل‌های تایم‌فریم تریگر۲ (چارت).
     * همهٔ کندل‌ها به‌جز آخری «بسته» در نظر گرفته می‌شوند؛ آخری فقط پیش‌نمایش زنده است.
     */
    fun feed(candles: List<Candle>, lastIsClosed: Boolean = false) {
        if (candles.isEmpty()) return
        val lastIdx = candles.size - 1
        var i = processed
        while (i <= lastIdx) {
            val cd = candles[i]
            val isLast = i == lastIdx
            if (!isLast || lastIsClosed) {
                processClosed(cd, i, candles.getOrNull(i - 1))
                processed = i + 1
            } else {
                updateLive(cd, i, candles.getOrNull(i - 1))
            }
            i++
        }
    }

    /** بارگذاری کامل از صفر (برای بک‌تست). همهٔ کندل‌ها بسته‌اند. */
    fun runAll(candles: List<Candle>) {
        reset()
        feed(candles, lastIsClosed = true)
    }

    /**
     * پیش‌بارگذاری توالی‌های ساختار از تاریخچهٔ قدیمی‌ترِ تایم‌فریم ساختار.
     * این بخش فقط پین‌ها، توالی و روند را بازسازی می‌کند؛ بدون کندل‌های تریگر۲
     * هیچ پروفایل/باکس دقیقی ساخته نمی‌شود. کندل‌های هم‌پوشان با شروع تاریخچهٔ
     * تریگر۲ کنار گذاشته می‌شوند تا با اجرای اصلی دوباره پردازش نشوند.
     *
     * ورودی باید از قبل به [cfg.tfS] تجمیع شده و مرتب زمانی باشد.
     * ایندکس منفی باعث می‌شود تاریخچهٔ seed پیش از ایندکس‌های عادی تریگر۲ بماند،
     * در حالی که مارکرها با زمان واقعی روی هر تایم‌فریم چارت نگاشت می‌شوند.
     */
    fun seedStructureHistory(structureCandles: List<Candle>, firstTrigger2Time: Long): Int {
        if (structureCandles.isEmpty() || firstTrigger2Time <= 0L || processed != 0 ||
            structBars.isNotEmpty() || markers.isNotEmpty()) return 0

        val firstModeBucket = Tf.bucket(cfg.tfS, firstTrigger2Time)
        val history = structureCandles.asSequence()
            .sortedBy { it.t }
            .map { c ->
                val bucket = Tf.bucket(cfg.tfS, c.t)
                Candle(c.bi, bucket, c.o, c.h, c.l, c.c, c.v)
            }
            .filter { it.t < firstModeBucket }
            .distinctBy { it.t }
            .toList()
        if (history.isEmpty()) return 0

        val firstBi = -history.size
        for ((index, src) in history.withIndex()) {
            val bi = firstBi + index
            val bar = Candle(bi, src.t, src.o, src.h, src.l, src.c, src.v)
            times[bi] = bar.t
            curBi = bi
            curT = bar.t + cfg.tfS * 1000L
            curVol = bar.v
            structureEngine(bar, buildZones = false)
        }
        return history.size
    }

    // ── پیش‌نمایش کندل باز (بدون تغییر وضعیت) ────────────────────────────────────
    /**
     * ⚠ این متد **هیچ** تغییری در وضعیت موتور نمی‌دهد.
     *
     * پیش‌تر کندل باز هم در تجمیع‌کننده‌ها ([aggS]/[aggM]/[agg1]) گام می‌زد. چون فید زنده
     * هر چند ثانیه همان کندل باز را دوباره می‌فرستد، حجم آن کندل به ازای هر تیک یک‌بار
     * دیگر جمع می‌شد (۲۰ تیک → حجم ~۲ برابر). آن حجم بادکرده بعداً در `agg?.cl` کپی می‌شد
     * و `prevT1Vol` را خراب می‌کرد → شرط «کندل ولوم کم» بیش از حد راحت پاس می‌شد و
     * نتیجهٔ لایو با بک‌تست یکی نبود.
     *
     * حالا تجمیع فقط در [processClosed] و فقط با کندل بسته انجام می‌شود (بدون ریپینت).
     */
    private fun updateLive(cd: Candle, i: Int, prev: Candle?) {
        curBi = i; curT = cd.t; curVol = cd.v; liveCandle = cd
        times[i] = cd.t
        livePreview.clear()
        prevChartVol = prev?.v ?: Double.NaN
        // وضعیت‌های در انتظار (فقط خواندنی)
        for (s in setups) {
            if (s.stage >= 1 && s.stage <= 6 && !s.zGone) {
                val d = if (s.dir == 1) "صعودی" else "نزولی"
                livePreview.add("ستاپ #${s.id} $d · مرحله: ${stageFa(s.stage)}")
            }
        }
        // آیا کندل جاری در حال مارک شدن به‌عنوان کندل ولوم زیاد است؟
        for (s in setups) {
            if (s.stage >= 4 && s.stage <= 5 && s.lvReEntered && s.hvBi < 0) {
                val inc = cd.v > (prev?.v ?: 0.0)
                if (inc) livePreview.add("کندل ولوم زیاد در حال مارک (حجم ${fmtV(cd.v)} > قبلی)")
            }
        }
    }

    private fun tfS() = cfg.tfS
    private fun tfM() = cfg.tfM
    private fun tf1() = cfg.tf1

    private fun aggStep(a: Agg, i: Int, cd: Candle, isNew: Boolean) {
        a.step(isNew, i, cd.t, cd.o, cd.h, cd.l, cd.c, cd.v)
    }

    // ── پردازش کندل بسته ───────────────────────────────────────────────────────
    private fun processClosed(cd: Candle, i: Int, prev: Candle?) {
        curBi = i; curT = cd.t; curVol = cd.v
        times[i] = cd.t
        if (times.size > 200000) times.clear()
        val isNewS = Tf.isNewBarStart(tfS(), cd.t, prev?.t)
        val isNewM = Tf.isNewBarStart(tfM(), cd.t, prev?.t)
        val isNew1 = Tf.isNewBarStart(tf1(), cd.t, prev?.t)

        aggStep(aggS, i, cd, isNewS)
        aggStep(aggM, i, cd, isNewM)
        aggStep(agg1, i, cd, isNew1)

        // ترتیب دقیقاً مثل Pine: ساختار → میانی → تریگر۱ → تریگر۲
        if (aggS.closedNow) {
            aggS.cl?.let { structureEngine(it) }
            // §4-8: فقط پس از گذشت یک کندل ساختار کامل از زمان آخرین مسلح‌شدن،
            // مسلح‌مجدد بسته می‌شود. اگر معامله باز باشد، خود معامله ادامه پیدا می‌کند.
            val it2 = setups.iterator()
            while (it2.hasNext()) {
                val s = it2.next()
                val structureElapsed = fullStructureElapsed(s.armBi, i, cfg.tfS, cfg.tf2)
                if (structureElapsed && s.stage == 4 && s.rearmAllowed) {
                    s.stage = 8; s.note = "گذشت یک کندل ساختار کامل پس از مسلح‌شدن"
                    s.rearmAllowed = false
                    closeT1Box(s, ZoneStatus.CONSUMED, i, cd.t)
                    closeT2Box(s, ZoneStatus.DELETED, i, cd.t)
                    broker?.onSetupInvalidated(s, curT, curBi)
                    events.add("[${s.id}] یک کندل ساختار کامل پس از مسلح‌شدن گذشت — مسلح‌مجدد ممنوع")
                    lg("ENGINE", "یک کندل ساختار کامل گذشت → مسلح‌مجدد ستاپ #${s.id} ممنوع",
                        "bar=$i")
                } else if (structureElapsed && s.stage == 7 && s.rearmAllowed) {
                    s.rearmAllowed = false
                    lg("ENGINE", "یک کندل ساختار کامل در زمان معامله گذشت → مسلح‌مجدد بعدی ممنوع · ستاپ #${s.id}",
                        "bar=$i")
                }
            }
        }
        if (aggM.closedNow) middleEngine(aggM.cl!!)
        if (agg1.closedNow) trigger1Engine(agg1.cl!!)
        trigger2Engine(cd)

        // ریزکندل‌های تریگر۲ داخل کندل ساختار جاری
        if (isNewS) microS.clear()
        if (microS.size > 3000) microS.removeAt(0)
        microS.add(cd)

        // چرخهٔ عمر باکس‌های ناحیه (حذف با کلوز از سمت دور)
        if (cfg.clearUsedZones) {
            for (zb in zones) {
                if (zb.status != ZoneStatus.ACTIVE) continue
                val dead = if (zb.dir == 1) cd.c < zb.bot else cd.c > zb.top
                if (dead) {
                    zb.status = ZoneStatus.DELETED
                    zb.endBi = i; zb.endT = cd.t
                }
            }
        }
        // سقف تعداد باکس‌های نمایان:
        // ⚠ پیش‌تر این‌جا `zones.size > maxZoneBoxes` سنجیده می‌شد؛ ولی `zones` هرگز کوچک نمی‌شود
        // (باکس‌ها فقط وضعیت می‌گیرند)، پس بعد از ساخت ۲۴ باکس، در **هر** کندل یک باکس زنده
        // بی‌دلیل CAP می‌شد و عملاً هیچ باکس ناحیه‌ای روی چارت نمی‌ماند.
        // شمارش درست = فقط باکس‌های نمایان (فعال/ردشده) — همان معیاری که trimZones دارد.
        trimZones()
        if (agg1.closedNow && agg1.cl != null) prevT1Vol = agg1.cl!!.v
        prevChartVol = cd.v
        cnt.zoneCount = zones.size
        barCommitHook?.invoke(cd)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ⑩ موتور ساختار (کندل مهم · Ready · pin · روند · ناحیه فیکس‌رنج)
    // ═══════════════════════════════════════════════════════════════════════════
    private fun structureEngine(sb: Candle) = structureEngine(sb, buildZones = true)

    private fun structureEngine(sb: Candle, buildZones: Boolean) {
        structBars.add(sb)
        while (structBars.size > 400) structBars.removeAt(0)
        val nS = structBars.size

        // ── تشخیص جفت‌کندل‌ها و ثبت کاندید کندل مهم ──
        if (nS >= 2) {
            val a = structBars[nS - 2]
            val b = structBars[nS - 1]
            if (a.c < a.o && b.c > b.o) {                       // کندل مهم در پایین‌ترین‌ها
                val (impH, impL, impBi) = pickLow(a, b)
                // §4-1 سند نهایی: سطح ابطال پایین‌ترین کندل‌ها = کمترین قیمت بین قیمت بسته شدن کندل اول و قیمت باز شدن کندل دوم
                val refLo = min(a.c, b.o)
                pins.add(Pin(1, impH, impL, impBi, b.bi, refLo, 0))
                lg("ENGINE", "کاندید کندل مهم پایین‌ترین‌ها ثبت شد",
                    "kind=BU hi=${f2(impH)} lo=${f2(impL)} ref=${f2(refLo)} bar=${b.bi}")
            }
            if (a.c > a.o && b.c < b.o) {                       // کندل مهم در بالاترین‌ها
                val (impH2, impL2, impBi2) = pickHigh(a, b)
                // §4-1 سند نهایی: سطح ابطال بالاترین کندل‌ها = بیشترین قیمت بین قیمت بسته شدن کندل اول و قیمت باز شدن کندل دوم
                val refHi = max(a.c, b.o)
                pins.add(Pin(-1, impH2, impL2, impBi2, b.bi, refHi, 0))
                lg("ENGINE", "کاندید کندل مهم بالاترین‌ها ثبت شد",
                    "kind=BE hi=${f2(impH2)} lo=${f2(impL2)} ref=${f2(refHi)} bar=${b.bi}")
            }
        }

        // ── چرخهٔ عمر کاندیدها : ابطال / Ready ──
        // §4-1 سند نهایی: مرگ کاندید با «باز یا کلوز» آن‌طرف ref، مقایسه اکید
        // (تساوی نمی‌کُشد). Ready فقط با کلوز بالای سقف / زیر کف.
        for (p in pins) {
            if (p.status == 0 && sb.bi > p.startBi) {
                val dead = if (p.kind == 1) (sb.o < p.ref || sb.c < p.ref)
                          else          (sb.o > p.ref || sb.c > p.ref)
                if (dead) p.status = -1
                else {
                    val rdy = if (p.kind == 1) sb.c > p.hi else sb.c < p.lo
                    if (rdy) p.status = 1
                }
            }
        }

        // ── انتخاب Ready با تناوب سخت BU↔BE ──
        var readyBuNow = false
        var readyBeNow = false
        if (pins.isNotEmpty()) {
            if (expectedReady == 0 || expectedReady == 1) {
                val iBU = bestReady(pins, 1)
                if (iBU >= 0) {
                    val pBU = pins[iBU]
                    run {
                        readyBuNow = true
                        lastBUpin = pBU.lo
                        lastBUpinRef = pBU.ref; lastBUpinHi = pBU.hi
                        buActive = true; buSeq = ++pinSeq
                        lowestBUpin = if (lowestBUpin.isNaN()) pBU.lo else min(lowestBUpin, pBU.lo)
                        addMarker(pBU.impBi, pBU.lo, 1, "کندل مهم پایین‌ترین\nReady BU · pinBU")
                        lg("ENGINE", "Ready BU (pinBU) تایید شد", "lo=${f2(pBU.lo)} hi=${f2(pBU.hi)} bar=${sb.bi}")
                        alarm(AlertKind.PIN, "کندل مهم · Ready BU",
                            "کف ${f2(pBU.lo)} — کندل مهم پایین‌ترین‌ها تایید شد (${Tf.label(cfg.tfS)})")
                        expectedReady = -1
                        pins.removeAt(iBU)
                        if (trend == 0 && beActive && !lastBEpin.isNaN()) {
                            tempBoxTop = lastBEpin; tempBoxBot = lastBUpin
                        } else if (trend == -1 && !lastBEpin.isNaN() && LL.isNaN()) {
                            LL = lastBUpin; LH = lastBEpin
                            lhPinHi = lastBEpin; lhPinRef = lastBEpinRef; lhPinLo = lastBEpinLo; lhPinSeq = beSeq
                            llPinLo = lastBUpin; llPinRef = lastBUpinRef; llPinHi = lastBUpinHi; llPinSeq = buSeq
                        }
                    }
                }
            }
            if ((expectedReady == 0 || expectedReady == -1) && pins.isNotEmpty()) {
                val iBE = bestReady(pins, -1)
                if (iBE >= 0) {
                    val pBE = pins[iBE]
                    run {
                        readyBeNow = true
                        lastBEpin = pBE.hi
                        lastBEpinRef = pBE.ref; lastBEpinLo = pBE.lo
                        beActive = true; beSeq = ++pinSeq
                        highestBE = if (highestBE.isNaN()) pBE.hi else max(highestBE, pBE.hi)
                        addMarker(pBE.impBi, pBE.hi, -1, "کندل مهم بالاترین\nReady BE · pinBE")
                        lg("ENGINE", "Ready BE (pinBE) تایید شد", "hi=${f2(pBE.hi)} lo=${f2(pBE.lo)} bar=${sb.bi}")
                        alarm(AlertKind.PIN, "کندل مهم · Ready BE",
                            "سقف ${f2(pBE.hi)} — کندل مهم بالاترین‌ها تایید شد (${Tf.label(cfg.tfS)})")
                        expectedReady = 1
                        pins.removeAt(iBE)
                        if (trend == 0 && buActive && !lastBUpin.isNaN()) {
                            tempBoxTop = lastBEpin; tempBoxBot = lastBUpin
                        } else if (trend == 1 && !lastBUpin.isNaN() && HH.isNaN()) {
                            HH = lastBEpin; HL = lastBUpin
                            hlPinLo = lastBUpin; hlPinRef = lastBUpinRef; hlPinHi = lastBUpinHi; hlPinSeq = buSeq
                            hhPinHi = lastBEpin; hhPinRef = lastBEpinRef; hhPinLo = lastBEpinLo; hhPinSeq = beSeq
                        }
                    }
                }
            }
        }

        // کاندیدِ Ready که تناوب اجازهٔ ثبتش را نداد هرگز ثبت نمی‌شود؛ کاندیدهای مرده هم دور ریخته می‌شوند.
        // کاندیدهای هنوز تاییدنشده (status=0) می‌مانند.
        pins.removeAll { it.status != 0 }
        while (pins.size > 400) pins.removeAt(0)

        // ── تعیین / ادامه / تغییر روند و ابطال پین‌ها (§4-1 سند نهایی) ──
        var evTrendUp = false; var evTrendDn = false
        var evChgUp = false; var evChgDn = false
        when (trend) {
            0 -> {
                if (!tempBoxTop.isNaN() && !tempBoxBot.isNaN()) {
                    if (sb.c > tempBoxTop) {
                        val top = tempBoxTop; val bot = tempBoxBot
                        trend = 1; evTrendUp = true
                        highestBE = top; lowestBUpin = bot
                        tempBoxTop = Double.NaN; tempBoxBot = Double.NaN
                        HH = Double.NaN; HL = Double.NaN
                        // در شروع روند صعودی، pinBU پایهٔ HL بعدی می‌ماند و منتظر اولین pinBE (HH) هستیم
                        beActive = false
                        buActive = true
                        if (buSeq < beSeq) buSeq = ++pinSeq
                        expectedReady = -1
                        lg("ENGINE", "روند صعودی تثبیت شد (توالی)",
                            "tempBoxTop=${f2(top)} tempBoxBot=${f2(bot)} close=${f2(sb.c)}")
                        alarm(AlertKind.TREND, "روند صعودی شد",
                            "توالی کامل · کلوز ${f2(sb.c)} بالای سقف tempbox ${f2(top)}")
                    } else if (sb.c < tempBoxBot) {
                        val top = tempBoxTop; val bot = tempBoxBot
                        trend = -1; evTrendDn = true
                        highestBE = top; lowestBUpin = bot
                        tempBoxTop = Double.NaN; tempBoxBot = Double.NaN
                        LL = Double.NaN; LH = Double.NaN
                        // در شروع روند نزولی، pinBE پایهٔ LH بعدی می‌ماند و منتظر اولین pinBU (LL) هستیم
                        buActive = false
                        beActive = true
                        if (beSeq < buSeq) beSeq = ++pinSeq
                        expectedReady = 1
                        lg("ENGINE", "روند نزولی تثبیت شد (توالی)",
                            "tempBoxBot=${f2(bot)} tempBoxTop=${f2(top)} close=${f2(sb.c)}")
                        alarm(AlertKind.TREND, "روند نزولی شد",
                            "توالی کامل · کلوز ${f2(sb.c)} زیر کف tempbox ${f2(bot)}")
                    }
                }
                if (trend == 0) {
                    if (!readyBuNow && buActive && !lastBUpinRef.isNaN() && beSeq <= buSeq) {
                        val crossedRef = sb.o < lastBUpinRef || sb.c < lastBUpinRef
                        val crossedLow = !lastBUpin.isNaN() && (sb.o < lastBUpin || sb.c < lastBUpin)
                        if (crossedRef || crossedLow) {
                            val oldPin = lastBUpin; val oldRef = lastBUpinRef
                            clearBUpin()
                            tempBoxTop = Double.NaN; tempBoxBot = Double.NaN
                            expectedReady = if (beActive) 1 else 0
                            addMarker(sb.bi, oldPin, 10, "pinBU بی‌اعتبار شد")
                            lg("ENGINE", "pinBU بی‌اعتبار و حذف شد — پین کامل پاک شد",
                                "ref=${f2(oldRef)} lo=${f2(oldPin)} o=${f2(sb.o)} c=${f2(sb.c)}")
                        }
                    }
                    if (!readyBeNow && beActive && !lastBEpinRef.isNaN() && buSeq <= beSeq) {
                        val crossedRef = sb.o > lastBEpinRef || sb.c > lastBEpinRef
                        val crossedHigh = !lastBEpin.isNaN() && (sb.o > lastBEpin || sb.c > lastBEpin)
                        if (crossedRef || crossedHigh) {
                            val oldPin = lastBEpin; val oldRef = lastBEpinRef
                            clearBEpin()
                            tempBoxTop = Double.NaN; tempBoxBot = Double.NaN
                            expectedReady = if (buActive) -1 else 0
                            addMarker(sb.bi, oldPin, 10, "pinBE بی‌اعتبار شد")
                            lg("ENGINE", "pinBE بی‌اعتبار و حذف شد — پین کامل پاک شد",
                                "ref=${f2(oldRef)} hi=${f2(oldPin)} o=${f2(sb.o)} c=${f2(sb.c)}")
                        }
                    }
                }
            }
            1 -> {
                // ۱) چرخش روند از صعودی به نزولی: بسته شدن حتی یک تیک زیر HL (شامل تبصرهٔ هم‌زمانی ReadyBE و شکست HL)
                if (!HL.isNaN() && sb.c < HL) {
                    val oldHL = HL; val oldHH = HH
                    trend = -1; evChgDn = true
                    highestBE = oldHH; lowestBUpin = oldHL
                    HH = Double.NaN; HL = Double.NaN; LL = Double.NaN; LH = Double.NaN
                    clearBUpin()
                    if (!hhPinHi.isNaN()) {
                        lastBEpin = hhPinHi; lastBEpinRef = hhPinRef; lastBEpinLo = hhPinLo
                        beActive = true; beSeq = ++pinSeq
                    }
                    expectedReady = 1
                    lg("ENGINE", "چرخش روند به نزولی (کلوز زیر HL · توالی کامل)",
                        "HL=${f2(oldHL)} HH=${f2(oldHH)} close=${f2(sb.c)}")
                    alarm(AlertKind.TREND, "چرخش روند به نزولی",
                        "کلوز ${f2(sb.c)} زیر کف HL ${f2(oldHL)} بسته شد")
                } else {
                    // ۲) ادامهٔ روند صعودی: بسته شدن بالای HH ⇒ برگشت HH به pinBE و HL به pinBU
                    if (!HH.isNaN() && sb.c > HH) {
                        if (buSeq < beSeq) {
                            clearBEpin()
                            HH = Double.NaN; HL = Double.NaN
                            if (!hlPinLo.isNaN()) {
                                lastBUpin = hlPinLo; lastBUpinRef = hlPinRef; lastBUpinHi = hlPinHi
                                buActive = true; buSeq = hlPinSeq
                            }
                            expectedReady = -1
                        } else {
                            HH = Double.NaN; HL = Double.NaN
                            expectedReady = -1
                        }
                    }
                    // ۳) ابطال pinBE بدون ثبت pinBU بعد از آن: HH و HL پاک می‌شوند و HL به pinBU برمی‌گردد
                    if (!readyBeNow && beActive && !lastBEpinRef.isNaN() && buSeq <= beSeq) {
                        val crossedRef = sb.o > lastBEpinRef || sb.c > lastBEpinRef
                        val crossedHigh = !lastBEpin.isNaN() && (sb.o > lastBEpin || sb.c > lastBEpin)
                        if (crossedRef || crossedHigh) {
                            val oldPin = lastBEpin; val oldRef = lastBEpinRef
                            // فقط اگر pinBE باطل‌شده خودِ HH باشد، HH/HL پاک می‌شوند (سند §روند)
                            val wasHH = !HH.isNaN() && hhPinSeq == beSeq
                            clearBEpin()
                            if (wasHH) {
                                HH = Double.NaN; HL = Double.NaN
                                if (!hlPinLo.isNaN()) {
                                    lastBUpin = hlPinLo; lastBUpinRef = hlPinRef; lastBUpinHi = hlPinHi
                                    buActive = true; buSeq = hlPinSeq
                                }
                            }
                            expectedReady = if (buActive) -1 else 0
                            addMarker(sb.bi, oldPin, 10, "pinBE بی‌اعتبار شد")
                            lg("ENGINE", "pinBE بی‌اعتبار و حذف شد — پین کامل پاک شد",
                                "ref=${f2(oldRef)} hi=${f2(oldPin)} o=${f2(sb.o)} c=${f2(sb.c)}")
                        }
                    }
                    // ۴) ابطال pinBU بدون ثبت pinBE بعد از آن
                    if (!readyBuNow && buActive && !lastBUpinRef.isNaN() && beSeq <= buSeq) {
                        val crossedRef = sb.o < lastBUpinRef || sb.c < lastBUpinRef
                        val crossedLow = !lastBUpin.isNaN() && (sb.o < lastBUpin || sb.c < lastBUpin)
                        val closedBelowPin = !lastBUpin.isNaN() && sb.c < lastBUpin
                        if (crossedRef || crossedLow) {
                            val oldPin = lastBUpin; val oldRef = lastBUpinRef
                            clearBUpin()
                            expectedReady = if (beActive) 1 else 0
                            addMarker(sb.bi, oldPin, 10, "pinBU بی‌اعتبار شد")
                            lg("ENGINE", "pinBU بی‌اعتبار و حذف شد — پین کامل پاک شد",
                                "ref=${f2(oldRef)} lo=${f2(oldPin)} o=${f2(sb.o)} c=${f2(sb.c)}")
                            if (closedBelowPin) {
                                lg("ENGINE", "کلوز زیر pinBU ولی توالی کامل نبود — فقط pinBU حذف شد، روند صعودی ماند",
                                    "pinBU=${f2(oldPin)} close=${f2(sb.c)} buSeq=$buSeq beSeq=$beSeq")
                            }
                        }
                    }
                }
            }
            -1 -> {
                // ۱) چرخش روند از نزولی به صعودی: بسته شدن حتی یک تیک بالای LH (شامل تبصرهٔ هم‌زمانی ReadyBU و شکست LH)
                if (!LH.isNaN() && sb.c > LH) {
                    val oldLH = LH; val oldLL = LL
                    trend = 1; evChgUp = true
                    highestBE = oldLH; lowestBUpin = oldLL
                    HH = Double.NaN; HL = Double.NaN; LL = Double.NaN; LH = Double.NaN
                    clearBEpin()
                    if (!llPinLo.isNaN()) {
                        lastBUpin = llPinLo; lastBUpinRef = llPinRef; lastBUpinHi = llPinHi
                        buActive = true; buSeq = ++pinSeq
                    }
                    expectedReady = -1
                    lg("ENGINE", "چرخش روند به صعودی (کلوز بالای LH · توالی کامل)",
                        "LH=${f2(oldLH)} LL=${f2(oldLL)} close=${f2(sb.c)}")
                    alarm(AlertKind.TREND, "چرخش روند به صعودی",
                        "کلوز ${f2(sb.c)} بالای سقف LH ${f2(oldLH)} بسته شد")
                } else {
                    // ۲) ادامهٔ روند نزولی: بسته شدن زیر LL ⇒ برگشت LL به pinBU و LH به pinBE
                    if (!LL.isNaN() && sb.c < LL) {
                        if (beSeq < buSeq) {
                            clearBUpin()
                            LL = Double.NaN; LH = Double.NaN
                            if (!lhPinHi.isNaN()) {
                                lastBEpin = lhPinHi; lastBEpinRef = lhPinRef; lastBEpinLo = lhPinLo
                                beActive = true; beSeq = lhPinSeq
                            }
                            expectedReady = 1
                        } else {
                            LL = Double.NaN; LH = Double.NaN
                            expectedReady = 1
                        }
                    }
                    // ۳) ابطال pinBU بدون ثبت pinBE بعد از آن: LL و LH پاک می‌شوند و LH به pinBE برمی‌گردد
                    if (!readyBuNow && buActive && !lastBUpinRef.isNaN() && beSeq <= buSeq) {
                        val crossedRef = sb.o < lastBUpinRef || sb.c < lastBUpinRef
                        val crossedLow = !lastBUpin.isNaN() && (sb.o < lastBUpin || sb.c < lastBUpin)
                        if (crossedRef || crossedLow) {
                            val oldPin = lastBUpin; val oldRef = lastBUpinRef
                            // فقط اگر pinBU باطل‌شده خودِ LL باشد، LL/LH پاک می‌شوند
                            val wasLL = !LL.isNaN() && llPinSeq == buSeq
                            clearBUpin()
                            if (wasLL) {
                                LL = Double.NaN; LH = Double.NaN
                                if (!lhPinHi.isNaN()) {
                                    lastBEpin = lhPinHi; lastBEpinRef = lhPinRef; lastBEpinLo = lhPinLo
                                    beActive = true; beSeq = lhPinSeq
                                }
                            }
                            expectedReady = if (beActive) 1 else 0
                            addMarker(sb.bi, oldPin, 10, "pinBU بی‌اعتبار شد")
                            lg("ENGINE", "pinBU بی‌اعتبار و حذف شد — پین کامل پاک شد",
                                "ref=${f2(oldRef)} lo=${f2(oldPin)} o=${f2(sb.o)} c=${f2(sb.c)}")
                        }
                    }
                    // ۴) ابطال pinBE بدون ثبت pinBU بعد از آن
                    if (!readyBeNow && beActive && !lastBEpinRef.isNaN() && buSeq <= beSeq) {
                        val crossedRef = sb.o > lastBEpinRef || sb.c > lastBEpinRef
                        val crossedHigh = !lastBEpin.isNaN() && (sb.o > lastBEpin || sb.c > lastBEpin)
                        val closedAbovePin = !lastBEpin.isNaN() && sb.c > lastBEpin
                        if (crossedRef || crossedHigh) {
                            val oldPin = lastBEpin; val oldRef = lastBEpinRef
                            clearBEpin()
                            expectedReady = if (buActive) -1 else 0
                            addMarker(sb.bi, oldPin, 10, "pinBE بی‌اعتبار شد")
                            lg("ENGINE", "pinBE بی‌اعتبار و حذف شد — پین کامل پاک شد",
                                "ref=${f2(oldRef)} hi=${f2(oldPin)} o=${f2(sb.o)} c=${f2(sb.c)}")
                            if (closedAbovePin) {
                                lg("ENGINE", "کلوز بالای pinBE ولی توالی کامل نبود — فقط pinBE حذف شد، روند نزولی ماند",
                                    "pinBE=${f2(oldPin)} close=${f2(sb.c)} buSeq=$buSeq beSeq=$beSeq")
                            }
                        }
                    }
                }
            }
        }

        if (evTrendUp || evTrendDn || evChgUp || evChgDn) {
            markers.removeAll { it.text.startsWith("روند فعلی:") }
        }
        if (evTrendUp) addMarkerAtTime(curT, sb.bi, sb.c, MarkerKind.TREND_UP, "شروع روند صعودی ▲")
        if (evTrendDn) addMarkerAtTime(curT, sb.bi, sb.c, MarkerKind.TREND_DOWN, "شروع روند نزولی ▼")
        if (evChgUp) addMarkerAtTime(curT, sb.bi, sb.c, MarkerKind.TREND_UP, "چرخش روند: نزولی ← صعودی ▲")
        if (evChgDn) addMarkerAtTime(curT, sb.bi, sb.c, MarkerKind.TREND_DOWN, "چرخش روند: صعودی ← نزولی ▼")

        if (evChgDn || evTrendDn) cancelDir(1)
        if (evChgUp || evTrendUp) cancelDir(-1)

        // در پیش‌بارگذاری تاریخچهٔ ساختار، دیتای تریگر۲ نداریم؛ بنابراین فقط
        // توالی/روند ثبت می‌شود و هیچ باکس پروفایل حدسی ساخته نمی‌شود.
        if (!buildZones) return

        // ── پروفایل حجم فیکس‌رنج + ثبت ناحیه‌ها ──
        val bullC = sb.c > sb.o
        val bearC = sb.c < sb.o
        val dirOK = (trend == 1 && bullC) || (trend == -1 && bearC)
        val prof = VolumeProfile.build(microS, cfg.vpRows, cfg.vpVA, cfg.distMode, cfg.vpSmooth, cfg.mintick)
        profileOnLastS = prof
        lastProfBars = microS.size
        if (cfg.showPocVA && !prof.poc.isNaN()) lastPocPx = prof.poc
        if (dirOK && !prof.step.isNaN() && prof.step > 0) {
            val rej = VolumeProfile.zones(prof, cfg.vpRows, bullC, sb.c, cfg.minZoneRows, wantRejected = true)
            cnt.zoneRejected += rej.size
            if (cfg.showRejectedZones) for (rz in rej) {
                addZoneBox(trend, rz, sb, ZoneStatus.REJECTED)
                lg("ZONE", "ناحیهٔ ردشده (کلوز داخل محدوده بود)",
                    "dir=${if (bullC) "BULL" else "BEAR"} top=${f2(rz.top)} bot=${f2(rz.bot)} rows=${rz.loIdx}-${rz.hiIdx} bar=${sb.bi}")
            }
            val zs = VolumeProfile.zones(prof, cfg.vpRows, bullC, sb.c, cfg.minZoneRows)
            for (z in zs) {
                if (z.top > z.bot) {
                    addZoneBox(trend, z, sb, ZoneStatus.ACTIVE)
                    lastZoneTop = z.top; lastZoneBot = z.bot
                    cnt.setup++
                    val st = Setup(setupSeq++, trend, 0, z.top, z.bot, z.idx, sb.bi)
                    st.zT = sb.t
                    st.note = "ثبت شد"
                    setups.add(st)
                    lg("ZONE", "ناحیهٔ فیکس‌رنج ثبت شد · Z${z.idx}",
                        "dir=${if (bullC) "BULL" else "BEAR"} top=${f2(z.top)} bot=${f2(z.bot)} rows=${z.loIdx}-${z.hiIdx} bar=${sb.bi} setup=${st.id}")
                    alarm(AlertKind.ZONE_NEW, "ناحیهٔ فیکس‌رنج Z${z.idx} ساخته شد",
                        "${if (bullC) "صعودی" else "نزولی"} · ${f2(z.bot)} تا ${f2(z.top)} · ستاپ #${st.id}")
                }
            }
            pruneSetups()
            trimZones()
        }
    }

    private fun pruneSetups() {
        //  ⚠ ستاپ‌های در حال معامله (stage=7) هرگز حذف نمی‌شوند — §۷-۲ و قانون
        //  «معاملهٔ باز هرگز به‌زور بسته نمی‌شود». اول ستاپ‌های پایان‌یافته (stage=8)،
        //  سپس ستاپ‌های دست‌نخورده (stage=0) و مراحل اولیه حذف می‌شوند تا ستاپ‌های دارای
        //  محدودهٔ فعال تریگر ۱ و ۲ محفوظ بمانند.
        while (setups.size > cfg.maxSetups) {
            var pick = setups.indexOfFirst { it.stage == 8 }
            if (pick < 0) pick = setups.indexOfFirst { it.stage == 0 }
            if (pick < 0) pick = setups.indexOfFirst { it.stage in 1..3 }
            if (pick < 0) pick = setups.indexOfFirst { it.stage in 4..5 }
            if (pick < 0) pick = setups.indexOfFirst { it.stage == 6 }
            if (pick < 0) break   // همه ستاپ‌ها در معامله‌اند — بیش از این حذف نمی‌کنیم
            val s = setups.removeAt(pick)
            if (s.stage < 7) {
                closeT1Box(s, ZoneStatus.CAP, curBi, curT)
                closeT2Box(s, ZoneStatus.CAP, curBi, curT)
                events.add("[${s.id}] پروندهٔ ستاپ بسته شد (سقف ستاپ‌های هم‌زمان)")
                broker?.onSetupInvalidated(s, curT, curBi)
            }
        }
    }

    private fun closeT1Box(s: Setup, status: Int, endBi: Int = curBi, endT: Long = curT) {
        if (s.lvZoneId >= 0) {
            t1Zones.firstOrNull { it.id == s.lvZoneId && it.status == ZoneStatus.ACTIVE }?.let {
                it.status = status; it.endBi = endBi; it.endT = endT
            }
            s.lvZoneId = -1L
        }
    }

    private fun closeT2Box(s: Setup, status: Int, endBi: Int = curBi, endT: Long = curT) {
        if (s.hvZoneId >= 0) {
            t2Zones.firstOrNull { it.id == s.hvZoneId && it.status == ZoneStatus.ACTIVE }?.let {
                it.status = status; it.endBi = endBi; it.endT = endT
            }
            s.hvZoneId = -1L
        }
    }

    private fun trimZones() {
        // مسیر سریع: اگر کل باکس‌ها هم از سقف کمترند، قطعاً باکس نمایان بیشتری وجود ندارد
        if (zones.size <= cfg.maxZoneBoxes) return
        // قدیمی‌ترین باکس نمایان (فعال/ردشده) تا وقتی که تعداد نمایان‌ها از سقف بگذرد حذف می‌شود
        while (zones.count { it.status == ZoneStatus.ACTIVE || it.status == ZoneStatus.REJECTED } > cfg.maxZoneBoxes) {
            val victim = zones.firstOrNull { it.status == ZoneStatus.ACTIVE || it.status == ZoneStatus.REJECTED } ?: break
            victim.status = ZoneStatus.CAP
        }
    }

    private fun addZoneBox(dir: Int, z: Zone, sb: Candle, status: Int) {
        zones.add(ZoneBox(zoneSeq++, dir, z.idx, z.top, z.bot, sb.bi, sb.t, z.loIdx, z.hiIdx, sb.bi, sb.t, status))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ⑪ موتور میانی (تایید میانی · کندل مارک‌شده · ابطال)
    // ═══════════════════════════════════════════════════════════════════════════
    private fun middleEngine(mc: Candle) {
        for (s in setups) {
            if (s.stage < 1 || s.stage >= 7) continue
            if (s.midRef.isNaN() && (s.touchMidStart <= 0L || mc.t >= s.touchMidStart)) {
                // ✔ تایید میانی
                s.midRef = if (s.dir == 1) mc.l else mc.h
                s.midBi = mc.bi
                s.midT = mc.t
                s.midConfirmBi = curBi
                s.markBi = -1; s.markH = Double.NaN; s.markL = Double.NaN
                s.stage = 2
                cnt.mid++
                s.lvReEntered = false
                lg("ENGINE", "تایید میانی انجام شد · ستاپ #${s.id}",
                    if (s.dir == 1) "کف تایید=${f2(s.midRef)} bar=${mc.bi}" else "سقف تایید=${f2(s.midRef)} bar=${mc.bi}")
                addMarker(mc.bi, s.midRef, 3, if (s.dir == 1) "کف تایید میانی" else "سقف تایید میانی")
            } else if (!s.midRef.isNaN()) {
                // ✖ ابطال تایید میانی — قبل از مارک چک می‌شود، چون کندل مارک هم
                //   می‌تواند (حتی در همان کندل پس از تایید، در پاس‌های بعدی) سطح را بشکند.
                //   سند ۶-۱۲: هر عبور شمع، حتی یک تیک، تایید را باطل می‌کند — شامل کندل بعد
                //   از تایید هم می‌شود (قبلاً این کندل از بررسی ابطال معاف بود).
                val broken = if (cfg.midInvalidClose) {
                    if (s.dir == 1) mc.c < s.midRef else mc.c > s.midRef
                } else {
                    if (s.dir == 1) mc.l < s.midRef else mc.h > s.midRef
                }
                if (broken) {
                    val old = s.midRef
                    //  ابطال میانی در هر مرحله‌ای (۱..۶) تایید را باطل می‌کند؛
                    //  اگر ستاپ قبلاً مسلح شده بود (stage=6)، سفارش معلق هم لغو می‌شود —
                    //  در غیر این‌صورت سفارش یتیم می‌ماند و ممکن است در کندل بعد پر شود
                    //  در حالی که موتور دیگر آن ستاپ را نمی‌شناسد.
                    if (s.stage == 6) broker?.onSetupInvalidated(s, curT, curBi)
                    closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                    closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
                    s.midRef = Double.NaN; s.midBi = -1; s.midT = 0L; s.markBi = -1
                    s.touchMidStart = aggM.cur?.t ?: mc.t
                    s.stage = 1
                    s.lvBi = -1; s.lvT = 0L; s.lvConfBi = -1; s.lvConfT = 0L; s.lvH = Double.NaN; s.lvL = Double.NaN
                    s.hvBi = -1; s.hvT = 0L; s.hvConfBi = -1; s.hvConfT = 0L; s.hvScanBi = -1; s.runMax = Double.NaN
                    s.lvTouched = false; s.lvUsed = false; s.lvReEntered = false
                    s.orderId = -1
                    addMarker(mc.bi, old, 10, "تایید میانی باطل شد")
                    lg("ENGINE", "تایید میانی باطل شد · ستاپ #${s.id}", "سطح قبلی=${f2(old)} bar=${mc.bi}")
                } else if (s.markBi < 0) {
                    // ◆ مارک کردن کندل بعد از تایید (فقط نشانه) — فقط اگر ابطال اتفاق نیفتاده باشد
                    s.markH = mc.h; s.markL = mc.l; s.markBi = mc.bi
                    if (s.stage == 2) s.stage = 3
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ⑫ موتور تریگر ۱ · ناحیهٔ ولوم کم (یک‌بارمصرف)
    // ═══════════════════════════════════════════════════════════════════════════
    private fun trigger1Engine(q1: Candle) {
        for (s in setups) {
            if (s.stage < 2 || s.stage > 7 || s.midRef.isNaN() || q1.bi <= s.midConfirmBi) continue
            val bullL = s.dir == 1

            // هنگام معامله هم چرخهٔ یک‌بارمصرف تریگر۱ را دنبال می‌کنیم، فقط برای
            // جلوگیری از مسلح‌مجدد اگر باکس در این فاصله مصرف شود یا نقض شود؛ معامله را دست نمی‌زنیم.
            if (s.stage == 7) {
                if (s.lvBi >= 0 && !s.lvUsed) {
                    val lvViol = s.lvConfBi >= 0 && (if (bullL) q1.l < s.lvL else q1.h > s.lvH)
                    if (lvViol) {
                        s.lvUsed = true
                        s.rearmAllowed = false
                        closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                    } else {
                        val inside = q1.l <= s.lvH && q1.h >= s.lvL
                        if (inside && !s.lvTouched) {
                            s.lvTouched = true
                        } else if (s.lvTouched) {
                            val left = if (bullL) q1.c > s.lvH else q1.c < s.lvL
                            if (left) {
                                s.lvUsed = true
                                s.rearmAllowed = false
                                closeT1Box(s, ZoneStatus.CONSUMED, curBi, curT)
                                addMarker(q1.bi, if (bullL) s.lvH else s.lvL, 11, "باکس ولوم کم مصرف شد ✖")
                                lg("ZONE", "باکس تریگر۱ هنگام معامله مصرف شد؛ مسلح‌مجدد بسته شد · ستاپ #${s.id}",
                                    "box=${f2(s.lvL)}-${f2(s.lvH)} bar=${q1.bi}")
                            }
                        }
                    }
                }
                continue
            }

            var lvConfirmedNow = false

            // ── مارک / تایید کندل ولوم کم (فقط پیش از تایید اولیهٔ باکس) ──
            if (!s.lvUsed && s.lvConfBi < 0 && s.stage <= 4) {
                if (s.lvBi < 0) {
                    if (prevT1Vol.isNaN() || q1.v < prevT1Vol) {
                        s.lvBi = q1.bi; s.lvT = q1.t; s.lvH = q1.h; s.lvL = q1.l
                        addMarker(q1.bi, if (bullL) q1.l else q1.h, 4, "کندل ولوم کم (تریگر۱)")
                        lg("ENGINE", "کندل ولوم کم مارک شد · ستاپ #${s.id}",
                            "vol=${f2(q1.v)} < prev=${f2(prevT1Vol)} hi=${f2(q1.h)} lo=${f2(q1.l)} bar=${q1.bi}")
                    }
                } else {
                    val violL = if (bullL) q1.l < s.lvL else q1.h > s.lvH
                    val confL = if (bullL) q1.c > s.lvH else q1.c < s.lvL
                    if (violL) {
                        s.lvBi = -1; s.lvT = 0L; s.lvH = Double.NaN; s.lvL = Double.NaN
                    } else if (confL) {
                        s.lvConfBi = curBi; s.lvConfT = curT
                        lvConfirmedNow = true
                        s.stage = 4
                        cnt.lv++
                        s.lvTouched = false
                        s.lvReEntered = false
                        s.hvScanBi = -1
                        s.hvBi = -1; s.hvT = 0L
                        if (!cfg.hvScanFirstTouch) s.hvScanBi = curBi
                        closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                        val zb1 = ZoneBox(zoneSeq++, s.dir, 1, s.lvH, s.lvL, s.lvBi, s.lvT, 0, 0, curBi, curT, ZoneStatus.ACTIVE)
                        t1Zones.add(zb1)
                        s.lvZoneId = zb1.id
                        addMarker(q1.bi, if (bullL) s.lvH else s.lvL, 5, "ناحیهٔ ولوم کم ✔ (تریگر۱)")
                        lg("ENGINE", "ناحیهٔ ولوم کم تایید شد · ستاپ #${s.id}",
                            "box=${f2(s.lvL)}-${f2(s.lvH)} bar=${q1.bi} مرحله=${s.stage}")
                        alarm(AlertKind.LV, "ناحیهٔ ولوم کم تایید شد",
                            "ستاپ #${s.id} · باکس ${f2(s.lvL)} تا ${f2(s.lvH)} · منتظر کندل ولوم زیاد (تریگر ۲)")
                    }
                }
            }

            // ── ابطال باکس ولوم کم پس از تایید (§4-7 سند نهایی) ──
            if (!lvConfirmedNow && s.lvBi >= 0 && s.lvConfBi >= 0 && !s.lvUsed) {
                val lvViolAfter = if (bullL) q1.l < s.lvL else q1.h > s.lvH
                if (lvViolAfter) {
                    if (s.stage in 4..5) {
                        closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                        closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
                        s.lvBi = -1; s.lvT = 0L; s.lvConfBi = -1; s.lvConfT = 0L; s.lvH = Double.NaN; s.lvL = Double.NaN
                        s.lvTouched = false; s.lvReEntered = false
                        s.hvBi = -1; s.hvT = 0L; s.hvConfBi = -1; s.hvConfT = 0L; s.hvScanBi = -1; s.runMax = Double.NaN
                        s.stage = 3
                        s.note = "نقض ناحیهٔ ولوم کم (تریگر۱)"
                        addMarker(q1.bi, if (bullL) q1.l else q1.h, 10, "ابطال ناحیهٔ ولوم کم")
                        lg("ENGINE", "ناحیهٔ ولوم کم با عبور از سمت مخالف باطل شد · ستاپ #${s.id}",
                            "bar=${q1.bi}")
                        continue
                    } else if (s.stage == 6) {
                        s.lvUsed = true
                        s.rearmAllowed = false
                        closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                    }
                }
            }

            // ── یک‌بارمصرف بودن باکس ولوم کم ──
            // کندل تاییدکننده خودش «برگشت» محسوب نمی‌شود؛ یک کندل تریگر۱ بعدتر لازم است.
            if (!lvConfirmedNow && s.lvBi >= 0 && !s.lvUsed && s.stage in 4..6) {
                val inside = q1.l <= s.lvH && q1.h >= s.lvL
                if (inside && !s.lvTouched) {
                    s.lvTouched = true
                    if (s.hvScanBi < 0 && cfg.hvScanFirstTouch) s.hvScanBi = q1.bi
                } else if (s.lvTouched) {
                    // سند: خروج فقط با کلوز آن‌طرف باکس سنجیده می‌شود؛ لازم نیست کل کندل بیرون باشد.
                    val left = if (bullL) q1.c > s.lvH else q1.c < s.lvL
                    if (left) {
                        s.lvUsed = true
                        closeT1Box(s, ZoneStatus.CONSUMED, curBi, curT)
                        addMarker(q1.bi, if (bullL) s.lvH else s.lvL, 11, "باکس ولوم کم مصرف شد ✖")
                        lg("ZONE", "باکس ولوم کم مصرف شد (یک‌بارمصرف) · ستاپ #${s.id}",
                            "box=${f2(s.lvL)}-${f2(s.lvH)} bar=${q1.bi}")
                        // §4-7 سند نهایی (تصمیم ۱٫۴٫۹):
                        //  - اگر ستاپ هنوز مسلح نشده (stage ۴/۵): ستاپ تمام می‌شود.
                        //  - اگر ستاپ مسلح شده (stage=6): سفارش معلق زنده می‌ماند،
                        //    باکس فقط یک‌بارمصرف است (دیگر برگشتی معتبر نیست).
                        if (s.stage in 4..5) {
                            s.stage = 8
                            s.note = "باکس ولوم کم مصرف شد (یک‌بار، قبل از مسلح)"
                            events.add("[${s.id}] باکس ولوم کم قبل از مسلح مصرف شد — ستاپ تمام")
                            closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
                            broker?.onSetupInvalidated(s, curT, curBi)
                        } else if (s.stage == 6) {
                            s.note = "باکس ولوم کم مصرف شد (سفارش معلق زنده ماند)"
                            events.add("[${s.id}] باکس ولوم کم بعد از مسلح مصرف شد — سفارش معلق معتبر ماند")
                            // سفارش معلق را لغو نمی‌کنیم.
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ⑬ موتور تریگر ۲ (چارت) : ناحیهٔ ساختار · ناحیهٔ ولوم زیاد · ورود · مدیریت
    // ═══════════════════════════════════════════════════════════════════════════
    private fun trigger2Engine(cd: Candle) {
        val open = cd.o; val low = cd.l; val high = cd.h; val close = cd.c; val volume = cd.v
        for (s in setups) {
            val bull = s.dir == 1

            // ④ انقضا / ورود بعد از مسلح‌شدن (§4-8 و §4-9 سند نهایی):
            //    اگر سفارش در کندل قبلی مسلح شده باشد (curBi > s.hvConfBi)، پیش از بررسی شکست
            //    میانی/ساختار در این کندل، بررسی می‌کنیم که آیا در ابتدای کندل منقضی شده یا با
            //    رسیدن قیمت به لبهٔ نزدیکِ HV سفارش پر شده است (چون لبهٔ ورود بالاتر از میانی/ساختار
            //    در خرید و پایین‌تر از آن‌ها در فروش است و قیمت اول سفارش را پر می‌کند).
            if (s.stage == 6 && curBi > s.hvConfBi) {
                if (s.priceExpiryRef.isNaN()) {
                    s.priceExpiryRef = if (bull) lastBEpin else lastBUpin
                }
                val openBreak = !s.priceExpiryRef.isNaN() &&
                        (if (bull) open > s.priceExpiryRef else open < s.priceExpiryRef)
                val closeBreak = priceExpiryCrossed(bull, open, close, s.priceExpiryRef)
                val nBars = maxOf(1, cfg.tfS / maxOf(1, cfg.tf2))
                val barExpire = s.armBi >= 0 && (curBi - s.armBi >= nBars)
                val entryTouched = if (bull) low <= s.hvH else high >= s.hvL
                val hvViol = if (bull) low < s.hvL else high > s.hvH

                if (barExpire || openBreak || (!entryTouched && closeBreak)) {
                    val expReason = if (barExpire) {
                        "گذشت یک کندل ساختار کامل (${nBars} کندل تریگر۲)"
                    } else if (bull) {
                        "باز/کلوز از بالای pinBE هنگام مسلح‌شدن (${f2(s.priceExpiryRef)}) عبور کرد"
                    } else {
                        "باز/کلوز از زیر pinBU هنگام مسلح‌شدن (${f2(s.priceExpiryRef)}) عبور کرد"
                    }
                    closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
                    s.stage = if (s.lvUsed || barExpire) 8 else 4
                    if (s.stage == 8) closeT1Box(s, ZoneStatus.CONSUMED, curBi, curT)
                    s.note = "سفارش منقضی"
                    events.add("[${s.id}] سفارش منقضی شد — $expReason")
                    broker?.onSetupExpired(s, curT, curBi, expReason)
                    s.orderId = -1
                    s.hvBi = -1; s.hvT = 0L; s.hvConfBi = -1; s.hvConfT = 0L; s.runMax = volume
                    if (s.stage == 4) s.rearmAllowed = true
                    lg("ENGINE", "سفارش معلق منقضی شد · ستاپ #${s.id}",
                        "reason=$expReason stage=${s.stage} bar=$curBi")
                    if (s.stage == 8) continue
                } else if (entryTouched) {
                    s.entry = if (bull) s.hvH else s.hvL
                    s.stage = 7
                    s.entryBi = curBi   // کندل پرشدن — برای قاعده §۶-۲۱
                    s.entryT = curT
                    cnt.entry++
                    lastEntryPx = s.entry; lastSlPx = s.sl; lastTpPx = s.tpx
                    addMarker(curBi, s.entry, 8, (if (bull) "ورود خرید" else "ورود فروش") + " @ " + f2(s.entry))
                    lg("TRADE", "قیمت به ناحیهٔ ولوم زیاد برگشت (شرط ورود) · ستاپ #${s.id}",
                        "ورود=${f2(s.entry)} bar=$curBi")
                } else if (hvViol) {
                    closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
                    s.hvBi = -1; s.hvT = 0L; s.hvConfBi = -1; s.hvConfT = 0L; s.runMax = volume
                    s.stage = if (s.lvUsed) 8 else 4
                    if (s.stage == 8) closeT1Box(s, ZoneStatus.CONSUMED, curBi, curT)
                    broker?.onSetupInvalidated(s, curT, curBi)
                    s.orderId = -1
                    if (s.stage == 4) {
                        s.prevEntry = Double.NaN
                        s.rearmAllowed = false
                    }
                    lg("ENGINE", "ناحیهٔ HV نقض شد (یک تیک از سمت دور) → سفارش لغو و اسکن از نو · ستاپ #${s.id}",
                        "hvL=${f2(s.hvL)} hvH=${f2(s.hvH)} stage=${s.stage} bar=$curBi")
                    if (s.stage == 8) continue
                }
            }

            // §4-6: تأیید میانی باید روی هر کندل بستهٔ تریگر۲ بررسی شود؛
            // منتظر بسته‌شدن کندل میانی نمانیم، چون یک عبور کوتاه هم تأیید را باطل می‌کند.
            if (s.stage == 7 && s.rearmAllowed && rearmInvalidatedByBar(s, cd, cfg.midInvalidClose)) {
                s.rearmAllowed = false
                lg("ENGINE", "اعتبار ناحیهٔ ساختار یا تایید میانی هنگام معامله از بین رفت؛ مسلح‌مجدد بسته شد · ستاپ #${s.id}",
                    "zone=${f2(s.zBot)}-${f2(s.zTop)} midRef=${f2(s.midRef)} bar=$curBi")
            }
            if (s.stage in 2..6 && !s.midRef.isNaN() && curBi > s.midConfirmBi) {
                val brokenMid = if (cfg.midInvalidClose) {
                    if (bull) close < s.midRef else close > s.midRef
                } else {
                    if (bull) low < s.midRef else high > s.midRef
                }
                if (brokenMid) {
                    val old = s.midRef
                    if (s.stage == 6) broker?.onSetupInvalidated(s, curT, curBi)
                    closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                    closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
                    s.midRef = Double.NaN; s.midBi = -1; s.midT = 0L; s.markBi = -1
                    // سند §۴-۶: «کندل بعدی برای تایید میانی سنجیده می‌شود» — یعنی اولین کندل میانی بعد از کندلی که تیک شکست در آن بوده
                    s.touchMidStart = Tf.bucket(cfg.tfM, cd.t) + cfg.tfM * 1000L
                    s.stage = 1
                    s.lvBi = -1; s.lvT = 0L; s.lvConfBi = -1; s.lvConfT = 0L; s.lvH = Double.NaN; s.lvL = Double.NaN
                    s.hvBi = -1; s.hvT = 0L; s.hvConfBi = -1; s.hvConfT = 0L; s.hvScanBi = -1; s.runMax = Double.NaN
                    s.lvTouched = false; s.lvUsed = false; s.lvReEntered = false
                    s.orderId = -1
                    addMarker(curBi, old, 10, "تایید میانی باطل شد")
                    lg("ENGINE", "تایید میانی باطل شد · ستاپ #${s.id}",
                        "سطح قبلی=${f2(old)} bar=$curBi · تریگر۲")
                    continue
                }
            }

            // ① برخورد به ناحیهٔ فیکس‌رنج ساختار
            if (s.stage == 0 && !s.zGone && curBi > s.zBi) {
                if (low <= s.zTop && high >= s.zBot) {
                    s.stage = 1
                    cnt.touch++
                    s.touchBi = curBi
                    s.touchMidStart = aggM.cur?.t ?: cd.t
                    addMarker(curBi, if (bull) s.zBot else s.zTop, 2, "برخورد · منتظر بسته شدن کندل میانی")
                    lg("ENGINE", "برخورد قیمت با ناحیهٔ ساختار · ستاپ #${s.id}",
                        "zone=${f2(s.zBot)}-${f2(s.zTop)} bar=$curBi · منتظر تایید میانی (${Tf.label(cfg.tfM)})")
                }
            }

            // ② پاک شدن / لغو ناحیهٔ ساختار (کلوز از سمت دور)
            if (s.stage < 7 && !s.zGone && curBi > s.zBi) {
                val broken = if (bull) close < s.zBot else close > s.zTop
                if (broken) {
                    //  اگر سفارش معلق داشت (stage=6) آن را هم لغو کن
                    if (s.stage == 6) broker?.onSetupInvalidated(s, curT, curBi)
                    closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                    closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
                    s.zGone = true
                    s.stage = 8
                    s.note = "شکست ناحیهٔ ساختار (لغو)"
                    s.orderId = -1
                    events.add("[${s.id}] لغو شد — شکست ناحیهٔ ساختار")
                    addMarker(curBi, close, 10, "لغو · شکست ناحیهٔ ساختار")
                    lg("ZONE", "ناحیهٔ ساختار شکست (لغو ستاپ #${s.id})",
                        "zone=${f2(s.zBot)}-${f2(s.zTop)} close=${f2(close)} bar=$curBi")
                    alarm(AlertKind.ZONE_DEAD, "ناحیهٔ ساختار شکست",
                        "ستاپ #${s.id} لغو شد · کلوز ${f2(close)} خارج از ناحیهٔ ${f2(s.zBot)}-${f2(s.zTop)}")
                }
            }

            // ②-ب ابطال باکس ولوم کم پس از تایید با عبور ۱ تیک از سمت مخالف (§4-7 سند نهایی)
            if (!s.lvUsed && s.lvBi >= 0 && s.lvConfBi >= 0 && curBi > s.lvConfBi) {
                val lvViol = if (bull) low < s.lvL else high > s.lvH
                if (lvViol) {
                    if (s.stage in 4..5) {
                        closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                        closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
                        s.lvBi = -1; s.lvT = 0L; s.lvConfBi = -1; s.lvConfT = 0L; s.lvH = Double.NaN; s.lvL = Double.NaN
                        s.lvTouched = false; s.lvReEntered = false
                        s.hvBi = -1; s.hvT = 0L; s.hvConfBi = -1; s.hvConfT = 0L; s.hvScanBi = -1; s.runMax = Double.NaN
                        s.stage = 3
                        s.note = "نقض ناحیهٔ ولوم کم (تریگر۱)"
                        addMarker(curBi, if (bull) low else high, 10, "ابطال ناحیهٔ ولوم کم")
                        lg("ENGINE", "ناحیهٔ ولوم کم با عبور از سمت مخالف باطل شد · ستاپ #${s.id}",
                            "bar=$curBi")
                    } else if (s.stage in 6..7) {
                        s.lvUsed = true
                        s.rearmAllowed = false
                        closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                    }
                }
            }

            // ③-الف بازگشت قیمت به باکس ولوم کم ← شروع اسکن ولوم زیاد
            if (s.stage == 4 && !s.zGone && s.lvBi >= 0 && !s.lvReEntered) {
                if (low <= s.lvH && high >= s.lvL) {
                    s.lvReEntered = true
                    if (s.hvScanBi < 0) s.hvScanBi = curBi
                    s.runMax = volume
                    addMarker(curBi, if (bull) s.lvH else s.lvL, 5, "بازگشت به باکس ولوم کم · اسکن ولوم زیاد")
                    lg("ENGINE", "بازگشت به باکس ولوم کم → اسکن ناحیهٔ ولوم زیاد فعال شد · ستاپ #${s.id}",
                        "box=${f2(s.lvL)}-${f2(s.lvH)} bar=$curBi vol=${f2(volume)}")
                }
            }

            // ③-ب ناحیهٔ ولوم زیاد (تریگر ۲)
            // §4-8 سند نهایی: کندل تایید ناحیه پرحجم صرفاً باید بالای سقف (خرید) یا زیر کف (فروش)
            // کندل پرحجم بسته شود و شرط افزایش مجدد حجم روی کندل تایید وجود ندارد.
            if (s.stage in 4..5 && s.lvBi >= 0 && s.lvReEntered) {
                val scanOK = if (s.hvScanBi < 0) true else curBi >= s.hvScanBi
                if (scanOK) {
                    val marked = s.hvBi >= 0
                    val viol = marked && (if (bull) low < s.hvL else high > s.hvH)
                    val conf = marked && (if (bull) close > s.hvH else close < s.hvL)
                    if (viol) {
                        s.hvBi = -1; s.hvT = 0L; s.runMax = volume; s.stage = 4
                        lg("ENGINE", "کندل ولوم زیاد نقض شد → جست‌وجوی کندل جدید · ستاپ #${s.id}",
                            "hvH=${f2(s.hvH)} hvL=${f2(s.hvL)} bar=$curBi")
                    } else if (conf) {
                        armSetup(s, cd, bull)
                    } else {
                        val markNow = if (cfg.hvMarkFirstIncrease) {
                            s.hvBi < 0 && volume > (prevChartVol.takeIf { !it.isNaN() } ?: Double.NEGATIVE_INFINITY)
                        } else {
                            volume > (s.runMax.takeIf { !it.isNaN() } ?: Double.NEGATIVE_INFINITY)
                        }
                        if (markNow) {
                            s.hvBi = curBi; s.hvT = curT; s.hvH = high; s.hvL = low
                            s.runMax = volume; s.stage = 5
                            addMarker(curBi, if (bull) low else high, 6, "کندل ولوم زیاد (مارک)")
                            lg("ENGINE", "کندل ولوم زیاد مارک شد · ستاپ #${s.id}",
                                "vol=${f2(volume)} hi=${f2(high)} lo=${f2(low)} bar=$curBi مرحله=5")
                        }
                    }
                }
            }

            // ⑤ مدیریت معامله : حدضرر / سر‌به‌سر / حدسود (بررسی موتور)
            //   ترتیب محافظه‌کارانه، مطابق §۲-۹ و PaperBroker.manageBar:
            //   SL/TPX بررسی اول می‌شوند و در یک کندل پایان می‌دهند.
            //   TP1 و TP2 پله‌های میانی‌اند و در یک کندل می‌توانند هر دو لمس شوند
            //   (مثلاً اسپایک قوی) — قبلاً به‌خاطر else-if فقط TP1 ثبت می‌شد و TP2
            //   در همان کندل نادیده می‌ماند. حالا TP2 با if مستقل چک می‌شود.
            //   توجه: stage فقط در پایان واقعی (SL/TPX/BE) به ۸ می‌رود.
            if (s.stage == 7 && !s.sl.isNaN() && !s.tpx.isNaN()) {
                var done = false
                var px = Double.NaN
                var note = ""
                val entrySameBar = (s.entryBi == curBi)
                if (bull) {
                    if (low <= s.sl) { done = true; px = s.sl; note = if (s.be) "خروج سر‌به‌سر" else "حد ضرر" }
                    else if (high >= s.tpx) { done = true; px = s.tpx; note = "حد سود نهایی (1.272)" }
                    else {
                        // §۶-۲۱: ورود و TP1 در یک کندل → خروج سر‌به‌سر خالص (نتیجه صفر)
                        val sameBarNetZero = entrySameBar && !s.tp1.isNaN()
                                && high >= s.tp1 && low <= s.entry
                        if (sameBarNetZero) {
                            s.be = true; s.sl = s.entry
                            done = true; px = s.entry; note = "خروج سر‌به‌سر (TP1 و ورود یک کندل)"
                        } else {
                            if (!s.be && !s.tp1.isNaN() && high >= s.tp1) {
                                s.be = true; s.sl = s.entry
                                addMarker(curBi, s.entry, 8, "TP1 (38%) ✔ · حدضرر = سر‌به‌سر")
                                lg("TRADE", "TP1 (۳۸٪) لمس شد → حدضرر به سر‌به‌سر منتقل شد · ستاپ #${s.id}",
                                    "tp1=${f2(s.tp1)} bar=$curBi")
                                alarm(AlertKind.TP1, "TP1 (۳۸٪) لمس شد", "ستاپ #${s.id} · حدضرر به سر‌به‌سر منتقل شد · ${f2(s.tp1)}")
                            }
                            if (s.be && !s.tp2Hit && !s.tp2.isNaN() && high >= s.tp2) {
                                s.tp2Hit = true
                                addMarker(curBi, s.tp2, 8, "TP2 (50%) ✔")
                                lg("TRADE", "TP2 (۵۰٪) لمس شد · ستاپ #${s.id}", "tp2=${f2(s.tp2)} bar=$curBi")
                                alarm(AlertKind.TP2, "TP2 (۵۰٪) لمس شد", "ستاپ #${s.id} · قیمت ${f2(s.tp2)}")
                            }
                            if (s.be && low <= s.entry) { done = true; px = s.entry; note = "خروج سر‌به‌سر" }
                        }
                    }
                } else {
                    if (high >= s.sl) { done = true; px = s.sl; note = if (s.be) "خروج سر‌به‌سر" else "حد ضرر" }
                    else if (low <= s.tpx) { done = true; px = s.tpx; note = "حد سود نهایی (1.272)" }
                    else {
                        val sameBarNetZero = entrySameBar && !s.tp1.isNaN()
                                && low <= s.tp1 && high >= s.entry
                        if (sameBarNetZero) {
                            s.be = true; s.sl = s.entry
                            done = true; px = s.entry; note = "خروج سر‌به‌سر (TP1 و ورود یک کندل)"
                        } else {
                            if (!s.be && !s.tp1.isNaN() && low <= s.tp1) {
                                s.be = true; s.sl = s.entry
                                addMarker(curBi, s.entry, 8, "TP1 (38%) ✔ · حدضرر = سر‌به‌سر")
                                lg("TRADE", "TP1 (۳۸٪) لمس شد → حدضرر به سر‌به‌سر منتقل شد · ستاپ #${s.id}",
                                    "tp1=${f2(s.tp1)} bar=$curBi")
                                alarm(AlertKind.TP1, "TP1 (۳۸٪) لمس شد", "ستاپ #${s.id} · حدضرر به سر‌به‌سر منتقل شد · ${f2(s.tp1)}")
                            }
                            if (s.be && !s.tp2Hit && !s.tp2.isNaN() && low <= s.tp2) {
                                s.tp2Hit = true
                                addMarker(curBi, s.tp2, 8, "TP2 (50%) ✔")
                                lg("TRADE", "TP2 (۵۰٪) لمس شد · ستاپ #${s.id}", "tp2=${f2(s.tp2)} bar=$curBi")
                                alarm(AlertKind.TP2, "TP2 (۵۰٪) لمس شد", "ستاپ #${s.id} · قیمت ${f2(s.tp2)}")
                            }
                            if (s.be && high >= s.entry) { done = true; px = s.entry; note = "خروج سر‌به‌سر" }
                        }
                    }
                }
                if (done) {
                    lastResult = note
                    s.note = note
                    addMarker(curBi, px, 9, note + " @ " + f2(px))
                    lg("TRADE", "پایان معامله · ستاپ #${s.id} → $note",
                        "خروج=${f2(px)} bar=$curBi")
                    closeT2Box(s, ZoneStatus.CONSUMED, curBi, curT)
                    // §4-8: پس از SL/سر‌به‌سر (نه TPX)، فقط اگر ساختار، میانی و تریگر۱
                    // هنوز معتبر باشند، همان ستاپ برای HV جدید می‌ماند؛ معاملهٔ باز در صورت نقض بسته نمی‌شود.
                    val finalWin = note.contains("1.272")
                    if (!finalWin && s.rearmAllowed && rearmPrerequisitesValid(s)) {
                        s.stage = 4
                        s.hvBi = -1; s.hvT = 0L; s.hvConfBi = -1; s.hvConfT = 0L; s.runMax = volume
                        // lvReEntered حفظ می‌شود: بازگشت اول تریگر۱ قبلاً رخ داده؛ برگشت دوم مجاز نیست.
                        s.be = false; s.tp2Hit = false
                        s.orderId = -1
                        lg("ENGINE", "معامله بسته شد؛ مسلح‌مجدد مجاز ماند (پیش‌نیازهای ستاپ برقرارند) · ستاپ #${s.id}",
                            "reason=$note bar=$curBi")
                    } else {
                        closeT1Box(s, ZoneStatus.CONSUMED, curBi, curT)
                        s.stage = 8
                        s.rearmAllowed = false
                        cnt.done++
                    }
                }
            }
        }
        // وضعیت کلی برای پنل
        stageFa = setups.minByOrNull { it.stage }?.let { stageFa(it.stage) } ?: "-"
    }

    /** لحظهٔ مسلح شدن (تایید ناحیهٔ ولوم زیاد) → محاسبهٔ SL/TP و ثبت سفارش ورود. */
    private fun armSetup(s: Setup, cd: Candle, bull: Boolean) {
        val eRef = if (bull) s.hvH else s.hvL
        s.sl = if (bull) s.hvL - cfg.slBufTicks * cfg.mintick else s.hvH + cfg.slBufTicks * cfg.mintick
        val a0 = if (bull) highestBE else lowestBUpin
        val a1 = s.midRef
        val targets = calculateTargetLevels(bull, eRef, a0, a1, cfg.minTPunits)
        s.tp1 = targets.tp1
        s.tp2 = targets.tp2
        s.tpx = targets.tpx
        // §4-8: پنجرهٔ مسلح‌مجدد پس از گذشت یک بازهٔ ساختار کامل بسته می‌شود؛
        // ورود جدید نیز باید بهتر از قبلی باشد (خرید پایین‌تر، فروش بالاتر).
        val isRearm = !s.prevEntry.isNaN()
        if (isRearm) {
            if (!s.rearmAllowed || !rearmPrerequisitesValid(s)) {
                s.hvBi = -1; s.hvT = 0L; s.runMax = Double.NaN
                lg("ENGINE", "مسلح‌مجدد ستاپ #${s.id} رد شد (پیش‌نیازهای ساختار/میانی/تریگر۱ برقرار نیست)",
                    "rearmAllowed=${s.rearmAllowed} zGone=${s.zGone} midValid=${!s.midRef.isNaN()} lvBi=${s.lvBi} lvReEntered=${s.lvReEntered} lvUsed=${s.lvUsed} bar=$curBi")
                return
            }
            val better = if (bull) eRef < s.prevEntry else eRef > s.prevEntry
            if (!better) {
                s.hvBi = -1; s.hvT = 0L; s.runMax = Double.NaN
                lg("ENGINE", "مسلح مجدد برای ستاپ #${s.id} رد شد (ورود جدید بهتر از قبلی نیست)",
                    "prev=${f2(s.prevEntry)} new=${f2(eRef)} bar=$curBi")
                return
            }
        }
        // §4-8 و §10 سند نهایی: ما فقط مجاز به گذاشتن ۱ سفارش در چارت هستیم (حداکثر ۱ سفارش معلق یا ۱ معامله باز).
        val hasActiveSetupOrder = setups.any { it.id != s.id && (it.stage == 6 || it.stage == 7) }
        if (hasActiveSetupOrder) {
            s.hvBi = -1; s.hvT = 0L; s.runMax = Double.NaN
            lg("ENGINE", "مسلح‌شدن ستاپ #${s.id} رد شد — سفارش یا معاملهٔ فعال دیگری در چارت وجود دارد",
                "bar=$curBi")
            return
        }
        //  ⚠ فقط اگر کارگزار واقعاً سفارش را ثبت کرد (یا در حالت بدون کارگزار) به stage=6 می‌رویم.
        val registered = broker?.registerLimit(s, cd) ?: true
        if (!registered) {
            s.hvBi = -1; s.hvT = 0L; s.runMax = Double.NaN
            lg("ENGINE", "ثبت سفارش برای ستاپ #${s.id} رد شد (${broker?.lastError}) — کندل HV ریست شد",
                "bar=$curBi")
            return
        }
        s.armBi = curBi
        s.armT = curT
        s.armsSinceS++
        s.prevEntry = eRef
        // Pin-level expiration applies to the latest opposing pin as it stood when this order armed.
        s.priceExpiryRef = if (bull) lastBEpin else lastBUpin
        s.rearmAllowed = true
        s.stage = 6
        cnt.hv++
        s.hvConfBi = curBi
        s.hvConfT = curT
        closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
        val zb2 = ZoneBox(zoneSeq++, s.dir, 2, s.hvH, s.hvL, s.hvBi, s.hvT, 0, 0, curBi, curT, ZoneStatus.ACTIVE)
        t2Zones.add(zb2)
        s.hvZoneId = zb2.id
        s.entry = eRef
        s.be = false
        s.tp2Hit = false
        addMarker(curBi, eRef, 7, if (bull) "مسلح برای خرید (Armed)" else "مسلح برای فروش (Armed)")
        events.add("[${s.id}] مسلح شد · ورود ${f2(eRef)} · SL ${f2(s.sl)} · TP ${f2(s.tpx)}")
        lg("ENGINE", "ناحیهٔ ولوم زیاد تایید شد → ستاپ مسلح (Armed) · ستاپ #${s.id}",
            "dir=${if (bull) "BUY" else "SELL"} ورود=${f2(eRef)} SL=${f2(s.sl)} TP1=${f2(s.tp1)} TP2=${f2(s.tp2)} TP=${f2(s.tpx)} bar=$curBi")
        alarm(AlertKind.HV, "ناحیهٔ ولوم زیاد تایید شد",
            "ستاپ #${s.id} (" + (if (bull) "خرید" else "فروش") + ") · ورود ${f2(eRef)} · حدضرر ${f2(s.sl)} · حدسود ${f2(s.tpx)}")
    }

    // ── ابزارها ────────────────────────────────────────────────────────────────
    private fun bestReady(ps: List<Pin>, kind: Int): Int {
        var idx = -1
        var best = Double.NaN
        for (i in ps.indices) {
            val p = ps[i]
            if (p.status == 1 && p.kind == kind) {
                val lv = if (kind == 1) p.lo else p.hi
                if (best.isNaN() || (if (kind == 1) lv < best else lv > best)) { best = lv; idx = i }
            }
        }
        return idx
    }

    /**
     * انتخاب کندل مهم در «پایین‌ترین‌ها» و «بالاترین‌ها» — **مطابق §4-1 سند نهایی استراتژی**.
     *
     * پایین‌ترین‌ها: کمترین کف (`min(low(a, b))`)؛ در صورت تساوی کف، کمترین سقف (`min(high(a, b))`).
     * بالاترین‌ها: بیشترین سقف (`max(high(a, b))`)؛ در صورت تساوی سقف، کمترین کف (`min(low(a, b))`).
     */
    internal fun pickLow(a: Candle, b: Candle): Triple<Double, Double, Int> =
        if (a.l < b.l || (a.l == b.l && a.h < b.h)) Triple(a.h, a.l, a.bi) else Triple(b.h, b.l, b.bi)

    internal fun pickHigh(a: Candle, b: Candle): Triple<Double, Double, Int> =
        if (a.h > b.h || (a.h == b.h && a.l < b.l)) Triple(a.h, a.l, a.bi) else Triple(b.h, b.l, b.bi)

    /**
     * پاک‌کردن کامل pinBU بعد از ابطال/شکست بدون توالی (§4-1 سند نهایی).
     */
    private fun clearBUpin() {
        buActive = false
        buSeq = -1
        lastBUpin = Double.NaN
        lastBUpinRef = Double.NaN
        lastBUpinHi = Double.NaN
    }

    /** قرینهٔ کامل برای pinBE. */
    private fun clearBEpin() {
        beActive = false
        beSeq = -1
        lastBEpin = Double.NaN
        lastBEpinRef = Double.NaN
        lastBEpinLo = Double.NaN
    }

    private fun cancelDir(dir: Int) {
        val it = setups.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.dir == dir && s.stage < 7) {
                closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
                s.stage = 8
                s.note = "لغو (تغییر روند)"
                events.add("[${s.id}] لغو شد — تغییر روند")
                lg("ENGINE", "ستاپ #${s.id} به دلیل تغییر روند لغو شد", "dir=${if (dir == 1) "BUY" else "SELL"}")
                broker?.onSetupInvalidated(s, curT, curBi)
                it.remove()
            }
        }
        for (zb in t1Zones) if (zb.dir == dir && zb.status == ZoneStatus.ACTIVE) {
            zb.status = ZoneStatus.DELETED; zb.endBi = curBi; zb.endT = curT
        }
        for (zb in t2Zones) if (zb.dir == dir && zb.status == ZoneStatus.ACTIVE) {
            zb.status = ZoneStatus.DELETED; zb.endBi = curBi; zb.endT = curT
        }
    }

    private fun addMarker(bi: Int, price: Double, kind: Int, text: String) {
        val t = times[bi] ?: curT
        addMarkerAtTime(t, bi, price, kind, text)
    }

    /** علامت‌گذاری جهت روند فعلی هنگام بازسازی/بازیابیِ وضعیت قدیمی. */
    fun ensureCurrentTrendMarker(t: Long, price: Double, bi: Int) {
        if (trend == 0 || t <= 0L || price.isNaN()) return
        val kind = if (trend > 0) MarkerKind.TREND_UP else MarkerKind.TREND_DOWN
        markers.removeAll { it.text.startsWith("روند فعلی:") }
        val text = if (trend > 0) "روند فعلی: صعودی ▲" else "روند فعلی: نزولی ▼"
        addMarkerAtTime(t, bi, price, kind, text)
    }

    private fun addMarkerAtTime(t: Long, bi: Int, price: Double, kind: Int, text: String) {
        markers.add(Marker(t, bi, price, kind, text))
        // توالی پین‌ها و شروع/چرخش روند تاریخچه‌اند و نباید با سقف مارکرهای
        // جزئی حذف شوند؛ فقط مارکرهای رخدادهای پرتکرار قدیمی‌تر trim می‌شوند.
        while (markers.size > 2000) {
            val discard = markers.indexOfFirst {
                it.kind != MarkerKind.PIN_BU && it.kind != MarkerKind.PIN_BE &&
                    it.kind != MarkerKind.TREND_UP && it.kind != MarkerKind.TREND_DOWN
            }
            if (discard < 0) break
            markers.removeAt(discard)
        }
    }

    /** نگاشت ایندکس کندل → زمان (برای برچسب‌ها و ذخیره‌سازی). */
    private val times = HashMap<Int, Long>()

    fun stageFa(st: Int): String = when (st) {
        0 -> "منتظر برخورد به ناحیهٔ ساختار"
        1 -> "برخورد شد · منتظر بسته شدن کندل میانی"
        2 -> "تایید میانی ✔"
        3 -> "اسکن ناحیهٔ ولوم کم (تریگر۱)"
        4 -> "ولوم کم ✔ · اسکن ولوم زیاد (تریگر۲)"
        5 -> "کندل ولوم زیاد (تریگر۲) مارک شد"
        6 -> "مسلح (Armed) · منتظر برگشت به ناحیهٔ ولوم زیاد"
        7 -> "در معامله"
        else -> "پایان‌یافته"
    }

    companion object {
        fun fmtV(v: Double): String = if (v >= 1000) String.format("%.1fk", v / 1000) else String.format("%.0f", v)
        fun f2(v: Double): String = String.format("%.2f", v)
    }
}

/** ستاپ — معادل ساختار Setup در Pine. */
class Setup(
    val id: Long,
    val dir: Int,
    var stage: Int,
    var zTop: Double,
    var zBot: Double,
    var zIdx: Int,
    var zBi: Int
) {
    var zT = 0L
    var touchBi = -1
    var touchMidStart = 0L
    var midRef = Double.NaN
    var midBi = -1
    var midT = 0L
    var markH = Double.NaN; var markL = Double.NaN; var markBi = -1
    var lvH = Double.NaN; var lvL = Double.NaN; var lvBi = -1; var lvT = 0L; var lvConfBi = -1; var lvConfT = 0L; var lvZoneId = -1L
    var hvH = Double.NaN; var hvL = Double.NaN; var hvBi = -1; var hvT = 0L; var hvScanBi = -1; var hvConfBi = -1; var hvConfT = 0L; var hvZoneId = -1L
    var midConfirmBi = -1
    var runMax = Double.NaN
    var zGone = false
    var lvTouched = false
    var lvUsed = false
    var lvReEntered = false
    var entry = Double.NaN
    var sl = Double.NaN
    var tp1 = Double.NaN
    var tp2 = Double.NaN
    var tpx = Double.NaN
    var be = false
    var tp2Hit = false      // آیا پلهٔ ۳۳٪ دوم (TP2) لمس شده (فقط برای مارکر چارت/لاگ)
    var note = ""
    var orderId = -1L
    // §4-8 سند نهایی: انقضای سفارش معلق
    var armBi = -1                // کندلی که در آن مسلح شدیم
    var armT = 0L
    var armsSinceS = 0            // تعداد مسلح‌شدن در این ستاپ (برای شرط «پایین‌تر/بالاتر»)
    var prevEntry = Double.NaN    // ورود قبلی در همین ستاپ (برای مقایسه)
    var rearmAllowed = false      // آیا اعتبار ساختار/میانی/تریگر۱ برای مسلح‌مجدد حفظ شده است
    var priceExpiryRef = Double.NaN // آخرین پین مخالف هنگام مسلح‌شدن این سفارش
    var entryBi = -1              // کندلی که ورود در آن پر شد (برای قاعده «ورود و TP1 در یک کندل»)
    var entryT = 0L
    val bull: Boolean get() = dir == 1
}


/** هدف‌های فیبوناچی با فالبک حداقل فاصلهٔ TPX. اگر TPX فالبک شود، پله‌ها نیز از همان فاصله محاسبه می‌شوند. */
internal data class TargetLevels(val tp1: Double, val tp2: Double, val tpx: Double)

internal fun calculateTargetLevels(
    bull: Boolean,
    entry: Double,
    anchor0: Double,
    anchor1: Double,
    minDistance: Double
): TargetLevels {
    val haveFib = !anchor0.isNaN() && !anchor1.isNaN() &&
            (if (bull) anchor0 > anchor1 else anchor0 < anchor1)
    var tp1 = Double.NaN
    var tp2 = Double.NaN
    var tpx = if (bull) entry + minDistance else entry - minDistance

    if (haveFib) {
        val r = if (bull) anchor0 - anchor1 else anchor1 - anchor0
        tp1 = if (bull) anchor1 + 0.382 * r else anchor1 - 0.382 * r
        tp2 = if (bull) anchor1 + 0.5 * r else anchor1 - 0.5 * r
        tpx = if (bull) anchor1 + 1.272 * r else anchor1 - 1.272 * r
    }

    if (bull) {
        val movedToMinimum = tpx - entry < minDistance
        if (movedToMinimum) tpx = entry + minDistance
        if (haveFib) {
            if (movedToMinimum) {
                val distance = tpx - entry
                tp1 = entry + distance * 0.382
                tp2 = entry + distance * 0.5
            } else {
                if (tp1.isNaN() || tp1 <= entry) tp1 = entry + (tpx - entry) * 0.382
                if (tp2.isNaN() || tp2 <= entry) tp2 = entry + (tpx - entry) * 0.5
            }
        }
    } else {
        val movedToMinimum = entry - tpx < minDistance
        if (movedToMinimum) tpx = entry - minDistance
        if (haveFib) {
            if (movedToMinimum) {
                val distance = entry - tpx
                tp1 = entry - distance * 0.382
                tp2 = entry - distance * 0.5
            } else {
                if (tp1.isNaN() || tp1 >= entry) tp1 = entry - (entry - tpx) * 0.382
                if (tp2.isNaN() || tp2 >= entry) tp2 = entry - (entry - tpx) * 0.5
            }
        }
    }
    return TargetLevels(tp1, tp2, tpx)
}

/** A full structure interval has elapsed when the trigger-2 bar delta reaches its configured bar count. */
internal fun fullStructureElapsed(armBi: Int, currentBi: Int, tfS: Int, tf2: Int): Boolean {
    if (armBi < 0) return false
    val nBars = maxOf(1, tfS / maxOf(1, tf2))
    return currentBi - armBi >= nBars
}

/** Strict open/close crossing of the captured latest opposing pin; wicks and equality do not expire an order. */
internal fun priceExpiryCrossed(bull: Boolean, open: Double, close: Double, level: Double): Boolean {
    if (level.isNaN()) return false
    return if (bull) open > level || close > level else open < level || close < level
}

/** The original structure/middle/Trigger-1 chain must remain valid for a rearm. */
internal fun rearmPrerequisitesValid(s: Setup): Boolean =
    !s.zGone && !s.midRef.isNaN() && s.lvBi >= 0 && s.lvReEntered && !s.lvUsed

/** A close/wick that invalidates a pre-entry condition while the trade is open blocks only a future rearm. */
internal fun rearmInvalidatedByBar(s: Setup, bar: Candle, midInvalidClose: Boolean): Boolean {
    val bull = s.dir == 1
    val structureBroken = bar.bi > s.zBi &&
            (if (bull) bar.c < s.zBot else bar.c > s.zTop)
    val midBroken = !s.midRef.isNaN() && bar.bi > s.midConfirmBi &&
            (if (midInvalidClose) {
                if (bull) bar.c < s.midRef else bar.c > s.midRef
            } else {
                if (bull) bar.l < s.midRef else bar.h > s.midRef
            })
    return structureBroken || midBroken
}
