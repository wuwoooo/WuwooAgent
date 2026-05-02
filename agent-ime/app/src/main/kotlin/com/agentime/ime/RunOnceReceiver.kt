package com.agentime.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.agentime.ime.host.orchestrator.HostForegroundService

/**
 * 允许在微信前台通过广播直接触发任务，避免为了点主界面按钮切回 agent-ime。
 */
class RunOnceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val incomingAction = intent.action
        if (
            incomingAction != HostForegroundService.ACTION_RUN_ONCE &&
            incomingAction != HostForegroundService.ACTION_RUN_OUTBOUND_TASK
        ) return

        val serviceIntent = Intent(context, HostForegroundService::class.java).apply {
            action = incomingAction
            if (incomingAction == HostForegroundService.ACTION_RUN_ONCE) {
                putExtra(
                    HostForegroundService.EXTRA_SESSION_ID,
                    intent.getStringExtra(HostForegroundService.EXTRA_SESSION_ID),
                )
                putExtra(
                    HostForegroundService.EXTRA_CONTACT_NAME,
                    intent.getStringExtra(HostForegroundService.EXTRA_CONTACT_NAME),
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
