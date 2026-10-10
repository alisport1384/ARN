package com.alisport.goldpin.core

// ═══════════════════════════════════════════════════════════════════════════════
//  ذخیره و بازیابی کامل وضعیت — «هر چه روی چارت بود، دقیقاً همان برگردد»
//  شامل: تنظیمات · کندل‌ها · موتور (روند/پین‌ها/ستاپ‌ها/باکس‌های ناحیه/مارکرها/
//        شمارنده‌ها) · کارگزار (سفارش‌ها/معاملات/موجودی/افت سرمایه)
// ═══════════════════════════════════════════════════════════════════════════════
object Store {

    const val FORMAT = 3

    class Loaded(
        val cfg: Settings,
        val eng: Engine,
        val broker: PaperBroker,
        val candles: List<Candle>,
        val meta: Map<String, Any?>,
        val savedAt: Long
    )

    // ── نوشتن ──────────────────────────────────────────────────────────────────
    fun save(
        cfg: Settings,
        eng: Engine,
        broker: PaperBroker,
        candles: List<Candle>,
        meta: Map<String, Any?> = emptyMap(),
        savedAt: Long = System.currentTimeMillis(),
        candleFrom: Int = 0
    ): String {
        val root = LinkedHashMap<String, Any?>()
        root["format"] = FORMAT
        root["savedAt"] = savedAt
        root["settings"] = settingsTo(cfg)
        root["meta"] = meta
        root["engine"] = engineTo(eng)
        root["broker"] = brokerTo(broker)

        val from = candleFrom.coerceIn(0, candles.size)
        val cs = ArrayList<Any?>(candles.size - from)
        for (i in from until candles.size) {
            val c = candles[i]
            cs.add(listOf(c.bi, c.t, c.o, c.h, c.l, c.c, c.v))
        }
        root["candleFrom"] = from
        root["candles"] = cs
        return Json.write(root)
    }

    private fun settingsTo(c: Settings): Map<String, Any?> = linkedMapOf(
        "tfMode" to c.tfMode, "tfS" to c.tfS, "tfM" to c.tfM, "tf1" to c.tf1, "tf2" to c.tf2,
        "vpRows" to c.vpRows, "vpVA" to c.vpVA, "vpSmooth" to c.vpSmooth, "distMode" to c.distMode,
        "minZoneRows" to c.minZoneRows, "drawZones" to c.drawZones, "showRejectedZones" to c.showRejectedZones,
        "clearUsedZones" to c.clearUsedZones, "maxZoneBoxes" to c.maxZoneBoxes, "showPocVA" to c.showPocVA,
        "maxSetups" to c.maxSetups, "midInvalidClose" to c.midInvalidClose,
        "hvScanFirstTouch" to c.hvScanFirstTouch, "hvMarkFirstIncrease" to c.hvMarkFirstIncrease,
        "rejectCandleNext" to c.rejectCandleNext,
        "mintick" to c.mintick, "slBufTicks" to c.slBufTicks, "minTPunits" to c.minTPunits,
        "useRiskPct" to c.useRiskPct, "riskPct" to c.riskPct, "equityPct" to c.equityPct,
        "maxLeverage" to c.maxLeverage, "roundQty" to c.roundQty,
        "contractSize" to c.contractSize, "initialEquity" to c.initialEquity
    )

