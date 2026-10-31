package com.nanoturtle.rootexec

import android.content.Context
import android.os.Build
import android.os.BatteryManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root 命令执行与信息采集。
 *
 * root 通道优先级：
 *  1. /system/bin/su（KernelSU / Magisk 的 sucompat）
 *  2. /data/local/tmp/cve-2026-43499-root（本机 43499 临时 root，helper 的 -c 模式）
 */
object Shell {
    private val SU_CANDIDATES = listOf("/system/bin/su", "/system/xbin/su")
    private const val FALLBACK_ROOT = "/data/local/tmp/cve-2026-43499-root"

    /** 危险命令黑名单：数据破坏类（用户要求至少 rm/rmdir/dd） */
    val BLOCKED_COMMANDS = setOf(
        "rm", "rmdir", "dd", "mkfs", "mke2fs", "mkswap", "wipe", "shred",
        "fdisk", "parted", "format", "tune2fs", "flash_erase", "blkdiscard"
    )

    fun suPath(): String? = SU_CANDIDATES.firstOrNull { File(it).exists() }

    /**
     * 递归检测危险命令：
     *  - 按 ; && || | & 换行拆分；
     *  - 取每段第一个 token 的 basename 与黑名单比对；
     *  - 对 `sh -c "..."`、`su -c "..."` 这类包装递归解析内部命令。
     * 返回命中的危险命令名，未命中返回 null。
     */
    fun findDangerous(command: String, depth: Int = 0): String? {
        if (depth > 3) return null
        val segments = command.split(";", "&&", "||", "|", "&", "\n")
        for (seg in segments) {
            val tokens = seg.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            val first = tokens[0].trim('\'', '"').substringAfterLast('/')
            if (first.isEmpty()) continue
            if (first in BLOCKED_COMMANDS) return first
            // 包装器：sh -c "rm ..." / su -c "rm ..." / toybox rm ...
            val cIdx = tokens.indexOfFirst { it == "-c" }
            if (cIdx >= 0 && cIdx + 1 < tokens.size) {
                val inner = tokens.drop(cIdx + 1).joinToString(" ").trim('\'', '"')
                findDangerous(inner, depth + 1)?.let { return it }
            }
            if (first == "toybox" || first == "busybox") {
                val second = tokens.getOrNull(1)?.substringAfterLast('/')
                if (second != null && second in BLOCKED_COMMANDS) return second
            }
        }
        return null
    }

    data class ExecResult(val lines: List<String>, val exitCode: Int, val root: Boolean)

    /** 执行 root 命令；stdout/stderr 分开回调（回调在读取线程，UI 层自行切主线程）。 */
    fun runRootCommand(
        command: String,
        onOut: (String) -> Unit = {},
        onErr: (String) -> Unit = {}
    ): ExecResult {
        val su = suPath()
        val attempts = mutableListOf<List<String>>()
        if (su != null) attempts.add(listOf(su, "-c", command))
        if (File(FALLBACK_ROOT).exists()) attempts.add(listOf(FALLBACK_ROOT, "-c", command))
        if (attempts.isEmpty()) {
            return ExecResult(listOf("没有可用的 root 通道（su 不存在）"), -1, false)
        }
        var lastErr = "执行失败"
        for ((idx, argv) in attempts.withIndex()) {
            try {
                val p = ProcessBuilder(argv).start()
                val stdout = java.util.Collections.synchronizedList(mutableListOf<String>())
                val stderr = java.util.Collections.synchronizedList(mutableListOf<String>())
                val tOut = Thread {
                    try {
                        p.inputStream.bufferedReader().useLines { seq ->
                            for (line in seq) {
                                if (stdout.size > 20000) break
                                stdout.add(line); onOut(line)
                            }
                        }
                    } catch (_: Throwable) {}
                }
                val tErr = Thread {
                    try {
                        p.errorStream.bufferedReader().useLines { seq ->
                            for (line in seq) {
                                if (stderr.size > 20000) break
                                stderr.add(line); onErr(line)
                            }
                        }
                    } catch (_: Throwable) {}
                }
                tOut.start(); tErr.start()
                val code = p.waitFor()
                tOut.join(2000); tErr.join(2000)
                if (code == 0 || idx == attempts.lastIndex) {
                    return ExecResult(stdout + stderr, code, true)
                }
                lastErr = "exit=$code"
            } catch (t: Throwable) {
                lastErr = t.message ?: t.toString()
            }
        }
        return ExecResult(listOf("执行失败：$lastErr"), -1, false)
    }

