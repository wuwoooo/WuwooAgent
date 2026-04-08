package com.agentime.ime.host.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.PowerManager
import android.util.Log
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import com.agentime.ime.host.agent.SessionIdentity
import com.agentime.ime.host.orchestrator.HostForegroundService
import com.agentime.ime.host.storage.HostLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 微信最小自动化服务：启动微信、点击输入框区域、点击发送区域。
 * 坐标默认值可通过 SharedPreferences 覆盖：
 * - input_x/input_y
 * - send_x/send_y
 */
class WechatAccessibilityService : AccessibilityService() {
    private var stopOverlayView: View? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private val pollHandler = Handler(Looper.getMainLooper())
    private val foregroundPoll = object : Runnable {
        override fun run() {
            runCatching { scanForegroundWechatUi(triggerSource = "poll") }
            pollHandler.postDelayed(this, 1800L)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventPackage = event?.packageName?.toString()
        maybeTriggerForegroundAuto(event)
    }
    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        instance = this
        syncRuntimeUi()
        startForegroundPolling()
    }

    override fun onDestroy() {
        stopForegroundPolling()
        hideStopOverlayInternal()
        releaseWakeLockInternal()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun syncRuntimeUi() {
        val running = getSharedPreferences("host_config", Context.MODE_PRIVATE)
            .getBoolean("runtime_enabled", false)
        if (running) {
            ensureWakeLockInternal()
            showStopOverlayInternal()
        } else {
            hideStopOverlayInternal()
            releaseWakeLockInternal()
        }
    }

    private fun startForegroundPolling() {
        pollHandler.removeCallbacks(foregroundPoll)
        pollHandler.postDelayed(foregroundPoll, 1800L)
    }

    private fun stopForegroundPolling() {
        pollHandler.removeCallbacks(foregroundPoll)
    }

    private fun ensureWakeLockInternal() {
        if (screenWakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        screenWakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "WuwooAgent:RuntimeScreenOn",
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLockInternal() {
        runCatching {
            if (screenWakeLock?.isHeld == true) {
                screenWakeLock?.release()
            }
        }
        screenWakeLock = null
    }

    private fun showStopOverlayInternal() {
        if (stopOverlayView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val btn = Button(this).apply {
            text = "停止"
            textSize = 14f
            setPadding(26, 18, 26, 18)
            alpha = 0.92f
            setOnClickListener {
                val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("runtime_enabled", false).apply()
                hideStopOverlayInternal()
                releaseWakeLockInternal()
                Log.i(TAG, "悬浮停止按钮已停止运行")
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 24
            y = 0
        }
        wm.addView(btn, lp)
        stopOverlayView = btn
    }

    private fun hideStopOverlayInternal() {
        val view = stopOverlayView ?: return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(view) }
        stopOverlayView = null
    }

    /**
     * 无障碍 [dispatchGesture] 须在 **主线程** 调用；Host 在后台线程跑 runOnce 时若直接调会失败或只走 onCancelled。
     * 主线程调用时不能阻塞等待回调（会与 Gesture 回调死锁），仅返回是否成功入队。
     */
    private fun tap(x: Float, y: Float): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
                .build()
            return dispatchGesture(gesture, null, null)
        }
        val latch = CountDownLatch(1)
        val completed = booleanArrayOf(false)
        Handler(Looper.getMainLooper()).post {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
                .build()
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        completed[0] = true
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "手势取消 ($x,$y)，可检查坐标/无障碍")
                        latch.countDown()
                    }
                },
                null,
            )
            if (!dispatched) {
                Log.w(TAG, "dispatchGesture=false ($x,$y)")
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return completed[0]
    }

