package com.alisport.goldpin.data

import com.alisport.goldpin.core.Candle
import com.alisport.goldpin.core.Tf
import com.alisport.goldpin.util.Fa
import com.alisport.goldpin.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

// ═══════════════════════════════════════════════════════════════════════════════
//  فید داده — دانلود خودکار تاریخچهٔ طلا + قیمت لحظه‌ای + ورود از فایل CSV
//
//  منابع (به ترتیب اولویت):
//    ۱) Yahoo Finance  — نماد GC=F (فیوچرز طلا) : کندل + حجم ، بدون کلید ، ۱ دقیقه تا ۲ سال
//    ۲) gold-api.com   — نرخ لحظه‌ای طلا (Spot XAU) برای به‌روزرسانی قیمت و تیک
//    ۳) فایل CSV       — ورود دستی (مثلاً خروجی دوکاس‌کپی) برای تاریخچهٔ عمیق با حجم واقعی
//    ۴) دارایی نمونهٔ داخلی — بک‌تست آفلاین
//
//  نکته: حجم در Yahoo برای GC=F موجود است. اگر فید انتخابی حجم ندهد، موتور
//  امکان «حجم تقریبی از دامنهٔ کندل» را دارد تا استراتژی از کار نیفتد.
// ═══════════════════════════════════════════════════════════════════════════════

class FeedException(msg: String) : Exception(msg)

object Feed {

    const val SYMBOL_DEFAULT = "GC=F"
    @Volatile var connectTimeoutMs: Int = 8000
    @Volatile var readTimeoutMs: Int = 12000
    /** برای اینکه استفاده از حجم ساختگی فقط در لاگ نماند، اپ این callback را وصل می‌کند. */
    @Volatile var syntheticVolumeWarning: ((Int) -> Unit)? = null

    // ── ابزار HTTP ─────────────────────────────────────────────────────────────
    /**
     * @param connectMs سقف اتصال و @param readMs سقف خواندن.
     *
     * ⚠ این سقف‌ها مستقیماً «بدترین حالت یک تیک لایو» را می‌سازند. پیش‌تر هر دو
     * ۲۰٬۰۰۰ms بود و سه منبع قیمت هم timeout جداگانه داشتند؛ یک تیک می‌توانست
     * بیش از دو دقیقه طول بکشد. حالا هر درخواست حداکثر ۸+۱۲ ثانیه است، دو تلاش Yahoo
     * و بودجهٔ زنجیرهٔ قیمت هرکدام محدودند (بدترین حالت حدود ۶۰ ثانیه)، و `CoalescingGate`
     * نمی‌گذارد تیک‌های تازه روی هم تلنبار شوند.
     */
    private fun get(urlStr: String, connectMs: Int = connectTimeoutMs, readMs: Int = readTimeoutMs, userAgent: String = UA): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept", "application/json,text/csv,*/*")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw FeedException("HTTP $code از $urlStr")
            val gz = conn.contentEncoding?.contains("gzip", true) == true
            val raw = conn.inputStream
            val ins = if (gz) GZIPInputStream(raw) else raw
            return BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    // ── Yahoo Finance ──────────────────────────────────────────────────────────
    /**
     * کندل‌های تاریخی از Yahoo.
     * interval: 1m,5m,15m,30m,1h,1d — range: 1d,5d,7d,1mo,3mo,6mo,1y,2y,max
     */
    fun yahooChart(symbol: String, interval: String, range: String): List<Candle> {
        val t0 = System.currentTimeMillis()
        Log.i(Log.CAT_FEED, "درخواست داده از Yahoo", "symbol=$symbol interval=$interval range=$range")
        val hosts = listOf("query1.finance.yahoo.com", "query2.finance.yahoo.com")
        var lastErr: Exception? = null
        for (h in hosts) {
            try {
                val url = "https://$h/v8/finance/chart/$symbol?interval=$interval&range=$range&includePrePost=false"
                val body = get(url)
                val list = parseYahoo(body)
                if (list.isNotEmpty()) {
                    Log.i(Log.CAT_FEED, "پاسخ Yahoo دریافت شد",
                        "host=$h کندل=${list.size} مدت=${System.currentTimeMillis() - t0}ms حجم‌دار=${list.any { it.v > 0 }}")
                    return list
                }
                Log.w(Log.CAT_FEED, "پاسخ Yahoo خالی بود", "host=$h")
            } catch (e: Exception) {
                Log.w(Log.CAT_FEED, "خطای درخواست Yahoo",
                    "host=$h · " + Fa.short(e.message, 200) + " · طول‌پیام=${e.message?.length ?: 0}")
                lastErr = e
            }
        }
        throw FeedException("دریافت داده از Yahoo ناموفق بود: " +
            Fa.short(lastErr?.message ?: "خالی", 200))
    }

