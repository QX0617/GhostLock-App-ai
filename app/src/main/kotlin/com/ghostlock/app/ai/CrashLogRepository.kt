package com.ghostlock.app.ai

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 崩溃日志记录 — 记录 exploit 导致花屏/重启前的最后状态。
 * 每次 run_exploit 前快照，崩溃重启后 AI 可以读取上次发生了什么。
 */
class CrashLogRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ghostlock_crash_logs", Context.MODE_PRIVATE)

    data class CrashEntry(
        val id: String,
        val timestamp: Long,
        val deviceName: String,
        val kernelRelease: String,
        val socName: String,
        val cpuPairIndex: Int,
        val safeMode: Boolean,
        val otaInput: String,
        val lastExploitLog: String,
        val notes: String,
    )

    /** 每次 run_exploit 前调用，记录"即将执行"的状态 */
    fun markPreExploit(
        deviceName: String,
        kernelRelease: String,
        socName: String,
        cpuPairIndex: Int,
        safeMode: Boolean,
        otaInput: String,
    ) {
        prefs.edit()
            .putLong("pending_time", System.currentTimeMillis())
            .putString("pending_device", deviceName)
            .putString("pending_kernel", kernelRelease)
            .putString("pending_soc", socName)
            .putInt("pending_cpu_pair", cpuPairIndex)
            .putBoolean("pending_safe_mode", safeMode)
            .putString("pending_ota", otaInput)
            .apply()
    }

    /** exploit 正常返回时调用，清除 pending（说明没崩溃） */
    fun clearPending() {
        prefs.edit().remove("pending_time").apply()
    }

    /** 每次 App 启动时调用：如果发现 pending 记录，说明上次崩溃了 */
    fun checkAndRecordCrash(): CrashEntry? {
        val pendingTime = prefs.getLong("pending_time", 0)
        if (pendingTime == 0L) return null
        val entry = CrashEntry(
            id = "crash_$pendingTime",
            timestamp = pendingTime,
            deviceName = prefs.getString("pending_device", "") ?: "",
            kernelRelease = prefs.getString("pending_kernel", "") ?: "",
            socName = prefs.getString("pending_soc", "") ?: "",
            cpuPairIndex = prefs.getInt("pending_cpu_pair", -1),
            safeMode = prefs.getBoolean("pending_safe_mode", false),
            otaInput = prefs.getString("pending_ota", "") ?: "",
            lastExploitLog = prefs.getString("pending_log", "") ?: "",
            notes = "",
        )
        // 加入历史，清除 pending
        val history = list().toMutableList()
        history.add(0, entry)
        saveHistory(history.take(20))
        clearPending()
        return entry
    }

    fun list(): List<CrashEntry> {
        val json = prefs.getString(KEY_CRASHES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                CrashEntry(
                    id = o.getString("id"),
                    timestamp = o.getLong("timestamp"),
                    deviceName = o.optString("deviceName", ""),
                    kernelRelease = o.optString("kernelRelease", ""),
                    socName = o.optString("socName", ""),
                    cpuPairIndex = o.optInt("cpuPairIndex", -1),
                    safeMode = o.optBoolean("safeMode", false),
                    otaInput = o.optString("otaInput", ""),
                    lastExploitLog = o.optString("lastExploitLog", ""),
                    notes = o.optString("notes", ""),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun clear() {
        prefs.edit().remove(KEY_CRASHES).apply()
    }

    private fun saveHistory(list: List<CrashEntry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().apply {
                put("id", e.id); put("timestamp", e.timestamp)
                put("deviceName", e.deviceName); put("kernelRelease", e.kernelRelease)
                put("socName", e.socName); put("cpuPairIndex", e.cpuPairIndex)
                put("safeMode", e.safeMode); put("otaInput", e.otaInput)
                put("lastExploitLog", e.lastExploitLog); put("notes", e.notes)
            })
        }
        prefs.edit().putString(KEY_CRASHES, arr.toString()).apply()
    }

    companion object {
        private const val KEY_CRASHES = "crash_history_v1"
    }
}
