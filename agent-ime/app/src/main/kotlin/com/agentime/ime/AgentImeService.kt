package com.agentime.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * 最小自定义输入法：通过 [InputConnection.commitText] 向当前焦点编辑框注入文本。
 * 外部注入见 [InjectTextReceiver]。
 *
 * 说明：关闭全屏 IME；输入面板保持 **尽量矮**（见 [R.dimen.ime_keyboard_min_height]），避免不透明大块盖住微信底栏。
 */
class AgentImeService : InputMethodService() {

    /** 输入区根视图，用于 [onComputeInsets] 计算高度（部分 ROM 上需显式高度）。 */
    private var inputRootView: View? = null

    override fun onCreateInputView(): View {
        val v = layoutInflater.inflate(R.layout.input_view, null)
        inputRootView = v
        return v
    }

    /** 不展示候选词栏，减少额外占位。 */
    override fun onCreateCandidatesView(): View? = null

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        setCandidatesViewShown(false)
    }

    /** 避免全屏输入法抢占布局，导致微信底部「文字输入栏/发送」异常。 */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * 在部分机型上保证可见区域高度与系统一致，避免 insets 为 0 时宿主 App 重排错误。
     */
    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        if (!isFullscreenMode) {
            val v = inputRootView
            val minPx = resources.getDimensionPixelSize(R.dimen.ime_keyboard_min_height)
            val h = when {
                v == null -> minPx
                v.height > 0 -> v.height
                v.measuredHeight > 0 -> v.measuredHeight
                else -> minPx
            }
            outInsets.contentTopInsets = h
            outInsets.visibleTopInsets = h
        }
    }

    companion object {
        @Volatile
        private var instance: AgentImeService? = null

        /**
         * 供 [InjectTextReceiver] 调用：在当前 IME 会话中提交文本。
         * @return 是否已调用 commit（连接不存在时返回 false）
         */
        fun commitTextExternal(text: CharSequence): Boolean {
            val ic: InputConnection? = instance?.getCurrentInputConnection()
            if (ic == null) return false
            return ic.commitText(text, 1)
        }

        /**
         * 注入前尽量清空输入框，减少残留文字/剪贴板拼进发送内容（依赖宿主是否支持 deleteSurroundingText）。
         */
        fun clearInputBeforeInject(): Boolean {
            val ic = instance?.getCurrentInputConnection() ?: return false
            return try {
                if (!ic.deleteSurroundingText(5000, 5000)) {
                    repeat(40) {
                        ic.deleteSurroundingText(200, 200)
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }
}