    companion object {
        private const val TAG = "WechatAccessibility"
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        @Volatile
        private var instance: WechatAccessibilityService? = null
        @Volatile
        private var lastLaunchError: String? = null

        @Volatile
        private var lastEventPackage: String? = null
        @Volatile
        private var lastForegroundTriggerAt: Long = 0L

        private val unreadCountRegex = Regex("""^\d+$""")
        private val unreadDescRegex = Regex("""未读|新消息""")
        private val timeLikeRegex = Regex("""^\d{1,2}:\d{2}$|^(昨天|前天)$|^\d{1,2}/\d{1,2}$""")
        private val listNoiseTexts = setOf("微信", "通讯录", "发现", "我", "搜索", "更多", "返回")

        fun launchWechat(context: Context): Boolean {
            val app = context.applicationContext
            val launch = app.packageManager.getLaunchIntentForPackage(WECHAT_PACKAGE) ?: run {
                lastLaunchError = "未找到微信启动 Intent"
                return false
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            lastLaunchError = null
            val svc = instance
            if (svc != null) {
                val serviceResult = runCatching {
                    svc.startActivity(launch)
                    true
                }.getOrElse {
                    lastLaunchError = "service.startActivity 失败: ${it.message}"
                    Log.e(TAG, "launchWechat(service) 失败: ${it.message}", it)
                    false
                }
                if (serviceResult) return true
            }
            return runCatching {
                app.startActivity(launch)
                true
            }.getOrElse {
                lastLaunchError = "context.startActivity 失败: ${it.message}"
                Log.e(TAG, "launchWechat(context) 失败: ${it.message}", it)
                false
            }
        }

        fun isWechatForeground(): Boolean {
            val svc = instance ?: return false
            val rootPkg = svc.rootInActiveWindow?.packageName?.toString()
            if (rootPkg == WECHAT_PACKAGE) return true
            val eventPkg = lastEventPackage
            if (eventPkg == WECHAT_PACKAGE) return true
            return false
        }

        fun getForegroundDebugInfo(): String {
            val svc = instance
            val rootPkg = svc?.rootInActiveWindow?.packageName?.toString()
            return "serviceConnected=${svc != null}, rootPkg=${rootPkg ?: "null"}, eventPkg=${lastEventPackage ?: "null"}, lastLaunchError=${lastLaunchError ?: "null"}"
        }

        fun getCurrentChatContactName(): String? {
            val svc = instance ?: return null
            val root = svc.rootInActiveWindow ?: return null
            return findLikelyChatTitle(root)
        }

        fun onRuntimeEnabledChanged(enabled: Boolean) {
            val svc = instance ?: return
            Handler(Looper.getMainLooper()).post {
                if (enabled) {
                    svc.ensureWakeLockInternal()
                    svc.showStopOverlayInternal()
                } else {
                    svc.hideStopOverlayInternal()
                    svc.releaseWakeLockInternal()
                }
            }
        }

        fun focusInputArea(): Boolean {
            val svc = instance ?: return false
            val prefs = svc.getSharedPreferences("host_config", Context.MODE_PRIVATE)
            val (x, y) = resolveTapPair(
                prefs,
                svc,
                "input_x",
                "input_y",
                450f / 1080f,
                2280f / 2245f,
            )
            Log.i(TAG, "focusInputArea 坐标 ($x, $y)")
            repeat(4) { attempt ->
                if (svc.tap(x, y)) {
                    if (attempt > 0) Log.i(TAG, "focusInputArea 第 ${attempt + 1} 次点击成功")
                    return true
                }
                Thread.sleep(280)
            }
            return false
        }

        fun tapConversationAt(x: Float, y: Float): Boolean {
            val svc = instance ?: return false
            Log.i(TAG, "tapConversationAt 坐标 ($x, $y)")
            return svc.tap(x, y)
        }

        fun clickSend(): Boolean {
            val svc = instance ?: return false
            val prefs = svc.getSharedPreferences("host_config", Context.MODE_PRIVATE)
            // 键盘弹出时固定像素易点到键盘区；未手动写入 send_* 时用屏宽比例估算「输入栏右侧发送」
            val (x, y) = resolveTapPair(
                prefs,
                svc,
                "send_x",
                "send_y",
                1000f / 1080f,
                2280f / 2245f,
            )
            Log.i(TAG, "clickSend 坐标 ($x, $y)；若仍偏可在 host_config 写入 send_x/send_y 覆盖")
            return svc.tap(x, y)
        }

        /** 仅当对应 key 从未写入时，用屏幕比例作为默认坐标（像素默认值易在不同分辨率上失效）。 */
        private fun resolveTapPair(
            prefs: android.content.SharedPreferences,
            svc: WechatAccessibilityService,
            keyX: String,
            keyY: String,
            ratioX: Float,
            ratioY: Float,
        ): Pair<Float, Float> {
            val dm = svc.resources.displayMetrics
            val w = dm.widthPixels.toFloat().coerceAtLeast(1f)
            val h = dm.heightPixels.toFloat().coerceAtLeast(1f)
            val x = if (prefs.contains(keyX)) prefs.getFloat(keyX, 0f) else w * ratioX
            val y = if (prefs.contains(keyY)) prefs.getFloat(keyY, 0f) else h * ratioY
            return Pair(x, y)
        }

        fun waitWechatForeground(timeoutMs: Long = 5000): Boolean {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                if (isWechatForeground()) return true
                Thread.sleep(120)
            }
            return false
        }

        fun waitServiceConnected(timeoutMs: Long = 2500): Boolean {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                if (instance != null) return true
                Thread.sleep(120)
            }
            return false
        }