    /**
     * `internal` (نه `private`) تا آزمون واحد بتواند شکل واقعی پاسخ Yahoo را
     * بدون شبکه بررسی کند — همان مسیری که باعث فریز اپ شده بود.
     */
    internal fun parseYahoo(body: String): List<Candle> {
        val root = JSONObject(body)
        val chart = root.optJSONObject("chart") ?: throw FeedException("پاسخ نامعتبر: بدون «chart»")
        if (chart.optString("result", "null") == "null")
            throw FeedException("نتیجه خالی (${Fa.short(chart.optString("error"), 120)})")
        val res = chart.optJSONArray("result")?.optJSONObject(0)
            ?: throw FeedException("پاسخ بدون «result»")
        val ts = res.optJSONArray("timestamp")
            ?: throw FeedException("پاسخ بدون «timestamp»")
        // ⚠ در پاسخ /v8/finance/chart کلید «indicators» یک **JSONObject** است نه
        // JSONArray. پیش‌تر `getJSONArray("indicators")` صدا زده می‌شد که همیشه
        // JSONException می‌داد — یعنی لایو هیچ‌وقت داده نمی‌گرفت و هر ۱۰ ثانیه شکست
        // می‌خورد. بدتر اینکه آن JSONException کل آبجکت indicators (نزدیک یک مگابایت
        // برای ۵ روز کندل ۱ دقیقه‌ای) را در پیامش جاسازی می‌کرد و همان رشته نخ رابط
        // را در صفحه‌آرایی متن قفل می‌کرد.
        val ind = res.optJSONObject("indicators")
            ?: res.optJSONArray("indicators")?.optJSONObject(0)
            ?: throw FeedException("پاسخ بدون «indicators»")
        val quote = ind.optJSONArray("quote")?.optJSONObject(0)
            ?: throw FeedException("پاسخ بدون «quote»")
        val o = quote.optJSONArray("open"); val hi = quote.optJSONArray("high")
        val lo = quote.optJSONArray("low"); val cl = quote.optJSONArray("close")
        val vol = quote.optJSONArray("volume")
        val out = ArrayList<Candle>(ts.length())
        var bi = 0
        for (i in 0 until ts.length()) {
            val ov = o?.optDouble(i, Double.NaN) ?: Double.NaN
            val hv = hi?.optDouble(i, Double.NaN) ?: Double.NaN
            val lv = lo?.optDouble(i, Double.NaN) ?: Double.NaN
            val cv = cl?.optDouble(i, Double.NaN) ?: Double.NaN
            if (ov.isNaN() || hv.isNaN() || lv.isNaN() || cv.isNaN()) continue
            val vv = vol?.optDouble(i, 0.0) ?: 0.0
            out.add(Candle(bi++, ts.getLong(i) * 1000L, ov, hv, lv, cv, if (vv.isNaN()) 0.0 else vv))
        }
        return out
    }

