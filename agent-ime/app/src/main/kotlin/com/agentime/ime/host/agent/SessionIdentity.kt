package com.agentime.ime.host.agent

import java.util.Locale

object SessionIdentity {
    fun normalizeContactName(raw: String?): String {
        return raw.orEmpty().trim().ifBlank { "当前联系人" }
    }

    fun buildSessionId(contactName: String): String {
        val normalized = normalizeContactName(contactName)
        val base = normalized.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), "_")
            .trim('_')
            .ifBlank { "wechat_contact" }
        return "wx_contact_$base"
    }
}
