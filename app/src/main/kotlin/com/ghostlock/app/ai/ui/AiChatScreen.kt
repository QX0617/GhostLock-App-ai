package com.ghostlock.app.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghostlock.app.ai.AiChatMessage
import com.ghostlock.app.ai.AiRole
import com.ghostlock.app.ai.AiToolCall
import com.ghostlock.app.ai.AiToolStatus

// 现代白底金属风格配色
private val Bg = Color(0xFFF5F6FA)
private val Surface = Color(0xFFFFFFFF)
private val UserBubble = Color(0xFF4F6BED)
private val AiBubble = Color(0xFFF0F2F8)
private val ToolCard = Color(0xFFF7F8FC)
private val ToolCardExpanded = Color(0xFFFFFFFF)
private val Accent = Color(0xFF4F6BED)
private val TextPrimary = Color(0xFF1A1D27)
private val TextDim = Color(0xFF8B8FA3)
private val Success = Color(0xFF16A34A)
private val Failed = Color(0xFFDC2626)
private val Running = Color(0xFFD97706)
private val BorderLight = Color(0xFFE2E5F0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    viewModel: AiChatViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val crashCount by viewModel.crashCount.collectAsStateWithLifecycle()
    val playbookCount by viewModel.playbookCount.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var showCrashDialog by remember { mutableStateOf(false) }
    var showPlaybookDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let { snackbar.showSnackbar(it); viewModel.clearError() }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    MaterialTheme(colorScheme = androidx.compose.material3.lightColorScheme(
        primary = Accent,
        background = Bg,
        surface = Surface,
        onPrimary = Color.White,
        onBackground = TextPrimary,
        onSurface = TextPrimary,
    )) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("GhostLock AI", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(viewModel.getActiveProviderName(), color = TextDim, fontSize = 11.sp)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = TextPrimary)
                        }
                    },
                    actions = {
                        // 流程库按钮（成功配方，带数量角标）
                        androidx.compose.foundation.layout.Box {
                            IconButton(onClick = { viewModel.refreshPlaybookCount(); showPlaybookDialog = true }) {
                                Icon(
                                    Icons.Default.Bookmark,
                                    contentDescription = "流程库",
                                    tint = if (playbookCount > 0) Success else TextDim,
                                )
                            }
                            if (playbookCount > 0) {
                                androidx.compose.foundation.layout.Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Success)
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        playbookCount.toString(),
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                        // 崩溃日志按钮（带红色角标）
                        androidx.compose.foundation.layout.Box {
                            IconButton(onClick = { showCrashDialog = true }) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = "崩溃日志",
                                    tint = if (crashCount > 0) Failed else TextDim,
                                )
                            }
                            if (crashCount > 0) {
                                androidx.compose.foundation.layout.Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Failed)
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        crashCount.toString(),
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { viewModel.clear() }) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = TextDim)
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = TextDim)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = Bg,
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (messages.isEmpty()) {
                        AiEmptyState()
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                        ) {
                            items(messages, key = { it.id }) { msg -> AiMessageItem(msg) }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                if (viewModel.isApiKeySet()) "输入指令，如：检查设备并尝试提权" else "请先在设置中填入 API Key",
                                color = TextDim,
                                fontSize = 13.sp,
                            )
                        },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (input.isNotBlank() && !loading) {
                                viewModel.send(input)
                                input = ""
                            }
                        },
                        enabled = input.isNotBlank() && !loading,
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = null,
                            tint = if (input.isNotBlank() && !loading) Accent else TextDim,
                        )
                    }
                }
            }
        }
        if (showCrashDialog) {
            CrashLogDialog(
                viewModel = viewModel,
                onDismiss = { showCrashDialog = false },
                onCleared = { showCrashDialog = false },
            )
        }
        if (showPlaybookDialog) {
            PlaybookDialog(
                viewModel = viewModel,
                onDismiss = { showPlaybookDialog = false },
            )
        }
    }
}

