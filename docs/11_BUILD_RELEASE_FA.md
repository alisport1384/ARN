# ۱۱ · ساخت و انتشار (از صفر تا صد)

> این سند دقیقاً برای همان محیطی نوشته شده که پروژه در آن ساخته می‌شود: لینوکس، بدون اندروید استودیو،
> فقط JDK + Android SDK + Gradle.

## ۱) پیش‌نیازها
| ابزار | نسخه |
|---|---|
| JDK | ۱۷ (اجبار — بیلد با ۱۱ شکست می‌خورد) |
| Android SDK | platform **34** + build-tools **34.0.0** (platform-tools لازم نیست) |
| Gradle | ۸٫۷ (از طریق wrapper خود پروژه) |
| رم | حداقل ۱٫۵ گیگابایت آزاد + swap (این پروژه روی ۲GB/۶GB swap ساخته می‌شود) |
| شبکه | برای بار اول دانلود وابستگی‌ها (AGP 8.5.2، Kotlin 1.9.24) |

## ۲) نصب ابزار از صفر (امضاشده و تست‌شده)
```bash
# JDK 17
sudo apt-get update && sudo apt-get install -y openjdk-17-jdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Android SDK (کامندلاین)
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-*.zip && mv cmdline-tools latest && rm *.zip
export ANDROID_HOME=~/android-sdk

# بسته‌های لازم
yes | ~/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses
~/android-sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-34" "build-tools;34.0.0"

# Gradle 8.7
wget https://services.gradle.org/distributions/gradle-8.7-bin.zip
unzip -q gradle-8.7-bin.zip -d ~/gradle-dist && export PATH=~/gradle-dist/gradle-8.7/bin:$PATH
```
فایل `local.properties` در ریشهٔ پروژه:
```properties
sdk.dir=/home/USER/android-sdk
```

## ۳) بیلد پروژه
```bash
cd st
source tools/env.sh          # اگر وجود دارد: JAVA_HOME + ANDROID_HOME + PATH + swap
gradle :engine:test :app:testReleaseUnitTest :app:assembleRelease
```
خروجی: `app/build/outputs/apk/release/app-release.apk` (حدود ۱٫۴ مگابایت)

### اگر رم کم بود
```bash
sudo fallocate -l 6G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
# در gradle.properties پروژه:
org.gradle.jvmargs=-Xmx1200m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8
```
خطای `Inconsistent JVM-target compatibility` یعنی `jvmToolchain(17)` و `jvmTarget = "11"` حذف شده‌اند —
هرگز آن‌ها را برنگردانید (در `engine/build.gradle.kts` تنظیم شده است).

## ۴) امضای APK (کلید موجود در ریپو)
```bash
keytool -genkeypair -v -keystore keystore/goldpin.jks -alias goldpin \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass goldpin123 -keypass goldpin123 \
        -dname "CN=GoldPin, OU=GoldPin, O=GoldPin, L=Tehran, C=IR"
```
`signingConfigs` در `app/build.gradle.kts`:
```kotlin
signingConfigs {
    create("release") {
        storeFile = file("../keystore/goldpin.jks")
        storePassword = "goldpin123"; keyAlias = "goldpin"; keyPassword = "goldpin123"
    }
}
buildTypes { release { signingConfig = signingConfigs.getByName("release") } }
```
> نکتهٔ امنیتی: کلید نمونه برای تست است. برای انتشار عمومی، کلید تازه بسازید و رمز را **بیرون از ریپو**
> (متغیر محیطی) نگه دارید.

### بررسی امضا
```bash
~/android-sdk/build-tools/34.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
~/android-sdk/build-tools/34.0.0/aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head
```

## ۵) ساخت ریپوی گیت و شاخهٔ باندل (برای انتقال بین ماشین‌ها)
```bash
git init -b main && git add -A && git commit -m "GoldPin 1.1"
git bundle create /path/goldpin_v11_repo.bundle --all
# در ماشین دیگر:  git clone goldpin_v11_repo.bundle st
```

## ۶) انتشار در گیت‌هاب
```bash
cd st
git remote add origin https://github.com/alisport1384/st.git
# توکن را در URL ذخیره نکنید:
git -c credential.helper='!f() { echo "username=x-access-token"; echo "password=$GH_TOKEN"; }; f' push -u origin main:main
git remote set-url origin https://github.com/alisport1384/st.git
```
> **هرگز** توکن را داخل فایل‌ها، `origin` ذخیره‌شده یا تاریخچهٔ گیت نگذارید.

