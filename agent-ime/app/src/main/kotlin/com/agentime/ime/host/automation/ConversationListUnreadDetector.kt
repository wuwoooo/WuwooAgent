package com.agentime.ime.host.automation

import android.graphics.BitmapFactory

object ConversationListUnreadDetector {
    data class ChatPageAnalysis(
        val looksLikeChatPage: Boolean,
        val contactName: String,
        val debugSummary: String,
    )

    data class ListPageAnalysis(
        val looksLikeListPage: Boolean,
        val debugSummary: String,
    )

    data class Hit(
        val tapX: Float,
        val tapY: Float,
        val redScore: Int,
    )

    private data class RedComponent(
        val centerX: Float,
        val centerY: Float,
        val width: Int,
        val height: Int,
        val count: Int,
    )

    private val timeRegex = Regex("""^\d{1,2}:\d{2}$|^\d{1,2}:\d{2}:\d{2}$""")
    private val fullTimestampRegex = Regex("""^\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}.*$""")
    private val fileSizeRegex = Regex("""^\d+(\.\d+)?\s*(KB|MB|GB)$""", RegexOption.IGNORE_CASE)
    private val listTitleRegex = Regex("""^微信(\(\d+\))?$""")
    private val punctuationOnlyRegex = Regex("""^[\p{Punct}（）()<>《》【】\[\]|]+$""")
    private val contactLikeCharRegex = Regex("""[\p{L}\p{N}一-龥]""")
    private val attachmentNameRegex = Regex(
        """^[\w\-.一-龥]+?\.(apk|jpg|jpeg|png|gif|webp|pdf|doc|docx|xls|xlsx|ppt|pptx|zip|rar|7z)(\.\d+)?$""",
        RegexOption.IGNORE_CASE,
    )

    fun analyzeChatPage(ocrText: String, headerOcrText: String = ""): ChatPageAnalysis {
        val lines = ocrText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val headerLines = headerOcrText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val normalizedPageText = ocrText.replace("（", "(").replace("）", ")")
        val normalizedHeaderText = headerOcrText.replace("（", "(").replace("）", ")")
        val bottomTabHitCount = listOf("微信", "通讯录", "发现", "我").count { normalizedPageText.contains(it) }
        val pageContactName = extractChatContactName(lines)
        val headerContactName = extractChatContactName(headerLines)
        val contactName = headerContactName.ifBlank { pageContactName }
        val headerLooksLikeListTitle = headerLines.any { listTitleRegex.matches(it.replace("（", "(").replace("）", ")")) }
        val pageLooksLikeListTitle = lines.take(4).any { listTitleRegex.matches(it.replace("（", "(").replace("）", ")")) }
        val messageLikeLines = lines.filter { isLikelyChatMessageLine(it) }
        val hasTimeline = lines.any { timeRegex.matches(it) }
        val hasHeaderCandidate = headerContactName.isNotBlank() || normalizedHeaderText.isNotBlank()
        val hasConversationStructure =
            messageLikeLines.size >= 2 ||
                (messageLikeLines.isNotEmpty() && hasTimeline) ||
                lines.size >= 3
        val tabSignalAcceptable =
            if (headerContactName.isNotBlank() && hasConversationStructure) {
                bottomTabHitCount <= 2
            } else {
                bottomTabHitCount <= 1
            }
        val looksLikeChatPage =
            tabSignalAcceptable &&
                !headerLooksLikeListTitle &&
                !pageLooksLikeListTitle &&
                (hasHeaderCandidate || hasConversationStructure)

        val summary =
            "looksLikeChatPage=$looksLikeChatPage " +
                "contactName=${if (contactName.isBlank()) "null" else contactName} " +
                "headerContact=${if (headerContactName.isBlank()) "null" else headerContactName} " +
                "messageLikeLines=${messageLikeLines.size} " +
                "hasTimeline=$hasTimeline " +
                "tabSignalAcceptable=$tabSignalAcceptable " +
                "bottomTabHitCount=$bottomTabHitCount"
        return ChatPageAnalysis(
            looksLikeChatPage = looksLikeChatPage,
            contactName = contactName,
            debugSummary = summary,
        )
    }

    fun extractChatContactNameFromOcr(ocrText: String): String {
        val lines = ocrText.lines().map { it.trim() }.filter { it.isNotBlank() }
        return extractChatContactName(lines)
    }

