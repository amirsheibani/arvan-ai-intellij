<div dir="rtl" lang="fa">

# ایجنت ترمینال (Aider) و پراکسی محلی

این سند توضیح می‌دهد پلاگین چطور ایجنت Aider را همراه graphify برای پروژه راه‌اندازی می‌کند و چطور Aider بدون دیدن کلید API به ابرآروان وصل می‌شود.

مرتبط: [ارتباط با API ابرآروان](arvan-api.md) · [قوانین و اسکیل‌ها](instructions-import.md) · [README](../README.md)

---

## نمای کلی

<div dir="ltr">

```
project opened ──► AgentStartupActivity ──(not set up)──► notification [Set up] [Don't ask again]
                                                              │
Settings › Tools › ArvanAi › "Set Up Agent" ─────────────────┤
                                                              ▼
                                         AgentSetup.run()   (Task.Backgroundable, cancellable)
                                           1. find uv / aider / graphify (PATH + ~/.local/bin, /opt/homebrew/bin …)
                                           2. uv tool install --python 3.12 aider-chat   (if missing, needs internet)
                                              uv tool install graphifyy                  (if missing)
                                           3. graphify aider install · graphify hook install · graphify update .
                                           4. write .aider.conf.yml (only if absent) + VFS refresh
                                                              │
Tools › ArvanAi: Open Agent ──► OpenAgentAction ──────────────┘ (runs setup first if needed)
        │ ApiKeyStore.get() present?
        │ LocalApiProxy.ensureStarted()  → http://127.0.0.1:<port>/v1
        │ <IDE temp>/arvanai/agent.env   → OPENAI_API_KEY=<session token>  (rw-------)
        ▼
  IDE terminal tab:  aider --env-file … --openai-api-base http://127.0.0.1:<port>/v1
                           --model custom_openai/<model> --no-show-model-warnings --no-check-update
        │
        ▼  Authorization: Bearer <session token>
  LocalApiProxy ──► {baseUrl}/…   Authorization: apikey <PasswordSafe key>   (SSE copied through)
```

</div>

---

## ۱. راه‌اندازی

- **اعلان هنگام باز شدن پروژه:** اگر `.aider.conf.yml` در پروژه نباشد یا `aider` پیدا نشود، اعلان نمایش داده می‌شود. «دیگر نپرس» تنظیم `agentSetupPrompt` را خاموش می‌کند. این تنظیم از Settings هم قابل تغییر است.
- **نصب بی‌صدا انجام نمی‌شود.** نصب فقط با کلیک کاربر شروع می‌شود و تا زمان کلیک، فایلی در پروژه ساخته نمی‌شود.
- **پیدا کردن ابزارها:** IDE وقتی از Dock باز شود PATH کاملی ندارد. برای همین `~/.local/bin`، `~/.cargo/bin`، `/opt/homebrew/bin` و `/usr/local/bin` هم جست‌وجو می‌شوند. فرایندهای فرزند هم همین PATH را می‌گیرند.
- **Python 3.12:** Aider 0.86 به scipy 1.15 وابسته است. scipy 1.15 برای Python 3.14 بستهٔ آماده ندارد و بدون کامپایلر Fortran نصب نمی‌شود.
- **بدون `uv`:** فقط اعلانی با لینک راهنمای نصب نمایش داده می‌شود. هیچ اسکریپتی از اینترنت اجرا نمی‌شود.
- **graphify:** دستور `graphify aider install` بخش graphify را به `AGENTS.md` اضافه می‌کند. `graphify hook install` گراف را بعد از هر commit به‌روز می‌کند و `graphify update .` گراف کد را می‌سازد. همهٔ این‌ها فقط ساختار کد را می‌خوانند و به LLM نیاز ندارند. استخراج معنایی اسناد با `/graphify` در یک دستیار انجام می‌شود.
- **`.aider.conf.yml`:** اگر از قبل وجود داشته باشد، بازنویسی نمی‌شود. فایل ساخته‌شده این‌ها را دارد: `AGENTS.md`، قوانین `.arvanai/rules/` و `GRAPH_REPORT.md` به‌صورت فقط‌خواندنی، اجازهٔ پیشنهاد دستور shell، و خاموش بودن commit خودکار و امضای AI مطابق `plugin-git.md`.
- **لغو:** دکمهٔ لغو نوار پیشرفت، فرایند در حال اجرا را می‌کشد. هر مرحله سقف زمانی هم دارد.

## ۲. اجرای ایجنت