## ۷) اگر بیلد در جایی دیگر انجام شود (چک‌لیست)
1. `settings.gradle.kts` سه ماژول `:engine`, `:cli`, `:app` دارد.
2. `gradle/wrapper/gradle-wrapper.properties` روی ۸٫۷ است.
3. `local.properties` ساخته شده و به SDK درست اشاره می‌کند.
4. `keystore/goldpin.jks` موجود و رمز در `app/build.gradle.kts` هم‌خوان است.
5. `JAVA_HOME` روی JDK 17 است.
6. `gradle :engine:test` باید ۱۴ تست و `gradle :app:testReleaseUnitTest` باید ۲۳ تست را پاس کند
   (کل: **۳۷ تست / ۰ خطا**؛ جزئیات در سند ۱۲).

## ۸) تأیید بیلد از کلون تمیز (آزمایش‌شده)
```bash
git clone https://github.com/alisport1384/st.git buildtest && cd buildtest
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle :engine:test :app:testReleaseUnitTest :app:assembleRelease
```
نتیجهٔ ثبت‌شده: **BUILD SUCCESSFUL** با **۳۷ تست / ۰ خطا** و APK امضاشدهٔ نسخهٔ ۱٫۳٫۱
(`GoldPin-1.3.1-5.<sha>.apk` ، ۱٬۴۰۳٬۵۹۴ بایت) و همان اثر انگشت گواهی (`b88fc3db…27cba`)
⇒ مخزن عمومی از صفر بیلد می‌شود.

## ۹) نسخه‌گذاری
* `versionName` در `app/build.gradle.kts` (نسخهٔ فعلی: **1.4.10**)
* `versionCode` عدد صحیح افزایشی (۱٫۰ = 1، ۱٫۱ = 2، ۱٫۲ = 3، ۱٫۳ = 4، ۱٫۳٫۱ = 5)
* هر نسخه: یک ورودی در `13_CHANGELOG_FA.md` + APK در ریشهٔ پروژهٔ تحویل + باندل ریپو

## ۱۰) ساخت خودکار در GitHub Actions (بدون نیاز به سیستم محلی)

مسیر: [`.github/workflows/build.yml`](../.github/workflows/build.yml) — سه job:

| job | چه می‌کند | کی |
|---|---|---|
| `apk` | JDK 17 + Android SDK 34 → `:app:assembleRelease` → تغییر نام به `GoldPin-<نسخه>-<کد>.<sha>.apk` + `sha256` + **تأیید امضا با `apksigner`** → آپلود به‌عنوان Artifact | هر push به `main` ، هر PR ، دکمهٔ *Run workflow* ، هر tag `v*` |
| `test` | `:engine:test` + `:app:testReleaseUnitTest` + اجرای CLI روی دادهٔ نمونهٔ ۴۰٬۰۰۰ کندلی + آپلود گزارش تست | همراه build (قابل خاموش کردن در *Run workflow*) |
| `release` | همان APK را به یک **GitHub Release** با یادداشت خودکار پیوست می‌کند | فقط tagهای `v*` |

### گرفتن فایل نصبی
**راه ساده (Artifact):**
1. تب **Actions** در گیت‌هاب ← آخرین اجرای «Build APK» (یا دکمهٔ *Run workflow* ← *Run*).
2. پایین صفحه، بخش **Artifacts** ← `GoldPin-1.3.1-apk` را دانلود کن.
3. از zip بیرون بیاور؛ فایل `GoldPin-1.3.1-5.xxxxxxx.apk` نصب‌شدنی است.

**راه ماندگار (Release):**
```bash
git tag v1.3.1 && git push origin v1.3.1
```
بعد از سبز شدن workflow، فایل در **Releases** صفحهٔ مخزن است (Artifact پس از ۹۰ روز پاک
می‌شود ولی Release می‌ماند).

### نکته‌های مهم
* **هیچ Secret لازم نیست**: کلید امضا و رمزهایش داخل خود مخزن است (پروژهٔ شخصی).
  اگر روزی کلید را عوض کردی، `keystore/goldpin.jks` و `signingConfigs` را با هم به‌روز کن.
* آزمون‌ها عمداً در job جدا هستند تا خرابی آن‌ها مانع گرفتن فایل نصبی نشود؛
  نتیجه‌شان را در job `test` و Artifact `test-reports` ببین.
* بیلد محلی و CI هر دو از همان wrapper (`./gradlew` ، Gradle 8.7) استفاده می‌کنند.
* صحت فایل دانلودی را می‌توانی با فایل `.sha256` کنارش بررسی کنی:
  `sha256sum -c GoldPin-1.3.1-5.xxxxxxx.apk.sha256`
