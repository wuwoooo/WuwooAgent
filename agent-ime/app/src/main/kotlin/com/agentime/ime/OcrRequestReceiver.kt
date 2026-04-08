package com.agentime.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors

/**
 * 对聊天截图做设备端 OCR，结果通过广播 [ACTION_OCR_RESULT] 发出。
 *
 * 触发 Action: [ACTION_OCR_IMAGE]
 * Extra（二选一优先 uri）:
 * - [EXTRA_IMAGE_URI]：content:// 或 file:// 字符串
 * - [EXTRA_IMAGE_PATH]：本地绝对路径
 * - [EXTRA_REQUEST_ID]：可选，原样带回，便于脚本并发区分
 *
 * 结果 Action: [ACTION_OCR_RESULT]
 * - [EXTRA_OCR_SUCCESS]：boolean
 * - [EXTRA_OCR_TEXT]：成功时的全文
 * - [EXTRA_OCR_ERROR]：失败时的说明
 * - [EXTRA_REQUEST_ID]：与请求一致
 */
class OcrRequestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_OCR_IMAGE) return
        val pending = goAsync()
        val app = context.applicationContext
        val path = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val uri = intent.getStringExtra(EXTRA_IMAGE_URI)
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)

        ioExecutor.execute {
            try {
                val task = OcrHelper.recognizeText(app, path, uri)
                task.addOnCompleteListener { t ->
                    try {
                        if (t.isSuccessful) {
                            sendResult(app, true, t.result, null, requestId)
                        } else {
                            val msg = t.exception?.message ?: "OCR 任务失败"
                            sendResult(app, false, null, msg, requestId)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            } catch (e: Exception) {
                sendResult(app, false, null, e.message ?: e.toString(), requestId)
                pending.finish()
            }
        }
    }

    private fun sendResult(
        app: Context,
        success: Boolean,
        text: String?,
        error: String?,
        requestId: String?,
    ) {
        val out = Intent(ACTION_OCR_RESULT).apply {
            putExtra(EXTRA_OCR_SUCCESS, success)
            if (success && text != null) putExtra(EXTRA_OCR_TEXT, text)
            if (!success && error != null) putExtra(EXTRA_OCR_ERROR, error)
            if (!requestId.isNullOrBlank()) putExtra(EXTRA_REQUEST_ID, requestId)
        }
        app.sendBroadcast(out)
    }

    companion object {
        const val ACTION_OCR_IMAGE = "com.agentime.ime.action.OCR_IMAGE"
        const val ACTION_OCR_RESULT = "com.agentime.ime.action.OCR_RESULT"
        const val EXTRA_IMAGE_PATH = "image_path"
        const val EXTRA_IMAGE_URI = "image_uri"
        const val EXTRA_OCR_SUCCESS = "success"
        const val EXTRA_OCR_TEXT = "ocr_text"
        const val EXTRA_OCR_ERROR = "error"
        const val EXTRA_REQUEST_ID = "request_id"

        private val ioExecutor = Executors.newSingleThreadExecutor()
    }
}
