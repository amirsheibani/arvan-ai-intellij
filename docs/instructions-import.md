<div dir="rtl" lang="fa">

# قوانین، اسکیل‌ها و دکمهٔ انتقال

این سند توضیح می‌دهد که ArvanAi قوانین و اسکیل‌های پروژه را از کجا می‌خواند و دکمهٔ «انتقال قوانین و اسکیل‌ها» چه کاری انجام می‌دهد.

مرتبط: [ارتباط با API ابرآروان](arvan-api.md) · [README](../README.md)

---

## نمای کلی

<div dir="ltr">

```
[Skills/Rules tab: Import button]      [Tools › ArvanAi: Import Rules & Skills]
            │ scanInstructions                        │
            ▼                                         ▼
   ChatPanel.handleFromJs ──► InstructionsImporter.scan(projectDir)   (pooled thread)
            │ instructionsPreview                     │ Yes/No dialog
            ▼                                         ▼
   preview in webview ──importInstructions──► scan again ──► InstructionsImporter.move()
                                                         │  Files.move + rewrite + VFS refresh
                                                         ▼
                                     instructionsImported {moved, skipped} → refresh tabs
```

</div>

---

## ۱. ساختار `.arvanai/`

<div dir="ltr">

```
.arvanai/
  AGENTS.md                 main project instructions
  rules/**/*.md             always added to the system prompt
  skills/<name>/SKILL.md    run with /<name> args ($ARGUMENTS)
  commands/**/*.md          run with /<name>
  agents/**/*.md            only listed
```

</div>

معادل سطح کاربر در `~/.arvanai/` است. اگر اسکیل پروژه و اسکیل کاربر هم‌نام باشند، اسکیل پروژه برنده می‌شود.

## ۲. ساخت system prompt

پرامپت سیستم در هر درخواست از نو ساخته می‌شود (در `ChatPanel.sendMessage`) و این بخش‌ها را به همین ترتیب دارد:

1. پرامپت زبان (`Prompts.languagePrompt`)، اگر گزینهٔ پاسخ هم‌زبان با کاربر روشن باشد
2. `ProjectInstructions.systemPrompt()`: اول قوانین کاربر، بعد قوانین پروژه، و در آخر فهرست اسکیل‌ها
3. پرامپت اضافهٔ کاربر از تنظیمات

سقف هر فایل ۲۰ هزار کاراکتر و سقف کل ۶۰ هزار کاراکتر است. بیشتر از آن بریده می‌شود و یک یادداشت به انتهایش اضافه می‌شود.

## ۳. اجرای اسکیل

اگر پیام با `/name args` شروع شود و اسکیلی با آن نام وجود داشته باشد، `ProjectInstructions.expandSkill` بدنهٔ اسکیل را بدون front matter برمی‌گرداند. در آن بدنه `$ARGUMENTS` با `args` جایگزین می‌شود و همین متن به‌جای پیام کاربر فرستاده می‌شود.

## ۴. دکمهٔ انتقال

| منبع | مقصد |
|---|---|
| `.claude/rules/**/*.md` | `.arvanai/rules/…` |
| `.claude/skills/<name>/` (کل پوشه) | `.arvanai/skills/<name>/` |
| `.claude/commands/**`، `.claude/agents/**` | `.arvanai/commands/…`، `.arvanai/agents/…` |
| `AGENTS.md`، `CLAUDE.md`، `.claude/CLAUDE.md` | `.arvanai/AGENTS.md`؛ اگر از قبل وجود داشته باشد، `rules/project-memory*.md` |
| `CLAUDE.local.md` | `.arvanai/rules/local-memory.md` |
| `.cursor/rules/**`، `.cursorrules`، `.windsurfrules`، `.github/copilot-instructions.md` | `.arvanai/rules/…` |

- عملیات **انتقال** (move) است، نه کپی. پوشه‌های منبعی که خالی شوند حذف می‌شوند.
- در محتوای فایل‌های منتقل‌شده، ارجاع به `.claude/` به `.arvanai/` تغییر می‌کند و ارجاع به `CLAUDE.md` به `AGENTS.md`.
- `.claude/settings*.json` منتقل **نمی‌شوند**.
- اگر مقصد از قبل وجود داشته باشد، آن مورد رد می‌شود و منبعش سر جایش می‌ماند.
- symlinkها و مسیرهای بیرون از ریشهٔ پروژه نادیده گرفته می‌شوند. مسیرها هم هرگز از webview گرفته نمی‌شوند و Kotlin خودش دوباره اسکن می‌کند.

---

## فایل‌ها

| فایل | نقش |
|---|---|
| `context/InstructionsImporter.kt` | اسکن و انتقال؛ تنها فایلی که نام پوشه‌های ابزارهای دیگر را می‌داند |
| `context/ProjectInstructions.kt` | خواندن قوانین و اسکیل‌ها، ساخت system prompt، اجرای اسکیل |
| `actions/ImportInstructionsAction.kt` | اکشن منوی Tools |
| `ui/ChatPanel.kt` | پیام‌های `scanInstructions` / `importInstructions` و `skills` / `rules` |
| `webview/chat.js` | تب‌های Skills و Rules، پیش‌نمایش و دکمهٔ انتقال |

## چک‌لیست اشکال‌یابی

- **تب‌ها خالی است:** آیا `.arvanai/` در ریشهٔ پروژه (`project.basePath`) وجود دارد؟
- **بعد از انتقال، فایل‌ها در Project View دیده نمی‌شوند:** refresh مربوط به VFS ناهمگام است؛ یک بار **File › Reload All from Disk** بزنید.
- **یک مورد «target exists» گرفت:** فایل مقصد را بررسی و ادغام کنید و دوباره انتقال را بزنید.
- **لاگ:** تب Log، با فیلتر `import`.

</div>
