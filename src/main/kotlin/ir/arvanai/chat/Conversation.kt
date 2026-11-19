package ir.arvanai.chat

/** One chat message in the OpenAI-compatible format (`role` = system | user | assistant). */
data class ChatMessage(val role: String, val content: String)

/**
 * Message history of one chat tab. The system prompt is not stored: it is rebuilt for every request
 * (project rules may have changed) and passed to [snapshot].
 */
class Conversation {

    private val turns = ArrayList<ChatMessage>()

    @Volatile
    var inFlight: Boolean = false

    @Synchronized
    fun addUser(text: String) {
        turns += ChatMessage("user", text)
        trim()
    }

    @Synchronized
    fun addAssistant(text: String) {
        turns += ChatMessage("assistant", text)
        trim()
    }

    /** Drops the last user message when its request failed or was cancelled before any reply. */
    @Synchronized
    fun dropLastUser() {
        if (turns.lastOrNull()?.role == "user") turns.removeAt(turns.size - 1)
    }

    @Synchronized
    fun reset() {
        turns.clear()
    }

    /** System prompt (if any) followed by the retained turns. */
    @Synchronized
    fun snapshot(systemPrompt: String): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(turns.size + 1)
        if (systemPrompt.isNotBlank()) out += ChatMessage("system", systemPrompt)
        out += turns
        return out
    }

    /** Keeps at most [MAX_MESSAGES] messages, dropping the oldest ones (the history always starts with a user turn). */
    private fun trim() {
        while (turns.size > MAX_MESSAGES) turns.removeAt(0)
        while (turns.isNotEmpty() && turns[0].role != "user") turns.removeAt(0)
    }

    companion object {
        const val MAX_MESSAGES = 40
    }
}