    private fun settingsFrom(m: Map<String, Any?>): Settings {
        val c = Settings()
        if (m.isEmpty()) return c
        c.tfMode = m["tfMode"].asI(); c.tfS = m["tfS"].asI(); c.tfM = m["tfM"].asI()
        c.tf1 = m["tf1"].asI(); c.tf2 = m["tf2"].asI()
        c.vpRows = m["vpRows"].asI(); c.vpVA = m["vpVA"].asD(); c.vpSmooth = m["vpSmooth"].asI()
        c.distMode = m["distMode"].asI(); c.minZoneRows = m["minZoneRows"].asI()
        c.drawZones = m["drawZones"].asB(); c.showRejectedZones = m["showRejectedZones"].asB()
        c.clearUsedZones = m["clearUsedZones"].asB(); c.maxZoneBoxes = m["maxZoneBoxes"].asI()
        c.showPocVA = m["showPocVA"].asB()
        c.maxSetups = m["maxSetups"].asI(); c.midInvalidClose = m["midInvalidClose"].asB()
        c.hvScanFirstTouch = m["hvScanFirstTouch"].asB(); c.hvMarkFirstIncrease = m["hvMarkFirstIncrease"].asB()
        // rejectCandleNext در فایل‌های نسخهٔ ۱٫۳ و قبل‌تر وجود ندارد → asB(null)=false = مقدار پیش‌فرض
        c.rejectCandleNext = m["rejectCandleNext"].asB()
        c.mintick = m["mintick"].asD(); c.slBufTicks = m["slBufTicks"].asD(); c.minTPunits = m["minTPunits"].asD()
        c.useRiskPct = m["useRiskPct"].asB(); c.riskPct = m["riskPct"].asD(); c.equityPct = m["equityPct"].asD()
        c.maxLeverage = m["maxLeverage"].asD(); c.roundQty = m["roundQty"].asB()
        c.contractSize = m["contractSize"].asD(); c.initialEquity = m["initialEquity"].asD()
        return c
    }

    private fun aggTo(a: Agg, key: String, out: MutableMap<String, Any?>) {
        out["${key}_periods"] = a.periods
        out["${key}_cur"] = a.cur?.let { listOf(it.bi, it.t, it.o, it.h, it.l, it.c, it.v) }
        out["${key}_cl"] = a.cl?.let { listOf(it.bi, it.t, it.o, it.h, it.l, it.c, it.v) }
    }

    private fun aggFrom(m: Map<String, Any?>, key: String, a: Agg) {
        a.periods = m["${key}_periods"].asI()
        a.cur = candleFrom(m["${key}_cur"])
        a.cl = candleFrom(m["${key}_cl"])
        a.closedNow = false
    }

    private fun candleFrom(v: Any?): Candle? {
        val l = v.asList()
        if (l.size < 7) return null
        return Candle(l[0].asI(), l[1].asL(), l[2].asD(), l[3].asD(), l[4].asD(), l[5].asD(), l[6].asD())
    }

    private fun candleListTo(l: List<Candle>): List<Any?> = l.map { c -> listOf(c.bi, c.t, c.o, c.h, c.l, c.c, c.v) }

    private fun candleListFrom(v: Any?): MutableList<Candle> {
        val out = ArrayList<Candle>()
        for (e in v.asList()) candleFrom(e)?.let { out.add(it) }
        return out
    }