        fun warmupInputAfterLaunch() {
            Handler(Looper.getMainLooper()).postDelayed({ focusInputArea() }, 400)
        }

        private fun maybeTriggerForegroundAuto(event: AccessibilityEvent?) {
            val svc = instance ?: return
            val e = event ?: return
            val pkg = e.packageName?.toString() ?: return
            if (pkg != WECHAT_PACKAGE) return
            if (
                e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            ) return

            scanForegroundWechatUi(triggerSource = "event", event = e)
        }

        private fun scanForegroundWechatUi(triggerSource: String, event: AccessibilityEvent? = null) {
            val svc = instance ?: return
            val root = resolveWechatRoot(svc, event)
            val rootPkg = root?.packageName?.toString().orEmpty()
            val eventPkg = lastEventPackage.orEmpty()
            if (rootPkg != WECHAT_PACKAGE && eventPkg != WECHAT_PACKAGE) {
                return
            }

            val prefs = svc.getSharedPreferences("host_config", Context.MODE_PRIVATE)
            if (prefs.getString("execution_mode", "auto").orEmpty() != "auto") return
            if (!prefs.getBoolean("runtime_enabled", false)) return
            if (!prefs.getBoolean("foreground_auto_enabled", false)) return
            if (HostForegroundService.isBusyOrCoolingDown()) return

            val now = System.currentTimeMillis()
            if (now - lastForegroundTriggerAt < 6_000L) return

            fun triggerConversationListScreenshotAnalysis() {
                lastForegroundTriggerAt = now
                val intent = Intent(svc, HostForegroundService::class.java).apply {
                    action = HostForegroundService.ACTION_SCAN_CONVERSATION_LIST
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    svc.startForegroundService(intent)
                } else {
                    svc.startService(intent)
                }
                hostLog(svc, "前台扫描已降级为截图分析会话列表（source=$triggerSource）")
            }

            if (root == null) {
                if (triggerSource == "poll") {
                    triggerConversationListScreenshotAnalysis()
                }
                return
            }

            if (triggerSource == "poll") {
                if (isConversationListPage(root)) {
                    triggerConversationListScreenshotAnalysis()
                    return
                }
            }

            if (isConversationListPage(root)) {
                hostLog(svc, "前台检测到微信会话列表页，开始扫描未读项（source=$triggerSource）")
                val unread = findBestUnreadConversation(root)
                if (unread != null) {
                    lastForegroundTriggerAt = now
                    val normalizedTitle = SessionIdentity.normalizeContactName(unread.title)
                    if (normalizedTitle.isNotBlank()) {
                        if (svc.tap(unread.tapX, unread.tapY)) {
                            Log.i(TAG, "前台会话列表发现未读，已点击进入: ${unread.title}")
                            hostLog(svc, "前台会话列表发现未读，已点击进入: ${unread.title}（source=$triggerSource）")
                            Handler(Looper.getMainLooper()).postDelayed({
                                val currentPrefs = svc.getSharedPreferences("host_config", Context.MODE_PRIVATE)
                                if (!currentPrefs.getBoolean("runtime_enabled", false)) return@postDelayed
                                startRunOnceForContact(svc, normalizedTitle)
                                Log.i(TAG, "前台会话列表未读已自动触发 runOnce: ${SessionIdentity.buildSessionId(normalizedTitle)}/$normalizedTitle")
                                hostLog(svc, "前台会话列表未读已自动触发 runOnce: ${SessionIdentity.buildSessionId(normalizedTitle)}/$normalizedTitle")
                            }, 1300L)
                        }
                    }
                } else {
                    Log.i(TAG, "前台会话列表已识别，但未找到可处理的未读会话")
                    hostLog(svc, "前台会话列表已识别，但未找到可处理的未读会话")
                }
                return
            }

            val cls = root.className?.toString().orEmpty()
            val looksLikeChatPage = cls.contains("Chatting", ignoreCase = true) ||
                cls.contains("ChatFooter", ignoreCase = true) ||
                cls.contains("LauncherUIBottomTabView", ignoreCase = true) ||
                cls.contains("RecyclerView", ignoreCase = true)
            if (!looksLikeChatPage) {
                if (triggerSource == "poll") {
                    triggerConversationListScreenshotAnalysis()
                }
                return
            }

            val contactName = SessionIdentity.normalizeContactName(getCurrentChatContactName())
            if (contactName.isBlank() || contactName == "当前联系人") return
            lastForegroundTriggerAt = now
            startRunOnceForContact(svc, contactName)
            Log.i(TAG, "前台聊天内容变化，已自动触发 runOnce: ${SessionIdentity.buildSessionId(contactName)}/$contactName")
            hostLog(svc, "前台聊天内容变化，已自动触发 runOnce: ${SessionIdentity.buildSessionId(contactName)}/$contactName（source=$triggerSource）")
        }

