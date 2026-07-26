# 安全

禁止提交 Token、JWT、设备密钥、密码、签名私钥、Keystore 或个人 SDK 路径。所有凭据使用
Tink AEAD，主密钥保留在 Android Keystore；清除认证时同时删除 JWT、设备密钥和小组件令牌。

Release 只使用 HTTPS，且缺少签名环境变量时构建必须失败。更新清单中的证书摘要为必填；
客户端只接受所选 GitHub Release 内的 APK 资产，并在安装前验证 SHA-256、应用包名、真实
版本、实际文件大小、已安装签名集合和官方证书指纹。个人仓库不保存 Token，只支持公开
仓库及同签名 APK；当前版本不接受签名轮换。Release notes 不进入通知 PendingIntent。
日志会清除 Bearer、token、apiKey 和 deviceKey 值。

安全问题请勿提交公开 Issue；使用仓库 Security Advisory 私密报告并提供可复现步骤和影响范围。

## 远程截图

Android 客户端不会采集或上传本机屏幕。只有用户在小组件设置中主动启用截图并选择远程
设备后，Worker 才下载该设备已上报的截图缩略图。请求通过独立小组件 Bearer 头鉴权，禁止
把 JWT、设备密钥或小组件令牌拼入图片 URL。

图片只保存在应用私有缓存目录，缓存键包含设备 ID、截图更新时间和资源 URL；解码后的
截图限制在 512×300 像素以内，缓存文件数量受限。退出登录或清除认证时应同时清除小组件
令牌、状态缓存与图片缓存。