    private fun engineTo(e: Engine): Map<String, Any?> {
        val o = LinkedHashMap<String, Any?>()
        aggTo(e.aggS, "S", o); aggTo(e.aggM, "M", o); aggTo(e.agg1, "T1", o)
        o["structBars"] = candleListTo(e.structBars)
        o["microS"] = candleListTo(e.microS)
        o["pins"] = e.pins.map { listOf(it.kind, it.hi, it.lo, it.impBi, it.startBi, it.ref, it.status) }
        o["setups"] = e.setups.map { setupTo(it) }
        o["zones"] = e.zones.map { listOf(it.id, it.dir, it.idx, it.top, it.bot, it.createdBi, it.createdT, it.loIdx, it.hiIdx, it.endBi, it.endT, it.status) }
        o["markers"] = e.markers.map { listOf(it.t, it.bi, it.price, it.kind, it.text) }
        o["events"] = e.events.takeLast(400)
        o["cnt"] = listOf(e.cnt.setup, e.cnt.touch, e.cnt.mid, e.cnt.lv, e.cnt.hv, e.cnt.entry, e.cnt.done, e.cnt.zoneCount, e.cnt.zoneRejected)
        o["trend"] = e.trend
        o["HH"] = e.HH; o["HL"] = e.HL; o["LL"] = e.LL; o["LH"] = e.LH
        o["seqStart"] = e.seqStart
        o["bullBU1Low"] = e.bullBU1Low; o["bullBEHigh"] = e.bullBEHigh
        o["bullBU2Low"] = e.bullBU2Low; o["bullBU2Bi"] = e.bullBU2Bi
        o["bearBE1High"] = e.bearBE1High; o["bearBULow"] = e.bearBULow
        o["bearBE2High"] = e.bearBE2High; o["bearBE2Bi"] = e.bearBE2Bi
        o["bullSetup"] = e.bullSetup; o["bearSetup"] = e.bearSetup
        o["expectedReady"] = e.expectedReady
        o["lowestBUpin"] = e.lowestBUpin; o["highestBE"] = e.highestBE
        o["lastBUpin"] = e.lastBUpin; o["lastBEpin"] = e.lastBEpin
        o["buActive"] = e.buActive; o["beActive"] = e.beActive
        o["pinSeq"] = e.pinSeq; o["buSeq"] = e.buSeq; o["beSeq"] = e.beSeq
        o["lastBUpinRef"] = e.lastBUpinRef; o["lastBUpinHi"] = e.lastBUpinHi
        o["lastBEpinRef"] = e.lastBEpinRef; o["lastBEpinLo"] = e.lastBEpinLo
        o["lastZoneTop"] = e.lastZoneTop; o["lastZoneBot"] = e.lastZoneBot
        o["lastPocPx"] = e.lastPocPx; o["lastProfBars"] = e.lastProfBars
        o["lastEntryPx"] = e.lastEntryPx; o["lastSlPx"] = e.lastSlPx; o["lastTpPx"] = e.lastTpPx
        o["lastResult"] = e.lastResult
        o["prevT1Vol"] = e.prevT1Vol; o["prevChartVol"] = e.prevChartVol
        o["zoneSeq"] = e.zoneSeq; o["setupSeq"] = e.setupSeq; o["processed"] = e.processed
        o["curBi"] = e.curBi; o["curT"] = e.curT
        return o
    }

