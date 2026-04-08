package com.agentime.ime.host.ocr

import android.util.Log

/**
 * 先本地 ML Kit；失败或 **空串** 时再试远程（首帧黑屏时本地常返回空，不会抛异常）。
 */
class FallbackOcrProvider(
    private val local: OcrProvider,
    private val remote: OcrProvider,
) : OcrProvider {
    override fun recognize(imagePath: String, imageUri: String?): String {
        val fromLocal = try {
            local.recognize(imagePath, imageUri)
        } catch (e: Exception) {
            Log.w(TAG, "本地 OCR 异常，改远程: ${e.message}")
            return tryRemote(imagePath, imageUri)
        }
        if (fromLocal.isNotBlank()) return fromLocal
        Log.w(TAG, "本地 OCR 结果为空，尝试远程兜底")
        return tryRemote(imagePath, imageUri)
    }

    private fun tryRemote(imagePath: String, imageUri: String?): String {
        return try {
            remote.recognize(imagePath, imageUri)
        } catch (e: Exception) {
            Log.w(TAG, "远程 OCR 不可用或失败: ${e.message}")
            ""
        }
    }

    private companion object {
        private const val TAG = "FallbackOcr"
    }
}
