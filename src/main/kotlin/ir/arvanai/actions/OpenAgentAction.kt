package ir.arvanai.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import ir.arvanai.I18n
import ir.arvanai.I18n.tr
import ir.arvanai.PluginLog
import ir.arvanai.api.LocalApiProxy
import ir.arvanai.ide.AgentSetup
import ir.arvanai.settings.ApiKeyStore
import ir.arvanai.settings.ArvanAiSettings
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Tools menu: opens Aider in an IDE terminal tab, talking to ArvanCloud through [LocalApiProxy].
 * Registered from `arvanai-terminal.xml`, so it only exists when the Terminal plugin is enabled.
 */
class OpenAgentAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
        e.presentation.text = I18n.tr("ArvanAi: Open Agent (Aider)", "ArvanAi: باز کردن ایجنت (Aider)")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (AgentSetup.isSetUp(project)) launch(project) else AgentSetup.run(project) { launch(project) }
        }
    }

    /** Background thread: checks the key, starts the proxy, writes the env file, then opens the terminal. */
    private fun launch(project: Project) {
        if (project.isDisposed) return
        val aider = AgentSetup.findTool("aider") ?: return
        if (ApiKeyStore.get() == null) {
            AgentSetup.notify(
                project,
                tr("No API key is set. Add it in Settings › Tools › ArvanAi.", "کلید API تنظیم نشده است. آن را در Settings › Tools › ArvanAi وارد کنید."),
                NotificationType.WARNING,
            )
            return
        }
        val s = ArvanAiSettings.get()
        val model = s.agentModel.trim().ifEmpty { s.model.trim() }
        val proxyUrl: String
        val envFile = try {
            proxyUrl = LocalApiProxy.get().ensureStarted()
            writeEnvFile(LocalApiProxy.get().token)
        } catch (ex: Exception) {
            PluginLog.warn("agent", "Could not start the local proxy: ${ex.javaClass.simpleName}")
            AgentSetup.notify(project, tr("Could not start the agent. See the Log tab.", "ایجنت اجرا نشد. تب Log را ببینید."), NotificationType.ERROR)
            return
        }
        // "custom_openai/": plain /chat/completions. With "openai/", LiteLLM sends some model names
        // (e.g. GPT-5.x-Pro) to the /responses API, which ArvanCloud does not serve.
        // --openai-api-base on the command line wins over a value in the project's .aider.conf.yml.
        val command = listOf(
            aider, "--env-file", envFile.absolutePath, "--openai-api-base", proxyUrl,
            "--model", "custom_openai/$model", "--no-show-model-warnings", "--no-check-update",
        ).joinToString(" ") { quote(it) }

        ApplicationManager.getApplication().invokeLater({
            try {
                @Suppress("DEPRECATION")
                val widget = TerminalToolWindowManager.getInstance(project)
                    .createLocalShellWidget(project.basePath, tr("ArvanAi Agent", "ایجنت ArvanAi"))
                widget.executeCommand(command)
                PluginLog.info("agent", "Agent opened (model=$model)")
            } catch (ex: Exception) {
                PluginLog.warn("agent", "Could not open the terminal: ${ex.javaClass.simpleName}")
                AgentSetup.notify(project, tr("Could not open the terminal. See the Log tab.", "ترمینال باز نشد. تب Log را ببینید."), NotificationType.ERROR)
            }
        }, project.disposed)
    }

    /**
     * Aider reads the session token from this file, so it is not typed into the shell (history)
     * or visible in the process list. The token only works for this IDE session.
     */
    private fun writeEnvFile(token: String): File {
        val dir = File(PathManager.getTempPath(), "arvanai").apply { mkdirs() }
        val file = File(dir, "agent.env")
        if (!file.exists()) {
            file.createNewFile()
        }
        if (!SystemInfo.isWindows) {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
        file.writeText("OPENAI_API_KEY=$token\n", UTF_8)
        return file
    }

    private fun quote(arg: String): String =
        if (SystemInfo.isWindows) "\"" + arg.replace("\"", "\\\"") + "\""
        else "'" + arg.replace("'", "'\\''") + "'"
}
