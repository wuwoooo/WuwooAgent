package com.agentime.ime.host.capture

import android.content.Context
import android.util.Log

/**
 * 委托 [MediaProjectionSession]：单实例 VirtualDisplay，多次仅抓帧。
 */
class MediaProjectionCaptureManager(private val context: Context) : CaptureController {
    companion object {
        private const val TAG = "MediaProjectionCapture"
    }

    override fun prewarm() {
        MediaProjectionSession.prewarm(context)
    }

    override fun captureScreen(sessionId: String): CaptureResult {
        var lastError: Throwable? = null
        repeat(3) { index ->
            try {
                if (index > 0) {
                    Log.w(TAG, "截图重试 #${index + 1}，重新预热截图管线")
                    Thread.sleep(450L * index)
                    MediaProjectionSession.prewarm(context)
                }
                return MediaProjectionSession.capturePngFile(context, sessionId)
            } catch (e: Throwable) {
                lastError = e
                val msg = e.message.orEmpty()
                val retryable =
                    msg.contains("legacy single shot 未获取到有效图像") ||
                        msg.contains("未获取到有效图像帧") ||
                        msg.contains("截图超时")
                Log.w(TAG, "captureScreen attempt=${index + 1} 失败: $msg")
                if (!retryable || index == 2) {
                    throw e
                }
            }
        }
        throw lastError ?: IllegalStateException("截图失败")
    }
}
