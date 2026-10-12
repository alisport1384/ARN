package com.alisport.goldpin.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// ═══════════════════════════════════════════════════════════════════════════════
//  کارگزار کاغذی (Paper) — معادل ⑮ در Pine
//  ثبت سفارش لیمیت روی لبهٔ ناحیهٔ ولوم زیاد ، پر شدن ، خروج پله‌ای ۳۳/۳۳/۳۴ ،
//  سر‌به‌سر بعد از TP1 ، انقضای سفارش ، و محاسبهٔ سود/زیان به دلار و R.
//
//  از v1.4.9 (تصمیم مالک Q4) چندین سفارش/پوزیشن هم‌زمان تا سقف maxSetups مجازند
//  (در هر دو جهت، هرم).
// ═══════════════════════════════════════════════════════════════════════════════
class PaperBroker(private val cfg: Settings) {

    val orders = ArrayList<Order>()
    val trades = ArrayList<Trade>()

    var balance = cfg.initialEquity
    var equity = cfg.initialEquity
    var maxEquity = cfg.initialEquity
    var maxDrawdown = 0.0
    var orderSeq = 0L
    var tradeSeq = 0L
    var lastError: String? = null
    var engine: Engine? = null

    /** چند سفارش/پوزیشن فعال هم‌زمان (تصمیم ۲۰۲۶-۱۰-۱۰). */
    private val pendings = ArrayList<Order>()
    private val opens = ArrayList<Trade>()

    /** سازگاری با کدهای قدیمی که یک سفارش/پوزیشن انتظار داشتند (اولین مورد). */
    val hasOpenTrade: Boolean get() = opens.isNotEmpty()
    val hasPending: Boolean get() = pendings.isNotEmpty()
    val openTrade: Trade? get() = opens.firstOrNull { it.open }
    val pendingOrder: Order? get() = pendings.firstOrNull()
    val openTrades: List<Trade> get() = opens.filter { it.open }
    val pendingOrders: List<Order> get() = pendings.toList()

    var logSink: ((String, String, String?) -> Unit)? = null
    var alertSink: ((String, String, String) -> Unit)? = null

    private fun lg(cat: String, msg: String, data: String? = null) { logSink?.invoke(cat, msg, data) }
    private fun fmt(v: Double): String = if (v.isNaN()) "-" else String.format("%.2f", v)
    private fun alarm(kind: String, title: String, body: String) { alertSink?.invoke(kind, title, body) }

    fun reset() {
        orders.clear(); trades.clear(); pendings.clear(); opens.clear()
        balance = cfg.initialEquity; equity = cfg.initialEquity
        maxEquity = cfg.initialEquity; maxDrawdown = 0.0
        lastError = null
    }

    /** مارجینِ در استفاده توسط سفارش‌های معلق و پوزیشن‌های باز. */
    private fun usedMargin(px: Double): Double {
        var u = 0.0
        for (o in pendings) u += o.qty * o.price
        for (t in opens) if (t.open) u += t.fills.filter { it.kind == "ENTRY" }.sumOf { it.qty } * t.entry
        return u
    }

    /** حجم بر اساس ریسک یا درصد سرمایه — با درنظرگرفتن مارجین مصرف‌شده. */
    fun qtyFor(px: Double, sl: Double): Double {
        if (px <= 0) return 0.0
        val risk = abs(px - sl)
        // از equity (نه balance) استفاده می‌کنیم تا چند پوزیشن باز جمع شوند.
        val eq = equity
        var q = if (cfg.useRiskPct) {
            if (risk > 0) eq * cfg.riskPct / 100.0 / risk else 0.0
        } else {
            eq * cfg.equityPct / 100.0 / px
        }
        // سقف مارجین کل نسبت به equity × maxLeverage
        val capMargin = eq * cfg.maxLeverage
        val availMargin = max(0.0, capMargin - usedMargin(px))
        val capQty = availMargin / px
        q = min(q, capQty)
        if (cfg.roundQty) q = floor(q)
        return max(q, 0.0)
    }

