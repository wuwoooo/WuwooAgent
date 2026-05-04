package com.agentime.ime.host.agent

data class AgentReply(
    val replyText: String,
    val raw: String,
    val silenced: Boolean = false,
    val reason: String = "",
    val currentStatus: String = "",
    val isGroupChat: Boolean = false,
)

interface AgentClient {
    fun chat(imagePath: String, ocrText: String, sessionId: String, contactName: String, isHumanReply: Boolean = false): AgentReply
}
