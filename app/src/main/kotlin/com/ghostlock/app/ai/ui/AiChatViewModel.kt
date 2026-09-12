package com.ghostlock.app.ai.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ghostlock.app.ai.AiAgent
import com.ghostlock.app.ai.AiChatMessage
import com.ghostlock.app.ai.AiRole
import com.ghostlock.app.ai.AiSettingsRepository
import com.ghostlock.app.ai.AiToolCall
import com.ghostlock.app.ai.AiToolStatus
import com.ghostlock.app.ai.CrashLogRepository
import com.ghostlock.app.ai.GhostlockToolLayer
import com.ghostlock.app.ai.OpenAiClient
import com.ghostlock.app.ai.PlaybookRepository
import com.ghostlock.app.domain.repository.GhostlockRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AiChatViewModel(
    application: Application,
    private val repository: GhostlockRepository,
) : AndroidViewModel(application) {

    private val settingsRepo = AiSettingsRepository(application)
    private val playbookRepo = PlaybookRepository(application)
    private val crashLogRepo = CrashLogRepository(application)
    private val toolLayer = GhostlockToolLayer(repository, application.filesDir, playbookRepo, crashLogRepo)

    private val _messages = MutableStateFlow<List<AiChatMessage>>(emptyList())
    val messages: StateFlow<List<AiChatMessage>> = _messages.asStateFlow()

    private val _crashCount = MutableStateFlow(0)
    val crashCount: StateFlow<Int> = _crashCount.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        _messages.value = settingsRepo.loadChatHistory()
        // 检查上次是否崩溃
        val crash = crashLogRepo.checkAndRecordCrash()
        _crashCount.value = crashLogRepo.list().size
        if (crash != null) {
            _messages.value = _messages.value + AiChatMessage(
                role = AiRole.SYSTEM,
                content = "⚠ 检测到上次 exploit 导致设备重启（花屏）。设备：${crash.deviceName}，内核：${crash.kernelRelease}，CPU pair=${crash.cpuPairIndex}，safe_mode=${crash.safeMode}。下次请换一个 CPU pair 重试，不要重复使用崩溃的参数。",
            )
        }
    }

    fun getCrashLogs(): List<CrashLogRepository.CrashEntry> = crashLogRepo.list()
    fun clearCrashLogs() { crashLogRepo.clear(); _crashCount.value = 0 }

    fun getActiveProviderName(): String = settingsRepo.getActiveProvider().let { "${it.name} · ${it.model}" }

    fun isApiKeySet(): Boolean = settingsRepo.getActiveProvider().apiKey.isNotBlank()

    fun send(text: String) {
        if (text.isBlank() || _loading.value) return
        val provider = settingsRepo.getActiveProvider()
        if (provider.apiKey.isBlank()) {
            _error.value = "请先在 AI 设置中填入 API Key"
            return
        }
        _loading.value = true
        _error.value = null

        val history = compressHistory(_messages.value.toList())
        val agent = AiAgent(OpenAiClient(provider), toolLayer)
        val maxIter = settingsRepo.getMaxIterations()

        viewModelScope.launch {
            val userMsg = AiChatMessage(role = AiRole.USER, content = text)
            _messages.value = _messages.value + userMsg

            agent.run(history, text, maxIter).collect { event ->
                when (event) {
                    is AiAgent.AgentEvent.AssistantStart -> {
                        _messages.value = _messages.value + AiChatMessage(
                            id = event.messageId,
                            role = AiRole.ASSISTANT,
                            content = "",
                            streaming = true,
                        )
                    }
                    is AiAgent.AgentEvent.AssistantContent -> {
                        val list = _messages.value.toMutableList()
                        val idx = list.indexOfLast { it.role == AiRole.ASSISTANT && it.streaming }
                        if (idx >= 0) {
                            list[idx] = list[idx].copy(content = list[idx].content + event.text)
                        }
                        _messages.value = list
                    }
                    is AiAgent.AgentEvent.ToolCallStart -> {
                        val list = _messages.value.toMutableList()
                        val idx = list.indexOfLast { it.role == AiRole.ASSISTANT }
                        if (idx >= 0) {
                            val msg = list[idx]
                            val calls = msg.toolCalls.toMutableList()
                            val existing = calls.indexOfFirst { it.id == event.toolCall.id }
                            if (existing >= 0) calls[existing] = event.toolCall else calls.add(event.toolCall)
                            list[idx] = msg.copy(toolCalls = calls)
                            _messages.value = list
                        }
                    }
                    is AiAgent.AgentEvent.ToolCallResult -> {
                        val list = _messages.value.toMutableList()
                        val idx = list.indexOfLast { it.role == AiRole.ASSISTANT }
                        var toolName: String? = null
                        if (idx >= 0) {
                            val msg = list[idx]
                            val calls = msg.toolCalls.map { tc ->
                                if (tc.id == event.toolCallId) {
                                    toolName = tc.name
                                    tc.copy(
                                        result = event.result,
                                        status = if (event.result.isSuccess) AiToolStatus.SUCCESS else AiToolStatus.FAILED,
                                    )
                                } else tc
                            }
                            list[idx] = msg.copy(toolCalls = calls)
                        }
                        list.add(
                            AiChatMessage(
                                role = AiRole.TOOL,
                                content = event.result.output,
                                toolCallId = event.toolCallId,
                                toolName = toolName,
                            )
                        )
                        _messages.value = list
                    }
                    is AiAgent.AgentEvent.Error -> {
                        _error.value = event.message
                        val list = _messages.value.map { it.copy(streaming = false) }
                        _messages.value = list
                    }
                    AiAgent.AgentEvent.Done -> {
                        _messages.value = _messages.value.map { it.copy(streaming = false) }
                    }
                }
            }
            _loading.value = false
            settingsRepo.saveChatHistory(_messages.value)
        }
    }

    /**
     * 上下文压缩：
     * - 保留最近 30 条消息
     * - 早期 TOOL 消息长输出截断为 800 字符
     * - 超长时用一条 SYSTEM 摘要消息替代早期对话
     * 保证给 API 的消息前缀稳定，提升 DeepSeek context cache 命中率
     */
    private fun compressHistory(messages: List<AiChatMessage>): List<AiChatMessage> {
        val MAX_MESSAGES = 30
        val MAX_TOOL_OUTPUT = 800
        if (messages.size <= MAX_MESSAGES) {
            return messages.map { m ->
                if (m.role == AiRole.TOOL && m.content.length > MAX_TOOL_OUTPUT) {
                    m.copy(content = m.content.take(MAX_TOOL_OUTPUT) + "\n...[truncated, ${m.content.length - MAX_TOOL_OUTPUT} chars omitted]")
                } else m
            }
        }
        val recent = messages.takeLast(MAX_MESSAGES)
        val older = messages.dropLast(MAX_MESSAGES)
        val summary = buildString {
            append("Earlier conversation summary (${older.size} messages omitted): ")
            val userMsgs = older.filter { it.role == AiRole.USER }.map { it.content.take(100) }
            if (userMsgs.isNotEmpty()) appendLine("User topics: ${userMsgs.joinToString("; ")}")
            val toolsUsed = older.filter { it.role == AiRole.TOOL }.mapNotNull { it.toolName }.distinct()
            if (toolsUsed.isNotEmpty()) appendLine("Tools used: ${toolsUsed.joinToString(", ")}")
            val lastResult = older.lastOrNull { it.role == AiRole.TOOL }
            if (lastResult != null) appendLine("Last tool output preview: ${lastResult.content.take(200)}")
        }
        return listOf(AiChatMessage(role = AiRole.SYSTEM, content = summary)) + recent
    }

    fun clear() {
        _messages.value = emptyList()
        settingsRepo.clearChatHistory()
    }

    fun clearError() {
        _error.value = null
    }
}
