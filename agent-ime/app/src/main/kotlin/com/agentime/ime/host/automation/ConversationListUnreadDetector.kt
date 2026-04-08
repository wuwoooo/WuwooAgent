package com.agentime.ime.host.automation

import android.graphics.BitmapFactory

object ConversationListUnreadDetector {
    data class ListPageAnalysis(
        val looksLikeListPage: Boolean,
        val debugSummary: String,
    )

    data class Hit(
        val tapX: Float,
        val tapY: Float,
        val redScore: Int,
    )

    fun analyzeConversationListPage(imagePath: String, ocrText: String): ListPageAnalysis {
        val bitmap = BitmapFactory.decodeFile(imagePath)
            ?: return ListPageAnalysis(false, "bitmap_decode_failed")
        try {
            val width = bitmap.width
            val height = bitmap.height
            if (width < 200 || height < 400) {
                return ListPageAnalysis(false, "bitmap_too_small=${width}x$height")
            }

            val normalized = ocrText
                .replace("（", "(")
                .replace("）", ")")
                .replace(" ", "")
            val titleLooksLikeList = normalized.contains("微信(") ||
                normalized.lines().take(4).any { line ->
                    val trimmed = line.trim()
                    trimmed == "微信" || trimmed.startsWith("微信(")
                }
            val bottomTabHitCount = listOf("微信", "通讯录", "发现", "我").count { normalized.contains(it) }
            val inputBarSignals = listOf("按住说话", "切换到键盘", "发送", "表情", "更多功能", "更多")
            val inputBarHitCount = inputBarSignals.count { normalized.contains(it) }

            val topHeaderLight = sampleLightRatio(
                bitmap,
                (width * 0.10f).toInt(),
                (height * 0.05f).toInt(),
                (width * 0.90f).toInt(),
                (height * 0.14f).toInt(),
            )
            val bottomBarLight = sampleLightRatio(
                bitmap,
                (width * 0.05f).toInt(),
                (height * 0.86f).toInt(),
                (width * 0.95f).toInt(),
                (height * 0.98f).toInt(),
            )
            val bottomBarNonWhite = sampleNonWhiteRatio(
                bitmap,
                (width * 0.05f).toInt(),
                (height * 0.86f).toInt(),
                (width * 0.95f).toInt(),
                (height * 0.98f).toInt(),
            )
            val bottomCenterNonWhite = sampleNonWhiteRatio(
                bitmap,
                (width * 0.18f).toInt(),
                (height * 0.88f).toInt(),
                (width * 0.82f).toInt(),
                (height * 0.97f).toInt(),
            )
            val leftAvatarNonWhite = sampleNonWhiteRatio(
                bitmap,
                (width * 0.02f).toInt(),
                (height * 0.14f).toInt(),
                (width * 0.20f).toInt(),
                (height * 0.42f).toInt(),
            )
            val looksLikeListPage =
                titleLooksLikeList &&
                    bottomTabHitCount >= 3 &&
                    inputBarHitCount == 0 &&
                    topHeaderLight >= 0.78 &&
                    bottomBarLight >= 0.70 &&
                    bottomBarNonWhite >= 0.02 &&
                    bottomCenterNonWhite >= 0.02 &&
                    leftAvatarNonWhite >= 0.10

            val summary =
                "titleLooksLikeList=$titleLooksLikeList " +
                    "bottomTabHitCount=$bottomTabHitCount " +
                    "inputBarHitCount=$inputBarHitCount " +
                    "topHeaderLight=${"%.2f".format(topHeaderLight)} " +
                    "bottomBarLight=${"%.2f".format(bottomBarLight)} " +
                    "bottomBarNonWhite=${"%.2f".format(bottomBarNonWhite)} " +
                    "bottomCenterNonWhite=${"%.2f".format(bottomCenterNonWhite)} " +
                    "leftAvatarNonWhite=${"%.2f".format(leftAvatarNonWhite)}"

            return ListPageAnalysis(looksLikeListPage, summary)
        } finally {
            bitmap.recycle()
        }
    }

    fun findTopUnreadConversation(imagePath: String): Hit? {
        val bitmap = BitmapFactory.decodeFile(imagePath) ?: return null
        try {
            val width = bitmap.width
            val height = bitmap.height
            if (width < 200 || height < 400) return null

            // 只看头像右上侧的小徽标区域，避免把第二行红色头像本体误判成未读红点。
            val xStart = (width * 0.095f).toInt()
            val xEnd = (width * 0.195f).toInt()
            val yStart = (height * 0.10f).toInt()
            val yEnd = (height * 0.40f).toInt()
            val bucketHeight = (height * 0.05f).toInt().coerceAtLeast(28)
            val bucketCount = ((yEnd - yStart) / bucketHeight).coerceAtLeast(1)
            val counts = IntArray(bucketCount)
            val sumY = IntArray(bucketCount)
            val sumX = IntArray(bucketCount)

            var y = yStart
            while (y < yEnd) {
                var x = xStart
                while (x < xEnd) {
                    val c = bitmap.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (isUnreadRed(r, g, b)) {
                        val bucket = ((y - yStart) / bucketHeight).coerceIn(0, bucketCount - 1)
                        counts[bucket] += 1
                        sumY[bucket] += y
                        sumX[bucket] += x
                    }
                    x += 2
                }
                y += 2
            }

            val bestIndex = counts.indices.maxByOrNull { counts[it] } ?: return null
            val bestScore = counts[bestIndex]
            if (bestScore < 10) return null

            val avgY = if (bestScore > 0) sumY[bestIndex] / bestScore else yStart + bestIndex * bucketHeight
            val avgX = if (bestScore > 0) sumX[bestIndex] / bestScore else xStart + (xEnd - xStart) / 2
            val rowHeight = (height * 0.094f).toInt().coerceAtLeast(140)
            val rowCenterY = (avgY.toFloat() + rowHeight * 0.42f).coerceIn(
                height * 0.16f,
                height * 0.62f,
            )
            return Hit(
                tapX = (width * 0.52f).coerceAtLeast(avgX.toFloat() + width * 0.24f),
                tapY = rowCenterY.toFloat(),
                redScore = bestScore,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun isUnreadRed(r: Int, g: Int, b: Int): Boolean {
        return r >= 185 && g <= 125 && b <= 125 &&
            (r - g) >= 55 && (r - b) >= 55
    }

    private fun sampleLightRatio(bitmap: android.graphics.Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var total = 0
        var hit = 0
        var y = y0.coerceAtLeast(0)
        while (y < y1.coerceAtMost(bitmap.height)) {
            var x = x0.coerceAtLeast(0)
            while (x < x1.coerceAtMost(bitmap.width)) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val light = (r + g + b) / 3
                if (light >= 210) hit++
                total++
                x += 6
            }
            y += 6
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }

    private fun sampleNonWhiteRatio(bitmap: android.graphics.Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var total = 0
        var hit = 0
        var y = y0.coerceAtLeast(0)
        while (y < y1.coerceAtMost(bitmap.height)) {
            var x = x0.coerceAtLeast(0)
            while (x < x1.coerceAtMost(bitmap.width)) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val nearWhite = r >= 240 && g >= 240 && b >= 240
                if (!nearWhite) hit++
                total++
                x += 6
            }
            y += 6
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }
}
