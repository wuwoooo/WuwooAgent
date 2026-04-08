#!/usr/bin/env bash
# 将本地图片推到手机并触发 OCR（需已安装本 APK，且路径可读）。
# 用法：./scripts/adb-ocr.sh /path/to/chat.png

set -euo pipefail
SRC="${1:?请传入本地图片路径}"
REMOTE="/sdcard/Download/agent_ocr_test.png"
PKG="com.agentime.ime"
ACTION="com.agentime.ime.action.OCR_IMAGE"

adb push "$SRC" "$REMOTE"
adb shell am broadcast -n "${PKG}/.OcrRequestReceiver" -a "$ACTION" --es image_path "$REMOTE"
echo "已发送 OCR 请求；请在 Auto.js 监听 com.agentime.ime.action.OCR_RESULT 或查看 logcat。"
