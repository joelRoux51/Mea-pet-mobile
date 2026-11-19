package com.meapet.mobile.telegram

import android.util.Log
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.chat.ConversationManager
import com.meapet.mobile.live2d.Live2dEmotion
import com.meapet.mobile.live2d.Live2dManager
import com.meapet.mobile.settings.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets

@Serializable
data class WebhookNotifyPayload(
    val text: String = "",
    val sender: String? = null
)

data class WebhookMessageEvent(
    val sender: String,
    val text: String,
    val message: ChatMessage,
    val emotion: String? = null
)

/**
 * Lightweight local HTTP Webhook server replacing Telegram `getUpdates` polling.
 *
 * Listens on `0.0.0.0:8081` (by default) and accepts `POST /notify` with a JSON body:
 * ```json
 * {"text": "[EMOTION: joie] C'est l'heure de boire de l'eau !"}
 * ```
 * Optional `"sender"` field can override the default sender label (`"Alita"`).
 * If an `[EMOTION: nom]` tag is present in `"text"`, it is extracted and stripped from the
 * displayed message, and the matching `.exp3.json` expression is triggered on the Live2D model.
 *
 * On valid receipt, it:
 * 1) Triggers the Live2D facial expression if `[EMOTION: ...]` is present,
 * 2) Persists the clean message in [ConversationManager] (visible in chat history),
 * 3) Emits a [WebhookMessageEvent] for Live2D dialogue bubbles (main screen + floating overlay),
 * 4) Displays an Android push notification via [TelegramNotificationHelper].
 */
