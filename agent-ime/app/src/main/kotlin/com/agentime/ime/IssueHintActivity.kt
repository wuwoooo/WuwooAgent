package com.agentime.ime

import android.app.AlertDialog
import android.os.Bundle
import androidx.activity.ComponentActivity

class IssueHintActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "提示" }
        val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty().ifBlank { "请重试。" }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton("知道了") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
    }
}
