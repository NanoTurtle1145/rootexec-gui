package com.nanoturtle.rootexec

import android.content.Context
import android.content.SharedPreferences
import java.util.Collections

/**
 * 远程调试的「危险命令」授权管理。
 *
 * 默认全部拒绝；只有在 App 的「远程授权」里打开总开关、并把具体命令加入
 * 授权列表后，电脑经 127.0.0.1:27182 发来的该类命令才会被执行。
 */
object RemoteAuth {
    private const val PREFS = "remote_auth"
    private const val KEY_ALLOW = "allow_dangerous"
    private const val KEY_LIST = "allowed_commands"

    private var prefs: SharedPreferences? = null

    @Volatile
    var allowDangerous: Boolean = false
        private set

    val allowed: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        allowDangerous = p.getBoolean(KEY_ALLOW, false)
        allowed.clear()
        allowed.addAll(p.getStringSet(KEY_LIST, emptySet()) ?: emptySet())
    }

    fun setAllowDangerous(value: Boolean) {
        allowDangerous = value
        prefs?.edit()?.putBoolean(KEY_ALLOW, value)?.apply()
    }

    fun add(command: String) {
        val c = command.trim().lowercase()
        if (c.isEmpty()) return
        allowed.add(c)
        prefs?.edit()?.putStringSet(KEY_LIST, HashSet(allowed))?.apply()
    }

    fun remove(command: String) {
        allowed.remove(command.trim().lowercase())
        prefs?.edit()?.putStringSet(KEY_LIST, HashSet(allowed))?.apply()
    }

    fun isAuthorized(dangerCommand: String): Boolean =
        allowDangerous && allowed.contains(dangerCommand.trim().lowercase())

    fun snapshot(): Set<String> = HashSet(allowed)
}
