package com.agentime.ime

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * 配置无障碍点击坐标（与 [com.agentime.ime.host.automation.WechatAccessibilityService] 共用 host_config）。
 */
class HostTapConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host_tap_config)

        val textScreen = findViewById<TextView>(R.id.textScreenInfo)
        val textInputHint = findViewById<TextView>(R.id.textInputHint)
        val textSendHint = findViewById<TextView>(R.id.textSendHint)
        val editInputX = findViewById<EditText>(R.id.editInputX)
        val editInputY = findViewById<EditText>(R.id.editInputY)
        val editSendX = findViewById<EditText>(R.id.editSendX)
        val editSendY = findViewById<EditText>(R.id.editSendY)

        fun refreshHints() {
            val dm = resources.displayMetrics
            val w = dm.widthPixels.toFloat()
            val h = dm.heightPixels.toFloat()
            textScreen.text = "当前屏幕逻辑像素：${dm.widthPixels} × ${dm.heightPixels}"
            textInputHint.text =
                "自动约：X=${(w * RATIO_INPUT_X).toInt()}  Y=${(h * RATIO_INPUT_Y).toInt()}（宽×${RATIO_INPUT_X}，高×${RATIO_INPUT_Y}）"
            textSendHint.text =
                "自动约：X=${(w * RATIO_SEND_X).toInt()}  Y=${(h * RATIO_SEND_Y).toInt()}（宽×${RATIO_SEND_X}，高×${RATIO_SEND_Y}）"
        }

        fun loadFields() {
            val p = getSharedPreferences(PREFS_HOST_CONFIG, MODE_PRIVATE)
            editInputX.setText(if (p.contains(KEY_INPUT_X)) p.getFloat(KEY_INPUT_X, 0f).toString() else "")
            editInputY.setText(if (p.contains(KEY_INPUT_Y)) p.getFloat(KEY_INPUT_Y, 0f).toString() else "")
            editSendX.setText(if (p.contains(KEY_SEND_X)) p.getFloat(KEY_SEND_X, 0f).toString() else "")
            editSendY.setText(if (p.contains(KEY_SEND_Y)) p.getFloat(KEY_SEND_Y, 0f).toString() else "")
            refreshHints()
        }

        loadFields()

        findViewById<Button>(R.id.btnSaveTapConfig).setOnClickListener {
            val ed = getSharedPreferences(PREFS_HOST_CONFIG, MODE_PRIVATE).edit()
            try {
                applyFloatOrRemove(ed, KEY_INPUT_X, editInputX.text?.toString())
                applyFloatOrRemove(ed, KEY_INPUT_Y, editInputY.text?.toString())
                applyFloatOrRemove(ed, KEY_SEND_X, editSendX.text?.toString())
                applyFloatOrRemove(ed, KEY_SEND_Y, editSendY.text?.toString())
                ed.apply()
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            } catch (e: NumberFormatException) {
                Toast.makeText(this, "请输入合法数字或留空", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnClearTapConfig).setOnClickListener {
            getSharedPreferences(PREFS_HOST_CONFIG, MODE_PRIVATE).edit()
                .remove(KEY_INPUT_X)
                .remove(KEY_INPUT_Y)
                .remove(KEY_SEND_X)
                .remove(KEY_SEND_Y)
                .apply()
            loadFields()
            Toast.makeText(this, "已清除，将使用自动比例", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyFloatOrRemove(
        ed: android.content.SharedPreferences.Editor,
        key: String,
        raw: String?,
    ) {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) {
            ed.remove(key)
        } else {
            ed.putFloat(key, s.toFloat())
        }
    }

    private companion object {
        const val PREFS_HOST_CONFIG = "host_config"
        const val KEY_INPUT_X = "input_x"
        const val KEY_INPUT_Y = "input_y"
        const val KEY_SEND_X = "send_x"
        const val KEY_SEND_Y = "send_y"

        /** 与 WechatAccessibilityService.resolveTapPair 中默认比例一致 */
        const val RATIO_INPUT_X = 450f / 1080f
        const val RATIO_INPUT_Y = 2280f / 2245f
        const val RATIO_SEND_X = 1000f / 1080f
        const val RATIO_SEND_Y = 2280f / 2245f
    }
}
