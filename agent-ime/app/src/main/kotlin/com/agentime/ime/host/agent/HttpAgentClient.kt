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
    }

    override fun chat(imagePath: String, ocrText: String, sessionId: String, contactName: String): AgentReply {
        val prefs = context.getSharedPreferences("host_config", Context.MODE_PRIVATE)
        val endpoint = prefs.getString("agent_chat_endpoint", "")?.trim().orEmpty().ifBlank { DEFAULT_ENDPOINT }

        val boundary = "----AgentImeFormBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 30000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        conn.outputStream.use { out ->
            writeMultipartField(out, boundary, "ocr_text", ocrText)
            writeMultipartField(out, boundary, "session_id", sessionId)
            writeMultipartField(out, boundary, "contact_name", contactName)
            val end = "--$boundary--\r\n"
            out.write(end.toByteArray(Charsets.UTF_8))
        }

        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }
            .orEmpty()

        if (code !in 200..299) throw IllegalStateException("Agent 请求失败($code): $text")
        val json = JSONObject(text)
        val replyText = json.optString("reply_text", "").trim()
        return AgentReply(replyText = replyText, raw = text)
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
