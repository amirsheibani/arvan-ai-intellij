package ir.arvanai.api

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import ir.arvanai.I18n
import ir.arvanai.PluginLog
import ir.arvanai.chat.ChatMessage
import ir.arvanai.settings.ApiKeyStore
import ir.arvanai.settings.ArvanAiSettings
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * Talks to the ArvanCloud AI chat-completions API (OpenAI-compatible):
 * `POST {baseUrl}/chat/completions` with `Authorization: apikey <key>`.
 * Handles both server-sent-event streams and plain JSON replies.
 */
object ArvanClient {

    interface Listener {
        fun onDelta(text: String)
        fun onDone(fullText: String, usage: JsonObject?)
        fun onError(message: String)
    }

    /** A request in flight. [cancel] aborts it; no listener callback is made afterwards. */
    class Handle internal constructor() {
        @Volatile
        internal var cancelled = false

        @Volatile
        internal var future: CompletableFuture<*>? = null

        @Volatile
        internal var stream: InputStream? = null

        fun cancel() {
            cancelled = true
            future?.cancel(true)
            try {
                stream?.close()
            } catch (_: Exception) {
            }
        }
    }

    private val gson = Gson()
    private const val MAX_ERROR_BODY = 64 * 1024

    private var cachedClient: HttpClient? = null
    private var cachedProxy: String = ""

    /** One shared client; rebuilt only when the proxy setting changes. */
    @Synchronized
    private fun client(): HttpClient {
        val proxy = ArvanAiSettings.get().httpsProxy.trim()
        cachedClient?.let { if (proxy == cachedProxy) return it }
        val b = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
        parseProxy(proxy)?.let { b.proxy(ProxySelector.of(it)) }
        return b.build().also {
            cachedClient = it
            cachedProxy = proxy
        }
    }

    private fun parseProxy(text: String): InetSocketAddress? {
        if (text.isBlank()) return null
        return try {
            val uri = URI(if (text.contains("://")) text else "http://$text")
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 8080
            InetSocketAddress(host, port)
        } catch (e: Exception) {
            PluginLog.warn("api", "Invalid proxy setting ignored")
            null
        }
    }