    /** ثبت سفارش لیمیت — مطابق §4-8 و §10 سند نهایی، حداکثر ۱ سفارش لیمیت در انتظار یا ۱ معامله باز در هر لحظه مجاز است. */
    fun registerLimit(s: Setup, bar: Candle): Boolean {
        val activeCount = pendings.size + opens.count { it.open }
        if (activeCount >= 1) {
            lastError = "فقط ۱ سفارش یا پوزیشن هم‌زمان در چارت مجاز است"
            return false
        }
        // هم‌پوشانی روی یک ستاپ: اگر همین setupId در حال حاضر سفارش معلق دارد، رد شود.
        if (pendings.any { it.setupId == s.id }) {
            lastError = "برای ستاپ #${s.id} قبلاً سفارش معلق ثبت شده"
            return false
        }
        val entryPx = if (s.bull) s.hvH else s.hvL
        val qty = qtyFor(entryPx, s.sl)
        if (qty <= 0) {
            lastError = "حجم صفر بود (مارجین کافی نیست یا حدضرر صفر)"
            return false
        }
        val o = Order(
            id = orderSeq++, setupId = s.id, dir = s.dir, type = "LIMIT",
            price = entryPx, sl = s.sl, tp1 = s.tp1, tp2 = s.tp2, tpX = s.tpx,
            qty = qty, zoneTop = s.zTop, zoneBot = s.zBot,
            placedT = bar.t, placedBi = bar.bi
        )
        s.orderId = o.id
        orders.add(o)
        pendings.add(o)
        lg("ORDER", "سفارش ورود ثبت شد (آماده) · ستاپ #${s.id}",
            "type=${if (s.dir == 1) "BUY_LIMIT" else "SELL_LIMIT"} price=${fmt(entryPx)} sl=${fmt(s.sl)} tp1=${fmt(s.tp1)} tp2=${fmt(s.tp2)} tp=${fmt(s.tpx)} qty=${fmt(qty)} active=$activeCount+1/${cfg.maxSetups} bar=${bar.bi}")
        alarm(AlertKind.ARMED, if (s.dir == 1) "سفارش خرید آماده شد" else "سفارش فروش آماده شد",
            "ستاپ #${s.id} · ورود ${fmt(entryPx)} · حدضرر ${fmt(s.sl)} · حدسود ${fmt(s.tpx)} · حجم ${fmt(qty)}")
        return true
    }

    fun cancel(
        order: Order,
        reason: String,
        t: Long,
        bi: Int,
        status: Int = OrderStatus.CANCELLED_INVALID
    ) {
        lg("ORDER", "سفارش لغو شد · #${order.id}", "reason=$reason status=$status")
        order.status = status
        order.cancelReason = reason
        order.closedT = t
        pendings.removeAll { it.id == order.id }
        // معامله باز را نمی‌بندیم (همان منطق ۷-۲).
    }

    /**
     * ستاپ باطل شد.
     * سفارش معلق لغو می‌شود؛ معاملهٔ پرشده تا حد سود/ضرر می‌رود.
     */
    fun onSetupInvalidated(s: Setup, t: Long? = null, bi: Int? = null) {
        val o = orders.lastOrNull { it.setupId == s.id && it.status == OrderStatus.PENDING }
        if (o != null) cancel(o, "ستاپ باطل شد", t ?: o.placedT, bi ?: o.placedBi)
    }

    /** انقضای واقعی طبق §4-8: وضعیت، زمان و هشدار سفارش هر سه ثبت می‌شوند. */
    fun onSetupExpired(s: Setup, t: Long, bi: Int, reason: String) {
        val o = orders.lastOrNull { it.setupId == s.id && it.status == OrderStatus.PENDING } ?: return
        cancel(o, reason, t, bi, OrderStatus.CANCELLED_EXPIRED)
        alarm(AlertKind.CANCEL, "سفارش ورود منقضی شد", "ستاپ #${s.id} · $reason · قیمت ${fmt(o.price)}")
    }

    fun restorePending(o: Order?) { if (o != null && !pendings.any { it.id == o.id }) pendings.add(o) }
    fun restoreOpen(t: Trade?) { if (t != null && t.open && !opens.any { it.id == t.id }) opens.add(t) }

    fun cancelPendingManually(t: Long, bi: Int) {
        ArrayList(pendings).forEach { cancel(it, "لغو دستی توسط کاربر", t, bi, OrderStatus.CANCELLED_MANUAL) }
    }

