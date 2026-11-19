package ir.arvanai.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "ArvanAiSettings", storages = [Storage("arvan-ai.xml")])
class ArvanAiSettings : PersistentStateComponent<ArvanAiSettings.State> {

    class State {
        /** OpenAI-compatible base URL of the ArvanCloud AI API (without `/chat/completions`). */
        var baseUrl: String = "https://api.arvancloudai.ir/v1"
        var model: String = "GPT-5.2-Pro"
        var maxCompletionTokens: Int = 8000
        /** Ask the server for a streamed (SSE) reply. */
        var streaming: Boolean = true
        /** Extra system prompt appended after the language prompt and the project rules. */
        var systemPrompt: String = ""
        /** Share the current file / selection with the assistant on every message. */
        var shareSelection: Boolean = true
        /** Keep the chat scrolled to the bottom while the assistant writes. */
        var autoFollow: Boolean = true

        /** UI language: "en" (default) or "fa". */
        var language: String = "en"
        /** Ask the assistant to answer in the language the user writes in (Persian or English). */
        var matchReplyLanguage: Boolean = true
        /** Replace Arabic ي/ك with Persian ی/ک before sending. */
        var normalizeArabicChars: Boolean = true
        /** Optional HTTP(S) proxy, e.g. http://127.0.0.1:10809 */
        var httpsProxy: String = ""
        var fontSize: Int = 14
        var sendOnEnter: Boolean = true

        /** Chat works as an agent: the model may call tools (read/search files, edit, run commands). */
        var agentTools: Boolean = true
        /** Apply file edits without asking. */
        var autoApproveEdits: Boolean = false
        /** Run shell commands without asking. */
        var autoApproveCommands: Boolean = false
        /** Open IntelliJ's diff viewer automatically when an edit waits for approval. */
        var showDiffOnApproval: Boolean = true
        /** Maximum model requests (tool rounds) per message. */
        var maxAgentSteps: Int = 40
        /** Compact the conversation automatically when it gets close to [contextLimitTokens]. */
        var autoCompact: Boolean = true
        /** Context budget in tokens; compaction starts at 60% of it. Lower = cheaper requests. */
        var contextLimitTokens: Int = 100_000

        /** Model for the terminal agent (Aider); empty = same as [model]. */
        var agentModel: String = ""
        /** Offer to set up the agent when a project that is not set up yet is opened. */
        var agentSetupPrompt: Boolean = true
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    companion object {
        fun get(): State =
            ApplicationManager.getApplication().getService(ArvanAiSettings::class.java).state
    }
}
