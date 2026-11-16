package com.meapet.mobile.telegram

import android.util.Log
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.chat.ConversationManager
import com.meapet.mobile.settings.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TelegramMessageEvent(
    val sender: String,
    val text: String,
    val message: ChatMessage
)

/**
 * Background long-poller for incoming Telegram Bot updates.
 * When a message arrives, it:
 * 1) Adds it to [ConversationManager] (visible in the chat list and persisted),
 * 2) Emits a [TelegramMessageEvent] for Live2D dialogue bubbles (main screen + floating overlay),
 * 3) Displays an Android push notification via [TelegramNotificationHelper].
 */
class TelegramPoller(
    private val settingsManager: SettingsManager,
    private val conversationManager: ConversationManager,
    private val botClient: TelegramBotClient,
    private val notificationHelper: TelegramNotificationHelper,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TelegramPoller"
        private const val ERROR_BACKOFF_MS = 5_000L
        private const val POLL_TIMEOUT_SECONDS = 25
    }

    private data class PollConfig(
        val enabled: Boolean,
        val token: String,
        val chatIdFilter: String
    )

    private val _events = MutableSharedFlow<TelegramMessageEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<TelegramMessageEvent> = _events.asSharedFlow()

    private var watchJob: Job? = null

    fun start() {
        if (watchJob != null) return
        watchJob = scope.launch(Dispatchers.IO) {
            settingsManager.awaitLoaded()
            combine(
                settingsManager.telegramEnabledFlow,
                settingsManager.telegramBotTokenFlow,
                settingsManager.telegramChatIdFlow
            ) { enabled, token, chatId ->
                PollConfig(enabled = enabled, token = token.trim(), chatIdFilter = chatId.trim())
            }.collectLatest { cfg ->
                if (!cfg.enabled || cfg.token.isEmpty()) return@collectLatest
                notificationHelper.ensureChannel()
                pollLoop(cfg)
            }
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
    }

    private suspend fun pollLoop(cfg: PollConfig) {
        var lastUpdateId = settingsManager.getTelegramLastUpdateId()
        Log.i(TAG, "Starting Telegram long-poll loop (offset=${if (lastUpdateId > 0) lastUpdateId + 1 else 0})")
        while (scope.isActive) {
            val nextOffset = if (lastUpdateId > 0L) lastUpdateId + 1L else null
            val result = botClient.getUpdates(
                botToken = cfg.token,
                offset = nextOffset,
                timeoutSeconds = POLL_TIMEOUT_SECONDS
            )
            result.fold(
                onSuccess = { updates ->
                    if (updates.isNotEmpty()) {
                        var maxId = lastUpdateId
                        for (msg in updates) {
                            if (msg.updateId > maxId) maxId = msg.updateId
                            if (cfg.chatIdFilter.isNotEmpty() && msg.chatId.toString() != cfg.chatIdFilter) {
                                continue
                            }
                            handleIncomingMessage(msg)
                        }
                        if (maxId > lastUpdateId) {
                            lastUpdateId = maxId
                            settingsManager.setTelegramLastUpdateId(maxId)
                        }
                    }
                },
                onFailure = { err ->
                    if (err is CancellationException) throw err
                    Log.w(TAG, "Telegram polling error: ${err.message}")
                    delay(ERROR_BACKOFF_MS)
                }
            )
        }
    }

    private fun handleIncomingMessage(incoming: TelegramIncomingMessage) {
        val chatMessage = ChatMessage(
            role = ChatRole.assistant,
            content = incoming.text,
            timestamp = incoming.timestampMs,
            source = ChatMessage.SOURCE_TELEGRAM
        )
        conversationManager.addMessage(chatMessage)
        notificationHelper.showNotification(incoming.senderName, incoming.text)
        _events.tryEmit(
            TelegramMessageEvent(
                sender = incoming.senderName,
                text = incoming.text,
                message = chatMessage
            )
        )
    }
}
