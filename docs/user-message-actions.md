<div dir="rtl" lang="fa">

# دکمه‌های پیام کاربر و تب‌های چت

روی هر پیام کاربر دو دکمهٔ کپی و اجرای مجدد ظاهر می‌شود؛ تب‌های چت هم در یک ردیف قابل اسکرول‌اند و تبی که منتظر تأیید شماست چشمک می‌زند.

مرتبط: [فهرست مستندات](README.md) · [ایجنت چت](chat-agent.md)

---

## نمای کلی

<div dir="ltr">

```
send(text) ──► addUser(T, text)
                 ├─ d._text = text          (raw text, kept off the DOM)
                 └─ .uacts  [⧉ copy] [↻ rerun]

click [data-uact]
   ├─ copy  ──► toIde({ type: "copy", text }) ──► ChatPanel.handleFromJs "copy"
   └─ rerun ──► send(d._text)   (ignored while c.busy)

onToolApproval / decide / onToolEnd / closeTools
   └─► renderTabs()  ──► pill.waiting + dot.warn if ".tool .approval" is open
```

</div>

---

## کپی و اجرای مجدد

- دکمه‌ها فقط با بردن موس روی پیام دیده می‌شوند (`opacity`) و در انتهای ردیف می‌نشینند.
- **کپی**: متن خام پیام کپی می‌شود و دکمه یک ثانیه `✓` نشان می‌دهد.
- **اجرای مجدد**: همان متن دوباره در گفتگوی فعال ارسال می‌شود.
- وقتی گفتگو مشغول است، `syncBusyUi()` کلاس `busy` را روی `#chatLogs` می‌گذارد و دکمهٔ اجرای مجدد غیرفعال می‌شود؛ `send()` هم در حالت مشغول کاری نمی‌کند.

## استایل تب‌های چت و چشمک انتظار

- تب‌ها در یک ردیف (`nowrap`) با اسکرول افقی نازک (۴ پیکسل) هستند؛ هر تب فقط خط پایین دارد و تب فعال خط `--accent` و پس‌زمینهٔ `--accent-bg` می‌گیرد. دکمهٔ × همان رفتار قبلی را دارد.
- بعد از هر `renderTabs()` تب فعال با `scrollIntoView` دیده می‌شود. ترتیب تب‌ها تغییر نمی‌کند.
- اگر در لاگ یک گفتگو جعبهٔ تأیید باز باشد (`.tool .approval`)، تب آن کلاس `waiting` و نقطهٔ `warn` می‌گیرد و با انیمیشن `tabblink` چشمک می‌زند. `renderTabs()` هنگام افزودن و حذف جعبهٔ تأیید (تصمیم کاربر، پایان ابزار، پایان/خطای اجرا) صدا زده می‌شود.

## امنیت

متن پیام فقط با `textContent` رندر و در property جاوااسکریپت (`_text`) نگهداری می‌شود، نه در attribute یا `innerHTML`. پیام `copy` در `ChatPanel.kt` از قبل وجود داشت و Kotlin تغییری نکرد.

---

## فایل‌ها

| فایل | نقش |
|------|-----|
| `src/main/resources/webview/chat.js` | `.uacts` در `addUser()`، هندلر `[data-uact]`، کلاس `busy` در `syncBusyUi()`، حالت `waiting` در `renderTabs()` |
| `src/main/resources/webview/chat.css` | استایل `.uacts`، تب‌های `#chatTabs`، `.dot.warn`، انیمیشن `tabblink` |
| `gradle.properties` | نسخهٔ پلاگین 0.3.0 |

## چک‌لیست دیباگ

- دکمه دیده نمی‌شود؟ موس را روی پیام ببرید.
- اجرای مجدد کار نمی‌کند؟ بررسی کنید `c.busy` نباشد.
- تب چشمک نمی‌زند؟ ببینید `.tool .approval` در لاگ آن گفتگو هست و `renderTabs()` صدا زده شده است.

</div>