    /** آخرین قیمت (چند ثانیه تأخیر) */
    fun yahooLast(symbol: String): Double? {
        return try {
            val body = get("https://query1.finance.yahoo.com/v8/finance/chart/$symbol?interval=1m&range=1d")
            val res = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val meta = res.optJSONObject("meta")
            val p = meta?.optDouble("regularMarketPrice", Double.NaN) ?: Double.NaN
            if (p.isNaN()) null else p
        } catch (e: Exception) {
            null
        }
    }

    /** نرخ لحظه‌ای طلای نقدی (Spot) — بدون کلید */
    fun goldApiSpot(): Double? {
        return try {
            val body = get("https://api.gold-api.com/price/XAU")
            val p = JSONObject(body).optDouble("price", Double.NaN)
            if (p.isNaN()) null else p
        } catch (e: Exception) {
            Log.d(Log.CAT_FEED, "gold-api پاسخ نداد", e.message ?: "")
            null
        }
    }

    /** نرخ XAU/USD از Swissquote (عمومی، بدون کلید) */
    fun swissquoteSpot(): Double? {
        return try {
            val body = get("https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/XAU/USD")
            val arr = org.json.JSONArray(body)
            val profiles = arr.getJSONObject(0).getJSONArray("spreadProfilePrices")
            val p = profiles.getJSONObject(0)
            val bid = p.optDouble("bid", Double.NaN)
            val ask = p.optDouble("ask", Double.NaN)
            if (bid.isNaN() || ask.isNaN()) null else (bid + ask) / 2.0
        } catch (e: Exception) {
            Log.d(Log.CAT_FEED, "Swissquote پاسخ نداد", e.message ?: "")
            null
        }
    }

    /** نرخ طلای توکنیزه (PAXG) از CoinGecko — پشتیبان سوم */
    fun coinGeckoSpot(): Double? {
        return try {
            val body = get("https://api.coingecko.com/api/v3/simple/price?ids=pax-gold&vs_currencies=usd")
            val p = JSONObject(body).optJSONObject("pax-gold")?.optDouble("usd", Double.NaN) ?: Double.NaN
            if (p.isNaN()) null else p
        } catch (e: Exception) {
            null
        }
    }

    /**
     * زنجیرهٔ منابع قیمت لحظه‌ای — هر کدام پاسخ داد، همان استفاده می‌شود.
     * (برای شرایطی که یک سرویس در دسترس نباشد.)
     */
    /**
     * کل زنجیرهٔ قیمت لحظه‌ای حداکثر [budgetMs] میلی‌ثانیه وقت دارد.
     * بدون این سقف، وقتی هر سه منبع قطع باشند یک تیک لایو ~۳۶ ثانیه فقط برای
     * قیمت لحظه‌ای معطل می‌ماند (و تیک‌های بعدی پشت آن صف می‌کشند).
     */
    fun spotPrice(budgetMs: Long = connectTimeoutMs.toLong() + readTimeoutMs.toLong()): Pair<Double?, String> {
        val deadline = System.currentTimeMillis() + budgetMs
        goldApiSpot()?.let { Log.d(Log.CAT_FEED, "قیمت لحظه‌ای از gold-api", com.alisport.goldpin.util.Fa.n(it, 2)); return it to "gold-api" }
        if (System.currentTimeMillis() >= deadline) { Log.w(Log.CAT_FEED, "بودجهٔ قیمت لحظه‌ای تمام شد", "منبع بعدی امتحان نشد"); return null to "کند/ناموفق" }
        swissquoteSpot()?.let { Log.d(Log.CAT_FEED, "قیمت لحظه‌ای از Swissquote", com.alisport.goldpin.util.Fa.n(it, 2)); return it to "Swissquote" }
        if (System.currentTimeMillis() >= deadline) { Log.w(Log.CAT_FEED, "بودجهٔ قیمت لحظه‌ای تمام شد", "منبع بعدی امتحان نشد"); return null to "کند/ناموفق" }
        coinGeckoSpot()?.let { Log.d(Log.CAT_FEED, "قیمت لحظه‌ای از CoinGecko", com.alisport.goldpin.util.Fa.n(it, 2)); return it to "CoinGecko(PAXG)" }
        Log.w(Log.CAT_FEED, "هیچ منبع قیمت لحظه‌ای پاسخ نداد")
        return null to "ناموفق"
    }

