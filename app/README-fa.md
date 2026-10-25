# TP/SL Helper برای ترندو (اندروید)

## ساخت APK (یک بار)
1. Android Studio رو نصب کن (رایگان، روی کامپیوتر).
2. File > New > New Project > **Empty Views Activity**
   - Name: `TpSl`
   - Package name: `com.example.tpsl` (دقیقاً همین)
   - Language: Kotlin، Minimum SDK: API 24
3. فایل‌های این پوشه رو جایگزین کن:
   - `app/src/main/java/com/example/tpsl/MainActivity.kt` (فایل قبلی رو حذف/جایگزین کن)
   - `app/src/main/java/com/example/tpsl/TpSlService.kt` (جدید)
   - `app/src/main/AndroidManifest.xml` (جایگزین)
   - `app/src/main/res/xml/accessibility_service_config.xml` (پوشه xml رو بساز)
   - `app/src/main/res/values/strings.xml` (جایگزین)
4. Build > Build Bundle(s)/APK(s) > Build APK(s)
5. فایل: `app/build/outputs/apk/debug/app-debug.apk` رو به گوشی بفرست و نصب کن.

## فعال‌سازی روی گوشی
1. اگه اندروید ۱۳ یا بالاتر داری: Settings > Apps > TP SL Helper > ⋮ (سه نقطه بالا) > **Allow restricted settings**
2. Settings > Accessibility > Installed apps > TP SL Helper > روشن.
3. اپ رو باز کن، مقدار TP و SL (دلار) رو بزن و ذخیره کن.
4. در تنظیمات باتری (شیائومی: Battery saver) برای اپ **No restrictions** بذار و اپ رو توی recent قفل کن.

## استفاده
- دکمه ⚡ روی صفحه هست: ضربه = باز/بسته، کشیدن = جابجایی.
- در پنل: TP و SL با + و − عوض میشن (ضربه ۱ دلار، نگه داشتن ۰.۱ دلار).
- «خودکار» روشن باشه: هر وقت پنجره حد سود/ضرر یک معامله جدید باز بشه (هر دو فیلد 0.00)، خودش پر می‌کنه و تایید می‌زنه.
- «ست کن»: همین الان روی پنجره باز اجرا میشه (حتی اگه فیلدها پر باشن).
- «تشخیص»: ساختار صفحه رو کپی می‌کنه؛ اگه چیزی کار نکرد، پیست کن و بفرست.

## ایمنی
- هر فیلد بعد از تایپ، با عدد دلاری کنار خودش چک میشه. اگه با هدف نخونه یا علامتش (مثبت/منفی) برعکس باشه، **تایید زده نمیشه**.
- اول با حجم کم و حساب دمو تست کن.