class LocalWebhookServer(
    private val settingsManager: SettingsManager,
    private val conversationManager: ConversationManager,
    private val notificationHelper: TelegramNotificationHelper,
    private val scope: CoroutineScope,
    private val port: Int = DEFAULT_PORT,
    private val host: String = DEFAULT_HOST,
    private val onEmotionReceived: (String) -> Unit = { emotion ->
        Live2dManager.getInstance().triggerEmotion(emotion)
    }
) {
    companion object {
        private const val TAG = "LocalWebhookServer"
        const val DEFAULT_PORT = 8081
        const val DEFAULT_HOST = "0.0.0.0"
        const val DEFAULT_SENDER = "Alita"
        const val ROUTE_NOTIFY = "/notify"

        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val CLIENT_READ_TIMEOUT_MS = 5_000

        private val json = Json { ignoreUnknownKeys = true }

        fun defaultLocalUrl(port: Int = DEFAULT_PORT): String = "http://127.0.0.1:$port"

        /**
         * Returns reachable local URLs for this device: loopback first (`http://127.0.0.1:8081`),
         * followed by active LAN IPv4 addresses (e.g. `http://192.168.1.42:8081`) when available.
         */
        fun resolveLocalServerUrls(port: Int = DEFAULT_PORT): List<String> {
            val urls = LinkedHashSet<String>()
            urls.add(defaultLocalUrl(port))
            runCatching {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
                while (interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                            addr.hostAddress?.takeIf { it.isNotBlank() }?.let { ip ->
                                urls.add("http://$ip:$port")
                            }
                        }
                    }
                }
            }
            return urls.toList()
        }
    }

    private val _events = MutableSharedFlow<WebhookMessageEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<WebhookMessageEvent> = _events.asSharedFlow()

    private val serverLock = Any()
    private var watchJob: Job? = null
    private var serverJob: Job? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var boundPort: Int = if (port > 0) port else 0
        private set

    val isListening: Boolean
        get() = serverSocket?.let { !it.isClosed } == true

    /**
     * Observes `telegramEnabledFlow` in Settings and starts/stops the HTTP server automatically.
     */
    fun start() {
        if (watchJob != null) return
        watchJob = scope.launch(Dispatchers.IO) {
            settingsManager.awaitLoaded()
            settingsManager.telegramEnabledFlow.collectLatest { enabled ->
                if (enabled) {
                    runCatching { notificationHelper.ensureChannel() }
                    runServerLoop()
                } else {
                    closeSocket()
                }
            }
        }
    }

    /**
     * Starts listening directly (without waiting on DataStore toggle), primarily for lifecycle control or tests.
     */
    fun startListening(): Int {
        synchronized(serverLock) {
            if (isListening) return boundPort
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(host, port))
            }
            serverSocket = socket
            boundPort = socket.localPort
            serverJob = scope.launch(Dispatchers.IO) {
                acceptClients(socket)
            }
            Log.i(TAG, "Local webhook server listening on $host:$boundPort")
            return boundPort
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        serverJob?.cancel()
        serverJob = null
        closeSocket()
    }

    private suspend fun runServerLoop() {
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(host, port))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind local webhook server on $host:$port", e)
            return
        }
        synchronized(serverLock) {
            serverSocket = socket
            boundPort = socket.localPort
        }
        Log.i(TAG, "Local webhook server listening on $host:$boundPort")
        try {
            acceptClients(socket)
        } finally {
            closeSocket(socket)
        }
    }

    private suspend fun acceptClients(socket: ServerSocket) {
        try {
            while (currentCoroutineContext().isActive && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    break
                }
                scope.launch(Dispatchers.IO) {
                    handleClient(client)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!socket.isClosed) {
                Log.w(TAG, "Webhook accept loop terminated: ${e.message}")
            }
        }
    }

    private fun closeSocket(target: ServerSocket? = null) {
        synchronized(serverLock) {
            val socket = target ?: serverSocket ?: return
            if (target == null || serverSocket === target) {
                serverSocket = null
            }
            runCatching { socket.close() }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = CLIENT_READ_TIMEOUT_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val parsedRequest = readHttpRequest(input)
            if (parsedRequest == null) {
                writeJsonResponse(output, 400, "Bad Request", """{"ok":false,"error":"Malformed HTTP request"}""")
                return
            }

            if (parsedRequest.errorStatus != null) {
                writeJsonResponse(
                    output,
                    parsedRequest.errorStatus,
                    statusReason(parsedRequest.errorStatus),
                    """{"ok":false,"error":"${escapeJson(parsedRequest.errorMessage ?: "Invalid request")}"}"""
                )
                return
            }

            val pathOnly = parsedRequest.target.substringBefore('?')
            if (pathOnly != ROUTE_NOTIFY) {
                writeJsonResponse(output, 404, "Not Found", """{"ok":false,"error":"Not found"}""")
                return
            }
            if (!parsedRequest.method.equals("POST", ignoreCase = true)) {
                writeJsonResponse(output, 405, "Method Not Allowed", """{"ok":false,"error":"Method not allowed"}""")
                return
            }

            val bodyText = parsedRequest.bodyBytes.toString(StandardCharsets.UTF_8)
            val payload = try {
                json.decodeFromString<WebhookNotifyPayload>(bodyText)
            } catch (_: Exception) {
                writeJsonResponse(output, 400, "Bad Request", """{"ok":false,"error":"Invalid JSON"}""")
                return
            }

            val rawText = payload.text
            if (rawText.isBlank()) {
                writeJsonResponse(output, 400, "Bad Request", """{"ok":false,"error":"Missing 'text' field"}""")
                return
            }

            val parsedEmotion = Live2dEmotion.extractFromText(rawText)
            val cleanText = parsedEmotion.cleanText
            val emotion = parsedEmotion.emotion
                ?: cleanText.takeIf { it.isNotEmpty() }?.let { Live2dEmotion.inferEmotionFromContext("", it) }

            if (cleanText.isEmpty() && emotion == null) {
                writeJsonResponse(output, 400, "Bad Request", """{"ok":false,"error":"Missing 'text' field"}""")
                return
            }

            val senderName = payload.sender?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_SENDER
            dispatchIncomingNotification(senderName, cleanText, emotion)
            writeJsonResponse(output, 200, "OK", """{"ok":true}""")
        } catch (e: Exception) {
            Log.w(TAG, "Error handling webhook client: ${e.message}")
            runCatching {
                writeJsonResponse(
                    client.getOutputStream(),
                    500,
                    "Internal Server Error",
                    """{"ok":false,"error":"Internal server error"}"""
                )
            }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun dispatchIncomingNotification(
        senderName: String,
        cleanText: String,
        emotion: String?
    ) {
        if (emotion != null) {
            runCatching { onEmotionReceived(emotion) }
                .onFailure { Log.w(TAG, "Failed to apply Live2D emotion '$emotion': ${it.message}") }
        }
        if (cleanText.isEmpty()) return

        val chatMessage = ChatMessage(
            role = ChatRole.assistant,
            content = cleanText,
            timestamp = System.currentTimeMillis(),
            source = ChatMessage.SOURCE_TELEGRAM
        )
        conversationManager.addMessage(chatMessage)
        runCatching { notificationHelper.showNotification(senderName, cleanText) }
        _events.tryEmit(
            WebhookMessageEvent(
                sender = senderName,
                text = cleanText,
                message = chatMessage,
                emotion = emotion
            )
        )
    }

    private data class ParsedHttpRequest(
        val method: String = "",
        val target: String = "",
        val bodyBytes: ByteArray = ByteArray(0),
        val errorStatus: Int? = null,
        val errorMessage: String? = null
    )

    private fun readHttpRequest(input: InputStream): ParsedHttpRequest? {
        val headerBuffer = ByteArrayOutputStream()
        val chunk = ByteArray(2048)
        var headerEndIndex = -1
        var delimiterLen = 4
        var totalBytes = ByteArray(0)

        while (headerEndIndex < 0) {
            val read = input.read(chunk)
            if (read < 0) break
            headerBuffer.write(chunk, 0, read)
            if (headerBuffer.size() > MAX_HEADER_BYTES) {
                return ParsedHttpRequest(errorStatus = 431, errorMessage = "Request headers too large")
            }
            totalBytes = headerBuffer.toByteArray()
            val crlfIdx = findSequence(totalBytes, byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte()))
            if (crlfIdx >= 0) {
                headerEndIndex = crlfIdx
                delimiterLen = 4
            } else {
                val lfIdx = findSequence(totalBytes, byteArrayOf('\n'.code.toByte(), '\n'.code.toByte()))
                if (lfIdx >= 0) {
                    headerEndIndex = lfIdx
                    delimiterLen = 2
                }
            }
        }

        if (headerEndIndex < 0) return null

        val headerStr = String(totalBytes, 0, headerEndIndex, StandardCharsets.ISO_8859_1)
        val lines = headerStr.split("\r\n", "\n")
        val requestLine = lines.firstOrNull()?.trim().orEmpty()
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        val method = parts[0].trim()
        val target = parts[1].trim()

        var contentLength = 0
        var isChunked = false
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            when (name) {
                "content-length" -> {
                    val parsed = value.toIntOrNull()
                        ?: return ParsedHttpRequest(errorStatus = 400, errorMessage = "Invalid Content-Length")
                    if (parsed < 0) {
                        return ParsedHttpRequest(errorStatus = 400, errorMessage = "Negative Content-Length")
                    }
                    contentLength = parsed
                }
                "transfer-encoding" -> {
                    if (value.lowercase().contains("chunked")) {
                        isChunked = true
                    }
                }
            }
        }

        if (isChunked) {
            return ParsedHttpRequest(errorStatus = 400, errorMessage = "Chunked transfer encoding not supported")
        }
        if (contentLength > MAX_BODY_BYTES) {
            return ParsedHttpRequest(errorStatus = 413, errorMessage = "Payload too large")
        }

        val bodyStart = headerEndIndex + delimiterLen
        val alreadyRead = totalBytes.size - bodyStart
        val bodyBytes = ByteArray(contentLength)
        val copyCount = minOf(alreadyRead, contentLength)
        if (copyCount > 0) {
            System.arraycopy(totalBytes, bodyStart, bodyBytes, 0, copyCount)
        }
        var offset = copyCount
        while (offset < contentLength) {
            val read = try {
                input.read(bodyBytes, offset, contentLength - offset)
            } catch (_: EOFException) {
                -1
            }
            if (read < 0) {
                return ParsedHttpRequest(errorStatus = 400, errorMessage = "Unexpected end of request body")
            }
            offset += read
        }

        return ParsedHttpRequest(
            method = method,
            target = target,
            bodyBytes = bodyBytes
        )
    }

    private fun findSequence(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun writeJsonResponse(
        output: java.io.OutputStream,
        status: Int,
        reason: String,
        jsonBody: String
    ) {
        val bodyBytes = jsonBody.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        output.write(headers)
        output.write(bodyBytes)
        output.flush()
    }

    private fun statusReason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        431 -> "Request Header Fields Too Large"
        else -> "Error"
    }

    private fun escapeJson(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
}
