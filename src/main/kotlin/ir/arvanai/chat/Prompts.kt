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

    fun normalizePersian(s: String): String =
        s.replace('ي', 'ی') // ي -> ی
            .replace('ى', 'ی') // ى -> ی
            .replace('ك', 'ک') // ك -> ک
}