        private fun resolveWechatRoot(
            svc: WechatAccessibilityService,
            event: AccessibilityEvent?,
        ): AccessibilityNodeInfo? {
            val direct = svc.rootInActiveWindow
            if (direct?.packageName?.toString() == WECHAT_PACKAGE) return direct

            val eventSource = event?.source
            if (eventSource?.packageName?.toString() == WECHAT_PACKAGE) return eventSource

            val windows = runCatching { svc.windows }.getOrNull().orEmpty()
            for (window in windows) {
                val root = runCatching { window.root }.getOrNull() ?: continue
                if (root.packageName?.toString() == WECHAT_PACKAGE) return root
            }
            return null
        }

        private fun startRunOnceForContact(context: Context, contactName: String) {
            val sessionId = SessionIdentity.buildSessionId(contactName)
            val intent = Intent(context, HostForegroundService::class.java).apply {
                action = HostForegroundService.ACTION_RUN_ONCE
                putExtra(HostForegroundService.EXTRA_SESSION_ID, sessionId)
                putExtra(HostForegroundService.EXTRA_CONTACT_NAME, contactName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private fun findLikelyChatTitle(root: AccessibilityNodeInfo): String? {
            val candidates = mutableListOf<Pair<String, Int>>()

            fun walk(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val text = node.text?.toString()?.trim().orEmpty()
                if (text.isNotBlank()) {
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    if (
                        bounds.top in 0..420 &&
                        text.length in 1..32 &&
                        !text.contains(":") &&
                        text != "微信" &&
                        text != "Wuwoo Agent" &&
                        !text.contains("返回") &&
                        !text.contains("更多") &&
                        !text.contains("搜索")
                    ) {
                        val score = bounds.top * -1 + (32 - text.length)
                        candidates += text to score
                    }
                }
                for (i in 0 until node.childCount) {
                    walk(node.getChild(i))
                }
            }

            walk(root)
            return candidates.maxByOrNull { it.second }?.first
        }

        private fun isConversationListPage(root: AccessibilityNodeInfo): Boolean {
            val texts = mutableSetOf<String>()
            fun walk(node: AccessibilityNodeInfo?) {
                if (node == null) return
                node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { texts += it }
                node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { texts += it }
                for (i in 0 until node.childCount) walk(node.getChild(i))
            }
            walk(root)
            val hasTabs = texts.contains("通讯录") && texts.contains("发现") && texts.contains("我")
            val hasWechatTitle = texts.any { it == "微信" || it.startsWith("微信(") || it.startsWith("微信（") }
            return hasTabs && hasWechatTitle
        }

        private fun findBestUnreadConversation(root: AccessibilityNodeInfo): UnreadConversationCandidate? {
            val screen = Rect().also { root.getBoundsInScreen(it) }
            val markers = mutableListOf<UnreadConversationCandidate>()

            fun walk(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val isUnreadMarker = unreadCountRegex.matches(text) ||
                    unreadDescRegex.containsMatchIn(text) ||
                    unreadDescRegex.containsMatchIn(desc)
                if (isUnreadMarker) {
                    val clickableRow = ascendToClickableRow(node, screen)
                    if (clickableRow != null) {
                        val title = extractConversationTitle(clickableRow)
                        if (title.isNotBlank()) {
                            val bounds = Rect().also { clickableRow.getBoundsInScreen(it) }
                            markers += UnreadConversationCandidate(
                                title = title,
                                tapX = bounds.centerX().toFloat(),
                                tapY = bounds.centerY().toFloat(),
                                top = bounds.top,
                            )
                        }
                    }
                }
                for (i in 0 until node.childCount) walk(node.getChild(i))
            }

            walk(root)
            return markers
                .distinctBy { it.title }
                .minByOrNull { it.top }
        }

        private fun ascendToClickableRow(node: AccessibilityNodeInfo, screen: Rect): AccessibilityNodeInfo? {
            var current: AccessibilityNodeInfo? = node
            repeat(8) {
                current = current?.parent
                val candidate = current ?: return@repeat
                val bounds = Rect().also { candidate.getBoundsInScreen(it) }
                val wideEnough = bounds.width() >= (screen.width() * 0.55f)
                val tallEnough = bounds.height() >= 90
                val inListArea = bounds.top > 140 && bounds.bottom < screen.bottom - 140
                if ((candidate.isClickable || candidate.isFocusable) && wideEnough && tallEnough && inListArea) {
                    return candidate
                }
            }
            return null
        }

        private fun extractConversationTitle(row: AccessibilityNodeInfo): String {
            val candidates = mutableListOf<Pair<String, Int>>()
            fun walk(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val text = node.text?.toString()?.trim().orEmpty()
                if (
                    text.isNotBlank() &&
                    text.length in 1..24 &&
                    !listNoiseTexts.contains(text) &&
                    !timeLikeRegex.matches(text) &&
                    !unreadCountRegex.matches(text) &&
                    !text.contains("条新消息") &&
                    !text.contains("未读") &&
                    !text.contains(":") &&
                    !text.contains("：")
                ) {
                    val bounds = Rect().also { rect -> node.getBoundsInScreen(rect) }
                    val score = bounds.top * 10000 + bounds.left
                    candidates += text to score
                }
                for (i in 0 until node.childCount) walk(node.getChild(i))
            }
            walk(row)
            return candidates.minByOrNull { it.second }?.first.orEmpty()
        }

        private fun hostLog(context: Context, message: String) {
            HostLogger(context).log(TAG, message)
        }

        private data class UnreadConversationCandidate(
            val title: String,
            val tapX: Float,
            val tapY: Float,
            val top: Int,
        )
    }
}
