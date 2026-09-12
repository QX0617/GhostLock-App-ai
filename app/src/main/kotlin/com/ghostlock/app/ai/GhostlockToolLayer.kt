package com.ghostlock.app.ai

import com.ghostlock.app.domain.model.CpuPair
import com.ghostlock.app.domain.model.ParseResult
import com.ghostlock.app.domain.repository.GhostlockRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 将 GhostLock repository 操作封装为 AI 可调用的工具（function calling）。
 * AI 通过这些工具来执行 exploit、解析 OTA、查询设备状态、导入导出偏移量等。
 */
class GhostlockToolLayer(
    private val repository: GhostlockRepository,
    private val filesDir: File,
    private val playbooks: PlaybookRepository? = null,
    private val crashLogs: CrashLogRepository? = null,
) {
    // 会话状态追踪 — 用于自动填充 save_playbook
    private var lastOtaInput: String = ""
    private var exploitRetryCount: Int = 0
    private var lastSuccessfulPair: Int = -1
    private var lastSafeMode: Boolean = false
    /** 获取 OpenAI function calling 格式的工具定义 */
    fun getToolDefinitions(): List<JSONObject> = listOf(
        fn("get_device_info", "Get device information: device name, SoC, kernel version, whether the kernel is supported by GhostLock, available CPU pairs, current offsets status, and settings. ALWAYS call this first to understand the device state.", emptyObj()),
        fn("run_exploit", "Run the GhostLock kernel exploit to obtain temporary root. Uses the currently selected CPU pair. May need multiple attempts due to race conditions. Returns exit code and full log output. On failure, read the error and adjust parameters before retrying.", obj {
            put("cpu_pair_index", obj {
                put("type", "integer"); put("description", "Index of CPU pair to use (from get_device_info). Omit to use current selection.")
            })
            put("retry", obj {
                put("type", "integer"); put("description", "Number of retry attempts already made (for logging).")
            })
        }),
        fn("parse_ota", "Parse a full OTA firmware ZIP (from URL or local path) OR a boot.img to extract kernel offsets for unsupported kernels. Runs the built-in extractor in-process. For OTA ZIP, pass the URL/path in 'input'. For boot.img, pass the image path in 'input' and set is_boot_img=true.", obj {
            put("input", obj { put("type","string"); put("description","OTA ZIP URL/path or boot.img path") })
            put("is_boot_img", obj { put("type","boolean"); put("description","true if input is a boot.img rather than OTA ZIP") })
            put("overwrite", obj { put("type","boolean"); put("description","Overwrite existing offsets (default false)") })
        }, required = listOf("input")),
        fn("select_cpu_pair", "Select which CPU core pair to use for the exploit race. Different pairs can affect success rate on different SoCs.", obj {
            put("index", obj { put("type","integer"); put("description","CPU pair index from get_device_info") })
        }, required = listOf("index")),
        fn("toggle_safe_mode", "Toggle safe mode (disables KernelSU module loading, only grants uid 0). Useful when module loading causes crashes or boot loops.", obj {
            put("enabled", obj { put("type","boolean"); put("description","true to enable safe mode, false to disable") })
        }, required = listOf("enabled")),
        fn("execute_shell", "Execute a shell command in the app sandbox. Useful for diagnostics: checking files, permissions, kernel symbols, running id/getprop/dmesg, checking /proc, etc. Returns stdout, stderr, and exit code.", obj {
            put("command", obj { put("type","string"); put("description","Shell command to execute") })
            put("timeout", obj { put("type","integer"); put("description","Timeout in seconds (default 30)") })
        }, required = listOf("command")),
        fn("check_root", "Check if root access is currently available. Runs id and checks for su binary and KernelSU.", emptyObj()),
        fn("import_offsets", "Import kernel offsets from a JSON string (e.g. from previously exported offsets or a community offset file). The JSON should contain offset names and values for this kernel.", obj {
            put("json", obj { put("type","string"); put("description","JSON string containing offset key-value pairs") })
            put("confirm", obj { put("type","boolean"); put("description","If true, confirms and applies the import immediately. If false, just previews.") })
        }, required = listOf("json")),
        fn("export_offsets", "Export currently known offset candidates as JSON. Useful for backing up, sharing, or debugging offset extraction.", emptyObj()),
        fn("publish_offsets", "Publish current offset candidates to the GhostLock community offset repository, so other users with the same kernel can benefit. Requires the offsets to be verified working.", obj {
            put("candidate_index", obj { put("type","integer"); put("description","Index of the offset candidate to publish (from export_offsets)") })
        }),
        fn("read_logs", "Read the last exploit attempt logs from the app's log buffer. Useful for debugging why an exploit attempt failed.", emptyObj()),
        fn("reboot", "Reboot the device. Only works after root is obtained (su -c reboot). Ask user confirmation first.", obj {
            put("reason", obj { put("type","string"); put("description","Why rebooting (e.g. to apply changes)") })
        }),
        // ---- 流程库 ----
        fn("list_playbooks", "List all saved successful root recipes (playbooks). Each contains device name, kernel, OTA URL, CPU pair, safe mode, and notes. Call this FIRST to check if this device already has a proven recipe.", emptyObj()),
        fn("load_playbook", "Load a specific playbook by id to get the full recipe: OTA URL, CPU pair index, safe mode setting, and notes. Use this to follow a proven success path.", obj {
            put("id", obj { put("type","string"); put("description","Playbook id from list_playbooks") })
        }, required = listOf("id")),
        fn("save_playbook", "Save a successful root recipe to the playbook library. Call this AFTER check_root confirms uid=0. Automatically captures current device, kernel, OTA URL used, CPU pair, safe mode, and retry count. Future sessions on the same device/kernel will reuse this.", obj {
            put("notes", obj { put("type","string"); put("description","Optional notes: what worked, what failed, key observations") })
        }),
        fn("delete_playbook", "Delete a playbook by id.", obj {
            put("id", obj { put("type","string"); put("description","Playbook id to delete") })
        }, required = listOf("id")),
        // ---- 崩溃日志 ----
        fn("read_crash_logs", "Read history of device crashes/reboots caused by previous exploit attempts. Each entry shows what CPU pair, safe mode, and OTA was used right before the crash. CRITICAL: If a crash occurred on a specific CPU pair, do NOT retry that pair — switch to a different one. Always check this first if the device recently rebooted.", emptyObj()),
        fn("clear_crash_logs", "Clear all crash history.", emptyObj()),
    )

    /** 执行工具调用，返回结果字符串 */
    suspend fun execute(name: String, arguments: String): AiToolResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val args = try { JSONObject(arguments) } catch (_: Exception) { JSONObject() }
        try {
            val (exitCode, output) = when (name) {
                "get_device_info" -> handleGetDeviceInfo()
                "run_exploit" -> handleRunExploit(args.optInt("cpu_pair_index", -1), args.optInt("retry", 0))
                "parse_ota" -> handleParseOta(args.optString("input", ""), args.optBoolean("is_boot_img", false), args.optBoolean("overwrite", false))
                "select_cpu_pair" -> Triple(0, "CPU pair selected: ${args.optInt("index", 0)}", 0).also { repository.selectCpuPair(args.optInt("index", 0)) }
                "toggle_safe_mode" -> {
                    val en = args.optBoolean("enabled", false)
                    lastSafeMode = en
                    Triple(0, "safe mode: $en", 0).also { repository.setSafeModeEnabled(en) }
                }
                "execute_shell" -> handleExecuteShell(args.optString("command", ""), args.optLong("timeout", 30))
                "check_root" -> handleCheckRoot()
                "import_offsets" -> handleImportOffsets(args.optString("json", ""), args.optBoolean("confirm", false))
                "export_offsets" -> handleExportOffsets()
                "publish_offsets" -> handlePublishOffsets(args.optInt("candidate_index", 0))
                "read_logs" -> handleReadLogs()
                "reboot" -> handleReboot(args.optString("reason", ""))
                "list_playbooks" -> handleListPlaybooks()
                "load_playbook" -> handleLoadPlaybook(args.optString("id", ""))
                "save_playbook" -> handleSavePlaybook(args.optString("notes", ""))
                "delete_playbook" -> handleDeletePlaybook(args.optString("id", ""))
                "read_crash_logs" -> handleReadCrashLogs()
                "clear_crash_logs" -> { crashLogs?.clear(); Triple(0, "crash logs cleared", 0) }
                else -> Triple(-1, "unknown tool: $name. Available: get_device_info, run_exploit, parse_ota, select_cpu_pair, toggle_safe_mode, execute_shell, check_root, import_offsets, export_offsets, publish_offsets, read_logs, reboot, list_playbooks, load_playbook, save_playbook, delete_playbook, read_crash_logs, clear_crash_logs", 0)
            }
            AiToolResult(exitCode = exitCode, output = output, durationMs = System.currentTimeMillis() - start)
        } catch (e: Exception) {
            AiToolResult(
                exitCode = -1,
                output = "exception: ${e::class.simpleName}: ${e.message}",
                durationMs = System.currentTimeMillis() - start,
            )
        }
    }

    private suspend fun handleGetDeviceInfo(): Triple<Int, String, Int> {
        val snapshot = repository.snapshot()
        val offsets = repository.exportCandidates()
        val output = buildString {
            appendLine("device: ${snapshot.deviceName}")
            appendLine("soc: ${snapshot.socName}")
            appendLine("kernel: ${snapshot.kernelRelease}")
            appendLine("kernel_supported: ${snapshot.kernelSupported}")
            appendLine("safe_mode: ${snapshot.safeModeEnabled}")
            appendLine("selected_cpu_pair: ${snapshot.cpuPairIndexLabel(snapshot.selectedCpuPair)}")
            appendLine("available_cpu_pairs:")
            snapshot.cpuPairLabels.forEachIndexed { i, label -> appendLine("  [$i] $label") }
            appendLine("known_offset_candidates: ${offsets.size}")
            if (offsets.isNotEmpty()) {
                offsets.take(10).forEachIndexed { i, c ->
                    appendLine("  [$i] release=${c.release} json=${c.json.take(200)}")
                }
            }
        }
        return Triple(0, output.trim(), 0)
    }

    private suspend fun handleRunExploit(cpuPairIndex: Int, retry: Int): Triple<Int, String, Int> {
        val snapshot = repository.snapshot()
        if (!snapshot.kernelSupported) {
            return Triple(1, "kernel not supported: ${snapshot.kernelRelease}. Parse OTA or import offsets first. Call parse_ota with the device's OTA URL.", 0)
        }
        if (cpuPairIndex >= 0 && cpuPairIndex in snapshot.cpuPairs.indices) {
            repository.selectCpuPair(cpuPairIndex)
        }
        val pair = snapshot.cpuPairs.getOrNull(if (cpuPairIndex >= 0) cpuPairIndex else snapshot.selectedCpuPair)
            ?: return Triple(1, "invalid CPU pair index. Available: ${snapshot.cpuPairs.indices}", 0)
        exploitRetryCount++
        lastSuccessfulPair = if (cpuPairIndex >= 0) cpuPairIndex else snapshot.selectedCpuPair
        // 崩溃记录：在执行 exploit 前标记，如果 App 下次启动时发现 pending，说明上次花屏重启了
        crashLogs?.markPreExploit(
            deviceName = snapshot.deviceName,
            kernelRelease = snapshot.kernelRelease,
            socName = snapshot.socName,
            cpuPairIndex = lastSuccessfulPair,
            safeMode = lastSafeMode,
            otaInput = lastOtaInput,
        )
        val logs = mutableListOf<String>()
        if (retry > 0) logs.add("=== Attempt #${retry + 1} (pair=$pair) ===")
        val code = repository.runExploit(pair) { logs.add(it) }
        // exploit 正常返回，清除崩溃标记（没花屏）
        crashLogs?.clearPending()
        val result = buildString {
            append(logs.joinToString("\n"))
            appendLine()
            append("exit_code=$code")
            if (code == 0) appendLine(" — SUCCESS! Root should be obtained. Call check_root to verify, then save_playbook to record this recipe.")
            else appendLine(" — FAILED. Analyze the log above, try a different CPU pair (select_cpu_pair), toggle safe mode, or check kernel compatibility.")
        }
        return Triple(code, result.trim(), 0)
    }

    private suspend fun handleParseOta(input: String, isBootImg: Boolean, overwrite: Boolean): Triple<Int, String, Int> {
        if (input.isBlank()) return Triple(1, "input is required: OTA ZIP URL/path or boot.img path", 0)
        lastOtaInput = input
        val logs = mutableListOf<String>()
        val xbl = if (isBootImg) input else null
        val result = repository.parseSource(input, xbl, overwrite) { logs.add(it) }
        val output = buildString {
            appendLine(logs.joinToString("\n"))
            appendLine("---")
            appendLine(
                when (result) {
                    is ParseResult.Parsed -> "SUCCESS: offsets parsed for ${result.releases.joinToString()}. Kernel should now be supported. Call get_device_info to verify, then run_exploit."
                    is ParseResult.RequiresOverwrite -> "REQUIRES_OVERWRITE: offsets already exist. Call parse_ota again with overwrite=true to replace them."
                    ParseResult.AlreadyPresent -> "ALREADY_PRESENT: offsets for this kernel are already installed. Call get_device_info to verify."
                    is ParseResult.Failed -> "FAILED (code=${result.code}): ${result.reason ?: "unknown error"}. Check that the input is a valid OTA ZIP or boot.img."
                }
            )
        }
        val ok = result is ParseResult.Parsed || result is ParseResult.AlreadyPresent
        return Triple(if (ok) 0 else 1, output.trim(), 0)
    }

    private suspend fun handleImportOffsets(json: String, confirm: Boolean): Triple<Int, String, Int> {
        if (json.isBlank()) return Triple(1, "json is required", 0)
        val result = if (confirm) repository.confirmImport(json) else repository.importOffsets(json)
        val output = buildString {
            appendLine("import: $result")
            appendLine("kernelSupported: ${repository.snapshot().kernelSupported}")
        }
        return Triple(0, output.trim(), 0)
    }

    private suspend fun handleExportOffsets(): Triple<Int, String, Int> {
        val candidates = repository.exportCandidates()
        val arr = JSONArray()
        candidates.forEach { c ->
            arr.put(JSONObject().apply {
                put("release", c.release)
                put("json", c.json)
            })
        }
        return Triple(0, arr.toString(2), 0)
    }

    private suspend fun handlePublishOffsets(candidateIndex: Int): Triple<Int, String, Int> {
        return try {
            val candidates = repository.exportCandidates()
            val c = candidates.getOrNull(candidateIndex)
                ?: return Triple(1, "candidate index $candidateIndex out of range (have ${candidates.size})", 0)
            val url = repository.publishOffsets(c)
            Triple(0, "Published offsets to: $url", 0)
        } catch (e: Exception) {
            Triple(1, "publish failed: ${e.message}", 0)
        }
    }

    private fun handleReadLogs(): Triple<Int, String, Int> {
        return handleExecuteShell("cat /data/local/tmp/ghostlock*.log 2>/dev/null || ls -la /data/local/tmp/ 2>/dev/null || echo 'no logs found'", 5)
    }

    private fun handleReboot(reason: String): Triple<Int, String, Int> {
        return handleExecuteShell("su -c 'reboot' 2>&1 || echo 'reboot requires root (su). reason: $reason'", 10)
    }

    private fun handleExecuteShell(command: String, timeout: Long): Triple<Int, String, Int> {
        if (command.isBlank()) return Triple(1, "command is empty", 0)
        // ---- 安全边界：拦截可能导致设备变砖的危险命令 ----
        val blocked = checkDangerousCommand(command)
        if (blocked != null) {
            return Triple(2, "BLOCKED by safety guard: $blocked\nThis command could brick the device. GhostLock only supports in-memory temporary root; it does not write to partitions. Use read-only diagnostics (cat, ls, id, getprop, dmesg, /proc reads).", 0)
        }
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .directory(filesDir)
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(timeout, TimeUnit.SECONDS)
            val code = if (finished) process.exitValue() else -1
            if (!finished) process.destroyForcibly()
            val output = buildString {
                if (stdout.isNotBlank()) append(stdout)
                if (stderr.isNotBlank()) {
                    if (isNotEmpty()) appendLine()
                    appendLine("[stderr]"); append(stderr)
                }
            }.trim()
            Triple(code, output.ifEmpty { "(empty output)" }, 0)
        } catch (e: Exception) {
            Triple(-1, "execution error: ${e.message}", 0)
        }
    }

    /** 检查命令是否危险（可能刷砖），返回 null 表示安全，否则返回危险原因 */
    private fun checkDangerousCommand(cmd: String): String? {
        val lower = cmd.lowercase().trim()
        val dangerousPatterns = listOf(
            // 写分区/闪存操作
            "dd if=" to "dd写入块设备会破坏分区",
            "dd of=" to "dd写入块设备会破坏分区",
            "flash_image" to "flash_image直接刷写镜像",
            "flash_erase" to "flash_erase擦除闪存",
            "erase /dev/block" to "擦除块设备",
            "erase dev/block" to "擦除块设备",
            // 格式化
            "mkfs" to "格式化文件系统",
            "make_ext4fs" to "制作ext4镜像并写入",
            "mke2fs" to "格式化ext2/3/4",
            // 删除关键分区/目录
            "rm -rf /dev" to "删除/dev目录",
            "rm -rf /system" to "删除/system分区",
            "rm -rf /vendor" to "删除/vendor分区",
            "rm -rf /data" to "删除/data分区",
            "rm -rf /boot" to "删除/boot分区",
            "rm -rf / " to "递归删除根目录",
            "rm -rf /*" to "递归删除根目录",
            // fastboot/bootloader
            "reboot bootloader" to "重启到bootloader，配合错误操作会砖",
            "reboot fastboot" to "重启到fastboot",
            "reboot edl" to "重启到EDL模式",
            "fastboot flash" to "fastboot刷写",
            "fastboot erase" to "fastboot擦除",
            // 直接写块设备
            "of=/dev/block" to "写入块设备",
            "of=/dev/zero" to "写入/dev/zero",
            // 挂载分区读写（危险）
            "mount -o remount,rw /system" to "重挂载/system为可写",
            "mount -o remount,rw /vendor" to "重挂载/vendor为可写",
            "mount -o remount,rw / " to "重挂载根分区为可写",
            // 修改内核/SELinux状态（通过exploit本身做，不允许shell直接改）
            "setenforce 1" to "强制SELinux可能导致崩溃",
            "echo 0 > /proc/sys/kernel" to "修改内核参数",
            // 写入boot镜像
            "boot.img" to "boot.img相关写入操作",
            "recovery.img" to "recovery.img相关写入操作",
        )
        for ((pattern, reason) in dangerousPatterns) {
            if (lower.contains(pattern)) return reason
        }
        return null
    }

    private fun handleCheckRoot(): Triple<Int, String, Int> {
        val idResult = handleExecuteShell("id 2>&1", 5)
        val suResult = handleExecuteShell("su -c 'id' 2>&1", 5)
        val output = buildString {
            appendLine("shell id: ${idResult.second.trim()}")
            appendLine("su id: ${suResult.second.trim()}")
            val hasRoot = suResult.second.contains("uid=0")
            append("root: " + if (hasRoot) "YES" else "NO")
        }
        return Triple(if (output.contains("root: YES")) 0 else 1, output, 0)
    }

    private fun com.ghostlock.app.domain.model.KernelSnapshot.cpuPairIndexLabel(index: Int): String =
        cpuPairLabels.getOrNull(index) ?: cpuPairs.getOrNull(index)?.toString() ?: "unknown"

    // ---- 流程库 ----

    private suspend fun handleListPlaybooks(): Triple<Int, String, Int> {
        val pb = playbooks ?: return Triple(1, "playbook repository not available", 0)
        val list = pb.list()
        if (list.isEmpty()) return Triple(0, "No saved playbooks yet. Run the exploit successfully and call save_playbook to record it.", 0)
        val output = buildString {
            appendLine("Saved playbooks (${list.size}):")
            list.forEach { p ->
                appendLine("---")
                appendLine("id: ${p.id}")
                appendLine("device: ${p.deviceName} (${p.socName.ifEmpty { "unknown SoC" }})")
                appendLine("kernel: ${p.kernelRelease}")
                appendLine("ota_url: ${p.otaUrl.ifEmpty { "(none — offsets imported)" }}")
                appendLine("cpu_pair: ${p.cpuPairIndex}")
                appendLine("safe_mode: ${p.safeMode}")
                appendLine("retries: ${p.retryCount}")
                if (p.notes.isNotBlank()) appendLine("notes: ${p.notes}")
            }
        }
        return Triple(0, output.trim(), 0)
    }

    private suspend fun handleLoadPlaybook(id: String): Triple<Int, String, Int> {
        val pb = playbooks ?: return Triple(1, "playbook repository not available", 0)
        val p = pb.list().firstOrNull { it.id == id }
            ?: return Triple(1, "playbook not found: $id. Call list_playbooks to see available ids.", 0)
        val output = buildString {
            appendLine("Playbook: ${p.deviceName} / ${p.kernelRelease}")
            appendLine()
            appendLine("RECOMMENDED STEPS:")
            if (p.otaUrl.isNotBlank()) {
                appendLine("1. Call parse_ota with input=${p.otaUrl} (is_boot_img=false)")
            } else {
                appendLine("1. Offsets are already imported (no OTA URL recorded)")
            }
            appendLine("2. Call select_cpu_pair index=${p.cpuPairIndex}")
            if (p.safeMode) appendLine("3. Call toggle_safe_mode enabled=true")
            else appendLine("3. Safe mode not needed (false)")
            appendLine("4. Call run_exploit (may need ~${p.retryCount} attempts due to race)")
            appendLine("5. Call check_root to verify")
            if (p.notes.isNotBlank()) {
                appendLine()
                appendLine("NOTES: ${p.notes}")
            }
        }
        return Triple(0, output.trim(), 0)
    }

    private suspend fun handleSavePlaybook(notes: String): Triple<Int, String, Int> {
        val pb = playbooks ?: return Triple(1, "playbook repository not available", 0)
        val snap = repository.snapshot()
        val id = "pb_${System.currentTimeMillis()}"
        pb.save(
            PlaybookRepository.Playbook(
                id = id,
                deviceName = snap.deviceName,
                socName = snap.socName,
                kernelRelease = snap.kernelRelease,
                otaUrl = lastOtaInput,
                cpuPairIndex = if (lastSuccessfulPair >= 0) lastSuccessfulPair else snap.selectedCpuPair,
                safeMode = lastSafeMode,
                retryCount = exploitRetryCount.coerceAtLeast(1),
                notes = notes,
                createdAt = System.currentTimeMillis(),
            )
        )
        return Triple(0, "Playbook saved (id=$id) for ${snap.deviceName} / ${snap.kernelRelease}. Future sessions will auto-suggest this recipe.", 0)
    }

    private suspend fun handleDeletePlaybook(id: String): Triple<Int, String, Int> {
        val pb = playbooks ?: return Triple(1, "playbook repository not available", 0)
        pb.delete(id)
        return Triple(0, "Playbook deleted: $id", 0)
    }

    private fun handleReadCrashLogs(): Triple<Int, String, Int> {
        val cl = crashLogs ?: return Triple(1, "crash log repository not available", 0)
        val list = cl.list()
        if (list.isEmpty()) return Triple(0, "No recorded crashes.", 0)
        val output = buildString {
            appendLine("⚠ Recorded crashes (${list.size}):")
            list.forEach { c ->
                appendLine("---")
                appendLine("time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(c.timestamp))}")
                appendLine("device: ${c.deviceName} (${c.socName})")
                appendLine("kernel: ${c.kernelRelease}")
                appendLine("cpu_pair: ${c.cpuPairIndex}")
                appendLine("safe_mode: ${c.safeMode}")
                if (c.otaInput.isNotBlank()) appendLine("ota_input: ${c.otaInput}")
                appendLine()
                appendLine("⚠ AVOID cpu_pair=${c.cpuPairIndex} on this kernel! It caused a kernel panic/reboot.")
            }
        }
        return Triple(0, output.trim(), 0)
    }

    // helpers
    private fun emptyObj() = JSONObject()
    private fun obj(block: JSONObject.() -> Unit) = JSONObject().apply(block)
    private fun fn(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()): JSONObject {
        val params = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", desc)
                put("parameters", params)
            })
        }
    }
}