    /** پردازش یک کندل برای همهٔ سفارش‌ها و پوزیشن‌های باز. */
    fun onBar(cd: Candle, live: Boolean = false) {
        // ① پر شدن سفارش‌های لیمیت (روی یک کپی از pendings تا در fillEntry تغییرات لیست به iteration آسیب نزند)
        val pendingSnapshot = ArrayList(pendings)
        for (p in pendingSnapshot) {
            if (p.status != OrderStatus.PENDING) continue
            val touched = if (p.dir == 1) cd.l <= p.price else cd.h >= p.price
            val sameBarAsArmed = cd.bi == p.placedBi
            if (touched && !sameBarAsArmed) fillEntry(p, cd)
        }
        // ② مدیریت پوزیشن‌های باز (کپی تا manageBar کلوز نکند و iteration نشکند)
        for (t in ArrayList(opens)) {
            if (t.open) manageBar(t, cd)
        }
        // ③ به‌روزرسانی موجودی
        refreshEquity(cd)
    }

    private fun fillEntry(o: Order, cd: Candle) {
        o.status = OrderStatus.FILLED
        o.filledT = cd.t
        o.filledBi = cd.bi
        pendings.removeAll { it.id == o.id }
        val tr = Trade(
            id = tradeSeq++, orderId = o.id, setupId = o.setupId, dir = o.dir,
            entryT = cd.t, entryBi = cd.bi, entry = o.price,
            sl0 = o.sl, tp1 = o.tp1, tp2 = o.tp2, tpX = o.tpX, qty = o.qty,
            zoneTop = o.zoneTop, zoneBot = o.zoneBot, contractSize = cfg.contractSize
        )
        tr.fills.add(Fill(cd.t, cd.bi, o.price, o.qty, "ENTRY", 0.0))
        trades.add(tr)
        opens.add(tr)
        lg("TRADE", "سفارش پر شد → ورود انجام شد · معامله #${tr.id}",
            "dir=${if (tr.dir == 1) "BUY" else "SELL"} entry=${fmt(tr.entry)} sl=${fmt(tr.sl0)} tp=${fmt(tr.tpX)} qty=${fmt(tr.qty)} bar=${cd.bi}")
        alarm(AlertKind.FILLED, if (tr.dir == 1) "ورود خرید انجام شد" else "ورود فروش انجام شد",
            "معامله #${tr.id} · ورود ${fmt(tr.entry)} · حدضرر ${fmt(tr.sl0)} · حدسود ${fmt(tr.tpX)}")
    }

    private fun manageBar(t: Trade, cd: Candle) {
        val bull = t.dir == 1
        val sameEntryBar = cd.bi == t.entryBi
        val slLvl = if (t.be) t.entry else t.sl0
        val q1 = t.qty * 0.33
        val q2 = t.qty * 0.33

        // §4-9 ترتیب ثابت: حد ضرر، حد سود نهایی، TP1، TP2.
        // حد ضرر در کندلی که SL و TP لمس شوند برنده است.
        val hitStop = if (bull) cd.l <= slLvl else cd.h >= slLvl
        if (hitStop) {
            closeAll(t, slLvl, cd.t, cd.bi, if (t.be) "خروج سر‌به‌سر" else "حد ضرر")
            return
        }
        val hitFinal = if (bull) cd.h >= t.tpX else cd.l <= t.tpX
        if (hitFinal) {
            closeAll(t, t.tpX, cd.t, cd.bi, "حد سود نهایی (1.272)")
            return
        }

        // §6-21: اگر همان کندل ورود، هم TP1 و هم ورود لمس شوند، TP1 ثبت نمی‌شود؛
        // کل معامله در ورود بسته می‌شود. این استثنا بعد از اولویت SL و TPX است.
        if (sameEntryBar && !t.tp1.isNaN()) {
            val tp1Hit = if (bull) cd.h >= t.tp1 else cd.l <= t.tp1
            val entryTouched = if (bull) cd.l <= t.entry else cd.h >= t.entry
            if (tp1Hit && entryTouched) {
                t.be = true
                closeAll(t, t.entry, cd.t, cd.bi, "خروج سر‌به‌سر (TP1 و ورود یک کندل)")
                return
            }
        }

        val tp1Done = t.fills.any { it.kind == "TP1" }
        val remaining = t.qty - t.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty }
        if (!tp1Done && !t.tp1.isNaN()) {
            val hit = if (bull) cd.h >= t.tp1 else cd.l <= t.tp1
            if (hit) {
                addExit(t, cd, t.tp1, min(q1, remaining), "TP1")
                t.be = true
            }
        }

