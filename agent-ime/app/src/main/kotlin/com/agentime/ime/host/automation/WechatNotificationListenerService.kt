package com.agentime.ime.host.automation

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.agentime.ime.host.agent.SessionIdentity
import com.agentime.ime.host.orchestrator.HostForegroundService
import com.agentime.ime.host.storage.HostLogger
import java.util.concurrent.atomic.AtomicBoolean

class WechatNotificationListenerService : NotificationListenerService() {
    private lateinit var logger: HostLogger

    override fun onCreate() {
        super.onCreate()
        logger = HostLogger(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected.set(true)
        logger.log(TAG, "通知监听已连接")
    }

    override fun onListenerDisconnected() {
        connected.set(false)
        logger.log(TAG, "通知监听已断开")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        if (sbn.packageName != WECHAT_PACKAGE) return

        val prefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
        val executionMode = prefs.getString("execution_mode", "auto").orEmpty()
        val autoEnabled = prefs.getBoolean(PREF_NOTIFY_AUTO_ENABLED, false)
        val runtimeEnabled = prefs.getBoolean(PREF_RUNTIME_ENABLED, false)
        if (executionMode != "auto" || !autoEnabled || !runtimeEnabled) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isBlank() || text.isBlank()) {
            logger.log(TAG, "忽略微信通知：title/text 为空 key=${sbn.key}")
            return
        }

        val signature = "${sbn.packageName}|$title|$text"
        val now = System.currentTimeMillis()
        val lastSig = prefs.getString(PREF_LAST_NOTIFY_SIG, "").orEmpty()
        val lastTs = prefs.getLong(PREF_LAST_NOTIFY_TS, 0L)
        if (signature == lastSig && now - lastTs < 8_000L) {
            logger.log(TAG, "忽略重复微信通知：$title")
            return
        }
        prefs.edit()
            .putString(PREF_LAST_NOTIFY_SIG, signature)
            .putLong(PREF_LAST_NOTIFY_TS, now)
            .apply()

        logger.log(TAG, "捕获微信通知：title=$title text=${text.take(40)}")
        if (WechatAccessibilityService.isLikelyOnChatPage()) {
            logger.log(TAG, "当前已处于微信聊天页，忽略通知触发以避免通知横幅干扰识别或切换会话：$title")
            return
        }

        val contentIntent = sbn.notification.contentIntent
        if (contentIntent == null) {
            logger.log(TAG, "微信通知缺少 contentIntent，无法自动打开会话：$title")
            return
        }

        runCatching { contentIntent.send() }
            .onFailure {
                logger.log(TAG, "打开微信通知会话失败：${it.message}")
                return
            }

        val normalizedTitle = SessionIdentity.normalizeContactName(this, title)
        val sessionId = SessionIdentity.buildSessionId(this, normalizedTitle)
        Handler(Looper.getMainLooper()).postDelayed({
            val currentPrefs = getSharedPreferences("host_config", Context.MODE_PRIVATE)
            if (!currentPrefs.getBoolean(PREF_RUNTIME_ENABLED, false)) {
                logger.log(TAG, "通知自动触发已被停止，取消本次 runOnce: $title")
                return@postDelayed
            }
            val intent = Intent(this, HostForegroundService::class.java).apply {
                action = HostForegroundService.ACTION_RUN_ONCE
                putExtra(HostForegroundService.EXTRA_SESSION_ID, sessionId)
                putExtra(HostForegroundService.EXTRA_CONTACT_NAME, normalizedTitle)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            logger.log(TAG, "已根据微信通知触发 runOnce: $sessionId/$title")
        }, 1800L)
    }

    companion object {
        private const val TAG = "WechatNotifyListener"
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val PREF_NOTIFY_AUTO_ENABLED = "notification_auto_enabled"
        private const val PREF_RUNTIME_ENABLED = "runtime_enabled"
        private const val PREF_LAST_NOTIFY_SIG = "last_notify_signature"
        private const val PREF_LAST_NOTIFY_TS = "last_notify_timestamp"
        private val connected = AtomicBoolean(false)

        fun isConnected(): Boolean = connected.get()

        fun isPermissionEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            val component = ComponentName(
                context,
                WechatNotificationListenerService::class.java,
            ).flattenToString()
            return enabled.contains(component, ignoreCase = true)
        }
    }
}
