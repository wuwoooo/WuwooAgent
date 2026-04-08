#!/usr/bin/env bash
# 用法：安装 APK 后，在微信聊天页切换到「Agent 代聊输入法」并聚焦输入框，再执行：
#   ./scripts/adb-inject.sh "要注入的文字"

set -euo pipefail
TEXT="${1:-测试文本}"
PKG="com.agentime.ime"
ACTION="com.agentime.ime.action.INJECT_TEXT"

adb shell am broadcast -n "${PKG}/.InjectTextReceiver" -a "$ACTION" --es text "$TEXT"