- **کلید:** اگر کلید API تنظیم نشده باشد، فقط هشدار نمایش داده می‌شود.
- **مدل:** مقدار `agentModel` استفاده می‌شود. اگر خالی باشد، همان `model` چت به کار می‌رود. در قطعی اینترنت باید مدلی را گذاشت که داخل ایران میزبانی می‌شود.
- **پیشوند `custom_openai/`:** با پیشوند `openai/`، LiteLLM (داخل Aider) نام‌هایی مثل `GPT-5.2-Pro` را به `/v1/responses` می‌فرستد که ابرآروان ارائه نمی‌کند. با `custom_openai/` درخواست همیشه به `/chat/completions` می‌رود. این رفتار با یک سرور آزمایشی محلی بررسی شده است.
- **`--openai-api-base` در خط فرمان:** این مقدار بر `openai-api-base` داخل `.aider.conf.yml` پروژه اولویت دارد. پس حتی اگر کاربر آدرس ابرآروان را در آن فایل گذاشته باشد، درخواست‌ها از پراکسی رد می‌شوند.
- **رمز جلسه:** در فایل env نوشته می‌شود تا در تاریخچهٔ shell و فهرست فرایندها دیده نشود.

## ۳. پراکسی محلی (`LocalApiProxy`)

- سرویس سطح برنامه است و `HttpServer` خود JDK را فقط روی `127.0.0.1` با پورت تصادفی اجرا می‌کند. با بسته شدن IDE در `dispose` خاموش می‌شود.
- رمز جلسه ۳۲ بایت تصادفی است و با هر اجرای IDE عوض می‌شود. مقایسهٔ رمز در زمان ثابت انجام می‌شود (`MessageDigest.isEqual`). درخواست بدون رمز درست، پاسخ 401 می‌گیرد.
- فقط مسیرهای `/v1/*` و روش‌های GET و POST پذیرفته می‌شوند. فقط `Content-Type` و `Accept` منتقل می‌شوند و هدر `Authorization` جایگزین می‌شود.
- از همان `ArvanClient.client()` استفاده می‌کند. پس تنظیم HTTPS proxy پلاگین اعمال می‌شود و redirect غیرفعال است.
- پاسخ به‌صورت chunked منتقل می‌شود و بعد از هر قطعه flush می‌شود تا استریم SSE زنده بماند.
- لاگ فقط روش، مسیر و وضعیت را ثبت می‌کند (`proxy`). کلید و رمز هرگز لاگ نمی‌شوند.

---

## فایل‌ها

| فایل | نقش |
|---|---|
| `api/LocalApiProxy.kt` | پراکسی محلی، رمز جلسه، جایگزینی هدر، انتقال استریم |
| `api/ArvanClient.kt` | `client()` مشترک (`internal`) با تنظیم proxy |
| `ide/AgentSetup.kt` | پیدا کردن و نصب ابزارها، graphify، نوشتن `.aider.conf.yml`، اعلان‌ها |
| `ide/AgentStartupActivity.kt` | اعلان راه‌اندازی هنگام باز شدن پروژه |
| `actions/OpenAgentAction.kt` | اجرای Aider در تب ترمینال IDE |
| `META-INF/arvanai-terminal.xml` | ثبت اکشن، فقط وقتی پلاگین Terminal فعال است |
| `settings/ArvanAiSettings.kt` و `ArvanAiConfigurable.kt` | `agentModel`، `agentSetupPrompt`، دکمهٔ راه‌اندازی |

## چک‌لیست اشکال‌یابی

- **«uv لازم است»:** uv را نصب کنید (`brew install uv`) و دوباره راه‌اندازی را بزنید.
- **نصب Aider ناموفق:** تب Log را با فیلتر `agent` ببینید. مرحلهٔ نصب اینترنت لازم دارد.
- **اکشن Open Agent در منو نیست:** پلاگین Terminal در IDE غیرفعال است.
- **Aider خطای 401 می‌دهد:**
  - `Invalid session token` یعنی Aider از فایل env دیگری استفاده کرده است.
  - `No ArvanAi API key` یعنی کلید در تنظیمات نیست.
- **Aider خطای 400 می‌دهد:** پیام سرور را ببینید. مثلاً برخی مدل‌ها `temperature` را نمی‌پذیرند.
- **پاسخ نمی‌آید:** تب Log را با فیلتر `proxy` ببینید. باید `POST /v1/chat/completions -> 200` ثبت شده باشد.

</div>
