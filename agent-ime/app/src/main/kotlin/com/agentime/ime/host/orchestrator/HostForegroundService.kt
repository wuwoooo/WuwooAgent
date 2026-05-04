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
import com.agentime.ime.host.agent.OutboundTask
import com.agentime.ime.host.agent.SessionIdentity
import com.agentime.ime.host.automation.AccessibilityAutomationController
import com.agentime.ime.host.automation.ConversationListUnreadDetector
import com.agentime.ime.host.automation.NoopAutomationController
import com.agentime.ime.host.automation.WechatAccessibilityService
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
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HostForegroundService : Service() {
    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var logger: HostLogger
    private val lastReportedOutboundBySession = mutableMapOf<String, String>()
    private data class PendingRunOnce(
        val sessionId: String,
        val contactName: String,
        val isExplicitTrigger: Boolean,
    )
    private data class ContactBindingResult(
        val contactName: String,
        val titleSource: String,
        val titleName: String,
        val titleScore: Int,
        val headerName: String,
        val headerScore: Int,
    )

    private val startupScanRunnable = Runnable {
        val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("runtime_enabled", false)) return@Runnable
        io.execute { scanConversationListAndRun(scanSource = "startup") }
    }
    private val outboundTaskPollRunnable = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("runtime_enabled", false)) return
            if (!prefs.getBoolean("outbound_task_enabled", true)) return
            io.execute { pollAndRunOutboundTask() }
            mainHandler.postDelayed(this, OUTBOUND_TASK_POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        logger = HostLogger(this)
        createNotificationChannel()
        startForegroundCompat("Host 服务运行中", includeProjectionType = false)
        val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
        if (prefs.getBoolean("runtime_enabled", false) && prefs.getBoolean("outbound_task_enabled", true)) {
            startOutboundTaskPolling()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_PREPARE_PROJECTION -> io.execute { prepareProjection() }
            ACTION_START_RUNTIME -> {
                mainHandler.removeCallbacks(startupScanRunnable)
                val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
                if (prefs.getBoolean("outbound_task_enabled", true)) {
                    startOutboundTaskPolling()
                } else {
                    logger.log(TAG, "主动外发任务轮询未启用，保持原有自动回复链路独立运行")
                }
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
                val triggerTime = intent.getLongExtra("trigger_time", 0L)
                val isExplicit = intent.getBooleanExtra("is_explicit_trigger", false)
                io.execute {
                    if (triggerTime > 0 && System.currentTimeMillis() - triggerTime > 15000L) {
                        logger.log(TAG, "忽略超时的 runOnce 请求: $sessionId/$contactName (延迟=${System.currentTimeMillis() - triggerTime}ms)")
                        return@execute
                    }
                    runOnceOrQueue(sessionId, contactName, isExplicitTrigger = isExplicit, reason = "service_intent")
                }
            }
            ACTION_RUN_OUTBOUND_TASK -> io.execute {
                runNextOutboundTaskOnce()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(startupScanRunnable)
        mainHandler.removeCallbacks(outboundTaskPollRunnable)
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

    private fun startOutboundTaskPolling() {
        mainHandler.removeCallbacks(outboundTaskPollRunnable)
        mainHandler.postDelayed(outboundTaskPollRunnable, 1_200L)
        logger.log(TAG, "已启动主动外发任务轮询")
    }

    private fun pollAndRunOutboundTask() {
        val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("runtime_enabled", false)) return
        if (!prefs.getBoolean("outbound_task_enabled", true)) return
        if (prefs.getString("execution_mode", "auto").orEmpty() != "auto") return
        if (isBusyOrCoolingDown()) return
        if (!running.compareAndSet(false, true)) return

        val agentClient = HttpAgentClient(this)
        try {
            val task = agentClient.claimNextOutboundTask()
            if (task == null) {
                running.set(false)
                return
            }
            logger.log(TAG, "领取主动外发任务: id=${task.id} contact=${task.contactName} autoSend=${task.autoSend}")
            runOutboundTask(task, agentClient, alreadyReserved = true)
        } catch (e: Exception) {
            logger.log(TAG, "主动外发任务轮询失败: ${e.message}")
            running.set(false)
        }
    }

    private fun isRuntimeEnabled(): Boolean =
        getSharedPreferences("host_config", Context.MODE_PRIVATE)
            .getBoolean("runtime_enabled", false)

    private fun ensureRuntimeEnabled(stage: String) {
        if (!isRuntimeEnabled()) {
            logger.log(TAG, "运行已停止，取消当前任务: $stage")
            throw CancellationException("运行已停止")
        }
    }

    private fun runNextOutboundTaskOnce() {
        if (!isRuntimeEnabled()) {
            logger.log(TAG, "运行已停止，忽略主动外发触发")
            return
        }
        if (running.get()) {
            logger.log(TAG, "已有任务在执行中，忽略本次主动外发触发")
            return
        }
        val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
        if (prefs.getString("execution_mode", "auto").orEmpty() != "auto") {
            logger.log(TAG, "当前不是自动模式，忽略主动外发触发")
            return
        }
        val agentClient = HttpAgentClient(this)
        try {
            val task = agentClient.claimNextOutboundTask()
            if (task == null) {
                logger.log(TAG, "主动外发触发成功，但后端没有待执行任务")
                return
            }
            logger.log(TAG, "手动触发主动外发任务: id=${task.id} contact=${task.contactName} autoSend=${task.autoSend}")
            runOutboundTask(task, agentClient)
        } catch (e: Exception) {
            logger.log(TAG, "手动触发主动外发失败: ${e.message}")
        }
    }

    private fun runOutboundTask(task: OutboundTask, agentClient: HttpAgentClient, alreadyReserved: Boolean = false) {
        if (!isRuntimeEnabled()) {
            logger.log(TAG, "运行已停止，忽略主动外发任务: ${task.id}")
            runCatching { agentClient.completeOutboundTask(task.id, false, "手机端已停止运行") }
            return
        }
        if (!alreadyReserved && !running.compareAndSet(false, true)) {
            logger.log(TAG, "已有任务在执行中，主动外发任务暂不处理: ${task.id}")
            runCatching { agentClient.completeOutboundTask(task.id, false, "手机端正忙，请稍后重试") }
            return
        }
        val automation = AccessibilityAutomationController(this)
        val ime = BroadcastImeController(this)
        val capture = CaptureProviderFactory.create(this)
        val ocr = FallbackOcrProvider(LocalOcrProvider(this), RemoteOcrProvider(this))
        try {
            ensureRuntimeEnabled("主动外发开始")
            moveState(HostState.IDLE, "开始主动外发任务: ${task.contactName}")
            if (task.contactName.isBlank() || task.searchKeyword.isBlank() || task.message.isBlank()) {
                error("任务缺少联系人、搜索词或消息内容")
            }
            if (!WechatAccessibilityService.waitServiceConnected()) {
                error("无障碍服务未连接，无法主动定位联系人")
            }
            if (!automation.isWechatForeground()) {
                if (!automation.launchWechat()) error("无法启动微信，请检查微信是否已安装")
                WechatAccessibilityService.waitWechatForeground(6000)
                ensureRuntimeEnabled("主动外发等待微信前台后")
                Thread.sleep(1200)
                ensureRuntimeEnabled("主动外发等待微信稳定后")
            }

            var chatVerification = verifyOutboundChatByOcr(capture, ocr, task.contactName, "outbound_initial_${task.id}")
            ensureRuntimeEnabled("主动外发 OCR 校验后")
            if (!chatVerification.matched) {
                if (chatVerification.looksLikeChatPage) {
                    logger.log(TAG, "当前不是目标聊天页，先返回微信列表页")
                    ensureRuntimeEnabled("主动外发返回前")
                    automation.clickBack()
                    Thread.sleep(900)
                    ensureRuntimeEnabled("主动外发返回后")
                }
                moveState(HostState.WECHAT_READY, "准备通过微信搜索定位联系人")
                ensureRuntimeEnabled("主动外发打开搜索前")
                if (!automation.openWechatSearch()) error("点击微信搜索失败")
                Thread.sleep(500)
                ensureRuntimeEnabled("主动外发聚焦搜索前")
                if (!automation.focusWechatSearchInput()) error("聚焦微信搜索框失败")
                Thread.sleep(250)
                ensureRuntimeEnabled("主动外发注入搜索词前")
                if (!ime.injectText(task.searchKeyword)) error("注入搜索关键词失败")
                Thread.sleep(1200)
                ensureRuntimeEnabled("主动外发点击搜索结果前")
                if (!automation.tapWechatSearchResult(task.contactName, task.searchKeyword)) {
                    error("点击搜索结果失败")
                }
                chatVerification = waitOutboundChatByOcr(capture, ocr, task.contactName, 6_000L, "outbound_after_search_${task.id}")
                if (!chatVerification.matched && (task.autoSend || !chatVerification.looksLikeChatPage)) {
                    error("进入聊天页后联系人校验失败，已停止以避免误发")
                }
                if (!chatVerification.matched) {
                    logger.log(TAG, "OCR 已确认进入聊天页但标题未匹配；任务未自动发送，继续只填入文本: ${chatVerification.debugSummary}")
                }
            } else {
                logger.log(TAG, "OCR 确认手机已在目标联系人聊天页，直接准备输入: ${chatVerification.debugSummary}")
            }

            ensureRuntimeEnabled("主动外发聚焦输入框前")
            if (!automation.focusInputArea()) error("聚焦微信输入框失败")
            Thread.sleep(700)
            ensureRuntimeEnabled("主动外发注入文本前")
            if (!ime.injectText(task.message)) error("注入主动外发文本失败")
            moveState(HostState.TEXT_INJECTED, "主动外发文本已注入")

            if (task.autoSend) {
                Thread.sleep(650)
                ensureRuntimeEnabled("主动外发点击发送前")
                if (!automation.clickSend()) error("点击发送失败")
                moveState(HostState.SENT, "主动外发已发送")
                Thread.sleep(800)
                ensureRuntimeEnabled("主动外发返回列表前")
                val backOk = automation.clickBack()
                logger.log(TAG, "主动外发发送完成后返回列表页，执行结果=$backOk")
                if (backOk) {
                    schedulePostSendFollowupScan("outbound_post_send_back")
                }
            } else {
                moveState(HostState.SENT, "主动外发已填入，等待人工确认发送")
            }
            agentClient.completeOutboundTask(task.id, true)
            logger.log(TAG, "主动外发任务完成: id=${task.id}")
        } catch (e: Exception) {
            val errorMessage = e.message.orEmpty().ifBlank { e::class.java.simpleName }
            logger.log(TAG, "主动外发任务失败: id=${task.id} error=$errorMessage")
            runCatching { agentClient.completeOutboundTask(task.id, false, errorMessage) }
        } finally {
            lastFinishedAt = System.currentTimeMillis()
            running.set(false)
            schedulePendingWorkDrain()
        }
    }

    private data class OutboundChatVerification(
        val looksLikeChatPage: Boolean,
        val matched: Boolean,
        val observedName: String,
        val debugSummary: String,
    )

    private fun waitOutboundChatByOcr(
        capture: com.agentime.ime.host.capture.CaptureController,
        ocr: FallbackOcrProvider,
        contactName: String,
        timeoutMs: Long,
        capturePrefix: String,
    ): OutboundChatVerification {
        val start = System.currentTimeMillis()
        var last = OutboundChatVerification(false, false, "", "not_checked")
        var attempt = 0
        while (System.currentTimeMillis() - start < timeoutMs) {
            attempt += 1
            last = verifyOutboundChatByOcr(capture, ocr, contactName, "${capturePrefix}_$attempt")
            logger.log(TAG, "主动外发 OCR 聊天页校验#$attempt: ${last.debugSummary}")
            if (last.matched) return last
            Thread.sleep(650)
        }
        return last
    }

    private fun verifyOutboundChatByOcr(
        capture: com.agentime.ime.host.capture.CaptureController,
        ocr: FallbackOcrProvider,
        contactName: String,
        captureName: String,
    ): OutboundChatVerification {
        return runCatching {
            val cap = capture.captureScreen(captureName)
            val headerOcrText = recognizeHeaderWithFallbacks(ocr, cap)
            val pageOcrText = recognizePageWithFallbacks(ocr, cap)
            val chatAnalysis = ConversationListUnreadDetector.analyzeChatPage(pageOcrText, headerOcrText)
            val titleOcrText = recognizeTitleOnly(ocr, cap)
            val titleRaw = ConversationListUnreadDetector.extractChatContactNameFromOcr(titleOcrText)
            val headerRaw = ConversationListUnreadDetector.extractChatContactNameFromOcr(headerOcrText)
            val observed = listOf(titleRaw, headerRaw, chatAnalysis.contactName)
                .map { SessionIdentity.normalizeContactName(this@HostForegroundService, it, useFuzzyMatch = true) }
                .firstOrNull { it.isNotBlank() && it != "当前联系人" }
                .orEmpty()
            val matched = outboundNamesMatch(observed, contactName) ||
                outboundTextContainsName(titleOcrText, contactName) ||
                outboundTextContainsName(headerOcrText, contactName)
            OutboundChatVerification(
                looksLikeChatPage = chatAnalysis.looksLikeChatPage,
                matched = chatAnalysis.looksLikeChatPage && matched,
                observedName = observed,
                debugSummary = "looksLikeChatPage=${chatAnalysis.looksLikeChatPage} observed=$observed target=$contactName titleRaw=$titleRaw headerRaw=$headerRaw chat=${chatAnalysis.debugSummary}",
            )
        }.getOrElse { e ->
            OutboundChatVerification(false, false, "", "ocr_verify_failed=${e.message}")
        }
    }

    private fun outboundNamesMatch(observed: String, target: String): Boolean {
        val left = normalizeOutboundName(observed)
        val right = normalizeOutboundName(target)
        if (left.isBlank() || right.isBlank()) return false
        return left == right || left.contains(right) || right.contains(left)
    }

    private fun outboundTextContainsName(ocrText: String, target: String): Boolean {
        val text = normalizeOutboundName(ocrText)
        val name = normalizeOutboundName(target)
        return text.isNotBlank() && name.isNotBlank() && (text.contains(name) || name.contains(text))
    }

    private fun normalizeOutboundName(value: String): String {
        return value
            .replace("（", "(")
            .replace("）", ")")
            .replace(Regex("""[\s\n\r\t]+"""), "")
            .lowercase()
            .trim()
    }

    private fun isPostSendFollowup(scanSource: String): Boolean =
        scanSource.contains("post_send_back", ignoreCase = true)

    private fun looksLikeSearchContactEcho(text: String, contactName: String): Boolean {
        val contactKey = normalizeOutboundName(contactName).filter { it.isLetterOrDigit() || it in '一'..'龥' }
        if (contactKey.isBlank()) return false
        val textKey = normalizeOutboundName(text)
            .filter { it.isLetterOrDigit() || it in '一'..'龥' }
            .trimStart('q', 'o', '0')
        if (textKey.isBlank()) return false
        return textKey == contactKey || (contactKey.length >= 6 && textKey.endsWith(contactKey) && textKey.length <= contactKey.length + 2)
    }

    private fun scopedPrefKey(name: String, sessionId: String): String = "$name:$sessionId"

    private fun getLastReplyText(prefs: android.content.SharedPreferences, sessionId: String): String =
        prefs.getString(scopedPrefKey("last_reply_text", sessionId), null)
            ?: prefs.getString("last_reply_text", "").orEmpty()

    private fun getLastInboundSignature(prefs: android.content.SharedPreferences, sessionId: String): String =
        prefs.getString(scopedPrefKey("last_inbound_signature", sessionId), null)
            ?: prefs.getString("last_inbound_signature", "").orEmpty()

    private fun saveConversationDedupState(
        prefs: android.content.SharedPreferences,
        sessionId: String,
        inboundSignature: String,
        replyText: String,
    ) {
        prefs.edit()
            .putString(scopedPrefKey("last_inbound_signature", sessionId), inboundSignature)
            .putString(scopedPrefKey("last_reply_text", sessionId), replyText)
            // 保留旧全局字段作为兼容兜底；读取时会优先按会话取值。
            .putString("last_inbound_signature", inboundSignature)
            .putString("last_reply_text", replyText)
            .apply()
    }

    private fun getLastReportedOutbound(sessionId: String): String =
        synchronized(lastReportedOutboundBySession) { lastReportedOutboundBySession[sessionId].orEmpty() }

    private fun setLastReportedOutbound(sessionId: String, text: String) {
        synchronized(lastReportedOutboundBySession) {
            lastReportedOutboundBySession[sessionId] = text
            if (lastReportedOutboundBySession.size > 80) {
                val firstKey = lastReportedOutboundBySession.keys.firstOrNull()
                if (firstKey != null) lastReportedOutboundBySession.remove(firstKey)
            }
        }
    }

    private fun runOnceOrQueue(
        sessionId: String,
        contactName: String,
        isExplicitTrigger: Boolean = false,
        reason: String,
    ) {
        if (isBusyOrCoolingDown()) {
            if (isExplicitTrigger) {
                enqueuePendingScan("explicit_runonce_busy", "$reason:$sessionId/$contactName")
                return
            }
            enqueuePendingRunOnce(sessionId, contactName, isExplicitTrigger, reason)
            return
        }
        runOnce(sessionId, contactName, isExplicitTrigger = isExplicitTrigger)
    }

    private fun enqueuePendingRunOnce(
        sessionId: String,
        contactName: String,
        isExplicitTrigger: Boolean,
        reason: String,
    ) {
        val key = "$sessionId|$contactName"
        val queued = synchronized(pendingLock) {
            if (pendingRunOnceKeys.contains(key)) {
                false
            } else {
                if (pendingRunOnceQueue.size >= MAX_PENDING_RUN_ONCE) {
                    val dropped = pendingRunOnceQueue.removeFirst()
                    pendingRunOnceKeys.remove("${dropped.sessionId}|${dropped.contactName}")
                }
                pendingRunOnceQueue.addLast(PendingRunOnce(sessionId, contactName, isExplicitTrigger))
                pendingRunOnceKeys.add(key)
                true
            }
        }
        logger.log(TAG, if (queued) "当前正忙，已排队 runOnce: $sessionId/$contactName reason=$reason" else "runOnce 已在队列中，跳过重复排队: $sessionId/$contactName")
        schedulePendingWorkDrain()
    }

    private fun enqueuePendingScan(scanSource: String, reason: String) {
        synchronized(pendingLock) {
            pendingScanSource = scanSource.ifBlank { "queued_scan" }
        }
        logger.log(TAG, "当前正忙，已排队会话列表扫描: source=$scanSource reason=$reason")
        schedulePendingWorkDrain()
    }

    private fun schedulePendingWorkDrain(delayMs: Long = PENDING_DRAIN_DELAY_MS) {
        mainHandler.postDelayed({
            io.execute { drainPendingWorkIfIdle() }
        }, delayMs)
    }

    private fun drainPendingWorkIfIdle() {
        if (!isRuntimeEnabled()) return
        if (running.get()) {
            schedulePendingWorkDrain(1_500L)
            return
        }
        val nextScan = synchronized(pendingLock) {
            val source = pendingScanSource
            pendingScanSource = null
            source
        }
        if (!nextScan.isNullOrBlank()) {
            logger.log(TAG, "开始处理排队会话列表扫描: source=$nextScan")
            scanConversationListAndRun(scanSource = "queued_$nextScan")
            return
        }
        if (isBusyOrCoolingDown()) {
            schedulePendingWorkDrain(1_500L)
            return
        }
        val nextRun = synchronized(pendingLock) {
            val item = pendingRunOnceQueue.pollFirst()
            if (item != null) pendingRunOnceKeys.remove("${item.sessionId}|${item.contactName}")
            item
        }
        if (nextRun != null) {
            logger.log(TAG, "开始处理排队 runOnce: ${nextRun.sessionId}/${nextRun.contactName}")
            runOnce(nextRun.sessionId, nextRun.contactName, isExplicitTrigger = nextRun.isExplicitTrigger)
            return
        }
    }

    private fun scanConversationListAndRun(scanSource: String) {
        val bypassCoolingDown =
            scanSource.contains("urgent_chat_followup") ||
                scanSource.contains("post_send_back_followup") ||
                scanSource.contains("vlm_retry_followup") ||
                scanSource.startsWith("queued_") ||
                scanSource.contains("notification_busy")
        if (running.get() || (!bypassCoolingDown && isBusyOrCoolingDown())) {
            logger.log(TAG, "会话列表截图分析跳过：当前正忙或冷却中")
            enqueuePendingScan(scanSource, "busy_or_cooling_down")
            return
        }
        val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("runtime_enabled", false)) return
        if (prefs.getString("execution_mode", "auto").orEmpty() != "auto") return

        val lastNotifyTs = prefs.getLong("last_notify_timestamp", 0L)
        val timeSinceNotify = System.currentTimeMillis() - lastNotifyTs
        val shouldAvoidNotificationTransition = !scanSource.startsWith("queued_") &&
            !scanSource.contains("notification_busy") &&
            !scanSource.contains("explicit_runonce_busy")
        if (shouldAvoidNotificationTransition && timeSinceNotify < 6000L) {
            logger.log(TAG, "会话列表截图分析跳过：近期 (${timeSinceNotify}ms前) 有通知触发，避让通知栏自动打开会话的过程")
            return
        }

        val capture = CaptureProviderFactory.create(this)
        val ocr = FallbackOcrProvider(LocalOcrProvider(this), RemoteOcrProvider(this))
        try {
            ensureRuntimeEnabled("会话列表扫描开始")
            logger.log(TAG, "开始通过截图分析当前微信页（source=$scanSource）")
            // 注意：此处不以 MEDIA_PROJECTION 类型调用 startForeground，
            // Android 14 下重复声明该类型会触发系统停止旧的 MediaProjection（onStop 回调）。
            // prepareProjection() 已在首次初始化时声明了该类型，后续无需重复。
            startForegroundCompat("正在识别当前微信页面", includeProjectionType = true)
            val cap = capture.captureScreen("wx_list_scan")
            ensureRuntimeEnabled("会话列表截图完成后")
            val headerOcrText = recognizeHeaderWithFallbacks(ocr, cap)
            val ocrText = recognizePageWithFallbacks(ocr, cap)
            val pageAnalysis = ConversationListUnreadDetector.analyzeConversationListPage(cap.imagePath, ocrText, headerOcrText)
            logger.log(TAG, "截图分析列表页判定: ${pageAnalysis.debugSummary}")
            if (!pageAnalysis.looksLikeListPage) {
                val chatAnalysis = ConversationListUnreadDetector.analyzeChatPage(ocrText, headerOcrText)
                logger.log(TAG, "截图分析聊天页判定: ${chatAnalysis.debugSummary}")
                if (chatAnalysis.looksLikeChatPage) {
                    val prefsReply = getSharedPreferences("host_config", Context.MODE_PRIVATE)
                    val binding = resolveChatContactBinding(
                        ocr = ocr,
                        cap = cap,
                        headerOcrText = headerOcrText,
                        listRowContactHint = "",
                    )
                    val contactName = binding.contactName
                    logger.log(
                        TAG,
                        "聊天页联系人绑定: title=${binding.titleName} source=${binding.titleSource} titleScore=${binding.titleScore} header=${binding.headerName} headerScore=${binding.headerScore} contact=$contactName",
                    )
                    if (ConversationListUnreadDetector.isBlockedWechatSystemPageTitle(contactName)) {
                        logger.log(TAG, "截图分析结果：识别到微信系统页标题($contactName)，跳过本轮且不执行点击/返回")
                        return
                    }
                    if (contactName.isBlank() || contactName == "当前联系人") {
                        logger.log(TAG, "截图分析结果：当前是聊天页，但联系人识别无效，跳过本轮")
                        returnToConversationList("聊天页联系人识别无效")
                        return
                    }
                    if (isPostSendFollowup(scanSource) && !cap.hasInboundAfterLatestOutbound) {
                        logger.log(TAG, "发送后补扫未检测到我方最后消息之后的新入站，跳过本轮，避免把搜索页/搜索结果页当成聊天页")
                        returnToConversationList("发送后补扫无新入站")
                        return
                    }
                    val sessionIdForContact = SessionIdentity.buildSessionId(this@HostForegroundService, contactName)
                    val lastReplyText = getLastReplyText(prefsReply, sessionIdForContact)
                    var inboundCandidate = extractInboundCandidate(
                        ocr = ocr,
                        cap = cap,
                        contactName = contactName,
                        lastReplyText = lastReplyText,
                        pageOcrText = ocrText,
                    )
                    var voiceTranscribeImagePathForRunOnce: String? = null
                    tryTranscribeLatestVoiceIfNeeded(
                        ocr = ocr,
                        capture = capture,
                        initialCap = cap,
                        contactName = contactName,
                        lastReplyText = lastReplyText,
                        sessionId = sessionIdForContact,
                    )?.let {
                        inboundCandidate = it
                        voiceTranscribeImagePathForRunOnce = it.fullScreenshotPath
                    }
                    val latestInbound = inboundCandidate.text
                    val inboundSignature = inboundCandidate.signature
                    logger.log(
                        TAG,
                        "聊天页触发门槛检测: latestSide=${cap.latestVisibleMessageSide} hasInboundAfterLatestOutbound=${cap.hasInboundAfterLatestOutbound} source=${inboundCandidate.sourceLabel}",
                    )
                    if (shouldSkipBecauseLatestVisibleIsOutbound(cap, inboundCandidate)) {
                        val currentOutboundText = cap.latestOutboundCropPath?.let { ocr.recognize(it).trim() } ?: ""
                        val lastReplyForCompare = getLastReplyText(prefsReply, sessionIdForContact)
                        // 判断 outbound 文本是否为 AI 自动发出的回复（与上次 AI 回复高度相似时跳过上报）
                        val looksLikeAiReply = lastReplyForCompare.isNotBlank() &&
                            ConversationTextExtractor.looksLikeAgentReplyCandidate(currentOutboundText, lastReplyForCompare)
                        if (currentOutboundText.isNotEmpty() && currentOutboundText != getLastReportedOutbound(sessionIdForContact) && !looksLikeAiReply) {
                            val agentClient = HttpAgentClient(this@HostForegroundService)
                            try {
                                agentClient.chat(cap.imagePath, currentOutboundText, sessionIdForContact, contactName, isHumanReply = true)
                                setLastReportedOutbound(sessionIdForContact, currentOutboundText)
                                logger.log(TAG, "检测到真人介入并发送消息: [${currentOutboundText.take(20)}]，已同步至后台接管流程")
                            } catch (e: Exception) {
                                logger.log(TAG, "同步真人介入状态失败: ${e.message}")
                            }
                        } else if (looksLikeAiReply) {
                            setLastReportedOutbound(sessionIdForContact, currentOutboundText)
                            logger.log(TAG, "检测到 outbound 文本与 AI 上次回复高度相似，跳过真人介入上报")
                        }
                        cleanupIntermediateOcrCrops(cap, inboundCandidate.path)
                        logger.log(TAG, "截图分析结果：当前聊天页最新可见消息疑似己方发送，且未检测到己方之后新入站，跳过本轮")
                        returnToConversationList("聊天页最新消息非客户新消息")
                        return
                    }
                    inboundCandidate.path?.let {
                        exportFinalInboundOcrSnapshot(it, sessionIdForContact)
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
                    if (looksLikeSearchContactEcho(latestInbound, contactName)) {
                        logger.log(TAG, "截图分析结果：最新候选仅像搜索框/联系人名回显($latestInbound)，跳过本轮")
                        returnToConversationList("聊天页候选疑似搜索框联系人名")
                        return
                    }
                    if (ConversationTextExtractor.looksLikeAgentReplyCandidate(latestInbound, lastReplyText)) {
                        logger.log(TAG, "截图分析结果：当前提取文本疑似我方上一条回复或客服话术，跳过本轮")
                        returnToConversationList("聊天页提取文本疑似我方回复")
                        return
                    }
                    val lastInboundSignature = getLastInboundSignature(prefsReply, sessionIdForContact)
                    if (inboundSignature.isNotBlank() && inboundSignature == lastInboundSignature) {
                        logger.log(TAG, "截图分析结果：当前是聊天页，但客户最新消息未变化，跳过本轮")
                        returnToConversationList("聊天页客户最新消息未变化")
                        return
                    }
                    logger.log(TAG, "截图分析结果：当前是聊天页，source=$scanSource，检测到客户新消息，直接复用本轮截图进入回复流程，联系人=$contactName")
                    ensureRuntimeEnabled("聊天页复用截图进入回复前")
                    runOnce(
                        sessionId = sessionIdForContact,
                        contactName = contactName,
                        preCaptured = cap,
                        preOcrText = ocrText,
                        preLatestInbound = latestInbound,
                        preInboundSignature = inboundSignature,
                        preVoiceTranscribeImagePath = voiceTranscribeImagePathForRunOnce,
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
                logger.log(TAG, "截图分析结果：已识别为会话列表页，但未识别到可点击的未读红点，返回系统主屏")
                ensureRuntimeEnabled("列表页无未读返回主屏前")
                com.agentime.ime.host.automation.WechatAccessibilityService.goHome()
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
            ensureRuntimeEnabled("点击未读会话前")
            if (!com.agentime.ime.host.automation.WechatAccessibilityService.tapConversationAt(hit.tapX, hit.tapY)) {
                logger.log(TAG, "截图分析点击未读会话失败")
                return
            }
            Thread.sleep(1400)
            ensureRuntimeEnabled("点击未读会话等待后")
            val postClickCap = capture.captureScreen("wx_chat_after_list_click")
            ensureRuntimeEnabled("点击后截图完成后")
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
            val binding = resolveChatContactBinding(
                ocr = ocr,
                cap = postClickCap,
                headerOcrText = postClickHeaderOcr,
                listRowContactHint = listRowContactHint,
            )
            val contactName = binding.contactName
            logger.log(
                TAG,
                "点击入会话联系人绑定: listHint=$listHintStrict title=${binding.titleName} source=${binding.titleSource} titleScore=${binding.titleScore} header=$headerResolvedName headerScore=$headerScore contact=$contactName",
            )
            if (ConversationListUnreadDetector.isBlockedWechatSystemPageTitle(contactName)) {
                logger.log(TAG, "点击未读会话后识别到微信系统页标题($contactName)，取消本轮且不执行后续点击")
                return
            }
            if (contactName.isBlank() || contactName == "当前联系人") {
                logger.log(TAG, "点击未读会话后，联系人绑定置信度不足，取消本轮（避免串会话）")
                returnToConversationList("点击后联系人绑定置信度不足")
                return
            }
            if (listRowContactHint.isNotBlank() &&
                listHintStrict.isNotBlank() &&
                binding.titleName.isNotBlank() &&
                binding.titleName != listHintStrict
            ) {
                logger.log(
                    TAG,
                    "点击入会话联系人校验不一致：listHint=$listRowContactHint title=${binding.titleName}；已采用聊天页可信标题作为会话主键",
                )
            }
            logger.log(TAG, "截图分析进入会话成功，联系人=$contactName")
            val prefsReply = getSharedPreferences("host_config", Context.MODE_PRIVATE)
            val sessionIdForContact = SessionIdentity.buildSessionId(this@HostForegroundService, contactName)
            val lastReplyText = getLastReplyText(prefsReply, sessionIdForContact)
            var inboundCandidate = extractInboundCandidate(
                ocr = ocr,
                cap = postClickCap,
                contactName = contactName,
                lastReplyText = lastReplyText,
                pageOcrText = postClickOcr,
            )
            var voiceTranscribeImagePathForRunOnce: String? = null
            tryTranscribeLatestVoiceIfNeeded(
                ocr = ocr,
                capture = capture,
                initialCap = postClickCap,
                contactName = contactName,
                lastReplyText = lastReplyText,
                sessionId = sessionIdForContact,
            )?.let {
                inboundCandidate = it
                voiceTranscribeImagePathForRunOnce = it.fullScreenshotPath
            }
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
                if (currentOutboundText.isNotEmpty() && currentOutboundText != getLastReportedOutbound(sessionIdForContact) && !looksLikeAiReply) {
                    val agentClient = HttpAgentClient(this@HostForegroundService)
                    try {
                        agentClient.chat(postClickCap.imagePath, currentOutboundText, sessionIdForContact, contactName, isHumanReply = true)
                        setLastReportedOutbound(sessionIdForContact, currentOutboundText)
                        logger.log(TAG, "检测到真人介入并发送消息: [${currentOutboundText.take(20)}]，已同步至后台接管流程 (VLM)")
                    } catch (e: Exception) {
                        logger.log(TAG, "同步真人介入状态失败: ${e.message}")
                    }
                } else if (looksLikeAiReply) {
                    setLastReportedOutbound(sessionIdForContact, currentOutboundText)
                    logger.log(TAG, "检测到 outbound 文本与 AI 上次回复高度相似，跳过真人介入上报")
                }
                cleanupIntermediateOcrCrops(postClickCap, inboundCandidate.path)
                logger.log(TAG, "点击进入聊天后，最新可见消息疑似己方发送，且未检测到己方之后新入站，取消本轮")
                returnToConversationList("点击进入后最新消息非客户新消息")
                return
            }
            inboundCandidate.path?.let {
                exportFinalInboundOcrSnapshot(it, sessionIdForContact)
                val pngFileName = File(it).name
                val pngBytes = File(it).readBytes()
                com.agentime.ime.host.capture.CaptureImageProcessor.exportPublicCopy(this@HostForegroundService, pngFileName, pngBytes)
            }
            cleanupIntermediateOcrCrops(postClickCap, inboundCandidate.path)
            logger.log(TAG, "点击进入聊天后最新客户消息预判: ${latestInbound.take(120)}")
            if (latestInbound.isBlank()) {
                logger.log(TAG, "点击进入聊天后，未提取到有效文本，但由于是红点触发，将强行交由大模型看图处理")
            }
            if (ConversationTextExtractor.looksLikeVoiceTranscriptionUiOnly(latestInbound)) {
                logger.log(TAG, "点击进入聊天后，提取文本疑似语音消息 UI，取消普通文字回复: ${latestInbound.take(80)}")
                returnToConversationList("点击进入后最新消息疑似语音")
                return
            }
            if (ConversationTextExtractor.looksLikeAgentReplyCandidate(latestInbound, lastReplyText)) {
                logger.log(TAG, "点击进入聊天后，提取文本疑似我方上一条回复或客服话术，取消本轮")
                returnToConversationList("点击进入后提取文本疑似我方回复")
                return
            }
            val lastInboundSignature = getLastInboundSignature(prefsReply, sessionIdForContact)
            if (inboundSignature.isNotBlank() && inboundSignature == lastInboundSignature) {
                logger.log(TAG, "点击进入聊天后，客户最新消息未变化，取消本轮")
                returnToConversationList("点击进入后客户最新消息未变化")
                return
            }
            ensureRuntimeEnabled("点击入会话进入回复前")
            runOnce(
                sessionId = sessionIdForContact,
                contactName = contactName,
                preCaptured = postClickCap,
                preOcrText = postClickOcr,
                preLatestInbound = latestInbound,
                preInboundSignature = inboundSignature,
                isExplicitTrigger = true,
                preVoiceTranscribeImagePath = voiceTranscribeImagePathForRunOnce,
            )
        } catch (e: CancellationException) {
            moveState(HostState.FAILED, e.message ?: "运行已停止")
            logger.log(TAG, "会话列表截图分析已取消：运行已停止")
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
        isExplicitTrigger: Boolean = false,
        // 语音转文字成功后的全屏截图路径（从 scanConversationListAndRun 传入）
        preVoiceTranscribeImagePath: String? = null,
    ) {
        if (!isRuntimeEnabled()) {
            logger.log(TAG, "运行已停止，忽略 runOnce: $sessionId/$contactName")
            return
        }
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
            ensureRuntimeEnabled("runOnce 开始")
            moveState(HostState.IDLE, "开始任务: $sessionId/$contactName")
            if (ConversationListUnreadDetector.isBlockedWechatSystemPageTitle(contactName)) {
                logger.log(TAG, "runOnce 拒绝处理微信系统页标题($contactName)，停止本轮自动回复")
                return
            }
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
                    ensureRuntimeEnabled("启动微信前")
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
                ensureRuntimeEnabled("微信前台就绪后")
                moveState(HostState.WECHAT_READY, "微信前台就绪")
                logger.log(TAG, "保持聊天页静止，等待界面稳定后截图")
                Thread.sleep(1800)
                ensureRuntimeEnabled("界面稳定等待后")
            }
            if (!ime.isImeActive()) logger.log(TAG, "警告：当前默认输入法不是 Agent IME")
            val runtimeStartedAt = prefs.getLong("runtime_started_at", 0L)
            val sinceRuntimeStart = System.currentTimeMillis() - runtimeStartedAt
            if (runtimeStartedAt > 0L && sinceRuntimeStart in 0..2500L) {
                val waitMs = 2500L - sinceRuntimeStart
                logger.log(TAG, "运行刚启动，等待截图管线稳定 ${waitMs}ms")
                Thread.sleep(waitMs)
                ensureRuntimeEnabled("截图管线稳定等待后")
            }
            val cap: com.agentime.ime.host.capture.CaptureResult
            val ocrText: String
            val latestInbound: String
            val inboundSignature: String
            // 语音转文字成功后的全屏截图路径（用于替代初始截图发给 VLM）
            var voiceTranscribeImagePath: String? = null
            if (preCaptured != null && !preOcrText.isNullOrBlank() && !preLatestInbound.isNullOrBlank()) {
                cap = preCaptured
                ocrText = preOcrText
                latestInbound = preLatestInbound
                inboundSignature = preInboundSignature.orEmpty()
                // 复用上层传入的语音转文字截图路径
                voiceTranscribeImagePath = preVoiceTranscribeImagePath
                startForegroundCompat("正在分析微信聊天", includeProjectionType = true)
                logger.log(TAG, "复用页面分析阶段已成功获取的截图与 OCR，跳过二次截图 voiceTranscribeImage=${preVoiceTranscribeImagePath != null}")
                moveState(HostState.SCREEN_CAPTURED, "复用截图: ${cap.imagePath}")
            } else {
                startForegroundCompat("正在分析微信聊天", includeProjectionType = true)
                logger.log(TAG, "开始截图，executionMode=$executionMode provider=$captureProvider")
                cap = capture.captureScreen(sessionId)
                ensureRuntimeEnabled("截图完成后")
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
                val chatAnalysis = ConversationListUnreadDetector.analyzeChatPage(ocrText, headerOcrText)
                if (!chatAnalysis.looksLikeChatPage) {
                    val listAnalysis = ConversationListUnreadDetector.analyzeConversationListPage(cap.imagePath, ocrText, headerOcrText)
                    if (listAnalysis.looksLikeListPage) {
                        logger.log(TAG, "runOnce 识别到当前处于会话列表页，而非聊天页，中止执行 (可能是过期任务)")
                        error("当前已返回列表页，已取消过期的回复任务")
                    } else {
                        logger.log(TAG, "runOnce 识别到当前非聊天页，中止执行: ${chatAnalysis.debugSummary}")
                        error("当前页面非聊天页，已取消回复任务")
                    }
                }

                val binding = resolveChatContactBinding(
                    ocr = ocr,
                    cap = cap,
                    headerOcrText = headerOcrText,
                    listRowContactHint = "",
                )
                val resolvedContactName = binding.contactName
                if (resolvedContactName.isNotBlank() && resolvedContactName != "当前联系人") {
                    logger.log(TAG, "当前聊天页绑定联系人: $resolvedContactName")
                    if (!outboundNamesMatch(resolvedContactName, contactName)) {
                        logger.log(TAG, "runOnce 识别到当前聊天页联系人($resolvedContactName)与目标($contactName)不匹配，中止执行，防止串会话")
                        error("聊天页联系人不匹配，已取消回复防止串会话")
                    }
                }
                var inboundCandidate = extractInboundCandidate(
                    ocr = ocr,
                    cap = cap,
                    contactName = resolvedContactName.ifBlank { contactName },
                    lastReplyText = getLastReplyText(prefs, sessionId),
                    pageOcrText = ocrText,
                )
                tryTranscribeLatestVoiceIfNeeded(
                    ocr = ocr,
                    capture = capture,
                    initialCap = cap,
                    contactName = resolvedContactName.ifBlank { contactName },
                    lastReplyText = getLastReplyText(prefs, sessionId),
                    sessionId = sessionId,
                )?.let {
                    inboundCandidate = it
                    // 记录语音转文字后的全屏截图路径，后续发给 VLM 时使用
                    voiceTranscribeImagePath = it.fullScreenshotPath
                    logger.log(TAG, "语音转文字成功，将使用转文字后截图发送给 VLM: ${it.fullScreenshotPath}")
                }
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
            if (latestInbound.isBlank()) {
                if (isExplicitTrigger) {
                    logger.log(TAG, "虽然未能从 OCR 提取出文本，但由于是明确触发的新消息(通知或红点)，将交由大模型看图兜底处理")
                } else {
                    error("未能从 OCR 中提取出最新客户消息")
                }
            }
            if (looksLikeSearchContactEcho(latestInbound, contactName)) {
                logger.log(TAG, "提炼结果疑似搜索框/联系人名回显，取消本轮自动回复: ${latestInbound.take(80)}")
                returnToConversationList("最新候选疑似搜索框联系人名")
                return
            }
            if (ConversationTextExtractor.looksLikeVoiceTranscriptionUiOnly(latestInbound)) {
                logger.log(TAG, "提炼结果疑似语音消息 UI（如扬声器残影+秒数），取消本轮普通文字回复: ${latestInbound.take(80)}")
                returnToConversationList("最新消息疑似语音，等待转文字处理")
                return
            }

            val lastInboundSignature = getLastInboundSignature(prefs, sessionId)
            if (inboundSignature.isNotBlank() && inboundSignature == lastInboundSignature) {
                logger.log(TAG, "检测到最近客户消息未变化，跳过本轮回复")
                moveState(HostState.SENT, "最近客户消息未变化，已跳过")
                returnToConversationList("最近客户消息未变化")
                return
            }

            // 优先使用语音转文字后的全屏截图（此时截图上已展开语音文字内容）
            val imageForVlm = voiceTranscribeImagePath ?: cap.imagePath
            logger.log(TAG, "触发后端 API 调用，使用图片进行 VLM OCR 提取: $imageForVlm (语音转文字截图=${voiceTranscribeImagePath != null}, 本地候选=${latestInbound.take(80)})")
            val reply = agentClient.chat(imageForVlm, latestInbound, sessionId, contactName)
            ensureRuntimeEnabled("后端回复返回后")
            moveState(HostState.REPLY_READY, "reply_text 长度=${reply.replyText.length}")

            if (reply.isGroupChat) {
                logger.log(TAG, "检测到当前为群聊，Agent 不处理并自动返回主屏(列表页)")
                moveState(HostState.SENT, "识别为群聊，已跳过")
                returnToConversationList("检测为群聊")
                return
            }

            if (reply.replyText.isBlank()) {
                if (reply.silenced) {
                    logger.log(TAG, "后端会话处于静音状态，跳过本轮注入: status=${reply.currentStatus} reason=${reply.reason}")
                    moveState(HostState.SENT, "后端会话静音，已跳过: ${reply.currentStatus.ifBlank { reply.reason }}")
                    if (reply.retryable && shouldScheduleVlmRetry(inboundSignature)) {
                        scheduleVlmRetryFollowupScan(reply.retryAfterMs)
                        return
                    }
                    returnToConversationList("后端会话静音或人工接管")
                    return
                }
                logger.log(TAG, "Agent 返回空 reply_text，raw=${reply.raw.take(500)}")
                error("reply_text 为空")
            }
            if (executionMode == "manual") {
                moveState(HostState.INPUT_FOCUSED, "手动模式：请先聚焦输入框，再继续注入")
            } else {
                ensureRuntimeEnabled("聚焦输入框前")
                if (!automation.focusInputArea()) error("聚焦输入框失败")
                moveState(HostState.INPUT_FOCUSED, "输入框聚焦完成")
                logger.log(TAG, "输入框已聚焦，等待输入法稳定后注入")
                Thread.sleep(450)
                ensureRuntimeEnabled("输入法稳定等待后")
            }

            ensureRuntimeEnabled("注入文本前")
            if (!ime.injectText(reply.replyText)) error("注入文本失败")
            moveState(HostState.TEXT_INJECTED, "文本注入完成")

            if (executionMode == "manual") {
                moveState(HostState.SENT, "手动模式：已注入文本，请人工点击发送")
            } else {
                // 微信需时间刷新发送按钮位置；立即点容易点到旧区域
                Thread.sleep(600)
                ensureRuntimeEnabled("点击发送前")
                val sendOk = automation.clickSend()
                logger.log(TAG, "clickSend 返回=$sendOk（若未发出，请在 host_config 调整 send_x/send_y）")
                if (!sendOk) error("点击发送失败（坐标可能不匹配当前机型，请调整 send_x/send_y）")
                saveConversationDedupState(prefs, sessionId, inboundSignature, reply.replyText)
                // 将 AI 自动发出的回复按会话记录，
                // 防止后续截屏检测时将 AI 回复误判为真人手动介入。
                setLastReportedOutbound(sessionId, reply.replyText)
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
                    ensureRuntimeEnabled("发送后返回列表前")
                    val backOk = automation.clickBack()
                    logger.log(TAG, "已触发自动返回动作，执行结果=$backOk")
                    if (backOk) {
                        schedulePostSendFollowupScan("post_send_back")
                    }
                }
            }
        } catch (e: CancellationException) {
            moveState(HostState.FAILED, e.message ?: "运行已停止")
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

    private fun resolveChatContactBinding(
        ocr: FallbackOcrProvider,
        cap: com.agentime.ime.host.capture.CaptureResult,
        headerOcrText: String,
        listRowContactHint: String,
    ): ContactBindingResult {
        val titleOcrText = recognizeTitleOnly(ocr, cap)
        val titleRawName = ConversationListUnreadDetector.extractChatContactNameFromOcr(titleOcrText)
        val titleScore = ConversationListUnreadDetector.scoreHeaderOcrCandidate(titleOcrText)
        val titleName = normalizeCounterpartyName(titleRawName, useFuzzyMatch = true)
        val trustedTitleName = when {
            titleScore >= 80 && titleName.isNotBlank() -> titleName
            else -> ""
        }
        val trustedTitleSource = when {
            titleScore >= 80 && titleName.isNotBlank() -> "title_ocr"
            else -> "none"
        }

        val listHintName = normalizeCounterpartyName(listRowContactHint, useFuzzyMatch = false)
        val headerRawName = ConversationListUnreadDetector.extractChatContactNameFromOcr(headerOcrText)
        val headerScore = ConversationListUnreadDetector.scoreHeaderOcrCandidate(headerOcrText)
        val headerName = normalizeCounterpartyName(headerRawName, useFuzzyMatch = true)
        val contactName = when {
            trustedTitleName.isNotBlank() -> trustedTitleName
            listHintName.isNotBlank() -> listHintName
            headerScore >= 120 && headerName.isNotBlank() -> headerName
            else -> "当前联系人"
        }

        return ContactBindingResult(
            contactName = contactName,
            titleSource = trustedTitleSource,
            titleName = trustedTitleName,
            titleScore = titleScore,
            headerName = headerName,
            headerScore = headerScore,
        )
    }

    private fun recognizeTitleOnly(
        ocr: FallbackOcrProvider,
        cap: com.agentime.ime.host.capture.CaptureResult,
    ): String {
        val path = cap.titleCropPath ?: return ""
        return ocr.recognize(path).trim().also { text ->
            if (text.isNotBlank()) {
                logger.log(
                    TAG,
                    "标题栏裁剪 OCR 长度=${text.length}, 候选分数=${ConversationListUnreadDetector.scoreHeaderOcrCandidate(text)}",
                )
            }
        }
    }

    private fun normalizeCounterpartyName(raw: String?, useFuzzyMatch: Boolean): String {
        val normalized = SessionIdentity.normalizeContactName(
            this@HostForegroundService,
            raw,
            useFuzzyMatch = useFuzzyMatch,
        )
        return normalized.takeIf { it.isNotBlank() && it != "当前联系人" }.orEmpty()
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
        if (cap.latestInboundVoiceRedDot) {
            logger.log(
                TAG,
                "检测到最新左侧消息语音红点: x=${cap.latestInboundVoiceRedDotX?.toInt()} y=${cap.latestInboundVoiceRedDotY?.toInt()} score=${cap.latestInboundVoiceRedDotScore}，跳过普通文字回复",
            )
            return InboundCandidate(
                sourceLabel = "最新左侧语音红点",
                path = cap.latestInboundBubbleCropPath ?: cap.imagePath,
            )
        }
        if (cap.latestVisibleMessageSide == "inbound" && !cap.latestInboundBubbleCropPath.isNullOrBlank()) {
            val bubbleOcr = ocr.recognize(cap.latestInboundBubbleCropPath).trim()
            if (bubbleOcr.isBlank()) {
                logger.log(TAG, "候选源 最新左侧气泡(强约束): OCR 为空，禁止回退整屏历史 OCR")
                return InboundCandidate(
                    sourceLabel = "最新左侧气泡(强约束)",
                    path = cap.latestInboundBubbleCropPath,
                )
            }

            val res = ConversationTextExtractor.extractLatestInboundMessage(
                ocrText = bubbleOcr,
                contactName = contactName,
                lastReplyText = lastReplyText,
            )
            logger.log(TAG, "候选源 最新左侧气泡(强约束): rawLen=${bubbleOcr.length} extracted=${res.text.take(80)}")
            if (res.text.isBlank()) {
                if (ConversationTextExtractor.looksLikeVoiceTranscriptionUiOnly(bubbleOcr)) {
                    logger.log(TAG, "候选源 最新左侧气泡(强约束): 疑似语音消息 UI，禁止回退整屏历史 OCR")
                } else {
                    logger.log(TAG, "候选源 最新左侧气泡(强约束): 未提取到有效文本，禁止回退整屏历史 OCR")
                }
                return InboundCandidate(
                    sourceLabel = "最新左侧气泡(强约束)",
                    path = cap.latestInboundBubbleCropPath,
                )
            }

            return InboundCandidate(
                text = res.text,
                signature = res.signature,
                sourceLabel = "最新左侧气泡(强约束)",
                path = cap.latestInboundBubbleCropPath,
                score = scoreInboundCandidate(res.text),
            )
        }
        // VLM 引入后，仅保留 latestInboundBubble 的本地 OCR（用于去重签名），
        // sinceLastOutbound 和 recentInboundCluster 的 OCR 已由 VLM 替代。
        val candidates = buildList {
            cap.latestInboundBubbleCropPath?.let { add("最新左侧气泡" to it) }
        }
        val tierBuckets = linkedMapOf<Int, InboundCandidate>()

        if (cap.latestVisibleMessageSide == "inbound") {
            val accessibilityText = WechatAccessibilityService.getLatestVisibleInboundText().orEmpty()
            if (accessibilityText.isNotBlank()) {
                val res = ConversationTextExtractor.extractLatestInboundMessage(
                    ocrText = accessibilityText,
                    contactName = contactName,
                    lastReplyText = lastReplyText,
                )
                logger.log(TAG, "候选源 无障碍最新入站文本: raw=${accessibilityText.take(80)} extracted=${res.text.take(80)}")
                if (res.text.isNotBlank()) {
                    tierBuckets[-1] = InboundCandidate(
                        text = res.text,
                        signature = res.signature,
                        sourceLabel = "无障碍最新入站文本",
                        path = cap.imagePath,
                        score = scoreInboundCandidate(res.text),
                    )
                }
            }
        }

        if (!pageOcrText.isNullOrBlank()) {
            val res = ConversationTextExtractor.extractLatestInboundMessage(
                ocrText = pageOcrText,
                contactName = contactName,
                lastReplyText = lastReplyText,
            )
            logger.log(TAG, "候选源 页面OCR预提取: rawLen=${pageOcrText.length} extracted=${res.text.take(80)}")
            if (res.text.isBlank() && ConversationTextExtractor.looksLikeVoiceTranscriptionUiOnly(pageOcrText)) {
                logger.log(TAG, "候选源 页面OCR预提取: 疑似语音消息 UI，仅包含时长/转文字按钮，跳过直接回复")
            }
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
            if (ocrRes.isBlank()) {
                logger.log(TAG, "候选源 $label: OCR 为空")
                continue
            }
            val res = ConversationTextExtractor.extractLatestInboundMessage(
                ocrText = ocrRes,
                contactName = contactName,
                lastReplyText = lastReplyText,
            )
            logger.log(TAG, "候选源 $label: rawLen=${ocrRes.length} extracted=${res.text.take(80)}")
            if (res.text.isBlank() && ConversationTextExtractor.looksLikeVoiceTranscriptionUiOnly(ocrRes)) {
                logger.log(TAG, "候选源 $label: 疑似语音消息 UI，仅包含时长/转文字按钮，跳过直接回复")
            }
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

    private fun tryTranscribeLatestVoiceIfNeeded(
        ocr: FallbackOcrProvider,
        capture: com.agentime.ime.host.capture.CaptureController,
        initialCap: com.agentime.ime.host.capture.CaptureResult,
        contactName: String,
        lastReplyText: String,
        sessionId: String,
    ): InboundCandidate? {
        if (ConversationListUnreadDetector.isBlockedWechatSystemPageTitle(contactName)) {
            logger.log(TAG, "语音转文字跳过：联系人标题为微信系统页($contactName)")
            return null
        }
        if (!initialCap.latestInboundVoiceRedDot) return null
        val redX = initialCap.latestInboundVoiceRedDotX ?: return null
        val redY = initialCap.latestInboundVoiceRedDotY ?: return null
        val size = readImageSize(initialCap.imagePath)
        val width = size?.first ?: 1080
        val height = size?.second ?: 2400

        // ──────────────────────────────────────────────
        // 阶段 1：只有图像上真的存在快捷"转文字"按钮时才点击。
        // ──────────────────────────────────────────────
        val shortcutButton = findShortcutTranscribeButtonCenter(initialCap.imagePath, redX, redY, width, height)
        if (shortcutButton != null) {
            val clickX = shortcutButton.first
            val clickY = shortcutButton.second
            logger.log(
                TAG,
                "检测到语音红点与快捷转文字按钮，点击快捷转文字: red=(${redX.toInt()},${redY.toInt()}) tap=(${clickX.toInt()},${clickY.toInt()}) score=${initialCap.latestInboundVoiceRedDotScore}",
            )
            val tapOk = WechatAccessibilityService.tapConversationAt(clickX, clickY)
            logger.log(TAG, "点击快捷转文字结果=$tapOk")

            if (tapOk) {
                Thread.sleep(1200)
                // 快捷按钮只等 3 轮；如果按钮点击未生效，后续通过长按兜底。
                for (attempt in 1..VOICE_SHORTCUT_MAX_WAIT_ROUNDS) {
                    val cap = capture.captureScreen("${sessionId}_voice_transcribe_$attempt")
                    val candidate = extractVoiceTranscriptionFromAnchor(
                        ocr = ocr,
                        cap = cap,
                        anchorY = redY,
                        contactName = contactName,
                        lastReplyText = lastReplyText,
                        label = "语音转文字等待#$attempt",
                    )
                    if (isUsableVoiceTranscriptionText(candidate.text)) {
                        logger.log(TAG, "语音快捷转文字已提取有效文本: ${candidate.text.take(120)}")
                        // 返回时携带转文字后的全屏截图路径，供后续 VLM 使用
                        return candidate.copy(fullScreenshotPath = cap.imagePath)
                    }
                    if (candidate.text.isNotBlank()) {
                        logger.log(TAG, "语音转文字等待#$attempt 候选疑似 OCR 噪声，继续等待/准备长按兜底: ${candidate.text.take(80)}")
                    }
                    logger.log(
                        TAG,
                        "语音转文字等待#$attempt 暂未得到有效文本 redDot=${cap.latestInboundVoiceRedDot} side=${cap.latestVisibleMessageSide}",
                    )
                    Thread.sleep(700)
                }
            } else {
                logger.log(TAG, "快捷转文字按钮点击手势未完成，直接回退长按语音气泡")
            }
        } else {
            logger.log(
                TAG,
                "红点右侧未检测到快捷转文字按钮，跳过快捷点击，直接长按语音气泡: red=(${redX.toInt()},${redY.toInt()}) score=${initialCap.latestInboundVoiceRedDotScore}",
            )
        }

        // ──────────────────────────────────────────────
        // 阶段 2：快捷按钮未生效，回退为长按语音气泡触发弹窗菜单
        // ──────────────────────────────────────────────
        logger.log(TAG, "快捷转文字未生效，回退到长按语音气泡触发弹窗菜单")
        val longPressResult = tryTranscribeViaLongPressMenu(
            ocr = ocr,
            capture = capture,
            redX = redX,
            redY = redY,
            width = width,
            height = height,
            contactName = contactName,
            lastReplyText = lastReplyText,
            sessionId = sessionId,
        )
        if (longPressResult != null) return longPressResult

        logger.log(TAG, "语音转文字等待超时（快捷+长按均失败），保持跳过普通文字回复")
        return null
    }

    private fun findShortcutTranscribeButtonCenter(
        imagePath: String,
        redX: Float,
        redY: Float,
        fullWidth: Int,
        fullHeight: Int,
    ): Pair<Float, Float>? {
        return runCatching {
            val bitmap = BitmapFactory.decodeFile(imagePath) ?: return null
            try {
                val width = bitmap.width
                val height = bitmap.height
                if (width < 200 || height < 400) return null

                val roiLeft = (redX + fullWidth * 0.025f).toInt().coerceIn(0, width - 1)
                val roiRight = (redX + fullWidth * 0.28f).toInt().coerceIn(roiLeft + 1, width)
                val roiTop = (redY - fullHeight * 0.045f).toInt().coerceIn(0, height - 1)
                val roiBottom = (redY + fullHeight * 0.045f).toInt().coerceIn(roiTop + 1, height)
                val step = 2

                fun rgb(pixel: Int): Triple<Int, Int, Int> {
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    return Triple(r, g, b)
                }

                fun isShortcutFill(pixel: Int): Boolean {
                    val (r, g, b) = rgb(pixel)
                    val max = maxOf(r, g, b)
                    val min = minOf(r, g, b)
                    val brightness = (r + g + b) / 3
                    return max - min <= 12 && brightness in 205..234
                }

                fun isShortcutText(pixel: Int): Boolean {
                    val (r, g, b) = rgb(pixel)
                    val max = maxOf(r, g, b)
                    val min = minOf(r, g, b)
                    val brightness = (r + g + b) / 3
                    return max - min <= 35 && brightness in 75..185
                }

                var left = width
                var right = -1
                var top = height
                var bottom = -1
                var fillCount = 0
                var textCount = 0

                var y = roiTop
                while (y < roiBottom) {
                    var x = roiLeft
                    while (x < roiRight) {
                        val pixel = bitmap.getPixel(x, y)
                        val fill = isShortcutFill(pixel)
                        val text = isShortcutText(pixel)
                        if (fill || text) {
                            left = minOf(left, x)
                            right = maxOf(right, x)
                            top = minOf(top, y)
                            bottom = maxOf(bottom, y)
                            if (fill) fillCount++
                            if (text) textCount++
                        }
                        x += step
                    }
                    y += step
                }

                if (right < left || bottom < top) {
                    logger.log(TAG, "快捷转文字按钮检测: roi=($roiLeft,$roiTop,$roiRight,$roiBottom) fill=$fillCount text=$textCount result=none")
                    return null
                }

                val candidateWidth = right - left + 1
                val candidateHeight = bottom - top + 1
                val enoughPixels = fillCount >= 80 || textCount >= 10
                val plausibleSize =
                    candidateWidth in (fullWidth * 0.045f).toInt()..(fullWidth * 0.24f).toInt() &&
                        candidateHeight in (fullHeight * 0.006f).toInt()..(fullHeight * 0.075f).toInt()

                if (!enoughPixels || !plausibleSize) {
                    logger.log(
                        TAG,
                        "快捷转文字按钮检测: roi=($roiLeft,$roiTop,$roiRight,$roiBottom) bbox=($left,$top,$right,$bottom) fill=$fillCount text=$textCount size=${candidateWidth}x$candidateHeight result=none",
                    )
                    return null
                }

                val centerX = ((left + right) / 2f).coerceIn(0f, (width - 1).toFloat())
                val centerY = ((top + bottom) / 2f).coerceIn(0f, (height - 1).toFloat())
                logger.log(
                    TAG,
                    "快捷转文字按钮检测: bbox=($left,$top,$right,$bottom) fill=$fillCount text=$textCount tap=(${centerX.toInt()},${centerY.toInt()})",
                )
                Pair(centerX, centerY)
            } finally {
                bitmap.recycle()
            }
        }.getOrElse { e ->
            logger.log(TAG, "快捷转文字按钮检测失败: ${e.message}")
            null
        }
    }

    /**
     * 通过长按语音气泡 → 弹窗菜单 → 点击"转文字"来转写语音消息。
     *
     * 微信弹窗菜单的布局参考（自截图观察）：
     * - 弹窗出现在语音气泡上方，居中偏左
     * - 菜单第二行左侧第一个选项通常就是"转文字"
     * - "转文字"位于弹窗底部偏左区域
     */
    private fun tryTranscribeViaLongPressMenu(
        ocr: FallbackOcrProvider,
        capture: com.agentime.ime.host.capture.CaptureController,
        redX: Float,
        redY: Float,
        width: Int,
        height: Int,
        contactName: String,
        lastReplyText: String,
        sessionId: String,
    ): InboundCandidate? {
        // 长按位置：语音气泡的中心区域。红点通常位于气泡右侧外缘，向左退约 13% 屏宽更稳。
        val longPressX = (redX - width * 0.13f).coerceIn(width * 0.10f, (width - 1).toFloat())
        val longPressY = redY.coerceIn(0f, (height - 1).toFloat())

        logger.log(TAG, "长按语音气泡: position=(${longPressX.toInt()},${longPressY.toInt()})")
        val longPressOk = WechatAccessibilityService.longPressAt(longPressX, longPressY)
        logger.log(TAG, "长按语音气泡结果=$longPressOk")
        if (!longPressOk) return null

        // 等待弹窗出现
        Thread.sleep(800)

        // 截图分析弹窗，用 OCR 定位"转文字"按钮
        val menuCap = capture.captureScreen("${sessionId}_voice_longpress_menu")
        val menuTranscribePos = findTranscribeButtonInPopupMenu(
            ocr = ocr,
            imagePath = menuCap.imagePath,
            width = width,
            height = height,
            anchorY = longPressY,
        )

        if (menuTranscribePos != null) {
            logger.log(
                TAG,
                "在弹窗中定位到\"转文字\"按钮: tap=(${menuTranscribePos.first.toInt()},${menuTranscribePos.second.toInt()})",
            )
            val menuTapOk = WechatAccessibilityService.tapConversationAt(menuTranscribePos.first, menuTranscribePos.second)
            logger.log(TAG, "点击弹窗\"转文字\"结果=$menuTapOk")
            if (!menuTapOk) return null
        } else {
            // OCR/图像定位都失败时，按微信长按菜单的常见布局兜底：第二行第一项。
            val fallbackX = (width * 0.11f).coerceIn(0f, (width - 1).toFloat())
            val fallbackY = (longPressY - height * 0.085f).coerceIn(0f, (height - 1).toFloat())
            logger.log(
                TAG,
                "OCR 未识别弹窗\"转文字\"，使用弹窗相对位置兜底点击: tap=(${fallbackX.toInt()},${fallbackY.toInt()})",
            )
            val fallbackTapOk = WechatAccessibilityService.tapConversationAt(fallbackX, fallbackY)
            logger.log(TAG, "兜底点击弹窗\"转文字\"结果=$fallbackTapOk")
            if (!fallbackTapOk) return null
        }

        // 等待"转文字"展开
        Thread.sleep(1500)

        // 轮询等待转文字结果
        for (attempt in 1..VOICE_LONGPRESS_MAX_WAIT_ROUNDS) {
            val cap = capture.captureScreen("${sessionId}_voice_longpress_transcribe_$attempt")
            val candidate = extractVoiceTranscriptionFromAnchor(
                ocr = ocr,
                cap = cap,
                anchorY = redY,
                contactName = contactName,
                lastReplyText = lastReplyText,
                label = "长按转文字等待#$attempt",
            )
            if (isUsableVoiceTranscriptionText(candidate.text)) {
                logger.log(TAG, "长按转文字已提取有效文本: ${candidate.text.take(120)}")
                return candidate.copy(fullScreenshotPath = cap.imagePath)
            }
            if (candidate.text.isNotBlank()) {
                logger.log(TAG, "长按转文字等待#$attempt 候选疑似 OCR 噪声，继续等待: ${candidate.text.take(80)}")
            }
            logger.log(
                TAG,
                "长按转文字等待#$attempt 暂未得到有效文本 redDot=${cap.latestInboundVoiceRedDot} side=${cap.latestVisibleMessageSide}",
            )
            Thread.sleep(700)
        }
        return null
    }

    /**
     * 在长按弹窗截图中用 OCR 定位"转文字"按钮。
     * 返回 (x, y) 坐标，若未找到返回 null。
     */
    private fun findTranscribeButtonInPopupMenu(
        ocr: FallbackOcrProvider,
        imagePath: String,
        width: Int,
        height: Int,
        anchorY: Float,
    ): Pair<Float, Float>? {
        return runCatching {
            val bitmap = BitmapFactory.decodeFile(imagePath) ?: return null
            try {
                // 裁剪弹窗可能出现的区域：语音气泡上方附近
                val cropTop = (anchorY - height * 0.20f).toInt().coerceAtLeast(0)
                val cropBottom = (anchorY + height * 0.02f).toInt().coerceAtMost(bitmap.height)
                val cropLeft = 0
                val cropRight = (width * 0.70f).toInt().coerceAtMost(bitmap.width)
                val cropWidth = (cropRight - cropLeft).coerceAtLeast(10)
                val cropHeight = (cropBottom - cropTop).coerceAtLeast(10)

                val menuCrop = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)
                try {
                    findDarkWechatPopupRect(menuCrop, cropLeft, cropTop, width, height)?.let { rect ->
                        val tapX = (rect.left + rect.width * 0.11f).coerceIn(0f, (width - 1).toFloat())
                        val tapY = (rect.top + rect.height * 0.78f).coerceIn(0f, (height - 1).toFloat())
                        logger.log(
                            TAG,
                            "通过深色弹窗矩形定位\"转文字\": rect=(${rect.left.toInt()},${rect.top.toInt()},${rect.right.toInt()},${rect.bottom.toInt()}) tap=(${tapX.toInt()},${tapY.toInt()})",
                        )
                        return Pair(tapX, tapY)
                    }

                    val outDir = File(filesDir, "captures").apply { mkdirs() }
                    val tempFile = File(outDir, "cap_longpress_menu_${System.currentTimeMillis()}.png")
                    FileOutputStream(tempFile).use { fos ->
                        menuCrop.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                    val menuOcrText = ocr.recognize(tempFile.absolutePath).trim()
                    logger.log(TAG, "弹窗 OCR 文本: ${menuOcrText.take(200)}")
                    runCatching { tempFile.delete() }

                    // 在 OCR 结果中搜索"转文字"关键词
                    val transcribeKeywords = listOf("转文字", "轉文字", "转女字", "轉女字", "转文宇", "轉文宇")
                    val lines = menuOcrText.lines()
                    for ((lineIdx, line) in lines.withIndex()) {
                        for (keyword in transcribeKeywords) {
                            val charIdx = line.indexOf(keyword)
                            if (charIdx >= 0) {
                                // 估算"转文字"在裁剪图中的相对位置，映射回全屏坐标
                                val relativeX = (charIdx.toFloat() + keyword.length / 2f) / line.length.coerceAtLeast(1)
                                val relativeY = (lineIdx.toFloat() + 0.5f) / lines.size.coerceAtLeast(1)
                                val tapX = (cropLeft + relativeX * cropWidth).coerceIn(0f, (width - 1).toFloat())
                                val tapY = (cropTop + relativeY * cropHeight).coerceIn(0f, (height - 1).toFloat())
                                return Pair(tapX, tapY)
                            }
                        }
                    }
                    null
                } finally {
                    menuCrop.recycle()
                }
            } finally {
                bitmap.recycle()
            }
        }.getOrElse { e ->
            logger.log(TAG, "弹窗 OCR 定位\"转文字\"失败: ${e.message}")
            null
        }
    }

    private data class PopupRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    private fun findDarkWechatPopupRect(
        crop: Bitmap,
        offsetX: Int,
        offsetY: Int,
        fullWidth: Int,
        fullHeight: Int,
    ): PopupRect? {
        val w = crop.width
        val h = crop.height
        if (w < 80 || h < 80) return null

        fun isMenuDark(pixel: Int): Boolean {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return r in 35..95 && g in 35..95 && b in 35..95 && kotlin.math.abs(r - g) <= 20 && kotlin.math.abs(g - b) <= 20
        }

        val rowHits = IntArray(h)
        val colHits = IntArray(w)
        val step = 3
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if (isMenuDark(crop.getPixel(x, y))) {
                    rowHits[y]++
                    colHits[x]++
                }
                x += step
            }
            y += step
        }

        val minRowHits = (w / step * 0.18f).toInt().coerceAtLeast(8)
        var top = -1
        var bottom = -1
        var inBand = false
        var gap = 0
        for (row in 0 until h step step) {
            if (rowHits[row] >= minRowHits) {
                if (!inBand) {
                    top = row
                    inBand = true
                }
                bottom = row
                gap = 0
            } else if (inBand) {
                gap += step
                if (gap > 15) break
            }
        }
        if (top < 0 || bottom - top < fullHeight * 0.08f) return null

        val minColHits = ((bottom - top) / step * 0.22f).toInt().coerceAtLeast(8)
        var left = -1
        var right = -1
        inBand = false
        gap = 0
        for (col in 0 until w step step) {
            if (colHits[col] >= minColHits) {
                if (!inBand) {
                    left = col
                    inBand = true
                }
                right = col
                gap = 0
            } else if (inBand) {
                gap += step
                if (gap > 18) break
            }
        }
        if (left < 0 || right - left < fullWidth * 0.30f) return null

        return PopupRect(
            left = (offsetX + left).toFloat(),
            top = (offsetY + top).toFloat(),
            right = (offsetX + right).toFloat(),
            bottom = (offsetY + bottom).toFloat(),
        )
    }

    private fun extractVoiceTranscriptionFromAnchor(
        ocr: FallbackOcrProvider,
        cap: com.agentime.ime.host.capture.CaptureResult,
        anchorY: Float,
        contactName: String,
        lastReplyText: String,
        label: String,
    ): InboundCandidate {
        val anchorCropPath = createVoiceTranscriptionAnchorCrop(cap.imagePath, anchorY, label)
        if (!anchorCropPath.isNullOrBlank()) {
            val raw = ocr.recognize(anchorCropPath).trim()
            if (raw.isBlank()) {
                logger.log(TAG, "$label: 锚点扩展区域 OCR 为空 path=$anchorCropPath")
            } else {
                val res = ConversationTextExtractor.extractLatestInboundMessage(
                    ocrText = raw,
                    contactName = contactName,
                    lastReplyText = lastReplyText,
                )
                logger.log(TAG, "$label: anchorRawLen=${raw.length} extracted=${res.text.take(80)}")
                if (isUsableVoiceTranscriptionText(res.text)) {
                    return InboundCandidate(
                        text = res.text,
                        signature = res.signature,
                        sourceLabel = label,
                        path = anchorCropPath,
                        score = scoreInboundCandidate(res.text),
                    )
                } else if (res.text.isNotBlank()) {
                    logger.log(TAG, "$label: 锚点候选疑似语音 UI/OCR 噪声，忽略: ${res.text.take(80)}")
                }
            }
        }

        return extractLatestInboundBubbleOnly(
            ocr = ocr,
            cap = cap,
            contactName = contactName,
            lastReplyText = lastReplyText,
            label = label,
        )
    }

    private fun createVoiceTranscriptionAnchorCrop(imagePath: String, anchorY: Float, label: String): String? {
        val bitmap = BitmapFactory.decodeFile(imagePath) ?: return null
        try {
            val width = bitmap.width
            val height = bitmap.height
            if (width < 200 || height < 400) return null

            val detectedInputTop = detectWechatInputBarTop(bitmap)
            val fallbackInputTop = (height * 0.985f).toInt().coerceAtMost(height)
            val inputTop = detectedInputTop
                ?.takeIf { it > anchorY + height * 0.09f }
                ?: fallbackInputTop
            val cropTop = (anchorY - height * 0.055f).toInt().coerceAtLeast((height * 0.10f).toInt())
            val cropBottom = (inputTop - maxOf(4, (height * 0.004f).toInt()))
                .coerceIn(cropTop + 40, (height * 0.985f).toInt().coerceAtMost(height))
            val cropRight = (width * 0.92f).toInt().coerceAtMost(width)
            val cropWidth = cropRight.coerceAtLeast(10)
            val cropHeight = (cropBottom - cropTop).coerceAtLeast(10)

            val crop = Bitmap.createBitmap(bitmap, 0, cropTop, cropWidth, cropHeight)
            try {
                val out = File(filesDir, "captures").apply { mkdirs() }
                    .resolve("cap_${label.replace(Regex("""\W+"""), "_")}_${System.currentTimeMillis()}_voice_anchor.png")
                FileOutputStream(out).use { fos ->
                    crop.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                logger.log(TAG, "$label: 锚点扩展裁剪 top=$cropTop bottom=$cropBottom inputTop=$inputTop path=${out.absolutePath}")
                return out.absolutePath
            } finally {
                crop.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun detectWechatInputBarTop(bitmap: Bitmap): Int? {
        val width = bitmap.width
        val height = bitmap.height
        val yStart = (height * 0.78f).toInt().coerceAtLeast(0)
        val yEnd = (height * 0.985f).toInt().coerceAtMost(height)
        if (yEnd - yStart < 40) return null

        fun isInputWhite(pixel: Int): Boolean {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return r >= 245 && g >= 245 && b >= 245
        }

        fun isDarkIcon(pixel: Int): Boolean {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return r <= 90 && g <= 90 && b <= 90
        }

        fun ratio(y: Int, xStart: Int, xEnd: Int, predicate: (Int) -> Boolean): Double {
            var hit = 0
            var total = 0
            var x = xStart
            while (x < xEnd) {
                if (predicate(bitmap.getPixel(x, y))) hit++
                total++
                x += 4
            }
            return if (total == 0) 0.0 else hit.toDouble() / total
        }

        fun rowLooksLikeInputBar(y: Int): Boolean {
            val centerWhite = ratio(
                y,
                (width * 0.12f).toInt(),
                (width * 0.76f).toInt(),
                ::isInputWhite,
            )
            val leftDark = ratio(
                y,
                (width * 0.02f).toInt(),
                (width * 0.13f).toInt(),
                ::isDarkIcon,
            )
            val rightDark = ratio(
                y,
                (width * 0.78f).toInt(),
                (width * 0.98f).toInt(),
                ::isDarkIcon,
            )
            return centerWhite >= 0.32 && (leftDark >= 0.018 || rightDark >= 0.018)
        }

        var runTop = -1
        var runLength = 0
        for (y in yEnd - 1 downTo yStart) {
            if (rowLooksLikeInputBar(y)) {
                runTop = y
                runLength++
            } else if (runLength >= 10) {
                return runTop
            } else {
                runTop = -1
                runLength = 0
            }
        }
        return if (runLength >= 10) runTop else null
    }

    private fun extractLatestInboundBubbleOnly(
        ocr: FallbackOcrProvider,
        cap: com.agentime.ime.host.capture.CaptureResult,
        contactName: String,
        lastReplyText: String,
        label: String,
    ): InboundCandidate {
        val path = cap.latestInboundVoiceTranscriptionCropPath ?: cap.latestInboundBubbleCropPath
        if (path.isNullOrBlank()) {
            logger.log(TAG, "$label: 无最新左侧语音转写裁剪")
            return InboundCandidate(sourceLabel = label, path = cap.imagePath)
        }
        val raw = ocr.recognize(path).trim()
        if (raw.isBlank()) {
            logger.log(TAG, "$label: 最新左侧语音转写区域 OCR 为空 path=$path")
            return InboundCandidate(sourceLabel = label, path = path)
        }
        val res = ConversationTextExtractor.extractLatestInboundMessage(
            ocrText = raw,
            contactName = contactName,
            lastReplyText = lastReplyText,
        )
        logger.log(TAG, "$label: rawLen=${raw.length} extracted=${res.text.take(80)}")
        if (!isUsableVoiceTranscriptionText(res.text)) {
            if (res.text.isNotBlank()) {
                logger.log(TAG, "$label: 最新左侧语音转写候选疑似 UI/OCR 噪声，忽略: ${res.text.take(80)}")
            }
            return InboundCandidate(sourceLabel = label, path = path)
        }
        return InboundCandidate(
            text = res.text,
            signature = res.signature,
            sourceLabel = label,
            path = path,
            score = scoreInboundCandidate(res.text),
        )
    }

    private fun isUsableVoiceTranscriptionText(text: String): Boolean {
        val normalized = ConversationTextExtractor.signatureOf(text)
        if (normalized.isBlank()) return false
        if (ConversationTextExtractor.looksLikeVoiceTranscriptionUiOnly(normalized)) return false

        val compact = normalized.replace(Regex("""\s+"""), "")
        if (compact.isBlank()) return false
        val hasHan = compact.any { it in '\u4e00'..'\u9fff' }
        val hasAsciiLetter = compact.any { it in 'A'..'Z' || it in 'a'..'z' }
        val hasDigit = compact.any { it.isDigit() }
        val hasDurationQuote = compact.any { it == '"' || it == '\'' || it == '”' || it == '″' || it == '’' }
        val hasKana = compact.any { it in '\u3040'..'\u30ff' }

        if (hasDurationQuote && hasDigit && compact.length <= 6) return false
        if (hasKana && !hasHan) return false
        if (!hasHan && !hasAsciiLetter) return false
        if (!hasHan && hasDigit && compact.length <= 8) return false
        if (!hasHan && compact.length <= 1) return false

        return true
    }

    private fun readImageSize(path: String): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        val width = options.outWidth
        val height = options.outHeight
        return if (width > 0 && height > 0) width to height else null
    }

    private fun inboundSourceTier(label: String): Int {
        return when {
            label.contains("最新左侧气泡") -> 0
            label.contains("我方最后一条之后会话区") -> 1
            label.contains("最近连续入站消息块") -> 2
            else -> 5
        }
    }

    private data class InboundCandidate(
        val text: String = "",
        val signature: String = "",
        val sourceLabel: String? = null,
        val path: String? = null,
        val score: Int = Int.MIN_VALUE,
        // 语音转文字成功后的全屏截图路径（转文字展开后重新截取，用于发给 VLM）
        val fullScreenshotPath: String? = null,
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
            cap.latestInboundBubbleCropPath,
            cap.latestInboundVoiceTranscriptionCropPath,
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
        
        val lastNotifyTs = prefs.getLong("last_notify_timestamp", 0L)
        val timeSinceNotify = System.currentTimeMillis() - lastNotifyTs
        if (timeSinceNotify < 6000L) {
            logger.log(TAG, "跳过返回会话列表动作：近期 (${timeSinceNotify}ms前) 有通知触发，正处于会话打开/转场阶段，避让通知流，reason=$reason")
            return
        }

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

    private fun shouldScheduleVlmRetry(inboundSignature: String): Boolean {
        val signature = inboundSignature.ifBlank { "blank" }
        val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
        val lastSignature = prefs.getString("last_vlm_retry_signature", "").orEmpty()
        val retryCount = if (lastSignature == signature) prefs.getInt("last_vlm_retry_count", 0) else 0
        if (retryCount >= 1) {
            logger.log(TAG, "VLM 识别失败已重试过一次，跳过继续重试: signature=${signature.take(60)}")
            return false
        }
        prefs.edit()
            .putString("last_vlm_retry_signature", signature)
            .putInt("last_vlm_retry_count", retryCount + 1)
            .apply()
        return true
    }

    private fun scheduleVlmRetryFollowupScan(retryAfterMs: Long) {
        val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("runtime_enabled", false)) return
        if (prefs.getString("execution_mode", "auto").orEmpty() != "auto") return
        val delay = retryAfterMs.takeIf { it in 1000L..10000L } ?: 2500L
        logger.log(TAG, "VLM 识别失败但可重试，已安排 ${delay}ms 后补扫一次")
        mainHandler.postDelayed({
            io.execute { scanConversationListAndRun(scanSource = "vlm_retry_followup") }
        }, delay)
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
            val lastReplyText = getLastReplyText(prefs, sessionId)
            val lastReplySig = ConversationTextExtractor.signatureOf(lastReplyText)
            val scopedSig = ConversationTextExtractor.signatureOf(scopedOcrText)
            // 发送后探测必须足够保守：若仍大量含有我方刚发内容，则判为未出现新的客户入站。
            if (lastReplySig.isNotBlank() && scopedSig.contains(lastReplySig.take(8))) {
                return@runCatching false
            }
            val extraction = ConversationTextExtractor.extractLatestInboundMessage(
                ocrText = scopedOcrText,
                contactName = contactName,
                lastReplyText = lastReplyText,
            )
            val text = extraction.text
            val sig = extraction.signature
            val lastSig = getLastInboundSignature(prefs, sessionId)
            if (text.isBlank() || sig.isBlank()) return@runCatching false
            if (sig == lastSig) return@runCatching false
            if (ConversationTextExtractor.looksLikeAgentReplyCandidate(text, lastReplyText)) {
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
        const val ACTION_RUN_OUTBOUND_TASK = "com.agentime.ime.action.RUN_OUTBOUND_TASK"
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
        private const val OUTBOUND_TASK_POLL_INTERVAL_MS = 4_000L
        // 语音转文字：快捷"转文字"按钮最大等待轮数（每轮 ~700ms）
        private const val VOICE_SHORTCUT_MAX_WAIT_ROUNDS = 3
        // 语音转文字：长按弹窗菜单后最大等待轮数（每轮 ~700ms）
        private const val VOICE_LONGPRESS_MAX_WAIT_ROUNDS = 8
        private const val MAX_PENDING_RUN_ONCE = 20
        private const val PENDING_DRAIN_DELAY_MS = 800L
        private val pendingLock = Any()
        private val pendingRunOnceQueue = ArrayDeque<PendingRunOnce>()
        private val pendingRunOnceKeys = mutableSetOf<String>()
        @Volatile private var pendingScanSource: String? = null

        fun isBusyOrCoolingDown(): Boolean {
            val coolingDown = System.currentTimeMillis() - lastFinishedAt < 8_000L
            return running.get() || coolingDown
        }
    }
}
