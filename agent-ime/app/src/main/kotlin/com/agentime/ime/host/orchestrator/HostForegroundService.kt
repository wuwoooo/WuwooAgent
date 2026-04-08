package com.agentime.ime.host.orchestrator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import com.agentime.ime.IssueHintActivity
import com.agentime.ime.R
import com.agentime.ime.host.agent.ConversationTextExtractor
import com.agentime.ime.host.agent.HttpAgentClient
import com.agentime.ime.host.agent.SessionIdentity
import com.agentime.ime.host.automation.AccessibilityAutomationController
import com.agentime.ime.host.automation.ConversationListUnreadDetector
import com.agentime.ime.host.automation.NoopAutomationController
import com.agentime.ime.host.capture.CaptureProviderFactory
import com.agentime.ime.host.capture.ProjectionPermissionStore
import com.agentime.ime.host.ime.BroadcastImeController
import com.agentime.ime.host.ocr.FallbackOcrProvider
import com.agentime.ime.host.ocr.LocalOcrProvider
import com.agentime.ime.host.ocr.RemoteOcrProvider
import com.agentime.ime.host.storage.HostLogger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HostForegroundService : Service() {
    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var logger: HostLogger

    override fun onCreate() {
        super.onCreate()
        logger = HostLogger(this)
        createNotificationChannel()
        startForegroundCompat("Host 服务运行中", includeProjectionType = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_PREPARE_PROJECTION -> io.execute { prepareProjection() }
            ACTION_SCAN_CONVERSATION_LIST -> io.execute { scanConversationListAndRun() }
            ACTION_RUN_ONCE -> {
                val contactName = SessionIdentity.normalizeContactName(
                    intent.getStringExtra(EXTRA_CONTACT_NAME),
                )
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty().ifBlank {
                    SessionIdentity.buildSessionId(contactName)
                }
                io.execute { runOnce(sessionId, contactName) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun prepareProjection() {
        val capture = CaptureProviderFactory.create(this)
        runCatching {
            startForegroundCompat("正在初始化截图管线", includeProjectionType = true)
            logger.log(TAG, "开始初始化截图管线 provider=${CaptureProviderFactory.currentProvider(this)}")
            capture.prewarm()
            logger.log(TAG, "截图管线初始化完成")
        }.onFailure {
            logger.log(TAG, "截图管线初始化失败: ${it.message}")
        }
    }

    private fun scanConversationListAndRun() {
        if (running.get() || isBusyOrCoolingDown()) {
            logger.log(TAG, "会话列表截图分析跳过：当前正忙或冷却中")
            return
        }
        val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("runtime_enabled", false)) return
        if (prefs.getString("execution_mode", "auto").orEmpty() != "auto") return

        val capture = CaptureProviderFactory.create(this)
        val ocr = FallbackOcrProvider(LocalOcrProvider(this), RemoteOcrProvider(this))
        try {
            logger.log(TAG, "开始通过截图分析会话列表未读项")
            startForegroundCompat("正在识别微信会话列表", includeProjectionType = true)
            val cap = capture.captureScreen("wx_list_scan")
            val ocrText = recognizeWithFallbacks(ocr, cap)
            val pageAnalysis = ConversationListUnreadDetector.analyzeConversationListPage(cap.imagePath, ocrText)
            logger.log(TAG, "截图分析列表页判定: ${pageAnalysis.debugSummary}")
            if (!pageAnalysis.looksLikeListPage) {
                logger.log(TAG, "截图分析结果：当前不是微信会话列表页，跳过本轮；OCR=${ocrText.take(120)}")
                return
            }
            val hit = ConversationListUnreadDetector.findTopUnreadConversation(cap.imagePath)
            if (hit == null) {
                logger.log(TAG, "截图分析结果：已识别为会话列表页，但未识别到可点击的未读红点")
                return
            }
            logger.log(TAG, "截图分析识别到未读会话，准备点击 tap=(${hit.tapX.toInt()},${hit.tapY.toInt()}) score=${hit.redScore}")
            if (!com.agentime.ime.host.automation.WechatAccessibilityService.tapConversationAt(hit.tapX, hit.tapY)) {
                logger.log(TAG, "截图分析点击未读会话失败")
                return
            }
            Thread.sleep(1400)
            val contactName = SessionIdentity.normalizeContactName(
                com.agentime.ime.host.automation.WechatAccessibilityService.getCurrentChatContactName(),
            )
            if (contactName.isBlank() || contactName == "当前联系人") {
                logger.log(TAG, "点击未读会话后，仍无法识别聊天页联系人，取消本轮")
                return
            }
            logger.log(TAG, "截图分析进入会话成功，联系人=$contactName")
            runOnce(SessionIdentity.buildSessionId(contactName), contactName)
        } catch (e: Exception) {
            logger.log(TAG, "会话列表截图分析失败: ${e.message}")
        }
    }

    private fun runOnce(sessionId: String, contactName: String) {
        if (!running.compareAndSet(false, true)) {
            logger.log(TAG, "已有任务在执行中，忽略新的 runOnce: $sessionId/$contactName")
            return
        }
        val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
        val executionMode = prefs.getString("execution_mode", "auto").orEmpty()
        val automation = if (executionMode == "manual") NoopAutomationController() else AccessibilityAutomationController(this)
        val ime = BroadcastImeController(this)
        val captureProvider = CaptureProviderFactory.currentProvider(this)
        val capture = CaptureProviderFactory.create(this)
        val ocr = FallbackOcrProvider(LocalOcrProvider(this), RemoteOcrProvider(this))
        val agentClient = HttpAgentClient(this)

        try {
            moveState(HostState.IDLE, "开始任务: $sessionId/$contactName")
            logger.log(TAG, "当前截图 provider=$captureProvider")

            if (executionMode == "manual") {
                moveState(HostState.WECHAT_READY, "手动模式：请保持微信聊天页前台")
                logger.log(TAG, "手动模式：先截图识别，注入前请确保输入框可聚焦")
            } else {
                if (!com.agentime.ime.host.automation.WechatAccessibilityService.waitServiceConnected()) {
                    logger.log(TAG, "无障碍服务未连接，前台信息=${com.agentime.ime.host.automation.WechatAccessibilityService.getForegroundDebugInfo()}")
                    error("无障碍服务未连接，请在系统设置里将 Agent IME 无障碍开关关闭后重新打开")
                }
                if (automation.isWechatForeground()) {
                    logger.log(TAG, "检测到微信已在前台，复用当前聊天页，不重新拉起微信")
                } else {
                    if (!automation.launchWechat()) {
                        logger.log(TAG, "启动微信失败，前台信息=${com.agentime.ime.host.automation.WechatAccessibilityService.getForegroundDebugInfo()}")
                        error("无法启动微信，请检查微信是否已安装")
                    }
                    if (!com.agentime.ime.host.automation.WechatAccessibilityService.waitWechatForeground()) {
                        logger.log(TAG, "等待微信前台超时，继续尝试截图，前台信息=${com.agentime.ime.host.automation.WechatAccessibilityService.getForegroundDebugInfo()}")
                    } else {
                        logger.log(TAG, "微信前台判定成功")
                    }
                }
                moveState(HostState.WECHAT_READY, "微信前台就绪")
                logger.log(TAG, "保持聊天页静止，等待界面稳定后截图")
                Thread.sleep(1800)
            }
            if (!ime.isImeActive()) logger.log(TAG, "警告：当前默认输入法不是 Agent IME")
            val runtimeStartedAt = prefs.getLong("runtime_started_at", 0L)
            val sinceRuntimeStart = System.currentTimeMillis() - runtimeStartedAt
            if (runtimeStartedAt > 0L && sinceRuntimeStart in 0..2500L) {
                val waitMs = 2500L - sinceRuntimeStart
                logger.log(TAG, "运行刚启动，等待截图管线稳定 ${waitMs}ms")
                Thread.sleep(waitMs)
            }
            startForegroundCompat("正在分析微信聊天", includeProjectionType = true)
            logger.log(TAG, "开始截图，executionMode=$executionMode provider=$captureProvider")
            val cap = capture.captureScreen(sessionId)
            logger.log(
                TAG,
                "截图尝试#1 acceptable=${cap.acceptableForOcr} total=${"%.1f".format(cap.totalScore)} sharp=${"%.1f".format(cap.sharpnessScore)}",
            )
            moveState(HostState.SCREEN_CAPTURED, "截图完成: ${cap.imagePath}")
            cap.rawImagePath?.let {
                logger.log(TAG, "原始帧直存图: $it")
            }
            cap.rawExportedPath?.let {
                logger.log(TAG, "原始帧公共导出位置: $it")
            }
            cap.exportedPath?.let {
                logger.log(TAG, "公共截图导出位置: $it")
            }
            cap.debugSummary?.let {
                logger.log(TAG, "截图质量: $it")
            }
            cap.captureTrace?.takeIf { it.isNotBlank() }?.lines()?.forEach {
                logger.log(TAG, it)
            }
            cap.chatCropPath?.let {
                logger.log(TAG, "聊天区裁剪图: $it")
            }
            cap.enhancedChatCropPath?.let {
                logger.log(TAG, "增强裁剪图: $it")
            }

            val ocrText = recognizeWithFallbacks(ocr, cap)
            logger.log(TAG, "OCR 文本: ${ocrText.take(200)}")
            if (!cap.acceptableForOcr && ocrText.isBlank()) {
                logger.log(TAG, "截图质量不足且 OCR 为空，停止自动发送；当前投影管线可能已进入糊图状态")
                showIssueHint(
                    title = "截图内容疑似被隐私保护模糊化",
                    message =
                        "当前截图质量不足，已停止自动发送。\n\n" +
                            "可能原因：\n" +
                            "1. 手机正处于屏幕共享/投屏/录屏保护模式\n" +
                            "2. 系统为保护隐私，自动把微信聊天内容做了模糊处理\n\n" +
                            "建议操作：\n" +
                            "1. 关闭屏幕共享或隐私保护模式\n" +
                            "2. 回到微信聊天页并等待界面稳定\n" +
                            "3. 再执行一次 runOnce",
                )
                error("截图质量不足，已停止自动发送；请稍后重试，必要时手动重新授权截图")
            }
            if (!cap.acceptableForOcr) {
                logger.log(TAG, "截图质量未达 OCR 阈值，但已识别出文本，继续后续流程")
            }
            val latestInbound = ConversationTextExtractor.extractLatestInboundMessage(
                ocrText = ocrText,
                contactName = contactName,
                lastReplyText = prefs.getString("last_reply_text", "").orEmpty(),
            )
            logger.log(TAG, "提炼后的最新客户消息: ${latestInbound.take(200)}")
            if (latestInbound.isBlank()) error("未能从 OCR 中提取出最新客户消息")

            val inboundSignature = ConversationTextExtractor.signatureOf(latestInbound)
            val lastInboundSignature = prefs.getString("last_inbound_signature", "").orEmpty()
            if (inboundSignature.isNotBlank() && inboundSignature == lastInboundSignature) {
                logger.log(TAG, "检测到最近客户消息未变化，跳过本轮回复")
                moveState(HostState.SENT, "最近客户消息未变化，已跳过")
                return
            }

            val reply = agentClient.chat(cap.imagePath, latestInbound, sessionId, contactName)
            moveState(HostState.REPLY_READY, "reply_text 长度=${reply.replyText.length}")

            if (reply.replyText.isBlank()) error("reply_text 为空")
            if (executionMode == "manual") {
                moveState(HostState.INPUT_FOCUSED, "手动模式：请先聚焦输入框，再继续注入")
            } else {
                if (!automation.focusInputArea()) error("聚焦输入框失败")
                moveState(HostState.INPUT_FOCUSED, "输入框聚焦完成")
                logger.log(TAG, "输入框已聚焦，等待输入法稳定后注入")
                Thread.sleep(450)
            }

            if (!ime.injectText(reply.replyText)) error("注入文本失败")
            moveState(HostState.TEXT_INJECTED, "文本注入完成")

            if (executionMode == "manual") {
                moveState(HostState.SENT, "手动模式：已注入文本，请人工点击发送")
            } else {
                // 微信需时间刷新发送按钮位置；立即点容易点到旧区域
                Thread.sleep(600)
                val sendOk = automation.clickSend()
                logger.log(TAG, "clickSend 返回=$sendOk（若未发出，请在 host_config 调整 send_x/send_y）")
                if (!sendOk) error("点击发送失败（坐标可能不匹配当前机型，请调整 send_x/send_y）")
                prefs.edit()
                    .putString("last_inbound_signature", inboundSignature)
                    .putString("last_reply_text", reply.replyText)
                    .apply()
                moveState(HostState.SENT, "发送完成")
            }
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (
                msg.contains("MediaProjection", ignoreCase = true) ||
                    msg.contains("VirtualDisplay", ignoreCase = true) ||
                    msg.contains("截图超时") ||
                    msg.contains("legacy single shot") ||
                    msg.contains("未获取到有效图像")
            ) {
                logger.log(TAG, "检测到截图管线异常，本次停止，不主动重置投影，避免 Android 14 复用旧授权时报错")
            }
            moveState(HostState.FAILED, e.message ?: e.toString())
        } finally {
            lastFinishedAt = System.currentTimeMillis()
            running.set(false)
        }
    }

    private fun recognizeWithFallbacks(ocr: FallbackOcrProvider, cap: com.agentime.ime.host.capture.CaptureResult): String {
        val candidates = buildList {
            add("原图" to cap.imagePath)
            cap.chatCropPath?.let { add("聊天区裁剪图" to it) }
            cap.enhancedChatCropPath?.let { add("增强裁剪图" to it) }
        }
        for ((label, path) in candidates) {
            val text = ocr.recognize(path).trim()
            logger.log(TAG, "$label OCR 长度=${text.length}")
            if (text.isNotBlank()) {
                logger.log(TAG, "采用 $label OCR 结果")
                return text
            }
        }
        return ""
    }

    private fun moveState(state: HostState, detail: String) {
        logger.log(TAG, "state=$state detail=$detail")
        val prefs = getSharedPreferences("host_state", Context.MODE_PRIVATE)
        prefs.edit().putString("state", state.name).putString("detail", detail).apply()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification("$state: $detail"))
        showProgressToast(state, detail)
    }

    /** 主线程 Toast，便于用户离开主界面时仍知当前步骤（通知栏同步有摘要）。 */
    private fun showProgressToast(state: HostState, detail: String) {
        val label = when (state) {
            HostState.IDLE -> "进行中"
            HostState.WECHAT_READY -> "微信"
            HostState.INPUT_FOCUSED -> "输入框"
            HostState.SCREEN_CAPTURED -> "截图"
            HostState.REPLY_READY -> "回复"
            HostState.TEXT_INJECTED -> "注入"
            HostState.SENT -> "完成"
            HostState.FAILED -> "失败"
        }
        val maxLen = if (state == HostState.FAILED) 100 else 70
        val short = detail.take(maxLen).let { if (detail.length > maxLen) "$it…" else it }
        val text = "Agent · $label：$short"
        val duration = if (state == HostState.FAILED) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        mainHandler.post {
            Toast.makeText(applicationContext, text, duration).show()
        }
    }

    private fun showIssueHint(title: String, message: String) {
        val intent = Intent(this, IssueHintActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IssueHintActivity.EXTRA_TITLE, title)
            putExtra(IssueHintActivity.EXTRA_MESSAGE, message)
        }
        runCatching { startActivity(intent) }
            .onFailure { logger.log(TAG, "弹窗提示启动失败: ${it.message}") }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Agent Host",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.host_service_title))
            .setContentText(content)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(content: String, includeProjectionType: Boolean) {
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (includeProjectionType) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            try {
                startForeground(NOTIFICATION_ID, notification, type)
                logger.log(
                    TAG,
                    if (includeProjectionType) {
                        "前台服务已启动 type=dataSync|mediaProjection"
                    } else {
                        "前台服务已启动 type=dataSync"
                    },
                )
            } catch (e: SecurityException) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                logger.log(TAG, "mediaProjection 类型前台服务启动失败，已降级为 dataSync: ${e.message}")
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
            logger.log(TAG, "前台服务已启动 type=legacy")
        }
    }

    companion object {
        private const val TAG = "HostForegroundService"
        private const val CHANNEL_ID = "agent_host_channel"
        private const val NOTIFICATION_ID = 1001
        private val running = AtomicBoolean(false)
        @Volatile private var lastFinishedAt: Long = 0L

        const val ACTION_RUN_ONCE = "com.agentime.ime.action.RUN_ONCE"
        const val ACTION_PREPARE_PROJECTION = "com.agentime.ime.action.PREPARE_PROJECTION"
        const val ACTION_SCAN_CONVERSATION_LIST = "com.agentime.ime.action.SCAN_CONVERSATION_LIST"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_CONTACT_NAME = "contact_name"

        fun isBusyOrCoolingDown(): Boolean {
            val coolingDown = System.currentTimeMillis() - lastFinishedAt < 8_000L
            return running.get() || coolingDown
        }
    }
}