    fun send(messages: List<ChatMessage>, listener: Listener): Handle {
        val handle = Handle()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                run(messages, listener, handle)
            } catch (e: Throwable) {
                if (!handle.cancelled) {
                    PluginLog.warn("api", "Request failed: ${e.javaClass.simpleName}")
                    listener.onError(describe(e))
                }
            }
        }
        return handle
    }

    private fun run(messages: List<ChatMessage>, listener: Listener, handle: Handle) {
        val s = ArvanAiSettings.get()
        val key = ApiKeyStore.get()
        if (key == null) {
            listener.onError(
                I18n.tr(
                    "No API key is set. Add it in Settings › Tools › ArvanAi.",
                    "کلید API تنظیم نشده است. آن را در Settings › Tools › ArvanAi وارد کنید.",
                )
            )
            return
        }
        val url = s.baseUrl.trim().trimEnd('/') + "/chat/completions"
        val uri = try {
            URI(url).also { require(it.scheme == "https" || it.scheme == "http") }
        } catch (e: Exception) {
            listener.onError(I18n.tr("The base URL in Settings is not valid.", "نشانی پایه در تنظیمات معتبر نیست."))
            return
        }
        val stream = s.streaming
        val body = JsonObject().apply {
            addProperty("model", s.model.trim())
            add("messages", JsonArray().also { arr ->
                messages.forEach { m ->
                    arr.add(JsonObject().apply {
                        addProperty("role", m.role)
                        addProperty("content", m.content)
                    })
                }
            })
            addProperty("max_completion_tokens", s.maxCompletionTokens)
            if (stream) addProperty("stream", true)
        }
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMinutes(5))
            .header("Authorization", "apikey $key")
            .header("Content-Type", "application/json")
            .header("Accept", if (stream) "text/event-stream, application/json" else "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), UTF_8))
            .build()
        PluginLog.info("api", "POST /chat/completions model=${s.model.trim()} messages=${messages.size} stream=$stream")

        val future = client().sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        handle.future = future
        if (handle.cancelled) future.cancel(true)
        val response = try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
        handle.stream = response.body()
        if (handle.cancelled) {
            handle.cancel()
            return
        }
        response.body().use { input ->
            val status = response.statusCode()
            if (status !in 200..299) {
                val text = String(input.readNBytes(MAX_ERROR_BODY), UTF_8)
                PluginLog.warn("api", "HTTP $status")
                listener.onError(httpError(status, extractError(text)))
                return
            }
            val contentType = response.headers().firstValue("Content-Type").orElse("").lowercase()
            if (contentType.contains("event-stream")) {
                readSse(input.bufferedReader(UTF_8).lineSequence(), listener, handle)
            } else {
                val text = input.readBytes().toString(UTF_8)
                if (handle.cancelled) return
                if (text.trimStart().startsWith("data:")) readSse(text.lineSequence(), listener, handle)
                else readJson(text, listener)
            }
        }
    }

    /** OpenAI-style chunks: `data: {...choices[0].delta.content...}` ... `data: [DONE]`. */
    private fun readSse(lines: Sequence<String>, listener: Listener, handle: Handle) {
        val full = StringBuilder()
        var usage: JsonObject? = null
        try {
            for (line in lines) {
                if (handle.cancelled) return
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break
                val obj = try {
                    JsonParser.parseString(payload).asJsonObject
                } catch (e: Exception) {
                    continue
                }
                obj.get("error")?.takeIf { !it.isJsonNull }?.let {
                    listener.onError(errorText(it))
                    return
                }
                (obj.get("usage") as? JsonObject)?.let { usage = it }
                val choice = obj.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject ?: continue
                val delta = choice.getAsJsonObject("delta")?.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                if (!delta.isNullOrEmpty()) {
                    full.append(delta)
                    listener.onDelta(delta)
                }
            }
        } catch (e: java.io.IOException) {
            if (handle.cancelled) return
            if (full.isEmpty()) throw e
            // Connection dropped mid-answer: keep what we have.
            PluginLog.warn("api", "Stream interrupted after ${full.length} chars")
        }
        if (handle.cancelled) return
        finish(full.toString(), usage, listener)
    }

    private fun readJson(text: String, listener: Listener) {
        val obj = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            listener.onError(I18n.tr("The server returned an unreadable reply.", "پاسخ سرور قابل خواندن نبود."))
            return
        }
        obj.get("error")?.takeIf { !it.isJsonNull }?.let {
            listener.onError(errorText(it))
            return
        }
        val content = obj.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
            ?.getAsJsonObject("message")?.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (content.isNotEmpty()) listener.onDelta(content)
        finish(content, obj.get("usage") as? JsonObject, listener)
    }

    private fun finish(text: String, usage: JsonObject?, listener: Listener) {
        if (text.isEmpty()) {
            listener.onError(I18n.tr("The model returned an empty reply.", "مدل پاسخ خالی برگرداند."))
        } else {
            listener.onDone(text, usage)
        }
    }

    private fun errorText(e: JsonElement): String =
        if (e.isJsonObject) e.asJsonObject.get("message")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        else if (e.isJsonPrimitive) e.asString else ""

    /** `error.message` (OpenAI style), `message` or `detail` from an error body; blank if none. */
    private fun extractError(body: String): String {
        val obj = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            return ""
        }
        obj.get("error")?.takeIf { !it.isJsonNull }?.let { errorText(it).takeIf { t -> t.isNotBlank() }?.let { t -> return t } }
        for (k in listOf("message", "detail")) {
            obj.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    private fun httpError(status: Int, serverMessage: String): String {
        val base = when (status) {
            401, 403 -> I18n.tr("The API key is invalid or missing (HTTP $status).", "کلید API نامعتبر است یا وجود ندارد (HTTP $status).")
            404 -> I18n.tr("Endpoint or model not found (HTTP 404). Check the base URL and model name.", "نشانی یا مدل پیدا نشد (HTTP 404). نشانی پایه و نام مدل را بررسی کنید.")
            400, 422 -> I18n.tr("The request was rejected (HTTP $status).", "درخواست پذیرفته نشد (HTTP $status).")
            429 -> I18n.tr("Too many requests or quota exceeded (HTTP 429). Try again later.", "تعداد درخواست‌ها زیاد است یا سهمیه تمام شده (HTTP 429). بعداً دوباره تلاش کنید.")
            in 500..599 -> I18n.tr("The server had an error (HTTP $status). Try again later.", "سرور خطا داد (HTTP $status). بعداً دوباره تلاش کنید.")
            else -> I18n.tr("Unexpected reply from the server (HTTP $status).", "پاسخ غیرمنتظره از سرور (HTTP $status).")
        }
        val detail = serverMessage.trim().take(400)
        return if (detail.isEmpty()) base else "$base\n$detail"
    }

    private fun describe(e: Throwable): String = when (e) {
        is CancellationException -> I18n.tr("Cancelled.", "لغو شد.")
        is java.net.http.HttpConnectTimeoutException, is java.net.http.HttpTimeoutException ->
            I18n.tr("The connection timed out.", "زمان اتصال به پایان رسید.")
        is java.net.ConnectException, is java.nio.channels.UnresolvedAddressException, is java.net.UnknownHostException ->
            I18n.tr("Could not connect to the server. Check your network or proxy.", "اتصال به سرور برقرار نشد. شبکه یا پراکسی را بررسی کنید.")
        else -> I18n.tr("Network error: ", "خطای شبکه: ") + (e.message ?: e.javaClass.simpleName)
    }
}
