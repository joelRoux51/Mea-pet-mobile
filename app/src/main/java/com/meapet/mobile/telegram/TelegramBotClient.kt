package com.meapet.mobile.telegram

import com.meapet.mobile.client.HttpClientEngine
import com.meapet.mobile.client.HttpMethod
import com.meapet.mobile.client.HttpRequest
import com.meapet.mobile.client.KtorHttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

data class TelegramBotInfo(
    val id: Long,
    val username: String,
    val firstName: String
)

data class TelegramIncomingMessage(
    val updateId: Long,
    val chatId: Long,
    val senderName: String,
    val text: String,
    val timestampMs: Long
)

/**
 * Lightweight client for Telegram Bot HTTP API (`getMe`, `getUpdates`).
 */
class TelegramBotClient(
    private val engine: HttpClientEngine = KtorHttpClientEngine(),
    private val apiBase: String = DEFAULT_API_BASE
) {
    companion object {
        const val DEFAULT_API_BASE = "https://api.telegram.org"
        private val json = Json { ignoreUnknownKeys = true }
    }

    suspend fun getMe(botToken: String): Result<TelegramBotInfo> {
        val cleanToken = botToken.trim()
        if (cleanToken.isEmpty()) {
            return Result.failure(IllegalArgumentException("Bot token is empty"))
        }
        return try {
            val url = "${apiBase.trimEnd('/')}/bot$cleanToken/getMe"
            val response = engine.execute(HttpRequest(HttpMethod.GET, url))
            val bodyText = response.bodyAsText()
            val root = json.parseToJsonElement(bodyText).jsonObject
            val ok = root["ok"]?.jsonPrimitive?.booleanOrNull == true
            if (response.statusCode !in 200..299 || !ok) {
                val desc = root["description"]?.jsonPrimitive?.contentOrNull
                    ?: "HTTP ${response.statusCode}"
                return Result.failure(IllegalStateException(desc))
            }
            val result = root["result"]?.jsonObject
                ?: return Result.failure(IllegalStateException("Missing result"))
            val id = result["id"]?.jsonPrimitive?.longOrNull ?: 0L
            val username = result["username"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val firstName = result["first_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            Result.success(TelegramBotInfo(id = id, username = username, firstName = firstName))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUpdates(
        botToken: String,
        offset: Long? = null,
        timeoutSeconds: Int = 25
    ): Result<List<TelegramIncomingMessage>> {
        val cleanToken = botToken.trim()
        if (cleanToken.isEmpty()) {
            return Result.failure(IllegalArgumentException("Bot token is empty"))
        }
        return try {
            val allowed = URLEncoder.encode("""["message","channel_post","edited_message"]""", "UTF-8")
            val query = buildString {
                append("?timeout=").append(timeoutSeconds.coerceIn(0, 50))
                append("&allowed_updates=").append(allowed)
                if (offset != null && offset > 0L) {
                    append("&offset=").append(offset)
                }
            }
            val url = "${apiBase.trimEnd('/')}/bot$cleanToken/getUpdates$query"
            val response = engine.execute(HttpRequest(HttpMethod.GET, url))
            val bodyText = response.bodyAsText()
            val root = json.parseToJsonElement(bodyText).jsonObject
            val ok = root["ok"]?.jsonPrimitive?.booleanOrNull == true
            if (response.statusCode !in 200..299 || !ok) {
                val desc = root["description"]?.jsonPrimitive?.contentOrNull
                    ?: "HTTP ${response.statusCode}"
                return Result.failure(IllegalStateException(desc))
            }
            val updates = root["result"]?.jsonArray ?: return Result.success(emptyList())
            val parsed = updates.mapNotNull { elem ->
                parseUpdate(elem.jsonObject)
            }
            Result.success(parsed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun close() {
        engine.close()
    }

    private fun parseUpdate(obj: JsonObject): TelegramIncomingMessage? {
        val updateId = obj["update_id"]?.jsonPrimitive?.longOrNull ?: return null
        val msgObj = obj["message"]?.jsonObject
            ?: obj["channel_post"]?.jsonObject
            ?: obj["edited_message"]?.jsonObject
            ?: return null

        val text = msgObj["text"]?.jsonPrimitive?.contentOrNull
            ?: msgObj["caption"]?.jsonPrimitive?.contentOrNull
            ?: return null
        if (text.isBlank()) return null

        val chatObj = msgObj["chat"]?.jsonObject
        val chatId = chatObj?.get("id")?.jsonPrimitive?.longOrNull ?: 0L

        val fromObj = msgObj["from"]?.jsonObject
        val senderName = extractSenderName(fromObj, chatObj)

        val dateSeconds = msgObj["date"]?.jsonPrimitive?.longOrNull
        val timestampMs = if (dateSeconds != null && dateSeconds > 0L) {
            dateSeconds * 1000L
        } else {
            System.currentTimeMillis()
        }

        return TelegramIncomingMessage(
            updateId = updateId,
            chatId = chatId,
            senderName = senderName,
            text = text.trim(),
            timestampMs = timestampMs
        )
    }

    private fun extractSenderName(fromObj: JsonObject?, chatObj: JsonObject?): String {
        if (fromObj != null) {
            val first = fromObj["first_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val last = fromObj["last_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val combined = listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ")
            if (combined.isNotEmpty()) return combined
            val username = fromObj["username"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (username.isNotEmpty()) return "@$username"
        }
        if (chatObj != null) {
            val title = chatObj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (title.isNotEmpty()) return title
            val username = chatObj["username"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (username.isNotEmpty()) return "@$username"
        }
        return "Telegram Bot"
    }
}
