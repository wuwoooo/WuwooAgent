package com.agentime.ime.host.ime

interface ImeController {
    fun injectText(text: String): Boolean
    fun isImeActive(): Boolean
}
