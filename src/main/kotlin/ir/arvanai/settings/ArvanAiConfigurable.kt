package ir.arvanai.settings

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import ir.arvanai.I18n.tr
import ir.arvanai.ide.AgentSetup

class ArvanAiConfigurable : BoundConfigurable("ArvanAi") {

    private val s get() = ArvanAiSettings.get()
    private val keyField = JBPasswordField()
    private var removeKey = false

    override fun createPanel(): DialogPanel = panel {
        group(tr("Connection to ArvanCloud AI", "اتصال به هوش مصنوعی آروان‌کلود")) {
            row(tr("API key:", "کلید API:")) {
                cell(keyField)
                    .align(AlignX.FILL)
                    .comment(
                        tr(
                            "Stored in the IDE's secure storage (never in a settings file). Leave empty to keep the saved key.",
                            "در ذخیره‌گاه امن IDE نگه‌داری می‌شود (هرگز در فایل تنظیمات نیست). خالی = حفظ کلید ذخیره‌شده.",
                        )
                    )
            }
            row {
                checkBox(tr("Remove the saved API key when applying", "حذف کلید API ذخیره‌شده هنگام اعمال"))
                    .bindSelected({ removeKey }, { removeKey = it })
            }
            row(tr("Base URL:", "نشانی پایه:")) {
                textField()
                    .align(AlignX.FILL)
                    .bindText(s::baseUrl)
                    .comment(tr("Requests go to <base URL>/chat/completions", "درخواست‌ها به <نشانی پایه>/chat/completions فرستاده می‌شوند"))
            }
            row(tr("Model:", "مدل:")) {
                textField()
                    .bindText(s::model)
                    .comment(tr("Example: GPT-5.2-Pro", "مثال: GPT-5.2-Pro"))
            }
            row(tr("Max completion tokens:", "حداکثر توکن پاسخ:")) {
                intTextField(1..200_000).bindIntText(s::maxCompletionTokens)
            }
            row {
                checkBox(tr("Stream the reply while it is written", "نمایش زندهٔ پاسخ هنگام نوشته شدن (stream)"))
                    .bindSelected(s::streaming)
            }
            row(tr("HTTP proxy:", "پراکسی HTTP:")) {
                textField()
                    .align(AlignX.FILL)
                    .bindText(s::httpsProxy)
                    .comment(tr("Optional, e.g. http://127.0.0.1:10809", "اختیاری، مثال: http://127.0.0.1:10809"))
            }
        }
        group(tr("Chat Agent", "ایجنت چت")) {
            row {
                checkBox(tr("Let the assistant use tools (read and search files, edit, run commands)", "اجازهٔ استفاده از ابزار به دستیار (خواندن و جستجوی فایل، ویرایش، اجرای دستور)"))
                    .bindSelected(s::agentTools)
                    .comment(tr("The model must support function calling (tools).", "مدل باید از function calling (tools) پشتیبانی کند."))
            }
            row {
                checkBox(tr("Apply file edits without asking", "اعمال ویرایش فایل‌ها بدون پرسیدن"))
                    .bindSelected(s::autoApproveEdits)
            }
            row {
                checkBox(tr("Open the IDE diff viewer when an edit waits for approval", "باز کردن diff viewer خود IDE وقتی ویرایشی منتظر تأیید است"))
                    .bindSelected(s::showDiffOnApproval)
            }
            row {
                checkBox(tr("Run shell commands without asking", "اجرای دستورهای shell بدون پرسیدن"))
                    .bindSelected(s::autoApproveCommands)
            }
            row(tr("Max steps per message:", "حداکثر گام در هر پیام:")) {
                intTextField(1..200).bindIntText(s::maxAgentSteps)
            }
            row {
                checkBox(tr("Compact the conversation automatically when it gets long", "خلاصه‌سازی خودکار گفتگو وقتی طولانی می‌شود"))
                    .bindSelected(s::autoCompact)
            }
            row(tr("Context budget (tokens):", "بودجهٔ context (توکن):")) {
                intTextField(8_000..2_000_000).bindIntText(s::contextLimitTokens)
                    .comment(tr(
                        "Old tool outputs are dropped at 60% and older messages are summarized at 80%. Lower = cheaper. /compact does it now.",
                        "در ۶۰٪ خروجی ابزارهای قدیمی حذف و در ۸۰٪ پیام‌های قدیمی خلاصه می‌شوند. عدد کمتر = هزینهٔ کمتر. /compact همین الان انجام می‌دهد.",
                    ))
            }
        }
        group(tr("Terminal Agent (Aider)", "ایجنت ترمینال (Aider)")) {
            row(tr("Agent model:", "مدل ایجنت:")) {
                textField()
                    .bindText(s::agentModel)
                    .comment(
                        tr(
                            "Empty = same as the chat model. During an internet outage, use a model hosted inside Iran.",
                            "خالی = همان مدل چت. هنگام قطعی اینترنت، مدلی را بگذارید که داخل ایران میزبانی می‌شود.",
                        )
                    )
            }
            row {
                checkBox(tr("Offer the agent setup when opening a project", "پیشنهاد راه‌اندازی ایجنت هنگام باز کردن پروژه"))
                    .bindSelected(s::agentSetupPrompt)
            }
            row {
                button(tr("Set Up Agent for Current Project", "راه‌اندازی ایجنت برای پروژهٔ فعلی")) {
                    val project = ProjectUtil.getActiveProject() ?: ProjectManager.getInstance().openProjects.firstOrNull()
                    if (project != null) AgentSetup.run(project)
                }.comment(
                    tr(
                        "Installs Aider and graphify with uv if missing (needs internet), writes .aider.conf.yml, and builds the code graph. Open it with Tools › ArvanAi: Open Agent.",
                        "اگر Aider و graphify نصب نباشند با uv نصب می‌کند (نیاز به اینترنت)، .aider.conf.yml را می‌سازد و گراف کد را تولید می‌کند. اجرا: Tools › ArvanAi: باز کردن ایجنت.",
                    )
                )
            }
        }
        group(tr("Context", "زمینه (context)")) {
            row {
                checkBox(tr("Share the open file and selected text with the assistant", "اشتراک خودکار فایل باز و متن انتخاب‌شده با دستیار"))
                    .bindSelected(s::shareSelection)
            }
            row {
                label(tr("Extra system prompt:", "دستور سیستمی اضافه:"))
            }
            row {
                textArea()
                    .align(AlignX.FILL)
                    .applyToComponent { rows = 4 }
                    .bindText(s::systemPrompt)
            }
            row {
                comment(
                    tr(
                        "Sent after the project rules from .arvanai/ on every request.",
                        "پس از قوانین پروژه (پوشهٔ .arvanai/) در هر درخواست فرستاده می‌شود.",
                    )
                )
            }
        }
        group(tr("Language and Appearance", "زبان و ظاهر")) {
            row(tr("Interface language:", "زبان رابط کاربری:")) {
                comboBox(LANGUAGES, SimpleListCellRenderer.create("") { if (it == "fa") "فارسی (Persian)" else "English" })
                    .bindItem({ s.language }, { s.language = it ?: "en" })
                    .comment(tr("Also switchable from the globe button next to Send.", "از دکمهٔ کره کنار «ارسال» هم قابل تغییر است."))
            }
            row {
                checkBox(
                    tr(
                        "Reply in the language I write in (Persian or English)",
                        "پاسخ به همان زبانی باشد که می‌نویسم (فارسی یا انگلیسی)",
                    )
                ).bindSelected(s::matchReplyLanguage)
            }
            row {
                checkBox(tr("Convert Arabic \"ي\" and \"ك\" to Persian \"ی\" and \"ک\"", "تبدیل «ي» و «ك» عربی به «ی» و «ک» فارسی"))
                    .bindSelected(s::normalizeArabicChars)
            }
            row {
                checkBox(tr("Send with Enter (new line with Shift+Enter)", "ارسال با Enter (خط جدید با Shift+Enter)"))
                    .bindSelected(s::sendOnEnter)
            }
            row(tr("Font size:", "اندازه فونت:")) {
                intTextField(10..28).bindIntText(s::fontSize)
            }
        }
    }

    override fun isModified(): Boolean = super.isModified() || keyField.password.isNotEmpty()

    override fun apply() {
        super.apply()
        val typed = String(keyField.password).trim()
        val remove = removeKey
        keyField.text = ""
        removeKey = false
        reset()
        if (typed.isNotEmpty() || remove) {
            // PasswordSafe may talk to the OS keychain: never on the EDT.
            ApplicationManager.getApplication().executeOnPooledThread {
                ApiKeyStore.set(if (remove && typed.isEmpty()) null else typed)
            }
        }
    }

    override fun reset() {
        super.reset()
        keyField.text = ""
        removeKey = false
    }

    companion object {
        val LANGUAGES = listOf("en", "fa")
    }
}
