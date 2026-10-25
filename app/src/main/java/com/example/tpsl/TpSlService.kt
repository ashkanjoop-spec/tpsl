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
            panel?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        panel = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::prefs.isInitialized) return
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
        if (busy) return
        busy = true
        try {
            Thread.sleep(350)
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
            var c = scanNow()
            var tries = 0
            while (c != null && c.confirm == null && tries < 8) {
                Thread.sleep(200)
                c = scanNow()
                tries++
            }
            if (c == null) {
                toast("پنجره بسته شد")
                return
            }
            val conf = c.confirm
            if (conf == null) {
                toast("دکمه تایید ظاهر نشد؛ خودت تایید رو بزن")
                return
            }
            clickConfirm(conf)
            Thread.sleep(700)
            val c2 = scanNow()
            if (c2 != null) {
                // هنوز بازه؛ یک بار با لمس مختصاتی روی همون دکمه تایید
                val conf2 = c2.confirm
                if (conf2 != null) tapCenter(conf2)
                Thread.sleep(700)
            }
            toast(if (scanNow() == null) "TP/SL ست شد ✓" else "تایید زده شد؛ نتیجه رو چک کن")
        } catch (e: Exception) {
            toast("خطا: " + e.message)
        } finally {
            busy = false
        }
    }

    /** یک فیلد (حد سود یا حد ضرر) رو روی مقدار دلاری مورد نظر ست می‌کنه و راستی‌آزمایی می‌کنه. */
    private fun setOne(isTp: Boolean, usd: Double): Boolean {
        var c = scanNow() ?: return false
        val kKey = "k_" + c.sym
        var k = prefs.getFloat(kKey, 1f).toDouble()
        val expected = if (isTp) usd else -usd
        val tol = max(0.02, 0.03 * usd)
        var prev: Double? = if (isTp) c.tpUsd else c.slUsd

        for (attempt in 0 until 3) {
            if (attempt == 0 && (if (isTp) c.tpUsd else c.slUsd) == null) {
                // تا یک بار + نزنی، لیبل دلاری نشون داده نمیشه؛ پس اول خودش + می‌زنه
                pressPlus(c, isTp)
                Thread.sleep(400)
                c = scanNow() ?: return false
                prev = if (isTp) c.tpUsd else c.slUsd
            }
            val d = usd / (c.vol * k)
            val above = if (isTp) c.buy else !c.buy // آیا قیمت هدف بالاتر از قیمت بازشدن باشه؟
            val price = if (above) c.open + d else c.open - d
            val field = (if (isTp) c.tpField else c.slField) ?: return false
            val txt = String.format(Locale.US, "%." + c.decimals + "f", price)
            if (!setFieldText(field, txt)) return false
            Thread.sleep(450)

            c = scanNow() ?: return false
            var got = if (isTp) c.tpUsd else c.slUsd
            if (got == null || got == prev) {
                // شاید لیبل دلاری تا فوکوس نره آپدیت نشه
                (if (isTp) c.tpField else c.slField)?.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                Thread.sleep(450)
                c = scanNow() ?: return false
                got = if (isTp) c.tpUsd else c.slUsd
            }
            if (got == null) return false
            if (got * expected < 0) return false // جهت اشتباه -> هیچ‌وقت تایید نکن
            if (abs(got - expected) <= tol) return true
            if (got == 0.0) return false
            // اصلاح ضریب (مثلاً سایز قرارداد نماد) بر اساس عدد واقعی که اپ نشون داد
            k = abs(got) / (abs(price - c.open) * c.vol)
            prefs.edit().putFloat(kKey, k.toFloat()).apply()
            prev = got
        }
        return false
    }

    private fun setFieldText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        // روش جایگزین: کلیک، انتخاب همه، چسباندن
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(400)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("p", text))
        val sel = Bundle()
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, node.text?.length ?: 0)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
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

    private fun scan(root: AccessibilityNodeInfo): Ctx? {
        val all = collect(root)
        val tpL = all.firstOrNull { key(it.t).startsWith("حدسود") } ?: return null
        val slL = all.firstOrNull { key(it.t).startsWith("حدضرر") } ?: return null
        val openL = all.firstOrNull { key(it.t).startsWith("بازشده") } ?: return null
        val volL = all.firstOrNull { key(it.t).startsWith("حجم") }
        val idL = all.firstOrNull { key(it.t).startsWith("شناسه") }
        // قبل از ویرایش، دکمه پایین "بستن $..." است (بستن معامله!). فقط متن دقیقاً "تایید" قبول است.
        val confirm = all
            .filter { key(it.t) == "تایید" && it.r.centerY() > slL.r.centerY() }
            .minByOrNull { it.r.centerY() }

        // جهت معامله از عنوان پنجره ("BITCOIN خرید" / "BITCOIN فروش")
        val limitY = (volL ?: tpL).r.centerY()
        val title = all.filter {
            val k = key(it.t)
            it.r.centerY() < limitY && (k.contains("خرید") || k.contains("فروش"))
        }.maxByOrNull { it.r.centerY() } ?: return null
        val tk = key(title.t)
        val hasBuy = tk.contains("خرید")
        val hasSell = tk.contains("فروش")
        if (hasBuy == hasSell) return null
        val sym = title.t.replace("خرید", "").replace("فروش", "").trim().ifEmpty { "x" }

        val openIt = rowOf(all, openL).firstOrNull { cleanNum(it.t) != null } ?: return null
        val openStr = cleanNum(openIt.t) ?: return null
        val open = openStr.toDouble()
        val decimals = max(2, openStr.substringAfter('.', "").length).coerceAtMost(6)

        val vol = volL?.let { l -> rowOf(all, l).firstNotNullOfOrNull { parseNum(it.t) } }
            ?: 0.01
        if (vol <= 0.0) return null

        val id = idL?.let { l -> rowOf(all, l).firstOrNull { cleanNum(it.t) != null }?.t }
            ?.let { cleanNum(it) }

        fun field(label: Item): Item? {
            val row = rowOf(all, label)
            return row.firstOrNull { it.n.isEditable } ?: row.firstOrNull { cleanNum(it.t) != null }
        }

        fun usd(label: Item): Double? {
            parseDollar(label.t)?.let { return it }
            return rowOf(all, label).firstNotNullOfOrNull { parseDollar(it.t) }
        }

        val tpF = field(tpL)
        val slF = field(slL)
        return Ctx(
            buy = hasBuy,
            sym = sym,
            open = open,
            decimals = decimals,
            vol = vol,
            id = id,
            tpField = tpF?.n,
            tpVal = tpF?.let { parseNum(it.t) ?: 0.0 },
            tpUsd = usd(tpL),
            slField = slF?.n,
            slVal = slF?.let { parseNum(it.t) ?: 0.0 },
            slUsd = usd(slL),
            confirm = confirm?.n
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

        val bubble = TextView(this)
        bubble.text = "⚡"
        bubble.textSize = 20f
        bubble.gravity = Gravity.CENTER
        bubble.setTextColor(Color.WHITE)
        bubble.background = bg(0xCC1E88E5.toInt(), dp(24))
        bubble.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.visibility = View.GONE
        content.setPadding(dp(8), dp(8), dp(8), dp(8))
        content.background = bg(0xEE202124.toInt(), dp(12))

        content.addView(stepper("TP \$", 0xFF66BB6A.toInt(), "tp", 5f), lpWrap())
        content.addView(stepper("SL \$", 0xFFEF5350.toInt(), "sl", 3f), lpWrap())

        val auto = CheckBox(this)
        auto.text = "خودکار"
        auto.setTextColor(Color.WHITE)
        auto.isChecked = prefs.getBoolean("auto", true)
        auto.setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("auto", b).apply() }
        content.addView(auto, lpWrap())

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
        ui.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
}
