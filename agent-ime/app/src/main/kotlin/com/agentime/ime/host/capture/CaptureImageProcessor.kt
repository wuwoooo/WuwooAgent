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

    enum class Side { LEFT, RIGHT }

    data class AvatarBand(
        val side: Side,
        val top: Int,
        val bottom: Int,
        val peak: Double,
    )

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
                rawExportedPath = null
            } else {
                rawImagePath = null
                rawExportedPath = null
            }
        } else {
            rawImagePath = null
            rawExportedPath = null
        }
        val exportedPath = null
        val headerCrop = createHeaderCrop(bitmap)
        val headerCropPath = saveBitmapIfPresent(context, headerCrop, outDir, fileName, "header")
        val titleCrop = createTitleCrop(bitmap)
        val titleCropPath = saveBitmapIfPresent(context, titleCrop, outDir, fileName, "title")
        val chatCrop = createChatCrop(bitmap)
        val chatCropPath = saveBitmapIfPresent(context, chatCrop, outDir, fileName, "chatcrop")
        val latestVisibleMessageSide = chatCrop?.let(::detectLatestVisibleMessageSide)
        val sinceLastOutboundCrop = chatCrop?.let(::createInboundSinceLastOutboundCrop)
        val hasInboundAfterLatestOutbound = sinceLastOutboundCrop?.let(::hasInboundSignalInCrop) ?: false
        val sinceLastOutboundCropPath = saveBitmapIfPresent(context, sinceLastOutboundCrop, outDir, fileName, "since_last_outbound")
        val recentInboundClusterCrop = chatCrop?.let(::createRecentInboundClusterCrop)
        val recentInboundClusterCropPath = saveBitmapIfPresent(context, recentInboundClusterCrop, outDir, fileName, "recent_inbound_cluster")
        val leftMessageCrop = chatCrop?.let(::createLeftMessageCrop)
        val leftMessageCropPath = saveBitmapIfPresent(context, leftMessageCrop, outDir, fileName, "leftmsg")
        val recentLeftMessageCrop = leftMessageCrop?.let(::createRecentLeftMessageCrop)
        val recentLeftMessageCropPath = saveBitmapIfPresent(context, recentLeftMessageCrop, outDir, fileName, "leftmsg_recent")
        val latestInboundBubbleCrop = createLatestInboundBubbleCrop(bitmap)
        val latestInboundBubbleCropPath = saveBitmapIfPresent(context, latestInboundBubbleCrop, outDir, fileName, "leftmsg_latest_bubble")
        val latestOutboundCrop = chatCrop?.let(::createLatestOutboundCrop)
        val latestOutboundCropPath = saveBitmapIfPresent(context, latestOutboundCrop, outDir, fileName, "latest_outbound")
        headerCrop?.recycle()
        titleCrop?.recycle()
        chatCrop?.recycle()
        sinceLastOutboundCrop?.recycle()
        recentInboundClusterCrop?.recycle()
        leftMessageCrop?.recycle()
        recentLeftMessageCrop?.recycle()
        latestInboundBubbleCrop?.recycle()
        latestOutboundCrop?.recycle()

        return CaptureResult(
            imagePath = out.absolutePath,
            rawImagePath = rawImagePath,
            rawExportedPath = rawExportedPath,
            exportedPath = exportedPath,
            debugSummary = debugSummary,
            captureTrace = captureTrace,
            headerCropPath = headerCropPath,
            titleCropPath = titleCropPath,
            chatCropPath = chatCropPath,
            sinceLastOutboundCropPath = sinceLastOutboundCropPath,
            recentInboundClusterCropPath = recentInboundClusterCropPath,
            latestVisibleMessageSide = latestVisibleMessageSide,
            hasInboundAfterLatestOutbound = hasInboundAfterLatestOutbound,
            leftMessageCropPath = leftMessageCropPath,
            recentLeftMessageCropPath = recentLeftMessageCropPath,
            latestInboundBubbleCropPath = latestInboundBubbleCropPath,
            latestOutboundCropPath = latestOutboundCropPath,
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

    private fun createHeaderCrop(bitmap: Bitmap): Bitmap? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 50 || height < 50) return null

        val left = (width * 0.12f).toInt().coerceAtLeast(0)
        val top = (height * 0.035f).toInt().coerceAtLeast(0)
        val right = (width * 0.88f).toInt().coerceAtMost(width)
        val bottom = (height * 0.125f).toInt().coerceAtMost(height)
        val cropWidth = (right - left).coerceAtLeast(10)
        val cropHeight = (bottom - top).coerceAtLeast(10)
        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    }

    private fun createTitleCrop(bitmap: Bitmap): Bitmap? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 50 || height < 50) return null

        // 只取联系人标题所在的中部窄区域，尽量避开状态栏、屏幕共享浮层、返回按钮和右上角菜单。
        val left = (width * 0.22f).toInt().coerceAtLeast(0)
        val top = (height * 0.060f).toInt().coerceAtLeast(0)
        val right = (width * 0.78f).toInt().coerceAtMost(width)
        val bottom = (height * 0.118f).toInt().coerceAtMost(height)
        val cropWidth = (right - left).coerceAtLeast(10)
        val cropHeight = (bottom - top).coerceAtLeast(10)
        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    }

    private fun createChatCrop(bitmap: Bitmap): Bitmap? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 50 || height < 50) return null

        val left = (width * 0.05f).toInt().coerceAtLeast(0)
        val top = (height * 0.10f).toInt().coerceAtLeast(0)
        val right = (width * 0.95f).toInt().coerceAtMost(width)
        val bottom = (height * 0.92f).toInt().coerceAtMost(height)
        val cropWidth = (right - left).coerceAtLeast(10)
        val cropHeight = (bottom - top).coerceAtLeast(10)
        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    }

    private fun createLeftMessageCrop(source: Bitmap): Bitmap? {
        val width = source.width
        val height = source.height
        if (width < 50 || height < 50) return null

        val left = (width * 0.02f).toInt().coerceAtLeast(0)
        val top = (height * 0.04f).toInt().coerceAtLeast(0)
        val right = (width * 0.58f).toInt().coerceAtMost(width)
        val bottom = (height * 0.98f).toInt().coerceAtMost(height)
        val cropWidth = (right - left).coerceAtLeast(10)
        val cropHeight = (bottom - top).coerceAtLeast(10)
        return Bitmap.createBitmap(source, left, top, cropWidth, cropHeight)
    }

    private fun createRecentLeftMessageCrop(source: Bitmap): Bitmap? {
        val width = source.width
        val height = source.height
        if (width < 50 || height < 50) return null

        val left = (width * 0.00f).toInt().coerceAtLeast(0)
        val top = (height * 0.62f).toInt().coerceAtLeast(0)
        val right = (width * 0.52f).toInt().coerceAtMost(width)
        val bottom = (height * 0.98f).toInt().coerceAtMost(height)
        val cropWidth = (right - left).coerceAtLeast(10)
        val cropHeight = (bottom - top).coerceAtLeast(10)
        return Bitmap.createBitmap(source, left, top, cropWidth, cropHeight)
    }

    private fun createInboundSinceLastOutboundCrop(source: Bitmap): Bitmap? {
        val width = source.width
        val height = source.height
        if (width < 80 || height < 120) return null

        val searchTop = (height * 0.06f).toInt().coerceAtLeast(0)
        // 避开最底部输入栏/按钮区域，减少把底部控件误识别为右侧头像的概率
        val searchBottom = (height * 0.90f).toInt().coerceAtMost(height)
        if (searchBottom - searchTop < 40) return null

        fun isNearWhite(pixel: Int): Boolean {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return r >= 238 && g >= 238 && b >= 238
        }

        fun rowDensity(y: Int, xStart: Int, xEnd: Int): Double {
            var total = 0
            var nonWhite = 0
            var x = xStart
            while (x < xEnd) {
                if (!isNearWhite(source.getPixel(x, y))) nonWhite++
                total++
                x += 3
            }
            return if (total == 0) 0.0 else nonWhite.toDouble() / total
        }

        val rightStart = (width * 0.84f).toInt().coerceAtLeast(0)
        val rightEnd = (width * 0.985f).toInt().coerceAtMost(width)
        val rightBands = mutableListOf<AvatarBand>()
        var top = -1
        var bottom = -1
        var peak = 0.0
        var gap = 0
        for (y in searchTop until searchBottom) {
            val density = rowDensity(y, rightStart, rightEnd)
            if (density >= 0.16) {
                if (top < 0) {
                    top = y
                    bottom = y
                    peak = density
                } else {
                    bottom = y
                    if (density > peak) peak = density
                }
                gap = 0
            } else if (top >= 0) {
                gap++
                if (gap >= 8) {
                    val h = bottom - top + 1
                    if (h in 34..160 && peak >= 0.22) {
                        rightBands.add(AvatarBand(Side.RIGHT, top, bottom, peak))
                    }
                    top = -1
                    bottom = -1
                    peak = 0.0
                    gap = 0
                }
            }
        }
        if (top >= 0) {
            val h = bottom - top + 1
            if (h in 34..160 && peak >= 0.22) {
                rightBands.add(AvatarBand(Side.RIGHT, top, bottom, peak))
            }
        }

        fun isLikelyGreenBubble(pixel: Int): Boolean {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return g >= 165 && r in 95..215 && b in 85..190 && (g - r) >= 18 && (g - b) >= 10
        }

        fun findLowestRightGreenBubbleBottom(): Int? {
            val xStart = (width * 0.44f).toInt().coerceAtLeast(0)
            val xEnd = (width * 0.90f).toInt().coerceAtMost(width)
            var inBand = false
            var bandTop = -1
            var bandBottom = -1
            var bestBottom = -1
            var gap = 0
            var bestPeakRatio = 0.0
            var peakRatio = 0.0
            for (y in searchTop until searchBottom) {
                var green = 0
                var total = 0
                var x = xStart
                while (x < xEnd) {
                    if (isLikelyGreenBubble(source.getPixel(x, y))) green++
                    total++
                    x += 3
                }
                val ratio = if (total == 0) 0.0 else green.toDouble() / total
                val rowIsGreen = ratio >= 0.08
                if (rowIsGreen) {
                    if (!inBand) {
                        inBand = true
                        bandTop = y
                        bandBottom = y
                        peakRatio = ratio
                    } else {
                        bandBottom = y
                        if (ratio > peakRatio) peakRatio = ratio
                    }
                    gap = 0
                } else if (inBand) {
                    gap++
                    if (gap >= 8) {
                        if (bandBottom - bandTop >= 22 && peakRatio >= 0.10) {
                            bestBottom = maxOf(bestBottom, bandBottom)
                            bestPeakRatio = maxOf(bestPeakRatio, peakRatio)
                        }
                        inBand = false
                        bandTop = -1
                        bandBottom = -1
                        peakRatio = 0.0
                        gap = 0
                    }
                }
            }
            if (inBand && bandBottom - bandTop >= 22 && peakRatio >= 0.10) {
                bestBottom = maxOf(bestBottom, bandBottom)
                bestPeakRatio = maxOf(bestPeakRatio, peakRatio)
            }
            return if (bestBottom > 0 && bestPeakRatio >= 0.10) bestBottom else null
        }

        val latestRightAvatarBottom = rightBands.maxByOrNull { it.bottom }?.bottom
        val latestRightGreenBottom = findLowestRightGreenBubbleBottom()
        // 绿色气泡锚定优先，避免头像锚定被底部控件误触发
        val anchorBottom = when {
            latestRightGreenBottom != null -> latestRightGreenBottom
            latestRightAvatarBottom != null -> latestRightAvatarBottom
            else -> -1
        }
        if (anchorBottom < 0) return null
        val cropTop = (anchorBottom + maxOf((height * 0.010f).toInt(), 8)).coerceAtMost(searchBottom - 24)
        val cropBottom = (height * 0.985f).toInt().coerceAtMost(height)
        val cropHeight = cropBottom - cropTop
        if (cropHeight < 40) return null

        val cropLeft = 0
        // 取整条消息横向区域（头像+气泡+尾部）
        val cropRight = width
        val cropWidth = (cropRight - cropLeft).coerceAtLeast(10)
        return Bitmap.createBitmap(source, cropLeft, cropTop, cropWidth, cropHeight)
    }

    private fun createRecentInboundClusterCrop(source: Bitmap): Bitmap? {
        val width = source.width
        val height = source.height
        if (width < 80 || height < 120) return null

        val searchTop = (height * 0.06f).toInt().coerceAtLeast(0)
        val searchBottom = (height * 0.90f).toInt().coerceAtMost(height)
        if (searchBottom - searchTop < 60) return null

        fun isNearWhite(pixel: Int): Boolean {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return r >= 238 && g >= 238 && b >= 238
        }

        fun nonWhiteRatio(y: Int, xStart: Int, xEnd: Int, step: Int = 3): Double {
            var total = 0
            var nonWhite = 0
            var x = xStart.coerceAtLeast(0)
            val end = xEnd.coerceAtMost(width)
            while (x < end) {
                if (!isNearWhite(source.getPixel(x, y))) nonWhite++
                total++
                x += step
            }
            return if (total == 0) 0.0 else nonWhite.toDouble() / total
        }

        fun isLikelyGreen(pixel: Int): Boolean {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return g >= 165 && r in 95..215 && b in 85..190 && (g - r) >= 18 && (g - b) >= 10
        }

        fun greenRatio(y: Int, xStart: Int, xEnd: Int, step: Int = 3): Double {
            var total = 0
            var green = 0
            var x = xStart.coerceAtLeast(0)
            val end = xEnd.coerceAtMost(width)
            while (x < end) {
                if (isLikelyGreen(source.getPixel(x, y))) green++
                total++
                x += step
            }
            return if (total == 0) 0.0 else green.toDouble() / total
        }

        var clusterBottom = -1
        var clusterTop = -1
        var inboundRows = 0
        var gapRows = 0
        var outboundRows = 0
        var leadingOutboundRows = 0

        for (y in (searchBottom - 1) downTo searchTop) {
            val leftAvatar = nonWhiteRatio(y, (width * 0.00f).toInt(), (width * 0.16f).toInt())
            val leftBody = nonWhiteRatio(y, (width * 0.12f).toInt(), (width * 0.82f).toInt())
            val rightGreen = greenRatio(y, (width * 0.46f).toInt(), (width * 0.94f).toInt())

            val outboundRow = rightGreen >= 0.08
            // 当存在绿色气泡时，强制排除 inbound 判定，避免自定义聊天背景导致 leftAvatar 大面积非白被误判为入站
            val inboundRow = !outboundRow && ((leftAvatar >= 0.10) || (leftBody >= 0.08 && rightGreen <= 0.04))

            if (clusterBottom < 0) {
                if (inboundRow) {
                    clusterBottom = y
                    clusterTop = y
                    inboundRows++
                    gapRows = 0
                    outboundRows = 0
                    leadingOutboundRows = 0
                } else if (outboundRow) {
                    leadingOutboundRows++
                    // 底部先连续出现己方消息，说明当前最新消息是己方，不能再往上捞旧入站消息
                    if (leadingOutboundRows >= 8) return null
                }
                continue
            }

            if (inboundRow) {
                clusterTop = y
                inboundRows++
                gapRows = 0
                outboundRows = 0
            } else {
                if (outboundRow) outboundRows++ else outboundRows = 0
                gapRows++
                if (outboundRows >= 5 || gapRows >= 24) break
            }
        }

        if (clusterBottom < 0 || inboundRows < 6) return null

        val topPadding = maxOf((height * 0.010f).toInt(), 8)
        val bottomPadding = maxOf((height * 0.014f).toInt(), 10)
        val cropTop = (clusterTop - topPadding).coerceAtLeast(searchTop)
        val cropBottom = (clusterBottom + bottomPadding).coerceAtMost((height * 0.985f).toInt().coerceAtMost(height))
        if (cropBottom - cropTop < 40) return null

        val cropLeft = 0
        val cropRight = width
        return Bitmap.createBitmap(
            source,
            cropLeft,
            cropTop,
            (cropRight - cropLeft).coerceAtLeast(10),
            (cropBottom - cropTop).coerceAtLeast(10),
        )
    }

    private fun hasInboundSignalInCrop(source: Bitmap): Boolean {
        val width = source.width
        val height = source.height
        if (width < 60 || height < 40) return false

        fun isNearWhite(pixel: Int): Boolean {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return r >= 238 && g >= 238 && b >= 238
        }

        fun nonWhiteRatio(y: Int, xStart: Int, xEnd: Int): Double {
            var total = 0
            var nonWhite = 0
            var x = xStart.coerceAtLeast(0)
            val end = xEnd.coerceAtMost(width)
            while (x < end) {
                if (!isNearWhite(source.getPixel(x, y))) nonWhite++
                total++
                x += 3
            }
            return if (total == 0) 0.0 else nonWhite.toDouble() / total
        }

        val top = (height * 0.02f).toInt().coerceAtLeast(0)
        val bottom = (height * 0.88f).toInt().coerceAtMost(height)
        var hitRows = 0
        var sampled = 0
        for (y in top until bottom step 2) {
            val leftAvatar = nonWhiteRatio(y, 0, (width * 0.18f).toInt())
            val centerBody = nonWhiteRatio(y, (width * 0.12f).toInt(), (width * 0.84f).toInt())
            if (leftAvatar >= 0.10 || centerBody >= 0.09) hitRows++
            sampled++
        }
        if (sampled <= 0) return false
        return hitRows >= 8 && hitRows.toDouble() / sampled >= 0.20
    }

    private fun createLatestInboundBubbleCrop(source: Bitmap): Bitmap? {
        val width = source.width
        val height = source.height
        if (width < 80 || height < 120) return null

        val searchTop = (height * 0.10f).toInt().coerceAtLeast(0)
        // 最新消息通常贴近输入栏上方，但输入栏自身也有大量复杂图形；裁剪底部收紧以避免误截输入框/按钮。
        val searchBottom = (height * 0.90f).toInt().coerceAtMost(height)
        if (searchBottom - searchTop < 40) return null

        fun rowComplexity(y: Int, xStart: Int, xEnd: Int): Int {
            var diffCount = 0
            var prevC = source.getPixel(xStart, y)
            for (x in xStart + 2 until xEnd step 2) {
                val c = source.getPixel(x, y)
                val dr = Math.abs(((c shr 16) and 0xFF) - ((prevC shr 16) and 0xFF))
                val dg = Math.abs(((c shr 8) and 0xFF) - ((prevC shr 8) and 0xFF))
                val db = Math.abs((c and 0xFF) - (prevC and 0xFF))
                if (dr + dg + db > 120) diffCount++
                prevC = c
            }
            return diffCount
        }

        // 检测头像列（x: 0~13% 宽）区间内是否存在明显色彩（非纯色背景），用于识别轻量气泡
        fun avatarBandHasContent(top: Int, bottom: Int): Boolean {
            val xStart = (width * 0.005f).toInt().coerceAtLeast(0)
            val xEnd = (width * 0.13f).toInt().coerceAtMost(width - 1)
            var totalDiff = 0
            var samples = 0
            val step = maxOf(1, (bottom - top) / 8)
            for (y in top until bottom step step) {
                val cx = rowComplexity(y, xStart, xEnd)
                totalDiff += cx
                samples++
            }
            // 若头像区域整体复杂度 > 2/采样行，认为有头像
            return samples > 0 && totalDiff.toFloat() / samples > 2f
        }

        // 分级判定：
        //   标准 block（高度≥30）：leftScore > rightScore*1.2 && leftScore > 20
        //   轻量 block（高度<30）：leftScore > rightScore*1.2 && leftScore > 4，或头像区有内容
        class Block(val top: Int, val bottom: Int, val leftScore: Int, val rightScore: Int) {
            val blockH = bottom - top
            val hasLeft: Boolean by lazy {
                val leftDominant = leftScore > rightScore * 1.2f
                if (blockH >= 30) {
                    leftDominant && leftScore > 20
                } else {
                    // 轻量气泡（单字符、数字、短词）：降低绝对阈值，辅以头像区检测
                    (leftDominant && leftScore > 4) || (leftScore > 0 && avatarBandHasContent(top, bottom))
                }
            }
        }
        val blocks = mutableListOf<Block>()

        var currentTop = -1
        var currentLeftScore = 0
        var currentRightScore = 0
        var gapCount = 0

        for (y in searchTop..searchBottom) {
            val leftCx = rowComplexity(y, (width * 0.03f).toInt(), (width * 0.18f).toInt())
            val rightCx = rowComplexity(y, (width * 0.82f).toInt(), (width * 0.97f).toInt())
            val centerCx = rowComplexity(y, (width * 0.20f).toInt(), (width * 0.80f).toInt())

            val rowHasLeft = leftCx > 2
            val rowHasRight = rightCx > 2
            val rowHasCenter = centerCx > 5

            if (rowHasLeft || rowHasRight || rowHasCenter) {
                if (currentTop < 0) currentTop = y
                currentLeftScore += leftCx
                currentRightScore += rightCx
                gapCount = 0
            } else {
                if (currentTop >= 0) {
                    gapCount++
                    if (gapCount >= 10) {
                        val blockBottom = y - gapCount
                        val blockHeight = blockBottom - currentTop
                        // 降低最小高度门槛至 6px，以捕获"1"等极短消息气泡
                        if (blockHeight >= 6) {
                            blocks.add(Block(currentTop, blockBottom, currentLeftScore, currentRightScore))
                        }
                        currentTop = -1
                        currentLeftScore = 0
                        currentRightScore = 0
                        gapCount = 0
                    }
                }
            }
        }
        if (currentTop >= 0) {
            val blockHeight = searchBottom - currentTop
            if (blockHeight >= 6) {
                blocks.add(Block(currentTop, searchBottom, currentLeftScore, currentRightScore))
            }
        }

        val lastInbound = blocks.lastOrNull { it.hasLeft } ?: return null

        val topPadding = maxOf((height * 0.010f).toInt(), 6)
        val bottomPadding = maxOf((height * 0.014f).toInt(), 10)
        val cropTop = (lastInbound.top - topPadding).coerceAtLeast(searchTop)
        val cropBottom = (lastInbound.bottom + bottomPadding).coerceAtMost(searchBottom)

        if (cropBottom - cropTop < 10) return null

        val cropLeft = 0
        val cropRight = (width * 0.85f).toInt().coerceAtMost(width)
        val cropWidth = (cropRight - cropLeft).coerceAtLeast(10)
        val cropHeight = (cropBottom - cropTop).coerceAtLeast(10)

        return Bitmap.createBitmap(
            source,
            cropLeft,
            cropTop,
            cropWidth,
            cropHeight
        )
    }

    private fun detectLatestVisibleMessageSide(source: Bitmap): String {
        val width = source.width
        val height = source.height
        if (width < 80 || height < 120) return "unknown"

        val searchTop = (height * 0.10f).toInt().coerceAtLeast(0)
        // 微信输入栏已在 chatCrop 外侧，这里放宽到底部以覆盖贴近输入栏的最后一条短消息。
        val searchBottom = (height * 0.985f).toInt().coerceAtMost(height)
        if (searchBottom - searchTop < 40) return "unknown"

        fun isNearWhite(pixel: Int): Boolean {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return r >= 238 && g >= 238 && b >= 238
        }

        fun nonWhiteRatio(y: Int, xStart: Int, xEnd: Int): Double {
            var total = 0
            var nonWhite = 0
            var x = xStart.coerceAtLeast(0)
            val end = xEnd.coerceAtMost(width)
            while (x < end) {
                if (!isNearWhite(source.getPixel(x, y))) nonWhite++
                total++
                x += 3
            }
            return if (total == 0) 0.0 else nonWhite.toDouble() / total
        }

        fun isLikelyGreen(pixel: Int): Boolean {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return g >= 165 && r in 95..215 && b in 85..190 && (g - r) >= 18 && (g - b) >= 10
        }

        fun greenRatio(y: Int, xStart: Int, xEnd: Int): Double {
            var total = 0
            var green = 0
            var x = xStart.coerceAtLeast(0)
            val end = xEnd.coerceAtMost(width)
            while (x < end) {
                if (isLikelyGreen(source.getPixel(x, y))) green++
                total++
                x += 3
            }
            return if (total == 0) 0.0 else green.toDouble() / total
        }

        var outboundHits = 0
        var inboundHits = 0
        var inspected = 0
        for (y in (searchBottom - 1) downTo searchTop) {
            val rightGreen = greenRatio(y, (width * 0.46f).toInt(), (width * 0.94f).toInt())
            val leftAvatar = nonWhiteRatio(y, 0, (width * 0.16f).toInt())
            val centerBody = nonWhiteRatio(y, (width * 0.12f).toInt(), (width * 0.82f).toInt())

            if (rightGreen >= 0.09) {
                outboundHits++
                inspected++
            } else if (leftAvatar >= 0.11 || centerBody >= 0.09) {
                inboundHits++
                inspected++
            }
            if (inspected >= 24) break
        }
        return when {
            outboundHits >= 8 && outboundHits >= inboundHits + 3 -> "outbound"
            inboundHits >= 8 && inboundHits >= outboundHits + 3 -> "inbound"
            else -> "unknown"
        }
    }

    private fun createLatestOutboundCrop(source: Bitmap): Bitmap? {
        val width = source.width
        val height = source.height
        if (width < 80 || height < 120) return null

        val searchTop = (height * 0.10f).toInt().coerceAtLeast(0)
        val searchBottom = (height * 0.95f).toInt().coerceAtMost(height)
        if (searchBottom - searchTop < 40) return null

        fun isLikelyGreen(pixel: Int): Boolean {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return g >= 165 && r in 95..215 && b in 85..190 && (g - r) >= 18 && (g - b) >= 10
        }

        fun greenRatio(y: Int, xStart: Int, xEnd: Int): Double {
            var green = 0
            var total = 0
            var x = xStart.coerceAtLeast(0)
            val end = xEnd.coerceAtMost(width)
            while (x < end) {
                if (isLikelyGreen(source.getPixel(x, y))) green++
                total++
                x += 3
            }
            return if (total == 0) 0.0 else green.toDouble() / total
        }

        var blockTop = -1
        var blockBottom = -1
        var inBlock = false
        var gap = 0

        val rightStart = (width * 0.45f).toInt()
        val rightEnd = (width * 0.95f).toInt()

        for (y in searchBottom downTo searchTop) {
            val ratio = greenRatio(y, rightStart, rightEnd)
            if (ratio >= 0.05) {
                if (!inBlock) {
                    inBlock = true
                    blockBottom = y
                    blockTop = y
                } else {
                    blockTop = y
                }
                gap = 0
            } else if (inBlock) {
                gap++
                if (gap >= 12) {
                    val h = blockBottom - blockTop
                    if (h >= 15) {
                        break
                    } else {
                        inBlock = false
                        blockBottom = -1
                        blockTop = -1
                        gap = 0
                    }
                }
            }
        }
        
        if (inBlock) {
            val h = blockBottom - blockTop
            if (h < 15) {
                blockBottom = -1
                blockTop = -1
            }
        }

        if (blockBottom < 0 || blockTop < 0) return null

        val topPadding = maxOf((height * 0.010f).toInt(), 6)
        val bottomPadding = maxOf((height * 0.014f).toInt(), 10)
        val cropTop = (blockTop - topPadding).coerceAtLeast(searchTop)
        val cropBottom = (blockBottom + bottomPadding).coerceAtMost(searchBottom)

        if (cropBottom - cropTop < 10) return null

        val cropLeft = (width * 0.15f).toInt().coerceAtLeast(0)
        val cropRight = width
        val cropWidth = (cropRight - cropLeft).coerceAtLeast(10)
        val cropHeight = (cropBottom - cropTop).coerceAtLeast(10)

        return Bitmap.createBitmap(
            source,
            cropLeft,
            cropTop,
            cropWidth,
            cropHeight
        )
    }



    private fun saveBitmapIfPresent(
        context: Context,
        bitmap: Bitmap?,
        outDir: File,
        baseFileName: String,
        suffix: String,
    ): String? {
        if (bitmap == null) return null
        val exportedFileName = baseFileName.removeSuffix(".png") + "_$suffix.png"
        val target = File(outDir, exportedFileName)
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

    fun exportPublicCopy(context: Context, fileName: String, pngBytes: ByteArray): String? {
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
