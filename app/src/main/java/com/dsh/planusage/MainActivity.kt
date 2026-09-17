package com.dsh.planusage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme { AppScreen() }
        }
    }
}

// ── 主题：只调色板，不引 Material Components 主题依赖 ────────────────────

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF8CCDF2),
            background = Color(0xFF0B1014),
            surface = Color(0xFF131A20),
            surfaceVariant = Color(0xFF232C33),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0B6FA4),
            background = Color(0xFFF2F5F8),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFE3E8ED),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ── 主界面 ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen() {
    val context = LocalContext.current.applicationContext
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()

    var openCodeKey by remember { mutableStateOf(prefs.openCodeKey) }
    var commandCodeKey by remember { mutableStateOf(prefs.commandCodeKey) }
    var openCode by remember { mutableStateOf<ProviderSnapshot?>(null) }
    var commandCode by remember { mutableStateOf<ProviderSnapshot?>(null) }
    var openCodeBusy by remember { mutableStateOf(false) }
    var commandCodeBusy by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    fun refreshOpenCode() {
        val key = openCodeKey.trim()
        if (key.isEmpty()) {
            openCode = ProviderSnapshot(false, error = "请先填入 OpenCode Go 的 API Key")
            return
        }
        openCodeBusy = true
        scope.launch {
            openCode = UsageApi.fetchOpenCodeGo(key)
            openCodeBusy = false
        }
    }

    fun refreshCommandCode() {
        val key = commandCodeKey.trim()
        if (key.isEmpty()) {
            commandCode = ProviderSnapshot(false, error = "请先填入 Command Code 的 API Key")
            return
        }
        commandCodeBusy = true
        scope.launch {
            commandCode = UsageApi.fetchCommandCode(key)
            commandCodeBusy = false
        }
    }

    // 倒计时每 30 秒自走一格。
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    // 已保存过 Key 的话，冷启动自动拉一次。
    LaunchedEffect(Unit) {
        if (openCodeKey.isNotBlank()) refreshOpenCode()
        if (commandCodeKey.isNotBlank()) refreshCommandCode()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("套餐用量", fontWeight = FontWeight.SemiBold)
                        Text(
                            "OpenCode Go · Command Code GOAT",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (openCodeBusy || commandCodeBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    IconButton(onClick = { refreshOpenCode(); refreshCommandCode() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "全部刷新")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProviderCard(
                title = "OpenCode Go",
                endpoint = "GET opencode.ai/zen/go/v1/usage",
                accent = Color(0xFF3BA7E0),
                keyText = openCodeKey,
                onKeyChange = { openCodeKey = it; prefs.openCodeKey = it },
                onRefresh = { refreshOpenCode() },
                busy = openCodeBusy,
                snapshot = openCode,
                now = now,
            )
            ProviderCard(
                title = "Command Code GOAT",
                endpoint = "GET api.commandcode.ai/alpha/billing/credits",
                accent = Color(0xFF9B6BE0),
                keyText = commandCodeKey,
                onKeyChange = { commandCodeKey = it; prefs.commandCodeKey = it },
                onRefresh = { refreshCommandCode() },
                busy = commandCodeBusy,
                snapshot = commandCode,
                now = now,
            )
            Text(
                "Key 只保存在本机应用私有存储，且只发往对应服务商的官方域名。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

// ── 服务商卡片 ───────────────────────────────────────────────────────────

@Composable
private fun ProviderCard(
    title: String,
    endpoint: String,
    accent: Color,
    keyText: String,
    onKeyChange: (String) -> Unit,
    onRefresh: () -> Unit,
    busy: Boolean,
    snapshot: ProviderSnapshot?,
    now: Long,
) {
    var visible by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(accent),
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        endpoint,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新 $title")
                    }
                }
            }

            OutlinedTextField(
                value = keyText,
                onValueChange = onKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                placeholder = { Text(if (title.startsWith("Open")) "Bearer …" else "user_…") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                trailingIcon = {
                    // material-icons-core 里没有 Visibility 图标，直接用小字开关，
                    // 手机上比 24dp 图标更好点。
                    Text(
                        if (visible) "隐藏" else "显示",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { visible = !visible }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                },
            )

            CompactButton(text = "刷新", enabled = !busy, accent = accent, onClick = onRefresh)

            val snap = snapshot
            if (snap == null) {
                Text(
                    if (keyText.isBlank()) "填入 API Key 后点「刷新」查看 5 小时 / 周 / 月度用量。"
                    else "尚未查询，点「刷新」拉取用量。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!snap.ok) {
                Text(
                    snap.error ?: "查询失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                snap.windows.forEach { WindowRow(it, now) }
                if (snap.facts.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    snap.facts.forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(value, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Text(
                    "更新于 ${clockTime(snap.queriedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CompactButton(text: String, enabled: Boolean, accent: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(42.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent),
    ) {
        Text(text, fontWeight = FontWeight.Medium)
    }
}

// ── 单个额度窗口：标题 / 百分比 / 进度条 / 金额 / 重置倒计时 ──────────────

@Composable
private fun WindowRow(window: UsageWindow, now: Long) {
    val fraction = ((window.percent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    val tint = usageTint(window.percent)

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                window.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            window.badge?.let { badge ->
                Spacer(Modifier.width(6.dp))
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                percentText(window.percent),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = tint,
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .clip(RoundedCornerShape(50))
                        .background(tint),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                window.usedText ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (window.showCountdown && window.resetsAtMs != null) {
                Text(
                    countdown(window.resetsAtMs, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (window.resetsAtMs != null && window.percent == 0.0) {
                Text(
                    "未开始（重置时间为占位值）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 绿 → 琥珀 → 红，与 cc-switch 的用量配色一致。 */
private fun usageTint(percent: Double?): Color = when {
    percent == null -> Color(0xFF8A94A0)
    percent >= 85 -> Color(0xFFD9483B)
    percent >= 60 -> Color(0xFFE0A400)
    else -> Color(0xFF2E9E5B)
}
