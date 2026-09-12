package com.ghostlock.app.ai

/** AI 服务商配置 */
data class AiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    companion object {
        fun deepSeekPreset() = AiProvider(
            id = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = "",
            model = "deepseek-chat",
        )
    }
}

/** 聊天消息 */
data class AiChatMessage(
    val id: String = System.nanoTime().toString(),
    val role: AiRole,
    var content: String,
    val toolCalls: List<AiToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    var streaming: Boolean = false,
)

enum class AiRole { USER, ASSISTANT, TOOL, SYSTEM }

/** 工具调用 */
data class AiToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    var result: AiToolResult? = null,
    var status: AiToolStatus = AiToolStatus.PENDING,
)

enum class AiToolStatus { PENDING, RUNNING, SUCCESS, FAILED }

/** 工具执行结果 */
data class AiToolResult(
    val exitCode: Int,
    val output: String,
    val durationMs: Long = 0,
) {
    val isSuccess: Boolean get() = exitCode == 0
}
