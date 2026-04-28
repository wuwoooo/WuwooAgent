package com.agentime.ime.host.agent

import android.content.Context
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class HttpAgentClient(private val context: Context) : AgentClient {
    companion object {
        const val DEFAULT_ENDPOINT = "http://118.24.71.189/api/wechat/chat"

        fun login(context: Context, endpoint: String, username: String, password: String): AgentLoginResult {
            val loginUrl = buildLoginUrl(endpoint.ifBlank { DEFAULT_ENDPOINT })
            val prefs = context.getSharedPreferences("host_config", Context.MODE_PRIVATE)
            val deviceId = SessionIdentity.getDeviceId(context)
            val payload = JSONObject().apply {
                put("username", username)
                put("password", password)
                put("device_id", deviceId)
            }.toString()

            val conn = (URL(loginUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10000
                readTimeout = 30000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) throw IllegalStateException("Agent 登录失败($code): $text")

            val json = JSONObject(text)
            val token = json.optString("access_token", "").trim()
            val agent = json.optJSONObject("agent")
            if (token.isBlank() || agent == null) {
                throw IllegalStateException("登录接口返回缺少 access_token 或 agent")
            }

            val result = AgentLoginResult(
                agentId = agent.optInt("id", 0),
                username = agent.optString("username", username).trim(),
                displayName = agent.optString("display_name", username).trim(),
                accessToken = token,
            )
            prefs.edit()
                .putString("agent_chat_endpoint", endpoint.ifBlank { DEFAULT_ENDPOINT })
                .putString("agent_username", result.username)
                .putString("agent_display_name", result.displayName)
                .putInt("agent_id", result.agentId)
                .putString("agent_access_token", result.accessToken)
                .apply()
            return result
        }

        private fun buildLoginUrl(endpoint: String): String {
            val trimmed = endpoint.trim().trimEnd('/')
            return when {
                trimmed.endsWith("/api/wechat/chat") -> trimmed.removeSuffix("/api/wechat/chat") + "/api/agent/login"
                trimmed.endsWith("/wechat/chat") -> trimmed.removeSuffix("/wechat/chat") + "/api/agent/login"
                else -> "$trimmed/api/agent/login"
            }
        }
    }

    override fun chat(imagePath: String, ocrText: String, sessionId: String, contactName: String, isHumanReply: Boolean): AgentReply {
        val prefs = context.getSharedPreferences("host_config", Context.MODE_PRIVATE)
        val endpoint = prefs.getString("agent_chat_endpoint", "")?.trim().orEmpty().ifBlank { DEFAULT_ENDPOINT }
        val token = prefs.getString("agent_access_token", "")?.trim().orEmpty()
        if (token.isBlank()) {
            throw IllegalStateException("Agent 账号未登录，请先在主界面输入用户名和密码登录")
        }

        val boundary = "----AgentImeFormBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 30000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Authorization", "Bearer $token")
        }

        conn.outputStream.use { out ->
            writeMultipartField(out, boundary, "ocr_text", ocrText)
            writeMultipartField(out, boundary, "session_id", sessionId)
            writeMultipartField(out, boundary, "contact_name", contactName)
            if (isHumanReply) {
                writeMultipartField(out, boundary, "is_human_reply", "true")
            }
            val end = "--$boundary--\r\n"
            out.write(end.toByteArray(Charsets.UTF_8))
        }

        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }
            .orEmpty()

        if (code !in 200..299) throw IllegalStateException("Agent 请求失败($code): $text")
        val json = if (text.isBlank()) null else try {
            JSONObject(text)
        } catch (e: Exception) {
            null
        }
        val replyText = json?.optString("reply_text", "")?.trim().orEmpty()
        val silenced = json?.optBoolean("silenced", false) ?: false
        val reason = json?.optString("reason", "")?.trim().orEmpty()
        val currentStatus = json?.optString("current_status", "")?.trim().orEmpty()
        return AgentReply(
            replyText = replyText,
            raw = text,
            silenced = silenced,
            reason = reason,
            currentStatus = currentStatus,
        )
    }

    private fun writeMultipartField(out: OutputStream, boundary: String, name: String, value: String) {
        val part = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n")
            append("\r\n")
            append(value).append("\r\n")
        }
        out.write(part.toByteArray(Charsets.UTF_8))
    }
}

data class AgentLoginResult(
    val agentId: Int,
    val username: String,
    val displayName: String,
    val accessToken: String,
)
