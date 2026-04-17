# Agent IME 截图 Provider 升级方案

## 当前结论

现阶段“个人微信代聊”主链路已经验证通过：

1. 拉起微信
2. 打开聊天页
3. 截图
4. OCR / Agent
5. 聚焦输入框
6. 注入文本
7. 自动发送

当前唯一没有彻底解决的问题，是 **截图质量**。

在当前设备/系统/微信组合下，`MediaProjection` 虽然能稳定截图，但导出的聊天截图长期表现为：

- 图像整体发糊
- 清晰度评分长期低位（`sharpness ≈ 18~20`）
- 本地 OCR 对原图、裁剪图、增强图均识别失败

这说明当前问题已经从“流程能不能跑”收敛成“截图后端是否足够清晰”。


## 已实现的截图架构

当前项目内已经完成截图能力抽象，支持按 provider 切换。

相关文件：

- [CaptureController.kt](/Users/wuwoo/Desktop/work/_旅游定制 Agent/agent-ime/app/src/main/kotlin/com/agentime/ime/host/capture/CaptureController.kt)
- [CaptureProviderFactory.kt](/Users/wuwoo/Desktop/work/_旅游定制 Agent/agent-ime/app/src/main/kotlin/com/agentime/ime/host/capture/CaptureProviderFactory.kt)
- [MediaProjectionCaptureManager.kt](/Users/wuwoo/Desktop/work/_旅游定制 Agent/agent-ime/app/src/main/kotlin/com/agentime/ime/host/capture/MediaProjectionCaptureManager.kt)
- [ShellScreencapCaptureManager.kt](/Users/wuwoo/Desktop/work/_旅游定制 Agent/agent-ime/app/src/main/kotlin/com/agentime/ime/host/capture/ShellScreencapCaptureManager.kt)
- [ShizukuScreencapCaptureManager.kt](/Users/wuwoo/Desktop/work/_旅游定制 Agent/agent-ime/app/src/main/kotlin/com/agentime/ime/host/capture/ShizukuScreencapCaptureManager.kt)
- [CaptureImageProcessor.kt](/Users/wuwoo/Desktop/work/_旅游定制 Agent/agent-ime/app/src/main/kotlin/com/agentime/ime/host/capture/CaptureImageProcessor.kt)

支持的 provider 名称：

- `media_projection`
- `shell_screencap`
- `shizuku_screencap`

当前默认 provider：

- `media_projection`


## 当前 provider 状态

### 1. media_projection

状态：

- 已接通
- 可稳定参与现有主链路
- 可正常完成截图、裁剪、OCR、注入、发送的整体流程

问题：

- 当前设备上截图明显发糊
- 影响 OCR 识别成功率

适用：

- 当前阶段主链路默认 provider
- 业务流程验证继续依赖它


### 2. shell_screencap

状态：

- 已接入 provider 骨架
- 已验证在普通应用进程内直接执行 `screencap -p` 失败

典型报错：

- `Failed to take screenshot. Status: -1`
- `Capturing failed.`

结论：

- 普通 App 进程不能直接把它当成稳定主方案
- 不适合作为当前默认 provider


### 3. shizuku_screencap

状态：

- 已预留 provider 类
- 还未真正接入 Shizuku SDK / 授权 / 服务通信

结论：

- 这是下一阶段最值得投入的方案
- 目标是替代 `media_projection` 成为高质量截图主方案


## 为什么下一阶段优先做 Shizuku

当前已基本排除：

- OCR 模型本身
- 裁剪策略
- 截图时机细节
- 输入框聚焦时序
- 单纯图像增强

剩余最可疑的根因就是：

- `MediaProjection -> VirtualDisplay -> ImageReader` 这一链路在当前设备/ROM 上输出就已经发糊

而 `Shizuku + screencap -p` 有机会走到更接近系统原始截图的路径，理论上更可能拿到清晰图。


## 下一阶段目标

把截图后端升级为：

- 业务默认继续使用 `media_projection`
- 新增可真正工作的 `shizuku_screencap`
- 在设备授权完成后切换主截图方案到 `shizuku_screencap`


## 推荐实施顺序

### Phase 1：接入 Shizuku SDK

目标：

- 工程内可检测 Shizuku 是否安装
- 可检测授权状态
- 可申请授权

需要改动：

- `app/build.gradle.kts`
- 新增 Shizuku 依赖
- 新增权限检测/状态封装类


### Phase 2：完成 Shizuku provider 真正截图实现

目标：

- 在 `ShizukuScreencapCaptureManager` 内执行真正可用的截图命令
- 将生成的 PNG 文件交给 `CaptureImageProcessor`

输出：

- 原图
- 公共导出
- 聊天区裁剪图
- 增强裁剪图


### Phase 3：主界面增加 provider 切换能力

目标：

- 在 UI 中切换：
  - `media_projection`
  - `shizuku_screencap`

并写入 `host_config.capture_provider`

这样可以直接做 A/B 测试：

- 同一聊天页
- 同一时机
- 对比两种 provider 的清晰度和 OCR 长度


### Phase 4：按截图质量决定主方案

判定标准建议：

- 肉眼清晰度
- `sharpnessScore`
- `OCR 长度`
- 业务可用性

如果 `shizuku_screencap` 明显优于 `media_projection`，则：

- 把默认 provider 改成 `shizuku_screencap`
- `media_projection` 退为兜底


## 当前不建议继续投入的方向

以下方向当前收益已经很低，不应继续作为主攻方向：

- 继续频繁微调截图等待时长
- 继续打磨 `MediaProjection` 的清晰度阈值
- 继续增强本地锐化/阈值化参数
- 继续尝试普通 shell 直接 `screencap -p`

原因：

- 这些方向已经被多轮实验验证，改善非常有限
- 当前主要瓶颈是截图源质量，不是 OCR 算法边缘参数


## 当前建议

短期：

- `media_projection` 继续作为默认 provider，保证主流程可用

中期：

- 立即开始接 `ShizukuScreencapCaptureManager`

长期：

- 如果 Shizuku 清晰度显著更高，则切换为主截图后端

