# Agent IME（agent-ime）APK 功能与使用说明

本文档描述当前仓库内 **Agent 代聊输入法** Android APK 的能力、使用方式与注意事项。与总方案的关系见 [个人微信代聊技术方案.md](./个人微信代聊技术方案.md)。

## 定位

- **PoC 级最小自定义输入法**：不依赖微信无障碍节点树，通过系统输入法通道向**当前获得焦点的文本框**写入文字。
- **典型用途**：在个人微信聊天页，由 Auto.js 负责打开会话、点击输入框与发送；本 APK 负责在「已切换为本输入法且输入框已聚焦」时，把 Agent 返回的 `reply_text` 注入输入框。

## 功能概览

| 能力 | 说明 |
|------|------|
| 系统输入法 | 注册为可选输入法，界面为极简提示条（非完整 QWERTY 键盘）。 |
| 文本注入 | 通过 `InputConnection.commitText()` 将字符串提交到当前编辑框。 |
| 广播触发注入 | 其他应用或 `adb` 发送指定广播，携带要注入的文本；接收端调用上述注入逻辑。 |
| **设备端 OCR** | 使用 **Google ML Kit 中文文本识别** 对聊天截图做本地识别，**无需上传云端**；由广播传入图片路径或 URI，识别结果再以广播返回（便于 Auto.js 衔接后端 Agent）。 |

## 应用信息

- **包名**：`com.agentime.ime`
- **应用名**：Agent IME（系统设置里输入法名称：**Agent 代聊输入法**）
- **Debug APK 路径**（构建成功后）：`agent-ime/app/build/outputs/apk/debug/app-debug.apk`

## 广播协议（注入文本）

用于从 Auto.js、本机脚本或 `adb` 触发注入，需与下列约定一致。

| 项目 | 值 |
|------|-----|
| Action | `com.agentime.ime.action.INJECT_TEXT` |
| 组件（显式广播，推荐） | `com.agentime.ime/.InjectTextReceiver` |
| Extra（二选一，优先前者） | `text`（String）或 `reply_text`（String） |

说明：若同时提供 `text` 与 `reply_text`，实现上**优先使用 `text`**。

## 广播协议（聊天截图 OCR）

用于将 **Auto.js 保存的截图**（或相册 URI）交给本应用做本地识别；识别为**异步**，完成后发出结果广播。

### 触发识别

| 项目 | 值 |
|------|-----|
| Action | `com.agentime.ime.action.OCR_IMAGE` |
| 组件（显式广播，推荐） | `com.agentime.ime/.OcrRequestReceiver` |
| Extra（二选一，**优先 `image_uri`**） | `image_uri`（String）：`content://` / `file://` 等；或 `image_path`（String）：可读绝对路径 |
| Extra（可选） | `request_id`（String）：任意标记，结果广播中原样带回，便于并发 |

### 识别结果

| 项目 | 值 |
|------|-----|
| Action | `com.agentime.ime.action.OCR_RESULT` |
| Extra | `success`（boolean）、成功时 `ocr_text`（String）、失败时 `error`（String）、可选 `request_id` |

说明：

- **截图仍由 Auto.js（或系统）生成并保存**；本 APK 不负责截屏。若你希望「截图也在本应用内完成」，需另加 MediaProjection/无障碍等能力，当前未实现。
- 首次使用 ML Kit 可能在联网条件下**下载模型**（已声明 `INTERNET` 权限）。
- Android 13+ 读取公共目录图片需 **运行时授权**（照片与视频）；若 Auto.js 将图写入本应用可访问路径或 `content://` 并带授权，请按实际权限模型处理。

### adb 联调示例（图片需已在手机内）

```bash
# 将电脑上的图片推到手机
adb push ./chat.png /sdcard/Download/agent_chat.png

adb shell am broadcast -n com.agentime.ime/.OcrRequestReceiver \
  -a com.agentime.ime.action.OCR_IMAGE \
  --es image_path "/sdcard/Download/agent_chat.png"
```

结果广播可在 Auto.js 中监听 `com.agentime.ime.action.OCR_RESULT`；仅用 adb 时可在 logcat 中过滤 `OcrRequestReceiver` 或自行加日志。

电脑侧一键推送图片并触发 OCR（需 `adb` 可用）：

```bash
cd agent-ime
./scripts/adb-ocr.sh /path/to/chat.png
```

## 使用前提（必读）

注入是否生效同时依赖以下条件：

