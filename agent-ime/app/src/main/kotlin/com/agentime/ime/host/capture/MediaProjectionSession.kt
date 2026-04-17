package com.agentime.ime.host.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 同一 [MediaProjection] 实例上 **只能创建一次** [VirtualDisplay]（Android 14+ 限制）。
 * 多次截图仅重复 [ImageReader.acquireLatestImage]，不重复 [MediaProjection.createVirtualDisplay]。
 *
 * 用户重新「授权截图」时由 [ProjectionPermissionStore.update] 调用 [tearDown]。
 */
object MediaProjectionSession {
    private const val TAG = "MediaProjectionSession"
    private const val ENABLE_SURFACE_REFRESH_PER_CAPTURE = false
    private const val ENABLE_LEGACY_SINGLE_SHOT_PATH = true
    private const val DESIRED_SAMPLE_COUNT = 6
    private const val EARLY_ACCEPT_SCORE = 205.0
    private const val MIN_ACCEPTABLE_SHARPNESS = 42.0
    private const val MIN_ACCEPTABLE_ACTIVE_ROW_RATIO = 0.12
    private const val STABLE_FRAME_DIFF_THRESHOLD = 7.5
    private const val MIN_FRAME_SPACING_NS = 35_000_000L
    private val lock = Any()

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var width = 0
    private var height = 0

    private var projectionCallback: MediaProjection.Callback? = null
    private var boundProjection: MediaProjection? = null

    fun prewarm(context: Context) {
        synchronized(lock) {
            ensurePipeline(context)
        }
        Log.i(TAG, "prewarm 完成，截图管线已就绪")
    }

    fun tearDown() {
        synchronized(lock) {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            handlerThread?.quitSafely()
            handlerThread = null
            handler = null

            val cb = projectionCallback
            val p = boundProjection
            // 先置 null 并注销 Callback，确保后续 p.stop() 触发的 onStop 回调不会再执行
            projectionCallback = null
            boundProjection = null
            if (p != null && cb != null) {
                runCatching { p.unregisterCallback(cb) }
            }
            // stop 放在 unregisterCallback 之后，避免回调重入
            runCatching { p?.stop() }
            ProjectionPermissionStore.onProjectionSessionEnded()
        }
    }

    private fun ensurePipeline(context: Context) {
        if (virtualDisplay != null) return

        val app = context.applicationContext
        val p = ProjectionPermissionStore.acquireMediaProjection(app)
        boundProjection = p

        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            width = bounds.width().coerceAtLeast(1)
            height = bounds.height().coerceAtLeast(1)
            density = app.resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            width = metrics.widthPixels.coerceAtLeast(1)
            height = metrics.heightPixels.coerceAtLeast(1)
            density = metrics.densityDpi
        }
        Log.i(TAG, "ensurePipeline width=$width height=$height density=$density")

