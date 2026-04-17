#!/usr/bin/env bash
# 一键将本目录同步到腾讯云服务器并重启 uvicorn（覆盖远程 .env，但不覆盖 uploads）。
# 用法：在 FastAPI 目录执行 ./deploy.sh
# 可选：SSH_KEY=~/.ssh/other.pem REMOTE_DIR=/path ./deploy.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ---------- 可按需覆盖的环境变量 ----------
: "${SSH_KEY:=$HOME/.ssh/id_ed25519_tencent}"
: "${REMOTE_USER_HOST:=ubuntu@118.24.71.189}"
: "${REMOTE_DIR:=/home/ubuntu/fastapi-app}"

RSYNC_RSH="ssh -i ${SSH_KEY} -o StrictHostKeyChecking=accept-new"
SSH_BASE=(ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new)

if [[ ! -f "$SSH_KEY" ]]; then
  echo "错误：未找到私钥文件: $SSH_KEY" >&2
  exit 1
fi

echo "==> 目标: ${REMOTE_USER_HOST}:${REMOTE_DIR}"
echo "==> 确保远程目录存在"
"${SSH_BASE[@]}" "${REMOTE_USER_HOST}" "mkdir -p '${REMOTE_DIR}'"

echo "==> rsync 同步代码（排除 venv、uploads 等）"
rsync -avz \
  -e "$RSYNC_RSH" \
  --exclude 'venv/' \
  --exclude '__pycache__/' \
  --exclude 'uploads/' \
  --exclude '.git/' \
  --exclude '*.db' \
  --exclude '*.db-*' \
  --exclude 'log.txt' \
  ./ "${REMOTE_USER_HOST}:${REMOTE_DIR}/"

echo "==> 远程：安装依赖并重启服务"
"${SSH_BASE[@]}" "${REMOTE_USER_HOST}" bash -s << EOF
set -euo pipefail
cd '${REMOTE_DIR}'

if [[ ! -d venv ]]; then
  echo "创建 venv..."
  python3 -m venv venv
fi
venv/bin/pip install -q -U pip
venv/bin/pip install -q -r requirements.txt

echo "停止旧 uvicorn..."
pkill -f "uvicorn main:app" 2>/dev/null || true
sleep 1

nohup venv/bin/uvicorn main:app --host 0.0.0.0 --port 8000 >> log.txt 2>&1 &
echo \$! > uvicorn.pid
echo "已启动 PID=\$(cat uvicorn.pid)，日志: ${REMOTE_DIR}/log.txt"
EOF

echo ""
echo "==> 同步到 GitHub"
(
  cd "$SCRIPT_DIR/.."
  git add .
  if ! git diff-index --quiet HEAD --; then
    git commit -m "Auto-commit from deploy.sh at $(date '+%Y-%m-%d %H:%M:%S')"
    git push
  else
    echo "没有检测到需要提交的变更"
  fi
)

echo ""
echo "部署完成。"
echo "  健康检查: curl -s http://118.24.71.189:8000/health"
echo "  当前部署会同步本地 .env 到服务器 ${REMOTE_DIR}/.env"
echo "  查看日志: ssh -i ${SSH_KEY} -o StrictHostKeyChecking=accept-new ${REMOTE_USER_HOST} 'tail -f ${REMOTE_DIR}/log.txt'"
