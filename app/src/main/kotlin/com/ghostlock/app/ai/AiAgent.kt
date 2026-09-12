package com.ghostlock.app.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/**
 * AI Agent 编排引擎 — 核心闭环：
 * 发送消息 → 接收流式响应 → 检测工具调用 → 执行工具 → 返回结果 → 循环
 * AI 看到错误后自动修改命令/参数并重试。
 */
class AiAgent(
    private val client: OpenAiClient,
    private val tools: GhostlockToolLayer,
) {
    sealed class AgentEvent {
        data class AssistantStart(val messageId: String) : AgentEvent()
        data class AssistantContent(val text: String) : AgentEvent()
        data class ToolCallStart(val toolCall: AiToolCall) : AgentEvent()
        data class ToolCallResult(val toolCallId: String, val result: AiToolResult) : AgentEvent()
        data class Error(val message: String) : AgentEvent()
        data object Done : AgentEvent()
    }

    fun run(
        history: List<AiChatMessage>,
        userMessage: String,
        maxIterations: Int = 30,
    ): Flow<AgentEvent> = flow {
        val messages = history.toMutableList()
        messages.add(AiChatMessage(role = AiRole.USER, content = userMessage))
        val toolDefs = tools.getToolDefinitions()

        repeat(maxIterations) {
            val content = StringBuilder()
            val toolBuilders = mutableMapOf<Int, ToolCallBuilder>()
            var finishReason: String? = null
            var hasError = false

            client.streamChat(messages, toolDefs).collect { event ->
                when (event) {
                    is OpenAiClient.StreamEvent.ContentDelta -> {
                        if (content.isEmpty()) {
                            emit(AgentEvent.AssistantStart("ai_${System.nanoTime()}"))
                        }
                        content.append(event.text)
                        emit(AgentEvent.AssistantContent(event.text))
                    }
                    is OpenAiClient.StreamEvent.ToolCallDelta -> {
                        val b = toolBuilders.getOrPut(event.index) {
                            ToolCallBuilder(id = event.id ?: "tc_${System.nanoTime()}_${event.index}")
                        }
                        event.id?.let { b.id = it }
                        event.name?.let { b.name = it }
                        event.arguments?.let { b.arguments.append(it) }
                    }
                    is OpenAiClient.StreamEvent.Finish -> finishReason = event.reason
                    is OpenAiClient.StreamEvent.Error -> {
                        emit(AgentEvent.Error(event.message))
                        hasError = true
                    }
                }
            }

            if (hasError) return@flow

            val toolCalls = toolBuilders.values.map { b ->
                AiToolCall(id = b.id, name = b.name ?: "unknown", arguments = b.arguments.toString())
            }
            messages.add(
                AiChatMessage(role = AiRole.ASSISTANT, content = content.toString(), toolCalls = toolCalls)
            )

            if (toolCalls.isEmpty() || finishReason != "tool_calls") return@flow

            for (tc in toolCalls) {
                emit(AgentEvent.ToolCallStart(tc.copy(status = AiToolStatus.RUNNING)))
                val result = tools.execute(tc.name, tc.arguments)
                emit(AgentEvent.ToolCallResult(tc.id, result))
                messages.add(
                    AiChatMessage(
                        role = AiRole.TOOL,
                        content = result.output.ifEmpty { "(empty, exit=${result.exitCode})" },
                        toolCallId = tc.id,
                        toolName = tc.name,
                    )
                )
            }
        }
        emit(AgentEvent.Done)
    }

    private class ToolCallBuilder(
        var id: String,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    )
}