        handlerThread = HandlerThread("mp-pipeline").apply { start() }
        handler = Handler(handlerThread!!.looper)
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                // 系统停止投影时的回调。此时管线已无效，下次 captureScreen 会报「未授权」错误。
                // 不在此调用 tearDown：tearDown 在 synchronized(lock) 内调 p.stop()，
                // 而 onStop 本身可能在 stop() 内部同步触发，再试图拿同一把锁会死锁。
                Log.w(TAG, "MediaProjection.Callback.onStop 触发，当前投影已被系统停止")
            }
        }
        projectionCallback = cb
        p.registerCallback(cb, handler)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        Log.i(TAG, "ImageReader 创建完成")
        virtualDisplay = p.createVirtualDisplay(
            "agent-pipeline",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            handler,
        )
        Log.i(TAG, "VirtualDisplay 创建完成: ${virtualDisplay != null}")
    }

    private fun refreshCaptureSurfaceLocked() {
        val vd = virtualDisplay ?: throw IllegalStateException("VirtualDisplay 未初始化")
        val oldReader = imageReader
        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader = newReader
        vd.surface = newReader.surface
        oldReader?.close()
        Log.i(TAG, "截图前已刷新 ImageReader Surface width=$width height=$height")
    }

    /**
     * 在创建 [VirtualDisplay] 时传入的 [Handler] 线程上调用 [ImageReader.acquireLatestImage]，
     * 部分机型在其它线程会一直返回 null。
     */
    fun capturePngFile(context: Context, sessionId: String): CaptureResult {
        return try {
            capturePngFileInternal(context, sessionId, preferLegacy = ENABLE_LEGACY_SINGLE_SHOT_PATH)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            val shouldFallback =
                ENABLE_LEGACY_SINGLE_SHOT_PATH &&
                    msg.contains("legacy single shot 未获取到有效图像")
            if (!shouldFallback) {
                Log.e(TAG, "capturePngFile 首次失败: ${e.message}", e)
                throw e
            }

            Log.w(TAG, "legacy 首拍未取到图像，沿用当前投影管线回退到监听式抓帧")
            capturePngFileInternal(context, sessionId, preferLegacy = false)
        }
    }

    private fun capturePngFileInternal(
        context: Context,
        sessionId: String,
        preferLegacy: Boolean,
    ): CaptureResult {
        if (preferLegacy) {
            return capturePngFileLegacySingleShot(context, sessionId)
        }
        val h: Handler
        val reader: ImageReader
        synchronized(lock) {
            ensurePipeline(context)
            if (ENABLE_SURFACE_REFRESH_PER_CAPTURE) {
                refreshCaptureSurfaceLocked()
            }
            h = handler ?: throw IllegalStateException("截图管线未初始化")
            reader = imageReader ?: throw IllegalStateException("ImageReader 未初始化")
        }
        Thread.sleep(180)
        val done = CountDownLatch(1)
        val maxBlankRejectBeforeForce = 36
        val maxAcquireAttempts = 170
        val captureStartNs = System.nanoTime()
        var listenerTriggered = false
        var nullAcquireCount = 0
        var blankRejectCount = 0
        var staleFrameCount = 0
        var scoredFrameCount = 0
        var bestFrame: ScoredBitmap? = null
        var previousSignature: IntArray? = null
        var previousAcceptedTimestampNs: Long? = null
        var stableFrameCount = 0
        var eventCount = 0
        var duplicateFrameCount = 0
        val trace = mutableListOf<String>()

        val listener = ImageReader.OnImageAvailableListener {
            listenerTriggered = true
            eventCount++
            Log.d(TAG, "OnImageAvailable 收到新帧通知 eventCount=$eventCount")
        }
        reader.setOnImageAvailableListener(listener, h)
        Log.i(TAG, "已注册 OnImageAvailableListener")
        val drainedBeforeStart = drainBufferedImages()
        Log.i(TAG, "capture 开始前已清空旧帧数量=$drainedBeforeStart")
        if (ENABLE_SURFACE_REFRESH_PER_CAPTURE) {
            trace += "截图前已刷新 ImageReader Surface width=$width height=$height"
        } else {
            trace += "沿用既有 ImageReader Surface width=$width height=$height"
        }
        trace += "legacySingleShot=false"
        trace += "capture 开始前已清空旧帧数量=$drainedBeforeStart"

        lateinit var runAcquireLoop: (Int, Int) -> Unit

        fun handleImage(attempt: Int, blankRejections: Int, acquired: Image) {
            when {
                acquired.timestamp < captureStartNs -> {
                    staleFrameCount++
                    Log.d(TAG, "捕获到旧帧 timestamp=${acquired.timestamp} < captureStartNs=$captureStartNs，丢弃 count=$staleFrameCount")
                    if (staleFrameCount <= 3) {
                        trace += "捕获旧帧并丢弃 count=$staleFrameCount"
                        }
                        acquired.close()
                    if (attempt + 1 >= maxAcquireAttempts) {
                        done.countDown()
                    } else {
                        h.postDelayed({ runAcquireLoop(attempt + 1, blankRejections) }, 55)
                    }
                }
                previousAcceptedTimestampNs != null &&
                    acquired.timestamp - previousAcceptedTimestampNs!! < MIN_FRAME_SPACING_NS -> {
                    duplicateFrameCount++
                    val deltaMs = (acquired.timestamp - previousAcceptedTimestampNs!!).toDouble() / 1_000_000.0
                    Log.d(TAG, "捕获到过近帧 deltaMs=${"%.2f".format(deltaMs)}，丢弃 count=$duplicateFrameCount")
                    if (duplicateFrameCount <= 4) {
                        trace += "捕获过近帧并丢弃 deltaMs=${"%.2f".format(deltaMs)} count=$duplicateFrameCount"
                    }
                    acquired.close()
                    if (attempt + 1 >= maxAcquireAttempts) {
                        done.countDown()
                    } else {
                        h.postDelayed({ runAcquireLoop(attempt + 1, blankRejections) }, 85)
                    }
                }
                blankRejections < maxBlankRejectBeforeForce && isMostlyBlank(acquired) -> {
                    blankRejectCount++
                    Log.d(TAG, "捕获到近空白帧，丢弃 count=$blankRejectCount attempt=$attempt")
                    if (blankRejectCount <= 3) {
                        trace += "捕获近空白帧并丢弃 count=$blankRejectCount"
                        }
                        acquired.close()
                    if (attempt + 1 >= maxAcquireAttempts) {
                        done.countDown()
                    } else {
                        h.postDelayed({ runAcquireLoop(attempt + 1, blankRejections + 1) }, 70)
                    }
                }
                else -> {
                    runCatching {
                        val bitmapInfo = imageToBitmapWithInfo(acquired)
                        val bmp = bitmapInfo.bitmap
                        trace += bitmapInfo.debug
                        val quality = evaluateBitmapQuality(bmp)
                        val signature = buildFrameSignature(bmp)
                        val diff = previousSignature?.let { signatureDistance(it, signature) }
                        val deltaMs = previousAcceptedTimestampNs?.let {
                            (acquired.timestamp - it).toDouble() / 1_000_000.0
                        }
                        val stable = diff != null && diff <= STABLE_FRAME_DIFF_THRESHOLD
                        if (stable) stableFrameCount++ else stableFrameCount = 0
                        previousSignature = signature
                        previousAcceptedTimestampNs = acquired.timestamp

                        val scored = ScoredBitmap(
                            bitmap = bmp,
                            quality = quality,
                            stable = stable,
                            signatureDiff = diff,
                            timestampNs = acquired.timestamp,
                        )
                        scoredFrameCount++
                        val acceptable = isAcceptableForOcr(quality)
                        Log.i(
                            TAG,
                            "候选帧 #$scoredFrameCount score=${"%.1f".format(quality.totalScore)} sharp=${"%.1f".format(quality.sharpnessScore)} content=${"%.1f".format(quality.contentScore)} rows=${quality.activeRowCount}/${quality.sampledRowCount} acceptable=$acceptable stable=$stable diff=${diff?.let { "%.2f".format(it) } ?: "n/a"} deltaMs=${deltaMs?.let { "%.2f".format(it) } ?: "n/a"}",
                        )
                        trace += "候选帧 #$scoredFrameCount score=${"%.1f".format(quality.totalScore)} sharp=${"%.1f".format(quality.sharpnessScore)} acceptable=$acceptable stable=$stable diff=${diff?.let { "%.2f".format(it) } ?: "n/a"} deltaMs=${deltaMs?.let { "%.2f".format(it) } ?: "n/a"}"
                        val currentBest = bestFrame
                        if (currentBest == null || isBetterFrame(scored, currentBest)) {
                            currentBest?.bitmap?.recycle()
                            bestFrame = scored
                            Log.i(
                                TAG,
                                "更新最佳帧 score=${"%.1f".format(quality.totalScore)} sharp=${"%.1f".format(quality.sharpnessScore)} stable=$stable",
                            )
                            trace += "更新最佳帧 score=${"%.1f".format(quality.totalScore)} sharp=${"%.1f".format(quality.sharpnessScore)} stable=$stable"
                        } else {
                            bmp.recycle()
                        }
                    }.onFailure {
                        Log.w(TAG, "候选帧评分失败: ${it.message}", it)
                    }.also {
                        acquired.close()
                    }

                    val bestScore = bestFrame?.quality?.totalScore ?: 0.0
                    val bestAcceptable = bestFrame?.quality?.let(::isAcceptableForOcr) == true
                    val enoughSamples = scoredFrameCount >= DESIRED_SAMPLE_COUNT
                    val goodEnough =
                        bestAcceptable &&
                            bestScore >= EARLY_ACCEPT_SCORE &&
                            (stableFrameCount >= 1 || scoredFrameCount >= 3)
                    if (goodEnough || enoughSamples || attempt + 1 >= maxAcquireAttempts) {
                        done.countDown()
                    } else {
                        h.postDelayed({ runAcquireLoop(attempt + 1, blankRejections) }, 220)
                    }
                }
            }
        }

        runAcquireLoop = { attempt: Int, blankRejections: Int ->
            h.post {
                if (done.count == 0L) return@post
                val acquired = synchronized(lock) { imageReader?.acquireNextImage() }
                if (acquired == null) {
                    nullAcquireCount++
                    if (attempt + 1 >= maxAcquireAttempts) {
                        done.countDown()
                    } else {
                        h.postDelayed({ runAcquireLoop(attempt + 1, blankRejections) }, 55)
                    }
                    return@post
                }
                handleImage(attempt, blankRejections, acquired)
            }
        }

        runAcquireLoop(0, 0)
        val ok = done.await(18, TimeUnit.SECONDS)
        reader.setOnImageAvailableListener(null, null)
        val frame = bestFrame
        if ((!ok && frame == null) || frame == null) {
            Log.e(
                TAG,
                "截图失败 width=$width height=$height listenerTriggered=$listenerTriggered nullAcquireCount=$nullAcquireCount staleFrameCount=$staleFrameCount duplicateFrameCount=$duplicateFrameCount blankRejectCount=$blankRejectCount scoredFrameCount=$scoredFrameCount pipelineReady=${virtualDisplay != null}",
            )
            throw IllegalStateException(
                "截图超时：未获取到有效图像帧(listener=$listenerTriggered null=$nullAcquireCount stale=$staleFrameCount dup=$duplicateFrameCount blank=$blankRejectCount scored=$scoredFrameCount)，请回到微信聊天页后重试",
            )
        }
        try {
            val acceptable = isAcceptableForOcr(frame.quality)
            Log.i(
                TAG,
                "截图成功 width=$width height=$height listenerTriggered=$listenerTriggered nullAcquireCount=$nullAcquireCount staleFrameCount=$staleFrameCount duplicateFrameCount=$duplicateFrameCount blankRejectCount=$blankRejectCount scoredFrameCount=$scoredFrameCount bestScore=${"%.1f".format(frame.quality.totalScore)} acceptable=$acceptable",
            )
            return encodePng(context, null, frame.bitmap, sessionId, frame.quality, trace.joinToString("\n"))
        } finally {
            frame.bitmap.recycle()
        }
    }

    private fun capturePngFileLegacySingleShot(
        context: Context,
        sessionId: String,
    ): CaptureResult {
        val h: Handler
        val reader: ImageReader
        synchronized(lock) {
            ensurePipeline(context)
            h = handler ?: throw IllegalStateException("截图管线未初始化")
            reader = imageReader ?: throw IllegalStateException("ImageReader 未初始化")
        }
        Thread.sleep(320)
        val trace = mutableListOf<String>()
        trace += "legacySingleShot=true"
        trace += "沿用既有 ImageReader Surface width=$width height=$height"
        val drainedBeforeStart = drainBufferedImages()
        trace += "capture 开始前已清空旧帧数量=$drainedBeforeStart"

        var chosen: BitmapWithInfo? = null
        var attempts = 0
        val maxAttempts = 18
        while (attempts < maxAttempts && chosen == null) {
            attempts++
            val image = synchronized(lock) { reader.acquireLatestImage() }
            if (image == null) {
                Thread.sleep(120)
                continue
            }
            try {
                if (!isMostlyBlank(image)) {
                    chosen = imageToBitmapWithInfo(image)
                    trace += "legacy 命中帧 attempt=$attempts"
                } else {
                    trace += "legacy 丢弃近空白帧 attempt=$attempts"
                }
            } finally {
                image.close()
            }
            if (chosen == null) {
                Thread.sleep(120)
            }
        }

        val bitmapInfo = chosen ?: throw IllegalStateException("legacy single shot 未获取到有效图像帧")
        trace += bitmapInfo.debug
        val quality = evaluateBitmapQuality(bitmapInfo.bitmap)
        trace += "legacy 候选帧 score=${"%.1f".format(quality.totalScore)} sharp=${"%.1f".format(quality.sharpnessScore)} acceptable=${isAcceptableForOcr(quality)}"
        Log.i(
            TAG,
            "legacy 单帧截图 width=$width height=$height attempts=$attempts score=${"%.1f".format(quality.totalScore)} sharp=${"%.1f".format(quality.sharpnessScore)}",
        )
        return try {
            encodePng(context, bitmapInfo.rawBitmap, bitmapInfo.bitmap, sessionId, quality, trace.joinToString("\n"))
        } finally {
            bitmapInfo.rawBitmap.recycle()
            bitmapInfo.bitmap.recycle()
        }
    }

    /**
     * 抽样判断整帧是否接近纯黑（预热未出画）。
     * 阈值过严会把深色模式微信整屏判成「空」导致一直丢弃；抽样前需 rewind 缓冲。
     */
    private fun isMostlyBlank(image: Image): Boolean {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return true
        val plane = image.planes.firstOrNull() ?: return true
        val buffer = plane.buffer.duplicate().apply { rewind() }
        val ps = plane.pixelStride
        val rs = plane.rowStride
        val cap = buffer.capacity()
        var dark = 0
        var n = 0
        var maxLum = 0
        val stepY = maxOf(1, h / 24)
        val stepX = maxOf(1, w / 24)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val pos = y * rs + x * ps
                if (pos + 2 < cap) {
                    val r = buffer.get(pos).toInt() and 0xFF
                    val g = buffer.get(pos + 1).toInt() and 0xFF
                    val b = buffer.get(pos + 2).toInt() and 0xFF
                    val lum = (r + g + b) / 3
                    maxLum = maxOf(maxLum, lum)
                    if (lum < 18) dark++
                    n++
                }
                x += stepX
            }
            y += stepY
        }
        // 有明显像素较亮 → 视为有画面（含深色主题）
        if (maxLum > 55) return false
        return n > 0 && dark * 100 / n >= 92
    }

    private fun encodePng(
        context: Context,
        rawBitmap: Bitmap?,
        bitmap: Bitmap,
        sessionId: String,
        quality: FrameQuality,
        captureTrace: String,
    ): CaptureResult {
        Log.i(
            TAG,
            "最终选择截图 score=${"%.1f".format(quality.totalScore)} sharp=${"%.1f".format(quality.sharpnessScore)} content=${"%.1f".format(quality.contentScore)} rows=${quality.activeRowCount}/${quality.sampledRowCount}",
        )
            return CaptureImageProcessor.processBitmap(
                context = context,
                bitmap = bitmap,
                rawBitmap = rawBitmap,
                sessionId = sessionId,
                debugSummary =
                    "score=${"%.1f".format(quality.totalScore)} sharp=${"%.1f".format(quality.sharpnessScore)} content=${"%.1f".format(quality.contentScore)} rows=${quality.activeRowCount}/${quality.sampledRowCount} acceptable=${isAcceptableForOcr(quality)}",
                captureTrace = captureTrace,
                acceptableForOcr = isAcceptableForOcr(quality),
                sharpnessScore = quality.sharpnessScore,
                totalScore = quality.totalScore,
            )
    }

    private fun imageToBitmapWithInfo(image: Image): BitmapWithInfo {
        val width = image.width
        val height = image.height
        val plane = image.planes.first()
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val imageInfo =
            "image=${width}x$height pixelStride=$pixelStride rowStride=$rowStride rowPadding=$rowPadding bufferCapacity=${buffer.capacity()}"
        val bmp = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        val dup = buffer.duplicate()
        dup.rewind()
        bmp.copyPixelsFromBuffer(dup)
        val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)
        val rawCopy = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val bitmapInfo = "bitmapRaw=${bmp.width}x${bmp.height} bitmapCropped=${cropped.width}x${cropped.height}"
        bmp.recycle()
        return BitmapWithInfo(
            rawBitmap = rawCopy,
            bitmap = cropped,
            debug = "$imageInfo | $bitmapInfo",
        )
    }

    private fun drainBufferedImages(): Int {
        var drained = 0
        while (true) {
            val img = synchronized(lock) { imageReader?.acquireLatestImage() } ?: break
            drained++
            img.close()
        }
        return drained
    }

    private fun evaluateBitmapQuality(bitmap: Bitmap): FrameQuality {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 2 || height <= 2) {
            return FrameQuality(0.0, 0.0, 0.0, 0, 0)
        }

        val left = (width * 0.06).toInt()
        val right = (width * 0.94).toInt().coerceAtLeast(left + 2)
        val top = (height * 0.05).toInt()
        val bottom = (height * 0.88).toInt().coerceAtLeast(top + 2)
        val stepX = maxOf(4, (right - left) / 36)
        val stepY = maxOf(6, (bottom - top) / 54)

        var activeRows = 0
        var sampledRows = 0
        var darkPixels = 0
        var sampledPixels = 0
        var edgeSum = 0.0
        var edgeCount = 0

        var y = top
        while (y < bottom - stepY) {
            var rowDark = 0
            var rowSamples = 0
            var x = left
            while (x < right - stepX) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lum = (r + g + b) / 3
                if (lum < 238) darkPixels++
                sampledPixels++
                rowSamples++
                if (lum < 238) rowDark++

                val cx = bitmap.getPixel(x + stepX, y)
                val cy = bitmap.getPixel(x, y + stepY)
                val lumX = (((cx shr 16) and 0xFF) + ((cx shr 8) and 0xFF) + (cx and 0xFF)) / 3
                val lumY = (((cy shr 16) and 0xFF) + ((cy shr 8) and 0xFF) + (cy and 0xFF)) / 3
                edgeSum += kotlin.math.abs(lum - lumX).toDouble()
                edgeSum += kotlin.math.abs(lum - lumY).toDouble()
                edgeCount += 2
                x += stepX
            }
            sampledRows++
            if (rowSamples > 0 && rowDark * 100 / rowSamples >= 8) {
                activeRows++
            }
            y += stepY
        }

        val contentRatio = if (sampledPixels == 0) 0.0 else darkPixels.toDouble() / sampledPixels.toDouble()
        val activeRowRatio = if (sampledRows == 0) 0.0 else activeRows.toDouble() / sampledRows.toDouble()
        val edgeAvg = if (edgeCount == 0) 0.0 else edgeSum / edgeCount.toDouble()

        val contentScore = (contentRatio * 140.0) + (activeRowRatio * 70.0)
        val sharpnessScore = edgeAvg * 2.2
        val totalScore = contentScore + sharpnessScore

        return FrameQuality(
            totalScore = totalScore,
            sharpnessScore = sharpnessScore,
            contentScore = contentScore,
            activeRowCount = activeRows,
            sampledRowCount = sampledRows,
        )
    }

    private fun isAcceptableForOcr(quality: FrameQuality): Boolean {
        val rowRatio =
            if (quality.sampledRowCount == 0) 0.0 else quality.activeRowCount.toDouble() / quality.sampledRowCount.toDouble()
        return quality.sharpnessScore >= MIN_ACCEPTABLE_SHARPNESS &&
            rowRatio >= MIN_ACCEPTABLE_ACTIVE_ROW_RATIO
    }

    private fun isBetterFrame(candidate: ScoredBitmap, current: ScoredBitmap): Boolean {
        val candidateAcceptable = isAcceptableForOcr(candidate.quality)
        val currentAcceptable = isAcceptableForOcr(current.quality)
        if (candidateAcceptable != currentAcceptable) {
            return candidateAcceptable
        }

        if (candidate.stable != current.stable) {
            return candidate.stable
        }

        val sharpDelta = candidate.quality.sharpnessScore - current.quality.sharpnessScore
        if (kotlin.math.abs(sharpDelta) >= 3.0) {
            return sharpDelta > 0
        }

        val rowRatioCandidate =
            if (candidate.quality.sampledRowCount == 0) 0.0 else candidate.quality.activeRowCount.toDouble() / candidate.quality.sampledRowCount.toDouble()
        val rowRatioCurrent =
            if (current.quality.sampledRowCount == 0) 0.0 else current.quality.activeRowCount.toDouble() / current.quality.sampledRowCount.toDouble()
        val rowDelta = rowRatioCandidate - rowRatioCurrent
        if (kotlin.math.abs(rowDelta) >= 0.04) {
            return rowDelta > 0
        }

        return candidate.quality.totalScore > current.quality.totalScore
    }

    private fun buildFrameSignature(bitmap: Bitmap): IntArray {
        val width = bitmap.width
        val height = bitmap.height
        val cols = 8
        val rows = 12
        val signature = IntArray(cols * rows)
        val left = (width * 0.12f).toInt().coerceAtLeast(0)
        val right = (width * 0.88f).toInt().coerceAtLeast(left + cols)
        val top = (height * 0.10f).toInt().coerceAtLeast(0)
        val bottom = (height * 0.84f).toInt().coerceAtLeast(top + rows)
        val cellW = ((right - left) / cols).coerceAtLeast(1)
        val cellH = ((bottom - top) / rows).coerceAtLeast(1)

        var index = 0
        var row = 0
        while (row < rows) {
            var col = 0
            while (col < cols) {
                val x = (left + col * cellW + cellW / 2).coerceAtMost(width - 1)
                val y = (top + row * cellH + cellH / 2).coerceAtMost(height - 1)
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                signature[index++] = (r + g + b) / 3
                col++
            }
            row++
        }
        return signature
    }

    private fun signatureDistance(prev: IntArray, current: IntArray): Double {
        if (prev.size != current.size || prev.isEmpty()) return Double.MAX_VALUE
        var sum = 0.0
        for (i in prev.indices) {
            sum += kotlin.math.abs(prev[i] - current[i]).toDouble()
        }
        return sum / prev.size.toDouble()
    }

    private data class ScoredBitmap(
        val bitmap: Bitmap,
        val quality: FrameQuality,
        val stable: Boolean,
        val signatureDiff: Double?,
        val timestampNs: Long,
    )

    private data class BitmapWithInfo(
        val rawBitmap: Bitmap,
        val bitmap: Bitmap,
        val debug: String,
    )

    private data class FrameQuality(
        val totalScore: Double,
        val sharpnessScore: Double,
        val contentScore: Double,
        val activeRowCount: Int,
        val sampledRowCount: Int,
    )
}