    // ── انتخاب بازه بر اساس تایم‌فریم چارت ─────────────────────────────────────
    /** (interval, range) مناسب Yahoo برای رسیدن به تایم‌فریم چارت */
    fun yahooSpec(chartTfSec: Int, depth: Int): Pair<String, String> {
        return when {
            chartTfSec <= Tf.M1 -> "1m" to if (depth >= 3) "7d" else "5d"
            chartTfSec <= Tf.M5 -> "5m" to when {
                depth >= 3 -> "3mo"; depth == 2 -> "1mo"; else -> "7d"
            }
            chartTfSec <= Tf.M15 -> "15m" to when {
                depth >= 3 -> "3mo"; depth == 2 -> "1mo"; else -> "7d"
            }
            chartTfSec <= Tf.M30 -> "30m" to when {
                depth >= 3 -> "3mo"; depth == 2 -> "1mo"; else -> "7d"
            }
            chartTfSec <= Tf.H1 -> "1h" to when {
                depth >= 3 -> "2y"; depth == 2 -> "1y"; else -> "3mo"
            }
            chartTfSec <= Tf.H4 -> "1h" to when {
                depth >= 3 -> "2y"; depth == 2 -> "1y"; else -> "6mo"
            }
            else -> "1d" to if (depth >= 3) "max" else if (depth == 2) "2y" else "1y"
        }
    }

    /** تایم‌فریم پایه‌ای که از Yahoo می‌گیریم و بعد تجمیع می‌کنیم */
    fun baseTfFor(chartTfSec: Int): Int = when {
        chartTfSec <= Tf.M1 -> Tf.M1
        chartTfSec <= Tf.M5 -> Tf.M5
        chartTfSec <= Tf.M15 -> Tf.M15
        chartTfSec <= Tf.M30 -> Tf.M30
        chartTfSec <= Tf.H1 -> Tf.H1
        chartTfSec <= Tf.H4 -> Tf.H1
        else -> Tf.D1
    }

    // ── تجمیع کندل‌ها ──────────────────────────────────────────────────────────
    /** تجمیع کندل‌های تایم‌فریم پایه به تایم‌فریم بالاتر (بدون ریپینت). */
    fun aggregate(src: List<Candle>, targetSec: Int): List<Candle> {
        if (src.isEmpty()) return src
        if (targetSec <= 0) return src
        val out = ArrayList<Candle>((src.size / 4) + 4)
        var curBucket = Long.MIN_VALUE
        var o = 0.0; var h = 0.0; var l = 0.0; var c = 0.0; var v = 0.0; var t = 0L
        for (cd in src) {
            val b = Tf.bucket(targetSec, cd.t)
            if (b != curBucket) {
                if (curBucket != Long.MIN_VALUE) out.add(Candle(out.size, t, o, h, l, c, v))
                curBucket = b; t = b; o = cd.o; h = cd.h; l = cd.l; c = cd.c; v = cd.v
            } else {
                h = maxOf(h, cd.h); l = minOf(l, cd.l); c = cd.c; v += cd.v
            }
        }
        if (curBucket != Long.MIN_VALUE) out.add(Candle(out.size, t, o, h, l, c, v))
        if (src.size != out.size) Log.d(Log.CAT_PERF, "تجمیع کندل‌ها",
            "ورودی=${src.size} خروجی=${out.size} تایم‌فریم=${Tf.label(targetSec)}")
        return out
    }

