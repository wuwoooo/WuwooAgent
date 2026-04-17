package com.agentime.ime

import android.content.ClipboardManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.media.projection.MediaProjectionManager
import android.app.Activity
import android.graphics.Color
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.activity.ComponentActivity
import com.agentime.ime.host.agent.HttpAgentClient
import com.agentime.ime.host.agent.SessionIdentity
import com.agentime.ime.host.automation.WechatNotificationListenerService
import com.agentime.ime.host.automation.WechatAccessibilityService
import com.agentime.ime.host.orchestrator.HostForegroundService
import com.agentime.ime.host.storage.HostLogger
import com.agentime.ime.host.capture.CaptureProviderFactory
import com.agentime.ime.host.capture.ProjectionPermissionStore

class MainActivity : ComponentActivity() {
    private lateinit var logger: HostLogger

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var logView: TextView
    private lateinit var captureProviderView: TextView
    private lateinit var executionModeView: TextView
    private lateinit var backendStatusView: TextView
    private lateinit var currentStatusView: TextView
    private lateinit var accessibilityStatusView: TextView
    private lateinit var imeStatusView: TextView
    private lateinit var notificationStatusView: TextView
    private lateinit var foregroundAutoStatusView: TextView
    private lateinit var advancedLayout: LinearLayout
    private lateinit var toggleAdvancedButton: Button
    private lateinit var runButton: Button
    private lateinit var projectionButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var imeButton: Button
    private lateinit var notificationButton: Button
    private lateinit var notificationAutoButton: Button
    private lateinit var foregroundAutoButton: Button

