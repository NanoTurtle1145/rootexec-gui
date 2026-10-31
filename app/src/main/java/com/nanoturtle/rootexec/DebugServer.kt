package com.nanoturtle.rootexec

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64

/**
 * 电脑远程调试通道。
 *
 * App 进程内监听 127.0.0.1:27182（仅本机回环，外部不可直接访问）；
 * 电脑上先 `adb forward tcp:27182 tcp:27182`，然后即可用 curl/nc 控制。
 *
 * 协议（每行一条文本命令，回复为 JSON 行）：
 *   PING                -> {"ok":true,"pong":<ts>}
 *   INFO                -> {"ok":true,"info":[{"label":...,"value":...},...]}
 *   CMD <base64(cmd)>   -> 流式 {"type":"stdout"|"stderr","line":...}，最后
 *                          {"type":"exit","code":n,"ms":n}；危险命令返回
 *                          {"type":"blocked","command":"rm"}
 *   HELP                -> {"ok":true,"commands":[...]}
 *
 * 安全：只绑定 127.0.0.1；命令同样走 Shell.findDangerous 过滤。
 */
object DebugServer {
    const val PORT = 27182

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    var running: Boolean = false
        private set

    fun start(context: Context): Boolean {
        if (running) return true
        return try {
            val ss = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
            server = ss
            running = true
            acceptThread = Thread({
                while (running) {
                    try {
                        val sock = ss.accept()
                        Thread { handle(sock) }.apply { isDaemon = true }.start()
                    } catch (_: Throwable) {
                        if (!running) break
                    }
                }
            }, "debug-server").apply { isDaemon = true; start() }
            true
        } catch (_: Throwable) {
            running = false
            false
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Throwable) {}
        server = null
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 0
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val writer = PrintWriter(OutputStreamWriter(sock.getOutputStream()), true)
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line == "PING" -> writer.println(json { it.put("ok", true).put("pong", System.currentTimeMillis()) })
                    line == "HELP" -> writer.println(json {
                        it.put("ok", true)
                        it.put("commands", JSONArray(listOf("PING", "INFO", "CMD <base64>", "HELP")))
                    })
                    line == "INFO" -> {
                        val rows = Shell.collectInfo(sockAppContext ?: return, RootState.Granted)
                        val arr = JSONArray()
                        rows.forEach { r ->
                            arr.put(JSONObject().put("label", r.label).put("value", r.value))
                        }
                        writer.println(json { it.put("ok", true).put("info", arr) })
                    }
                    line.startsWith("CMD ") -> {
                        val raw = line.removePrefix("CMD ").trim()
                        val cmd = try {
                            String(Base64.getDecoder().decode(raw))
                        } catch (_: Throwable) {
                            ""
                        }
                        if (cmd.isBlank()) {
                            writer.println(json { it.put("type", "error").put("message", "empty command") })
                            continue
                        }
                        val danger = Shell.findDangerous(cmd)
                        if (danger != null) {
                            writer.println(json { it.put("type", "blocked").put("command", danger) })
                            continue
                        }
                        val start = System.currentTimeMillis()
                        Shell.runRootCommand(
                            cmd,
                            onOut = { l -> synchronized(writer) { writer.println(json { it.put("type", "stdout").put("line", l) }) } },
                            onErr = { l -> synchronized(writer) { writer.println(json { it.put("type", "stderr").put("line", l) }) } }
                        ).let { res ->
                            writer.println(json {
                                it.put("type", "exit").put("code", res.exitCode)
                                    .put("ms", System.currentTimeMillis() - start)
                            })
                        }
                    }
                    else -> writer.println(json { it.put("ok", false).put("error", "unknown command, send HELP") })
                }
            }
        } catch (_: Throwable) {
        } finally {
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    /** INFO 需要 Context；由 Application/Activity 注入。 */
    @Volatile
    var sockAppContext: Context? = null

    private inline fun json(block: (JSONObject) -> JSONObject): String =
        try { block(JSONObject()).toString() } catch (t: Throwable) { "{\"ok\":false,\"error\":\"json\"}" }
}