    fun scoreHeaderOcrCandidate(ocrText: String): Int {
        val lines = ocrText.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return Int.MIN_VALUE

        val hasListTitle = lines.any { line ->
            val normalized = line.replace("（", "(").replace("）", ")")
            listTitleRegex.matches(normalized) || normalized == "微信" || normalized.startsWith("微信(")
        }
        if (hasListTitle) {
            return 300
        }

        val candidate = extractChatContactName(lines)
        if (candidate.isBlank()) return -200

        var score = 0
        score += 120
        score += when (candidate.length) {
            in 2..8 -> 50
            in 9..14 -> 30
            in 15..20 -> 10
            else -> -40
        }
        if (candidate.contains('·') || candidate.contains('-')) score += 18
        if (candidate.any { it in '一'..'龥' }) score += 18
        if (candidate.matches(Regex("""[A-Za-z][A-Za-z0-9_. -]{1,20}"""))) score += 8
        if (ocrText.lines().size <= 2) score += 10
        if (ocrText.contains("屏幕共享")) score -= 80
        if (ocrText.contains("共享中")) score -= 60
        if (ocrText.contains("5G")) score -= 40
        if (ocrText.contains("%")) score -= 20
        if (ocrText.contains("HostForegroundService", ignoreCase = true)) score -= 150
        if (ocrText.contains("clickSend", ignoreCase = true)) score -= 120
        if (ocrText.contains("state=", ignoreCase = true)) score -= 120
        return score
    }