1. 已在 **系统设置 → 语言与输入法** 中启用本输入法。
2. 在目标应用（如微信）输入界面中，**已切换到「Agent 代聊输入法」**。
3. 聊天输入框处于 **聚焦** 状态（光标在输入框内）。
4. `AgentImeService` 已处于可用状态（输入法进程存活）；若从未切换过本输入法，可先切换一次再试。

若 `InputConnection` 不可用（例如未聚焦、未选本输入法），注入会失败且当前实现**无弹窗提示**，表现为输入框无变化。

## 安装

```bash
adb install -r agent-ime/app/build/outputs/apk/debug/app-debug.apk
```

路径请按本机仓库位置调整。

## 编译（无 Android Studio 亦可）

1. 安装 **JDK 17+** 与 **Android SDK**，并在 `agent-ime/local.properties` 中配置 `sdk.dir`（可参考 `local.properties.example`）。
2. 在 `agent-ime` 目录执行：

```bash
./gradlew :app:assembleDebug
```

更简说明见仓库内 `agent-ime/BUILD.txt`。

## 联调：adb 注入示例

在 **已安装 APK、微信聊天页已选本输入法且输入框已聚焦** 的前提下，于电脑执行：

```bash
cd agent-ime
./scripts/adb-inject.sh "要注入的文字"
```

等价命令示例：

```bash
adb shell am broadcast -n com.agentime.ime/.InjectTextReceiver \
  -a com.agentime.ime.action.INJECT_TEXT \
  --es text "要注入的文字"
```

使用 `reply_text` 字段时：

```bash
adb shell am broadcast -n com.agentime.ime/.InjectTextReceiver \
  -a com.agentime.ime.action.INJECT_TEXT \
  --es reply_text "后端返回的回复"
```

## Auto.js 侧（思路）

在脚本已获得 `reply_text` 后，向系统发送与上节一致的 **显式广播**（指向包名 `com.agentime.ime` 与 `InjectTextReceiver`），Extras 使用 `text` 或 `reply_text` 即可。具体 API 以 Auto.js 版本文档为准（如 `context.sendBroadcast` / 封装方法）。

## 安全说明（PoC）

- `InjectTextReceiver` 在 Manifest 中为 **`android:exported="true"`**，任意应用均可向该 Action 发广播，存在被滥用的风险。
- 若产品化，应改为：**自定义签名级权限**、或 **不导出** 且仅同进程/同签调用，并结合本地 HTTP/WebSocket 等受控通道（见总方案第 2 阶段）。

## 微信里看不到「发送」或底部栏异常

常见原因与处理：

1. **输入框里没有文字**  
   多数版本下，微信在**空输入**时只突出语音/加号等，**「发送」往往在输入了内容之后**才出现或变为可点。请先手动打一个字，或用本文档的广播/`adb-inject.sh` **注入一段文字**，再看输入栏右侧是否出现发送。

2. **当前是语音输入态**  
   若底部是「按住说话」而不是键盘，需先点输入框旁的 **键盘图标**，切回文字输入，再观察发送区域。

3. **极简键盘高度过小 / 全屏 IME 导致宿主布局错乱**  
   本输入法已做兼容：**关闭全屏模式**、为键盘区域设置 **约 200dp 最小高度**，并校正 `onComputeInsets`，便于微信正确排版底部输入栏。若你仍使用旧版 APK，请 **重新编译安装** 当前工程生成的 `app-debug.apk`。

若注入成功后仍无发送，可再试：**收起键盘再聚焦输入框**、或重启微信后再切换为本输入法。

## 已知限制

- 未实现完整键盘输入；仅适合「外部注入 + 其它工具负责发送」的流程。
- 未内置与后端的 HTTP/WebSocket；需由 Auto.js 或本机服务拿到 `reply_text` 再发广播。
- OCR 为**通用文本识别**，聊天界面 UI/气泡/叠字可能影响准确率；复杂场景仍需后端或规则后处理。
- 注入失败时当前无统一 UI 反馈，联调时建议配合 `adb logcat` 自行加日志或断点。

## 版本与构建参数摘要

- `compileSdk` / `targetSdk`：34  
- `minSdk`：24  
- 应用版本：`agent-ime/app/build.gradle.kts` 中 `versionCode` / `versionName`（集成 OCR 后已递增至 1.1 / 2）  
- 构建工具链：Gradle + Android Gradle Plugin（见 `agent-ime` 内 `build.gradle.kts`）  
- OCR 依赖：`com.google.mlkit:text-recognition-chinese`
