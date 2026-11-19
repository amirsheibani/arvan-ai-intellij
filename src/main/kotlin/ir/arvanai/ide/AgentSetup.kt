package ir.arvanai.ide

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import ir.arvanai.I18n.tr
import ir.arvanai.PluginLog
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sets up the terminal agent (Aider + graphify) for a project:
 * installs missing tools with `uv`, writes `.aider.conf.yml`, and builds the code graph.
 * Nothing secret is written: the agent reaches ArvanCloud through [ir.arvanai.api.LocalApiProxy].
 */
object AgentSetup {

    const val CONFIG_FILE = ".aider.conf.yml"
    const val OPEN_AGENT_ACTION = "ArvanAi.OpenAgent"
    private const val UV_URL = "https://docs.astral.sh/uv/getting-started/installation/"
    private const val INSTALL_TIMEOUT_MS = 20 * 60 * 1000
    private const val STEP_TIMEOUT_MS = 10 * 60 * 1000

    private val running = AtomicBoolean(false)

    /** Folders the IDE's PATH often misses when it is launched from the Dock / a desktop entry. */
    private fun extraDirs(): List<String> {
        val home = System.getProperty("user.home")
        return listOf("$home/.local/bin", "$home/.cargo/bin", "/opt/homebrew/bin", "/usr/local/bin")
    }

    /** PATH for child processes: the IDE's PATH plus [extraDirs]. */
    fun searchPath(): String =
        (System.getenv("PATH").orEmpty().split(File.pathSeparator) + extraDirs())
            .filter { it.isNotBlank() }.distinct().joinToString(File.pathSeparator)

    /** Absolute path of [name] on [searchPath], or null. Blocking (file system): not on the EDT. */
    fun findTool(name: String): String? {
        val names = if (SystemInfo.isWindows) listOf("$name.exe", "$name.cmd", name) else listOf(name)
        for (dir in searchPath().split(File.pathSeparator)) {
            for (n in names) {
                val f = File(dir, n)
                if (f.isFile && f.canExecute()) return f.absolutePath
            }
        }
        return null
    }

    /** True when the tools exist and the project has the agent config. Blocking: not on the EDT. */
    fun isSetUp(project: Project): Boolean {
        val base = project.basePath ?: return false
        return File(base, CONFIG_FILE).isFile && findTool("aider") != null
    }

    /**
     * Runs the whole setup in a background task with progress.
     * [onDone] is called on a background thread when the setup succeeded.
     */
    fun run(project: Project, onDone: (() -> Unit)? = null) {
        if (project.basePath == null) return
        // May be called from a pooled thread (Open Agent): start the task from the EDT.
        ApplicationManager.getApplication().invokeLater({ startTask(project, onDone) }, project.disposed)
    }

