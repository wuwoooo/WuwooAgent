package com.agentime.ime.host.orchestrator

enum class HostState {
    IDLE,
    WECHAT_READY,
    INPUT_FOCUSED,
    SCREEN_CAPTURED,
    REPLY_READY,
    TEXT_INJECTED,
    SENT,
    FAILED,
}
