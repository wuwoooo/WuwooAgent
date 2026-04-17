package com.agentime.ime.host.agent

object ConversationTextExtractor {
    private val timeRegex = Regex("""^\d{1,2}:\d{2}$""")
    private val dateRegex = Regex("""^\d{4}[-/年]\d{1,2}([-/月]\d{1,2})?.*$""")
    private val fullTimestampRegex = Regex("""^\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}.*$""")
    private val separatorRegex = Regex("""^[\-\s·•~=_]{2,}$""")
    private val whitespaceRegex = Regex("""\s+""")
    private val fileSizeRegex = Regex("""^\d+(\.\d+)?\s*(KB|MB|GB)$""", RegexOption.IGNORE_CASE)
    private val attachmentNameRegex = Regex(
        """^[\w\-.一-龥]+?\.(apk|jpg|jpeg|png|gif|webp|pdf|doc|docx|xls|xlsx|ppt|pptx|zip|rar|7z)(\.\d+)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val lonePunctuationRegex = Regex("""^[!！,，.。|?？]+$""")
    private val plausibleInboundRegex = Regex("""[\p{L}\p{N}一-龥]""")
    private val tokenRegex = Regex("""[\p{L}\p{N}一-龥]{2,}""")
    private val systemNoiseMarkers = listOf(
        "微信团队",
        "微信(1)",
        "欢迎你再次回到微信",
        "如果你在使用过程中有任何",
        "转发截图",
        "转发载图",
        "回复:123",
        "微信ClawBot",
        "微信客服",
        "HostForegroundService",
        "state=SENT",
        "state=IDLE",
        "state=SCREEN_CAPTURED",
        "detail=",
        "clickSend 返回",
        "type=dataSync",
        "会话列表截图分析跳过",
    )

    fun extractLatestInboundMessage(
        ocrText: String,
        contactName: String,
        lastReplyText: String,
    ): ExtractionResult {
        val normalized = normalize(ocrText)
        if (normalized.isBlank()) return ExtractionResult("", "")

        val allLines = normalized.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val normalizedLastReply = normalize(lastReplyText)
        val filtered = allLines.filterNot { shouldIgnoreLine(it, contactName) }
            .let { lines ->
                if (lastReplyText.isBlank()) lines else removeKnownReply(lines, normalizedLastReply)
            }

        if (filtered.isEmpty()) return ExtractionResult("", "")

        val scoped = trimToRecentConversationWindow(filtered)
        if (scoped.isEmpty()) return ExtractionResult("", "")

        val strippedOwn = scoped.filterNot {
            looksLikeOwnLine(it, normalizedLastReply) || looksLikeAgentStyleLine(it)
        }
        if (strippedOwn.isEmpty()) return ExtractionResult("", "")

        val strippedTrailingOwn = stripTrailingOwnChunks(strippedOwn, normalizedLastReply)
        if (strippedTrailingOwn.isEmpty()) return ExtractionResult("", "")

        val chunks = splitToChunks(strippedTrailingOwn)
        if (chunks.isEmpty()) return ExtractionResult("", "")

        for (chunk in chunks.asReversed()) {
            val text = trimOwnReplyTail(
                chunk.takeLast(15).joinToString("\n").trim(),
                normalizedLastReply,
            )
            if (text.isBlank()) continue
            if (looksLikeOwnReply(text, normalizedLastReply)) continue
            if (looksLikeAgentReplyCandidate(text, normalizedLastReply)) {
                val fallback = fallbackToLastPlausibleInbound(chunk, normalizedLastReply)
                if (fallback.isNotBlank()) {
                    return ExtractionResult(fallback, computeContextSignature(allLines, fallback, contactName))
                }
                continue
            }
            return ExtractionResult(text, computeContextSignature(allLines, text, contactName))
        }

        val finalFallback = fallbackToLastPlausibleInbound(strippedTrailingOwn, normalizedLastReply)
        return ExtractionResult(finalFallback, computeContextSignature(allLines, finalFallback, contactName))
    }

    /**
     * 计算带上下文的签名。
     * 上下文定义为：当前消息的签名 + 它在原文中前面的第一条有效(非忽略)行的签名。
     */
    private fun computeContextSignature(allLines: List<String>, text: String, contactName: String): String {
        val mainSig = signatureOf(text)
        if (mainSig.isBlank()) return ""

        // 寻找此文本在 allLines 中的最后一次出现位置
        var foundIndex = -1
        for (i in allLines.indices.reversed()) {
            val lineSig = signatureOf(allLines[i])
            if (lineSig.contains(mainSig.take(10)) || mainSig.contains(lineSig.take(10))) {
                foundIndex = i
                break
            }
        }

        if (foundIndex <= 0) return mainSig

        // 寻找前面的有效行，最多往前看 10 行，跳过忽略行
        var prevSig = ""
        for (i in (foundIndex - 1) downTo maxOf(0, foundIndex - 10)) {
            val line = allLines[i]
            if (!shouldIgnoreLine(line, contactName) && signatureOf(line).isNotBlank()) {
                prevSig = signatureOf(line)
                break
            }
        }

        return if (prevSig.isBlank()) mainSig else "$mainSig|ctx:$prevSig"
    }

    data class ExtractionResult(
        val text: String,
        val signature: String,
    )

    fun signatureOf(text: String): String = normalize(text)
        .replace("\n", " ")
        .trim()

    private fun shouldIgnoreLine(line: String, contactName: String): Boolean {
        if (isBoundaryLine(line)) return true
        if (line == contactName.trim() && contactName.isNotBlank()) return true
        if (line == "微信") return true
        if (line == "Wuwoo Agent") return true
        if (fullTimestampRegex.matches(line)) return true
        if (systemNoiseMarkers.any { marker -> line.contains(marker) }) return true
        if (fileSizeRegex.matches(line)) return true
        if (attachmentNameRegex.matches(line)) return true
        if (lonePunctuationRegex.matches(line)) return true
        return false
    }

    private fun isBoundaryLine(line: String): Boolean {
        return timeRegex.matches(line) ||
            dateRegex.matches(line) ||
            separatorRegex.matches(line)
    }

    private fun removeKnownReply(lines: List<String>, lastReplyText: String): List<String> {
        if (lastReplyText.isBlank()) return lines
        val replyLines = lastReplyText.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (replyLines.isEmpty()) return lines
        if (lines.size < replyLines.size) return lines

        for (start in lines.indices) {
            if (start + replyLines.size > lines.size) break
            val window = lines.subList(start, start + replyLines.size)
            if (window == replyLines) {
                return buildList {
                    addAll(lines.subList(0, start))
                    addAll(lines.subList(start + replyLines.size, lines.size))
                }
            }
        }
        return lines
    }

    private fun stripTrailingOwnChunks(lines: List<String>, normalizedLastReply: String): List<String> {
        if (lines.isEmpty()) return lines
        val chunks = splitToChunks(lines)
        if (chunks.isEmpty()) return lines

        var keepUntil = chunks.size
        for (i in chunks.indices.reversed()) {
            val chunkText = chunks[i].joinToString("\n").trim()
            if (chunkText.isBlank()) {
                keepUntil = i
                continue
            }
            if (looksLikeOwnReply(chunkText, normalizedLastReply) || looksLikeAgentStyle(chunkText)) {
                keepUntil = i
                continue
            }
            break
        }
        return chunks.take(keepUntil).flatten()
    }

    private fun splitToChunks(lines: List<String>): List<List<String>> {
        val chunks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        for (line in lines) {
            if (isBoundaryLine(line)) {
                if (current.isNotEmpty()) {
                    chunks.add(current.toList())
                    current = mutableListOf()
                }
                continue
            }
            current.add(line)
        }
        if (current.isNotEmpty()) {
            chunks.add(current.toList())
        }
        return chunks
    }

    private fun trimToRecentConversationWindow(lines: List<String>): List<String> {
        val boundaryIndexes = lines.mapIndexedNotNull { index, line ->
            if (isBoundaryLine(line)) index else null
        }
        if (boundaryIndexes.isEmpty()) return lines

        val lastBoundary = boundaryIndexes.last()
        val afterLast = lines.drop(lastBoundary + 1).filter { it.isNotBlank() }
        if (afterLast.isNotEmpty()) return afterLast

        if (boundaryIndexes.size >= 2) {
            val prevBoundary = boundaryIndexes[boundaryIndexes.size - 2]
            val betweenLastTwo = lines.subList(prevBoundary + 1, lastBoundary).filter { it.isNotBlank() }
            if (betweenLastTwo.isNotEmpty()) return betweenLastTwo
        }

        return lines
    }

    private fun looksLikeOwnReply(chunkText: String, normalizedLastReply: String): Boolean {
        if (normalizedLastReply.isBlank()) return false
        val chunk = signatureOf(chunkText)
        val reply = signatureOf(normalizedLastReply)
        if (chunk.isBlank() || reply.isBlank()) return false
        if (chunk == reply) return true
        if (chunk.contains(reply) || reply.contains(chunk)) return true

        val replyTokens = reply.split(whitespaceRegex).filter { it.isNotBlank() }
        if (replyTokens.isEmpty()) return false
        val hit = replyTokens.count { token -> token.length >= 2 && chunk.contains(token) }
        return hit >= maxOf(2, replyTokens.size * 2 / 3)
    }

    private fun looksLikeOwnLine(line: String, normalizedLastReply: String): Boolean {
        val text = signatureOf(line)
        if (text.isBlank()) return false
        if (looksLikeOwnReply(text, normalizedLastReply)) return true
        val reply = signatureOf(normalizedLastReply)
        if (reply.isBlank()) return false
        val replyTokens = reply.split(whitespaceRegex).filter { it.length >= 2 }
        if (replyTokens.isEmpty()) return false
        val hit = replyTokens.count { token -> text.contains(token) }
        return hit >= 2
    }

    private fun looksLikeAgentStyle(chunkText: String): Boolean {
        val text = signatureOf(chunkText)
        if (text.isBlank()) return false
        val markers = listOf(
            "我是云南云鹿旅行社",
            "我是携程旅行定制师",
            "我可以帮你",
            "我可以帮您",
            "我帮你看看",
            "我帮您看看",
            "方便的话",
            "可以帮你们",
            "可以帮您",
            "帮你规划",
            "帮您规划",
            "给您看看",
            "机票和酒店",
            "什么时候出发",
            "几个人出行",
        )
        val hit = markers.count { marker -> text.contains(marker) }
        return hit >= 2
    }

    private fun looksLikeAgentStyleLine(line: String): Boolean {
        val text = signatureOf(line)
        if (text.isBlank()) return false
        val markers = listOf(
            "我是小鹿",
            "云南的旅游顾问",
            "想咨询云南旅游吗",
            "帮您推荐",
            "帮你推荐",
            "适合亲子游",
            "小朋友多大",
            "什么时候出发",
            "方便的话",
            "我这边好像没收到",
            "再发一次试试",
        )
        val hit = markers.count { marker -> text.contains(marker) }
        return hit >= 1
    }

    private fun fallbackToLastPlausibleInbound(lines: List<String>, normalizedLastReply: String): String {
        val plausible = lines.asReversed()
            .filterNot { line ->
                line.isBlank() ||
                    shouldIgnoreLine(line, contactName = "") ||
                    looksLikeOwnLine(line, normalizedLastReply) ||
                    looksLikeAgentReplyCandidate(line, normalizedLastReply) ||
                    looksLikeAgentStyleLine(line) ||
                    !plausibleInboundRegex.containsMatchIn(line) && !line.contains("?") && !line.contains("？")
            }
            .firstOrNull()
            .orEmpty()
        return trimOwnReplyTail(plausible.trim(), normalizedLastReply)
    }

    fun containsOwnReplyTail(candidateText: String, lastReplyText: String): Boolean {
        val candidate = signatureOf(candidateText)
        val reply = signatureOf(lastReplyText)
        if (candidate.isBlank() || reply.isBlank()) return false
        if (candidate == reply) return true
        if (candidate.endsWith(reply)) return true

        val replyLines = reply.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (replyLines.isEmpty()) return false
        val suffix = replyLines.takeLast(minOf(2, replyLines.size)).joinToString(" ").trim()
        return suffix.length >= 4 && candidate.contains(suffix)
    }

    fun looksLikeAgentReplyCandidate(candidateText: String, lastReplyText: String): Boolean {
        val candidate = signatureOf(candidateText)
        if (candidate.isBlank()) return false
        val normalizedLastReply = normalize(lastReplyText)
        if (containsOwnReplyTail(candidate, normalizedLastReply)) return true
        if (looksLikeAgentStyle(candidate) || looksLikeAgentStyleLine(candidate)) return true
        if (normalizedLastReply.isBlank()) return false
        return tokenOverlapRatio(candidate, normalizedLastReply) >= 0.45
    }

    private fun trimOwnReplyTail(candidateText: String, normalizedLastReply: String): String {
        val candidate = normalize(candidateText)
        if (candidate.isBlank() || normalizedLastReply.isBlank()) return candidate.trim()

        val candidateLines = candidate.lines().map { it.trim() }.filter { it.isNotBlank() }
        val replyLines = normalizedLastReply.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (candidateLines.isEmpty() || replyLines.isEmpty()) return candidate.trim()

        for (count in minOf(candidateLines.size, replyLines.size) downTo 1) {
            val candidateTail = candidateLines.takeLast(count)
            val replyTail = replyLines.takeLast(count)
            if (candidateTail == replyTail) {
                return candidateLines.dropLast(count).joinToString("\n").trim()
            }
        }

        val replySignature = signatureOf(normalizedLastReply)
        val candidateSignature = signatureOf(candidate)
        val index = candidateSignature.indexOf(replySignature)
        if (index > 0) {
            return candidateSignature.substring(0, index).trim()
        }
        return candidate.trim()
    }

    private fun tokenOverlapRatio(a: String, b: String): Double {
        val aTokens = tokenRegex.findAll(signatureOf(a)).map { it.value }.toList().distinct()
        val bTokens = tokenRegex.findAll(signatureOf(b)).map { it.value }.toList().distinct()
        if (aTokens.isEmpty() || bTokens.isEmpty()) return 0.0
        val overlap = aTokens.count { token -> bTokens.any { other -> other.contains(token) || token.contains(other) } }
        return overlap.toDouble() / aTokens.size.coerceAtLeast(1)
    }

    private fun normalize(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()
    }
}
