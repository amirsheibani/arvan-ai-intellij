package ir.arvanai.chat

/** Prompt helpers shared by the chat. */
object Prompts {

    /**
     * The user may write in Persian or English; the assistant mirrors the language of each message.
     * [uiLang] is only the fallback for messages with no clear language (e.g. a bare command).
     */
    fun languagePrompt(uiLang: String): String {
        val fallback = if (uiLang == "fa") "Persian (Farsi)" else "English"
        return "The user may write in Persian (Farsi) or English. Always reply in the language of the user's latest " +
            "message: Persian messages get fluent, natural Persian answers; English messages get English answers. " +
            "If the language of a message is unclear (for example only code, a path or a command), reply in $fallback. " +
            "When writing Persian, keep source code, identifiers, file paths, shell commands, and technical terms that " +
            "have no common Persian equivalent in English, use Persian punctuation (، ؛ ؟) and the zero-width non-joiner " +
            "(U+200C) where Persian orthography needs it, and never put Persian text inside code blocks except for string " +
            "literals or comments the user asked for. Do not mix both languages in one sentence unless quoting a term."
    }

    /**
     * Tells the model it is a coding agent inside the IDE with tools, so it explores the project itself instead of
     * asking the user to paste files. [topLevel] is a listing of the project root.
     */
    fun agentPrompt(projectRoot: String, topLevel: String, os: String, canEdit: Boolean, canRun: Boolean): String = buildString {
        append("You are ArvanAi, a coding agent running inside the user's JetBrains IDE. You work directly in the user's ")
        append("project at `").append(projectRoot).append("` (OS: ").append(os).append(").\n\n")
        append("# Tools\n")
        append("You have tools to explore the project: list_dir, glob, grep and read_file. ")
        if (canEdit) append("You can change files with edit_file and write_file. ")
        if (canRun) append("You can run shell commands (build, tests, git, CLI tools) with run_command. ")
        append("\n- Never ask the user to paste file contents or run commands you can run yourself: use the tools.\n")
        append("- Find code with glob/grep, then read only the relevant parts of files. Several independent tool calls can be made in one step.\n")
        append("- Before editing a file, read it. Keep edits minimal and in the existing style; old_string must match the file exactly.\n")
        append("- After changes, verify them when practical (build or tests) and report the result honestly.\n")
        append("- File edits and commands may need the user's approval; if a call is denied, do not retry it unchanged — adjust or ask.\n")
        append("- If the project root has AGENTS.md, CLAUDE.md or README.md and the task needs project knowledge, read it first.\n")
        append("- Paths are relative to the project root. Mention files as `path/to/File.kt:42` so the user can click them.\n\n")
        append("# Style\n")
        append("Be concise and direct. Do the work instead of describing what you would do; then summarize what you found or changed.\n\n")
        append("# Project root listing\n").append(topLevel)
    }

    /**
     * Normalizes common Arabic variants of Persian letters to their Persian forms (ی/ک),
     * to make downstream processing (matching, prompts, etc.) more consistent.
     */
    /** System prompt of the request that compacts older messages into a summary. */
    const val COMPACT_SYSTEM =
        "You compress the history of a conversation between a user and a coding agent working in the user's project, " +
            "so the agent can continue the work with only your summary. Keep everything needed to continue; drop chit-chat " +
            "and tool output that no longer matters. Write the summary in the language the user writes in; keep code, " +
            "paths and identifiers as they are."

    /** The user message of the compaction request: the previous summary, the messages to compact and the format. */
    fun compactRequest(previousSummary: String?, messages: List<ChatMessage>, focus: String): String = buildString {
        if (!previousSummary.isNullOrBlank()) {
            append("<previous_summary>\n").append(previousSummary.trim()).append("\n</previous_summary>\n\n")
        }
        append("<conversation>\n")
        val body = StringBuilder()
        for (m in messages) {
            when (m.role) {
                "user" -> body.append("\n[user]\n").append(m.content.take(MAX_MSG)).append('\n')
                "assistant" -> {
                    if (m.content.isNotBlank()) body.append("\n[assistant]\n").append(m.content.take(MAX_MSG)).append('\n')
                    m.toolCalls.forEach { body.append("[tool call] ").append(it.name).append(' ').append(it.arguments.take(MAX_ARGS)).append('\n') }
                }
                "tool" -> body.append("[tool result]\n").append(m.content.take(MAX_TOOL)).append('\n')
            }
        }
        // Keep the most recent part if the history is huge.
        append(if (body.length > MAX_BODY) "…(older part omitted)…\n" + body.takeLast(MAX_BODY) else body)
        append("</conversation>\n\n")
        append("Summarize the conversation above")
        if (!previousSummary.isNullOrBlank()) append(" together with the previous summary")
        append(" in these sections (omit empty ones):\n")
        append("1. User requests and goals — every request, in order, with exact wording for important constraints.\n")
        append("2. Decisions and preferences — what the user approved, rejected or asked to do differently.\n")
        append("3. Files — paths read or changed, and what was changed in each.\n")
        append("4. Commands and results — builds, tests, errors and how they were fixed.\n")
        append("5. Key facts about the code — architecture, names, line references the work depends on.\n")
        append("6. Current state and next steps — what was being done last and what is still open.\n")
        if (focus.isNotBlank()) append("\nPay special attention to: ").append(focus).append('\n')
        append("\nReply with the summary only.")
    }

    private const val MAX_MSG = 8_000
    private const val MAX_ARGS = 1_500
    private const val MAX_TOOL = 2_500
    private const val MAX_BODY = 300_000

    fun normalizePersian(s: String): String =
        s.replace('ي', 'ی') // ي -> ی
            .replace('ى', 'ی') // ى -> ی
            .replace('ك', 'ک') // ك -> ک
}
