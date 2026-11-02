package com.nanoturtle.rootexec

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LineKind { CMD, OUT, ERR, SYS }

data class ConsoleLine(val text: String, val kind: LineKind)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 电脑远程调试通道：127.0.0.1:27182（电脑先 adb forward tcp:27182 tcp:27182）
        DebugServer.sockAppContext = applicationContext
        RemoteAuth.init(applicationContext)
        DebugServer.start(applicationContext)
        setContent { RootExecTheme { RootExecApp() } }
    }
}

@Composable
fun RootExecTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        android.os.Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootExecApp() {
    var tab by remember { mutableIntStateOf(0) }
    var rootState by remember { mutableStateOf<RootState>(RootState.Checking) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            rootState = if (Shell.rootAuthorized()) RootState.Granted else RootState.Denied
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("RootExec", fontWeight = FontWeight.SemiBold) },
                actions = { RootBadge(rootState, Modifier.padding(end = 12.dp)) }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("信息") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("控制台") })
            }
            when (tab) {
                0 -> InfoTab(rootState)
                else -> ConsoleTab()
            }
        }
    }
}

@Composable
private fun RootBadge(state: RootState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (bg, fg) = when (state) {
        is RootState.Granted -> colors.primaryContainer to colors.onPrimaryContainer
        is RootState.Denied -> colors.errorContainer to colors.onErrorContainer
        else -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Surface(color = bg, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(
            state.display,
            color = fg,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun InfoTab(rootState: RootState) {
    val context = LocalContext.current
    val info by produceState(initialValue = emptyList<InfoRow>(), rootState) {
        while (true) {
            value = try {
                Shell.collectInfo(context, rootState) + InfoRow(
                    "远程调试",
                    if (DebugServer.running) "127.0.0.1:27182（adb forward tcp:27182 tcp:27182）" else "未启动"
                )
            } catch (t: Throwable) {
                listOf(InfoRow("采集失败", t.message ?: "?"))
            }
            delay(2000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("设备与系统", style = MaterialTheme.typography.titleMedium)
                info.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            row.label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            row.value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }
        }
        SelinuxCard()
        RemoteAuthCard()
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("电脑远程调试", style = MaterialTheme.typography.titleSmall)
                Text(
                    "adb forward tcp:27182 tcp:27182\n" +
                        "curl -s --data-binary $'PING\\n' --no-buffer telnet://127.0.0.1:27182\n" +
                        "或使用 nc 127.0.0.1 27182 发送 PING/INFO/CMD <base64>",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("危险命令已禁用", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                Text(
                    Shell.BLOCKED_COMMANDS.sorted().joinToString("  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "控制台中的命令会在执行前检查；rm/rmdir/dd 等破坏性命令以及 sh -c / su -c 包装内的同类命令都会被拦截。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SelinuxCard() {
    val enforcing by produceState(initialValue = Shell.selinuxEnforcing()) {
        while (true) {
            value = Shell.selinuxEnforcing()
            delay(1000)
        }
    }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("SELinux 模式", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(
                    checked = enforcing == false,
                    enabled = !busy && enforcing != null,
                    onCheckedChange = { wantPermissive ->
                        busy = true
                        result = null
                        scope.launch(Dispatchers.IO) {
                            val (code, out) = Shell.setSelinux(!wantPermissive)
                            withContext(Dispatchers.Main) {
                                result = if (code == 0) {
                                    out.ifEmpty { if (wantPermissive) "已切到 Permissive" else "已切回 Enforcing" }
                                } else {
                                    "失败(exit=" + code + ")：" + out.takeLast(120)
                                }
                                busy = false
                            }
                        }
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when (enforcing) {
                        null -> "未知"
                        true -> "Enforcing（打开开关=Permissive）"
                        false -> "Permissive"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            result?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RemoteAuthCard() {
    val state by produceState(
        initialValue = Triple(
            RemoteAuth.allowDangerous,
            RemoteAuth.snapshot(),
            DebugServer.remoteLog.toList()
        )
    ) {
        while (true) {
            value = Triple(RemoteAuth.allowDangerous, RemoteAuth.snapshot(), DebugServer.remoteLog.toList())
            delay(1000)
        }
    }
    val allow = state.first
    val allowed = state.second.sorted()
    val log = state.third.takeLast(6)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("远程授权", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(
                    checked = allow,
                    onCheckedChange = { RemoteAuth.setAllowDangerous(it) }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (allow) "已允许远程危险命令（仅限列表内）" else "禁止远程危险命令",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                "已授权（点击移除）：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (allowed.isEmpty()) Text("（空）", style = MaterialTheme.typography.bodySmall)
                allowed.forEach { c ->
                    AssistChip(
                        onClick = { RemoteAuth.remove(c) },
                        label = { Text(c, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                    )
                }
            }
            Text(
                "点击添加授权：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Shell.BLOCKED_COMMANDS.sorted().filter { it !in allowed }.forEach { c ->
                    AssistChip(
                        onClick = { RemoteAuth.add(c) },
                        label = { Text("+ $c", fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                    )
                }
            }
            Text(
                "远程执行记录：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (log.isEmpty()) {
                Text("（无）", style = MaterialTheme.typography.bodySmall)
            } else {
                log.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

private val QUICK_COMMANDS = listOf(
    "id", "getenforce", "uptime", "ps -A | head -20",
    "ls /data/adb/modules", "dmesg | tail -30", "su -v"
)

@Composable
private fun ConsoleTab() {
    val lines = remember { mutableStateListOf<ConsoleLine>() }
    val history = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf<String?>(null) }
    var lastExit by remember { mutableStateOf<Int?>(null) }
    var lastMs by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    fun execute(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty() || running) return
        val danger = Shell.findDangerous(cmd)
        if (danger != null) {
            blocked = "已拦截危险命令：$danger（该命令被禁用）"
            return
        }
        blocked = null
        if (history.size >= 50) history.removeAt(0)
        history.add(cmd)
        input = ""
        lines.add(ConsoleLine("$ $cmd", LineKind.CMD))
        running = true
        lastExit = null
        lastMs = null
        scope.launch {
            val t0 = System.currentTimeMillis()
            val res = withContext(Dispatchers.IO) {
                Shell.runRootCommand(
                    cmd,
                    onOut = { l -> mainHandler.post { lines.add(ConsoleLine(l, LineKind.OUT)) } },
                    onErr = { l -> mainHandler.post { lines.add(ConsoleLine(l, LineKind.ERR)) } }
                )
            }
            val ms = System.currentTimeMillis() - t0
            mainHandler.post {
                running = false
                lastExit = res.exitCode
                lastMs = ms
                lines.add(ConsoleLine("[exit=${res.exitCode} · ${ms}ms · ${lines.size} 行]", LineKind.SYS))
            }
        }
    }

    fun copyOutput() {
        val text = lines.joinToString("\n") { it.text }
        if (text.isEmpty()) return
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("RootExec", text))
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // 快捷命令
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QUICK_COMMANDS.forEach { qc ->
                AssistChip(
                    onClick = { input = qc; execute(qc) },
                    label = { Text(qc, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                )
            }
        }
        // 输入
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            placeholder = { Text("输入命令（以 root 执行），例如 id / getenforce / ps -A | head") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            singleLine = false,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { execute(input) })
        )
        blocked?.let { msg ->
            Text(
                msg,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(onClick = { execute(input) }, enabled = !running) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("执行")
                }
            }
            TextButton(onClick = {
                val last = history.lastOrNull()
                if (last != null) input = last
            }, enabled = history.isNotEmpty()) { Text("上一条") }
            TextButton(onClick = {
                lines.clear(); lastExit = null; lastMs = null
            }) { Text("清空") }
            TextButton(onClick = { copyOutput() }, enabled = lines.isNotEmpty()) { Text("复制") }
            Spacer(Modifier.weight(1f))
            val status = when {
                running -> "运行中…"
                lastExit != null -> "exit=$lastExit · ${lastMs}ms"
                else -> "${lines.size} 行"
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(8.dp)
            ) {
                items(lines) { line ->
                    val color = when (line.kind) {
                        LineKind.CMD -> MaterialTheme.colorScheme.primary
                        LineKind.ERR -> MaterialTheme.colorScheme.error
                        LineKind.SYS -> MaterialTheme.colorScheme.onSurfaceVariant
                        LineKind.OUT -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        line.text.ifEmpty { " " },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = if (line.kind == LineKind.CMD) FontWeight.Bold else FontWeight.Normal,
                        color = color,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
