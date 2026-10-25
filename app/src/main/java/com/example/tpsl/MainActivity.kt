package com.example.tpsl

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences("tpsl", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL

        fun label(s: String): TextView {
            val t = TextView(this)
            t.text = s
            t.textSize = 16f
            t.setPadding(0, pad / 2, 0, pad / 4)
            return t
        }

        fun num(key: String, def: Float): EditText {
            val e = EditText(this)
            e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            e.setText(p.getFloat(key, def).toString())
            return e
        }

        root.addView(label("مرحله ۱: سرویس رو در تنظیمات دسترسی‌پذیری (Accessibility) روشن کن."))
        val acc = Button(this)
        acc.text = "باز کردن تنظیمات دسترسی‌پذیری"
        acc.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        root.addView(acc)

        root.addView(label("مرحله ۲: سود و ضرر هدف (دلار)"))
        root.addView(label("حد سود (TP) به دلار"))
        val tp = num("tp", 5f)
        root.addView(tp)
        root.addView(label("حد ضرر (SL) به دلار"))
        val sl = num("sl", 3f)
        root.addView(sl)

        val auto = CheckBox(this)
        auto.text = "خودکار (فقط وقتی هر دو فیلد 0.00 باشن و برای هر معامله یک بار)"
        auto.isChecked = p.getBoolean("auto", true)
        root.addView(auto)

        val save = Button(this)
        save.text = "ذخیره"
        save.setOnClickListener {
            p.edit()
                .putFloat("tp", tp.text.toString().toFloatOrNull() ?: 5f)
                .putFloat("sl", sl.text.toString().toFloatOrNull() ?: 3f)
                .putBoolean("auto", auto.isChecked)
                .apply()
            Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
        }
        root.addView(save)

        root.addView(label("مرحله ۳: دکمه ⚡ روی صفحه ظاهر میشه. با ضربه بازش کن؛ با کشیدن جابجاش کن. در پنل: ضربه روی + و − = ۱ دلار، نگه داشتن = ۰.۱ دلار."))

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
