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
        private var lastScreenshotAnalysisTriggerAt: Long = 0L
        @Volatile
        private var lastDelayedScheduleAt: Long = 0L

        private val unreadCountRegex = Regex("""^\d+$""")
        private val unreadDescRegex = Regex("""未读|新消息""")
        private val timeLikeRegex = Regex("""^\d{1,2}:\d{2}$|^(昨天|前天)$|^\d{1,2}/\d{1,2}$""")
        private val listNoiseTexts = setOf("微信", "通讯录", "发现", "我", "搜索", "更多", "返回")
        private const val SCREENSHOT_ANALYSIS_TRIGGER_COOLDOWN_MS = 2_500L

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

        fun isCurrentChatTarget(contactName: String): Boolean {
            val current = getCurrentChatContactName().orEmpty()
            return namesLookSame(current, contactName)
        }

        fun openWechatSearch(): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow
            if (root?.packageName?.toString() == WECHAT_PACKAGE) {
                findNodeByLabel(root, "搜索", topOnly = true)?.let { node ->
                    if (clickNodeOrParent(node)) {
                        Log.i(TAG, "已通过无障碍节点点击微信搜索")
                        return true
                    }
                }
            }
            val dm = svc.resources.displayMetrics
            val x = dm.widthPixels * 0.84f
            val y = dm.heightPixels * 0.075f
            Log.i(TAG, "未找到搜索节点，使用坐标点击搜索 ($x,$y)")
            return svc.tap(x, y)
        }

        fun focusWechatSearchInput(): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow
            if (root?.packageName?.toString() == WECHAT_PACKAGE) {
                findEditableNode(root)?.let { node ->
                    if (clickNodeOrParent(node)) {
                        Log.i(TAG, "已通过无障碍节点聚焦搜索输入框")
                        return true
                    }
                }
            }
            val dm = svc.resources.displayMetrics
            val x = dm.widthPixels * 0.45f
            val y = dm.heightPixels * 0.075f
            Log.i(TAG, "未找到搜索输入框节点，使用坐标聚焦搜索框 ($x,$y)")
            return svc.tap(x, y)
        }

        fun tapWechatSearchResult(contactName: String, keyword: String): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow
            if (root?.packageName?.toString() == WECHAT_PACKAGE) {
                findBestSearchResultNode(root, contactName, keyword)?.let { node ->
                    if (clickNodeOrParent(node)) {
                        Log.i(TAG, "已点击搜索结果节点: ${node.text ?: node.contentDescription}")
                        return true
                    }
                }
            }
            val dm = svc.resources.displayMetrics
            val x = dm.widthPixels * 0.43f
            val y = dm.heightPixels * 0.205f
            Log.i(TAG, "未找到搜索结果节点，点击首个联系人结果坐标 ($x,$y)")
            return svc.tap(x, y)
        }

        fun getLatestVisibleInboundText(): String? {
            val svc = instance ?: return null
            val root = svc.rootInActiveWindow ?: return null
            if (root.packageName?.toString() != WECHAT_PACKAGE) return null

            val screen = Rect().also { root.getBoundsInScreen(it) }
            val width = screen.width().coerceAtLeast(1)
            val height = screen.height().coerceAtLeast(1)
            val timeLikeRegex = Regex("""^\d{1,2}:\d{2}$""")
            val noiseTexts = setOf("微信", "返回", "更多", "表情", "按住说话", "切换到键盘", "切换到语音", "更多功能")
            val candidates = mutableListOf<Pair<String, Rect>>()

            fun isLikelyMessageText(text: String, bounds: Rect): Boolean {
                if (text.isBlank() || text.length > 160) return false
                if (text in noiseTexts) return false
                if (timeLikeRegex.matches(text)) return false
                if (text.contains("返回") || text.contains("更多")) return false
                if (bounds.width() <= 0 || bounds.height() <= 0) return false
                if (bounds.top < height * 0.14f || bounds.bottom > height * 0.90f) return false
                // 仅作为 OCR 失败兜底：在最新可见消息已判定为 inbound 时，取底部偏左文本。
                if (bounds.centerX() > width * 0.78f) return false
                return true
            }

            fun walk(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val text = node.text?.toString()?.trim().orEmpty()
                if (text.isNotBlank()) {
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    if (isLikelyMessageText(text, bounds)) {
                        candidates += text to bounds
                    }
                }
                for (i in 0 until node.childCount) {
                    walk(node.getChild(i))
                }
            }

            walk(root)
            return candidates
                .maxWithOrNull(compareBy<Pair<String, Rect>> { it.second.bottom }.thenBy { it.second.top })
                ?.first
        }

        fun isLikelyOnChatPage(): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            if (root.packageName?.toString() != WECHAT_PACKAGE) return false

            val screen = Rect().also { root.getBoundsInScreen(it) }
            val height = screen.height().coerceAtLeast(1)
            var inputSignalCount = 0
            var bottomTabHitCount = 0
            var hasTopBackSignal = false
            val bottomTabs = setOf("微信", "通讯录", "发现", "我")
            val inputSignals = listOf("按住说话", "切换到键盘", "切换到语音", "表情", "更多功能")

            fun walk(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val label = listOf(
                    node.text?.toString()?.trim().orEmpty(),
                    node.contentDescription?.toString()?.trim().orEmpty(),
                ).filter { it.isNotBlank() }.joinToString(" ")
                if (label.isNotBlank()) {
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    if (bounds.top < height * 0.18f && label.contains("返回")) {
                        hasTopBackSignal = true
                    }
                    if (bounds.top > height * 0.72f && inputSignals.any { label.contains(it) }) {
                        inputSignalCount += 1
                    }
                    if (bounds.top > height * 0.72f && bottomTabs.any { it == label }) {
                        bottomTabHitCount += 1
                    }
                }
                for (i in 0 until node.childCount) {
                    walk(node.getChild(i))
                }
            }

            walk(root)
            return inputSignalCount > 0 &&
                bottomTabHitCount < 2 &&
                (hasTopBackSignal || !findLikelyChatTitle(root).isNullOrBlank())
        }

        private fun namesLookSame(left: String, right: String): Boolean {
            val a = normalizeNameForCompare(left)
            val b = normalizeNameForCompare(right)
            if (a.isBlank() || b.isBlank()) return false
            return a == b || a.contains(b) || b.contains(a)
        }

        private fun normalizeNameForCompare(value: String): String {
            return value
                .replace("\\s".toRegex(), "")
                .replace("（", "(")
                .replace("）", ")")
                .lowercase()
                .trim()
        }

        private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isEditable || node.className?.toString()?.contains("EditText") == true) return node
                for (i in 0 until node.childCount) {
                    walk(node.getChild(i))?.let { return it }
                }
                return null
            }
            return walk(root)
        }

        private fun findNodeByLabel(root: AccessibilityNodeInfo, keyword: String, topOnly: Boolean = false): AccessibilityNodeInfo? {
            val screen = Rect().also { root.getBoundsInScreen(it) }
            fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                val label = listOf(
                    node.text?.toString()?.trim().orEmpty(),
                    node.contentDescription?.toString()?.trim().orEmpty(),
                ).firstOrNull { it.isNotBlank() }.orEmpty()
                if (label.contains(keyword)) {
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    if (!topOnly || bounds.top < screen.height() * 0.18f) return node
                }
                for (i in 0 until node.childCount) {
                    walk(node.getChild(i))?.let { return it }
                }
                return null
            }
            return walk(root)
        }

        private fun findBestSearchResultNode(root: AccessibilityNodeInfo, contactName: String, keyword: String): AccessibilityNodeInfo? {
            val screen = Rect().also { root.getBoundsInScreen(it) }
            val wanted = listOf(contactName, keyword).map { normalizeNameForCompare(it) }.filter { it.isNotBlank() }
            val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()

            fun walk(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val label = listOf(
                    node.text?.toString()?.trim().orEmpty(),
                    node.contentDescription?.toString()?.trim().orEmpty(),
                ).firstOrNull { it.isNotBlank() }.orEmpty()
                if (label.isNotBlank()) {
                    val normalized = normalizeNameForCompare(label)
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    val inResultArea = bounds.top > screen.height() * 0.11f &&
                        bounds.top < screen.height() * 0.42f &&
                        bounds.left < screen.width() * 0.82f
                    if (inResultArea && wanted.any { normalized == it || normalized.contains(it) || it.contains(normalized) }) {
                        candidates += node to bounds.top
                    }
                }
                for (i in 0 until node.childCount) walk(node.getChild(i))
            }

            walk(root)
            return candidates.minByOrNull { it.second }?.first
        }

        private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
            var current: AccessibilityNodeInfo? = node
            repeat(6) {
                val candidate = current ?: return@repeat
                if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                current = candidate.parent
            }
            val svc = instance ?: return false
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (bounds.width() <= 0 || bounds.height() <= 0) return false
            return svc.tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
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

        fun clickBack(): Boolean {
            val svc = instance ?: run {
                Log.w(TAG, "clickBack 失败：无障碍服务实例为空")
                return false
            }
            Log.i(TAG, "准备执行系统级返回动作 (GLOBAL_ACTION_BACK)")
            // 连续执行两次返回：第一次可能是收起键盘，第二次才是退出聊天页
            val ok1 = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            if (ok1) {
                Thread.sleep(200)
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            return ok1
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
            instance ?: return
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
            if (triggerSource == "poll") return
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
            val runtimeStartedAt = prefs.getLong("runtime_started_at", 0L)
            if (now - runtimeStartedAt < 6000L) {
                // 忽略刚启动前 6 秒的事件（防止 MainActivity 弹出的 Toast 消失引起画面变化）
                // 若期内有真实事件，我们只安排一次延后（6 秒后）的兜底扫描，避免漏掉期内的真实新消息
                if (System.currentTimeMillis() - lastDelayedScheduleAt > 6000L) {
                    lastDelayedScheduleAt = System.currentTimeMillis()
                    val delay = 6000L - (now - runtimeStartedAt) + 200L
                    Handler(Looper.getMainLooper()).postDelayed({
                        runCatching { scanForegroundWechatUi("delayed_startup_event", event) }
                    }, delay)
                }
                return
            }

            if (now - lastScreenshotAnalysisTriggerAt < SCREENSHOT_ANALYSIS_TRIGGER_COOLDOWN_MS) return
            lastScreenshotAnalysisTriggerAt = now
            val intent = Intent(svc, HostForegroundService::class.java).apply {
                action = HostForegroundService.ACTION_SCAN_CONVERSATION_LIST
                putExtra(HostForegroundService.EXTRA_SCAN_SOURCE, triggerSource)
            }
            svc.startService(intent)
            hostLog(
                svc,
                "前台变化已触发截图分析当前微信页（source=$triggerSource root=${root?.className ?: "null"}）",
            )
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
            val sessionId = SessionIdentity.buildSessionId(context, contactName)
            val intent = Intent(context, HostForegroundService::class.java).apply {
                action = HostForegroundService.ACTION_RUN_ONCE
                putExtra(HostForegroundService.EXTRA_SESSION_ID, sessionId)
                putExtra(HostForegroundService.EXTRA_CONTACT_NAME, contactName)
            }
            context.startService(intent)
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
