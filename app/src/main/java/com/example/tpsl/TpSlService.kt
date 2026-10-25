package com.example.tpsl

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * وقتی پنجره "حد سود / حد ضرر" ترندو باز بشه:
 *  1) جهت (خرید/فروش)، قیمت بازشدن و حجم رو می‌خونه
 *  2) قیمت هدف رو برای سود/ضرر دلاری مورد نظر حساب و توی فیلد تایپ می‌کنه
 *  3) عدد دلاری کنار فیلد رو می‌خونه و تأیید می‌کنه که درست شده (اگه نه، خودش اصلاح می‌کنه)
 *  4) فقط اگه هر دو درست بود، "تایید" رو می‌زنه
 */
class TpSlService : AccessibilityService() {

    private class Item(val n: AccessibilityNodeInfo, val t: String, val r: Rect)

    private class Ctx(
        val buy: Boolean,
        val sym: String,
        val open: Double,
        val decimals: Int,
        val vol: Double,
        val id: String?,
        val tpField: AccessibilityNodeInfo?,
        val tpVal: Double?,
        val tpUsd: Double?,
        val slField: AccessibilityNodeInfo?,
        val slVal: Double?,
        val slUsd: Double?,
        val confirm: AccessibilityNodeInfo?
    )

    private var statusView: TextView? = null
    private var badgeView: TextView? = null
    private var falconView: ImageView? = null
    private var enabledBox: CheckBox? = null
    private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var busy = false
    private var lastCheck = 0L
    private var lastAutoId: String? = null
    private var wm: WindowManager? = null
    private var panel: View? = null

