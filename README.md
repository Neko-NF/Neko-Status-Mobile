# Neko Status Mobile

Neko Status 的 Android 原生客户端，使用 Kotlin、Jetpack Compose 和单一前台服务上报设备存在状态。

[下载最新版本](https://github.com/Neko-NF/Neko-Status-Mobile/releases/latest) ·
[更新清单规范](api/update-manifest.schema.json) · [安全策略](SECURITY.md) · [MIT 许可证](LICENSE)

## 更新

应用默认从公开仓库 `Neko-NF/Neko-Status-Mobile` 检查 GitHub Releases。自动检查默认开启，
自动下载默认关闭；下载完成后会校验 APK 的 SHA-256、包名、版本号和签名证书，安装仍由
Android 系统要求用户确认。

更新中心也可切换到公开的个人 `owner/repository`。个人仓库必须使用兼容的 `update.json`，
且 APK 必须与当前安装版本同包名、同签名；应用不保存 GitHub Token，也不支持私有仓库。

## 本地构建

需要 JDK 17、Android SDK 36，并在不提交的 `local.properties` 中配置 SDK 路径。执行：

```powershell
./gradlew.bat ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug
```

Debug APK 使用 `com.nekonf.nekostatus.debug` 包名。Release 只接受 CI Secret 提供的
`NEKO_KEYSTORE_PATH`、`NEKO_KEYSTORE_PASSWORD`、`NEKO_KEY_ALIAS` 和 `NEKO_KEY_PASSWORD`。

正式 APK 不提交到 Git 历史，由 GitHub Release 同时提供 APK、`.sha256` 和 `update.json`。

## 安全与协议

- 生产服务固定为 `https://nekostatus.koirin.com`；明文局域网地址只在 Debug 可用。
- JWT、设备密钥和小组件令牌由 Tink/Android Keystore 保护，不能写入日志、截图或 Git。
- 上报只包含安装标识、应用、屏幕、电量和可选媒体信息，不上传截图。
- 详细设计见 `docs/architecture.md`、`docs/protocol.md`、`docs/security.md` 和 `docs/release.md`。
