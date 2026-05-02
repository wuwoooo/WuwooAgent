package com.agentime.ime.host.automation

/**
 * 手动执行模式：不依赖无障碍，交由人工完成打开微信/聚焦输入框/点击发送。
 */
class NoopAutomationController : AutomationController {
    override fun launchWechat(): Boolean = true
    override fun isWechatForeground(): Boolean = true
    override fun focusInputArea(): Boolean = true
    override fun clickSend(): Boolean = true
    override fun clickBack(): Boolean = true
    override fun openWechatSearch(): Boolean = true
    override fun focusWechatSearchInput(): Boolean = true
    override fun tapWechatSearchResult(contactName: String, keyword: String): Boolean = true
}