    /** 兼容旧调用：只关心合并输出。 */
    fun runRootCommandSimple(command: String, onLine: (String) -> Unit = {}): ExecResult =
        runRootCommand(command, onOut = onLine, onErr = onLine)

    /** 检测 root 是否已授权（su -c id，最多等 3 秒）。 */
    fun rootAuthorized(): Boolean {
        val su = suPath() ?: return false
        return try {
            val p = ProcessBuilder(listOf(su, "-c", "id")).redirectErrorStream(true).start()
            val finished = p.waitFor(3, TimeUnit.SECONDS)
            if (!finished) { p.destroyForcibly(); return false }
            p.exitValue() == 0
        } catch (t: Throwable) {
            false
        }
    }

    private fun readFile(path: String, maxLen: Int = 4096): String? = try {
        val f = File(path)
        if (!f.exists()) null else f.readText().take(maxLen)
    } catch (t: Throwable) {
        null
    }

    private fun memInfoKb(key: String): Long? {
        val text = readFile("/proc/meminfo", 8192) ?: return null
        return text.lineSequence().firstOrNull { it.startsWith("$key:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
    }

    private fun fmtKb(kb: Long?): String = when {
        kb == null -> "?"
        kb >= 1024 * 1024 -> String.format("%.2f GB", kb / 1024.0 / 1024.0)
        kb >= 1024 -> String.format("%.1f MB", kb / 1024.0)
        else -> "$kb KB"
    }

    /** 信息采集：大部分走 /proc、/sys，不需要 root；root 状态单独显示。 */
    fun collectInfo(context: Context, rootState: RootState): List<InfoRow> {
        val rows = mutableListOf<InfoRow>()
        rows.add(InfoRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}"))
        rows.add(InfoRow("Android", "${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"))
        rows.add(InfoRow("内核", System.getProperty("os.version") ?: "?"))
        rows.add(InfoRow("架构", Build.SUPPORTED_ABIS.firstOrNull() ?: "?"))

        val enforce = readFile("/sys/fs/selinux/enforce", 8)?.trim()
        val selinux = when (enforce) {
            "1" -> "Enforcing"
            "0" -> "Permissive"
            else -> "未知"
        }
        rows.add(InfoRow("SELinux", selinux))

        val modules = readFile("/proc/modules", 65536)
        val ksu = modules?.lineSequence()?.any { it.startsWith("kernelsu ") } == true
        rows.add(InfoRow("KernelSU", if (ksu) "已加载（late-load）" else "未加载"))

        val uptime = readFile("/proc/uptime")?.split(" ")?.firstOrNull()?.toDoubleOrNull()
        rows.add(InfoRow("运行时长", uptime?.let { fmtUptime(it.toLong()) } ?: "?"))

        rows.add(InfoRow("内存", "可用 ${fmtKb(memInfoKb("MemAvailable"))} / 空闲 ${fmtKb(memInfoKb("MemFree"))}"))
        rows.add(InfoRow("负载", readFile("/proc/loadavg")?.split(" ")?.take(3)?.joinToString(" ") ?: "?"))

        val battery = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        rows.add(InfoRow("电量", if (battery != null && battery >= 0) "$battery%" else "?"))

        rows.add(InfoRow("Root", rootState.display))
        rows.add(InfoRow("Root 通道", suPath() ?: (if (File(FALLBACK_ROOT).exists()) FALLBACK_ROOT else "无")))
        return rows
    }

    private fun fmtUptime(seconds: Long): String {
        val d = seconds / 86400
        val h = (seconds % 86400) / 3600
        val m = (seconds % 3600) / 60
        return if (d > 0) "${d}天${h}小时${m}分" else "${h}小时${m}分"
    }
}

data class InfoRow(val label: String, val value: String)

sealed class RootState(val display: String) {
    data object Checking : RootState("检测中…")
    data object Granted : RootState("已授权 root")
    data object Denied : RootState("未授权 / 无 root")
}
