package ir.arvanai.settings

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import ir.arvanai.api.ArvanClient
import javax.swing.DefaultComboBoxModel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import ir.arvanai.I18n.tr
import ir.arvanai.ide.GraphifySetup

class ArvanAiConfigurable : BoundConfigurable("ArvanAi") {

    private val s get() = ArvanAiSettings.get()
    private val keyField = JBPasswordField()
    private var removeKey = false
    private lateinit var baseUrlField: JBTextField
    private val modelCombo = ComboBox<String>().apply { isEditable = true }
    private val modelStatus = JBLabel().apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
    }
    /** Models fetched while this page is open; saved on apply. */
    private var fetchedModels: List<String>? = null

    /** What is in the model box, including text typed but not committed yet. */
    private fun modelText(): String =
        ((modelCombo.editor.item as? String) ?: (modelCombo.selectedItem as? String)).orEmpty().trim()

    private fun fillModels(models: List<String>, selected: String) {
        modelCombo.model = DefaultComboBoxModel(models.toTypedArray())
        modelCombo.selectedItem = selected
        modelCombo.editor.item = selected
        modelStatus.text = if (models.isEmpty()) {
            tr("Type a model name, or press Fetch Models to load the list from ArvanCloud.",
                "نام مدل را بنویسید یا «دریافت مدل‌ها» را بزنید تا فهرست از ابرآروان خوانده شود.")
        } else {
            tr("${models.size} chat models available for this API key.", "${models.size} مدل چت برای این کلید API در دسترس است.")
        }
    }

    private fun fetchModels() {
        val typedKey = String(keyField.password).trim()
        val base = baseUrlField.text.trim()
        try {
            val models = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<List<String>, Exception> { ArvanClient.listModels(typedKey, base) },
                tr("Fetching models from ArvanCloud…", "دریافت مدل‌ها از ابرآروان…"),
                true,
                null,
            )
            fetchedModels = models
            fillModels(models, modelText())
        } catch (e: ArvanClient.ApiException) {
            modelStatus.text = "<html>" + StringUtil.escapeXmlEntities(e.message.orEmpty()).replace("\n", "<br>") + "</html>"
        } catch (e: ProcessCanceledException) {
            // cancelled by the user
        }
    }

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
                baseUrlField = textField()
                    .align(AlignX.FILL)
                    .bindText(s::baseUrl)
                    .comment(tr("Requests go to <base URL>/chat/completions", "درخواست‌ها به <نشانی پایه>/chat/completions فرستاده می‌شوند"))
                    .component
            }
            row(tr("Model:", "مدل:")) {
                cell(modelCombo).resizableColumn().align(AlignX.FILL)
                button(tr("Fetch Models", "دریافت مدل‌ها")) { fetchModels() }
            }
            row("") {
                cell(modelStatus)
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
            row {
                checkBox(tr("Use MCP servers (.arvanai/mcp.json in the project, ~/.arvanai/mcp.json)", "استفاده از سرورهای MCP (.arvanai/mcp.json پروژه، ~/.arvanai/mcp.json)"))
                    .bindSelected(s::mcpEnabled)
                    .comment(tr("Project servers run only after you allow them. Type /mcp in the chat to see their status.",
                        "سرورهای پروژه فقط پس از اجازهٔ شما اجرا می‌شوند. برای دیدن وضعیت، در چت /mcp بنویسید."))
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
        group(tr("Code Graph (graphify, optional)", "گراف کد (graphify، اختیاری)")) {
            row {
                button(tr("Build Code Graph for Current Project", "ساخت گراف کد برای پروژهٔ فعلی")) {
                    val project = ProjectUtil.getActiveProject() ?: ProjectManager.getInstance().openProjects.firstOrNull()
                    if (project != null) GraphifySetup.run(project)
                }.comment(
                    tr(
                        "The chat agent already finds symbols, references and file relations with the IDE's index. graphify builds a code graph that is used when you ask with /graphify <question>. graphify and the graph live in the IDE's own folder (installed with uv on first use, needs internet); nothing in the project or in other tools is changed.",
                        "ایجنت چت خودش با index خود IDE نمادها، ارجاع‌ها و رابطهٔ فایل‌ها را پیدا می‌کند. graphify یک گراف کد می‌سازد که با ‎/graphify <سؤال> استفاده می‌شود. خود graphify و گراف در پوشهٔ مخصوص IDE نگه‌داری می‌شوند (بار اول با uv نصب می‌شود و اینترنت لازم است). در پروژه و ابزارهای دیگر چیزی تغییر نمی‌کند.",
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

    override fun isModified(): Boolean =
        super.isModified() || keyField.password.isNotEmpty() ||
            modelText().let { it.isNotEmpty() && it != s.model } ||
            fetchedModels.let { it != null && it != s.knownModels }

    override fun apply() {
        super.apply()
        modelText().takeIf { it.isNotEmpty() }?.let { s.model = it }
        fetchedModels?.let { s.knownModels = it.toMutableList() }
        fetchedModels = null
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
        fetchedModels = null
        fillModels(s.knownModels.toList(), s.model)
    }

    companion object {
        val LANGUAGES = listOf("en", "fa")
    }
}
