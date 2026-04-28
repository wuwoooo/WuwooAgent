package com.agentime.ime.host.orchestrator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HostForegroundService : Service() {
    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var logger: HostLogger
    private var lastReportedOutboundText: String = ""
    private val startupScanRunnable = Runnable {
        val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("runtime_enabled", false)) return@Runnable
        io.execute { scanConversationListAndRun(scanSource = "startup") }
    }

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
            ACTION_START_RUNTIME -> {
                mainHandler.removeCallbacks(startupScanRunnable)
                // 延迟 2s 后首次扫描，给 MediaProjection 授权弹窗留出交互时间；
                // 即使此时管线未就绪也没关系：prepareProjection 完成后会再补一次 post_prepare 扫描。
                mainHandler.postDelayed(startupScanRunnable, 2000L)
                logger.log(TAG, "运行期启动，已安排首次截图分析")
            }
            ACTION_SCAN_CONVERSATION_LIST -> io.execute {
                scanConversationListAndRun(
                    intent.getStringExtra(EXTRA_SCAN_SOURCE).orEmpty().ifBlank { "unknown" },
                )
            }
            ACTION_RUN_ONCE -> {
                val contactName = SessionIdentity.normalizeContactName(
                    this@HostForegroundService,
                    intent.getStringExtra(EXTRA_CONTACT_NAME),
                )
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty().ifBlank {
                    SessionIdentity.buildSessionId(this@HostForegroundService, contactName)
                }
                io.execute { runOnce(sessionId, contactName) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(startupScanRunnable)
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun prepareProjection() {
        val capture = CaptureProviderFactory.create(this)
        val initOk = runCatching {
            startForegroundCompat("正在初始化截图管线", includeProjectionType = true)
            logger.log(TAG, "开始初始化截图管线 provider=${CaptureProviderFactory.currentProvider(this)}")
            capture.prewarm()
            logger.log(TAG, "截图管线初始化完成")
        }.onFailure {
            logger.log(TAG, "截图管线初始化失败: ${it.message}")
        }.isSuccess

        // 截图管线就绪后，若运行时已启用，立即补触发一次会话列表扫描。
        // 这解决了「先点开始运行 → 管线尚未就绪时 startup 扫描失败 → 用户在弹窗授权后无后续扫描」的竞态问题。
        if (initOk) {
            val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
            if (prefs.getBoolean("runtime_enabled", false)) {
                logger.log(TAG, "截图管线就绪，补触发一次会话列表扫描（source=post_prepare）")
                // 稍延迟以确保前台服务通知已更新
                mainHandler.postDelayed({
                    io.execute { scanConversationListAndRun(scanSource = "post_prepare") }
                }, 500L)
            }
        }
    }

    private fun scanConversationListAndRun(scanSource: String) {
        val bypassCoolingDown =
            scanSource.contains("urgent_chat_followup") ||
                scanSource.contains("post_send_back_followup")
        if (running.get() || (!bypassCoolingDown && isBusyOrCoolingDown())) {
            logger.log(TAG, "会话列表截图分析跳过：当前正忙或冷却中")
            return
        }
        val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("runtime_enabled", false)) return
        if (prefs.getString("execution_mode", "auto").orEmpty() != "auto") return

        val capture = CaptureProviderFactory.create(this)
        val ocr = FallbackOcrProvider(LocalOcrProvider(this), RemoteOcrProvider(this))
        try {
            logger.log(TAG, "开始通过截图分析当前微信页（source=$scanSource）")
            // 注意：此处不以 MEDIA_PROJECTION 类型调用 startForeground，
            // Android 14 下重复声明该类型会触发系统停止旧的 MediaProjection（onStop 回调）。
            // prepareProjection() 已在首次初始化时声明了该类型，后续无需重复。
            startForegroundCompat("正在识别当前微信页面", includeProjectionType = true)
            val cap = capture.captureScreen("wx_list_scan")
            val headerOcrText = recognizeHeaderWithFallbacks(ocr, cap)
            val ocrText = recognizePageWithFallbacks(ocr, cap)
            val pageAnalysis = ConversationListUnreadDetector.analyzeConversationListPage(cap.imagePath, ocrText, headerOcrText)
            logger.log(TAG, "截图分析列表页判定: ${pageAnalysis.debugSummary}")
            if (!pageAnalysis.looksLikeListPage) {
                val chatAnalysis = ConversationListUnreadDetector.analyzeChatPage(ocrText, headerOcrText)
                logger.log(TAG, "截图分析聊天页判定: ${chatAnalysis.debugSummary}")
                if (chatAnalysis.looksLikeChatPage) {
                    val prefsReply = getSharedPreferences("host_config", Context.MODE_PRIVATE)
                    val contactName = SessionIdentity.normalizeContactName(
                        this@HostForegroundService,
                        ConversationListUnreadDetector.extractChatContactNameFromOcr(headerOcrText)
                            .ifBlank { chatAnalysis.contactName }
                            .ifBlank { "当前联系人" },
                    )
                    if (contactName.isBlank() || contactName == "当前联系人") {
                        logger.log(TAG, "截图分析结果：当前是聊天页，但联系人识别无效，跳过本轮")
                        returnToConversationList("聊天页联系人识别无效")
                        return
                    }
                    val lastReplyText = prefsReply.getString("last_reply_text", "").orEmpty()
                    val inboundCandidate = extractInboundCandidate(
                        ocr = ocr,
                        cap = cap,
                        contactName = contactName,
                        lastReplyText = lastReplyText,
                        pageOcrText = ocrText,
                    )
                    val latestInbound = inboundCandidate.text
                    val inboundSignature = inboundCandidate.signature
                    logger.log(
                        TAG,
                        "聊天页触发门槛检测: latestSide=${cap.latestVisibleMessageSide} hasInboundAfterLatestOutbound=${cap.hasInboundAfterLatestOutbound} source=${inboundCandidate.sourceLabel}",
                    )
                    if (shouldSkipBecauseLatestVisibleIsOutbound(cap, inboundCandidate)) {
                        val currentOutboundText = cap.latestOutboundCropPath?.let { ocr.recognize(it).trim() } ?: ""
                        val lastReplyForCompare = prefsReply.getString("last_reply_text", "").orEmpty()
                        // 判断 outbound 文本是否为 AI 自动发出的回复（与上次 AI 回复高度相似时跳过上报）
                        val looksLikeAiReply = lastReplyForCompare.isNotBlank() &&
                            ConversationTextExtractor.looksLikeAgentReplyCandidate(currentOutboundText, lastReplyForCompare)
                        if (currentOutboundText.isNotEmpty() && currentOutboundText != lastReportedOutboundText && !looksLikeAiReply) {
                            val agentClient = HttpAgentClient(this@HostForegroundService)
                            val sessionId = SessionIdentity.buildSessionId(this@HostForegroundService, contactName)
                            try {
                                agentClient.chat(cap.imagePath, currentOutboundText, sessionId, contactName, isHumanReply = true)
                                lastReportedOutboundText = currentOutboundText
                                logger.log(TAG, "检测到真人介入并发送消息: [${currentOutboundText.take(20)}]，已同步至后台接管流程")
                            } catch (e: Exception) {
                                logger.log(TAG, "同步真人介入状态失败: ${e.message}")
                            }
                        } else if (looksLikeAiReply) {
                            lastReportedOutboundText = currentOutboundText
                            logger.log(TAG, "检测到 outbound 文本与 AI 上次回复高度相似，跳过真人介入上报")
                        }
                        cleanupIntermediateOcrCrops(cap, inboundCandidate.path)
                        logger.log(TAG, "截图分析结果：当前聊天页最新可见消息疑似己方发送，且未检测到己方之后新入站，跳过本轮")
                        returnToConversationList("聊天页最新消息非客户新消息")
                        return
                    }
                    inboundCandidate.path?.let {
                        exportFinalInboundOcrSnapshot(it, SessionIdentity.buildSessionId(this@HostForegroundService, contactName))
                        val pngFileName = File(it).name
                        val pngBytes = File(it).readBytes()
                        com.agentime.ime.host.capture.CaptureImageProcessor.exportPublicCopy(this@HostForegroundService, pngFileName, pngBytes)
                    }
                    cleanupIntermediateOcrCrops(cap, inboundCandidate.path)
                    logger.log(TAG, "截图分析聊天页最新客户消息预判: ${latestInbound.take(120)}")
                    if (latestInbound.isBlank()) {
                        logger.log(TAG, "截图分析结果：当前是聊天页，但未提取到有效客户新消息，跳过本轮")
                        returnToConversationList("聊天页未提取到有效客户新消息")
                        return
                    }
                    if (ConversationTextExtractor.looksLikeAgentReplyCandidate(latestInbound, lastReplyText)) {
                        logger.log(TAG, "截图分析结果：当前提取文本疑似我方上一条回复或客服话术，跳过本轮")
                        returnToConversationList("聊天页提取文本疑似我方回复")
                        return
                    }
                    val lastInboundSignature = prefsReply.getString("last_inbound_signature", "").orEmpty()
                    if (inboundSignature.isNotBlank() && inboundSignature == lastInboundSignature) {
                        logger.log(TAG, "截图分析结果：当前是聊天页，但客户最新消息未变化，跳过本轮")
                        returnToConversationList("聊天页客户最新消息未变化")
                        return
                    }
                    logger.log(TAG, "截图分析结果：当前是聊天页，source=$scanSource，检测到客户新消息，直接复用本轮截图进入回复流程，联系人=$contactName")
                    runOnce(
                        sessionId = SessionIdentity.buildSessionId(this@HostForegroundService, contactName),
                        contactName = contactName,
                        preCaptured = cap,
                        preOcrText = ocrText,
                        preLatestInbound = latestInbound,
                        preInboundSignature = inboundSignature,
                    )
                    return
                }
                logger.log(TAG, "截图分析结果：当前既不是微信会话列表页，也不像聊天页，跳过本轮；OCR=${ocrText.take(120)}")
                return
            }
            val badgeDebugLog = StringBuilder()
            val hit = ConversationListUnreadDetector.findTopUnreadConversation(cap.imagePath, badgeDebugLog)
            //logger.log(TAG, "红点扫描诊断: $badgeDebugLog")
            if (hit == null) {
                logger.log(TAG, "截图分析结果：已识别为会话列表页，但未识别到可点击的未读红点")
                if (scanSource.startsWith("post_send_back_followup")) {
                    schedulePostSendAdaptiveRetry(scanSource)
                }
                return
            }
            val listRowContactHint = extractListRowContactHint(cap.imagePath, hit.tapY, ocr)
            if (listRowContactHint.isNotBlank()) {
                logger.log(TAG, "列表页点击目标联系人提示: $listRowContactHint")
            }
            logger.log(TAG, "截图分析识别到未读会话，准备点击 tap=(${hit.tapX.toInt()},${hit.tapY.toInt()}) score=${hit.redScore}")
            if (!com.agentime.ime.host.automation.WechatAccessibilityService.tapConversationAt(hit.tapX, hit.tapY)) {
                logger.log(TAG, "截图分析点击未读会话失败")
                return
            }
            Thread.sleep(1400)
            val postClickCap = capture.captureScreen("wx_chat_after_list_click")
            val postClickHeaderOcr = recognizeHeaderWithFallbacks(ocr, postClickCap)
            val postClickOcr = recognizePageWithFallbacks(ocr, postClickCap)
            val postClickListAnalysis = ConversationListUnreadDetector.analyzeConversationListPage(
                postClickCap.imagePath,
                postClickOcr,
                postClickHeaderOcr,
            )
            val postClickChatAnalysis = ConversationListUnreadDetector.analyzeChatPage(postClickOcr, postClickHeaderOcr)
            logger.log(TAG, "点击后列表页判定: ${postClickListAnalysis.debugSummary}")
            logger.log(TAG, "点击后聊天页判定: ${postClickChatAnalysis.debugSummary}")
            if (postClickListAnalysis.looksLikeListPage || !postClickChatAnalysis.looksLikeChatPage) {
                logger.log(TAG, "点击未读会话后，页面仍未稳定进入聊天页，取消本轮")
                return
            }
            val headerRawName = ConversationListUnreadDetector.extractChatContactNameFromOcr(postClickHeaderOcr)
            val headerScore = ConversationListUnreadDetector.scoreHeaderOcrCandidate(postClickHeaderOcr)
            val listHintStrict = SessionIdentity.normalizeContactName(
                this@HostForegroundService,
                listRowContactHint,
                useFuzzyMatch = false,
            ).takeIf { it.isNotBlank() && it != "当前联系人" }.orEmpty()
            val headerResolvedName = SessionIdentity.normalizeContactName(
                this@HostForegroundService,
                headerRawName,
                useFuzzyMatch = true,
            )
            val contactName = when {
                listHintStrict.isNotBlank() -> listHintStrict
                headerScore >= 80 &&
                    headerResolvedName.isNotBlank() &&
                    headerResolvedName != "当前联系人" -> headerResolvedName
                else -> "当前联系人"
            }
            logger.log(
                TAG,
                "点击入会话联系人绑定: listHint=$listHintStrict header=$headerResolvedName headerScore=$headerScore",
            )
            if (contactName.isBlank() || contactName == "当前联系人") {
                logger.log(TAG, "点击未读会话后，联系人绑定置信度不足，取消本轮（避免串会话）")
                returnToConversationList("点击后联系人绑定置信度不足")
                return
            }
            if (listRowContactHint.isNotBlank() &&
                headerResolvedName.isNotBlank() &&
                headerResolvedName != "当前联系人" &&
                headerResolvedName != contactName
            ) {
                logger.log(
                    TAG,
                    "点击入会话联系人校验不一致：listHint=$listRowContactHint header=$headerResolvedName；已采用 listHint 作为会话主键",
                )
            }
            logger.log(TAG, "截图分析进入会话成功，联系人=$contactName")
            val prefsReply = getSharedPreferences("host_config", Context.MODE_PRIVATE)
            val lastReplyText = prefsReply.getString("last_reply_text", "").orEmpty()
            val inboundCandidate = extractInboundCandidate(
                ocr = ocr,
                cap = postClickCap,
                contactName = contactName,
                lastReplyText = lastReplyText,
                pageOcrText = postClickOcr,
            )
            val latestInbound = inboundCandidate.text
            val inboundSignature = inboundCandidate.signature
            logger.log(
                TAG,
                "点击入会话门槛检测: latestSide=${postClickCap.latestVisibleMessageSide} hasInboundAfterLatestOutbound=${postClickCap.hasInboundAfterLatestOutbound} source=${inboundCandidate.sourceLabel}",
            )
            if (shouldSkipBecauseLatestVisibleIsOutbound(postClickCap, inboundCandidate)) {
                val currentOutboundText = postClickCap.latestOutboundCropPath?.let { ocr.recognize(it).trim() } ?: ""
                // 判断 outbound 文本是否为 AI 自动发出的回复（与上次 AI 回复高度相似时跳过上报）
                val looksLikeAiReply = lastReplyText.isNotBlank() &&
                    ConversationTextExtractor.looksLikeAgentReplyCandidate(currentOutboundText, lastReplyText)
                if (currentOutboundText.isNotEmpty() && currentOutboundText != lastReportedOutboundText && !looksLikeAiReply) {
                    val agentClient = HttpAgentClient(this@HostForegroundService)
                    val sessionId = SessionIdentity.buildSessionId(this@HostForegroundService, contactName)
                    try {
                        agentClient.chat(postClickCap.imagePath, currentOutboundText, sessionId, contactName, isHumanReply = true)
                        lastReportedOutboundText = currentOutboundText
                        logger.log(TAG, "检测到真人介入并发送消息: [${currentOutboundText.take(20)}]，已同步至后台接管流程")
                    } catch (e: Exception) {
                        logger.log(TAG, "同步真人介入状态失败: ${e.message}")
                    }
                } else if (looksLikeAiReply) {
                    lastReportedOutboundText = currentOutboundText
                    logger.log(TAG, "检测到 outbound 文本与 AI 上次回复高度相似，跳过真人介入上报")
                }
                cleanupIntermediateOcrCrops(postClickCap, inboundCandidate.path)
                logger.log(TAG, "点击进入聊天后，最新可见消息疑似己方发送，且未检测到己方之后新入站，取消本轮")
                returnToConversationList("点击进入后最新消息非客户新消息")
                return
            }
            inboundCandidate.path?.let {
                exportFinalInboundOcrSnapshot(it, SessionIdentity.buildSessionId(this@HostForegroundService, contactName))
                val pngFileName = File(it).name
                val pngBytes = File(it).readBytes()
                com.agentime.ime.host.capture.CaptureImageProcessor.exportPublicCopy(this@HostForegroundService, pngFileName, pngBytes)
            }
            cleanupIntermediateOcrCrops(postClickCap, inboundCandidate.path)
            logger.log(TAG, "点击进入聊天后最新客户消息预判: ${latestInbound.take(120)}")
            if (latestInbound.isBlank()) {
                logger.log(TAG, "点击进入聊天后，未提取到有效客户新消息，取消本轮")
                returnToConversationList("点击进入后未提取到有效客户新消息")
                return
            }
            if (ConversationTextExtractor.looksLikeAgentReplyCandidate(latestInbound, lastReplyText)) {
                logger.log(TAG, "点击进入聊天后，提取文本疑似我方上一条回复或客服话术，取消本轮")
                returnToConversationList("点击进入后提取文本疑似我方回复")
                return
            }
            val lastInboundSignature = prefsReply.getString("last_inbound_signature", "").orEmpty()
            if (inboundSignature.isNotBlank() && inboundSignature == lastInboundSignature) {
                logger.log(TAG, "点击进入聊天后，客户最新消息未变化，取消本轮")
                returnToConversationList("点击进入后客户最新消息未变化")
                return
            }
            runOnce(
                sessionId = SessionIdentity.buildSessionId(this@HostForegroundService, contactName),
                contactName = contactName,
                preCaptured = postClickCap,
                preOcrText = postClickOcr,
                preLatestInbound = latestInbound,
                preInboundSignature = inboundSignature,
            )
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            // 授权类失败：需要用户手动重新授权，不应自动重试
            val isAuthFailure =
                msg.contains("未授权录屏") ||
                    msg.contains("重新授权截图") ||
                    msg.contains("授权已失效") ||
                    msg.contains("录屏授权数据丢失") ||
                    msg.contains("non-current MediaProjection", ignoreCase = true)
            // 管线类失败：可由系统自动重试一次
            val isPipelineFailure = !isAuthFailure && (
                msg.contains("截图超时") ||
                    msg.contains("legacy single shot") ||
                    msg.contains("未获取到有效图像") ||
                    msg.contains("VirtualDisplay", ignoreCase = true)
            )
            // 判断是否处于「启动初始化阶段」的来源：
            // startup / delayed_startup_event / post_prepare 均可能在 prepareProjection 完成前触发，
            // 此时未授权属于正常竞态，不弹提示，等待 post_prepare 扫描覆盖即可。
            val isStartupSource = scanSource == "startup" ||
                scanSource.startsWith("delayed_startup") ||
                scanSource == "post_prepare"
            if (isAuthFailure && isStartupSource) {
                // 启动竞态导致的未授权：静默跳过，prepareProjection 完成后 post_prepare 会补触发
                logger.log(TAG, "会话列表截图跳过（source=$scanSource，截图管线尚未就绪，将由 post_prepare 补触发）")
            } else {
                if (isAuthFailure) {
                    showIssueHint(
                        title = "截图授权已失效",
                        message =
                            "无法截图，请回到 Agent IME 主界面，重新点击「授权截图(MediaProjection)」后再开始运行。",
                    )
                }
                logger.log(TAG, "会话列表截图分析失败: ${e.message}")
                // 管线类失败 → 仅允许重试一次（source 不含 _retry 时），防止出现无限重试链
                if (isPipelineFailure && !scanSource.contains("_retry")) {
                    val retryDelaySec = PIPELINE_RETRY_DELAY_MS / 1000L
                    logger.log(TAG, "截图管线失败，将在 ${retryDelaySec}s 后自动重试扫描一次（source=${scanSource}_retry）")
                    mainHandler.postDelayed({
                        io.execute { scanConversationListAndRun(scanSource = "${scanSource}_retry") }
                    }, PIPELINE_RETRY_DELAY_MS)
                }
            }
        }
    }
    private fun runOnce(
        sessionId: String,
        contactName: String,
        preCaptured: com.agentime.ime.host.capture.CaptureResult? = null,
        preOcrText: String? = null,
        preLatestInbound: String? = null,
        preInboundSignature: String? = null,
    ) {
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
            val cap: com.agentime.ime.host.capture.CaptureResult
            val ocrText: String
            val latestInbound: String
            val inboundSignature: String
            if (preCaptured != null && !preOcrText.isNullOrBlank() && !preLatestInbound.isNullOrBlank()) {
                cap = preCaptured
                ocrText = preOcrText
                latestInbound = preLatestInbound
                inboundSignature = preInboundSignature.orEmpty()
                startForegroundCompat("正在分析微信聊天", includeProjectionType = true)
                logger.log(TAG, "复用页面分析阶段已成功获取的截图与 OCR，跳过二次截图")
                moveState(HostState.SCREEN_CAPTURED, "复用截图: ${cap.imagePath}")
            } else {
                startForegroundCompat("正在分析微信聊天", includeProjectionType = true)
                logger.log(TAG, "开始截图，executionMode=$executionMode provider=$captureProvider")
                cap = capture.captureScreen(sessionId)
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
                val headerOcrText = recognizeHeaderWithFallbacks(ocr, cap)
                ocrText = recognizePageWithFallbacks(ocr, cap)
                logger.log(TAG, "OCR 文本: ${ocrText.take(200)}")
                val resolvedContactName = SessionIdentity.normalizeContactName(
                    this@HostForegroundService,
                    ConversationListUnreadDetector.extractChatContactNameFromOcr(headerOcrText),
                )
                if (resolvedContactName.isNotBlank() && resolvedContactName != "当前联系人") {
                    logger.log(TAG, "顶部栏识别联系人: $resolvedContactName")
                }
                val inboundCandidate = extractInboundCandidate(
                    ocr = ocr,
                    cap = cap,
                    contactName = resolvedContactName.ifBlank { contactName },
                    lastReplyText = prefs.getString("last_reply_text", "").orEmpty(),
                    pageOcrText = ocrText,
                )
                latestInbound = inboundCandidate.text
                inboundSignature = inboundCandidate.signature
                inboundCandidate.path?.let {
                        exportFinalInboundOcrSnapshot(it, sessionId)
                        val pngFileName = File(it).name
                        val pngBytes = File(it).readBytes()
                        com.agentime.ime.host.capture.CaptureImageProcessor.exportPublicCopy(this@HostForegroundService, pngFileName, pngBytes)
                    }
                cleanupIntermediateOcrCrops(cap, inboundCandidate.path)
            }
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
            logger.log(TAG, "提炼后的最新客户消息: ${latestInbound.take(200)}")
            if (latestInbound.isBlank()) error("未能从 OCR 中提取出最新客户消息")

            val lastInboundSignature = prefs.getString("last_inbound_signature", "").orEmpty()
            if (inboundSignature.isNotBlank() && inboundSignature == lastInboundSignature) {
                logger.log(TAG, "检测到最近客户消息未变化，跳过本轮回复")
                moveState(HostState.SENT, "最近客户消息未变化，已跳过")
                returnToConversationList("最近客户消息未变化")
                return
            }

            val reply = agentClient.chat(cap.imagePath, latestInbound, sessionId, contactName)
            moveState(HostState.REPLY_READY, "reply_text 长度=${reply.replyText.length}")

            if (reply.replyText.isBlank()) {
                if (reply.silenced) {
                    logger.log(TAG, "后端会话处于静音状态，跳过本轮注入: status=${reply.currentStatus} reason=${reply.reason}")
                    moveState(HostState.SENT, "后端会话静音，已跳过: ${reply.currentStatus.ifBlank { reply.reason }}")
                    returnToConversationList("后端会话静音或人工接管")
                    return
                }
                logger.log(TAG, "Agent 返回空 reply_text，raw=${reply.raw.take(500)}")
                error("reply_text 为空")
            }
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
                // 将 AI 自动发出的回复记录到 lastReportedOutboundText，
                // 防止后续截屏检测时将 AI 回复误判为真人手动介入。
                lastReportedOutboundText = reply.replyText
                moveState(HostState.SENT, "发送完成")
                // 发送后先在当前会话内快速探测一次：若对方又发了新消息，先不返回列表页，
                // 直接触发一次会话内补扫，避免“返回后无红点导致漏处理”。
                val hasFreshInbound = detectFreshInboundAfterSend(
                    sessionId = sessionId,
                    contactName = contactName,
                    capture = capture,
                    ocr = ocr,
                    prefs = prefs,
                )
                if (hasFreshInbound) {
                    logger.log(TAG, "发送后检测到会话内新增客户消息，取消自动返回，触发紧急会话补扫")
                    scheduleUrgentChatFollowupScan()
                } else {
                    // 发送完消息后，稍作停留(等待动画)即返回会话列表页
                    Thread.sleep(800)
                    val backOk = automation.clickBack()
                    logger.log(TAG, "已触发自动返回动作，执行结果=$backOk")
                    if (backOk) {
                        schedulePostSendFollowupScan("post_send_back")
                    }
                }
            }
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            // 授权类失败：需要用户手动重新授权
            val isAuthFailure =
                msg.contains("未授权录屏") ||
                    msg.contains("重新授权截图") ||
                    msg.contains("授权已失效") ||
                    msg.contains("录屏授权数据丢失") ||
                    msg.contains("non-current MediaProjection", ignoreCase = true)
            // 管线类失败：可由系统自动重试一次
            val isPipelineFailure = !isAuthFailure && (
                msg.contains("截图超时") ||
                    msg.contains("legacy single shot") ||
                    msg.contains("未获取到有效图像") ||
                    msg.contains("VirtualDisplay", ignoreCase = true)
            )
            if (isPipelineFailure) {
                logger.log(TAG, "检测到截图管线异常，本次停止")
            }
            moveState(HostState.FAILED, e.message ?: e.toString())
            // 管线类失败且自动模式 → 仅允许重试一次（sessionId 不含 _retry 时）
            val prefs2 = getSharedPreferences("host_config", Context.MODE_PRIVATE)
            val autoMode = prefs2.getString("execution_mode", "auto").orEmpty() == "auto"
            if (isPipelineFailure && autoMode && !sessionId.contains("_retry")) {
                val retryDelaySec = PIPELINE_RETRY_DELAY_MS / 1000L
                logger.log(TAG, "截图管线失败，将在 ${retryDelaySec}s 后自动重试扫描一次（source=runonce_capture_retry）")
                mainHandler.postDelayed({
                    io.execute { scanConversationListAndRun(scanSource = "runonce_capture_retry") }
                }, PIPELINE_RETRY_DELAY_MS)
            }
        } finally {
            lastFinishedAt = System.currentTimeMillis()
            running.set(false)
        }
    }

    private fun recognizeWithFallbacks(ocr: FallbackOcrProvider, cap: com.agentime.ime.host.capture.CaptureResult): String {
        val candidates = buildList {
            add("原图" to cap.imagePath)
            cap.chatCropPath?.let { add("聊天区裁剪图" to it) }
        }
        for ((label, path) in candidates) {
            val text = ocr.recognize(path).trim()
            if (text.isNotBlank()) {
                logger.log(TAG, "采用 $label OCR 结果, 长度=${text.length}")
                return text
            }
        }
        return ""
    }

    private fun recognizeHeaderWithFallbacks(ocr: FallbackOcrProvider, cap: com.agentime.ime.host.capture.CaptureResult): String {
        val candidates = buildList {
            cap.titleCropPath?.let { add("标题裁剪图" to it) }
            cap.headerCropPath?.let { add("顶部栏裁剪图" to it) }
            add("原图" to cap.imagePath)
        }
        var bestText = ""
        var bestLabel = ""
        var bestScore = Int.MIN_VALUE
        for ((label, path) in candidates) {
            val text = ocr.recognize(path).trim()
            if (text.isBlank()) continue
            val score = ConversationListUnreadDetector.scoreHeaderOcrCandidate(text)
            if (score > bestScore) {
                bestScore = score
                bestText = text
                bestLabel = label
            }
        }
        if (bestText.isNotBlank()) {
            logger.log(TAG, "顶部栏采用 $bestLabel, 长度=${bestText.length}, 候选分数=$bestScore")
        }
        return bestText
    }

    private fun recognizePageWithFallbacks(ocr: FallbackOcrProvider, cap: com.agentime.ime.host.capture.CaptureResult): String {
        val candidates = buildList {
            cap.chatCropPath?.let { add("聊天区裁剪图" to it) }
            add("原图" to cap.imagePath)
        }
        for ((label, path) in candidates) {
            val text = ocr.recognize(path).trim()
            if (text.isNotBlank()) {
                logger.log(TAG, "页面 OCR 采用 $label, 长度=${text.length}")
                return text
            }
        }
        return ""
    }

    private fun extractListRowContactHint(
        imagePath: String,
        tapY: Float,
        ocr: FallbackOcrProvider,
    ): String {
        return runCatching {
            val bitmap = BitmapFactory.decodeFile(imagePath) ?: return@runCatching ""
            try {
                val width = bitmap.width
                val height = bitmap.height
                if (width < 200 || height < 400) return@runCatching ""

                val rowHeight = (height * 0.094f).toInt().coerceAtLeast(130)
                val centerY = tapY.toInt().coerceIn(0, height - 1)
                val top = (centerY - rowHeight / 2).coerceAtLeast((height * 0.10f).toInt())
                val bottom = (centerY + rowHeight / 2).coerceAtMost((height * 0.88f).toInt())
                val left = (width * 0.16f).toInt().coerceAtLeast(0)
                val right = (width * 0.92f).toInt().coerceAtMost(width)
                if (bottom - top < 24 || right - left < 40) return@runCatching ""

                val rowCrop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                val tempFile = File(filesDir, "captures").apply { mkdirs() }
                    .resolve("cap_list_row_hint_${System.currentTimeMillis()}.png")
                try {
                    FileOutputStream(tempFile).use { fos ->
                        rowCrop.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                    val rowOcr = ocr.recognize(tempFile.absolutePath).trim()
                    val extracted = ConversationListUnreadDetector.extractChatContactNameFromOcr(rowOcr)
                    SessionIdentity.normalizeContactName(
                        this@HostForegroundService,
                        extracted,
                        useFuzzyMatch = false,
                    )
                        .takeIf { it.isNotBlank() && it != "当前联系人" }
                        .orEmpty()
                } finally {
                    rowCrop.recycle()
                    runCatching { tempFile.delete() }
                }
            } finally {
                bitmap.recycle()
            }
        }.getOrElse {
            logger.log(TAG, "列表页联系人提示提取失败: ${it.message}")
            ""
        }
    }

    private fun extractInboundCandidate(
        ocr: FallbackOcrProvider,
        cap: com.agentime.ime.host.capture.CaptureResult,
        contactName: String,
        lastReplyText: String,
        pageOcrText: String? = null,
    ): InboundCandidate {
        val candidates = buildList {
            cap.sinceLastOutboundCropPath?.let { add("我方最后一条之后会话区" to it) }
            cap.recentInboundClusterCropPath?.let { add("最近连续入站消息块" to it) }
        }
        val tierBuckets = linkedMapOf<Int, InboundCandidate>()

        if (!pageOcrText.isNullOrBlank()) {
            val res = ConversationTextExtractor.extractLatestInboundMessage(
                ocrText = pageOcrText,
                contactName = contactName,
                lastReplyText = lastReplyText,
            )
            if (res.text.isNotBlank()) {
                val score = scoreInboundCandidate(res.text)
                tierBuckets[2] = InboundCandidate(
                    text = res.text,
                    signature = res.signature,
                    sourceLabel = "页面OCR预提取",
                    path = cap.chatCropPath ?: cap.imagePath,
                    score = score,
                )
            }
        }

        for ((label, path) in candidates) {
            val ocrRes = ocr.recognize(path).trim()
            if (ocrRes.isBlank()) continue
            val res = ConversationTextExtractor.extractLatestInboundMessage(
                ocrText = ocrRes,
                contactName = contactName,
                lastReplyText = lastReplyText,
            )
            if (res.text.isBlank()) continue
            val score = scoreInboundCandidate(res.text)
            val tier = inboundSourceTier(label)
            val current = tierBuckets[tier]
            if (current == null || score > current.score) {
                tierBuckets[tier] = InboundCandidate(
                    text = res.text,
                    signature = res.signature,
                    sourceLabel = label,
                    path = path,
                    score = score,
                )
            }
        }
        return tierBuckets.entries.minByOrNull { it.key }?.value ?: InboundCandidate()
    }

    private fun scoreInboundCandidate(text: String): Int {
        val normalized = ConversationTextExtractor.signatureOf(text)
        if (normalized.isBlank()) return Int.MIN_VALUE
        var score = 0
        val length = normalized.length
        score += minOf(length, 42)
        if (length <= 2) score -= 15
        if (length in 3..4) score -= 6
        if (normalized.contains("?", ignoreCase = false) || normalized.contains("？") || normalized.contains("。") || normalized.contains("，") || normalized.contains(",")) {
            score += 6
        }
        val lineCount = normalized.lines().size
        if (lineCount == 2) score += 2
        if (lineCount >= 3) score -= (lineCount - 2) * 6
        return score
    }

    private fun inboundSourceTier(label: String): Int {
        return when {
            label.contains("我方最后一条之后会话区") -> 0
            label.contains("最近连续入站消息块") -> 1
            else -> 5
        }
    }

    private data class InboundCandidate(
        val text: String = "",
        val signature: String = "",
        val sourceLabel: String? = null,
        val path: String? = null,
        val score: Int = Int.MIN_VALUE,
    )

    private fun shouldSkipBecauseLatestVisibleIsOutbound(
        cap: com.agentime.ime.host.capture.CaptureResult,
        candidate: InboundCandidate,
    ): Boolean {
        val latestSide = cap.latestVisibleMessageSide.orEmpty()
        if (latestSide != "outbound") return false
        if (
            cap.hasInboundAfterLatestOutbound &&
            candidate.text.isNotBlank() &&
            candidate.sourceLabel.orEmpty().contains("我方最后一条之后会话区")
        ) {
            return false
        }
        // 防误触发优先：当“最新可见消息”仍是己方且没有可信的己方之后入站时，不自动回复。
        return true
    }

    private fun cleanupIntermediateOcrCrops(
        cap: com.agentime.ime.host.capture.CaptureResult,
        keepPath: String?,
    ) {
        val paths = listOfNotNull(
            cap.headerCropPath,
            cap.titleCropPath,
            cap.chatCropPath,
            cap.sinceLastOutboundCropPath,
            cap.recentInboundClusterCropPath,
            cap.leftMessageCropPath,
            cap.recentLeftMessageCropPath,
            cap.latestInboundBubbleCropPath,
        )
        paths.distinct()
            .filter { it != keepPath }
            .forEach { path ->
                runCatching { File(path).delete() }
            }
    }

    private fun exportFinalInboundOcrSnapshot(sourcePath: String, sessionId: String): String? {
        return runCatching {
            val source = File(sourcePath)
            if (!source.exists()) return null
            val target = File(filesDir, "captures").apply { mkdirs() }
                .resolve("cap_${sessionId}_final_inbound_ocr.png")
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            target.absolutePath
        }.getOrNull()
    }

    private fun schedulePostSendFollowupScan(reason: String) {
        val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("runtime_enabled", false)) return
        if (prefs.getString("execution_mode", "auto").orEmpty() != "auto") return

        val now = System.currentTimeMillis()
        if (now - lastPostSendFollowupScheduledAt < POST_SEND_FOLLOWUP_DEBOUNCE_MS) {
            logger.log(TAG, "发送后补扫已在防抖窗口内，跳过重复调度")
            return
        }
        lastPostSendFollowupScheduledAt = now

        val delaySec = POST_SEND_FOLLOWUP_DELAY_MS / 1000L
        logger.log(TAG, "已安排发送后补扫，将在 ${delaySec}s 后执行（source=${reason}_followup）")
        mainHandler.postDelayed({
            io.execute { scanConversationListAndRun(scanSource = "${reason}_followup") }
        }, POST_SEND_FOLLOWUP_DELAY_MS)
    }

    private fun schedulePostSendAdaptiveRetry(scanSource: String) {
        val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("runtime_enabled", false)) return
        if (prefs.getString("execution_mode", "auto").orEmpty() != "auto") return
        if (!scanSource.startsWith("post_send_back_followup")) return

        val (nextSource, delayMs) = when {
            scanSource.endsWith("_r2") -> return
            scanSource.endsWith("_r1") -> "post_send_back_followup_r2" to POST_SEND_FOLLOWUP_RETRY2_DELAY_MS
            else -> "post_send_back_followup_r1" to POST_SEND_FOLLOWUP_RETRY1_DELAY_MS
        }

        logger.log(
            TAG,
            "发送后补扫未命中未读，安排分级重试：source=$nextSource delay=${"%.1f".format(delayMs / 1000.0)}s",
        )
        mainHandler.postDelayed({
            io.execute { scanConversationListAndRun(scanSource = nextSource) }
        }, delayMs)
    }

    private fun returnToConversationList(reason: String, scheduleFollowup: Boolean = true) {
        val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
        if (prefs.getString("execution_mode", "auto").orEmpty() != "auto") return
        Thread.sleep(500)
        val backOk = com.agentime.ime.host.automation.WechatAccessibilityService.clickBack()
        logger.log(TAG, "跳过当前会话后返回会话列表: reason=$reason result=$backOk")
        if (backOk && scheduleFollowup) {
            schedulePostSendFollowupScan("skip_chat_return")
        }
    }

    private fun scheduleUrgentChatFollowupScan() {
        val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("runtime_enabled", false)) return
        if (prefs.getString("execution_mode", "auto").orEmpty() != "auto") return
        logger.log(TAG, "已安排紧急会话补扫，将在 ${URGENT_CHAT_FOLLOWUP_DELAY_MS / 1000.0}s 后执行")
        mainHandler.postDelayed({
            io.execute { scanConversationListAndRun(scanSource = "urgent_chat_followup") }
        }, URGENT_CHAT_FOLLOWUP_DELAY_MS)
    }

    private fun detectFreshInboundAfterSend(
        sessionId: String,
        contactName: String,
        capture: com.agentime.ime.host.capture.CaptureController,
        ocr: FallbackOcrProvider,
        prefs: android.content.SharedPreferences,
    ): Boolean {
        return runCatching {
            startForegroundCompat("正在检查会话内是否有连续新消息", includeProjectionType = true)
            val probeCap = capture.captureScreen("${sessionId}_postsend_probe")
            val probeSide = probeCap.latestVisibleMessageSide.orEmpty()
            if (probeSide != "inbound") {
                cleanupIntermediateOcrCrops(probeCap, null)
                logger.log(TAG, "发送后会话探测跳过：latestSide=$probeSide（仅当 inbound 才继续）")
                return@runCatching false
            }
            val sinceLastOutboundPath = probeCap.sinceLastOutboundCropPath
            if (sinceLastOutboundPath.isNullOrBlank()) {
                cleanupIntermediateOcrCrops(probeCap, null)
                return@runCatching false
            }
            val scopedOcrText = ocr.recognize(sinceLastOutboundPath).trim()
            cleanupIntermediateOcrCrops(probeCap, sinceLastOutboundPath)
            if (scopedOcrText.isBlank()) return@runCatching false
            val lastReplySig = ConversationTextExtractor.signatureOf(
                prefs.getString("last_reply_text", "").orEmpty(),
            )
            val scopedSig = ConversationTextExtractor.signatureOf(scopedOcrText)
            // 发送后探测必须足够保守：若仍大量含有我方刚发内容，则判为未出现新的客户入站。
            if (lastReplySig.isNotBlank() && scopedSig.contains(lastReplySig.take(8))) {
                return@runCatching false
            }
            val extraction = ConversationTextExtractor.extractLatestInboundMessage(
                ocrText = scopedOcrText,
                contactName = contactName,
                lastReplyText = prefs.getString("last_reply_text", "").orEmpty(),
            )
            val text = extraction.text
            val sig = extraction.signature
            val lastSig = prefs.getString("last_inbound_signature", "").orEmpty()
            if (text.isBlank() || sig.isBlank()) return@runCatching false
            if (sig == lastSig) return@runCatching false
            if (ConversationTextExtractor.looksLikeAgentReplyCandidate(text, prefs.getString("last_reply_text", "").orEmpty())) {
                return@runCatching false
            }
            logger.log(TAG, "发送后会话探测到新增客户消息候选: ${text.take(80)}")
            true
        }.getOrElse {
            logger.log(TAG, "发送后会话内新消息探测失败: ${it.message}")
            false
        }
    }

    private fun moveState(state: HostState, detail: String) {
        logger.log(TAG, "state=$state detail=$detail")
        val prefs = getSharedPreferences("host_state", Context.MODE_PRIVATE)
        prefs.edit().putString("state", state.name).putString("detail", detail).apply()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification("$state: $detail"))
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
        @Volatile private var lastPostSendFollowupScheduledAt: Long = 0L

        const val ACTION_RUN_ONCE = "com.agentime.ime.action.RUN_ONCE"
        const val ACTION_PREPARE_PROJECTION = "com.agentime.ime.action.PREPARE_PROJECTION"
        const val ACTION_START_RUNTIME = "com.agentime.ime.action.START_RUNTIME"
        const val ACTION_SCAN_CONVERSATION_LIST = "com.agentime.ime.action.SCAN_CONVERSATION_LIST"
        const val EXTRA_SCAN_SOURCE = "scan_source"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_CONTACT_NAME = "contact_name"
        private const val PIPELINE_RETRY_DELAY_MS = 3_000L
        // 发送并返回列表页后，尽快处理下一位未读联系人
        private const val POST_SEND_FOLLOWUP_DELAY_MS = 2_200L
        private const val POST_SEND_FOLLOWUP_DEBOUNCE_MS = 1_500L
        private const val POST_SEND_FOLLOWUP_RETRY1_DELAY_MS = 3_500L
        private const val POST_SEND_FOLLOWUP_RETRY2_DELAY_MS = 7_000L
        private const val URGENT_CHAT_FOLLOWUP_DELAY_MS = 1_400L

        fun isBusyOrCoolingDown(): Boolean {
            val coolingDown = System.currentTimeMillis() - lastFinishedAt < 8_000L
            return running.get() || coolingDown
        }
    }
}