    private fun engineFrom(m: Map<String, Any?>, cfg: Settings): Engine {
        val e = Engine(cfg)
        aggFrom(m, "S", e.aggS); aggFrom(m, "M", e.aggM); aggFrom(m, "T1", e.agg1)
        e.structBars.addAll(candleListFrom(m["structBars"]))
        e.microS.addAll(candleListFrom(m["microS"]))
        for (p in m["pins"].asList()) {
            val l = p.asList(); if (l.size < 7) continue
            e.pins.add(Pin(l[0].asI(), l[1].asD(), l[2].asD(), l[3].asI(), l[4].asI(), l[5].asD(), l[6].asI()))
        }
        for (s in m["setups"].asList()) e.setups.add(setupFrom(s.asMap()))
        for (z in m["zones"].asList()) {
            val l = z.asList(); if (l.size < 12) continue
            e.zones.add(ZoneBox(l[0].asL(), l[1].asI(), l[2].asI(), l[3].asD(), l[4].asD(), l[5].asI(), l[6].asL(), l[7].asI(), l[8].asI(), l[9].asI(), l[10].asL(), l[11].asI()))
        }
        for (mk in m["markers"].asList()) {
            val l = mk.asList(); if (l.size < 5) continue
            e.markers.add(Marker(l[0].asL(), l[1].asI(), l[2].asD(), l[3].asI(), l[4].asS()))
        }
        for (ev in m["events"].asList()) e.events.add(ev.asS())
        val cn = m["cnt"].asList()
        if (cn.size >= 9) {
            e.cnt.setup = cn[0].asI(); e.cnt.touch = cn[1].asI(); e.cnt.mid = cn[2].asI()
            e.cnt.lv = cn[3].asI(); e.cnt.hv = cn[4].asI(); e.cnt.entry = cn[5].asI()
            e.cnt.done = cn[6].asI(); e.cnt.zoneCount = cn[7].asI(); e.cnt.zoneRejected = cn[8].asI()
        }
        e.trend = m["trend"].asI()
        e.HH = m["HH"].asD(); e.HL = m["HL"].asD(); e.LL = m["LL"].asD(); e.LH = m["LH"].asD()
        e.seqStart = m["seqStart"].asI()
        e.bullBU1Low = m["bullBU1Low"].asD(); e.bullBEHigh = m["bullBEHigh"].asD()
        e.bullBU2Low = m["bullBU2Low"].asD(); e.bullBU2Bi = m["bullBU2Bi"].asI()
        e.bearBE1High = m["bearBE1High"].asD(); e.bearBULow = m["bearBULow"].asD()
        e.bearBE2High = m["bearBE2High"].asD(); e.bearBE2Bi = m["bearBE2Bi"].asI()
        e.bullSetup = m["bullSetup"].asB(); e.bearSetup = m["bearSetup"].asB()
        e.expectedReady = m["expectedReady"].asI()
        e.lowestBUpin = m["lowestBUpin"].asD(); e.highestBE = m["highestBE"].asD()
        e.lastBUpin = m["lastBUpin"].asD(); e.lastBEpin = m["lastBEpin"].asD()
        e.buActive = m["buActive"].asB(); e.beActive = m["beActive"].asB()
        e.pinSeq = m["pinSeq"].asI()
        e.buSeq = if (m.containsKey("buSeq")) m["buSeq"].asI() else -1
        e.beSeq = if (m.containsKey("beSeq")) m["beSeq"].asI() else -1
        e.lastBUpinRef = m["lastBUpinRef"].asD(); e.lastBUpinHi = m["lastBUpinHi"].asD()
        e.lastBEpinRef = m["lastBEpinRef"].asD(); e.lastBEpinLo = m["lastBEpinLo"].asD()
        e.lastZoneTop = m["lastZoneTop"].asD(); e.lastZoneBot = m["lastZoneBot"].asD()
        e.lastPocPx = m["lastPocPx"].asD(); e.lastProfBars = m["lastProfBars"].asI()
        e.lastEntryPx = m["lastEntryPx"].asD(); e.lastSlPx = m["lastSlPx"].asD(); e.lastTpPx = m["lastTpPx"].asD()
        e.lastResult = m["lastResult"].asS()
        e.prevT1Vol = m["prevT1Vol"].asD(); e.prevChartVol = m["prevChartVol"].asD()
        e.zoneSeq = m["zoneSeq"].asL(); e.setupSeq = m["setupSeq"].asL(); e.processed = m["processed"].asI()
        e.curBi = m["curBi"].asI(); e.curT = m["curT"].asL()
        return e
    }

    private fun setupTo(s: Setup): Map<String, Any?> = linkedMapOf(
        "id" to s.id, "dir" to s.dir, "stage" to s.stage, "zTop" to s.zTop, "zBot" to s.zBot,
        "zIdx" to s.zIdx, "zBi" to s.zBi, "touchBi" to s.touchBi, "touchMidStart" to s.touchMidStart,
        "midRef" to s.midRef, "midBi" to s.midBi, "markH" to s.markH, "markL" to s.markL, "markBi" to s.markBi,
        "lvH" to s.lvH, "lvL" to s.lvL, "lvBi" to s.lvBi, "lvConfBi" to s.lvConfBi,
        "hvH" to s.hvH, "hvL" to s.hvL, "hvBi" to s.hvBi, "hvScanBi" to s.hvScanBi, "hvConfBi" to s.hvConfBi,
        "midConfirmBi" to s.midConfirmBi, "runMax" to s.runMax, "zGone" to s.zGone,
        "lvTouched" to s.lvTouched, "lvUsed" to s.lvUsed, "lvReEntered" to s.lvReEntered,
        "entry" to s.entry, "sl" to s.sl, "tp1" to s.tp1, "tp2" to s.tp2, "tpx" to s.tpx,
        "be" to s.be, "tp2Hit" to s.tp2Hit, "note" to s.note, "orderId" to s.orderId,
        "armBi" to s.armBi, "armsSinceS" to s.armsSinceS, "prevEntry" to s.prevEntry,
        "rearmAllowed" to s.rearmAllowed, "entryBi" to s.entryBi
    )

