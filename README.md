# 怪团建（Teambox）

怪团建是一个面向聚会、观影和团建场景的开源 Android 工具箱。项目会逐步加入彼此独立的小工具；当前首个可用功能是腾讯视频“举手 +1”自动识别与点击。

> 项目仍在快速迭代。腾讯视频界面变化可能影响识别效果，请优先使用最新版，并只在你有权操作的账号和场景中使用。

## 当前功能

### 腾讯视频举手 +1

- 只在腾讯视频位于前台时扫描，不在其他应用中执行点击。
- 在本机识别白色“举手小人 + 固定 `+1`”标志，并排除已知的橙色皇冠样式。
- 目标连续出现并确认向左移动后才点击；同一目标最多点击一次。
- 提供明确的开始、停止和状态提示，关闭功能或无障碍服务后立即停止。
- 屏幕帧默认只在内存中处理，不上传完整画面。

本项目与腾讯、腾讯视频无隶属或合作关系。“腾讯视频”是相关权利人的商标。自动操作可能受到目标平台规则限制，使用者应自行确认并遵守服务条款。第三方名称、标识及兼容性素材的权利边界见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 系统要求与权限

- Android 8.0（API 26）或更高版本。
- 无障碍服务：确认前台应用并执行用户开启的点击动作。服务需要由用户在系统设置中明确启用。
- 屏幕捕获：读取用于本地识别的画面；系统要求时会显示 MediaProjection 授权窗口。
- 前台服务：在识别运行期间维持屏幕捕获，并显示系统通知。
- 网络：仅用于检查新版本，不上传屏幕内容或团建数据。应用不申请网络状态权限。

部分国产系统会限制后台服务。识别容易中断时，可按系统提示允许自启动，并将电池策略设为“不限制”；这些设置不是安装应用的前置条件。

更完整的数据处理说明见 [PRIVACY.md](PRIVACY.md)，漏洞报告方式见 [SECURITY.md](SECURITY.md)。

## 安装与更新

正式安装包发布在本仓库的 GitHub Releases。请从 Release 说明确认版本号、文件名和 SHA-256，不要安装来源不明的二次打包版本。

应用内更新检测先读取 `api.github.com` 的公开 Releases 元数据；访问失败时，再通过固定 HTTPS 加速端点 `gh-proxy.com` 重试。它只接受本仓库的官方 Release 页面和 APK 地址，不跟随 HTTP 重定向，也不采用响应中提供的代理地址。

检测到新版本后，由用户决定是否打开官方页面或固定加速下载地址。应用不会自动下载、静默安装或主动打开链接。

## 从源码构建

需要 JDK 17、Android SDK Platform 35，以及可访问 Google Maven、Maven Central、Gradle 和 JetBrains Compose 仓库的网络环境。

Linux / macOS：

```bash
./gradlew \
  :shared:desktopTest \
  :composeApp:desktopTest \
  :composeApp:testDebugUnitTest \
  :androidRuntime:testDebugUnitTest \
  :composeApp:assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat `
  :shared:desktopTest `
  :composeApp:desktopTest `
  :composeApp:testDebugUnitTest `
  :androidRuntime:testDebugUnitTest `
  :composeApp:assembleDebug
```

Debug APK 输出到 `composeApp/build/outputs/apk/debug/composeApp-debug.apk`。Release 签名信息只能通过本机 Gradle 属性或环境变量提供，不要提交密钥、密码或签名配置。

## 项目结构

- `composeApp`：Android 主应用和 Compose Multiplatform 界面。
- `androidRuntime`：无障碍服务、屏幕捕获、视觉识别与点击运行时。
- `shared`：跨平台模型、数据处理、识别基础逻辑和测试。
- `testHost`：用于自动操作闭环回归的自建 Android 测试宿主。

## 路线图

- 增加更多可独立启停的团建小工具。
- 改善不同设备、分辨率和腾讯视频版本下的识别兼容性。
- 将工具入口、权限和数据进一步模块化，避免新功能互相影响。
- 完善发布校验、更新完整性验证和国内下载可用性。
- 补充贡献指南、问题模板和更多自动化测试。

欢迎提交 Issue 或 Pull Request。请勿上传包含账号、头像、聊天内容、设备标识的真实截图或数据库；涉及识别回归时，优先提供脱敏后的最小样本。

## 许可证

[MIT License](LICENSE)
