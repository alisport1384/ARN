package com.alisport.goldpin

import com.alisport.goldpin.data.Feed
import com.alisport.goldpin.util.Fa
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// ═══════════════════════════════════════════════════════════════════════════════
//  ریشهٔ فریز لایو — از ردپای ANR لاگ کاربر (samsung SM-J810F، اندروید ۱۰)
//
//    ERROR [ANR] main thread stalled · lag=2500ms → 5006 → 7509 → 10011ms
//      at android.graphics.text.LineBreaker.nComputeLineBreaks(Native Method)
//      at android.text.StaticLayout.generate(StaticLayout.java:784)
//      at android.widget.TextView.makeSingleLayout(TextView.java:10181)
//
//  زنجیرهٔ علت:
//   ۱) `parseYahoo` کلید «indicators» را با getJSONArray می‌خواند، ولی در پاسخ
//      /v8/finance/chart یک JSONObject است ⇒ همیشه JSONException.
//   ۲) پیام JSONException کل آبجکت indicators را جاسازی می‌کند ⇒ ~۱ مگابایت متن
//      (۵ روز کندل ۱ دقیقه‌ای × ۵ آرایه).
//   ۳) آن رشته → lastFeedError → headerStatus.text (یک TextView) و یک Toast
//      ⇒ LineBreaker روی ۱ مگابایت متن ⇒ نخ رابط بیش از ۱۰ ثانیه قفل.
// ═══════════════════════════════════════════════════════════════════════════════
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class YahooParseTest {

    /** شکل واقعی پاسخ /v8/finance/chart */
    private fun realPayload(n: Int): String {
        val ts = JSONArray(); val o = JSONArray(); val h = JSONArray()
        val l = JSONArray(); val c = JSONArray(); val v = JSONArray()
        for (i in 0 until n) {
            ts.put(1700000000L + i * 60)
            o.put(2000.0 + i); h.put(2001.5 + i); l.put(1999.0 + i)
            c.put(2000.7 + i); v.put(100 + i)
        }
        val quote = JSONObject().put("open", o).put("high", h)
            .put("low", l).put("close", c).put("volume", v)
        val indicators = JSONObject().put("quote", JSONArray().put(quote))
        val res = JSONObject().put("timestamp", ts).put("indicators", indicators)
            .put("meta", JSONObject().put("symbol", "GC=F"))
        return JSONObject().put("chart",
            JSONObject().put("result", JSONArray().put(res))).toString()
    }

    // ── ۱) باگ اصلی: «indicators» یک JSONObject است، نه JSONArray ──────────────
    @Test
    fun `indicators is a JSONObject - the old getJSONArray call could never work`() {
        val res = JSONObject(realPayload(3)).getJSONObject("chart")
            .getJSONArray("result").getJSONObject(0)
        val threw = try {
            res.getJSONArray("indicators")   // کاری که کد قدیمی می‌کرد
            false
        } catch (e: Exception) {
            println("OLD CODE WOULD THROW: ${Fa.short(e.message, 110)}")
            true
        }
        assertTrue("کد قدیمی باید همیشه استثنا می‌داد — یعنی لایو هیچ‌وقت داده نمی‌گرفت", threw)
        assertTrue("indicators باید واقعاً JSONObject باشد", res.optJSONObject("indicators") != null)
    }

    // ── ۲) پارسر اصلاح‌شده روی شکل واقعی کار می‌کند ────────────────────────────
    @Test
    fun `parseYahoo reads a real v8 chart response`() {
        val list = Feed.parseYahoo(realPayload(5))
        assertEquals("باید هر ۵ کندل خوانده شود", 5, list.size)
        assertEquals(1700000000L * 1000L, list[0].t)
        assertEquals(2000.0, list[0].o, 1e-9)
        assertEquals(2000.7, list[0].c, 1e-9)
        assertEquals(100.0, list[0].v, 1e-9)
        println("PARSED: ${list.size} candles, first=${list[0]}")
    }

    // ── ۳) پیام خطای org.json واقعاً بزرگ است (علت فریز) ───────────────────────
    @Test
    fun `json exception message embeds the whole payload and must be bounded`() {
        val big = realPayload(5000)          // ≈ پاسخ ۵ روز کندل ۱ دقیقه‌ای
        val res = JSONObject(big).getJSONObject("chart")
            .getJSONArray("result").getJSONObject(0)
        val rawMsg = try {
            res.getJSONArray("indicators"); ""
        } catch (e: Exception) {
            e.message ?: ""
        }
        println("RAW JSONException message length = ${rawMsg.length} chars")
        assertTrue("پیام خام باید بزرگ باشد (همان چیزی که TextView را قفل کرد)",
            rawMsg.length > 50_000)

        val bounded = Fa.short(rawMsg, 200)
        println("BOUNDED length = ${bounded.length}")
        assertTrue("رشتهٔ مهارشده باید کوچک بماند", bounded.length <= 260)
    }

    // ── ۴) هیچ رشتهٔ بزرگی از Fa.short رد نمی‌شود ──────────────────────────────
    @Test
    fun `Fa_short bounds any input including multiline`() {
        val oneMb = "x".repeat(1_000_000)
        assertTrue(Fa.short(oneMb, 160).length <= 220)
        val multiline = ("خطای بلند\n".repeat(200_000))
        val out = Fa.short(multiline, 90)
        assertTrue(out.length <= 160)
        assertTrue("خط جدید باید حذف شود", !out.contains('\n'))
        assertEquals("", Fa.short(null, 100))
        assertEquals("کوتاه", Fa.short("کوتاه", 100))
    }

    // ── ۵) تایم‌فریم‌های بالاتر از ۱ دقیقه هرگز بازه‌های غیرمجاز Yahoo (3mo / 2y / max) نمی‌فرستند ──
    @Test
    fun `yahooSpec and candidate ranges respect Yahoo v8 interval limits for all timeframes`() {
        val tfs = intArrayOf(
            com.alisport.goldpin.core.Tf.M1,
            180,
            com.alisport.goldpin.core.Tf.M5,
            com.alisport.goldpin.core.Tf.M15,
            com.alisport.goldpin.core.Tf.M30,
            com.alisport.goldpin.core.Tf.H1,
            com.alisport.goldpin.core.Tf.H4,
            com.alisport.goldpin.core.Tf.D1,
            com.alisport.goldpin.core.Tf.W1
        )
        assertEquals(com.alisport.goldpin.core.Tf.M1, Feed.baseTfFor(180))
        assertEquals(com.alisport.goldpin.core.Tf.W1, Feed.baseTfFor(com.alisport.goldpin.core.Tf.W1))

        for (depth in 1..3) {
            for (tf in tfs) {
                val base = Feed.baseTfFor(tf)
                val (intv, rng) = Feed.yahooSpec(base, depth)
                if (intv in setOf("5m", "15m", "30m")) {
                    assertTrue("بازهٔ 3mo روی $intv خطای HTTP 422 می‌دهد", rng != "3mo")
                }
                if (intv in setOf("1h", "60m")) {
                    assertTrue("بازهٔ 2y در سال کبیسه روی $intv خطای HTTP 422 می‌دهد", rng != "2y")
                }
                if (intv in setOf("1d", "1wk")) {
                    assertTrue("بازهٔ max روی $intv دقت داده را به ماهانه (1mo) تقلیل می‌دهد", rng != "max")
                }
            }
        }
        assertEquals("60d", Feed.yahooCandidateRanges("5m", "3mo").first())
        assertEquals("730d", Feed.yahooCandidateRanges("1h", "2y").first())
        assertEquals("10y", Feed.yahooCandidateRanges("1d", "max").first())
    }
}
