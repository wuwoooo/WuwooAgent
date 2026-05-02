package com.agentime.ime.host.automation

interface AutomationController {
    fun launchWechat(): Boolean
    fun isWechatForeground(): Boolean
    fun focusInputArea(): Boolean
    fun clickSend(): Boolean
    fun clickBack(): Boolean
    fun openWechatSearch(): Boolean
    fun focusWechatSearchInput(): Boolean
    fun tapWechatSearchResult(contactName: String, keyword: String): Boolean
    fun isCurrentChatTarget(contactName: String): Boolean
}
