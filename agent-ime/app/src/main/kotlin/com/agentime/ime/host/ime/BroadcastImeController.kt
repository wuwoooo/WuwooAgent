package com.agentime.ime.host.ime

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.agentime.ime.AgentImeService
import com.agentime.ime.InjectTextReceiver

class BroadcastImeController(private val context: Context) : ImeController {
    override fun injectText(text: String): Boolean {
        if (text.isBlank()) return false
        AgentImeService.clearInputBeforeInject()
        if (AgentImeService.commitTextExternal(text)) return true
        val intent = Intent(InjectTextReceiver.ACTION_INJECT_TEXT).apply {
            setClassName(context.packageName, "com.agentime.ime.InjectTextReceiver")
            putExtra(InjectTextReceiver.EXTRA_TEXT, text)
        }
        context.sendBroadcast(intent)
        return true
    }

    override fun isImeActive(): Boolean {
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        ) ?: return false
        return current.contains(context.packageName)
    }
}
