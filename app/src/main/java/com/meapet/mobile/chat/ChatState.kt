package com.meapet.mobile.chat

import com.meapet.mobile.core.UndoableAction
import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryStats

/**
 * 聊天 UI 状态。
 */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val inputText: String = "",
    val updateNotice: UpdateNoticeUi? = null,
    val memoryDialog: MemoryDialogUi? = null,
    val pendingUndo: ChatUndo? = null,
    val characterBubble: CharacterBubbleUi? = null
)

/** Live2D 角色上方弹出的对话气泡（如 Telegram Bot 推送消息）。 */
data class CharacterBubbleUi(
    val id: String,
    val sender: String,
    val text: String,
    val isTelegram: Boolean = false
)

/** UI 层展示的更新提示。 */
data class UpdateNoticeUi(
    val message: String,
    val url: String
)

/** 记忆查看对话框数据。 */
data class MemoryDialogUi(
    val memories: List<MemoryItem> = emptyList(),
    val stats: MemoryStats = MemoryStats(),
    val isMemoryEnabled: Boolean = true
)

/**
 * 聊天页可撤销的破坏性操作。
 */
sealed interface ChatUndo : UndoableAction {
    /** 清除对话：快照为清空前的完整会话历史。 */
    data class ClearConversation(
        override val id: Long,
        override val deadlineMs: Long,
        val snapshot: List<ChatMessage>,
        override val message: String = "Cleared ${snapshot.size} messages",
        override val undoLabel: String = "Undo"
    ) : ChatUndo

    /** 清除全部记忆：快照为清空前的全部条目。 */
    data class ClearMemory(
        override val id: Long,
        override val deadlineMs: Long,
        val snapshot: List<MemoryItem>,
        val exchangeCount: Int,
        override val message: String = "Cleared ${snapshot.size} memories",
        override val undoLabel: String = "Undo"
    ) : ChatUndo
}