@Composable
private fun CrashLogDialog(
    viewModel: AiChatViewModel,
    onDismiss: () -> Unit,
    onCleared: () -> Unit,
) {
    val crashes = remember { viewModel.getCrashLogs() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("崩溃记录 (${crashes.size})", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            if (crashes.isEmpty()) {
                Text("暂无崩溃记录", color = TextDim)
            } else {
                LazyColumn {
                    items(crashes) { c ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFFDE8E8))
                                .padding(10.dp),
                        ) {
                            Text(
                                android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", java.util.Date(c.timestamp)).toString(),
                                color = Failed, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            )
                            Text("设备: ${c.deviceName}", color = TextDim, fontSize = 12.sp)
                            Text("内核: ${c.kernelRelease}", color = TextDim, fontSize = 12.sp)
                            Text("CPU pair: ${c.cpuPairIndex}  |  Safe mode: ${c.safeMode}", color = TextDim, fontSize = 12.sp)
                            if (c.otaInput.isNotBlank()) Text("OTA: ${c.otaInput.take(60)}", color = TextDim, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { viewModel.clearCrashLogs(); onCleared() }) {
                Text("清空记录", color = Failed)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("关闭", color = Accent)
            }
        },
        containerColor = Surface,
    )
}

@Composable
private fun PlaybookDialog(
    viewModel: AiChatViewModel,
    onDismiss: () -> Unit,
) {
    var version by remember { mutableStateOf(0) }
    val playbooks = remember(version) { viewModel.getPlaybooks() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("流程库 (${playbooks.size})", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            if (playbooks.isEmpty()) {
                Text("暂无成功配方。root 成功后会自动记录，下次同设备可直接套用。", color = TextDim)
            } else {
                LazyColumn {
                    items(playbooks) { p ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFE8F5EC))
                                .padding(10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "✓ ${p.deviceName}",
                                        color = Success, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    )
                                    Text("内核: ${p.kernelRelease}", color = TextDim, fontSize = 11.sp)
                                }
                                IconButton(onClick = { viewModel.deletePlaybook(p.id); version++ }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = Failed, modifier = Modifier.size(20.dp))
                                }
                            }
                            Text("SoC: ${p.socName.ifEmpty { "未知" }}", color = TextDim, fontSize = 11.sp)
                            if (p.otaUrl.isNotBlank()) Text("OTA: ${p.otaUrl.take(60)}", color = TextDim, fontSize = 11.sp)
                            Text("CPU pair: ${p.cpuPairIndex}  |  Safe mode: ${p.safeMode}  |  重试: ${p.retryCount}", color = TextDim, fontSize = 11.sp)
                            if (p.notes.isNotBlank()) Text("备注: ${p.notes}", color = TextDim, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("关闭", color = Accent)
            }
        },
        containerColor = Surface,
    )
}

@Composable
private fun AiEmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("GhostLock AI", color = Accent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        Text("AI 驱动的自动化提权助手", color = TextDim, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            "试试说：\n• 检查设备信息\n• 解析这个 OTA：https://...\n• 尝试 GhostLock 提权\n• 换个 CPU 核心重试",
            color = TextDim,
            fontSize = 13.sp,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun AiMessageItem(msg: AiChatMessage) {
    when (msg.role) {
        AiRole.USER -> UserBubble(msg.content)
        AiRole.ASSISTANT -> AssistantBubble(msg)
        AiRole.TOOL -> ToolResultBubble(msg)
        AiRole.SYSTEM -> {}
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Spacer(modifier = Modifier.width(40.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                .background(UserBubble)
                .padding(12.dp),
        ) {
            Text(text, color = Color.White, fontSize = 15.sp)
        }
    }
}

@Composable
private fun AssistantBubble(msg: AiChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (msg.content.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                        .background(AiBubble)
                        .padding(12.dp),
                ) {
                    Text(
                        if (msg.streaming) msg.content + "▋" else msg.content,
                        color = TextPrimary,
                        fontSize = 15.sp,
                    )
                }
            }
            msg.toolCalls.forEach { ToolCallCard(it) }
        }
        Spacer(modifier = Modifier.width(40.dp))
    }
}

@Composable
private fun ToolCallCard(tc: AiToolCall) {
    var expanded by remember { mutableStateOf(false) }
    val statusColor = when (tc.status) {
        AiToolStatus.PENDING, AiToolStatus.RUNNING -> Running
        AiToolStatus.SUCCESS -> Success
        AiToolStatus.FAILED -> Failed
    }
    val statusText = when (tc.status) {
        AiToolStatus.PENDING -> "等待"
        AiToolStatus.RUNNING -> "执行中..."
        AiToolStatus.SUCCESS -> "成功"
        AiToolStatus.FAILED -> "失败"
    }
    val cmd = extractCommand(tc.arguments)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, start = 4.dp, end = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (expanded) ToolCardExpanded else ToolCard)
            .clickable { expanded = !expanded }
            .border(1.dp, BorderLight, RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        // 头部行：状态点 + 工具名 + 状态 + 展开箭头
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("●", color = statusColor, fontSize = 10.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(tc.name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.width(8.dp))
            Text(statusText, color = statusColor, fontSize = 11.sp)
            Spacer(modifier = Modifier.weight(1f))
            if (tc.result != null) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = TextDim,
                    modifier = Modifier.padding(2.dp),
                )
            }
        }

        // 收缩时：只显示命令第一行或摘要
        if (!expanded && cmd.isNotBlank()) {
            Text(
                cmd.take(120) + if (cmd.length > 120) "..." else "",
                color = TextDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // 展开时：完整输入输出
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                if (cmd.isNotBlank() || tc.arguments.isNotBlank()) {
                    Text("参数", color = TextDim, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    Text(
                        tc.arguments,
                        color = TextDim,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFF0F1F5))
                            .padding(8.dp),
                    )
                }
                tc.result?.let { r ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "退出码: ${r.exitCode}  ·  耗时: ${r.durationMs}ms",
                        color = if (r.isSuccess) Success else Failed,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (r.output.isNotBlank()) {
                        Text(
                            r.output,
                            color = TextDim,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFF0F1F5))
                                .padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolResultBubble(msg: AiChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(ToolCard)
                .padding(8.dp),
        ) {
            Text(
                "↳ ${msg.toolName ?: "tool"}",
                color = TextDim,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun extractCommand(args: String): String = try {
    val obj = org.json.JSONObject(args)
    val cmd = obj.optString("command", "")
    if (cmd.isNotBlank()) cmd else args
} catch (_: Exception) {
    args
}
