package com.agentime.ime.host.storage

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HostLogger(private val context: Context) {
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun log(tag: String, message: String) {
        val line = "${formatter.format(Date())} [$tag] $message\n"
        val f = File(context.filesDir, "host.log")
        f.appendText(line)
    }

    fun readRecent(maxChars: Int = 8000): String {
        val f = File(context.filesDir, "host.log")
        if (!f.exists()) return "暂无日志"
        val text = f.readText()
        return if (text.length <= maxChars) text else text.takeLast(maxChars)
    }

    /** 清空宿主日志文件 */
    fun clear() {
        File(context.filesDir, "host.log").delete()
    }
}
