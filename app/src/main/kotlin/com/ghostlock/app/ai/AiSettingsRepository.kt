package com.ghostlock.app.ai

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** AI 服务商配置存储 — 用 SharedPreferences，避免 DataStore 依赖 */
class AiSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ghostlock_ai_settings", Context.MODE_PRIVATE)

    fun getProviders(): List<AiProvider> {
        val json = prefs.getString(KEY_PROVIDERS, null) ?: return listOf(AiProvider.deepSeekPreset())
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                AiProvider(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    baseUrl = obj.getString("baseUrl"),
                    apiKey = obj.optString("apiKey", ""),
                    model = obj.getString("model"),
                )
            }
        } catch (_: Exception) {
            listOf(AiProvider.deepSeekPreset())
        }
    }

    fun getActiveProvider(): AiProvider {
        val activeId = prefs.getString(KEY_ACTIVE_ID, "deepseek") ?: "deepseek"
        return getProviders().firstOrNull { it.id == activeId } ?: AiProvider.deepSeekPreset()
    }

    fun setActiveProvider(id: String) {
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    fun saveProviders(providers: List<AiProvider>) {
        val arr = JSONArray()
        for (p in providers) {
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("baseUrl", p.baseUrl)
                put("apiKey", p.apiKey)
                put("model", p.model)
            })
        }
        prefs.edit().putString(KEY_PROVIDERS, arr.toString()).apply()
    }

    fun addOrUpdate(provider: AiProvider) {
        val current = getProviders().toMutableList()
        val idx = current.indexOfFirst { it.id == provider.id }
        if (idx >= 0) current[idx] = provider else current.add(provider)
        saveProviders(current)
    }

    fun deleteProvider(id: String) {
        saveProviders(getProviders().filter { it.id != id })
    }

    // ---- 聊天历史持久化 ----

    fun saveChatHistory(messages: List<AiChatMessage>) {
        val arr = JSONArray()
        for (m in messages) {
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("role", m.role.name)
                put("content", m.content)
                put("toolCallId", m.toolCallId)
                put("toolName", m.toolName)
                val tcs = JSONArray()
                for (tc in m.toolCalls) {
                    tcs.put(JSONObject().apply {
                        put("id", tc.id)
                        put("name", tc.name)
                        put("arguments", tc.arguments)
                        put("status", tc.status.name)
                    })
                }
                put("toolCalls", tcs)
            })
        }
        prefs.edit().putString(KEY_CHAT_HISTORY, arr.toString()).apply()
    }

    fun loadChatHistory(): List<AiChatMessage> {
        val json = prefs.getString(KEY_CHAT_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                val tcsArr = obj.optJSONArray("toolCalls") ?: JSONArray()
                val tcs = List(tcsArr.length()) { j ->
                    val tc = tcsArr.getJSONObject(j)
                    AiToolCall(
                        id = tc.getString("id"),
                        name = tc.getString("name"),
                        arguments = tc.getString("arguments"),
                        status = runCatching { AiToolStatus.valueOf(tc.optString("status", "PENDING")) }
                            .getOrDefault(AiToolStatus.PENDING),
                    )
                }
                AiChatMessage(
                    id = obj.getString("id"),
                    role = AiRole.valueOf(obj.getString("role")),
                    content = obj.getString("content"),
                    toolCalls = tcs,
                    toolCallId = obj.optString("toolCallId", null),
                    toolName = obj.optString("toolName", null),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearChatHistory() {
        prefs.edit().remove(KEY_CHAT_HISTORY).apply()
    }

    fun getMaxIterations(): Int = prefs.getInt(KEY_MAX_ITER, 30)
    fun setMaxIterations(v: Int) { prefs.edit().putInt(KEY_MAX_ITER, v).apply() }

    companion object {
        private const val KEY_PROVIDERS = "providers"
        private const val KEY_ACTIVE_ID = "active_provider_id"
        private const val KEY_CHAT_HISTORY = "chat_history_v1"
        private const val KEY_MAX_ITER = "max_iterations"
    }
}
