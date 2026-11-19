# ArvanAi — دستیار هوش مصنوعی ابرآروان داخل IDEهای JetBrains

پنل گفتگو با مدل‌های **هوش مصنوعی ابرآروان** (`api.arvancloudai.ir`) داخل IntelliJ IDEA، Android Studio، PyCharm، WebStorm و ...،
با نمایش درست متن فارسی، راست‌به‌چپ و متن‌های ترکیبی فارسی/انگلیسی.

## امکانات
- پنل گفتگوی راست‌به‌چپ با فونت **وزیرمتن** (داخل پلاگین)
- اتصال مستقیم به API سازگار با OpenAI ابرآروان (`/v1/chat/completions`) با نمایش زندهٔ پاسخ (streaming)
- هر پاراگراف جهت خودش را از اولین حرف تشخیص می‌دهد؛ کد و مسیر فایل‌ها همیشه LTR
- دکمه‌های **کپی** و **درج در ویرایشگر** برای هر بلوک کد
- ارسال فایل/انتخاب فعلی ویرایشگر همراه پیام
- **قوانین و اسکیل‌های پروژه** از پوشهٔ `.arvanai/` (قوانین به system prompt اضافه می‌شوند، اسکیل‌ها با `/نام` اجرا می‌شوند)
- دکمهٔ **«انتقال قوانین و اسکیل‌ها»**: ریشهٔ پروژه را می‌گردد و قوانین/اسکیل‌های ابزارهای دیگر را به `.arvanai/` منتقل می‌کند
- کلید API در Password Safe خود IDE ذخیره می‌شود، نه در فایل تنظیمات
- **ایجنت ترمینال (Aider):** با یک کلیک Aider و graphify نصب و برای پروژه تنظیم می‌شوند. **Tools › ArvanAi: Open Agent** آن را در ترمینال IDE باز می‌کند. Aider از طریق یک پراکسی محلی به ابرآروان وصل می‌شود و کلید را نمی‌بیند. جزئیات: [docs/agent-aider.md](docs/agent-aider.md)

## پیش‌نیاز
1. یک **کلید API** از پنل هوش مصنوعی ابرآروان
2. JDK 21 برای build (IDE باید نسخهٔ 2024.2 یا جدیدتر باشد)

## ساخت و نصب
```bash
JAVA_HOME="/Applications/IntelliJ IDEA CE.app/Contents/jbr/Contents/Home" ./gradlew buildPlugin
```
فایل `build/distributions/arvan-ai-intellij-0.1.0.zip` ساخته می‌شود.
در IDE: **Settings › Plugins › ⚙ › Install Plugin from Disk…** و انتخاب همان zip.

برای اجرای یک IDE آزمایشی همراه با پلاگین:
```bash
./gradlew runIde
```

## تنظیمات
**Settings › Tools › ArvanAi**

| تنظیم | پیش‌فرض | توضیح |
|---|---|---|
| API key | — | در Password Safe ذخیره می‌شود؛ هدر `Authorization: apikey <key>` |
| Base URL | `https://api.arvancloudai.ir/v1` | |
| Model | `GPT-5.2-Pro` | نام مدل در ابرآروان |
| Max completion tokens | `8000` | |
| Streaming | روشن | ارسال `"stream": true` |
| HTTPS proxy | — | مثلاً `http://127.0.0.1:10809` |

## قوانین و اسکیل‌ها (`.arvanai/`)
```
.arvanai/
  AGENTS.md                 دستورالعمل اصلی پروژه
  rules/**/*.md             قوانین (همیشه به system prompt اضافه می‌شوند)
  skills/<name>/SKILL.md    اسکیل‌ها؛ با /<name> آرگومان‌ها اجرا می‌شوند ($ARGUMENTS)
  commands/**/*.md          دستورهای ساده؛ با /<name>
```
معادل سطح کاربر در `~/.arvanai/` است.

جزئیات دکمهٔ انتقال: [docs/instructions-import.md](docs/instructions-import.md)

## ساختار پروژه
```
src/main/kotlin/ir/arvanai/
  api/ArvanClient.kt            درخواست HTTP به ابرآروان، استریم SSE
  chat/                         تاریخچهٔ گفتگو، پرامپت زبان
  context/                      قوانین/اسکیل‌های .arvanai و انتقال‌دهنده
  ui/ChatPanel.kt               JCEF browser + پل JS ↔ Kotlin
  settings/                     تنظیمات + ذخیرهٔ کلید در Password Safe
  actions/                      اکشن‌های منو
src/main/resources/webview/
  chat.html / chat.css / chat.js  رابط گفتگو (markdown، RTL، استریم)
  Vazirmatn.woff2                 فونت (مجوز OFL)
```

## ایده‌های توسعه
- ابزارهای ایجنتی (خواندن/ویرایش فایل) از طریق `tools` در API، اگر مدل پشتیبانی کند
- نمایش diff ویرایش‌ها با Diff Viewer خود IDE
- ذخیرهٔ تاریخچهٔ گفتگوها
