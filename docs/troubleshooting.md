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

## Linux Release 元数据脚本误判有效 APK

- 日期：2026-07-27
- 环境：GitHub-hosted Ubuntu Runner、PowerShell 7、Android Build Tools
- 现象：正式 APK 构建和签名成功，但元数据门禁先后报告 `aapt2 could not inspect the APK` 和官方证书指纹不匹配；两个任务都在创建 Draft Release 前停止。
- 根因：把原生命令直接管道到 `Select-Object -First 1` 会提前关闭下游管道，使 Linux `aapt2` 以非零状态退出；同时，`apksigner` 新版输出以 `V3.0 Signer:` 开头，按第一个冒号分割会截取到错误字段。
- 解决：先完整捕获原生命令输出和退出码，再在内存中选取需要的行；证书使用严格正则捕获 64 位 SHA-256，并要求唯一证书集合恰好为一个。
- 预防：发布工作流必须先生成 Draft、精确核对三个资产后再公开；本地用真实 APK 验证 `aapt2` 完整输出和 `apksigner` 多行格式，不能只对伪造单行做解析测试。
- 当前验证：两次失败运行均在创建 Draft Release 前停止，没有留下公开的残缺版本。修复后的
  [`v2.0.0-alpha.3`](https://github.com/Neko-NF/Neko-Status-Mobile/releases/tag/v2.0.0-alpha.3)
  已作为普通 Release 公开并设为 latest；
  [发布运行 30250919010](https://github.com/Neko-NF/Neko-Status-Mobile/actions/runs/30250919010)
  的 preflight、无密钥 verify 和签名 release 均成功。远程下载后再次确认 APK、`.sha256`、
  `update.json`、GitHub digest、APK 元数据和证书一致。

## 未知来源授权返回后没有继续打开系统安装器

- 日期：2026-07-27
- 环境：`2.0.0-alpha.3`、Android API 36 模拟器
- 现象：已验证更新缺少“安装未知应用”权限时，点击安装只打开系统设置；用户授权并返回后，
  应用不会自动继续打开系统安装器。
- 根因：安装入口使用一次性调用；旧桥接 Activity 配置了 `noHistory` 和 `Theme.NoDisplay`，
  打开权限设置后立即结束，没有保存等待状态或处理返回结果。安装 Intent 也没有兼容回退和
  可区分的失败结果。
- 解决：`2.0.0-alpha.4` 候选版本使用有状态的桥接 Activity 等待权限页结果，返回后重新检查
  权限并续接安装；通过 FileProvider 只读 `content://` URI、读权限 flag 和 `ClipData` 交给
  系统安装器，并在 `ACTION_VIEW` 不可用时尝试 `ACTION_INSTALL_PACKAGE`。所有启动方式失败时
  显示明确提示并保留已验证 APK。
- 预防：单元测试覆盖权限往返续接、只读 URI 授权、安装 Intent 回退、无 READY 包和下载完成
  Receiver 的平台权限；公开发布验收必须从上一公开版本走完整应用内更新链路，不能用
  `adb install -r` 代替。
- 当前验证：修复和自动化覆盖已进入 `2.0.0-alpha.4` 候选代码；该版本尚未在本文中记为公开，
  从 `2.0.0-alpha.3` 到该版本的公开升级和实体机验收仍待实际 Release 后记录。API 36 模拟器
  结果不能表述为 ColorOS 实体机通过。
