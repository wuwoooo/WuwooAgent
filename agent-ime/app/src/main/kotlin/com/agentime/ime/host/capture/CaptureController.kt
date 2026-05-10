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
    val latestVisibleMessageSide: String? = null,
    val hasInboundAfterLatestOutbound: Boolean = false,
    val latestInboundBubbleCropPath: String? = null,
    val latestInboundVoiceTranscriptionCropPath: String? = null,
    val latestInboundVoiceRedDot: Boolean = false,
    val latestInboundVoiceRedDotX: Float? = null,
    val latestInboundVoiceRedDotY: Float? = null,
    val latestInboundVoiceRedDotScore: Int = 0,
    val latestOutboundCropPath: String? = null,
    // 检测到语音转文字正在加载中（灰色旋转 loading spinner），截图内容可能不完整
    val voiceTranscriptionLoading: Boolean = false,
    val acceptableForOcr: Boolean = false,
    val sharpnessScore: Double = 0.0,
    val totalScore: Double = 0.0,
)

interface CaptureController {
    fun prewarm() {}

    fun captureScreen(sessionId: String): CaptureResult
}
