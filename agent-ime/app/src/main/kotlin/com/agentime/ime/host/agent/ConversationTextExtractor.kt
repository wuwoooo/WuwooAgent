package com.agentime.ime.host.agent

object ConversationTextExtractor {
    private val timeRegex = Regex("""^\d{1,2}:\d{2}$""")
    private val dateRegex = Regex("""^\d{4}[-/年]\d{1,2}([-/月]\d{1,2})?.*$""")
    private val separatorRegex = Regex("""^[\-\s·•~=_]{2,}$""")
    private val whitespaceRegex = Regex("""\s+""")
    private val fileSizeRegex = Regex("""^\d+(\.\d+)?\s*(KB|MB|GB)$""", RegexOption.IGNORE_CASE)
    private val attachmentNameRegex = Regex(
        """^[\w\-.一-龥]+?\.(apk|jpg|jpeg|png|gif|webp|pdf|doc|docx|xls|xlsx|ppt|pptx|zip|rar|7z)(\.\d+)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val lonePunctuationRegex = Regex("""^[?？!！,，.。|]+$""")

    fun extractLatestInboundMessage(
        ocrText: String,
        contactName: String,
        lastReplyText: String,
    ): String {
        val normalized = normalize(ocrText)
        if (normalized.isBlank()) return ""

        val allLines = normalized.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val filtered = allLines.filterNot { shouldIgnoreLine(it, contactName) }
            .let { lines ->
                if (lastReplyText.isBlank()) lines else removeKnownReply(lines, normalize(lastReplyText))
            }

        if (filtered.isEmpty()) return ""

        val scoped = trimToRecentConversationWindow(filtered)
        if (scoped.isEmpty()) return ""

        val normalizedLastReply = normalize(lastReplyText)
        val strippedOwn = scoped.filterNot {
            looksLikeOwnLine(it, normalizedLastReply) || looksLikeAgentStyleLine(it)
        }
        if (strippedOwn.isEmpty()) return ""

        val strippedTrailingOwn = stripTrailingOwnChunks(strippedOwn, normalizedLastReply)
        if (strippedTrailingOwn.isEmpty()) return ""

        val chunks = splitToChunks(strippedTrailingOwn)
        if (chunks.isEmpty()) return ""
        for (chunk in chunks.asReversed()) {
            val text = chunk.takeLast(3).joinToString("\n").trim()
            if (text.isBlank()) continue
            if (looksLikeOwnReply(text, normalizedLastReply)) continue
            return text
        }
        return ""
    }

    fun signatureOf(text: String): String = normalize(text)
        .replace("\n", " ")
        .trim()

    private fun shouldIgnoreLine(line: String, contactName: String): Boolean {
        if (isBoundaryLine(line)) return true
        if (line == contactName.trim() && contactName.isNotBlank()) return true
        if (line == "微信") return true
        if (line == "Wuwoo Agent") return true
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

    private fun normalize(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()
    }
}
