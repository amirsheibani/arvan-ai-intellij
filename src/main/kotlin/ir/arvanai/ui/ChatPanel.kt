package ir.arvanai.ui

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.LafManagerListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import ir.arvanai.I18n
import ir.arvanai.PluginLog
import ir.arvanai.api.ArvanClient
import ir.arvanai.chat.Conversation
import ir.arvanai.chat.Prompts
import ir.arvanai.context.InstructionsImporter
import ir.arvanai.context.ProjectInstructions
import ir.arvanai.ide.GitInfo
import ir.arvanai.ide.ProjectFileSearch
import ir.arvanai.ide.SelectionTracker
import ir.arvanai.settings.ApiKeyStore
import ir.arvanai.settings.ArvanAiSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.Color
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.JComponent

/**
 * The ArvanAi tool window: a JCEF page hosting several chats (tabs). Each chat has its own
 * [Conversation]; replies come from [ArvanClient]. Messages to the page carry the chat id.
 */
class ChatPanel(private val project: Project) : Disposable {

    /** One request in flight. [finished] makes sure it is finalized exactly once (reply, error or stop). */
    private class Turn(val chat: String) {
        val partial = StringBuilder()
        val finished = AtomicBoolean(false)

        @Volatile
        var handle: ArvanClient.Handle? = null
    }

    private val gson = Gson()
    private val browser: JBCefBrowser = JBCefBrowser.createBuilder().setOffScreenRendering(false).build()
    private val jsQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    @Volatile
    private var pageReady = false
    private val pending = ConcurrentLinkedQueue<String>()
    private val lock = Any()

    private val conversations = ConcurrentHashMap<String, Conversation>()
    private val turns = ConcurrentHashMap<String, Turn>()
    private val tracker = SelectionTracker.getInstance(project)
    private val logListener: (PluginLog.Line) -> Unit = { post("logLine", lineJson(it)) }

    val component: JComponent get() = browser.component

