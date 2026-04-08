"""
本地集成测试：腾讯云智能体开发平台对话端 WebSocket V2（GetWsToken + Socket.IO）。

用法（在 FastAPI 目录下）：
  cp .env.example .env   # 填入 TENCENT_SECRET_ID / TENCENT_SECRET_KEY / TENCENT_ADP_APP_KEY
  ./venv/bin/python test_adp_ws_local.py
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path

from dotenv import load_dotenv

_ROOT = Path(__file__).resolve().parent
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

# 优先 .env；若不存在则读取 .env.example（本地误把密钥写在 example 里时也能跑通测试）
_env = _ROOT / ".env"
_example = _ROOT / ".env.example"
if _env.is_file():
    load_dotenv(_env)
elif _example.is_file():
    load_dotenv(_example)
else:
    load_dotenv(_env)

from adp_client import load_adp_config_from_env, run_adp_chat  # noqa: E402


async def _main() -> int:
    try:
        sid, skey, region, app_key = load_adp_config_from_env()
    except RuntimeError as e:
        print("失败：", e)
        print("请在 FastAPI/.env（或 .env.example）中配置 TENCENT_SECRET_ID、TENCENT_SECRET_KEY、TENCENT_ADP_APP_KEY")
        return 2

    print("已通过环境校验，region =", region)
    print("正在 GetWsToken 并建立 WebSocket，发送测试问题…")

    try:
        reply = await run_adp_chat(
            secret_id=sid,
            secret_key=skey,
            region=region,
            bot_app_key=app_key,
            session_id="local-ws-v2-test-session",
            user_text="你好，请用不超过两句话自我介绍。",
        )
    except Exception as e:
        print("调用失败：", e)
        return 1

    print("--- 智能体 reply（节选）---")
    preview = reply[:800] + ("…" if len(reply) > 800 else "")
    print(preview)
    print("--- 测试通过 ---")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(_main()))
