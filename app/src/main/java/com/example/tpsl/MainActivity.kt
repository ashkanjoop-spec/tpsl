package com.example.tpsl

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private val bgColor = 0xFF12071D.toInt()
    private val cardColor = 0xFF211033.toInt()
    private val strokeColor = 0xFF3B2459.toInt()
    private val gold = 0xFFF6C434.toInt()
    private val crimson = 0xFFB3122B.toInt()
    private val textColor = 0xFFF1E9FA.toInt()
    private val subColor = 0xFFB9A9D0.toInt()
    private val green = 0xFF43A047.toInt()
    private val red = 0xFFE53935.toInt()
    private val darkText = 0xFF1B0B2E.toInt()

    private lateinit var p: SharedPreferences
    private lateinit var dot: View
    private lateinit var statusText: TextView
    private lateinit var tpEdit: EditText
    private lateinit var slEdit: EditText

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, radius: Int, stroke: Int? = null, oval: Boolean = false): GradientDrawable {
        val g = GradientDrawable()
        if (oval) g.shape = GradientDrawable.OVAL
        g.setColor(color)
        if (!oval) g.cornerRadius = radius.toFloat()
        if (stroke != null) g.setStroke(dp(1), stroke)
        return g
    }

    private fun lp(top: Int = 0, bottom: Int = 12): LinearLayout.LayoutParams {
        val l = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        l.setMargins(0, dp(top), 0, dp(bottom))
        return l
    }

    private fun label(s: String, size: Float = 15f, color: Int = textColor, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        c.background = shape(cardColor, dp(18), strokeColor)
        return c
    }

    private fun button(text: String, fill: Int, txt: Int, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = text
        b.textSize = 16f
        b.setTextColor(txt)
        b.gravity = Gravity.CENTER
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.setPadding(dp(16), dp(12), dp(16), dp(12))
        b.background = shape(fill, dp(14))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun toggle(text: String, key: String, def: Boolean): Switch {
        val s = Switch(this)
        s.text = text
        s.textSize = 16f
        s.setTextColor(textColor)
        s.isChecked = p.getBoolean(key, def)
        s.setPadding(0, dp(6), 0, dp(6))
        s.setOnCheckedChangeListener { _, b -> p.edit().putBoolean(key, b).apply() }
        return s
    }

    private fun numberField(key: String, def: Float, color: Int): EditText {
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        e.setText(String.format(Locale.US, "%.1f", p.getFloat(key, def)))
        e.textSize = 22f
        e.setTextColor(color)
        e.gravity = Gravity.CENTER
        e.setPadding(dp(12), dp(10), dp(12), dp(10))
        e.background = shape(0xFF160A24.toInt(), dp(12), strokeColor)
        e.setSelectAllOnFocus(true)
        return e
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = getSharedPreferences("tpsl", MODE_PRIVATE)
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(24), dp(18), dp(28))
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL

        // ---- هدر: شهباز + نام
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val img = ImageView(this)
        img.setImageResource(R.drawable.shahbaz)
        img.layoutParams = LinearLayout.LayoutParams(dp(76), dp(76))
        val titleBox = LinearLayout(this)
        titleBox.orientation = LinearLayout.VERTICAL
        titleBox.setPadding(dp(14), 0, dp(14), 0)
        titleBox.addView(label("Persia", 32f, gold, true))
        titleBox.addView(label("دستیار هوشمند حد سود و حد ضرر", 13f, subColor))
        header.addView(img)
        header.addView(titleBox)
        root.addView(header, lp(0, 18))

        // ---- وضعیت سرویس دسترسی
        val st = card()
        val stRow = LinearLayout(this)
        stRow.orientation = LinearLayout.HORIZONTAL
        stRow.gravity = Gravity.CENTER_VERTICAL
        dot = View(this)
        dot.layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
        statusText = label("", 16f, textColor, true)
        statusText.setPadding(dp(10), 0, dp(10), 0)
        stRow.addView(dot)
        stRow.addView(statusText)
        st.addView(stRow)
        st.addView(
            button("باز کردن تنظیمات دسترسی", gold, darkText) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            lp(12, 0)
        )
        root.addView(st, lp())

        // ---- کلید اصلی خاموش/روشن
        val master = card()
        master.addView(toggle("فعال بودن Persia", "enabled", true))
        master.addView(label("با خاموش کردن، ربات هیچ کاری نمی‌کنه و شهباز روی صفحه کم‌رنگ می‌شه.", 12f, subColor))
        root.addView(master, lp())

        // ---- مقادیر
        val vals = card()
        vals.addView(label("حد سود (TP) به دلار", 14f, subColor))
        tpEdit = numberField("tp", 5f, 0xFF66BB6A.toInt())
        vals.addView(tpEdit, lp(6, 12))
        vals.addView(label("حد ضرر (SL) به دلار", 14f, subColor))
        slEdit = numberField("sl", 3f, 0xFFEF5350.toInt())
        vals.addView(slEdit, lp(6, 12))
        vals.addView(
            button("ذخیره مقادیر", gold, darkText) {
                saveValues()
                Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
            },
            lp(4, 0)
        )
        root.addView(vals, lp())

        // ---- گزینه‌ها
        val opts = card()
        opts.addView(toggle("خودکار برای هر معامله جدید", "auto", true))
        opts.addView(toggle("تایید خودکار", "confirm", true))
        root.addView(opts, lp())

        // ---- راهنما
        val help = card()
        help.addView(label("اگه دسترسی خودش خاموش شد", 16f, gold, true))
        val tips = label(
            "اندروید اجازه نمی‌ده برنامه خودش دسترسی رو دوباره روشن کنه، و شیائومی وقتی برنامه رو از لیست اخیر پاک می‌کنی سرویس رو خاموش می‌کنه. برای جلوگیری:\n\n" +
                "۱) برنامه رو از لیست برنامه‌های اخیر با کشیدن نبند. روی کارتش نگه دار و قفل رو بزن.\n" +
                "۲) توی تنظیمات برنامه، شروع خودکار (Autostart) رو روشن و صرفه‌جویی باتری رو روی «بدون محدودیت» بذار.\n" +
                "۳) اگه بازم خاموش شد، با دکمه بالا فقط دوباره روشنش کن.",
            13f,
            textColor
        )
        tips.setLineSpacing(0f, 1.25f)
        tips.setPadding(0, dp(8), 0, dp(10))
        help.addView(tips)
        help.addView(
            button("تنظیمات برنامه (باتری و پس‌زمینه)", crimson, textColor) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName))
                )
            }
        )
        root.addView(help, lp())

        val sv = ScrollView(this)
        sv.setBackgroundColor(bgColor)
        sv.addView(root)
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        saveValues()
        super.onPause()
    }

    private fun saveValues() {
        val tp = tpEdit.text.toString().replace(',', '.').toFloatOrNull()
        val sl = slEdit.text.toString().replace(',', '.').toFloatOrNull()
        val e = p.edit()
        if (tp != null && tp > 0f) e.putFloat("tp", tp)
        if (sl != null && sl > 0f) e.putFloat("sl", sl)
        e.apply()
    }

    private fun serviceOn(): Boolean {
        return try {
            val en = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            en && list.contains("$packageName/")
        } catch (e: Exception) {
            false
        }
    }

    private fun refreshStatus() {
        val on = serviceOn()
        dot.background = shape(if (on) green else red, 0, null, true)
        statusText.text = if (on) "سرویس دسترسی روشنه ✓" else "سرویس دسترسی خاموشه"
    }
}
