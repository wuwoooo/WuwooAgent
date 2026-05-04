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
            val loginUrls = buildLoginUrls(endpoint.ifBlank { DEFAULT_ENDPOINT })
            val prefs = context.getSharedPreferences("host_config", Context.MODE_PRIVATE)
            val deviceId = SessionIdentity.getDeviceId(context)
            val payload = JSONObject().apply {
                put("username", username)
                put("password", password)
                put("device_id", deviceId)
            }.toString()

            var lastError = ""
            var responseText = ""
            for (loginUrl in loginUrls) {
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
                if (code in 200..299) {
                    responseText = text
                    break
                }
                lastError = "Agent 登录失败($code): $text"
                if (code != 404) {
                    throw IllegalStateException(lastError)
                }
            }
            if (responseText.isBlank()) throw IllegalStateException(lastError.ifBlank { "Agent 登录失败：登录接口无响应" })

            val json = JSONObject(responseText)
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

        private fun buildLoginUrls(endpoint: String): List<String> {
            return buildApiUrls(endpoint, "agent/login")
        }

        private fun buildApiUrls(endpoint: String, path: String): List<String> {
            val trimmed = endpoint.trim().trimEnd('/')
            val base = when {
                trimmed.endsWith("/api/wechat/chat") -> trimmed.removeSuffix("/api/wechat/chat")
                trimmed.endsWith("/wechat/chat") -> trimmed.removeSuffix("/wechat/chat")
                else -> trimmed
            }
            val cleanPath = path.trimStart('/')
            return listOf("$base/api/$cleanPath", "$base/$cleanPath").distinct()
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
            connectTimeout = 15000
            readTimeout = 60000
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
            if (imagePath.isNotBlank()) {
                val file = java.io.File(imagePath)
                if (file.exists()) {
                    writeMultipartFile(out, boundary, "image", file.name, file.readBytes())
                }
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
        val isGroupChat = json?.optBoolean("is_group_chat", false) ?: false
        return AgentReply(
            replyText = replyText,
            raw = text,
            silenced = silenced,
            reason = reason,
            currentStatus = currentStatus,
            isGroupChat = isGroupChat,
        )
    }

    fun claimNextOutboundTask(): OutboundTask? {
        val prefs = context.getSharedPreferences("host_config", Context.MODE_PRIVATE)
        val endpoint = prefs.getString("agent_chat_endpoint", "")?.trim().orEmpty().ifBlank { DEFAULT_ENDPOINT }
        val token = prefs.getString("agent_access_token", "")?.trim().orEmpty()
        if (token.isBlank()) throw IllegalStateException("Agent 账号未登录，请先在主界面输入用户名和密码登录")

        var lastError = ""
        for (url in buildApiUrls(endpoint, "agent/outbound-tasks/next")) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 20000
                setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
                .orEmpty()
            if (code in 200..299) {
                val taskJson = JSONObject(text).optJSONObject("task") ?: return null
                return OutboundTask.fromJson(taskJson)
            }
            lastError = "领取主动外发任务失败($code): $text"
            if (code != 404) throw IllegalStateException(lastError)
        }
        if (lastError.isNotBlank()) throw IllegalStateException(lastError)
        return null
    }

    fun completeOutboundTask(taskId: Long, success: Boolean, error: String = "") {
        val prefs = context.getSharedPreferences("host_config", Context.MODE_PRIVATE)
        val endpoint = prefs.getString("agent_chat_endpoint", "")?.trim().orEmpty().ifBlank { DEFAULT_ENDPOINT }
        val token = prefs.getString("agent_access_token", "")?.trim().orEmpty()
        if (token.isBlank()) throw IllegalStateException("Agent 账号未登录，请先在主界面输入用户名和密码登录")

        val payload = JSONObject().apply {
            put("success", success)
            put("error", error.take(500))
        }.toString()
        var lastError = ""
        for (url in buildApiUrls(endpoint, "agent/outbound-tasks/$taskId/result")) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10000
                readTimeout = 20000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { out -> out.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
                .orEmpty()
            if (code in 200..299) return
            lastError = "回报主动外发任务失败($code): $text"
            if (code != 404) throw IllegalStateException(lastError)
        }
        if (lastError.isNotBlank()) throw IllegalStateException(lastError)
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

    private fun writeMultipartFile(out: OutputStream, boundary: String, name: String, fileName: String, fileBytes: ByteArray) {
        val part = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"").append(name).append("\"; filename=\"").append(fileName).append("\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }
        out.write(part.toByteArray(Charsets.UTF_8))
        out.write(fileBytes)
        out.write("\r\n".toByteArray(Charsets.UTF_8))
    }
}

data class AgentLoginResult(
    val agentId: Int,
    val username: String,
    val displayName: String,
    val accessToken: String,
)

data class OutboundTask(
    val id: Long,
    val sessionId: String,
    val contactName: String,
    val searchKeyword: String,
    val message: String,
    val autoSend: Boolean,
) {
    companion object {
        fun fromJson(json: JSONObject): OutboundTask {
            return OutboundTask(
                id = json.optLong("id"),
                sessionId = json.optString("session_id", "").trim(),
                contactName = json.optString("contact_name", "").trim(),
                searchKeyword = json.optString("search_keyword", "").trim(),
                message = json.optString("message", "").trim(),
                autoSend = json.optBoolean("auto_send", false),
            )
        }
    }
}
