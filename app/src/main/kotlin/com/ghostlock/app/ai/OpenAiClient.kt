package com.ghostlock.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** OpenAI 兼容 API 客户端（DeepSeek 等），支持 SSE 流式 + function calling */
class OpenAiClient(private val provider: AiProvider) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    sealed class StreamEvent {
        data class ContentDelta(val text: String) : StreamEvent()
        data class ToolCallDelta(
            val index: Int,
            val id: String?,
            val name: String?,
            val arguments: String?,
        ) : StreamEvent()
        data class Finish(val reason: String) : StreamEvent()
        data class Error(val message: String) : StreamEvent()
    }

    fun streamChat(
        messages: List<AiChatMessage>,
        tools: List<JSONObject>? = null,
        systemPrompt: String? = null,
    ): Flow<StreamEvent> = flow {
        val url = "${provider.baseUrl.trimEnd('/')}/chat/completions"
        val body = buildRequestBody(messages, tools, systemPrompt)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                emit(StreamEvent.Error("HTTP ${response.code}: ${response.body?.string() ?: "error"}"))
                return@flow
            }
            val reader = response.body?.charStream()?.buffered() ?: run {
                emit(StreamEvent.Error("empty response"))
                return@flow
            }
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val data = line?.takeIf { it.startsWith("data:") }?.removePrefix("data:")?.trim() ?: continue
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                try {
                    val json = JSONObject(data)
                    val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: continue
                    val delta = choice.optJSONObject("delta") ?: continue
                    delta.optString("content")?.takeIf { it.isNotEmpty() }?.let {
                        emit(StreamEvent.ContentDelta(it))
                    }
                    delta.optJSONArray("tool_calls")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val tc = arr.getJSONObject(i)
                            val func = tc.optJSONObject("function")
                            emit(
                                StreamEvent.ToolCallDelta(
                                    index = tc.optInt("index", 0),
                                    id = if (tc.has("id")) tc.getString("id") else null,
                                    name = func?.let { if (it.has("name")) it.getString("name") else null },
                                    arguments = func?.let { if (it.has("arguments")) it.getString("arguments") else null },
                                )
                            )
                        }
                    }
                    choice.optString("finish_reason")?.takeIf { it.isNotEmpty() && it != "null" }?.let {
                        emit(StreamEvent.Finish(it))
                    }
                } catch (_: Exception) { }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequestBody(
        messages: List<AiChatMessage>,
        tools: List<JSONObject>?,
        systemPrompt: String?,
    ): JSONObject {
        val arr = JSONArray()
        arr.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt ?: DEFAULT_SYSTEM_PROMPT)
        })
        for (msg in messages) {
            val obj = JSONObject().apply { put("role", msg.role.name.lowercase()) }
            when (msg.role) {
                AiRole.USER, AiRole.SYSTEM -> obj.put("content", msg.content)
                AiRole.ASSISTANT -> {
                    obj.put("content", msg.content.ifEmpty { JSONObject.NULL })
                    if (msg.toolCalls.isNotEmpty()) {
                        val tcs = JSONArray()
                        for (tc in msg.toolCalls) {
                            tcs.put(JSONObject().apply {
                                put("id", tc.id)
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", tc.name)
                                    put("arguments", tc.arguments)
                                })
                            })
                        }
                        obj.put("tool_calls", tcs)
                    }
                }
                AiRole.TOOL -> {
                    obj.put("content", msg.content)
                    obj.put("tool_call_id", msg.toolCallId)
                }
            }
            arr.put(obj)
        }
        return JSONObject().apply {
            put("model", provider.model)
            put("messages", arr)
            put("stream", true)
            put("temperature", 0.3)
            if (!tools.isNullOrEmpty()) {
                put("tools", JSONArray(tools))
                put("tool_choice", "auto")
            }
        }
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT = """# GhostLock AI — Android 内核提权助手

你是 GhostLock 的 AI 助手，专门通过工具调用驱动 GhostLock 临时提权工具（CVE-2026-43499，futex PI requeue UAF）。请始终用中文回答。

## 安全红线（绝对不能违反）
- GhostLock 只提供**内存中的临时 root**，不刷写、不修改任何分区。
- 绝对不要建议或尝试：刷镜像、格式化分区、写入 /dev/block、重启到 bootloader/fastboot/EDL、修改 /system 或 /vendor。
- Shell 工具有内置安全守卫，会拦截这类命令。请遵守。
- 如果当前内核无法提权，请如实告知用户，不要强行尝试危险操作。
- 获得的 root 重启后会失效，请提醒用户。

## 工作流程（按顺序执行）
1. **先查流程库**：调用 `list_playbooks`，看此设备/内核是否已有成功配方。如果有匹配项，调用 `load_playbook` 按成熟步骤走（OTA 链接、CPU pair、safe mode、重试次数）。
2. **检查设备**：调用 `get_device_info`。读取内核版本、SoC、是否支持、CPU 对列表。
3. **内核不支持时**：调用 `parse_ota`，传入用户提供的 OTA ZIP 链接或 boot.img 路径。如果用户没有，告诉用户去哪里下载（官方固件）。
4. **选 CPU 对**：查看可用 CPU 对，从 pair 0 开始。不同 SoC 成功率不同。
5. **执行提权**：调用 `run_exploit`。这是竞态条件漏洞，失败是正常的。
6. **诊断与重试循环**（核心）：
   - 仔细阅读完整日志输出。
   - 超时/竞态丢失 → 换一个 `cpu_pair_index`。
   - SELinux 拒绝 → 开启 `safe_mode` 后重试。
   - 偏移量不匹配 → 重新解析 OTA 或重新导入偏移量。
   - 崩溃/花屏 → 切换 safe mode，换 pair 0。
   - **重要**：如果 `read_crash_logs` 显示某个 CPU pair 导致过崩溃，绝对不要再用那个 pair。
   - 每次操作前，用 1-2 句话解释你在做什么、为什么。
7. **验证**：exit code 为 0 后，调用 `check_root` 确认 uid=0。
8. **保存流程**：root 确认后，调用 `save_playbook` 记录成功配方（设备、内核、OTA 链接、CPU pair、safe mode、重试次数、备注），下次同设备直接复用。

## 工具使用规则
- 新会话第一步必须调用 `list_playbooks` + `get_device_info`。
- `execute_shell` 只用于只读诊断：`id`、`getprop`、`cat /proc/version`、`ls`、`dmesg | tail`、`uname -a`。不要用它修改系统。
- 工具返回错误时，**仔细读错误文本**——它会告诉你下一步该做什么。不要盲目重复调用。
- 最多 30 轮迭代。如果 15 次尝试后仍无进展，总结发现并停止。

## 沟通风格
- 简洁。每次工具调用前用 1-2 句话说明。
- 展示失败原因和你的修复思路后再重试。
- 用通俗语言，用户可能不是漏洞开发专家。
- 在 `check_root` 确认 uid=0 之前，不要声称已获得 root。"""
    }
}
