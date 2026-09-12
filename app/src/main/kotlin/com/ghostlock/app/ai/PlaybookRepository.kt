package com.ghostlock.app.ai

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 流程库 — 记录成功 root 的设备配方。
 * 下次同设备/同内核来时，AI 直接套用，跳过试错。
 */
class PlaybookRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ghostlock_playbooks", Context.MODE_PRIVATE)

    data class Playbook(
        val id: String,
        val deviceName: String,
        val socName: String,
        val kernelRelease: String,
        val otaUrl: String,          // 成功时用的 OTA 链接或 boot.img 路径
        val cpuPairIndex: Int,       // 成功时用的 CPU pair
        val safeMode: Boolean,       // 成功时 safe mode 状态
        val retryCount: Int,         // 试了几次
        val notes: String,           // 备注（关键日志摘要）
        val createdAt: Long,
    )

    fun list(): List<Playbook> {
        val json = prefs.getString(KEY_PLAYBOOKS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Playbook(
                    id = o.getString("id"),
                    deviceName = o.getString("deviceName"),
                    socName = o.optString("socName", ""),
                    kernelRelease = o.getString("kernelRelease"),
                    otaUrl = o.optString("otaUrl", ""),
                    cpuPairIndex = o.optInt("cpuPairIndex", 0),
                    safeMode = o.optBoolean("safeMode", false),
                    retryCount = o.optInt("retryCount", 1),
                    notes = o.optString("notes", ""),
                    createdAt = o.optLong("createdAt", 0),
                )
            }.sortedByDescending { it.createdAt }
        } catch (_: Exception) { emptyList() }
    }

    fun findMatching(kernelRelease: String, deviceName: String): List<Playbook> {
        return list().filter { pb ->
            pb.kernelRelease == kernelRelease || pb.deviceName.equals(deviceName, ignoreCase = true)
        }
    }

    fun save(playbook: Playbook) {
        val current = list().toMutableList()
        // 同设备+同内核，更新而非重复
        current.removeAll { it.deviceName == playbook.deviceName && it.kernelRelease == playbook.kernelRelease }
        current.add(0, playbook)
        val arr = JSONArray()
        for (pb in current.take(50)) {
            arr.put(JSONObject().apply {
                put("id", pb.id)
                put("deviceName", pb.deviceName)
                put("socName", pb.socName)
                put("kernelRelease", pb.kernelRelease)
                put("otaUrl", pb.otaUrl)
                put("cpuPairIndex", pb.cpuPairIndex)
                put("safeMode", pb.safeMode)
                put("retryCount", pb.retryCount)
                put("notes", pb.notes)
                put("createdAt", pb.createdAt)
            })
        }
        prefs.edit().putString(KEY_PLAYBOOKS, arr.toString()).apply()
    }

    fun delete(id: String) {
        val remaining = list().filter { it.id != id }
        val arr = JSONArray()
        for (pb in remaining) {
            arr.put(JSONObject().apply {
                put("id", pb.id); put("deviceName", pb.deviceName)
                put("socName", pb.socName); put("kernelRelease", pb.kernelRelease)
                put("otaUrl", pb.otaUrl); put("cpuPairIndex", pb.cpuPairIndex)
                put("safeMode", pb.safeMode); put("retryCount", pb.retryCount)
                put("notes", pb.notes); put("createdAt", pb.createdAt)
            })
        }
        prefs.edit().putString(KEY_PLAYBOOKS, arr.toString()).apply()
    }

    companion object {
        private const val KEY_PLAYBOOKS = "playbooks_v1"
    }
}
