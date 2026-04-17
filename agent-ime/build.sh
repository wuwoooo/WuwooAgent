#!/usr/bin/env bash
# 在 agent-ime 根目录编译 Android 应用（默认 assembleDebug）。
# 用法: ./build.sh [Gradle 任务与参数…]
# 示例: ./build.sh assembleRelease
#       ./build.sh build --no-daemon

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

if [[ ! -x ./gradlew ]]; then
  chmod +x ./gradlew
fi

# 清理异常残留的中间产物（例如 Finder/复制产生的 “xxx 2.xml / xxx 2.jar”），
# 否则 aapt / d8 在资源解析或 dex 合并阶段会直接失败。
find "$ROOT/app/build" -name '* 2.*' -type f -delete 2>/dev/null || true

if [[ $# -eq 0 ]]; then
  ./gradlew clean assembleDebug
else
  ./gradlew "$@"
fi

echo ""
echo "==> 同步到 GitHub"
(
  cd "$ROOT/.."
  git add .
  if ! git diff-index --quiet HEAD --; then
    git commit -m "Auto-commit from build.sh at $(date '+%Y-%m-%d %H:%M:%S')"
    git push
  else
    echo "没有检测到需要提交的变更"
  fi
)
