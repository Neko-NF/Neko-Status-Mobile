# 工程踩坑与解决方法

本文件记录开发中已经发生且可能再次出现的问题。新增记录时必须包含：日期、环境、现象、根因、解决方法、预防措施和相关验证命令。不要只记录临时绕过方式。

## Windows PowerShell 内层命令被提前展开

- 日期：2026-07-26
- 环境：Windows，PowerShell 7
- 现象：调用 `pwsh -Command` 时，`$OutputEncoding` 或 `$()` 在外层 Shell 被提前解释，出现 `ParserError` 或执行了意外表达式。
- 根因：外层和内层命令都使用双引号，变量插值边界不明确。
- 解决：固定使用 `D:\Apps\PowerShell\7\7\pwsh.exe -NoProfile`，并用单引号包住传给内层的完整命令；中文读取显式使用 `-Encoding UTF8`。
- 预防：避免在双层 PowerShell 中拼接含 `$` 的命令；复杂脚本优先放入仓库脚本文件并测试。

## Android SDK location not found

- 日期：2026-07-26
- 环境：本机 Android SDK 位于 `C:\Users\qwe\AppData\Local\Android\Sdk`
- 现象：Gradle 在配置 Android 模块时提示 `SDK location not found`。
- 根因：新仓库没有本机专用的 `local.properties`，且当前进程未提供 `ANDROID_HOME`。
- 解决：在被 Git 忽略的 `local.properties` 写入 `sdk.dir=C\:\\Users\\qwe\\AppData\\Local\\Android\\Sdk`。
- 预防：README 明确首次构建步骤；不要提交含个人路径的 `local.properties`。
- 验证：`./gradlew.bat projects --no-daemon --console=plain`

## Gradle 配置缓存锁被残留进程占用

- 日期：2026-07-26
- 环境：Gradle 8.11.1，首次依赖解析
- 现象：后续构建提示 `Timeout waiting to lock Configuration Cache`，并显示持锁 PID。
- 根因：终端工具超时结束了父进程，但 Gradle 单次 Daemon 仍在后台收尾。
- 解决：先用 `./gradlew.bat --status` 确认进程，再执行 `./gradlew.bat --stop`；诊断阶段可临时加 `--no-configuration-cache`。
- 预防：首次构建预留足够超时时间，不要并发启动修改同一仓库配置缓存的 Gradle 任务。

## 模块间实现依赖没有暴露 Retrofit 类型

- 日期：2026-07-26
- 环境：`:core:data` 依赖 `:core:network`
- 现象：数据模块无法解析 `retrofit2.Response`，并连带产生大量类型推断错误。
- 根因：网络模块把 Retrofit 声明为 `implementation`；数据模块又直接使用了 Retrofit 类型，不能依赖传递实现细节。
- 解决：在直接使用该类型的 `:core:data` 显式添加 `implementation(libs.retrofit.core)`。
- 预防：公开 API 中出现的第三方类型必须由使用方显式依赖，或在所有权边界内封装为项目自己的结果类型。
- 验证：`./gradlew.bat :core:data:compileDebugKotlin --no-configuration-cache`

## Retrofit 契约测试缺少协程测试库

- 日期：2026-07-26
- 现象：`runTest` 无法解析，随后 suspend API 报告只能从协程调用。
- 根因：版本目录已有 `kotlinx-coroutines-test`，但 `:core:network` 未加入测试依赖。
- 解决：增加 `testImplementation(libs.kotlinx.coroutines.test)`。
- 预防：新增 suspend 契约测试时同时检查测试 source set 的协程依赖。
