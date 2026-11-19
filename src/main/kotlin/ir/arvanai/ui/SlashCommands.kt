package ir.arvanai.ui

import ir.arvanai.I18n
import ir.arvanai.context.ProjectInstructions

/** Slash commands offered when the user types `/` in the chat input. */
object SlashCommands {

    data class Command(val name: String, val description: String, val source: String)

    private val BUILT_IN
        get() = listOf(
            Command("clear", I18n.tr("Start a new conversation", "شروع گفتگوی جدید"), "builtin"),
            Command("new", I18n.tr("Start a new conversation", "شروع گفتگوی جدید"), "builtin"),
        )

    /** Built-ins plus the skills found in `.arvanai/` (project and user). Blocking file I/O: call off the EDT. */
    fun list(projectDir: String?): List<Command> {
        val out = ArrayList(BUILT_IN)
        ProjectInstructions.skills(projectDir).forEach { out += Command(it.name, it.description, it.source) }
        return out.distinctBy { it.name }
    }
}
