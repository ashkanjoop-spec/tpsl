# ساخت APK بدون Android Studio (با GitHub)

1. توی github.com حساب بساز (رایگان؛ با VPN).
2. New repository > اسم دلخواه (مثلاً tpsl) > Create repository.
3. توی صفحه ریپو: "uploading an existing file". زیپ رو Extract کن و **محتویات** پوشه رو (نه خود پوشه رو) بکش داخل صفحه. باید `app`، `.github`، `settings.gradle.kts` و بقیه کنار هم باشن. بعد Commit changes.
4. تب **Actions** رو باز کن. ساخت خودکار شروع میشه (۳ تا ۸ دقیقه). وقتی تیک سبز شد، روش کلیک کن.
5. پایین صفحه، بخش **Artifacts**: `TpSl-apk` رو دانلود کن. داخل زیپ، فایل `app-debug.apk` هست. بفرستش به گوشی و نصب کن.
6. اگه ساخت ارور قرمز داد، روش کلیک کن و عکس لاگ (قسمت قرمز) رو بفرست.

بعد از نصب: فعال‌سازی و استفاده مثل قبل (Allow restricted settings روی اندروید ۱۳+، بعد Accessibility).