    private val projectionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ProjectionPermissionStore.update(result.resultCode, result.data)
            logger.log("MainActivity", "MediaProjection 授权成功")
            logger.log("MainActivity", "截图管线将在前台服务中初始化")
            refreshGuideStatus()
            val intent = Intent(this, HostForegroundService::class.java).apply {
                action = HostForegroundService.ACTION_PREPARE_PROJECTION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            logger.log("MainActivity", "MediaProjection 授权取消")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logger = HostLogger(this)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        val prefs = getSharedPreferences("host_config", MODE_PRIVATE)
        val sessionInput = findViewById<EditText>(R.id.inputSessionId)
        val contactInput = findViewById<EditText>(R.id.inputContactName)
        val endpointInput = findViewById<EditText>(R.id.inputAgentEndpoint)
        logView = findViewById(R.id.textLog)
        captureProviderView = findViewById(R.id.textCaptureProvider)
        executionModeView = findViewById(R.id.textExecutionMode)
        backendStatusView = findViewById(R.id.textBackendStatus)
        currentStatusView = findViewById(R.id.textCurrentStatus)
        accessibilityStatusView = findViewById(R.id.textAccessibilityStatus)
        imeStatusView = findViewById(R.id.textImeStatus)
        notificationStatusView = findViewById(R.id.textNotificationStatus)
        foregroundAutoStatusView = findViewById(R.id.textForegroundAutoStatus)
        advancedLayout = findViewById(R.id.layoutAdvanced)
        toggleAdvancedButton = findViewById(R.id.btnToggleAdvanced)
        runButton = findViewById(R.id.btnStartRunOnce)
        projectionButton = findViewById(R.id.btnGrantProjection)
        accessibilityButton = findViewById(R.id.btnAccessibilitySettings)
        imeButton = findViewById(R.id.btnImeSettings)
        notificationButton = findViewById(R.id.btnNotificationSettings)
        notificationAutoButton = findViewById(R.id.btnToggleNotificationAuto)
        foregroundAutoButton = findViewById(R.id.btnToggleForegroundAuto)

        val savedContact = prefs.getString("last_contact_name", "").orEmpty()
        val currentChatContact = WechatAccessibilityService.getCurrentChatContactName().orEmpty()
        val effectiveContact = SessionIdentity.normalizeContactName(this, if (savedContact.isNotBlank()) savedContact else currentChatContact)
        val effectiveSession = prefs.getString("last_session_id", "").orEmpty()
            .ifBlank { SessionIdentity.buildSessionId(this, effectiveContact) }

        sessionInput.setText(effectiveSession)
        contactInput.setText(effectiveContact)
        endpointInput.setText(
            prefs.getString("agent_chat_endpoint", HttpAgentClient.DEFAULT_ENDPOINT)
                .orEmpty()
                .ifBlank { HttpAgentClient.DEFAULT_ENDPOINT },
        )

        toggleAdvancedButton.setOnClickListener {
            val expanded = advancedLayout.visibility == View.VISIBLE
            advancedLayout.visibility = if (expanded) View.GONE else View.VISIBLE
            toggleAdvancedButton.text = if (expanded) "展开高级选项" else "收起高级选项"
        }

        findViewById<Button>(R.id.btnDebugRunOnce).setOnClickListener {
            val rawContact = contactInput.text?.toString().orEmpty()
            val contactName = SessionIdentity.normalizeContactName(
                this,
                rawContact.ifBlank { WechatAccessibilityService.getCurrentChatContactName() },
            )
            val sessionId = sessionInput.text?.toString().orEmpty().ifBlank {
                SessionIdentity.buildSessionId(this, contactName)
            }
            val endpoint = endpointInput.text?.toString().orEmpty().trim()
            prefs.edit()
                .putString("last_session_id", sessionId)
                .putString("last_contact_name", contactName)
                .putString("agent_chat_endpoint", endpoint)
                .apply()
            val intent = Intent(this, HostForegroundService::class.java).apply {
                action = HostForegroundService.ACTION_RUN_ONCE
                putExtra(HostForegroundService.EXTRA_SESSION_ID, sessionId)
                putExtra(HostForegroundService.EXTRA_CONTACT_NAME, contactName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            logger.log("MainActivity", "主界面按钮已触发 runOnce")
            logView.text = "已发起 runOnce: $sessionId / $contactName\n" + logger.readRecent()
            refreshBackendStatus()
            refreshGuideStatus()
        }

        runButton.setOnClickListener {
            if (prefs.getBoolean("runtime_enabled", false)) {
                prefs.edit().putBoolean("runtime_enabled", false).apply()
                WechatAccessibilityService.onRuntimeEnabledChanged(false)
                logger.log("MainActivity", "已停止运行")
                Toast.makeText(this, "已停止自动监听", Toast.LENGTH_SHORT).show()
                refreshGuideStatus()
                refreshCapabilityStatus()
                logView.text = logger.readRecent()
                return@setOnClickListener
            }

            val readiness = computeReadiness()
            if (!readiness.readyToRun) {
                val reason = readiness.firstBlockingReason ?: "请先完成未开启的设置"
                Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
                logger.log("MainActivity", "开始运行被阻止：$reason")
                refreshGuideStatus()
                refreshCapabilityStatus()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("execution_mode", "auto")
                .putBoolean("runtime_enabled", true)
                .putBoolean("foreground_auto_enabled", true)
                .putBoolean("notification_auto_enabled", true)
                .putLong("runtime_started_at", System.currentTimeMillis())
                .apply()
            WechatAccessibilityService.onRuntimeEnabledChanged(true)

            // 只有当截图管线未就绪时才发送 PREPARE；
            // 如果已经初始化过（用户就是先授权再点开始），
            // 重复发送会导致在同一 MediaProjection 上再次调用 createVirtualDisplay 而报错。
            if (!ProjectionPermissionStore.hasPermission()) {
                val prepareIntent = Intent(this, HostForegroundService::class.java).apply {
                    action = HostForegroundService.ACTION_PREPARE_PROJECTION
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(prepareIntent)
                } else {
                    startService(prepareIntent)
                }
            }

            launchWechatApp()
            val runtimeIntent = Intent(this, HostForegroundService::class.java).apply {
                action = HostForegroundService.ACTION_START_RUNTIME
            }
            startService(runtimeIntent)
            logger.log("MainActivity", "已开始运行，正在切到微信前台等待消息；已默认开启前台聊天自动触发和后台通知自动触发")
            refreshExecutionModeStatus()
            refreshGuideStatus()
            refreshCapabilityStatus()
            logView.text = logger.readRecent()
        }


        projectionButton.setOnClickListener {
            val intent = projectionManager.createScreenCaptureIntent()
            projectionLauncher.launch(intent)
        }
        findViewById<Button>(R.id.btnModeAuto).setOnClickListener {
            getSharedPreferences("host_config", MODE_PRIVATE).edit().putString("execution_mode", "auto").apply()
            logger.log("MainActivity", "已切换自动模式")
            logView.text = logger.readRecent()
            refreshExecutionModeStatus()
            refreshGuideStatus()
        }

        findViewById<Button>(R.id.btnModeManual).setOnClickListener {
            getSharedPreferences("host_config", MODE_PRIVATE).edit().putString("execution_mode", "manual").apply()
            logger.log("MainActivity", "已切换手动模式")
            logView.text = logger.readRecent()
            refreshExecutionModeStatus()
            refreshGuideStatus()
        }

        findViewById<Button>(R.id.btnTapConfig).setOnClickListener {
            startActivity(Intent(this, HostTapConfigActivity::class.java))
        }

        findViewById<Button>(R.id.btnRefreshLog).setOnClickListener {
            refreshCaptureProviderStatus()
            refreshExecutionModeStatus()
            refreshBackendStatus()
            refreshGuideStatus()
            refreshCapabilityStatus()
            logView.text = logger.readRecent()
        }


        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val text = logger.readRecent()
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("agent_host_log", text))
            logger.log("MainActivity", "日志已复制到剪贴板")
            logView.text = logger.readRecent()
        }

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            logger.clear()
            logView.text = "日志已清空"
            refreshCaptureProviderStatus()
            refreshExecutionModeStatus()
            refreshBackendStatus()
            refreshGuideStatus()
            refreshCapabilityStatus()
        }

        accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        imeButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        notificationButton.setOnClickListener {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            startActivity(intent)
        }

        notificationAutoButton.setOnClickListener {
            val enabled = prefs.getBoolean("notification_auto_enabled", false)
            prefs.edit().putBoolean("notification_auto_enabled", !enabled).apply()
            logger.log("MainActivity", if (!enabled) "已开启通知自动触发" else "已关闭通知自动触发")
            refreshGuideStatus()
            refreshCapabilityStatus()
            logView.text = logger.readRecent()
        }

        foregroundAutoButton.setOnClickListener {
            val enabled = prefs.getBoolean("foreground_auto_enabled", false)
            prefs.edit().putBoolean("foreground_auto_enabled", !enabled).apply()
            logger.log("MainActivity", if (!enabled) "已开启前台聊天自动触发" else "已关闭前台聊天自动触发")
            refreshGuideStatus()
            refreshCapabilityStatus()
            logView.text = logger.readRecent()
        }

        findViewById<Button>(R.id.btnOverlaySettings).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
            startActivity(intent)
        }

