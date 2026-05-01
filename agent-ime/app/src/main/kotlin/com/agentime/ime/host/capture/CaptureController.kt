package com.agentime.ime.host.capture

data class CaptureResult(
    val imagePath: String,
    val rawImagePath: String? = null,
    val rawExportedPath: String? = null,
    val exportedPath: String? = null,
    val debugSummary: String? = null,
    val captureTrace: String? = null,
    val headerCropPath: String? = null,
    val titleCropPath: String? = null,
    val chatCropPath: String? = null,
    val sinceLastOutboundCropPath: String? = null,
    val recentInboundClusterCropPath: String? = null,
    val latestVisibleMessageSide: String? = null,
    val hasInboundAfterLatestOutbound: Boolean = false,
    val leftMessageCropPath: String? = null,
    val recentLeftMessageCropPath: String? = null,
    val latestInboundBubbleCropPath: String? = null,
    val latestInboundVoiceTranscriptionCropPath: String? = null,
    val latestInboundVoiceRedDot: Boolean = false,
    val latestInboundVoiceRedDotX: Float? = null,
    val latestInboundVoiceRedDotY: Float? = null,
    val latestInboundVoiceRedDotScore: Int = 0,
    val latestOutboundCropPath: String? = null,
    val acceptableForOcr: Boolean = false,
    val sharpnessScore: Double = 0.0,
    val totalScore: Double = 0.0,
)

interface CaptureController {
    fun prewarm() {}

    fun captureScreen(sessionId: String): CaptureResult
}