    private fun setupFrom(m: Map<String, Any?>): Setup {
        val s = Setup(m["id"].asL(), m["dir"].asI(), m["stage"].asI(), m["zTop"].asD(), m["zBot"].asD(), m["zIdx"].asI(), m["zBi"].asI())
        s.touchBi = m["touchBi"].asI(); s.touchMidStart = m["touchMidStart"].asL()
        s.midRef = m["midRef"].asD(); s.midBi = m["midBi"].asI()
        s.markH = m["markH"].asD(); s.markL = m["markL"].asD(); s.markBi = m["markBi"].asI()
        s.lvH = m["lvH"].asD(); s.lvL = m["lvL"].asD(); s.lvBi = m["lvBi"].asI(); s.lvConfBi = m["lvConfBi"].asI()
        s.hvH = m["hvH"].asD(); s.hvL = m["hvL"].asD(); s.hvBi = m["hvBi"].asI()
        s.hvScanBi = m["hvScanBi"].asI(); s.hvConfBi = m["hvConfBi"].asI()
        s.midConfirmBi = m["midConfirmBi"].asI(); s.runMax = m["runMax"].asD(); s.zGone = m["zGone"].asB()
        s.lvTouched = m["lvTouched"].asB(); s.lvUsed = m["lvUsed"].asB(); s.lvReEntered = m["lvReEntered"].asB()
        s.entry = m["entry"].asD(); s.sl = m["sl"].asD(); s.tp1 = m["tp1"].asD(); s.tp2 = m["tp2"].asD()
        s.tpx = m["tpx"].asD(); s.be = m["be"].asB()
        // tp2Hit در فایل‌های ذخیره‌شدهٔ نسخه‌های قبل وجود ندارد → false پیش‌فرض
        s.tp2Hit = m["tp2Hit"].asB()
        s.note = m["note"].asS(); s.orderId = m["orderId"].asL()
        s.armBi = if (m.containsKey("armBi")) m["armBi"].asI() else -1
        s.armsSinceS = m["armsSinceS"].asI()
        s.prevEntry = m["prevEntry"].asD()
        s.rearmAllowed = m["rearmAllowed"].asB()
        s.entryBi = if (m.containsKey("entryBi")) m["entryBi"].asI() else -1
        return s
    }

    private fun brokerTo(b: PaperBroker): Map<String, Any?> {
        val o = LinkedHashMap<String, Any?>()
        o["balance"] = b.balance; o["equity"] = b.equity
        o["maxEquity"] = b.maxEquity; o["maxDrawdown"] = b.maxDrawdown
        o["orderSeq"] = b.orderSeq; o["tradeSeq"] = b.tradeSeq
        o["orders"] = b.orders.map { listOf(it.id, it.setupId, it.dir, it.type, it.price, it.sl, it.tp1, it.tp2, it.tpX, it.qty, it.zoneTop, it.zoneBot, it.placedT, it.placedBi, it.status, it.filledT ?: 0L, it.filledBi ?: -1, it.closedT ?: 0L, it.cancelReason ?: "") }
        o["trades"] = b.trades.map { t ->
            linkedMapOf<String, Any?>(
                "id" to t.id, "orderId" to t.orderId, "setupId" to t.setupId, "dir" to t.dir,
                "entryT" to t.entryT, "entryBi" to t.entryBi, "entry" to t.entry,
                "sl0" to t.sl0, "tp1" to t.tp1, "tp2" to t.tp2, "tpX" to t.tpX, "qty" to t.qty,
                "zoneTop" to t.zoneTop, "zoneBot" to t.zoneBot, "contractSize" to t.contractSize,
                "exitT" to (t.exitT ?: 0L), "exitBi" to (t.exitBi ?: -1), "exitPx" to (t.exitPx ?: Double.NaN),
                "reason" to t.reason, "be" to t.be,
                "fills" to t.fills.map { listOf(it.t, it.bi, it.price, it.qty, it.kind, it.pnl) }
            )
        }
        o["pendingIds"] = b.pendingOrders.map { it.id }
        o["openIds"] = b.openTrades.map { it.id }
        // سازگاری با نسخه‌های قبل (تک‌سفارش):
        o["pendingId"] = b.pendingOrder?.id ?: -1L
        o["openId"] = b.openTrade?.id ?: -1L
        return o
    }

