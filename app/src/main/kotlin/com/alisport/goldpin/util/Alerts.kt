package com.alisport.goldpin.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.alisport.goldpin.MainActivity
import com.alisport.goldpin.core.AlertKind

// ═══════════════════════════════════════════════════════════════════════════════
//  سیستم هشدار GoldPin
//  • وقتی سفارش «آماده» (Armed) می‌شود → هشدار
//  • پر شدن سفارش، ورود، TP1/TP2/حدسود، حدضرر، سر‌به‌سر، لغو، شکست ناحیه،
//    ناحیهٔ ولوم کم/زیاد، تغییر روند و کندل مهم → همه قابل هشدار دادن‌اند
//  • نوتیفیکیشن سیستمی (کانال جداگانه با صدا/لرزش) + بنر داخل اپ + گزارش در لاگر
//  • در حالت بک‌تست ساکت است (فقط لاگ) تا صدها هشدار پشت‌سرهم تولید نشود.
// ═══════════════════════════════════════════════════════════════════════════════
object Alerts {

    const val CHANNEL_ID = "goldpin_alerts_v1"

    // انواع هشدار
    const val K_ARMED = AlertKind.ARMED        // سفارش آمادهٔ ثبت شد
    const val K_FILLED = AlertKind.FILLED      // سفارش پر شد (ورود)
    const val K_TP1 = AlertKind.TP1
    const val K_TP2 = AlertKind.TP2
    const val K_TPX = AlertKind.TPX            // حد سود نهایی ۱٫۲۷۲
    const val K_SL = AlertKind.SL
    const val K_BE = AlertKind.BE              // خروج سر‌به‌سر
    const val K_CLOSE = AlertKind.CLOSE        // پایان معامله
    const val K_CANCEL = AlertKind.CANCEL      // لغو سفارش/ستاپ
    const val K_ZONE_NEW = AlertKind.ZONE_NEW
    const val K_ZONE_DEAD = AlertKind.ZONE_DEAD
    const val K_LV = AlertKind.LV              // ناحیهٔ ولوم کم
    const val K_HV = AlertKind.HV              // ناحیهٔ ولوم زیاد
    const val K_TREND = AlertKind.TREND
    const val K_PIN = AlertKind.PIN            // کندل مهم Ready
    const val K_STAGE = AlertKind.STAGE        // تغییر مرحلهٔ ستاپ
    const val K_FEED = AlertKind.FEED
    const val K_INFO = AlertKind.INFO

    fun kindFa(k: String): String = when (k) {
        K_ARMED -> "سفارش آماده"
        K_FILLED -> "ورود (پر شدن سفارش)"
        K_TP1 -> "TP1"
        K_TP2 -> "TP2"
        K_TPX -> "حد سود نهایی"
        K_SL -> "حد ضرر"
        K_BE -> "خروج سر‌به‌سر"
        K_CLOSE -> "پایان معامله"
        K_CANCEL -> "لغو"
        K_ZONE_NEW -> "ناحیهٔ جدید"
        K_ZONE_DEAD -> "ناحیه باطل شد"
        K_LV -> "ناحیهٔ ولوم کم"
        K_HV -> "ناحیهٔ ولوم زیاد"
        K_TREND -> "روند"
        K_PIN -> "کندل مهم"
        K_STAGE -> "تغییر مرحله"
        K_FEED -> "فید داده"
        else -> "اطلاع"
    }

    // ── تنظیمات (پیش‌فرض: روشن برای موارد مهم) ────────────────────────────────
    @Volatile var enabled = true
    @Volatile var notifyEnabled = true
    @Volatile var sound = true
    @Volatile var vibrate = true
    @Volatile var banner = true
    /** در حالت بک‌تست هم نوتیفیکیشن بدهد؟ پیش‌فرض خیر (فقط لاگ) */
    @Volatile var notifyInBacktest = false
    @Volatile var inBacktest = false
    @Volatile var liveRunning = false

    private val on = HashMap<String, Boolean>()

