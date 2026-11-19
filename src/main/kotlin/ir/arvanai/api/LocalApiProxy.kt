package ir.arvanai.api

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import ir.arvanai.PluginLog
import ir.arvanai.settings.ApiKeyStore
import ir.arvanai.settings.ArvanAiSettings
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * OpenAI-compatible endpoint on `127.0.0.1` for terminal agents (Aider).
 *
 * The agent sends `Authorization: Bearer <session token>`; the proxy checks the token and
 * forwards `/v1/...` to the configured base URL with `Authorization: apikey <key from PasswordSafe>`.
 * The real API key therefore never leaves the IDE process, and the token dies with the IDE.
 * Responses (including SSE streams) are copied through as they arrive.
 */
@Service(Service.Level.APP)
class LocalApiProxy : Disposable {

    /** Random per-IDE-session secret the agent must present. */
    val token: String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    /** Starts the server if needed and returns its base URL, e.g. `http://127.0.0.1:53121/v1`. */
    @Synchronized
    fun ensureStarted(): String {
        server?.let { return baseUrl(it) }
        val counter = AtomicInteger()
        val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "ArvanAi-proxy-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.createContext("/") { ex -> handle(ex) }
        s.executor = pool
        s.start()
        server = s
        executor = pool
        PluginLog.info("proxy", "Local API proxy listening on ${baseUrl(s)}")
        return baseUrl(s)
    }

    private fun baseUrl(s: HttpServer) = "http://127.0.0.1:${s.address.port}/v1"

    private fun handle(ex: HttpExchange) {
        try {
            forward(ex)
        } catch (e: IOException) {
            // Usually the agent closed the connection (Ctrl+C / cancelled reply).
            PluginLog.debug("proxy", "Connection ended: ${e.javaClass.simpleName}")
        } catch (e: Exception) {
            PluginLog.warn("proxy", "Request failed: ${e.javaClass.simpleName}")
        } finally {
            ex.close()
        }
    }

    private fun forward(ex: HttpExchange) {
        val path = ex.requestURI.rawPath ?: ""
        if (!path.startsWith("/v1/")) return reply(ex, 404, "Not found")
        if (!tokenMatches(ex.requestHeaders.getFirst("Authorization"))) return reply(ex, 401, "Invalid session token")
        val method = ex.requestMethod.uppercase()
        if (method != "GET" && method != "POST") return reply(ex, 405, "Method not allowed")

        val key = ApiKeyStore.get() ?: return reply(ex, 401, "No ArvanAi API key is set in the IDE (Settings › Tools › ArvanAi)")
        val base = ArvanAiSettings.get().baseUrl.trim().trimEnd('/')
        val target = try {
            val query = ex.requestURI.rawQuery?.let { "?$it" } ?: ""
            URI(base + path.removePrefix("/v1") + query).also { require(it.scheme == "https" || it.scheme == "http") }
        } catch (e: Exception) {
            return reply(ex, 500, "The base URL in ArvanAi settings is not valid")
        }

        val body = if (method == "POST") ex.requestBody.readAllBytes() else ByteArray(0)
        val request = HttpRequest.newBuilder(target)
            .timeout(Duration.ofMinutes(10))
            .header("Authorization", "apikey $key")
            .header("Content-Type", ex.requestHeaders.getFirst("Content-Type") ?: "application/json")
            .header("Accept", ex.requestHeaders.getFirst("Accept") ?: "*/*")
            .method(method, if (method == "POST") HttpRequest.BodyPublishers.ofByteArray(body) else HttpRequest.BodyPublishers.noBody())
            .build()

        val response = ArvanClient.client().send(request, HttpResponse.BodyHandlers.ofInputStream())
        PluginLog.info("proxy", "$method $path -> ${response.statusCode()}")
        response.body().use { upstream ->
            response.headers().firstValue("Content-Type").ifPresent { ex.responseHeaders.set("Content-Type", it) }
            ex.sendResponseHeaders(response.statusCode(), 0) // chunked: works for SSE and JSON
            val out = ex.responseBody
            val buf = ByteArray(8192)
            while (true) {
                val n = upstream.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                out.flush() // deliver SSE chunks immediately
            }
        }
    }

    private fun tokenMatches(header: String?): Boolean {
        val given = header?.removePrefix("Bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(given.toByteArray(UTF_8), token.toByteArray(UTF_8))
    }

    private fun reply(ex: HttpExchange, status: Int, message: String) {
        val json = """{"error":{"message":${Gson().toJson(message)}}}""".toByteArray(UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json")
        ex.sendResponseHeaders(status, json.size.toLong())
        ex.responseBody.write(json)
    }

    @Synchronized
    override fun dispose() {
        server?.stop(0)
        executor?.shutdownNow()
        server = null
        executor = null
    }

    companion object {
        fun get(): LocalApiProxy = ApplicationManager.getApplication().getService(LocalApiProxy::class.java)
    }
}
