import fs from "node:fs/promises";

const BASE_URL = "https://ilinkai.weixin.qq.com";

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function randomUinBase64() {
  const n = Math.floor(Math.random() * 0xffffffff);
  return Buffer.from(String(n)).toString("base64");
}

function buildHeaders(botToken = null) {
  const headers = {
    "Content-Type": "application/json",
    "AuthorizationType": "ilink_bot_token",
    "X-WECHAT-UIN": randomUinBase64(),
  };

  if (botToken) {
    headers.Authorization = `Bearer ${botToken}`;
  }

  return headers;
}

async function apiGet(path, botToken = null) {
  const res = await fetch(`${BASE_URL}${path}`, {
    method: "GET",
    headers: buildHeaders(botToken),
  });

  const text = await res.text();

  if (!res.ok) {
    throw new Error(`GET ${path} failed: ${res.status} ${text}`);
  }

  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`GET ${path} returned non-json: ${text}`);
  }
}

async function apiPost(path, body, botToken) {
  const res = await fetch(`${BASE_URL}${path}`, {
    method: "POST",
    headers: buildHeaders(botToken),
    body: JSON.stringify(body),
  });

  const text = await res.text();

  if (!res.ok) {
    throw new Error(`POST ${path} failed: ${res.status} ${text}`);
  }

  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`POST ${path} returned non-json: ${text}`);
  }
}

async function getBotQrCode() {
  return apiGet("/ilink/bot/get_bot_qrcode?bot_type=3");
}

async function pollQrStatus(qrcode) {
  return apiGet(`/ilink/bot/get_qrcode_status?qrcode=${encodeURIComponent(qrcode)}`);
}

async function getUpdates(botToken, getUpdatesBuf = "") {
  return apiPost(
    "/ilink/bot/getupdates",
    {
      get_updates_buf: getUpdatesBuf,
      base_info: {
        channel_version: "1.0.2",
      },
    },
    botToken
  );
}

async function sendTextMessage(botToken, toUserId, contextToken, text) {
  return apiPost(
    "/ilink/bot/sendmessage",
    {
      msg: {
        to_user_id: toUserId,
        message_type: 2,
        message_state: 2,
        context_token: contextToken,
        item_list: [
          {
            type: 1,
            text_item: { text },
          },
        ],
      },
    },
    botToken
  );
}

function extractInboundText(msg) {
  if (!msg?.item_list?.length) return null;
  const first = msg.item_list[0];
  return first?.text_item?.text ?? null;
}

async function main() {
  console.log("1) 获取登录二维码...");
  const qrResp = await getBotQrCode();

  console.log("二维码响应：", qrResp);

  if (qrResp.qrcode_img_content) {
    await fs.writeFile("qrcode.txt", qrResp.qrcode_img_content, "utf8");
    console.log("已保存 qrcode.txt，可查看 base64 内容");
  }

  if (qrResp.url) {
    console.log("扫码链接：", qrResp.url);
  }

  if (!qrResp.qrcode) {
    throw new Error("没有拿到 qrcode 字段，流程终止");
  }

  console.log("2) 轮询扫码状态，请用微信扫码确认...");
  let botToken = null;
  let botBaseUrl = null;

  while (true) {
    const status = await pollQrStatus(qrResp.qrcode);
    console.log("扫码状态：", status);

    if (status.status === "confirmed") {
      botToken = status.bot_token;
      botBaseUrl = status.baseurl || null;
      break;
    }

    if (status.status === "expired") {
      throw new Error("二维码已过期，请重新运行脚本");
    }

    await sleep(1000);
  }

  if (!botToken) {
    throw new Error("登录成功但没有拿到 bot_token");
  }

  console.log("3) 登录成功");
  console.log("botBaseUrl =", botBaseUrl);
  console.log("botToken 前20位 =", String(botToken).slice(0, 20) + "...");

  let getUpdatesBuf = "";

  console.log("4) 开始长轮询收消息，收到文本后自动回声回复...");
  while (true) {
    try {
      const result = await getUpdates(botToken, getUpdatesBuf);

      if (result.get_updates_buf) {
        getUpdatesBuf = result.get_updates_buf;
      }

      const msgs = result.msgs || [];
      if (!msgs.length) {
        continue;
      }

      for (const msg of msgs) {
        console.log("收到消息：", JSON.stringify(msg, null, 2));

        if (msg.message_type !== 1) {
          continue;
        }

        const inboundText = extractInboundText(msg);
        if (!inboundText) {
          continue;
        }

        const toUserId = msg.from_user_id;
        const contextToken = msg.context_token;

        if (!toUserId || !contextToken) {
          console.log("缺少 toUserId 或 contextToken，跳过");
          continue;
        }

        const reply = `回复：${inboundText}`;
        console.log("准备回复：", reply);

        const sendResp = await sendTextMessage(
          botToken,
          toUserId,
          contextToken,
          reply
        );

        console.log("发送结果：", sendResp);
      }
    } catch (err) {
      console.error("轮询异常：", err);
      await sleep(2000);
    }
  }
}

main().catch((err) => {
  console.error("程序失败：", err);
  process.exit(1);
});
