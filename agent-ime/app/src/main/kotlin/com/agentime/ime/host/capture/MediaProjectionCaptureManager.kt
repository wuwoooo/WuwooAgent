package com.agentime.ime.host.capture

import android.content.Context
import android.util.Log

/**
 * 委托 [MediaProjectionSession]：单实例 VirtualDisplay，多次仅抓帧。
 *
 * Android 14 约束：
 * - [android.media.projection.MediaProjectionManager.getMediaProjection] 的 resultCode/data 是一次性令牌，
 *   只能调用一次；调用后 token 即消耗，tearDown 后必须重新授权才能截图。
 * - [android.media.projection.MediaProjection.createVirtualDisplay] 同一实例只能调用一次。
 *
 * 因此重试策略改为：**不销毁重建管线**，只重试截帧逻辑；
 * 若截帧持续失败，抛出异常由上层决定是否提示用户重新授权。
 */
class MediaProjectionCaptureManager(private val context: Context) : CaptureController {
    companion object {
        private const val TAG = "MediaProjectionCapture"
        /** 纯截帧失败（无帧可读）时的最大重试次数，重试间不重建管线。 */
        // 单次调用内不做长时间重试，失败快速返回给上层（Host 会在短延时后重扫），
        // 避免一次截图把链路卡住 30~60 秒。
        private const val MAX_FRAME_RETRIES = 0
    }

    override fun prewarm() {
        MediaProjectionSession.prewarm(context)
    }

    override fun captureScreen(sessionId: String): CaptureResult {
        var lastError: Throwable? = null
        repeat(MAX_FRAME_RETRIES + 1) { index ->
            try {
                if (index > 0) {
                    val delayMs = 1200L * index
                    Log.w(TAG, "截图重试 #${index + 1}/${MAX_FRAME_RETRIES + 1}，不重建管线，等待 ${delayMs}ms 后重新抓帧")
                    Thread.sleep(delayMs)
                }
                return MediaProjectionSession.capturePngFile(context, sessionId)
            } catch (e: Throwable) {
                lastError = e
                val msg = e.message.orEmpty()
                // 授权/管线级别的错误：无法通过重试修复，立即上抛
                val isTerminal = msg.contains("未授权录屏") ||
                    msg.contains("授权已失效") ||
                    msg.contains("non-current MediaProjection", ignoreCase = true) ||
                    msg.contains("Don't re-use", ignoreCase = true) ||
                    msg.contains("multiple captures", ignoreCase = true) ||
                    msg.contains("MediaProjection 初始化失败") ||
                    msg.contains("录屏授权数据丢失")
                if (isTerminal) {
                    // 统一包装成「授权已失效」，方便上层识别
                    throw IllegalStateException("截图授权已失效，请回到主界面重新授权截图", e)
                }
                // 仅截帧超时类失败才允许重试
                val retryable = msg.contains("legacy single shot 未获取到有效图像") ||
                    msg.contains("未获取到有效图像帧") ||
                    msg.contains("截图超时")
                Log.w(TAG, "captureScreen 第 ${index + 1} 次失败: $msg retryable=$retryable")
                if (!retryable || index == MAX_FRAME_RETRIES) {
                    throw e
                }
            }
        }
        throw lastError ?: IllegalStateException("截图失败")
    }
}
