package com.agentime.ime.host.ocr

interface OcrProvider {
    fun recognize(imagePath: String, imageUri: String? = null): String
}