        refreshCaptureProviderStatus()
        refreshExecutionModeStatus()
        refreshBackendStatus()
        refreshGuideStatus()
        refreshCapabilityStatus()
        logView.text = logger.readRecent()
    }

    override fun onResume() {
        super.onResume()
        refreshCaptureProviderStatus()
        refreshExecutionModeStatus()
        refreshBackendStatus()
        refreshGuideStatus()
        refreshCapabilityStatus()
        logView.text = logger.readRecent()
    }

    private fun refreshCaptureProviderStatus() {
        val provider = CaptureProviderFactory.currentProvider(this)
        captureProviderView.text = "当前截图方案：$provider"
    }

    private fun refreshExecutionModeStatus() {
        val mode = getSharedPreferences("host_config", MODE_PRIVATE)
            .getString("execution_mode", "auto")
            .orEmpty()
        executionModeView.text =
            "当前执行模式：" + if (mode == "manual") {
                "手动模式（只注入，不自动点发送）"
            } else {
                "自动模式（自动截图、注入、发送）"
            }
    }

    private fun refreshBackendStatus() {
        val endpoint = getSharedPreferences("host_config", MODE_PRIVATE)
            .getString("agent_chat_endpoint", "")
            .orEmpty()
            .trim()
        backendStatusView.text =
            if (endpoint.isBlank()) {
                "后端接口：使用默认地址 ${HttpAgentClient.DEFAULT_ENDPOINT}"
            } else if (endpoint == HttpAgentClient.DEFAULT_ENDPOINT) {
                "后端接口：使用默认地址 $endpoint"
            } else {
                "后端接口：使用自定义地址 $endpoint"
            }
    }

    private fun refreshGuideStatus() {
        val prefs = getSharedPreferences("host_config", MODE_PRIVATE)
        val mode = prefs
            .getString("execution_mode", "auto")
            .orEmpty()
        val endpoint = prefs.getString("agent_chat_endpoint", "").orEmpty().trim()
        val endpointText = if (endpoint.isBlank()) "默认地址" else "已配置"
        val modeText = if (mode == "manual") "手动模式" else "自动模式"
        val notifyAutoEnabled = prefs.getBoolean("notification_auto_enabled", false)
        val foregroundAutoEnabled = prefs.getBoolean("foreground_auto_enabled", false)
        val running = prefs.getBoolean("runtime_enabled", false)
        val readiness = computeReadiness()
        currentStatusView.text =
            if (running) {
                "当前状态：正在运行中\n" +
                    "• 执行模式：$modeText\n" +
                    "• 后端接口：$endpointText\n" +
                    "• 前台聊天触发：" + if (foregroundAutoEnabled) "已开启" else "未开启" + "\n" +
                    "• 后台通知触发：" + if (notifyAutoEnabled) "已开启" else "未开启" + "\n" +
                    "• 现在可以切到微信，等待新消息自动分析"
            } else {
                "当前状态：" + if (readiness.readyToRun) "已就绪，可以开始运行" else "尚未就绪" + "\n" +
                    "• 执行模式：$modeText\n" +
                    "• 后端接口：$endpointText\n" +
                    "• 提示：把下面所有红叉项处理成绿勾后，再点击“开始运行”"
            }

        runButton.isEnabled = running || readiness.readyToRun
        runButton.alpha = if (runButton.isEnabled) 1f else 0.45f
        runButton.text = if (running) "停止运行" else "开始运行"
        highlightPrimaryAction(readiness.blockingStep)
    }

    private fun refreshCapabilityStatus() {
        val prefs = getSharedPreferences("host_config", MODE_PRIVATE)
        val accessibilityServiceName = ComponentName(
            this,
            com.agentime.ime.host.automation.WechatAccessibilityService::class.java,
        ).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val accessibilityEnabled = enabledServices.contains(accessibilityServiceName, ignoreCase = true)
        val accessibilityConnected = com.agentime.ime.host.automation.WechatAccessibilityService
            .getForegroundDebugInfo()
            .contains("serviceConnected=true")
        setChecklistStatus(
            accessibilityStatusView,
            accessibilityConnected,
            "无障碍已连接，可自动操作微信",
            if (accessibilityEnabled) "无障碍已开启，但当前未连接，建议关闭后重新打开一次" else "无障碍未开启，请先打开无障碍设置",
        )

        val currentIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        val imeEnabled = currentIme.contains(packageName) && currentIme.contains("AgentImeService")
        setChecklistStatus(
            imeStatusView,
            imeEnabled,
            "默认输入法已切换为 Agent IME",
            "默认输入法不是 Agent IME（当前：${if (currentIme.isBlank()) "未知" else currentIme}）",
        )

        val notifyPermission = WechatNotificationListenerService.isPermissionEnabled(this)
        val notifyConnected = WechatNotificationListenerService.isConnected()
        val notifyAutoEnabled = prefs.getBoolean("notification_auto_enabled", false)
        setChecklistStatus(
            notificationStatusView,
            notifyConnected && notifyAutoEnabled,
            "后台通知自动触发已就绪",
            when {
                notifyPermission && notifyAutoEnabled -> "通知读取已授权，但当前未连接，建议重进设置页确认"
                notifyPermission -> "通知读取已授权，但后台通知自动触发未开启"
                else -> "通知读取权限未开启"
            },
        )

        val foregroundAutoEnabled = prefs.getBoolean("foreground_auto_enabled", false)
        setChecklistStatus(
            foregroundAutoStatusView,
            accessibilityConnected && foregroundAutoEnabled,
            "前台聊天自动触发已就绪",
            when {
                accessibilityEnabled && foregroundAutoEnabled -> "前台聊天自动触发已开启，但无障碍当前未连接"
                else -> "前台聊天自动触发未开启"
            },
        )
    }

    private fun computeReadiness(): Readiness {
        val prefs = getSharedPreferences("host_config", MODE_PRIVATE)
        val projectionReady = ProjectionPermissionStore.hasPermission()
        val accessibilityConnected = com.agentime.ime.host.automation.WechatAccessibilityService
            .getForegroundDebugInfo()
            .contains("serviceConnected=true")
        val currentIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        val imeEnabled = currentIme.contains(packageName) && currentIme.contains("AgentImeService")
        val notifyPermission = WechatNotificationListenerService.isPermissionEnabled(this)
        val notifyConnected = WechatNotificationListenerService.isConnected()
        val notifyAutoEnabled = prefs.getBoolean("notification_auto_enabled", false)
        val foregroundAutoEnabled = prefs.getBoolean("foreground_auto_enabled", false)
        val triggerReady =
            (foregroundAutoEnabled && accessibilityConnected) ||
                (notifyAutoEnabled && notifyPermission && notifyConnected)
        val firstBlockingReason = when {
            !projectionReady -> "请先完成截图授权"
            !accessibilityConnected -> "请先连接无障碍服务"
            !imeEnabled -> "请先把默认输入法切换为 Agent IME"
            !triggerReady -> "请至少开启一种自动触发方式"
            else -> null
        }
        return Readiness(
            readyToRun = projectionReady && accessibilityConnected && imeEnabled && triggerReady,
            firstBlockingReason = firstBlockingReason,
            blockingStep = when {
                !projectionReady -> BlockingStep.PROJECTION
                !accessibilityConnected -> BlockingStep.ACCESSIBILITY
                !imeEnabled -> BlockingStep.IME
                !(notifyPermission) && !(foregroundAutoEnabled) -> BlockingStep.NOTIFICATION_PERMISSION
                !triggerReady && notifyPermission && !notifyAutoEnabled && !foregroundAutoEnabled -> BlockingStep.TRIGGER_MODE
                !triggerReady && foregroundAutoEnabled && !accessibilityConnected -> BlockingStep.ACCESSIBILITY
                !triggerReady -> BlockingStep.TRIGGER_MODE
                else -> null
            },
        )
    }

    private fun setChecklistStatus(view: TextView, ready: Boolean, okText: String, failText: String) {
        view.text = if (ready) "✓ $okText" else "✗ $failText"
        view.setTextColor(Color.parseColor(if (ready) "#1B5E20" else "#B71C1C"))
        view.setBackgroundColor(Color.parseColor(if (ready) "#FFF1F8E9" else "#FFFFEBEE"))
    }

    private fun highlightPrimaryAction(step: BlockingStep?) {
        val normalBg = Color.parseColor("#FFE0E0E0")
        val focusBg = Color.parseColor("#FFFFD54F")
        val normalText = Color.parseColor("#FF102027")
        val focusText = Color.parseColor("#FF5D4037")

        val all = listOf(
            projectionButton,
            accessibilityButton,
            imeButton,
            notificationButton,
            notificationAutoButton,
            foregroundAutoButton,
        )
        all.forEach {
            it.setBackgroundColor(normalBg)
            it.setTextColor(normalText)
        }
        val target = when (step) {
            BlockingStep.PROJECTION -> projectionButton
            BlockingStep.ACCESSIBILITY -> accessibilityButton
            BlockingStep.IME -> imeButton
            BlockingStep.NOTIFICATION_PERMISSION -> notificationButton
            BlockingStep.TRIGGER_MODE -> foregroundAutoButton
            null -> null
        }
        target?.setBackgroundColor(focusBg)
        target?.setTextColor(focusText)
    }

    private fun launchWechatApp() {
        val launch = packageManager.getLaunchIntentForPackage("com.tencent.mm")
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } else {
            Toast.makeText(this, "未找到微信应用", Toast.LENGTH_LONG).show()
            logger.log("MainActivity", "开始运行失败：未找到微信启动 Intent")
        }
    }

    private data class Readiness(
        val readyToRun: Boolean,
        val firstBlockingReason: String?,
        val blockingStep: BlockingStep?,
    )

    private enum class BlockingStep {
        PROJECTION,
        ACCESSIBILITY,
        IME,
        NOTIFICATION_PERMISSION,
        TRIGGER_MODE,
    }
}
