package com.agentime.ime.host.capture

data class CaptureResult(
    val imagePath: String,
    val rawImagePath: String? = null,
    val rawExportedPath: String? = null,
    val exportedPath: String? = null,
    val debugSummary: String? = null,
    val captureTrace: String? = null,
    val chatCropPath: String? = null,
    val enhancedChatCropPath: String? = null,
    val acceptableForOcr: Boolean = false,
    val sharpnessScore: Double = 0.0,
    val totalScore: Double = 0.0,
)

interface CaptureController {
    fun prewarm() {}

    fun captureScreen(sessionId: String): CaptureResult
}
