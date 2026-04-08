package com.agentime.ime.host.ocr

import android.content.Context
import com.agentime.ime.OcrHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LocalOcrProvider(private val context: Context) : OcrProvider {
    override fun recognize(imagePath: String, imageUri: String?): String {
        val result = AtomicReference<String>()
        val error = AtomicReference<Throwable>()
        val latch = CountDownLatch(1)

        OcrHelper.recognizeText(context, imagePath, imageUri)
            .addOnSuccessListener {
                result.set(it)
                latch.countDown()
            }
            .addOnFailureListener {
                error.set(it)
                latch.countDown()
            }

        if (!latch.await(20, TimeUnit.SECONDS)) {
            throw IllegalStateException("本地 OCR 超时")
        }
        error.get()?.let { throw it }
        return result.get().orEmpty()
    }
}
