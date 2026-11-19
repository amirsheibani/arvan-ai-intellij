<div dir="rtl" lang="fa">

# ارتباط با API هوش مصنوعی ابرآروان

این سند توضیح می‌دهد که پیام از پنل گفتگو چطور به `api.arvancloudai.ir` می‌رسد و پاسخ چطور برمی‌گردد.

مرتبط: [راهنمای شروع](getting-started.md) · [قوانین، اسکیل‌ها و دکمهٔ کپی](instructions-import.md) · [README](../README.md)

---

## نمای کلی

<div dir="ltr">

```
chat.js  ──send {chat,text}──►  ChatPanel.sendMessage
                                   │ skill expand / @file refs / IDE context / ي→ی
                                   │ Conversation.snapshot(systemPrompt)
                                   ▼
                              ArvanClient.send()   (pooled thread)
                                   │ POST {baseUrl}/chat/completions
                                   │ Authorization: apikey <PasswordSafe>
                                   ▼
                       SSE (data: …) or JSON reply
                                   │
chat.js  ◄── delta / done / error / busy ──┘
```

</div>

---

## ۱. درخواست

<div dir="ltr">

```json
{"model": "GPT-5.2-Pro", "messages": [...], "max_completion_tokens": 8000, "stream": true}
```

</div>

- اگر تنظیم Streaming خاموش باشد، `stream` فرستاده نمی‌شود.
- کلید API فقط از `ApiKeyStore`، یعنی همان PasswordSafe خود IDE، خوانده می‌شود و هیچ‌وقت لاگ نمی‌شود.
- redirect غیرفعال است تا هدر کلید به میزبان دیگری نرود.
- پراکسی را از تنظیمات `HTTPS proxy` می‌گیرد.

## ۲. پاسخ

- **`text/event-stream`:** خط‌های `data:` خوانده می‌شوند و `choices[0].delta.content` به‌صورت `delta` به UI فرستاده می‌شود. با رسیدن `[DONE]`، رویداد `done` ارسال می‌شود.
- **JSON:** `choices[0].message.content` یک‌جا نمایش داده می‌شود.
- **`usage`:** اگر سرور `usage` بفرستد، مقدارهای `prompt_tokens` / `completion_tokens` / `total_tokens` نمایش داده می‌شوند. در حالت استریم ممکن است سرور `usage` نفرستد.

## ۳. خطاها

| وضعیت | پیام |
|---|---|
| بدون کلید | «کلید API تنظیم نشده است» |
| پاسخ شامل `debtor` (`Account is debtor`) | «حساب ابرآروان شما بدهکار است»، یعنی باید حساب را شارژ کرد |
| 401 | کلید نامعتبر |
| 403 (JSON) | دسترسی به مدل نیست یا اعتبار حساب تمام شده |
| 403 (صفحهٔ HTML) | فایروال یا پراکسی جلوی درخواست را گرفته |
| 404 | نشانی یا نام مدل اشتباه |
| 429 | سهمیه یا محدودیت تعداد درخواست |
| 5xx | خطای سرور |
| شبکه | بررسی شبکه یا پراکسی |

بعد از خطا، آخرین پیام کاربر از تاریخچه حذف می‌شود تا ارسال دوباره تکراری نشود. دکمهٔ توقف و همچنین بسته شدن پنل (`dispose`) درخواست در جریان را لغو می‌کنند.

## ۴. فهرست مدل‌ها

`ArvanClient.listModels()` درخواست `GET {baseUrl}/models` را با همان هدر `Authorization: apikey <key>` می‌فرستد. پاسخ OpenAI-compatible است (`{"object":"list","data":[{"id":"GLM-5.3",...}]}`) و فقط مدل‌هایی را برمی‌گرداند که gateway این کلید به آن‌ها دسترسی دارد.

- مدل‌های غیرچت (embedding، `Bge-m3`، `Whisper`، `Transcribe`، `TTS` و تولید تصویر) حذف و بقیه بر اساس نام مرتب می‌شوند.
- نتیجه در `ArvanAiSettings.knownModels` ذخیره می‌شود.
- **Settings › Tools › ArvanAi:** فیلد Model یک ComboBox قابل ویرایش است. دکمهٔ «دریافت مدل‌ها» از کلید و نشانی پایه‌ای استفاده می‌کند که در همان صفحه نوشته شده، حتی اگر هنوز Apply نشده باشد.
- **پنل چت:** منوی مدل فهرست را با امکان جستجو نشان می‌دهد. اولین بار که باز شود، فهرست را خودکار دریافت می‌کند و گزینهٔ «به‌روزرسانی فهرست مدل‌ها» هم دارد. نام دلخواه هم با Enter پذیرفته می‌شود.
- خطاها همان پیام‌های جدول بالا هستند، از جمله «حساب بدهکار است» برای پاسخ `Account is debtor`.

---

## فایل‌ها

| فایل | نقش |
|---|---|
| `api/ArvanClient.kt` | HTTP، خواندن SSE و JSON، لغو درخواست، متن خطاها |
| `chat/Conversation.kt` | تاریخچهٔ هر چت (حداکثر ۲۰۰ پیام و ۶۰۰ هزار نویسه؛ بعد از آن خلاصه‌سازی) |
| `chat/Prompts.kt` | پرامپت ایجنت، حالت طرح، graphify، پرامپت زبان، تبدیل «ي/ك» |
| `settings/ApiKeyStore.kt` | ذخیرهٔ کلید در PasswordSafe |
| `settings/ArvanAiSettings.kt` و `ArvanAiConfigurable.kt` | نشانی پایه، مدل و فهرست مدل‌ها (`knownModels`)، توکن، استریم، پراکسی |
| `ui/ChatPanel.kt` | پل JS ↔ Kotlin |

## چک‌لیست اشکال‌یابی

- **HTTP 401:** کلید را در Settings › Tools › ArvanAi دوباره وارد کنید.
- **«حساب بدهکار است»:** حساب را در پنل ابرآروان شارژ کنید. ساخت سرویس و کلید: [راهنمای شروع](getting-started.md).
- **پاسخ یک‌جا می‌آید، نه زنده:** سرور `stream` را نادیده گرفته است؛ این حالت مشکلی ایجاد نمی‌کند.
- **HTTP 400 به خاطر پارامترها:** Streaming را خاموش کنید یا `max_completion_tokens` را کم کنید.
- **لاگ:** تب Log، با فیلتر `api`.

</div>
