# RootExec GUI

一个面向 Android 的 root 命令 GUI 工具（Jetpack Compose / Material 3），用于在手机上执行 root 命令、
查看设备状态，并支持从电脑远程调试。

## 功能

- **信息面板**：设备型号、Android 版本、内核版本、架构、SELinux 状态、KernelSU 是否加载、
  运行时长、内存、负载、电量、Root 授权状态与 root 通道。
- **控制台**：root 命令执行（`su -c` 优先，自动回退到 43499 临时 root helper）、
  stdout/stderr 分色显示、退出码与耗时、命令历史、快捷命令、复制输出、自动滚动。
- **危险命令拦截**：`rm` `rmdir` `dd` `mkfs` `mke2fs` `mkswap` `wipe` `shred` `fdisk`
  `parted` `format` `tune2fs` `flash_erase` `blkdiscard`；并递归检查
  `sh -c "..."`、`su -c "..."`、`toybox rm` 等包装内的同类命令。
- **电脑远程调试**：App 进程内监听 `127.0.0.1:27182`（仅回环），电脑上
  `adb forward tcp:27182 tcp:27182` 后即可用 `nc`/`curl`/脚本控制。

## 远程调试协议

每行一条命令，回复为 JSON 行：

```
PING                -> {"ok":true,"pong":<ms>}
HELP                -> {"ok":true,"commands":[...]}
INFO                -> {"ok":true,"info":[{"label":"...","value":"..."},...]}
CMD <base64(cmd)>   -> {"type":"stdout"|"stderr","line":"..."} ...
                       {"type":"exit","code":n,"ms":n}
                       （危险命令 -> {"type":"blocked","command":"rm"}）
```

示例：

```bash
adb forward tcp:27182 tcp:27182
printf 'PING\n' | nc 127.0.0.1 27182
CMD=$(printf 'id; getenforce' | base64 -w0)
printf 'CMD %s\n' "$CMD" | nc 127.0.0.1 27182
```

## 远程危险命令授权

远程调试通道**默认拒绝**危险命令（`rm`/`rmdir`/`dd` 等）。若确实需要远程执行，必须在手机 App 里授权：

1. 打开 RootExec → 「信息」页 → 「远程授权」卡片；
2. 打开总开关「允许远程危险命令」；
3. 在「点击添加授权」里逐个添加要放行的命令（如 `rm`），点已授权 chip 可移除；
4. 授权后，远程执行危险命令会先返回
   `{"type":"authorized","command":"rm"}`，然后再返回 stdout/stderr 与 exit；
5. 未授权时返回
   `{"type":"blocked","command":"rm","reason":"需要 App 授权：请在 RootExec 的「远程授权」列表中允许 rm"}`；
6. 「远程执行记录」显示最近 30 条远程执行/拒绝记录（含时间）。

授权状态保存在 `SharedPreferences("remote_auth")`，重启 App 后仍有效；总开关关闭时，即使列表里有命令也会拒绝。

## 构建

- 环境：JDK 21、Android SDK 37、Gradle 9.7.1、AGP 9.3.2、Kotlin 2.4.10
- 命令：

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
gradle assembleDebug        # 或使用仓库内 Gradle 发行版
```

AGP 9 起内置 Kotlin 支持，因此项目不再显式应用 `org.jetbrains.kotlin.android` 插件
（仅保留 `org.jetbrains.kotlin.plugin.compose`）。

## 权限说明

- 需要 root（KernelSU / Magisk 的 `su`，或本机 43499 临时 root helper）；
- 首次运行需在 KernelSU 管理器中授权；
- 远程调试接口只绑定 `127.0.0.1`，必须先 `adb forward` 才能访问，命令同样经过危险命令过滤。

## 免责声明

仅供设备调试与研究使用。