    init {
        Disposer.register(this, browser)
        Disposer.register(this, jsQuery)

        jsQuery.addHandler { raw ->
            try {
                handleFromJs(JsonParser.parseString(raw).asJsonObject)
            } catch (e: Exception) {
                PluginLog.warn("bridge", "JS message failed: ${e.message}")
                post("error", JsonObject().apply { addProperty("text", I18n.tr("Internal error: ", "خطای داخلی: ") + e.message) })
            }
            null
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                cefBrowser.executeJavaScript(
                    "window.__toIde = function(m){ ${jsQuery.inject("m")} };",
                    cefBrowser.url, 0,
                )
                synchronized(lock) {
                    pageReady = true
                    while (true) {
                        val js = pending.poll() ?: break
                        cefBrowser.executeJavaScript(js, cefBrowser.url, 0)
                    }
                }
                sendInit()
                pooled { sendKeyStatus() }
            }
        }, browser.cefBrowser)

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { sendInit() })

        tracker.addListener(this) { snap -> post("context", contextJson(snap)) }
        PluginLog.addListener(logListener)

        browser.loadHTML(buildHtml())
    }

    private fun conversation(chat: String): Conversation = conversations.getOrPut(chat) { Conversation() }

    private fun chatJson(chat: String) = JsonObject().apply { addProperty("chat", chat) }

    // ---------------------------------------------------------------- IDE context (selection sharing)

    private fun contextJson(snap: SelectionTracker.Snapshot?) = JsonObject().apply {
        if (snap == null) return@apply
        addProperty("file", snap.relPath)
        addProperty("startLine", snap.startLine)
        addProperty("endLine", snap.endLine)
        addProperty("hasSelection", snap.text != null)
    }

    /** Prepends what the user is looking at in the IDE (selection sharing). */
    private fun withIdeContext(text: String): String {
        if (!ArvanAiSettings.get().shareSelection) return text
        val snap = tracker.snapshot() ?: return text
        val ctx = if (snap.text != null && snap.text.isNotBlank()) {
            "The user is viewing `${snap.relPath}` in the IDE and has selected lines ${snap.startLine}-${snap.endLine}:\n" +
                "```${snap.language}\n${snap.text}\n```"
        } else {
            "The user has `${snap.relPath}` open in the IDE (caret on line ${snap.startLine}). " +
                "It may or may not be related to the request."
        }
        return "<ide_context>\n$ctx\n</ide_context>\n\n$text"
    }

    // ---------------------------------------------------------------- public API (used by actions)

    /** Inserts text into the active chat's input box (does not send). */
    fun insertIntoInput(text: String, inline: Boolean = false) = post("insertInput", JsonObject().apply {
        addProperty("text", text)
        addProperty("inline", inline)
    })

    /** Sends a prompt in the active chat, as if the user typed it. */
    fun sendPrompt(text: String) = post("externalSend", JsonObject().apply { addProperty("text", text) })

    // ---------------------------------------------------------------- JS -> IDE

    private fun validChat(id: String) = CHAT_ID.matches(id)

    private fun handleFromJs(msg: JsonObject) {
        val chat = msg.get("chat")?.takeIf { it.isJsonPrimitive }?.asString ?: "c1"
        if (!validChat(chat)) return
        fun str(k: String) = msg.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        fun bool(k: String, def: Boolean) = msg.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: def

        when (msg.get("type")?.takeIf { it.isJsonPrimitive }?.asString) {
            "send" -> {
                val text = str("text")
                if (text.isBlank() || text.length > MAX_INPUT_CHARS) return
                sendMessage(chat, text, bool("shareContext", true), bool("expandRefs", true))
            }
            "stop" -> stopTurn(chat)
            "clearChat" -> {
                stopTurn(chat)
                conversations[chat]?.reset()
            }
            "closeChat" -> {
                stopTurn(chat)
                conversations.remove(chat)
            }
            "fileSearch" -> {
                val reqId = msg.get("reqId")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                val q = str("q")
                pooled {
                    val items = runCatching { ProjectFileSearch.getInstance(project).search(q) }.getOrDefault(emptyList())
                    post("fileResults", JsonObject().apply {
                        addProperty("reqId", reqId)
                        add("items", gson.toJsonTree(items))
                    })
                }
            }
            "recentFiles" -> ApplicationManager.getApplication().invokeLater { sendRecentFiles() }
            "setModel" -> str("value").trim().takeIf { MODEL_ID.matches(it) }?.let { ArvanAiSettings.get().model = it }
            "setAutoFollow" -> ArvanAiSettings.get().autoFollow = bool("value", true)
            "setLanguage" -> {
                ArvanAiSettings.get().language = if (str("value") == "fa") "fa" else "en"
                PluginLog.info("ui", "language: " + ArvanAiSettings.get().language)
            }
            "attach" -> attach(str("kind"))
            "listCommands" -> pooled {
                post("commands", JsonObject().apply { add("items", gson.toJsonTree(SlashCommands.list(project.basePath))) })
            }
            "copy" -> CopyPasteManager.getInstance().setContents(StringSelection(str("text")))
            "openUrl" -> str("url").takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { BrowserUtil.browse(it) }
            "insertCode" -> insertAtCaret(str("text"))
            "openFile" -> openFile(str("path"), msg.get("line")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0)
            "settings" -> ApplicationManager.getApplication().invokeLater {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "ArvanAi")
                sendInit()
                pooled { sendKeyStatus() }
            }

            // ----- Skills / Rules tabs
            "skills" -> pooled { sendSkills() }
            "rules" -> pooled { sendRules() }
            "scanInstructions" -> pooled { scanInstructions() }
            "importInstructions" -> pooled { importInstructions() }

            // ----- Git tab
            "git" -> pooled { post("git", GitInfo.overview(project.basePath)) }
            "ideAction" -> ApplicationManager.getApplication().invokeLater { runIdeAction(str("id")) }

            // ----- Log tab
            "log" -> post("log", JsonObject().apply {
                add("lines", JsonArray().apply { PluginLog.snapshot().forEach { add(lineJson(it)) } })
                addProperty("debug", PluginLog.debugEnabled)
            })
            "logDebug" -> {
                PluginLog.debugEnabled = bool("value", false)
                PluginLog.info("log", "debug logging " + if (PluginLog.debugEnabled) "on" else "off")
            }
        }
    }

    // ---------------------------------------------------------------- chat turns

    private fun sendMessage(chat: String, rawText: String, shareContext: Boolean, expandRefs: Boolean) {
        val conv = conversation(chat)
        val turn = Turn(chat)
        if (turns.putIfAbsent(chat, turn) != null) {
            post("error", chatJson(chat).apply { addProperty("text", I18n.tr("The previous reply is still running.", "پاسخ قبلی هنوز در حال اجراست.")) })
            return
        }
        sendInit()
        pooled(onFail = { finishTurn(turn) { post("error", chatJson(chat).apply { addProperty("text", I18n.tr("Could not send the message.", "ارسال پیام ناموفق بود.")) }) } }) {
            val s = ArvanAiSettings.get()
            val base = project.basePath
            // "/skill args" becomes the skill's instructions; other text keeps the IDE context.
            var text = rawText
            var isSkill = false
            if (rawText.startsWith("/")) {
                val name = rawText.substring(1).substringBefore(' ').substringBefore('\n')
                val args = rawText.substring(1 + name.length).trim()
                ProjectInstructions.expandSkill(base, name, args)?.let {
                    text = it
                    isSkill = true
                }
            }
            if (!isSkill && !rawText.startsWith("/")) {
                if (expandRefs) text = expandFileRefs(text)
                if (shareContext) text = withIdeContext(text)
            }
            if (s.normalizeArabicChars) text = Prompts.normalizePersian(text)

            if (turn.finished.get()) return@pooled
            conv.addUser(text)
            val system = buildSystemPrompt()
            val messages = conv.snapshot(system)
            post("busy", chatJson(chat).apply { addProperty("busy", true) })
            turn.handle = ArvanClient.send(messages, object : ArvanClient.Listener {
                override fun onDelta(text: String) {
                    if (turn.finished.get()) return
                    turn.partial.append(text)
                    post("delta", chatJson(chat).apply { addProperty("text", text) })
                }

                override fun onDone(fullText: String, usage: JsonObject?) = finishTurn(turn) {
                    conv.addAssistant(fullText)
                    post("done", chatJson(chat).apply { usage?.let { add("usage", usageJson(it)) } })
                }

                override fun onError(message: String) = finishTurn(turn) {
                    if (turn.partial.isNotEmpty()) conv.addAssistant(turn.partial.toString()) else conv.dropLastUser()
                    post("error", chatJson(chat).apply { addProperty("text", message) })
                }
            })
            // The user may have pressed Stop while the request was being set up.
            if (turn.finished.get()) turn.handle?.cancel()
        }
    }

    /** Runs [block] once for [turn] and then releases the chat for the next message. */
    private fun finishTurn(turn: Turn, block: () -> Unit) {
        if (!turn.finished.compareAndSet(false, true)) return
        try {
            block()
        } finally {
            turns.remove(turn.chat, turn)
            post("busy", chatJson(turn.chat).apply { addProperty("busy", false) })
        }
    }

    private fun stopTurn(chat: String) {
        val turn = turns[chat] ?: return
        turn.handle?.cancel()
        finishTurn(turn) {
            val conv = conversation(chat)
            if (turn.partial.isNotEmpty()) conv.addAssistant(turn.partial.toString()) else conv.dropLastUser()
            post("done", chatJson(chat).apply { addProperty("stopped", true) })
        }
    }

    /** Only the token counts the page needs. */
    private fun usageJson(u: JsonObject) = JsonObject().apply {
        for (k in listOf("prompt_tokens", "completion_tokens", "total_tokens")) {
            u.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let { add(k, it) }
        }
    }

    /** Language prompt + project rules + the user's own extra prompt. Built fresh for every request. */
    private fun buildSystemPrompt(): String {
        val s = ArvanAiSettings.get()
        return listOf(
            if (s.matchReplyLanguage) Prompts.languagePrompt(I18n.lang) else "",
            ProjectInstructions.systemPrompt(project.basePath),
            s.systemPrompt.trim(),
        ).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /** Appends the content of `@path/File.ext[#L1-9]` references that point at small text files inside the project. */
    private fun expandFileRefs(text: String): String {
        val root = project.basePath?.let { File(it).canonicalFile } ?: return text
        val seen = HashSet<String>()
        val blocks = StringBuilder()
        for (m in FILE_REF.findAll(text)) {
            if (seen.size >= MAX_REF_FILES) break
            val rel = m.groupValues[1].replace('\\', '/')
            val from = m.groupValues[2].toIntOrNull()
            val to = m.groupValues[3].toIntOrNull()
            if (!seen.add(m.value)) continue
            val f = try { File(root, rel).canonicalFile } catch (e: Exception) { continue }
            if (!f.isFile || !f.path.startsWith(root.path + File.separator) || f.length() > MAX_REF_BYTES) continue
            val content = try { f.readText() } catch (e: Exception) { continue }
            if (content.indexOf('\u0000') >= 0) continue
            val shown = if (from != null) {
                val lines = content.lines()
                lines.subList((from - 1).coerceIn(0, lines.size), (to ?: from).coerceIn(0, lines.size)).joinToString("\n")
            } else content
            blocks.append("<file path=\"").append(rel).append("\">\n```").append(f.extension).append('\n')
                .append(shown.trimEnd()).append("\n```\n</file>\n")
        }
        return if (blocks.isEmpty()) text else "$text\n\n<referenced_files>\n$blocks</referenced_files>"
    }

    // ---------------------------------------------------------------- skills, rules, import

    private fun sendSkills() = post("skills", JsonObject().apply {
        add("items", gson.toJsonTree(ProjectInstructions.skills(project.basePath)))
    })

    private fun sendRules() = post("rules", JsonObject().apply {
        add("items", gson.toJsonTree(ProjectInstructions.rules(project.basePath)))
    })

    private fun displayPath(f: File): String {
        val base = project.basePath ?: return f.path
        return try {
            f.relativeTo(File(base)).path.replace(File.separatorChar, '/').takeIf { !it.startsWith("..") } ?: f.path
        } catch (e: Exception) {
            f.path
        }
    }

    private fun scanInstructions() {
        val base = project.basePath
        val items = if (base == null) emptyList() else InstructionsImporter.scan(base)
        post("instructionsPreview", JsonObject().apply {
            add("items", JsonArray().also { arr ->
                items.forEach {
                    arr.add(JsonObject().apply {
                        addProperty("kind", it.kind)
                        addProperty("name", it.name)
                        addProperty("source", displayPath(it.source))
                        addProperty("target", displayPath(it.target))
                    })
                }
            })
        })
    }

    /** Re-scans on the Kotlin side: the page never supplies paths. */
    private fun importInstructions() {
        val base = project.basePath ?: return
        val res = InstructionsImporter.move(base, InstructionsImporter.scan(base))
        PluginLog.info("import", "moved ${res.moved.size}, skipped ${res.skipped.size}")
        post("instructionsImported", JsonObject().apply {
            addProperty("moved", res.moved.size)
            add("skipped", JsonArray().also { arr ->
                res.skipped.forEach { (item, reason) ->
                    arr.add(JsonObject().apply {
                        addProperty("name", item.name)
                        addProperty("reason", reason)
                    })
                }
            })
        })
        sendSkills()
        sendRules()
        post("commands", JsonObject().apply { add("items", gson.toJsonTree(SlashCommands.list(base))) })
    }

    private fun sendKeyStatus() {
        post("keyStatus", JsonObject().apply { addProperty("hasKey", ApiKeyStore.get() != null) })
    }

    private fun pooled(onFail: (() -> Unit)? = null, block: () -> Unit) = ApplicationManager.getApplication().executeOnPooledThread {
        try {
            block()
        } catch (e: Exception) {
            PluginLog.warn("bridge", "Background task failed: ${e.javaClass.simpleName}")
            onFail?.invoke()
        }
    }

    private fun lineJson(l: PluginLog.Line) = JsonObject().apply {
        addProperty("time", l.time)
        addProperty("level", l.level)
        addProperty("source", l.source)
        addProperty("message", l.message)
    }

    private fun relRef(f: VirtualFile): String {
        val base = project.guessProjectDir()
        return "@" + (base?.let { VfsUtilCore.getRelativePath(f, it) } ?: f.path)
    }

    /** 📎 menu: Files… / Directory… / Image… */
    private fun attach(kind: String) {
        ApplicationManager.getApplication().invokeLater {
            val descriptor: FileChooserDescriptor = when (kind) {
                "dir" -> FileChooserDescriptorFactory.createSingleFolderDescriptor()
                "image" -> FileChooserDescriptor(true, false, false, false, false, true)
                    .withFileFilter { it.extension?.lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp") }
                else -> FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
            }.withTitle(I18n.tr("ArvanAi: Add to Message", "ArvanAi: افزودن به پیام"))
            FileChooser.chooseFiles(descriptor, project, project.guessProjectDir()) { files ->
                val refs = files.joinToString(" ") { f -> relRef(f) + if (f.isDirectory) "/" else "" }
                if (refs.isNotBlank()) insertIntoInput("$refs ", inline = true)
            }
        }
    }

    private fun sendRecentFiles() {
        val files = runCatching { EditorHistoryManager.getInstance(project).fileList.reversed() }.getOrDefault(emptyList())
        post("recentFiles", JsonObject().apply {
            add("items", JsonArray().apply {
                files.filter { it.isInLocalFileSystem }.take(30).forEach { add(relRef(it).removePrefix("@")) }
            })
        })
    }

    private fun runIdeAction(id: String) {
        val am = ActionManager.getInstance()
        val action = am.getAction(id)
        if (action == null) {
            notify(
                I18n.tr(
                    "This action is not available in your IDE ($id). The Git plugin may be disabled.",
                    "این عمل در IDE شما در دسترس نیست ($id). شاید پلاگین Git فعال نباشد.",
                ),
                NotificationType.WARNING,
            )
            return
        }
        am.tryToExecute(action, null, browser.component, "ArvanAi", true)
    }

    private fun insertAtCaret(text: String) {
        ApplicationManager.getApplication().invokeLater {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor == null) {
                notify(I18n.tr("No editor is open.", "هیچ ویرایشگری باز نیست."), NotificationType.WARNING)
                return@invokeLater
            }
            WriteCommandAction.runWriteCommandAction(project, "Insert Code from ArvanAi", null, {
                val caret = editor.caretModel.primaryCaret
                if (caret.hasSelection()) {
                    editor.document.replaceString(caret.selectionStart, caret.selectionEnd, text)
                } else {
                    editor.document.insertString(caret.offset, text)
                }
            })
        }
    }

    private fun openFile(path: String, line: Int) {
        if (path.isBlank()) return
        ApplicationManager.getApplication().invokeLater {
            val f = File(path).let { if (it.isAbsolute) it else File(project.basePath ?: "", path) }
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f) ?: return@invokeLater
            OpenFileDescriptor(project, vf, maxOf(0, line - 1), 0).navigate(true)
        }
    }

    // ---------------------------------------------------------------- IDE -> JS

    private fun sendInit() {
        val s = ArvanAiSettings.get()
        post("init", JsonObject().apply {
            addProperty("dark", !JBColor.isBright())
            addProperty("bg", hex(UIUtil.getPanelBackground()))
            addProperty("fg", hex(UIUtil.getLabelForeground()))
            addProperty("fontSize", s.fontSize)
            addProperty("sendOnEnter", s.sendOnEnter)
            addProperty("projectPath", project.basePath ?: "")
            addProperty("model", s.model)
            addProperty("shareSelection", s.shareSelection)
            addProperty("autoFollow", s.autoFollow)
            addProperty("language", I18n.lang)
        })
    }

    private fun post(kind: String, payload: JsonObject) {
        val js = "window.bridge && window.bridge.receive(${gson.toJson(kind)}, ${gson.toJson(payload)});"
        synchronized(lock) {
            if (!pageReady) {
                pending.add(js)
                return
            }
        }
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
    }

    private fun notify(text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("ArvanAi")
            .createNotification(text, type).notify(project)
    }

    private fun buildHtml(): String {
        fun res(name: String): ByteArray =
            ChatPanel::class.java.getResourceAsStream("/webview/$name")?.use { it.readBytes() } ?: ByteArray(0)

        val html = String(res("chat.html"), Charsets.UTF_8)
        val css = String(res("chat.css"), Charsets.UTF_8)
        val js = String(res("chat.js"), Charsets.UTF_8)
        val font = Base64.getEncoder().encodeToString(res("Vazirmatn.woff2"))
        return html
            .replace("/*__CSS__*/", css.replace("__FONT_BASE64__", font))
            .replace("/*__JS__*/", js)
    }

    private fun hex(c: Color) = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

    override fun dispose() {
        PluginLog.removeListener(logListener)
        turns.values.forEach { it.finished.set(true); it.handle?.cancel() }
        turns.clear()
        conversations.clear()
        ChatPanelHolder.getInstance(project).let { if (it.panel === this) it.panel = null }
    }

    companion object {
        private val CHAT_ID = Regex("c\\d{1,6}")
        private val MODEL_ID = Regex("[\\w.:/\\- ]{1,100}")
        private val FILE_REF = Regex("(?<![\\w@])@([\\w.\\-/\\\\]+\\.[A-Za-z0-9]{1,8})(?:#L(\\d+)(?:-(\\d+))?)?")
        private const val MAX_INPUT_CHARS = 200_000
        private const val MAX_REF_FILES = 5
        private const val MAX_REF_BYTES = 200_000L
    }
}
