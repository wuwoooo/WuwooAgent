package com.agentime.ime.host.automation

import android.content.Context
import android.content.Intent

class AccessibilityAutomationController(private val context: Context) : AutomationController {
    override fun launchWechat(): Boolean {
        val ok = WechatAccessibilityService.launchWechat(context)
        if (ok) WechatAccessibilityService.warmupInputAfterLaunch()
        return ok
    }

    override fun isWechatForeground(): Boolean = WechatAccessibilityService.isWechatForeground()

    override fun focusInputArea(): Boolean = WechatAccessibilityService.focusInputArea()

    override fun clickSend(): Boolean = WechatAccessibilityService.clickSend()
    override fun clickBack(): Boolean = WechatAccessibilityService.clickBack()
    override fun openWechatSearch(): Boolean = WechatAccessibilityService.openWechatSearch()
    override fun focusWechatSearchInput(): Boolean = WechatAccessibilityService.focusWechatSearchInput()
    override fun tapWechatSearchResult(contactName: String, keyword: String): Boolean =
        WechatAccessibilityService.tapWechatSearchResult(contactName, keyword)

    override fun isCurrentChatTarget(contactName: String): Boolean =
        WechatAccessibilityService.isCurrentChatTarget(contactName)
}