    fun analyzeConversationListPage(imagePath: String, ocrText: String, headerOcrText: String = ""): ListPageAnalysis {
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
            val normalizedHeader = headerOcrText
                .replace("（", "(")
                .replace("）", ")")
                .replace(" ", "")
            val combinedText = if (normalizedHeader.isNotBlank()) {
                normalizedHeader + "\n" + normalized
            } else {
                normalized
            }

            val titleLooksLikeList = combinedText.contains("微信(") ||
                combinedText.lines().take(6).any { line ->
                    val trimmed = line.trim()
                    trimmed == "微信" || trimmed.startsWith("微信(")
                }
            val bottomTabHitCount = listOf("微信", "通讯录", "发现", "我").count { combinedText.contains(it) }
            // 列表页会出现“需要发送”等预览文案，不能把“发送”单字当输入栏强信号。
            val inputBarSignals = listOf("按住说话", "切换到键盘", "表情", "更多功能", "更多")
            val inputBarHitCount = inputBarSignals.count { combinedText.contains(it) }

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
            // 列表页判定以视觉结构为主，OCR 文本只做弱约束。
            val visualLooksLikeList =
                topHeaderLight >= 0.78 &&
                    bottomBarLight >= 0.70 &&
                    bottomBarNonWhite >= 0.02 &&
                    // 列表页底部中心通常接近纯白；聊天页输入栏通常更“实”。
                    bottomCenterNonWhite <= 0.20 &&
                    leftAvatarNonWhite >= 0.10
            val textLooksLikeList =
                titleLooksLikeList || bottomTabHitCount >= 2
            val notChatInputBar =
                inputBarHitCount <= 1

            val looksLikeListPage =
                visualLooksLikeList &&
                    textLooksLikeList &&
                    notChatInputBar

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

    fun findTopUnreadConversation(imagePath: String, debugLog: StringBuilder? = null): Hit? {
        val bitmap = BitmapFactory.decodeFile(imagePath) ?: return null
        try {
            val width = bitmap.width
            val height = bitmap.height
            if (width < 200 || height < 400) return null

            // 扫描头像右上方的未读徽标区域。使用红色连通域而不是简单按行 bucket，避免点到下方红色头像/缩略图。
            val xStart = (width * 0.035f).toInt()
            val xEnd = (width * 0.255f).toInt()
            val yStart = (height * 0.10f).toInt()
            val yEnd = (height * 0.46f).toInt()
            debugLog?.append("scanArea x=${xStart}~$xEnd y=${yStart}~$yEnd bitmapSize=${width}x$height")
            val component = findUnreadBadgeComponent(bitmap, xStart, xEnd, yStart, yEnd, debugLog) ?: return null
            val rowHeight = (height * 0.094f).toInt().coerceAtLeast(140)
            val rowCenterY = (component.centerY + rowHeight * 0.20f).coerceIn(
                height * 0.16f,
                height * 0.62f,
            )
            return Hit(
                tapX = (width * 0.52f).coerceAtLeast(component.centerX + width * 0.24f),
                tapY = rowCenterY.toFloat(),
                redScore = component.count,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun isUnreadRed(r: Int, g: Int, b: Int): Boolean {
        // 用户洞察极其精准：红点有一半是突出的，叠加在白色/亮灰背景上！
        // 红色与白色抗锯齿融合后，边缘像素会变成较亮的粉红（R很大, G和B也会随之升高甚至达到180+）
        // 绝对不能限制 G 和 B 的绝对上限，只要 R 足够高且属于明显的优势色即可！
        return r >= 200 && (r - g) >= 50 && (r - b) >= 50
    }

    private fun findUnreadBadgeComponent(
        bitmap: android.graphics.Bitmap,
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int,
        debugLog: StringBuilder? = null,
    ): RedComponent? {
        // 以 480px 宽为基准屏幕，count 随面积平方缩放，dim 随线性缩放
        // 例如 1080px 屏幕：widthScale=2.25, countMax≈607, dimMax≈180
        val widthScale = (bitmap.width / 480f).coerceAtLeast(1f)
        val countMax = (120 * widthScale * widthScale).toInt()
        val dimMax = (80 * widthScale).toInt()
        val step = 2
        val gridWidth = ((xEnd - xStart) / step).coerceAtLeast(1)
        val gridHeight = ((yEnd - yStart) / step).coerceAtLeast(1)
        val mask = BooleanArray(gridWidth * gridHeight)
        fun idx(gx: Int, gy: Int): Int = gy * gridWidth + gx

        for (gy in 0 until gridHeight) {
            val y = yStart + gy * step
            for (gx in 0 until gridWidth) {
                val x = xStart + gx * step
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                mask[idx(gx, gy)] = isUnreadRed(r, g, b)
            }
        }

        val visited = BooleanArray(mask.size)
        var best: RedComponent? = null
        val queueX = IntArray(mask.size)
        val queueY = IntArray(mask.size)

        for (gy in 0 until gridHeight) {
            for (gx in 0 until gridWidth) {
                val start = idx(gx, gy)
                if (!mask[start] || visited[start]) continue

                var head = 0
                var tail = 0
                queueX[tail] = gx
                queueY[tail] = gy
                tail++
                visited[start] = true

                var count = 0
                var minX = gx
                var maxX = gx
                var minY = gy
                var maxY = gy
                var sumX = 0
                var sumY = 0

                while (head < tail) {
                    val cx = queueX[head]
                    val cy = queueY[head]
                    head++
                    count++
                    sumX += cx
                    sumY += cy
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx
                            val ny = cy + dy
                            if (nx !in 0 until gridWidth || ny !in 0 until gridHeight) continue
                            val next = idx(nx, ny)
                            if (!mask[next] || visited[next]) continue
                            visited[next] = true
                            queueX[tail] = nx
                            queueY[tail] = ny
                            tail++
                        }
                    }
                }

                val compWidth = (maxX - minX + 1) * step
                val compHeight = (maxY - minY + 1) * step
                val aspect = compWidth.toDouble() / compHeight.coerceAtLeast(1)
                val centerX = xStart + (sumX.toFloat() / count) * step
                val centerY = yStart + (sumY.toFloat() / count) * step
                // 连通域最右端像素的实际 X 坐标
                val compRightX = xStart + maxX * step

                // 【核心判断】角标会「突出」头像右边界进入白色列表区。
                // 微信头像占屏幕宽度约 3.5%~19%，角标圆心在头像右上角附近（centerX > 13%），
                // 其最右端必然超过头像右边界（compRightX > ~16% width），进入白色背景区域。
                // 头像内部橙红色色块：中心偏左（centerX < 13%），右端不超出头像边界（< 16%）。
                val protrudeThresh = bitmap.width * 0.16f
                val centerXMin = bitmap.width * 0.13f
                val centerXMax = bitmap.width * 0.28f
                val badgeProtrudesBeyondAvatar =
                    compRightX > protrudeThresh &&
                        centerX > centerXMin &&
                        centerX < centerXMax &&
                        centerY in (bitmap.height * 0.10f)..(bitmap.height * 0.45f)
                val plausibleSize =
                    count in 4..countMax &&
                        compWidth in 8..dimMax &&
                        compHeight in 8..dimMax &&
                        aspect in 0.55..1.85

                // 诊断日志：记录所有被过滤的连通域及原因，便于调试
                if (debugLog != null && (!badgeProtrudesBeyondAvatar || !plausibleSize)) {
                    val rejectReasons = buildList {
                        if (compRightX <= protrudeThresh) add("compRightX=${compRightX.toInt()} <= ${protrudeThresh.toInt()}(16%w)")
                        if (centerX <= centerXMin) add("centerX=${centerX.toInt()} <= ${centerXMin.toInt()}(13%w)")
                        if (centerX >= centerXMax) add("centerX=${centerX.toInt()} >= ${centerXMax.toInt()}(28%w)")
                        if (centerY !in (bitmap.height * 0.10f)..(bitmap.height * 0.45f)) add("centerY=${centerY.toInt()} out of y-band")
                        if (count !in 4..countMax) add("count=$count not in 4..$countMax")
                        if (compWidth !in 8..dimMax) add("compWidth=$compWidth not in 8..$dimMax")
                        if (compHeight !in 8..dimMax) add("compHeight=$compHeight not in 8..$dimMax")
                        if (aspect !in 0.55..1.85) add("aspect=${"%,.2f".format(aspect)} not in 0.55..1.85")
                    }
                    debugLog.append("\n  rejected: count=$count cx=${centerX.toInt()} cy=${centerY.toInt()} rightX=${compRightX.toInt()} w=$compWidth h=$compHeight aspect=${"%,.2f".format(aspect)} | ${rejectReasons.joinToString(", ")}")
                }
                if (!badgeProtrudesBeyondAvatar || !plausibleSize) continue

                val candidate = RedComponent(
                    centerX = centerX,
                    centerY = centerY,
                    width = compWidth,
                    height = compHeight,
                    count = count,
                )
                val current = best
                if (
                    current == null ||
                    candidate.centerY < current.centerY - bitmap.height * 0.015f ||
                    (
                        kotlin.math.abs(candidate.centerY - current.centerY) <= bitmap.height * 0.015f &&
                            candidate.count > current.count
                        )
                ) {
                    best = candidate
                }
            }
        }
        return best
    }

    private fun extractChatContactName(lines: List<String>): String {
        val candidates = lines.take(10)
            .filterNot { line ->
                line.isBlank() ||
                    timeRegex.matches(line) ||
                    listTitleRegex.matches(line.replace("（", "(").replace("）", ")")) ||
                    punctuationOnlyRegex.matches(line) ||
                    fileSizeRegex.matches(line) ||
                    attachmentNameRegex.matches(line) ||
                    fullTimestampRegex.matches(line) ||
                    line.length < 2 ||
                    line.length > 24 ||
                    !contactLikeCharRegex.containsMatchIn(line) ||
                    line.contains("HostForegroundService", ignoreCase = true) ||
                    line.contains("state=", ignoreCase = true) ||
                    line.contains("detail=", ignoreCase = true) ||
                    line.contains("clickSend", ignoreCase = true) ||
                    line.contains("type=dataSync", ignoreCase = true) ||
                    line.contains("你已添加") ||
                    line.contains("以上是") ||
                    line.contains("打招呼") ||
                    line.contains("通讯录") ||
                    line.contains("发现") ||
                    line.contains("微信团队") ||
                    line.contains("微信ClawBot") ||
                    line.contains("欢迎你再次回到微信") ||
                    line.contains("你好") ||
                    line.contains("您好") ||
                    line.startsWith("我是")
            }
        val latinCandidate = candidates.firstOrNull { candidate ->
            candidate.matches(Regex("""[A-Za-z][A-Za-z0-9_. -]{1,20}"""))
        }
        if (!latinCandidate.isNullOrBlank()) return latinCandidate

        return candidates.firstOrNull { candidate ->
            candidate.length in 2..24 &&
                candidate.none { it in ",，。！？?!~" }
        }.orEmpty()
    }

    private fun isLikelyChatMessageLine(line: String): Boolean {
        val normalized = line.trim()
        if (normalized.isBlank()) return false
        if (timeRegex.matches(normalized) || fullTimestampRegex.matches(normalized)) return false
        if (listTitleRegex.matches(normalized.replace("（", "(").replace("）", ")"))) return false
        if (fileSizeRegex.matches(normalized) || attachmentNameRegex.matches(normalized)) return false
        if (punctuationOnlyRegex.matches(normalized)) return false
        if (normalized.contains("HostForegroundService", ignoreCase = true)) return false
        if (normalized.contains("state=", ignoreCase = true)) return false
        if (normalized.contains("detail=", ignoreCase = true)) return false
        if (normalized.contains("clickSend", ignoreCase = true)) return false
        if (normalized.contains("type=dataSync", ignoreCase = true)) return false
        if (normalized.contains("Agent IME", ignoreCase = true)) return false
        if (normalized.contains("广播可注入文本")) return false
        return normalized.length >= 4 && contactLikeCharRegex.containsMatchIn(normalized)
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
