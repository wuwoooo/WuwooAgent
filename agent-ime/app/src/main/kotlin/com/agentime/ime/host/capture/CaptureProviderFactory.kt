package com.agentime.ime.host.capture

import android.content.Context

object CaptureProviderFactory {
    const val PROVIDER_MEDIA_PROJECTION = "media_projection"
    private const val DEFAULT_PROVIDER = PROVIDER_MEDIA_PROJECTION

    fun create(context: Context): CaptureController {
        return MediaProjectionCaptureManager(context)
    }

    fun currentProvider(context: Context): String {
        return DEFAULT_PROVIDER
    }
}
