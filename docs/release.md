# 发布与更新

## Release 内容

每个公开 Release 必须同时包含：

- GitHub 自动生成的源码归档；
- `neko-status-<version>-universal.apk`；
- 同名 `.sha256` 文件；
- 符合 [`api/update-manifest.schema.json`](../api/update-manifest.schema.json) 的 `update.json`。

客户端只从同一个 GitHub Release 精确选择 `apkAsset`，不接受清单指定任意下载 URL。
APK 上限为 250 MiB，且必须通过 SHA-256、包名、真实 `versionCode`/`versionName`、当前安装
签名和清单证书指纹校验。下载完成广播只调度唯一 WorkManager 任务，实际哈希和 APK 解析
不占用广播执行窗口；进程重启后会恢复未完成下载或重建验证任务。

## CI 发布顺序

1. 创建并推送与 `versionName` 一致的 `v<versionName>` tag。
2. CI 执行格式、静态分析、Lint、单测和 Debug 构建门禁。
3. 从 GitHub Secrets 恢复临时 Release Keystore，构建并用 `apksigner` 验证 APK。
4. 从 Gradle `output-metadata.json` 提取版本，并使用 `aapt2` 与 APK 的真实包名和版本交叉校验，
   不在工作流中硬编码 `versionCode`。
5. 生成 SHA-256、证书摘要和 `update.json`，并与公开官方证书指纹比对。
6. 先创建含全部附件的 Draft Release；附件成功后才发布并设为 latest。

第三方 Actions 固定到完整 commit SHA，Dependabot 通过 PR 提议升级；签名工作流不得引用
可移动的 major tag。

客户端使用 GitHub 的 `/repos/{owner}/{repository}/releases/latest` 接口，而该接口不会返回
标记为 prerelease 的版本。因此即使版本名包含 `alpha` 或 `beta`，需要被客户端发现的版本也
必须作为普通 Release 发布并设为 latest；版本阶段由 `versionName` 表达。

需要的 GitHub Actions Secrets：`NEKO_KEYSTORE_BASE64`、`NEKO_KEYSTORE_PASSWORD`、
`NEKO_KEY_ALIAS`、`NEKO_KEY_PASSWORD`。工作流使用 `release` Environment；长期私钥应离线
备份，绝不能写入仓库、Actions 日志或构建附件。

首次创建仓库时，应确认仓库可见性为 Public、默认分支为 `main`、Actions 对工作流具有
`contents: write` 权限，并创建名为 `release` 的 Environment。四项签名 Secret 应配置在该
Environment 中，同时启用 Private vulnerability reporting；推送首个版本 tag 前先确认默认
分支的 Verify Android 工作流通过。

## 真机发布门禁

发布前必须使用同一签名证书完成上一版本到候选版本的覆盖升级，确认应用数据保留，并验证
GitHub API、`update.json`、APK 下载、SHA-256、证书摘要和 APK 元数据一致。普通 Android
应用不能静默安装；自动化范围止于检查、下载和验证，最终安装必须由系统安装器确认。

个人仓库使用相同清单契约。APK 的当前签名集合必须与已安装版本完全一致；首版不支持证书
轮换。使用自有签名的 fork 必须先作为自己的基线安装，不能覆盖官方安装版。