    // ------------------------------------------------------------------
    // چرخه عمر سرویس
    // ------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("tpsl", Context.MODE_PRIVATE)
        showPanel()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try {
            prefListener?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        } catch (_: Exception) {
        }
        try {
            panel?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        panel = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::prefs.isInitialized) return
        if (!prefs.getBoolean("enabled", true)) return
        if (!prefs.getBoolean("auto", true) || busy) return
        if (event?.packageName?.toString() == packageName) return
        val now = SystemClock.uptimeMillis()
        if (now - lastCheck < 700) return
        lastCheck = now
        worker.execute { autoCheck() }
    }

    // ------------------------------------------------------------------
    // منطق اصلی
    // ------------------------------------------------------------------

    private fun autoCheck() {
        if (busy) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() == packageName) return
        val c = scan(root) ?: return
        val id = c.id ?: ("open:" + c.open)
        if (id == lastAutoId) return
        // خودکار فقط وقتی کار می‌کنه که هر دو فیلد خالی (۰.۰۰) باشن
        if ((c.tpVal ?: 0.0) != 0.0 || (c.slVal ?: 0.0) != 0.0) return
        lastAutoId = id
        runApply()
    }

    private fun scanNow(): Ctx? {
        val r = rootInActiveWindow ?: return null
        return scan(r)
    }

    private fun runApply() {
        if (!prefs.getBoolean("enabled", true)) {
            toast("Persia خاموشه")
            return
        }
        if (busy) return
        busy = true
        try {
            Thread.sleep(50)
            val tpUsd = prefs.getFloat("tp", 5f).toDouble()
            val slUsd = prefs.getFloat("sl", 3f).toDouble()
            if (scanNow() == null) {
                toast("پنجره حد سود/ضرر پیدا نشد")
                return
            }
            if (!setOne(true, tpUsd)) {
                toast("حد سود ست نشد؛ دستی انجام بده (تایید زده نشد)")
                return
            }
            if (!setOne(false, slUsd)) {
                toast("حد ضرر ست نشد؛ دستی انجام بده (تایید زده نشد)")
                return
            }
            // منتظر بمون تا دکمه "تایید" ظاهر بشه (نشونه اینه که اپ تغییر فیلدها رو قبول کرده)
            var c: Ctx? = scanNow()
            var tries = 0
            while (c != null && c.confirm == null && tries < 10) {
                Thread.sleep(150)
                c = scanNow()
                tries++
            }
            if (c == null) {
                toast("پنجره بسته شد")
                return
            }
            if ((c.tpVal ?: 0.0) == 0.0 || (c.slVal ?: 0.0) == 0.0) {
                toast("مقادیر ثبت نشد؛ دستی انجام بده")
                return
            }
            var conf = c.confirm
            if (conf == null && Build.VERSION.SDK_INT >= 30) {
                // راه اول: Enter کیبورد روی فیلد حد ضرر
                c.slField?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                Thread.sleep(400)
                conf = scanNow()?.confirm
            }
            if (conf == null) {
                // راه دوم: یک + واقعی بزن (دکمه تایید ظاهر میشه) و حد ضرر رو دوباره تایپ کن
                val cx = scanNow()
                if (cx != null) {
                    pressPlus(cx, false)
                    Thread.sleep(250)
                    if (setOne(false, slUsd)) {
                        Thread.sleep(150)
                        conf = scanNow()?.confirm
                    }
                }
            }
            if (conf == null) {
                toast("فیلدها پر شد ولی دکمه تایید ظاهر نشد؛ خودت تایید رو بزن")
                return
            }
            if (!prefs.getBoolean("confirm", true)) {
                toast("TP/SL پر شد ✓ تایید رو خودت بزن")
                return
            }
            if (!conf.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tapCenter(conf)
            Thread.sleep(500)
            toast(if (scanNow() == null) "TP/SL ست شد ✓" else "تایید زده شد؛ نتیجه رو چک کن")
        } catch (e: Exception) {
            toast("خطا: " + e.message)
        } finally {
            busy = false
        }
    }

    private fun isBtc(sym: String): Boolean =
        sym.contains("BITCOIN", true) || sym.contains("BTC", true)

    /**
     * ضریب K: سود/ضرر دلاری = حجم × K × (اختلاف قیمت).
     * بیت‌کوین: K=1 ثابت (دست نمی‌خوره). نمادهای دیگه (اتریوم، طلا، ...): بار اول یک قیمت آزمایشی تایپ
     * می‌کنیم، لیبل دلاری اپ رو می‌خونیم و K رو حساب و ذخیره می‌کنیم؛ دفعه‌های بعد مستقیم میره.
     */
    private fun kFor(c0: Ctx, isTp: Boolean): Double? {
        if (isBtc(c0.sym)) return 1.0
        val saved = prefs.getFloat("kv_" + c0.sym, 0f).toDouble()
        if (saved > 0.0) return saved
        var frac = 0.003
        for (i in 0 until 3) {
            val c = scanNow() ?: return null
            val d = c.open * frac
            val above = if (isTp) c.buy else !c.buy
            val price = if (above) c.open + d else c.open - d
            val txt = String.format(Locale.US, "%." + c.decimals + "f", price)
            for (method in 0 until 2) {
                val ok = if (method == 0) focusAndSet(isTp, txt) else focusAndPaste(isTp, txt)
                if (!ok) continue
                Thread.sleep(250)
                val cc = scanNow() ?: return null
                val l = if (isTp) cc.tpUsd else cc.slUsd
                val tv = if (isTp) cc.tpVal else cc.slVal
                if (l != null && tv != null && abs(tv - price) <= 0.011 && abs(l) >= 0.3) {
                    val k = abs(l) / (abs(tv - cc.open) * cc.vol)
                    if (k >= 1e-4 && k <= 1e7) {
                        prefs.edit().putFloat("kv_" + c0.sym, k.toFloat()).apply()
                        return k
                    }
                }
            }
            frac *= 5 // لیبل خیلی کوچیک بود -> فاصله آزمایشی بزرگ‌تر
        }
        return null
    }

    private fun fieldOf(c: Ctx, isTp: Boolean): AccessibilityNodeInfo? =
        if (isTp) c.tpField else c.slField

    /** روی فیلد کلیک می‌کنه (فوکوس واقعی ورودی + باز شدن کیبورد) و گره تازه فیلد رو برمی‌گردونه. */
    private fun clickField(isTp: Boolean): AccessibilityNodeInfo? {
        val c = scanNow() ?: return null
        fieldOf(c, isTp)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(250)
        val c2 = scanNow() ?: return null
        return fieldOf(c2, isTp)
    }

    private fun focusAndSet(isTp: Boolean, text: String): Boolean {
        val node = clickField(isTp) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun focusAndPaste(isTp: Boolean, text: String): Boolean {
        val node = clickField(isTp) ?: return false
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("p", text))
        val sel = Bundle()
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, node.text?.length ?: 0)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        Thread.sleep(80)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    /**
     * یک فیلد رو روی مقدار دلاری هدف می‌ذاره. موفقیت فقط وقتی حساب می‌شه که لیبل دلاری خود اپ
     * (که با مقدار واقعی فیلد آپدیت میشه) با هدف بخونه؛ تایپی که اپ قبول نکرده هیچ‌وقت تایید نمی‌شه.
     */
    private fun setOne(isTp: Boolean, usd: Double): Boolean {
        var c = scanNow() ?: return false
        var k = kFor(c, isTp) ?: return false
        val btc = isBtc(c.sym)
        val expected = if (isTp) usd else -usd
        val tol = max(0.03, 0.04 * usd)

        for (attempt in 0 until 2) {
            c = scanNow() ?: return false
            val d = usd / (c.vol * k)
            // محافظ: فاصله نامعقول (خیلی کم یا بیشتر از ۲۰٪ قیمت) هرگز تایپ نمی‌شه
            if (d.isNaN() || d < c.open * 1e-5 || d > c.open * 0.2) return false
            val above = if (isTp) c.buy else !c.buy // خرید: سود بالاتر از قیمت بازشدن. فروش: پایین‌تر
            val price = if (above) c.open + d else c.open - d
            val txt = String.format(Locale.US, "%." + c.decimals + "f", price)
            var lastGot: Double? = null

            for (method in 0 until 2) {
                val ok = if (method == 0) focusAndSet(isTp, txt) else focusAndPaste(isTp, txt)
                if (!ok) continue
                Thread.sleep(250)
                val cc = scanNow() ?: return false
                val got = if (isTp) cc.tpUsd else cc.slUsd
                val tv = if (isTp) cc.tpVal else cc.slVal
                if (got != null && tv != null && abs(tv - price) <= 0.011) {
                    if (abs(got - expected) <= tol) return true
                    lastGot = got
                }
            }

            // فقط برای نمادهای غیر بیت‌کوین: یک بار اصلاح دقیق K از روی لیبل واقعی نزدیک هدف
            if (btc || attempt == 1) return false
            val g = lastGot ?: return false
            if (g * expected <= 0 || abs(g) < 0.05) return false
            val k2 = abs(g) / (abs(price - c.open) * c.vol)
            if (k2 < 0.7 * k || k2 > 1.3 * k) return false
            k = k2
            prefs.edit().putFloat("kv_" + c.sym, k.toFloat()).apply()
        }
        return false
    }

    private fun clickConfirm(n: AccessibilityNodeInfo) {
        if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        tapCenter(n)
    }

    private fun tapCenter(n: AccessibilityNodeInfo) {
        val r = Rect()
        n.getBoundsInScreen(r)
        tapAt(r.exactCenterX(), r.exactCenterY())
    }

    private fun tapAt(x: Float, y: Float) {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        dispatchGesture(g, null, null)
    }

    /** دکمه + کنار فیلد (سمت چپ فیلد، هم‌ردیف) رو یک بار می‌زنه. */
    private fun pressPlus(c: Ctx, isTp: Boolean) {
        val root = rootInActiveWindow ?: return
        val fieldNode = (if (isTp) c.tpField else c.slField) ?: return
        val fr = Rect()
        fieldNode.getBoundsInScreen(fr)
        val tol = max(26, fr.height())
        var best: AccessibilityNodeInfo? = null
        var bestX = Int.MAX_VALUE
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 60) return
            if (n.isClickable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (abs(r.centerY() - fr.centerY()) <= tol && r.centerX() < fr.centerX() && r.centerX() < bestX) {
                    best = n
                    bestX = r.centerX()
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        val b = best
        if (b != null && b.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val x = if (b != null) {
            val r = Rect()
            b.getBoundsInScreen(r)
            r.exactCenterX()
        } else {
            fr.exactCenterX() - 0.1417f * resources.displayMetrics.widthPixels
        }
        tapAt(x, fr.exactCenterY())
    }

    // ------------------------------------------------------------------
    // خواندن صفحه
    // ------------------------------------------------------------------

    private fun collect(root: AccessibilityNodeInfo): List<Item> {
        val out = ArrayList<Item>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60 || out.size > 800) return
            val t = n.text?.toString() ?: n.contentDescription?.toString() ?: ""
            if (t.isNotEmpty() || n.isEditable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                out.add(Item(n, t, r))
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    private fun key(s: String): String =
        s.replace('\u064A', '\u06CC')
            .replace('\u0643', '\u06A9')
            .replace('\u0623', '\u0627')
            .replace(Regex("[\\s:\\u200c\\u200e\\u200f]"), "")

    private fun rowOf(all: List<Item>, label: Item): List<Item> {
        val tol = max(26, (label.r.height() * 0.8).toInt())
        return all.filter {
            it.n != label.n &&
                it.r.centerX() < label.r.centerX() &&
                abs(it.r.centerY() - label.r.centerY()) <= tol
        }.sortedBy { abs(it.r.centerY() - label.r.centerY()) }
    }

    private fun fixText(s: String): String =
        normalizeDigits(s)
            .replace('\u064A', '\u06CC')
            .replace('\u0643', '\u06A9')
            .replace('\u0623', '\u0627')
            .replace('\u200c', ' ')
            .replace("\u200f", "")
            .replace("\u200e", "")

    /**
     * ساختار واقعی صفحه ترندو (فلاتر): همه برچسب‌ها (حجم، شناسه، بازشده در، حد سود، حد ضرر) توی یک
     * گره ادغام‌شده هستن و سه تا EditText جدا برای حجم / حد سود / حد ضرر وجود داره.
     * دکمه پایین (بستن/تایید) توی درخت نیست؛ با مختصات نسبی زده میشه.
     */
    private fun scan(root: AccessibilityNodeInfo): Ctx? {
        val all = collect(root)
        val dlg = all.firstOrNull {
            val k = key(it.t)
            k.contains("حدسود") && k.contains("حدضرر")
        }
        val d = if (dlg != null) fixText(dlg.t)
        else {
            val blob = all.joinToString("\n") { fixText(it.t) }
            val k = key(blob)
            if (k.contains("حدسود") && k.contains("حدضرر")) blob else return null
        }

        val titlePart = d.substringBefore("حجم")
        val hasBuy = titlePart.contains("خرید")
        val hasSell = titlePart.contains("فروش")
        if (hasBuy == hasSell) return null
        val sym = titlePart.replace("خرید", "").replace("فروش", "").trim()
            .lines().firstOrNull { it.isNotBlank() }?.trim() ?: "x"

        val openRaw = Regex("باز\\s*شده\\s*در\\s*:?\\s*(-?[0-9]+(?:[.,/][0-9]+)?)")
            .find(d)?.groupValues?.get(1)
        val openStr = cleanNum(openRaw) ?: return null
        val open = openStr.toDouble()
        val decimals = max(2, openStr.substringAfter('.', "").length).coerceAtMost(6)
        val id = Regex("شناسه\\s*:?\\s*([0-9]+)").find(d)?.groupValues?.get(1)

        val eds = all.filter { it.n.isEditable }.sortedBy { it.r.centerY() }
        if (eds.size < 3) return null
        val volI = eds[eds.size - 3]
        val tpI = eds[eds.size - 2]
        val slI = eds[eds.size - 1]
        val vol = parseNum(volI.t) ?: 0.01
        if (vol <= 0.0) return null

        // عدد دلاری کنار هر فیلد: یا گره جدا هم‌ردیف، یا داخل متن ادغام‌شده
        val iTp = d.indexOf("حد سود")
        val iSl = d.indexOf("حد ضرر")
        var tpSeg = ""
        var slSeg = ""
        if (iTp >= 0 && iSl >= 0) {
            if (iTp < iSl) {
                tpSeg = d.substring(iTp, iSl)
                slSeg = d.substring(iSl).substringBefore("بستن")
            } else {
                slSeg = d.substring(iSl, iTp)
                tpSeg = d.substring(iTp).substringBefore("بستن")
            }
        }
        fun nearUsd(row: Item): Double? {
            val tol = max(30, (row.r.height() * 0.6).toInt())
            return all.firstNotNullOfOrNull { cand ->
                if (cand === dlg || cand.n.isEditable) null
                else if (abs(cand.r.centerY() - row.r.centerY()) <= tol) parseDollar(cand.t)
                else null
            }
        }
        val tpUsd = nearUsd(tpI) ?: parseDollar(tpSeg)
        val slUsd = nearUsd(slI) ?: parseDollar(slSeg)

        // دکمه "تایید" فقط بعد از تغییر فیلدها توی درخت ظاهر میشه (قبلش "بستن" است که توی درخت نیست)
        val confirm = all.firstOrNull { key(it.t) == "تایید" && it.n.isClickable }?.n
        return Ctx(
            buy = hasBuy,
            sym = sym,
            open = open,
            decimals = decimals,
            vol = vol,
            id = id,
            tpField = tpI.n,
            tpVal = parseNum(tpI.t) ?: 0.0,
            tpUsd = tpUsd,
            slField = slI.n,
            slVal = parseNum(slI.t) ?: 0.0,
            slUsd = slUsd,
            confirm = confirm
        )
    }

    // ------------------------------------------------------------------
    // تبدیل عدد (فارسی/عربی -> انگلیسی)
    // ------------------------------------------------------------------

    private fun normalizeDigits(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                in '\u06F0'..'\u06F9' -> sb.append('0' + (c - '\u06F0'))
                in '\u0660'..'\u0669' -> sb.append('0' + (c - '\u0660'))
                '\u066B' -> sb.append('.')
                '\u066C' -> {
                }
                '\u2212', '\u2013', '\u2014' -> sb.append('-')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun cleanNum(raw: String?): String? {
        if (raw == null) return null
        var t = normalizeDigits(raw).replace(Regex("[\\s\\u200e\\u200f\\u202a-\\u202e]"), "")
        if (t.contains(',') && t.contains('.')) {
            t = t.replace(",", "")
        } else if (Regex("^-?\\d+[/,]\\d+$").matches(t)) {
            t = t.replace('/', '.').replace(',', '.')
        }
        return if (Regex("^-?\\d+(\\.\\d+)?$").matches(t)) t else null
    }

    private fun parseNum(raw: String?): Double? = cleanNum(raw)?.toDoubleOrNull()

    /** "(-$۰٫۰۸)" -> -0.08 */
    private fun parseDollar(raw: String): Double? {
        val t = normalizeDigits(raw).replace('/', '.')
        if (!t.contains('$')) return null
        val m = Regex("\\d+(?:\\.\\d+)?").find(t) ?: return null
        val v = m.value.toDoubleOrNull() ?: return null
        return if (t.contains('-')) -v else v
    }

    // ------------------------------------------------------------------
    // پنل شناور
    // ------------------------------------------------------------------

    private fun applyEnabledLook() {
        val on = prefs.getBoolean("enabled", true)
        falconView?.alpha = if (on) 1f else 0.35f
        enabledBox?.let { if (it.isChecked != on) it.isChecked = on }
    }

    private fun lpWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radius.toFloat()
        return g
    }

    private fun mkBtn(label: String, color: Int, onClick: () -> Unit, onLong: (() -> Unit)? = null): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 16f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(12), dp(6), dp(12), dp(6))
        t.background = bg(color, dp(8))
        t.setOnClickListener { onClick() }
        if (onLong != null) t.setOnLongClickListener { onLong(); true }
        return t
    }

    private fun stepper(title: String, titleColor: Int, k: String, def: Float): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(3), 0, dp(3))
        val name = TextView(this)
        name.text = title
        name.setTextColor(titleColor)
        name.textSize = 15f
        name.minWidth = dp(48)
        val value = TextView(this)
        value.setTextColor(Color.WHITE)
        value.textSize = 16f
        value.gravity = Gravity.CENTER
        value.minWidth = dp(56)

        fun refresh() {
            value.text = String.format(Locale.US, "%.1f", prefs.getFloat(k, def))
        }

        fun change(delta: Float) {
            val v = (prefs.getFloat(k, def) + delta).coerceAtLeast(0.1f)
            prefs.edit().putFloat(k, Math.round(v * 10) / 10f).apply()
            refresh()
        }
        refresh()
        row.addView(name)
        row.addView(mkBtn("−", 0xFF555555.toInt(), { change(-1f) }, { change(-0.1f) }))
        row.addView(value)
        row.addView(mkBtn("+", 0xFF555555.toInt(), { change(1f) }, { change(0.1f) }))
        return row
    }

    private fun showPanel() {
        if (panel != null) return
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(4)
        lp.y = dp(220)

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL

        // دکمه شناور: فقط شهباز، بدون پس‌زمینه
        val bubble = FrameLayout(this)
        bubble.layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        val falcon = ImageView(this)
        falcon.setImageResource(R.drawable.shahbaz)
        falconView = falcon
        falcon.scaleType = ImageView.ScaleType.FIT_CENTER
        bubble.addView(falcon, FrameLayout.LayoutParams(dp(56), dp(56)))
        // نشانه کوچک نتیجه (✓ یا !) که چند ثانیه گوشه دکمه دیده میشه
        val badge = TextView(this)
        badge.textSize = 12f
        badge.gravity = Gravity.CENTER
        badge.setTextColor(Color.WHITE)
        badge.visibility = View.GONE
        val bl = FrameLayout.LayoutParams(dp(20), dp(20))
        bl.gravity = Gravity.TOP or Gravity.END
        bubble.addView(badge, bl)
        badgeView = badge
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.visibility = View.GONE
        content.setPadding(dp(8), dp(8), dp(8), dp(8))
        content.background = bg(0xEE202124.toInt(), dp(12))

        val en = CheckBox(this)
        en.text = "فعال"
        en.setTextColor(Color.WHITE)
        en.isChecked = prefs.getBoolean("enabled", true)
        en.setOnCheckedChangeListener { _, b ->
            prefs.edit().putBoolean("enabled", b).apply()
            falconView?.alpha = if (b) 1f else 0.35f
        }
        enabledBox = en
        content.addView(en, lpWrap())

        content.addView(stepper("TP \$", 0xFF66BB6A.toInt(), "tp", 5f), lpWrap())
        content.addView(stepper("SL \$", 0xFFEF5350.toInt(), "sl", 3f), lpWrap())

        val auto = CheckBox(this)
        auto.text = "خودکار"
        auto.setTextColor(Color.WHITE)
        auto.isChecked = prefs.getBoolean("auto", true)
        auto.setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("auto", b).apply() }
        content.addView(auto, lpWrap())

        val conf = CheckBox(this)
        conf.text = "تایید خودکار"
        conf.setTextColor(Color.WHITE)
        conf.isChecked = prefs.getBoolean("confirm", true)
        conf.setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("confirm", b).apply() }
        content.addView(conf, lpWrap())

        val st = TextView(this)
        st.setTextColor(0xFFBBBBBB.toInt())
        st.textSize = 12f
        st.maxWidth = dp(230)
        st.text = "آماده"
        statusView = st
        content.addView(st, lpWrap())

        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.addView(mkBtn("ست کن", 0xFF2E7D32.toInt(), { worker.execute { runApply() } }))
        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(dp(8), 1)
        btnRow.addView(spacer)
        btnRow.addView(mkBtn("تشخیص", 0xFF6A1B9A.toInt(), { worker.execute { dump() } }))
        content.addView(btnRow, lpWrap())

        box.addView(bubble)
        box.addView(content, lpWrap())

        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX
                    sy = e.rawY
                    ox = lp.x
                    oy = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt()
                    val dy = (e.rawY - sy).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        lp.x = ox + dx
                        lp.y = oy + dy
                        manager.updateViewLayout(box, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        content.visibility =
                            if (content.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        manager.updateViewLayout(box, lp)
                    }
                    true
                }
                else -> false
            }
        }

        manager.addView(box, lp)
        panel = box
        applyEnabledLook()
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "enabled") ui.post { applyEnabledLook() }
        }
        prefListener = l
        prefs.registerOnSharedPreferenceChangeListener(l)
    }

    // ------------------------------------------------------------------
    // ابزار عیب‌یابی: ساختار صفحه رو کپی می‌کنه تا بتونی برام بفرستی
    // ------------------------------------------------------------------

    private fun dump() {
        val root = rootInActiveWindow
        if (root == null) {
            toast("صفحه‌ای پیدا نشد")
            return
        }
        val sb = StringBuilder()
        val c = try {
            scan(root)
        } catch (e: Exception) {
            null
        }
        sb.append("SCAN: ")
        if (c == null) {
            sb.append("null (پنجره حد سود/ضرر شناسایی نشد)\n")
        } else {
            sb.append("buy=").append(c.buy).append(" sym=").append(c.sym)
                .append(" open=").append(c.open).append(" vol=").append(c.vol)
                .append(" id=").append(c.id)
                .append(" tpField=").append(c.tpField != null).append(" tpVal=").append(c.tpVal)
                .append(" tpUsd=").append(c.tpUsd)
                .append(" slField=").append(c.slField != null).append(" slVal=").append(c.slVal)
                .append(" slUsd=").append(c.slUsd).append('\n')
        }
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 40 || sb.length > 60000) return
            val r = Rect()
            n.getBoundsInScreen(r)
            sb.append("  ".repeat(d)).append(n.className)
                .append(" | t=").append(n.text)
                .append(" | d=").append(n.contentDescription)
                .append(" | edit=").append(n.isEditable)
                .append(" clk=").append(n.isClickable)
                .append(" | ").append(r.toShortString()).append('\n')
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dump", sb.toString()))
        toast("ساختار صفحه کپی شد؛ توی چت پیست کن")
    }

    private fun toast(msg: String) {
        ui.post {
            try {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
            }
            statusView?.text = msg
            badgeView?.let { b ->
                val good = msg.contains("✓")
                b.text = if (good) "✓" else "!"
                b.background = bg(if (good) 0xFF2E7D32.toInt() else 0xFFC62828.toInt(), dp(10))
                b.visibility = View.VISIBLE
                ui.postDelayed({ b.visibility = View.GONE }, 4000)
            }
        }
    }
}