    /** اگر فید حجم نداشت، حجم تقریبی از دامنهٔ کندل می‌سازیم (تقریبی — برای کارکرد موتور). */
    fun synthesizeVolumeIfMissing(candles: List<Candle>): List<Candle> {
        if (candles.isEmpty()) return candles
        val hasVol = candles.count { it.v > 0.0 } > candles.size / 10
        if (hasVol) return candles
        Log.w(Log.CAT_FEED, "فید حجم نداشت → ساخت حجم تقریبی از دامنهٔ کندل", "کندل=${candles.size}")
        try { syntheticVolumeWarning?.invoke(candles.size) }
        catch (t: Throwable) { Log.e(Log.CAT_FEED, "اعلام حجم ساختگی به کاربر ناموفق بود", t) }
        return candles.map { Candle(it.bi, it.t, it.o, it.h, it.l, it.c, (it.h - it.l) * 10000.0 + 1.0) }
    }

    // ── CSV ────────────────────────────────────────────────────────────────────
    /**
     * خواندن CSV کندل. ستون‌ها: timestamp,open,high,low,close[,volume]
     * سرصفحه اختیاری است. مهر زمان می‌تواند ثانیه یا میلی‌ثانیه باشد.
     */
    fun parseCsv(text: String): List<Candle> {
        val out = ArrayList<Candle>()
        var bi = 0
        var first = true
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (first) {
                first = false
                if (line.contains("open", true) || line.contains("timestamp", true)) continue
            }
            // ستون‌های داخل " " هم پذیرفته می‌شوند (خروجی برخی ابزارها CSV نقل‌قول‌دار می‌دهند)
            val p = line.split(',', ';', '\t').map { it.trim().trim('"', '\'') }
            if (p.size < 5) continue
            val t = p[0].toLongOrNull() ?: continue
            val o = p[1].toDoubleOrNull() ?: continue
            val h = p[2].toDoubleOrNull() ?: continue
            val l = p[3].toDoubleOrNull() ?: continue
            val c = p[4].toDoubleOrNull() ?: continue
            val v = if (p.size >= 6) (p[5].toDoubleOrNull() ?: 0.0) else 0.0
            val ms = if (t < 100_000_000_000L) t * 1000L else t
            out.add(Candle(bi++, ms, o, h, l, c, v))
        }
        if (out.isEmpty()) throw FeedException("فایل CSV خوانده نشد (قالب ستون‌ها را بررسی کنید)")
        out.sortBy { it.t }
        val res = out.mapIndexed { i, c -> Candle(i, c.t, c.o, c.h, c.l, c.c, c.v) }
        Log.i(Log.CAT_FEED, "CSV تجزیه شد", "کندل=${res.size} حجم‌دار=${res.any { it.v > 0 }}")
        return res
    }

    /**
     * خواندن فایل CSV از ورودی با تشخیص خودکار gzip (بر اساس بایت‌های جادویی).
     * توجه: بسته‌بندی اندروید ممکن است دارایی «.gz» را باز و با نام «.csv» ذخیره کند،
     * پس هر دو حالت پشتیبانی می‌شود.
     */
    fun readCsvStream(stream: java.io.InputStream, gzipped: Boolean = false): String {
        val buffered = java.io.BufferedInputStream(stream, 1 shl 16)
        buffered.mark(4)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        val isGz = (b0 == 0x1f && b1 == 0x8b) || gzipped
        val ins = if (isGz) GZIPInputStream(buffered) else buffered
        return BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use { it.readText() }
    }

    /** باز کردن دارایی نمونهٔ داخلی (هر نامی که بسته‌بندی برایش انتخاب کرده باشد) */
    fun openSampleAsset(ctx: android.content.Context): java.io.InputStream {
        val names = listOf("sample_xauusd_m1.csv.gz", "sample_xauusd_m1.csv")
        for (n in names) {
            try { return ctx.assets.open(n) } catch (e: Exception) { }
        }
        throw FeedException("دارایی نمونهٔ داخلی پیدا نشد")
    }

    /** علامت خالی بودن دادهٔ زنده */
    fun isStale(candles: List<Candle>, chartTfSec: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (candles.isEmpty()) return true
        val last = candles.last().t
        // بازار طلا آخر هفته تعطیل است → ۳ روز مهلت
        return nowMs - last > 3L * 86400_000L
    }
}