    private fun brokerFrom(m: Map<String, Any?>, cfg: Settings, e: Engine): PaperBroker {
        val b = PaperBroker(cfg)
        b.engine = e
        b.balance = m["balance"].asD(); b.equity = m["equity"].asD()
        b.maxEquity = m["maxEquity"].asD(); b.maxDrawdown = m["maxDrawdown"].asD()
        b.orderSeq = m["orderSeq"].asL(); b.tradeSeq = m["tradeSeq"].asL()
        for (x in m["orders"].asList()) {
            val l = x.asList(); if (l.size < 19) continue
            val or = Order(l[0].asL(), l[1].asL(), l[2].asI(), l[3].asS(), l[4].asD(), l[5].asD(), l[6].asD(),
                l[7].asD(), l[8].asD(), l[9].asD(), l[10].asD(), l[11].asD(), l[12].asL(), l[13].asI(), l[14].asI())
            or.filledT = if (l[15].asL() == 0L) null else l[15].asL()
            or.filledBi = if (l[16].asI() < 0) null else l[16].asI()
            or.closedT = if (l[17].asL() == 0L) null else l[17].asL()
            or.cancelReason = l[18].asS().ifEmpty { null }
            b.orders.add(or)
        }
        for (x in m["trades"].asList()) {
            val t = x.asMap()
            val tr = Trade(t["id"].asL(), t["orderId"].asL(), t["setupId"].asL(), t["dir"].asI(),
                t["entryT"].asL(), t["entryBi"].asI(), t["entry"].asD(), t["sl0"].asD(),
                t["tp1"].asD(), t["tp2"].asD(), t["tpX"].asD(), t["qty"].asD(),
                t["zoneTop"].asD(), t["zoneBot"].asD(), t["contractSize"].asD())
            for (f in t["fills"].asList()) {
                val l = f.asList(); if (l.size < 6) continue
                tr.fills.add(Fill(l[0].asL(), l[1].asI(), l[2].asD(), l[3].asD(), l[4].asS(), l[5].asD()))
            }
            if (t["exitT"].asL() != 0L) {
                tr.exitT = t["exitT"].asL(); tr.exitBi = t["exitBi"].asI()
                tr.exitPx = t["exitPx"].asD(); tr.reason = t["reason"].asS(); tr.be = t["be"].asB()
            }
            b.trades.add(tr)
        }
        // پشتیبانی از چند سفارش/پوزیشن (v1.4.9+):
        val pids = m["pendingIds"]
        if (pids != null) {
            for (pid in pids.asList()) {
                val id = pid.asL()
                b.orders.firstOrNull { it.id == id }?.let { b.restorePending(it) }
            }
        } else {
            val pid = m["pendingId"].asL(); if (pid >= 0) b.restorePending(b.orders.firstOrNull { it.id == pid })
        }
        val oids = m["openIds"]
        if (oids != null) {
            for (oid in oids.asList()) {
                val id = oid.asL()
                b.trades.firstOrNull { it.id == id }?.let { b.restoreOpen(it) }
            }
        } else {
            val oid = m["openId"].asL(); if (oid >= 0) b.restoreOpen(b.trades.firstOrNull { it.id == oid })
        }
        return b
    }

    // ── خواندن ─────────────────────────────────────────────────────────────────
    fun load(json: String): Loaded {
        val root = Json.parse(json).asMap()
        val cfg = settingsFrom(root["settings"].asMap())
        val eng = engineFrom(root["engine"].asMap(), cfg)
        val broker = brokerFrom(root["broker"].asMap(), cfg, eng)
        eng.broker = broker
        val from = root["candleFrom"].asI()
        val candles = ArrayList<Candle>()
        var idx = from
        for (x in root["candles"].asList()) {
            val c = candleFrom(x) ?: continue
            candles.add(Candle(idx++, c.t, c.o, c.h, c.l, c.c, c.v))
        }
        return Loaded(cfg, eng, broker, candles, root["meta"].asMap(), root["savedAt"].asL())
    }
}
