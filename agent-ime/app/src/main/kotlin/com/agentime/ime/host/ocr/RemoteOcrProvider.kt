package com.agentime.ime.host.ocr

import android.content.Context
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class RemoteOcrProvider(private val context: Context) : OcrProvider {
    override fun recognize(imagePath: String, imageUri: String?): String {
        val prefs = context.getSharedPreferences("host_config", Context.MODE_PRIVATE)
        val endpoint = prefs.getString("remote_ocr_endpoint", "") ?: ""
        if (endpoint.isBlank()) throw IllegalStateException("未配置 remote_ocr_endpoint")

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 30000
            setRequestProperty("Content-Type", "application/json")
        }

        val body = JSONObject()
            .put("image_path", imagePath)
            .put("image_uri", imageUri)
            .toString()

        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }
            .orEmpty()

        if (code !in 200..299) throw IllegalStateException("远程 OCR 失败($code): $text")
        val json = JSONObject(text)
        return json.optString("ocr_text", json.optString("text", "")).trim()
    }
}
