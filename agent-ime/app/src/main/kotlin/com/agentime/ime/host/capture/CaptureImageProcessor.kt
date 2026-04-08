package com.agentime.ime.host.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object CaptureImageProcessor {
    private const val TAG = "CaptureImageProcessor"

    fun processBitmap(
        context: Context,
        bitmap: Bitmap,
        rawBitmap: Bitmap? = null,
        sessionId: String,
        debugSummary: String? = null,
        captureTrace: String? = null,
        acceptableForOcr: Boolean = false,
        sharpnessScore: Double = 0.0,
        totalScore: Double = 0.0,
    ): CaptureResult {
        val outDir = File(context.filesDir, "captures").apply { mkdirs() }
        val fileName = "cap_${sessionId}_${System.currentTimeMillis()}.png"
        val out = File(outDir, fileName)
        val pngBytes = encodeBitmapPng(bitmap)
        FileOutputStream(out).use { fos -> fos.write(pngBytes) }
        val rawImagePath: String?
        val rawExportedPath: String?
        if (rawBitmap != null) {
            val rawFileName = fileName.removeSuffix(".png") + "_raw.png"
            val rawPngBytes = runCatching { encodeBitmapPng(rawBitmap) }
                .onFailure { Log.w(TAG, "raw 图编码失败: ${it.message}", it) }
                .getOrNull()
            if (rawPngBytes != null) {
                val rawFile = File(outDir, rawFileName)
                FileOutputStream(rawFile).use { fos -> fos.write(rawPngBytes) }
                rawImagePath = rawFile.absolutePath
                rawExportedPath = exportPublicCopy(context, rawFileName, rawPngBytes)
            } else {
                rawImagePath = null
                rawExportedPath = null
            }
        } else {
            rawImagePath = null
            rawExportedPath = null
        }
        val exportedPath = exportPublicCopy(context, fileName, pngBytes)
        val chatCrop = createChatCrop(bitmap)
        val chatCropPath = saveBitmapIfPresent(chatCrop, outDir, fileName, "chatcrop")
        val enhancedChatCrop = chatCrop?.let(::createEnhancedBitmap)
        val enhancedChatCropPath = saveBitmapIfPresent(enhancedChatCrop, outDir, fileName, "enhanced")
        chatCrop?.recycle()
        enhancedChatCrop?.recycle()

        return CaptureResult(
            imagePath = out.absolutePath,
            rawImagePath = rawImagePath,
            rawExportedPath = rawExportedPath,
            exportedPath = exportedPath,
            debugSummary = debugSummary,
            captureTrace = captureTrace,
            chatCropPath = chatCropPath,
            enhancedChatCropPath = enhancedChatCropPath,
            acceptableForOcr = acceptableForOcr,
            sharpnessScore = sharpnessScore,
            totalScore = totalScore,
        )
    }

    fun processExistingPng(
        context: Context,
        imagePath: String,
        sessionId: String,
        debugSummary: String? = null,
        captureTrace: String? = null,
    ): CaptureResult {
        val bitmap = BitmapFactory.decodeFile(imagePath)
            ?: throw IllegalStateException("无法解码截图文件: $imagePath")
        try {
            return processBitmap(
                context = context,
                bitmap = bitmap,
                rawBitmap = null,
                sessionId = sessionId,
                debugSummary = debugSummary,
                captureTrace = captureTrace,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun createChatCrop(bitmap: Bitmap): Bitmap? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 50 || height < 50) return null

        val left = (width * 0.05f).toInt().coerceAtLeast(0)
        val top = (height * 0.10f).toInt().coerceAtLeast(0)
        val right = (width * 0.95f).toInt().coerceAtMost(width)
        val bottom = (height * 0.84f).toInt().coerceAtMost(height)
        val cropWidth = (right - left).coerceAtLeast(10)
        val cropHeight = (bottom - top).coerceAtLeast(10)
        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    }

    private fun createEnhancedBitmap(source: Bitmap): Bitmap {
        val scaled = Bitmap.createScaledBitmap(source, source.width * 2, source.height * 2, false)
        val width = scaled.width
        val height = scaled.height
        val gray = IntArray(width * height)

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val c = scaled.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                gray[y * width + x] = (r * 30 + g * 59 + b * 11) / 100
                x++
            }
            y++
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val idx = y * width + x
                val center = gray[idx]
                val left = gray[y * width + maxOf(0, x - 1)]
                val right = gray[y * width + minOf(width - 1, x + 1)]
                val up = gray[maxOf(0, y - 1) * width + x]
                val down = gray[minOf(height - 1, y + 1) * width + x]

                val sharpened = (5 * center - left - right - up - down).coerceIn(0, 255)
                val boosted = (((sharpened - 128) * 1.85f) + 128f).toInt().coerceIn(0, 255)
                val normalized = when {
                    boosted > 185 -> 255
                    boosted < 70 -> 0
                    else -> boosted
                }
                val color = (0xFF shl 24) or (normalized shl 16) or (normalized shl 8) or normalized
                out.setPixel(x, y, color)
                x++
            }
            y++
        }

        scaled.recycle()
        return out
    }

    private fun saveBitmapIfPresent(
        bitmap: Bitmap?,
        outDir: File,
        baseFileName: String,
        suffix: String,
    ): String? {
        if (bitmap == null) return null
        val target = File(outDir, baseFileName.removeSuffix(".png") + "_$suffix.png")
        FileOutputStream(target).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        return target.absolutePath
    }

    private fun encodeBitmapPng(bitmap: Bitmap): ByteArray {
        return ByteArrayOutputStream().use { bos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bos.toByteArray()
        }
    }

    private fun exportPublicCopy(context: Context, fileName: String, pngBytes: ByteArray): String? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "image/png")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/agent-ime")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法创建 MediaStore 下载项")
                resolver.openOutputStream(uri)?.use { it.write(pngBytes) }
                    ?: error("无法打开导出输出流")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Log.i(TAG, "截图已导出到公共下载目录: $uri")
                "Download/agent-ime/$fileName"
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "agent-ime",
                ).apply { mkdirs() }
                val out = File(dir, fileName)
                FileOutputStream(out).use { it.write(pngBytes) }
                Log.i(TAG, "截图已导出到公共下载目录: ${out.absolutePath}")
                out.absolutePath
            }
        }.onFailure {
            Log.w(TAG, "导出公共截图失败: ${it.message}", it)
        }.getOrNull()
    }
}
