package com.alisport.goldpin.core

import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** History available only at the structure resolution seeds sequences, never FRVP boxes. */
class StructureHistorySeedTest {
    private fun sampleM1(): List<Candle> {
        val out = ArrayList<Candle>()
        GZIPInputStream(java.io.File("../app/src/main/assets/sample_xauusd_m1.csv.gz").inputStream())
            .bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    val p = line.split(',')
                    if (p.size >= 6) runCatching {
                        out.add(Candle(
                            out.size,
                            p[0].toLong(),
                            p[1].toDouble(), p[2].toDouble(), p[3].toDouble(),
                            p[4].toDouble(), p[5].toDouble()
                        ))
                    }
                }
            }
        return out
    }

    private fun aggregate(src: List<Candle>, tfSec: Int): List<Candle> {
        val out = ArrayList<Candle>()
        var bucket = Long.MIN_VALUE
        var o = 0.0; var h = 0.0; var l = 0.0; var c = 0.0; var v = 0.0
        fun flush() {
            if (bucket != Long.MIN_VALUE) out.add(Candle(out.size, bucket, o, h, l, c, v))
        }
        for (bar in src) {
            val next = Tf.bucket(tfSec, bar.t)
            if (next != bucket) {
                flush()
                bucket = next
                o = bar.o; h = bar.h; l = bar.l; c = bar.c; v = bar.v
            } else {
                h = maxOf(h, bar.h); l = minOf(l, bar.l); c = bar.c; v += bar.v
            }
        }
        flush()
        return out
    }

    @Test
    fun `older structure history records all pin and trend events without approximate zones`() {
        val m1 = sampleM1()
        assertTrue(m1.size > 10_000)
        val structure = aggregate(m1, Tf.H1)
        val engine = Engine(Settings())

        val firstModeTime = structure.last().t + Tf.H1 * 1000L
        assertEquals(structure.size, engine.seedStructureHistory(structure, firstModeTime))
        assertTrue(engine.trend != 0, "توالی قدیمی باید روند را از تاریخچه تعیین کند")
        assertTrue(engine.markers.any { it.kind == MarkerKind.PIN_BU || it.kind == MarkerKind.PIN_BE },
            "پین‌های تاریخی باید ثبت شوند")
        assertTrue(engine.markers.any { it.kind == MarkerKind.TREND_UP || it.kind == MarkerKind.TREND_DOWN },
            "شروع روند یا چرخش باید برچسب تاریخی داشته باشد")
        assertTrue(engine.markers.filter {
            it.kind == MarkerKind.TREND_UP || it.kind == MarkerKind.TREND_DOWN
        }.all { it.text.contains("صعودی") || it.text.contains("نزولی") },
            "جهت هر روند باید در متن برچسب مشخص باشد")
        assertTrue(engine.zones.isEmpty(), "بدون دیتای تریگر۲ نباید باکس پروفایل ساخته شود")
    }

    @Test
    fun `restored trend gets one explicit current direction label`() {
        val engine = Engine(Settings())
        engine.trend = 1
        engine.ensureCurrentTrendMarker(1_700_000_000L, 2_000.0, 12)
        engine.ensureCurrentTrendMarker(1_700_000_000L, 2_000.0, 12)
        assertEquals(1, engine.markers.count { it.text.startsWith("روند فعلی:") })
        assertEquals(MarkerKind.TREND_UP, engine.markers.last().kind)
        assertTrue(engine.markers.last().text.contains("صعودی"))

        engine.trend = -1
        engine.ensureCurrentTrendMarker(1_700_000_060L, 1_999.0, 13)
        assertEquals(1, engine.markers.count { it.text.startsWith("روند فعلی:") })
        assertEquals(MarkerKind.TREND_DOWN, engine.markers.last().kind)
        assertTrue(engine.markers.last().text.contains("نزولی"))
    }

    @Test
    fun `only structure bars before trigger2 history are seeded`() {
        val m1 = sampleM1()
        val structure = aggregate(m1, Tf.H1)
        val cut = m1.size * 3 / 4
        val fine = m1.subList(cut, m1.size)
        val firstFineBucket = Tf.bucket(Tf.H1, fine.first().t)
        val engine = Engine(Settings())

        val seeded = engine.seedStructureHistory(structure, fine.first().t)
        assertEquals(structure.count { it.t < firstFineBucket }, seeded)
        assertTrue(engine.zones.isEmpty(), "seed باید فقط توالی‌ها را ثبت کند")

        engine.feed(fine, lastIsClosed = true)
        assertEquals(fine.size, engine.processed)
        assertTrue(engine.zones.all { it.createdT >= fine.first().t },
            "باکس دقیق نباید پیش از شروع دادهٔ واقعی تریگر۲ ساخته شود")
    }
}
