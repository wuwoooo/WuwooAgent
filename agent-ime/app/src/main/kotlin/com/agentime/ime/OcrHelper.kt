package com.agentime.ime

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.android.gms.tasks.Task

/**
 * 使用 ML Kit 中文文本识别（设备端，无需上传云端）。
 */
object OcrHelper {

    private val recognizer by lazy {
        TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build(),
        )
    }

    /**
     * @param imagePath 本地绝对路径，例如 Auto.js 写入的截图路径
     * @param imageUriString 可选 content:// 或 file:// 字符串，与 path 二选一，优先 uri
     */
    fun recognizeText(
        context: Context,
        imagePath: String?,
        imageUriString: String?,
    ): Task<String> {
        val app = context.applicationContext
        val inputImage = buildInputImage(app, imagePath, imageUriString)
        return recognizer.process(inputImage).continueWith { task ->
            if (!task.isSuccessful) {
                throw task.exception ?: IllegalStateException("OCR 失败")
            }
            val vision = task.result ?: return@continueWith ""
            vision.text.trim()
        }
    }

    private fun buildInputImage(
        context: Context,
        imagePath: String?,
        imageUriString: String?,
    ): InputImage {
        val bitmap = when {
            !imageUriString.isNullOrBlank() -> {
                val uri = Uri.parse(imageUriString)
                context.contentResolver.openInputStream(uri).use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: throw IllegalArgumentException("无法解码 image_uri")
            }
            !imagePath.isNullOrBlank() -> {
                BitmapFactory.decodeFile(imagePath)
                    ?: throw IllegalArgumentException("无法解码 image_path")
            }
            else -> throw IllegalArgumentException("请提供 image_uri 或 image_path")
        }
        // 旋转角可由后续根据 EXIF 扩展；PoC 使用 0
        return InputImage.fromBitmap(bitmap, 0)
    }
}