    /**
     * شناسهٔ نوتیفیکیشن **به ازای هر نوع هشدار ثابت** است.
     *
     * پیش‌تر `seq++` بود: هر هشدار یک نوتیفیکیشن *جدید* می‌ساخت که هیچ‌وقت هم
     * لغو نمی‌شد. در حالت لایو این یعنی انباشت بی‌نهایت نوتیفیکیشن (اندروید پس از
     * ~۵۰ نوتیفیکیشن برای هر اپ بقیه را بی‌صدا حذف می‌کند) و ساختن PendingIntent
     * و Notification برای هر کدام روی نخ لایو. حالا نوتیفیکیشن هر نوع *به‌روز*
     * می‌شود و تعدادشان به تعداد انواع هشدار (۱۸) محدود می‌ماند.
     */
    private val notifIdByKind = HashMap<String, Int>()
    private var nextNotifId = 4800
    private fun notifId(kind: String): Int = synchronized(notifIdByKind) {
        notifIdByKind.getOrPut(kind) { nextNotifId++ }
    }

    /** بنر داخل اپ: (متن، رنگ، نوع) */
    var bannerSink: ((String, Int, String) -> Unit)? = null

    class Rec(val t: Long, val kind: String, val title: String, val body: String)

    /**
     * ⚠ این صف از نخ لایو/موتور **نوشته** و از نخ رابط (بنر، تاریخچهٔ هشدارها)
     * **خوانده** می‌شود. `ArrayDeque.toList()` روی یک deque در حال تغییر
     * `ConcurrentModificationException` می‌دهد ⇒ با هر هشدار لایو، باز کردن
     * تاریخچه یا بنر می‌توانست اپ را بیندازد. پس همهٔ دسترسی‌ها زیر یک قفل‌اند.
     */
    private val historyList = ArrayDeque<Rec>()
    private val historyLock = Any()
    private const val MAX_HISTORY = 500

    fun history(): List<Rec> = synchronized(historyLock) { historyList.toList() }

    fun isOn(kind: String): Boolean = on[kind] ?: defaultOn(kind)

