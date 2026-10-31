package com.nanoturtle.rootexec

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                Shell.collectInfo(context, rootState)
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
private fun ConsoleTab() {
    val output = remember { mutableStateListOf<String>() }
    val history = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val listState = rememberLazyListState()

    LaunchedEffect(output.size) {
        if (output.isNotEmpty()) {
            listState.animateScrollToItem(output.lastIndex)
        }
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
        output.add("$ $cmd")
        running = true
        scope.launch {
            withContext(Dispatchers.IO) {
                Shell.runRootCommand(cmd) { line ->
                    mainHandler.post { output.add(line) }
                }
            }
            mainHandler.post {
                running = false
                output.add("")
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (blocked != null) {
            Text(
                blocked!!,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("输入命令（以 root 执行），例如 id / getenforce / uptime") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            singleLine = false,
            maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { execute(input) })
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { execute(input) }, enabled = !running) {
                if (running) {
                    CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("执行")
                }
            }
            TextButton(onClick = {
                val last = history.lastOrNull()
                if (last != null) input = last
            }, enabled = history.isNotEmpty()) { Text("上一条") }
            TextButton(onClick = { output.clear() }) { Text("清空输出") }
            Spacer(Modifier.weight(1f))
            Text("${output.size} 行", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(8.dp)
            ) {
                items(output) { line ->
                    Text(
                        line.ifEmpty { " " },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