    private fun startTask(project: Project, onDone: (() -> Unit)?) {
        if (!running.compareAndSet(false, true)) {
            notify(project, tr("The agent setup is already running.", "راه‌اندازی ایجنت در حال اجراست."), NotificationType.INFORMATION)
            return
        }
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, tr("Setting up the ArvanAi agent", "راه‌اندازی ایجنت ArvanAi"), true) {
            override fun run(indicator: ProgressIndicator) {
                val ok = try {
                    setUp(project, indicator)
                } catch (e: Exception) {
                    PluginLog.warn("agent", "Setup failed: ${e.javaClass.simpleName}")
                    notify(project, tr("The agent setup failed. See the Log tab.", "راه‌اندازی ایجنت ناموفق بود. تب Log را ببینید."), NotificationType.ERROR)
                    false
                }
                if (ok) onDone?.invoke()
            }

            override fun onFinished() {
                running.set(false)
            }
        })
    }

    private fun setUp(project: Project, indicator: ProgressIndicator): Boolean {
        val base = File(project.basePath ?: return false)

        indicator.text = tr("Looking for tools…", "جست‌وجوی ابزارها…")
        var aider = findTool("aider")
        var graphify = findTool("graphify")
        if (aider == null || graphify == null) {
            val uv = findTool("uv")
            if (uv == null) {
                notify(
                    project,
                    tr(
                        "Installing the agent needs \"uv\" (Python tool manager). Install it, then run the setup again.",
                        "برای نصب ایجنت ابزار «uv» لازم است. آن را نصب کنید و دوباره راه‌اندازی را اجرا کنید.",
                    ),
                    NotificationType.WARNING,
                    NotificationAction.createSimpleExpiring(tr("How to install uv", "راهنمای نصب uv")) { BrowserUtil.browse(UV_URL) },
                )
                return false
            }
            if (aider == null) {
                indicator.text = tr("Installing Aider (needs internet)…", "نصب Aider (نیاز به اینترنت)…")
                // Python 3.12: some Aider dependencies (scipy) have no wheels for newer Pythons yet.
                if (!exec(listOf(uv, "tool", "install", "--python", "3.12", "aider-chat"), base, indicator, INSTALL_TIMEOUT_MS)) {
                    return fail(project, tr("Installing Aider failed.", "نصب Aider ناموفق بود."))
                }
                aider = findTool("aider") ?: return fail(project, tr("Aider was installed but not found.", "Aider نصب شد ولی پیدا نشد."))
            }
            if (graphify == null) {
                indicator.text = tr("Installing graphify (needs internet)…", "نصب graphify (نیاز به اینترنت)…")
                if (!exec(listOf(uv, "tool", "install", "graphifyy"), base, indicator, INSTALL_TIMEOUT_MS)) {
                    return fail(project, tr("Installing graphify failed.", "نصب graphify ناموفق بود."))
                }
                graphify = findTool("graphify") ?: return fail(project, tr("graphify was installed but not found.", "graphify نصب شد ولی پیدا نشد."))
            }
        }

        indicator.text = tr("Building the code graph…", "ساخت گراف کد…")
        // AGENTS.md section that tells the agent to use the graph; AST-only graph build (no LLM, no cost).
        if (!exec(listOf(graphify, "aider", "install"), base, indicator, STEP_TIMEOUT_MS)) PluginLog.warn("agent", "graphify aider install failed")
        if (File(base, ".git").exists() && !exec(listOf(graphify, "hook", "install"), base, indicator, STEP_TIMEOUT_MS)) {
            PluginLog.warn("agent", "graphify hook install failed")
        }
        if (!exec(listOf(graphify, "update", "."), base, indicator, STEP_TIMEOUT_MS)) PluginLog.warn("agent", "graphify update failed")

        indicator.text = tr("Writing the agent config…", "نوشتن تنظیمات ایجنت…")
        writeConfig(base)
        LocalFileSystem.getInstance().findFileByIoFile(base)?.let { VfsUtil.markDirtyAndRefresh(true, true, true, it) }

        PluginLog.info("agent", "Agent setup finished")
        notify(
            project,
            tr("The ArvanAi agent is ready.", "ایجنت ArvanAi آماده است."),
            NotificationType.INFORMATION,
            *openAgentAction(project),
        )
        return true
    }

    /** Writes `.aider.conf.yml` unless the project already has one (the user's file wins). */
    private fun writeConfig(base: File) {
        val file = File(base, CONFIG_FILE)
        if (file.exists()) return
        val reads = buildList {
            if (File(base, "AGENTS.md").isFile) add("AGENTS.md")
            File(base, ".arvanai/rules").walkTopDown().maxDepth(3)
                .filter { it.isFile && it.extension == "md" }
                .sortedBy { it.path }
                .forEach { add(it.relativeTo(base).invariantSeparatorsPath) }
            if (File(base, "graphify-out/GRAPH_REPORT.md").isFile) add("graphify-out/GRAPH_REPORT.md")
        }
        val text = buildString {
            appendLine("# Written by the ArvanAi plugin. Edit freely; the plugin does not overwrite this file.")
            appendLine("# The model and server come from Settings > Tools > ArvanAi (via the plugin's local proxy).")
            appendLine("edit-format: diff")
            if (reads.isNotEmpty()) {
                appendLine("read:")
                reads.forEach { appendLine("  - $it") }
            }
            appendLine("suggest-shell-commands: true")
            appendLine("auto-commits: false")
            appendLine("dirty-commits: false")
            appendLine("attribute-author: false")
            appendLine("attribute-committer: false")
            appendLine("gitignore: true")
        }
        file.writeText(text, UTF_8)
    }

    /** Runs a command with [searchPath]; killed when the task is cancelled or times out. */
    private fun exec(cmd: List<String>, dir: File, indicator: ProgressIndicator, timeoutMs: Int): Boolean {
        val line = GeneralCommandLine(cmd)
            .withWorkDirectory(dir)
            .withCharset(UTF_8)
            .withEnvironment("PATH", searchPath())
        PluginLog.info("agent", "run: ${cmd.drop(1).joinToString(" ")}")
        val out = CapturingProcessHandler(line).runProcessWithProgressIndicator(indicator, timeoutMs)
        if (out.isCancelled || out.isTimeout || out.exitCode != 0) {
            val tail = (out.stderr.ifBlank { out.stdout }).lines().filter { it.isNotBlank() }.takeLast(5).joinToString(" | ")
            PluginLog.warn("agent", "failed (exit=${out.exitCode}, timeout=${out.isTimeout}, cancelled=${out.isCancelled}): $tail")
            return false
        }
        return true
    }

    private fun fail(project: Project, text: String): Boolean {
        notify(project, text + " " + tr("See the Log tab.", "تب Log را ببینید."), NotificationType.ERROR)
        return false
    }

    /** "Open agent" button, only when the terminal integration is loaded. */
    fun openAgentAction(project: Project): Array<NotificationAction> {
        val action = ActionManager.getInstance().getAction(OPEN_AGENT_ACTION) ?: return emptyArray()
        return arrayOf(
            NotificationAction.createSimpleExpiring(tr("Open agent", "باز کردن ایجنت")) {
                if (!project.isDisposed) {
                    ActionUtil.invokeAction(action, SimpleDataContext.getProjectContext(project), ActionPlaces.NOTIFICATION, null, null)
                }
            }
        )
    }

    fun notify(project: Project, text: String, type: NotificationType, vararg actions: NotificationAction) {
        val n = NotificationGroupManager.getInstance().getNotificationGroup("ArvanAi").createNotification(text, type)
        actions.forEach { n.addAction(it) }
        n.notify(project)
    }
}
