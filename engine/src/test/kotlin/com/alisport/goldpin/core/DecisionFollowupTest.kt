package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression checks for the owner-confirmed follow-up policies. */
class DecisionFollowupTest {

    private fun invokePrivate(engine: Engine, methodName: String, vararg args: Any?) {
        val types = args.map { arg ->
            when (arg) {
                is Boolean -> Boolean::class.javaPrimitiveType!!
                else -> arg!!::class.java
            }
        }.toTypedArray()
        val method = Engine::class.java.getDeclaredMethod(methodName, *types)
        method.isAccessible = true
        method.invoke(engine, *args)
    }

    @Test
    fun `minimum TPX fallback moves TP1 and TP2 with the new distance`() {
        val buy = calculateTargetLevels(
            bull = true, entry = 100.0, anchor0 = 105.0, anchor1 = 100.0, minDistance = 10.0
        )
        assertEquals(110.0, buy.tpx, 1e-9)
        assertEquals(103.82, buy.tp1, 1e-9)
        assertEquals(105.0, buy.tp2, 1e-9)

        val sell = calculateTargetLevels(
            bull = false, entry = 100.0, anchor0 = 95.0, anchor1 = 100.0, minDistance = 10.0
        )
        assertEquals(90.0, sell.tpx, 1e-9)
        assertEquals(96.18, sell.tp1, 1e-9)
        assertEquals(95.0, sell.tp2, 1e-9)
    }

    @Test
    fun `invalid Fibonacci still produces TPX only`() {
        val buy = calculateTargetLevels(
            bull = true, entry = 100.0, anchor0 = 99.0, anchor1 = 100.0, minDistance = 10.0
        )
        assertEquals(110.0, buy.tpx, 1e-9)
        assertTrue(buy.tp1.isNaN())
        assertTrue(buy.tp2.isNaN())

        val sell = calculateTargetLevels(
            bull = false, entry = 100.0, anchor0 = 101.0, anchor1 = 100.0, minDistance = 10.0
        )
        assertEquals(90.0, sell.tpx, 1e-9)
        assertTrue(sell.tp1.isNaN())
        assertTrue(sell.tp2.isNaN())
    }

    @Test
    fun `rearm window closes only after a full structure interval from arming`() {
        assertFalse(fullStructureElapsed(armBi = 58, currentBi = 60, tfS = 60, tf2 = 1))
        assertTrue(fullStructureElapsed(armBi = 1, currentBi = 61, tfS = 60, tf2 = 1))
        assertFalse(fullStructureElapsed(armBi = -1, currentBi = 100, tfS = 60, tf2 = 1))
    }

    @Test
    fun `strict price expiry uses open or close only and ignores equality`() {
        assertTrue(priceExpiryCrossed(bull = true, open = 101.0, close = 100.0, level = 100.0))
        assertFalse(priceExpiryCrossed(bull = true, open = 99.0, close = 100.0, level = 100.0))
        assertFalse(priceExpiryCrossed(bull = true, open = 99.0, close = 99.5, level = Double.NaN))

        assertTrue(priceExpiryCrossed(bull = false, open = 99.0, close = 100.0, level = 100.0))
        assertFalse(priceExpiryCrossed(bull = false, open = 101.0, close = 100.0, level = 100.0))
    }

    @Test
    fun `arming snapshots the latest opposing pin rather than the sequence extreme`() {
        val cfg = Settings().apply { minTPunits = 10.0 }
        val engine = Engine(cfg)
        val broker = PaperBroker(cfg)
        engine.broker = broker
        broker.engine = engine
        engine.highestBE = 200.0
        engine.lastBEpin = 190.0

        val setup = Setup(1L, 1, 5, 220.0, 210.0, 1, 0).apply {
            hvH = 210.0
            hvL = 209.0
            midRef = 195.0 // Valid anchor; latest pin differs from the sequence extreme.
        }
        val bar = Candle(10, 1_700_000_600_000L, 211.0, 212.0, 210.0, 211.0, 100.0)
        invokePrivate(engine, "armSetup", setup, bar, true)

        assertEquals(190.0, setup.priceExpiryRef, 1e-9)
        assertEquals(200.0, engine.highestBE, 1e-9)
        assertEquals(1, broker.pendingOrders.size)
    }

