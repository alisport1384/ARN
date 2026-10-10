package com.alisport.goldpin.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ═══════════════════════════════════════════════════════════════════════════════
//  پروفایل حجم فیکس‌رنج + الگوریتم ناحیه (نسخهٔ ۲ کارفرما)
//  پورت مستقیم ⑥ و ⑥-A اسکریپت Pine.  ⚠ POC هیچ نقشی در ساخت ناحیه‌ها ندارد.
// ═══════════════════════════════════════════════════════════════════════════════
object VolumeProfile {

    const val DIST_TRI_CLOSE = 0
    const val DIST_TRI_TYPICAL = 1
    const val DIST_UNIFORM = 2

    /** معادل `f_smoothRows` — میانگین وزنی (۱،۲،۱)/۴ به تعداد passes. */
    fun smoothRows(rv: DoubleArray, passes: Int) {
        val n = rv.size
        repeat(passes) {
            val tmp = DoubleArray(n)
            for (i in 0 until n) {
                val a = if (i > 0) rv[i - 1] else rv[i]
                val b = rv[i]
                val cc = if (i < n - 1) rv[i + 1] else rv[i]
                tmp[i] = (a + 2.0 * b + cc) / 4.0
            }
            System.arraycopy(tmp, 0, rv, 0, n)
        }
    }

    /**
     * ساخت پروفایل حجم فیکس‌رنج از ریزکندل‌های تریگر۲ داخل کندل ساختار.
     * @param micro کندل‌های تایم‌فریم تریگر۲
     * @param rows تعداد ردیف‌ها (پیش‌فرض ۲۴)
     */
    fun build(
        micro: List<Candle>,
        rows: Int,
        vaPct: Double,
        dist: Int,
        smoothPasses: Int,
        mintick: Double
    ): Profile {
        var lo = Double.NaN
        var hi = Double.NaN
        if (micro.isNotEmpty()) {
            lo = micro[0].l
            hi = micro[0].h
            for (i in 1 until micro.size) {
                lo = min(lo, micro[i].l)
                hi = max(hi, micro[i].h)
            }
        }
        val rv = DoubleArray(rows)
        if (!lo.isNaN() && hi > lo) {
            val step0 = (hi - lo) / rows
            val uniform = dist == DIST_UNIFORM
            for (m in micro) {
                val anchor = if (dist == DIST_TRI_TYPICAL) (m.h + m.l + m.c) / 3.0 else m.c
                val span = max(m.h - m.l, mintick) * 1.2
                var wSum = 0.0
                for (r in 0 until rows) {
                    val rBot = lo + r * step0
                    val rTop = rBot + step0
                    val ov = max(0.0, min(m.h, rTop) - max(m.l, rBot))
                    if (ov > 0) {
                        val w = if (uniform) 1.0
                        else max(0.05, 1.0 - abs((rBot + rTop) / 2.0 - anchor) / span)
                        wSum += ov * w
                    }
                }
                if (wSum > 0) {
                    for (r in 0 until rows) {
                        val rBot = lo + r * step0
                        val rTop = rBot + step0
                        val ov = max(0.0, min(m.h, rTop) - max(m.l, rBot))
                        if (ov > 0) {
                            val w = if (uniform) 1.0
                            else max(0.05, 1.0 - abs((rBot + rTop) / 2.0 - anchor) / span)
                            rv[r] += m.v * ov * w / wSum
                        }
                    }
                }
            }
        }
        if (smoothPasses > 0) smoothRows(rv, smoothPasses)

        // ── POC + Value Area (فقط برای نمایش/مقایسه؛ در ساخت ناحیه استفاده نمی‌شود) ──
        var poc = 0
        for (r in 1 until rows) if (rv[r] > rv[poc]) poc = r
        val tot = rv.sum()
        val inVA = BooleanArray(rows)
        inVA[poc] = true
        var acc = rv[poc]
        var cnt = 1
        while (acc < tot * vaPct / 100.0 && cnt < rows) {
            val dn = poc - cnt
            val up = poc + cnt
            val dv = if (dn >= 0) rv[dn] else -1.0
            val uv = if (up < rows) rv[up] else -1.0
            if (dv < 0 && uv < 0) break
            if (uv >= dv) { inVA[up] = true; acc += uv } else { inVA[dn] = true; acc += dv }
            cnt++
        }
        var firstVA = -1
        var lastVA = -1
        for (r in 0 until rows) if (inVA[r]) { if (firstVA < 0) firstVA = r; lastVA = r }

        val hasRange = !lo.isNaN() && hi > lo
        val step = if (hasRange) (hi - lo) / rows else 0.0
        val vaBot = if (firstVA >= 0 && hasRange) lo + firstVA * step else Double.NaN
        val vaTop = if (firstVA >= 0 && hasRange) lo + (lastVA + 1) * step else Double.NaN
        val pocPrice = if (hasRange) lo + (poc + 0.5) * (hi - lo) / rows else Double.NaN
        return Profile(rv, lo, step, pocPrice, vaTop, vaBot)
    }

