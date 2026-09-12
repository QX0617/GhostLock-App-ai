package com.ghostlock.app.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import com.ghostlock.app.ai.AiProvider

// 现代白底金属风格
private val Bg = Color(0xFFF5F6FA)
private val Surface = Color(0xFFFFFFFF)
private val CardBg = Color(0xFFFFFFFF)
private val CardActive = Color(0xFFEEF1FF)
private val Accent = Color(0xFF4F6BED)
private val AccentDim = Color(0xFF7C93F5)
private val TextPrimary = Color(0xFF1A1D27)
private val TextDim = Color(0xFF8B8FA3)
private val Danger = Color(0xFFDC2626)
private val BorderLight = Color(0xFFE2E5F0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    viewModel: AiSettingsViewModel,
    onBack: () -> Unit,
) {
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AiProvider?>(null) }

    MaterialTheme(colorScheme = androidx.compose.material3.lightColorScheme(
        primary = Accent, background = Bg, surface = Surface,
        onPrimary = Color.White, onBackground = TextPrimary, onSurface = TextPrimary,
    )) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("AI 服务商", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("配置模型连接，支持 OpenAI 兼容接口", color = TextDim, fontSize = 12.sp)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = TextPrimary)
                        }
                    },
                    actions = {
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clip(CircleShape)
                                .background(AccentDim)
                        ) {
                            IconButton(onClick = { showDialog = true; editing = null }) {
                                Icon(Icons.Default.Add, contentDescription = "添加", tint = Color.White)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
                )
            },
            containerColor = Bg,
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // 顶部信息卡片
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AccentDim),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("DS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("已预制 DeepSeek", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(
                                "默认 deepseek-chat，支持上下文缓存。填入 API Key 即可使用。",
                                color = TextDim, fontSize = 12.sp, lineHeight = 17.sp,
                            )
                        }
                    }
                }

                // 工具调用次数滑块
                val maxIter by viewModel.maxIter.collectAsStateWithLifecycle()
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .border(1.dp, BorderLight, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("最大工具调用次数", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Spacer(modifier = Modifier.weight(1f))
                            Text("$maxIter", color = Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Text(
                            "AI 每次会话最多自动调用工具的轮次。竞态漏洞可能需要多次重试，建议 20-40。",
                            color = TextDim, fontSize = 11.sp, lineHeight = 16.sp,
                        )
                        androidx.compose.material3.Slider(
                            value = maxIter.toFloat(),
                            onValueChange = { viewModel.setMaxIter(it.toInt()) },
                            valueRange = 5f..50f,
                            steps = 44,
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = Accent,
                                activeTrackColor = Accent,
                            ),
                        )
                    }
                }

                LazyColumn(
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(providers, key = { it.id }) { p ->
                        ProviderCard(
                            provider = p,
                            isActive = p.id == activeId,
                            onSetActive = { viewModel.setActive(p.id) },
                            onEdit = { editing = p; showDialog = true },
                            onDelete = { viewModel.delete(p.id) },
                        )
                    }
                }
            }
        }
    }

    if (showDialog) {
        ProviderEditDialog(
            initial = editing,
            onDismiss = { showDialog = false },
            onConfirm = { name, url, key, model ->
                if (editing != null) {
                    viewModel.addOrUpdate(editing!!.copy(name = name, baseUrl = url, apiKey = key, model = model))
                } else {
                    viewModel.addOrUpdate(
                        AiProvider(
                            id = "p_${System.nanoTime()}",
                            name = name, baseUrl = url, apiKey = key, model = model,
                        )
                    )
                }
                showDialog = false
            },
        )
    }
}

@Composable
private fun ProviderCard(
    provider: AiProvider,
    isActive: Boolean,
    onSetActive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderLight, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (isActive) CardActive else CardBg),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isActive) Accent else Surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        provider.name.take(1).uppercase(),
                        color = if (isActive) Color.White else TextDim,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(provider.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(provider.model, color = TextDim, fontSize = 12.sp)
                }
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Accent)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text("当前", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                provider.baseUrl,
                color = TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            )
            Text(
                "Key: ${if (provider.apiKey.isBlank()) "未设置" else provider.apiKey.take(6) + "••••"}",
                color = TextDim, fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!isActive) {
                    Button(
                        onClick = onSetActive,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) { Text("启用", fontSize = 12.sp, color = Color.White) }
                }
                TextButton(onClick = onEdit, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.width(14.dp), tint = TextDim)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("编辑", fontSize = 12.sp, color = TextDim)
                }
                if (provider.id != "deepseek") {
                    TextButton(onClick = onDelete, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.width(14.dp), tint = Danger)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("删除", fontSize = 12.sp, color = Danger)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderEditDialog(
    initial: AiProvider?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.baseUrl ?: "https://api.deepseek.com/v1") }
    var key by remember { mutableStateOf(initial?.apiKey ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "deepseek-chat") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial != null) "编辑服务商" else "添加服务商", color = TextPrimary, fontWeight = FontWeight.Bold) },
        containerColor = Surface,
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称", color = TextDim) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("Base URL", color = TextDim) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("API Key", color = TextDim) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text("模型名称", color = TextDim) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, url, key, model) },
                enabled = name.isNotBlank() && url.isNotBlank() && model.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) { Text("保存", color = Color.White) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextDim) }
        },
    )
}