        val remainingNow = t.qty - t.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty }
        val tp2Done = t.fills.any { it.kind == "TP2" }
        if (t.open && t.be && !tp2Done && !t.tp2.isNaN() && remainingNow > 1e-9) {
            val hit = if (bull) cd.h >= t.tp2 else cd.l <= t.tp2
            if (hit) addExit(t, cd, t.tp2, min(q2, remainingNow), "TP2")
        }

        // اگر TP1 همین کندل حد ضرر را به ورود برد و قیمت به ورود برگشت،
        // کل باقیمانده در همین کندل بسته می‌شود؛ موتور و دفتر Broker یکی می‌مانند.
        val returnedToEntry = if (bull) cd.l <= t.entry else cd.h >= t.entry
        if (t.open && t.be && returnedToEntry) {
            closeAll(t, t.entry, cd.t, cd.bi, "خروج سر‌به‌سر")
        }
    }

    private fun addExit(t: Trade, cd: Candle, px: Double, qty: Double, kind: String) {
        val q = if (qty <= 0) t.qty - t.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty } else qty
        if (q <= 0) return
        val pnl = if (t.dir == 1) (px - t.entry) * q * t.contractSize else (t.entry - px) * q * t.contractSize
        t.fills.add(Fill(cd.t, cd.bi, px, q, kind, pnl))
        balance += pnl
        maxEquity = max(maxEquity, balance)
        val dd = maxEquity - balance
        if (dd > maxDrawdown) maxDrawdown = dd
        lg("TRADE", "پلهٔ خروج $kind اجرا شد · معامله #${t.id}",
            "px=${fmt(px)} qty=${fmt(q)} pnl=${fmt(pnl)} موجودی=${fmt(balance)}")
        val kindAlert = when (kind) {
            "TP1" -> AlertKind.TP1; "TP2" -> AlertKind.TP2; "TPX" -> AlertKind.TPX
            "SL" -> AlertKind.SL; "BE" -> AlertKind.BE; else -> AlertKind.CLOSE
        }
        alarm(kindAlert, "خروج $kind", "معامله #${t.id} · قیمت ${fmt(px)} · حجم ${fmt(q)} · سود/زیان ${fmt(pnl)} دلار")
    }

    private fun closeAll(t: Trade, px: Double, time: Long, bi: Int, reason: String) {
        val used = t.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty }
        val remaining = t.qty - used
        val kind = when {
            reason.contains("سر‌به‌سر") -> "BE"
            reason.startsWith("حد ضرر") -> "SL"
            reason.contains("1.272") -> "TPX"
            else -> "CLOSE"
        }
        if (remaining > 1e-9) {
            val pnl = if (t.dir == 1) (px - t.entry) * remaining * t.contractSize else (t.entry - px) * remaining * t.contractSize
            t.fills.add(Fill(time, bi, px, remaining, kind, pnl))
            balance += pnl
            maxEquity = max(maxEquity, balance)
            val dd = maxEquity - balance
            if (dd > maxDrawdown) maxDrawdown = dd
        }
        t.exitT = time; t.exitBi = bi; t.exitPx = px
        t.reason = reason
        opens.removeAll { it.id == t.id }
        lg("TRADE", "معامله #${t.id} بسته شد", "reason=$reason exit=${fmt(px)} pnl=${fmt(t.pnl())} R=${fmt(t.rMultiple())} موجودی=${fmt(balance)}")
        val pnl = t.pnl()
        alarm(AlertKind.CLOSE, "معامله بسته شد · ${t.reason}",
            "معامله #${t.id} · خروج ${fmt(px)} · سود/زیان ${fmt(pnl)} دلار · ${fmt(t.rMultiple())}R")
    }

    /** به‌روزرسانی سود/زیان شناور مجموع همهٔ پوزیشن‌های باز. */
    fun refreshEquity(cd: Candle) {
        var unreal = 0.0
        for (t in opens) {
            if (!t.open) continue
            val used = t.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty }
            val rem = t.qty - used
            unreal += if (t.dir == 1) (cd.c - t.entry) * rem * t.contractSize
                     else (t.entry - cd.c) * rem * t.contractSize
        }
        equity = balance + unreal
        maxEquity = max(maxEquity, equity)
        val dd = maxEquity - equity
        if (dd > maxDrawdown) maxDrawdown = dd
    }

    fun openTradePnl(): Double = opens.filter { it.open }.sumOf { it.pnl() }

    fun closedTrades(): List<Trade> = trades.filter { !it.open }
}