    /**
     * الگوریتم ناحیه (نسخهٔ ۲) — `f_zones`.
     * صعودی: از کف به سقف پیمایش؛ قلهٔ اول = ردیفی که رشد در آن متوقف می‌شود،
     * درهٔ اول = ردیفی که افت در آن متوقف می‌شود. کف ناحیه = مرز بین ردیف قبلِ قله و خودِ قله ،
     * سقف ناحیه = مرز بین دره و ردیف بعدِ بزرگ‌ترش. سپس همین چرخه بعد از دره تکرار می‌شود.
     * نزولی: کاملاً قرینه.
     * شرط اعتبار: کلوز نباید داخل/روی محدوده باشد (صعودی: zTop < close).
     */
    fun zones(
        p: Profile,
        rows: Int,
        bull: Boolean,
        cClose: Double,
        minRows: Int,
        wantRejected: Boolean = false
    ): List<Zone> {
        val out = ArrayList<Zone>(2)
        val rej = ArrayList<Zone>(2)
        var found = 0
        val rv = p.vol
        val lo = p.lo
        val step = p.step
        if (rows >= 5 && step > 0 && !lo.isNaN()) {
            val stepDir = if (bull) 1 else -1
            var i = if (bull) 0 else rows - 1
            var guard = 0
            // سقف دو نامزد در مجموع است؛ نامزد ردشده به‌خاطر کلوز هم سهمیه می‌گیرد.
            while (guard < 4 * rows && out.size + rej.size < 2) {
                guard++
                // ① صعود به سمت قلهٔ محلی در جهت حرکت
                //   §۲-۴ اکید + قاعدهٔ کارفرما ۱۴۰۵/۰۷/۱۹:
                //   «قله آخرین، دره اولین». صعود می‌کنیم تا ردیف بعد بزرگ‌تر نباشد.
                //   در فلات صعودی (چند ردیف با حجم مساوی در قله) جلو می‌رویم تا آخرین
                //   ردیف مساوی، یعنی جایی که نزول واقعاً شروع می‌شود. مثال:
                //   10,20,30,40,50,50,50,40,30,20,20,20 → pk=اندیس ۶ (آخرین ۵۰).
                var pk = -1
                var j = i
                var climbed = false
                // ① صعود به سمت قله: در حلقهٔ بیرونی تکرار می‌کنیم تا جایی که ردیف بعدی
                //   اکیداً کوچک‌تر شود (یعنی نزول واقعاً شروع شده). درون هر چرخه:
                //   (الف) تا جایی که ردیف بعد بزرگ‌تر است صعود می‌کنیم،
                //   (ب) در فلات صعودی تا **آخرین** ردیف مساوی جلو می‌رویم
                //       — قاعدهٔ «قله آخری».
                while (true) {
                    while (j + stepDir in 0..(rows - 1) && rv[j + stepDir] > rv[j]) {
                        j += stepDir; climbed = true
                    }
                    while (j + stepDir in 0..(rows - 1) && rv[j + stepDir] == rv[j]) j += stepDir
                    val nextGt = j + stepDir in 0..(rows - 1) && rv[j + stepDir] > rv[j]
                    if (!nextGt) break
                }
                //   قله = انتهای صعود (j) در صورتی که حرکت کرده باشیم. در غیر این صورت
                //   (حتی یک گام هم جلو نرفته)، بررسی می‌کنیم که آیا در نقطهٔ شروع
                //   یک قلهٔ تک‌ردیفه داریم یا نه — این قاعده فقط برای اولین پیمایش
                //   نیست و بعد از هر دره نیز اعمال می‌شود (§۲-۴ تکرار بعد از دره).
                if (j != i) pk = j
                else {
                    val nx = i + stepDir
                    if (nx in 0..(rows - 1) && rv[nx] < rv[i]) { pk = i; climbed = true }
                }
                if (pk < 0) break
                //   اگر کل حرکت شامل هیچ افزایش اکیدی نبود (مثلاً پروفایل تخت یا
                //   پیشروی فقط روی فلات)، قلهٔ واقعی نداریم.
                if (!climbed) break
                // ② اولین درهٔ محلی بعد از قله (قاعدهٔ «دره اولی»)
                //   دره = جایی که حجم دیگر اکیداً کمتر نمی‌شود. فلات نزولی را ادامه
                //   نمی‌دهیم — اولین نقطهٔ توقف، خودِ دره است.
                var tr = -1
                var k = pk
                while (k + stepDir in 0..(rows - 1) && rv[k + stepDir] < rv[k]) k += stepDir
                if (k != pk && k + stepDir in 0..(rows - 1)) tr = k
                //   دره پیدا نشد (لبهٔ جدول) — جست‌وجو تمام می‌شود
                //   (ناحیهٔ یک‌ردیفه تا مرز جدول نسازیم).
                if (tr < 0) break
                val loIdx = min(pk, tr)
                val hiIdx = max(pk, tr)
                // ③ محدوده = از مرز پایین قله تا مرز بالای دره
                val zBot = lo + loIdx * step
                val zTop = lo + (hiIdx + 1) * step
                val rowCnt = hiIdx - loIdx + 1
                val closeOK = if (bull) zTop < cClose else zBot > cClose
                if (zTop > zBot && rowCnt >= minRows) {
                    val z = Zone(zTop, zBot, found + 1, loIdx, hiIdx)
                    found++
                    if (closeOK) out.add(z) else rej.add(z)
                }
                //  ادامه پیمایش از یک ردیف بعد از دره — §۲-۴: بعد از هر دره الگوریتم
                //  معمولی تکرار می‌شود تا حداکثر دو ناحیه پیدا شود.
                i = tr + stepDir
            }
        }
        return if (wantRejected) rej else out
    }
}
