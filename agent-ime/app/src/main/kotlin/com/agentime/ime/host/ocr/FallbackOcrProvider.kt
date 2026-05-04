package com.agentime.ime.host.ocr

import android.util.Log

/**
 * 本地 ML Kit OCR 提供者封装。
 *
 * 历史版本曾包含 Remote OCR 兜底，但自 VLM 引入后
 * 远程 OCR 已被 VLM 视觉大模型完全替代，此处仅保留本地 OCR。
 * 类名保持不变以减少调用方改动（调用方类型签名大量引用 FallbackOcrProvider）。
 */
class FallbackOcrProvider(
    private val local: OcrProvider,
) : OcrProvider {
    // 兼容旧的双参数构造调用：忽略第二个参数（已废弃的 RemoteOcrProvider）
    constructor(local: OcrProvider, @Suppress("UNUSED_PARAMETER") deprecated: OcrProvider) : this(local)

    override fun recognize(imagePath: String, imageUri: String?): String {
        return try {
            local.recognize(imagePath, imageUri)
        } catch (e: Exception) {
            Log.w(TAG, "本地 OCR 异常: ${e.message}")
            ""
        }
    }

    private companion object {
        private const val TAG = "FallbackOcr"
    }
}
