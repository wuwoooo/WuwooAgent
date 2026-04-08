package com.agentime.ime.host.agent

data class AgentReply(
    val replyText: String,
    val raw: String,
)

interface AgentClient {
    fun chat(imagePath: String, ocrText: String, sessionId: String, contactName: String): AgentReply
}
