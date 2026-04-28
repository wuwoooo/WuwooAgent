package com.agentime.ime.host.agent

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

object SessionIdentity {
    private const val PREFS_NAME = "agent_identity_prefs"
    private const val KEY_DEVICE_ID = "device_id"

    private var cachedDeviceId: String? = null

    private fun getDeviceId(context: Context): String {
        cachedDeviceId?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        cachedDeviceId = id
        return id
    }

    private fun getLevenshteinDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                if (a[i - 1] == b[j - 1]) dp[i][j] = dp[i - 1][j - 1]
                else dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + 1)
            }
        }
        return dp[a.length][b.length]
    }

    internal fun canonicalizeContactName(raw: String?): String {
        var normalized = raw.orEmpty()
            .replace("（", "(")
            .replace("）", ")")
            .trim()

        if (normalized.isBlank()) return "当前联系人"

        while (true) {
            val before = normalized
            normalized = removeTrailingEmojiPlaceholder(normalized)
            normalized = removeTrailingEmojiCodePoints(normalized).trim()
            if (normalized == before) break
            if (normalized.isBlank()) return "当前联系人"
        }

        return normalized.ifBlank { "当前联系人" }
    }

    private fun removeTrailingEmojiPlaceholder(value: String): String {
        return value
            .replace(Regex("""\s*[\[(](?:表情|动画表情|emoji|Emoji|EMOJI)[\])]$"""), "")
            .trimEnd()
    }

    private fun removeTrailingEmojiCodePoints(value: String): String {
        var end = value.length
        while (end > 0) {
            val codePoint = value.codePointBefore(end)
            if (!isTrailingEmojiPart(codePoint)) break
            end -= Character.charCount(codePoint)
        }
        return value.substring(0, end)
    }

    private fun isTrailingEmojiPart(codePoint: Int): Boolean {
        return codePoint == 0x200D ||
            codePoint == 0xFE0F ||
            codePoint in 0x1F000..0x1FAFF ||
            codePoint in 0x2600..0x27BF ||
            codePoint in 0xE0020..0xE007F
    }

    fun normalizeContactName(
        context: Context,
        raw: String?,
        useFuzzyMatch: Boolean = true,
    ): String {
        val trimmed = canonicalizeContactName(raw)
        if (trimmed == "当前联系人") return trimmed

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val knownContactsStr = prefs.getString("known_contacts_list", "") ?: ""
        val knownNames = if (knownContactsStr.isEmpty()) {
            mutableSetOf()
        } else {
            knownContactsStr
                .split(",,")
                .map { canonicalizeContactName(it) }
                .filter { it != "当前联系人" }
                .toMutableSet()
        }
        val canonicalKnownContactsStr = knownNames.joinToString(",,")
        if (canonicalKnownContactsStr != knownContactsStr) {
            prefs.edit().putString("known_contacts_list", canonicalKnownContactsStr).apply()
        }

        if (knownNames.contains(trimmed)) {
            return trimmed
        }

        if (useFuzzyMatch) {
            var bestMatch: String? = null
            var bestDistance = Int.MAX_VALUE
            for (known in knownNames) {
                if (kotlin.math.abs(known.length - trimmed.length) > 2) continue
                val dist = getLevenshteinDistance(known, trimmed)
                if (dist < bestDistance) {
                    bestDistance = dist
                    bestMatch = known
                }
            }

            val threshold = if (trimmed.length <= 4) 1 else 2
            if (bestMatch != null && bestDistance <= threshold) {
                return bestMatch
            }
        }

        knownNames.add(trimmed)
        if (knownNames.size > 100) {
            val iterator = knownNames.iterator()
            for (i in 0 until (knownNames.size - 100)) { 
                if (iterator.hasNext()) { iterator.next(); iterator.remove() }
            }
        }
        prefs.edit().putString("known_contacts_list", knownNames.joinToString(",,")).apply()

        return trimmed
    }

    fun buildSessionId(context: Context, contactName: String): String {
        val normalized = normalizeContactName(context, contactName, useFuzzyMatch = true)
        val deviceId = getDeviceId(context)
        val hashStr = "$deviceId-$normalized"
        
        val md5Hash = try {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(hashStr.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // 极少数情况下 MD5 不可用时，仍然生成一个稳定的本机会话标识。
            hashStr.hashCode().toString(16).trim('-')
        }
        
        return "wx_md5_$md5Hash"
    }
}
