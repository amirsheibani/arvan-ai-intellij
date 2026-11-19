package ir.arvanai.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import ir.arvanai.I18n.tr
import ir.arvanai.settings.ArvanAiSettings

/** On project open: offers a one-click agent setup if this project is not set up yet. */
class AgentStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode || ApplicationManager.getApplication().isHeadlessEnvironment) return
        if (!ArvanAiSettings.get().agentSetupPrompt || project.basePath == null) return
        // ProjectActivity runs off the EDT, so the file-system checks here are fine.
        if (AgentSetup.isSetUp(project)) return
        AgentSetup.notify(
            project,
            tr(
                "Set up the ArvanAi terminal agent (Aider + graphify) for this project? It uses your ArvanAi API key from the IDE; the key is never written to disk.",
                "ایجنت ترمینال ArvanAi (Aider و graphify) برای این پروژه راه‌اندازی شود؟ از کلید API ذخیره‌شده در IDE استفاده می‌کند و کلید هرگز روی دیسک نوشته نمی‌شود.",
            ),
            NotificationType.INFORMATION,
            NotificationAction.createSimpleExpiring(tr("Set up", "راه‌اندازی")) { AgentSetup.run(project) },
            NotificationAction.createSimpleExpiring(tr("Don't ask again", "دیگر نپرس")) {
                ArvanAiSettings.get().agentSetupPrompt = false
            },
        )
    }
}