    @Test
    fun `next middle close reconfirms after invalidation without a new structure touch`() {
        val engine = Engine(Settings())
        val setup = Setup(1L, 1, 3, 95.0, 90.0, 1, 0).apply {
            midRef = 100.0
            midConfirmBi = 1
            touchMidStart = 0L
        }
        engine.setups.add(setup)
        val nextMiddleStart = 1_700_000_700_000L
        engine.aggM.cur = Candle(2, nextMiddleStart, 101.0, 103.0, 99.0, 100.0, 100.0)
        engine.curBi = 2
        engine.curT = nextMiddleStart

        invokePrivate(
            engine,
            "trigger2Engine",
            Candle(2, nextMiddleStart, 100.0, 102.0, 99.0, 101.0, 100.0)
        )
        assertTrue(setup.midRef.isNaN())
        assertEquals(2, setup.stage)
        assertEquals(nextMiddleStart, setup.touchMidStart)

        val closingMiddle = Candle(2, nextMiddleStart, 101.0, 104.0, 98.0, 102.0, 500.0)
        invokePrivate(engine, "middleEngine", closingMiddle)
        assertEquals(98.0, setup.midRef, 1e-9)
        assertEquals(2, setup.stage)
    }

    @Test
    fun `structure break during a trade blocks rearm without closing the trade`() {
        val engine = Engine(Settings())
        val setup = Setup(2L, 1, 7, 105.0, 95.0, 1, 0).apply {
            midRef = 85.0
            midConfirmBi = 0
            entry = 100.0
            entryBi = 0
            sl = 80.0
            tp1 = 110.0
            tp2 = 115.0
            tpx = 130.0
            rearmAllowed = true
        }
        engine.setups.add(setup)
        engine.curBi = 2
        engine.curT = 1_700_000_900_000L

        invokePrivate(
            engine,
            "trigger2Engine",
            Candle(2, engine.curT, 96.0, 102.0, 94.0, 94.0, 100.0)
        )

        assertEquals(7, setup.stage, "ستاپِ معاملهٔ باز نباید به‌خاطر شرط مسلح‌مجدد بسته شود")
        assertFalse(setup.rearmAllowed, "شکست ناحیهٔ ساختار باید مسلح‌مجدد بعدی را ببندد")
    }

    @Test
    fun `break-even rearm is gated by structure middle and Trigger 1 validity`() {
        val setup = Setup(1L, 1, 7, 105.0, 95.0, 1, 0).apply {
            midRef = 90.0
            midConfirmBi = 1
            lvBi = 2
            lvReEntered = true
        }
        assertTrue(rearmPrerequisitesValid(setup))

        setup.zGone = true
        assertFalse(rearmPrerequisitesValid(setup), "broken structure zone must block rearm")
        setup.zGone = false
        setup.midRef = Double.NaN
        assertFalse(rearmPrerequisitesValid(setup), "missing middle confirmation must block rearm")
        setup.midRef = 90.0
        setup.lvBi = -1
        assertFalse(rearmPrerequisitesValid(setup), "missing Trigger 1 box must block rearm")
        setup.lvBi = 2
        setup.lvReEntered = false
        assertFalse(rearmPrerequisitesValid(setup), "the documented first Trigger 1 return must have happened")
        setup.lvReEntered = true
        setup.lvUsed = true
        assertFalse(rearmPrerequisitesValid(setup), "a consumed Trigger 1 box must block rearm")
    }

    @Test
    fun `breaking structure or middle confirmation during an open trade blocks only rearm`() {
        val setup = Setup(1L, 1, 7, 105.0, 95.0, 1, 0).apply {
            midRef = 90.0
            midConfirmBi = 1
        }
        val middleWickBreak = Candle(2, 1_700_000_800_000L, 96.0, 100.0, 89.0, 96.0, 100.0)
        assertTrue(rearmInvalidatedByBar(setup, middleWickBreak, midInvalidClose = false))
        assertFalse(rearmInvalidatedByBar(setup, middleWickBreak, midInvalidClose = true))

        setup.midRef = 85.0
        val structureCloseBreak = Candle(2, 1_700_000_860_000L, 96.0, 97.0, 89.0, 94.0, 100.0)
        assertTrue(rearmInvalidatedByBar(setup, structureCloseBreak, midInvalidClose = true))
    }
}
