package com.agentime.ime.host.automation

interface AutomationController {
    fun launchWechat(): Boolean
    fun isWechatForeground(): Boolean
    fun focusInputArea(): Boolean
    fun clickSend(): Boolean
}
