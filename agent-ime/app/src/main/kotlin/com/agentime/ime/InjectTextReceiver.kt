package com.agentime.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收广播并调用 [AgentImeService.commitTextExternal]。
 *
 * Action: [ACTION_INJECT_TEXT]
 * Extra: [EXTRA_TEXT] 或 [EXTRA_REPLY_TEXT]（优先使用前者）
 *
 * adb 示例见 scripts/adb-inject.sh
 */
class InjectTextReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INJECT_TEXT) return
        val text = intent.getStringExtra(EXTRA_TEXT)
            ?: intent.getStringExtra(EXTRA_REPLY_TEXT)
            ?: return
        if (text.isEmpty()) return
        AgentImeService.commitTextExternal(text)
    }

    companion object {
        const val ACTION_INJECT_TEXT = "com.agentime.ime.action.INJECT_TEXT"
        const val EXTRA_TEXT = "text"
        const val EXTRA_REPLY_TEXT = "reply_text"
    }
}