    private fun defaultOn(kind: String): Boolean = when (kind) {
        K_ARMED, K_FILLED, K_TP1, K_TP2, K_TPX, K_SL, K_BE, K_CLOSE -> true
        K_CANCEL, K_ZONE_DEAD, K_FEED -> true
        K_LV, K_HV, K_TREND, K_PIN -> true
        K_ZONE_NEW, K_STAGE, K_INFO -> false
        else -> false
    }

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("goldpin", Context.MODE_PRIVATE)
        enabled = p.getBoolean("alert_enabled", true)
        notifyEnabled = p.getBoolean("alert_notify", true)
        sound = p.getBoolean("alert_sound", true)
        vibrate = p.getBoolean("alert_vibrate", true)
        banner = p.getBoolean("alert_banner", true)
        notifyInBacktest = p.getBoolean("alert_in_backtest", false)
        for (k in ALL_KINDS) {
            if (p.contains("alert_$k")) on[k] = p.getBoolean("alert_$k", defaultOn(k))
        }
        createChannel(ctx)
        Log.i(Log.CAT_ALERT, "سیستم هشدار آماده شد", "enabled=$enabled notify=$notifyEnabled sound=$sound vibrate=$vibrate")
    }

    val ALL_KINDS = listOf(
        K_ARMED, K_FILLED, K_TP1, K_TP2, K_TPX, K_SL, K_BE, K_CLOSE,
        K_CANCEL, K_ZONE_NEW, K_ZONE_DEAD, K_LV, K_HV, K_TREND, K_PIN, K_STAGE, K_FEED, K_INFO
    )

    fun setEnabled(ctx: Context, v: Boolean) { enabled = v; save(ctx, "alert_enabled", v); Log.i(Log.CAT_ALERT, "هشدارها ${if (v) "روشن" else "خاموش"} شد") }
    fun setNotify(ctx: Context, v: Boolean) { notifyEnabled = v; save(ctx, "alert_notify", v) }
    fun setSound(ctx: Context, v: Boolean) { sound = v; save(ctx, "alert_sound", v) }
    fun setVibrate(ctx: Context, v: Boolean) { vibrate = v; save(ctx, "alert_vibrate", v) }
    fun setBanner(ctx: Context, v: Boolean) { banner = v; save(ctx, "alert_banner", v) }
    fun setInBacktest(ctx: Context, v: Boolean) { notifyInBacktest = v; save(ctx, "alert_in_backtest", v) }

    fun setKind(ctx: Context, kind: String, v: Boolean) {
        on[kind] = v
        ctx.getSharedPreferences("goldpin", Context.MODE_PRIVATE).edit().putBoolean("alert_$kind", v).apply()
        Log.d(Log.CAT_ALERT, "هشدار «${kindFa(kind)}» ${if (v) "روشن" else "خاموش"} شد")
    }

    private fun save(ctx: Context, key: String, v: Boolean) {
        ctx.getSharedPreferences("goldpin", Context.MODE_PRIVATE).edit().putBoolean(key, v).apply()
    }

    // ── کانال نوتیفیکیشن ──────────────────────────────────────────────────────
    private fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            val ch = NotificationChannel(CHANNEL_ID, "هشدارهای معاملاتی", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "آماده شدن سفارش، ورود، حدسود، حدضرر و تغییر وضعیت استراتژی"
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 220, 120, 220)
            if (sound) {
                ch.setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            val exist = nm.getNotificationChannel(CHANNEL_ID)
            if (exist == null) nm.createNotificationChannel(ch) else {
                // کانال موجود است؛ فقط اهمیت/لرزش را هم‌گام نگه می‌داریم
                exist.enableVibration(vibrate)
            }
        }
    }

    // ── شلیک هشدار ────────────────────────────────────────────────────────────
    /**
     * @param kind یکی از K_*
     * @param title عنوان هشدار
     * @param body توضیح (قیمت‌ها، حجم، دلیل)
     * @param symbol نشانهٔ متنی برای لاگر
     */
    fun fire(ctx: Context?, kind: String, title: String, body: String, symbol: String? = null) {
        val icon = iconFor(kind)
        // ۱) ثبت در تاریخچه و لاگر — همیشه (حتی اگر هشدار خاموش باشد، لاگ می‌شود)
        synchronized(historyLock) {
            historyList.addLast(Rec(System.currentTimeMillis(), kind, title, body))
            while (historyList.size > MAX_HISTORY) historyList.removeFirst()
        }
        Log.i(Log.CAT_ALERT, "هشدار [${kindFa(kind)}] $title", (body + (symbol?.let { " · $it" } ?: "")).take(400))

        if (!enabled || !isOn(kind)) return

        val allowNotify = notifyEnabled && (!inBacktest || notifyInBacktest)

        // ۲) بنر داخل اپ
        if (banner && allowNotify) {
            try { bannerSink?.invoke("$title — $body", icon, kind) } catch (t: Throwable) { }
        }

        // ۳) نوتیفیکیشن سیستمی — با سقف نرخ.
        // هر `notify()` یک Binder IPC است؛ در بازپخش تاریخچه چند هزار فراخوانی
        // پشت‌سرهم، نخ لایو و NotificationManager را قفل می‌کرد.
        if (allowNotify && ctx != null) {
            val nowN = System.currentTimeMillis()
            if (nowN - lastNotifAt >= MIN_NOTIF_GAP_MS) {
                lastNotifAt = nowN
                postNotification(ctx, kind, title, body, icon)
            } else {
                notifDropped++
            }
        }

        // ۴) صدا و لرزش (فقط وقتی اپ در حال کار است یا لایو روشن است)
        if (allowNotify && (liveRunning || !inBacktest)) {
            val now = System.currentTimeMillis()
            if (now - lastCueAt >= MIN_CUE_GAP_MS) {
                lastCueAt = now
                if (sound) playSound(ctx)
                if (vibrate) doVibrate(ctx)
            }
        }
    }

    private fun iconFor(kind: String): Int = when (kind) {
        K_ARMED -> Palette.gold
        K_FILLED, K_TP1, K_TP2, K_TPX -> Palette.up
        K_SL, K_ZONE_DEAD, K_CANCEL -> Palette.down
        K_BE -> Palette.violet
        K_LV, K_HV -> Palette.accent
        else -> Palette.grey
    }

    private fun postNotification(ctx: Context, kind: String, title: String, body: String, color: Int) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(ctx, CHANNEL_ID) else
                @Suppress("DEPRECATION") Notification.Builder(ctx)
            b.setContentTitle("GoldPin · ${kindFa(kind)} — $title")
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText("$title\n$body"))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setWhen(System.currentTimeMillis())
            b.setColor(color)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                b.setPriority(Notification.PRIORITY_HIGH)
                if (sound) b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                if (vibrate) b.setVibrate(longArrayOf(0, 220, 120, 220))
            }
            nm.notify(notifId(kind), b.build())
            Log.d(Log.CAT_ALERT, "نوتیفیکیشن ارسال شد", "kind=$kind")
        } catch (t: Throwable) {
            Log.e(Log.CAT_ALERT, "ارسال نوتیفیکیشن ناموفق", t)
        }
    }

    private fun doVibrate(ctx: Context?) {
        try {
            val c = ctx ?: return
            val v = c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 220, 120, 220), -1))
            } else {
                @Suppress("DEPRECATION") v.vibrate(longArrayOf(0, 220, 120, 220), -1)
            }
        } catch (t: Throwable) { }
    }

    private var ringtone: android.media.Ringtone? = null

    /**
     * ⚠ کمینه فاصلهٔ بین دو صدا/لرزش. بدون آن، چند هشدار پشت‌سرهم (مثلاً ورود +
     * TP1 + TP2 در یک کندل) چند Ringtone هم‌زمان می‌ساختند.
     */
    private const val MIN_CUE_GAP_MS = 1500L
    @Volatile private var lastCueAt = 0L

    /**
     * کمینه فاصلهٔ بین دو نوتیفیکیشن سیستمی، و شمار آن‌هایی که به همین دلیل
     * کنار گذاشته شدند (در لاگ/نوتیفیکیشن وضعیت قابل دیدن است).
     */
    private const val MIN_NOTIF_GAP_MS = 700L
    @Volatile private var lastNotifAt = 0L
    @Volatile var notifDropped: Long = 0L
        private set

    /**
     * یک نمونهٔ [android.media.Ringtone] برای کل عمر اپ ساخته و **بازیافت** می‌شود.
     *
     * پیش‌تر به ازای *هر* هشدار `RingtoneManager.getRingtone(...)` یک پلیر صوتی
     * تازه ساخته می‌شد و قبلی فقط `stop()` می‌شد. با هشدارهای پشت‌سرهم در حالت
     * لایو این یعنی ساخت/دور انداختن مداوم پلیر صوتی.
     *
     * توجه: `Ringtone` متد عمومی `release()` **ندارد** (با `javap` روی
     * `platforms/android-34/android.jar` بررسی شد: فقط play/stop/isPlaying/…)،
     * پس راه درست، ساختن نمونهٔ جدید نیست بلکه بازیافت همان یک نمونه است.
     */
    private fun playSound(ctx: Context?) {
        try {
            val c = ctx ?: return
            val r = ringtone ?: RingtoneManager.getRingtone(
                c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            )?.also { ringtone = it }
            if (r == null) return
            if (r.isPlaying) { try { r.stop() } catch (t: Throwable) { } }
            r.play()
        } catch (t: Throwable) { }
    }

    /** هشدار آزمایشی از داخل اپ */
    fun test(ctx: Context) {
        fire(
            ctx, K_ARMED, "تست هشدار",
            "این یک هشدار آزمایشی است — اگر صدا/لرزش/نوتیفیکیشن را دیدید، سیستم درست کار می‌کند.",
            symbol = "TEST"
        )
    }

    fun clearHistory() { synchronized(historyLock) { historyList.clear() } }
}
