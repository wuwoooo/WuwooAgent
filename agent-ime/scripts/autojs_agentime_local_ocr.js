/*
 * Auto.js + Agent IME 本地 OCR 联调脚本
 *
 * 闭环：
 * 1. launchApp("微信")
 * 2. 当前聊天页截图到本地
 * 3. 广播给 Agent IME 做本地 OCR
 * 4. 拿 OCR 文本后，仅把文本发给后端 Agent
 * 5. 后端返回 reply_text
 * 6. 广播给 Agent IME 注入文本
 * 7. 点击发送坐标
 *
 * 前置条件：
 * - 手机已安装并启用 Agent IME 输入法
 * - 当前已手动打开到目标微信聊天页
 * - Agent IME 的 OcrRequestReceiver / InjectTextReceiver 可用
 * - Auto.js 已授予无障碍、截图、悬浮窗、网络权限
 */

importClass(android.content.BroadcastReceiver);
importClass(android.content.Intent);
importClass(android.content.IntentFilter);
importClass(android.content.ComponentName);
importClass(java.util.UUID);

const API_URL = "http://118.24.71.189/api/wechat/chat";
const OCR_ACTION = "com.agentime.ime.action.OCR_IMAGE";
const OCR_RESULT_ACTION = "com.agentime.ime.action.OCR_RESULT";
const INJECT_ACTION = "com.agentime.ime.action.INJECT_TEXT";

const IMG_DIR = "/sdcard/Download/agent-ime-autojs";
const IMG_PATH = IMG_DIR + "/chat_latest.png";
const LOG_PATH = IMG_DIR + "/autojs_local_ocr.log";

const CONTACT_NAME = "张三"; // 先按你的测试联系人写死，后续可改成手输/读标题
const SESSION_ID = "wx_local_ocr_demo";

const INPUT_X_RATIO = 0.42;
const INPUT_Y_RATIO = 0.94;
const SEND_X_RATIO = 0.86;
const SEND_Y_RATIO = 0.92;

let ocrState = {
  done: false,
  success: false,
  text: "",
  blocksJson: "[]",
  blocks: [],
  error: "",
  requestId: ""
};

let screenCaptureReady = false;

function ensureDir() {
  files.ensureDir(IMG_DIR + "/placeholder.txt");
}

function writeLog(msg) {
  let line = new Date().toLocaleString() + " | " + msg + "\n";
  files.append(LOG_PATH, line);
  log(msg);
}

function resetOcrState(requestId) {
  ocrState.done = false;
  ocrState.success = false;
  ocrState.text = "";
  ocrState.blocksJson = "[]";
  ocrState.blocks = [];
  ocrState.error = "";
  ocrState.requestId = requestId;
}

function registerOcrReceiver() {
  const filter = new IntentFilter();
  filter.addAction(OCR_RESULT_ACTION);

  const receiver = new JavaAdapter(BroadcastReceiver, {
    onReceive: function(_context, intent) {
      if (!intent || intent.getAction() !== OCR_RESULT_ACTION) return;

      let reqId = intent.getStringExtra("request_id") || "";
      if (ocrState.requestId && reqId && reqId !== ocrState.requestId) {
        writeLog("收到其他 request_id 的 OCR 结果，忽略: " + reqId);
        return;
      }

      ocrState.success = !!intent.getBooleanExtra("success", false);
      ocrState.text = intent.getStringExtra("ocr_text") || "";
      ocrState.blocksJson = intent.getStringExtra("ocr_blocks_json") || "[]";
      ocrState.blocks = parseOcrBlocks(ocrState.blocksJson);
      ocrState.error = intent.getStringExtra("error") || "";
      ocrState.done = true;

      writeLog(
        "收到 OCR 结果 success=" + ocrState.success +
        " text_length=" + (ocrState.text ? ocrState.text.length : 0) +
        " blocks=" + ocrState.blocks.length +
        " error=" + ocrState.error
      );
    }
  });

  context.registerReceiver(receiver, filter);
  return receiver;
}

function unregisterReceiverSafe(receiver) {
  if (!receiver) return;
  try {
    context.unregisterReceiver(receiver);
  } catch (e) {
    writeLog("注销 OCR receiver 失败: " + e);
  }
}

function ensureWechatForeground() {
  launchApp("微信");
  sleep(5000);
  writeLog("已执行 launchApp(微信)，当前包名=" + currentPackage());
}

function captureChatScreen() {
  ensureDir();

  if (!screenCaptureReady) {
    if (!requestScreenCapture()) {
      throw new Error("请求截图权限失败");
    }
    screenCaptureReady = true;
  }

  sleep(800);
  let img = captureScreen();
  images.save(img, IMG_PATH);
  writeLog("截图已保存: " + IMG_PATH);
}

function requestLocalOcr(requestId) {
  resetOcrState(requestId);

  let intent = new Intent();
  intent.setAction(OCR_ACTION);
  intent.setComponent(new ComponentName("com.agentime.ime", "com.agentime.ime.OcrRequestReceiver"));
  intent.putExtra("image_path", IMG_PATH);
  intent.putExtra("request_id", requestId);
  context.sendBroadcast(intent);

  writeLog("已发送本地 OCR 广播 request_id=" + requestId);
}

function waitForOcrResult(timeoutMs) {
  let start = new Date().getTime();
  while (new Date().getTime() - start < timeoutMs) {
    if (ocrState.done) {
      if (!ocrState.success) {
        throw new Error("本地 OCR 失败: " + (ocrState.error || "未知错误"));
      }
      return ocrState.text || "";
    }
    sleep(200);
  }
  throw new Error("等待本地 OCR 结果超时");
}

function waitForOcrPayload(timeoutMs) {
  let text = waitForOcrResult(timeoutMs);
  return {
    text: text,
    blocks: ocrState.blocks || []
  };
}

function parseOcrBlocks(blocksJson) {
  try {
    let raw = JSON.parse(blocksJson || "[]");
    if (!raw || !raw.length) return [];
    let out = [];
    for (let i = 0; i < raw.length; i++) {
      let item = raw[i] || {};
      let left = Number(item.left);
      let top = Number(item.top);
      let right = Number(item.right);
      let bottom = Number(item.bottom);
      if (!isFinite(left) || !isFinite(top) || !isFinite(right) || !isFinite(bottom)) continue;
      if (right <= left || bottom <= top) continue;
      out.push({
        text: String(item.text || ""),
        left: left,
        top: top,
        right: right,
        bottom: bottom
      });
    }
    return out;
  } catch (e) {
    writeLog("解析 OCR 坐标 JSON 失败: " + e);
    return [];
  }
}

function blockCenterX(block) {
  return (block.left + block.right) / 2;
}

function blockCenterY(block) {
  return (block.top + block.bottom) / 2;
}

function normalizeOcrToken(text) {
  return String(text || "")
    .replace(/\s+/g, "")
    .replace(/[“”″]/g, "\"")
    .replace(/[‘’]/g, "'");
}

function blockToString(block) {
  if (!block) return "null";
  return "'" + block.text + "'@[" +
    Math.round(block.left) + "," + Math.round(block.top) + "," +
    Math.round(block.right) + "," + Math.round(block.bottom) + "]";
}

function isVoiceDurationText(text) {
  let token = normalizeOcrToken(text);
  if (/^\d{1,2}"$/.test(token)) return true;
  if (/^\d{1,2}'\d{1,2}"?$/.test(token)) return true;

  // 部分机型会漏识别秒符号；仅在很短的数字文本上做弱匹配。
  if (/^\d{1,2}$/.test(token)) {
    let seconds = parseInt(token, 10);
    return seconds > 0 && seconds <= 60;
  }
  return false;
}

function isTranscribeText(text) {
  let token = normalizeOcrToken(text);
  return token.indexOf("转文字") >= 0 || token.indexOf("轉文字") >= 0;
}

function captureAndOcr(timeoutMs) {
  captureChatScreen();
  let requestId = String(UUID.randomUUID());
  requestLocalOcr(requestId);
  return waitForOcrPayload(timeoutMs || 20000);
}

function findLatestInboundVoiceDurationBlock(blocks) {
  let candidates = [];
  for (let i = 0; i < blocks.length; i++) {
    let block = blocks[i];
    let cx = blockCenterX(block);
    let cy = blockCenterY(block);
    if (cy < device.height * 0.12 || cy > device.height * 0.88) continue;
    if (cx > device.width * 0.58) continue;
    if (!isVoiceDurationText(block.text)) continue;
    candidates.push(block);
  }

  writeLog("语音时长候选数量=" + candidates.length + " " + candidates.map(blockToString).join(" | "));
  if (!candidates.length) return null;

  candidates.sort(function(a, b) {
    return blockCenterY(b) - blockCenterY(a);
  });
  return candidates[0];
}

function findShortcutTranscribeBlock(blocks, voiceBlock) {
  let voiceCx = blockCenterX(voiceBlock);
  let voiceCy = blockCenterY(voiceBlock);
  let candidates = [];

  for (let i = 0; i < blocks.length; i++) {
    let block = blocks[i];
    if (!isTranscribeText(block.text)) continue;
    let cx = blockCenterX(block);
    let cy = blockCenterY(block);
    if (cx <= voiceCx + 30) continue;
    if (Math.abs(cy - voiceCy) > 90) continue;
    candidates.push(block);
  }

  writeLog("快捷转文字候选数量=" + candidates.length + " " + candidates.map(blockToString).join(" | "));
  if (!candidates.length) return null;

  candidates.sort(function(a, b) {
    let ay = Math.abs(blockCenterY(a) - voiceCy);
    let by = Math.abs(blockCenterY(b) - voiceCy);
    if (ay !== by) return ay - by;
    return blockCenterX(a) - blockCenterX(b);
  });
  return candidates[0];
}

function clickBlockCenter(block, reason) {
  let x = parseInt(blockCenterX(block), 10);
  let y = parseInt(blockCenterY(block), 10);
  writeLog(reason + "，点击 OCR 坐标: " + x + "," + y + " block=" + blockToString(block));
  click(x, y);
  sleep(500);
}

function longPressVoiceBubble(voiceBlock) {
  let x = parseInt(Math.min(device.width * 0.52, blockCenterX(voiceBlock) + 80), 10);
  let y = parseInt(blockCenterY(voiceBlock), 10);
  writeLog("未找到快捷转文字，长按语音气泡估算点: " + x + "," + y + " anchor=" + blockToString(voiceBlock));
  press(x, y, 900);
  sleep(900);
}

function findMenuTranscribeBlock(blocks, voiceBlock) {
  let voiceCy = voiceBlock ? blockCenterY(voiceBlock) : device.height / 2;
  let candidates = [];
  for (let i = 0; i < blocks.length; i++) {
    let block = blocks[i];
    if (!isTranscribeText(block.text)) continue;
    let cy = blockCenterY(block);
    // 菜单一般在语音气泡上方；保留少量容错，避免点击旧聊天内容。
    if (cy > voiceCy + 40) continue;
    candidates.push(block);
  }

  writeLog("菜单转文字候选数量=" + candidates.length + " " + candidates.map(blockToString).join(" | "));
  if (!candidates.length) return null;

  candidates.sort(function(a, b) {
    return Math.abs(blockCenterY(a) - voiceCy) - Math.abs(blockCenterY(b) - voiceCy);
  });
  return candidates[0];
}

function clickMenuTranscribeFallback(voiceBlock) {
  let x = parseInt(Math.max(70, Math.min(device.width - 70, blockCenterX(voiceBlock) - 140)), 10);
  let y = parseInt(Math.max(device.height * 0.18, blockCenterY(voiceBlock) - 155), 10);
  writeLog("OCR 未识别菜单转文字，使用菜单相对位置兜底点击: " + x + "," + y);
  click(x, y);
  sleep(500);
}

function stripVoiceUiNoise(text) {
  return String(text || "")
    .split(/\n+/)
    .map(function(line) { return line.trim(); })
    .filter(function(line) {
      if (!line) return false;
      if (isVoiceDurationText(line)) return false;
      if (isTranscribeText(line)) return false;
      if (/^(听筒播放|收藏|背景播放|删除|多选|引用|提醒)$/.test(normalizeOcrToken(line))) return false;
      return true;
    })
    .join("\n")
    .trim();
}

function extractTranscribedTextNearVoice(blocks, voiceBlock) {
  let voiceCy = blockCenterY(voiceBlock);
  let lines = [];
  for (let i = 0; i < blocks.length; i++) {
    let block = blocks[i];
    let cy = blockCenterY(block);
    let token = normalizeOcrToken(block.text);
    if (cy < voiceCy - 35) continue;
    if (cy > Math.min(device.height * 0.9, voiceCy + 280)) continue;
    if (isVoiceDurationText(block.text)) continue;
    if (isTranscribeText(block.text)) continue;
    if (/^(听筒播放|收藏|背景播放|删除|多选|引用|提醒)$/.test(token)) continue;
    if (!/[\u4e00-\u9fa5]/.test(block.text)) continue;
    lines.push(block.text.trim());
  }
  return lines.join("\n").trim();
}

function waitForTranscriptionStable(voiceBlock, timeoutMs) {
  let start = new Date().getTime();
  let lastStableText = "";
  let stableCount = 0;
  let lastPayload = null;

  while (new Date().getTime() - start < timeoutMs) {
    let payload = captureAndOcr(12000);
    lastPayload = payload;

    let nearbyText = extractTranscribedTextNearVoice(payload.blocks, voiceBlock);
    let cleaned = nearbyText;
    writeLog(
      "等待转文字稳定: cleaned_length=" + cleaned.length +
      " stableCount=" + stableCount +
      " nearby_length=" + nearbyText.length +
      " text=" + cleaned.slice(0, 80).replace(/\n/g, " / ")
    );

    if (cleaned && cleaned === lastStableText) {
      stableCount++;
    } else {
      lastStableText = cleaned;
      stableCount = cleaned ? 1 : 0;
    }

    if (stableCount >= 2) {
      writeLog("语音转文字结果已稳定");
      return payload;
    }
    sleep(700);
  }

  throw new Error("等待语音转文字稳定超时");
}

function prepareLatestMessageForOcr() {
  let initialPayload = captureAndOcr(20000);
  let voiceBlock = findLatestInboundVoiceDurationBlock(initialPayload.blocks);
  if (!voiceBlock) {
    writeLog("未发现最新左侧语音时长，按普通文字消息处理");
    return initialPayload;
  }

  writeLog("发现疑似最新语音消息: " + blockToString(voiceBlock));
  let shortcutBlock = findShortcutTranscribeBlock(initialPayload.blocks, voiceBlock);
  if (shortcutBlock) {
    clickBlockCenter(shortcutBlock, "点击语音气泡旁快捷转文字");
    return waitForTranscriptionStable(voiceBlock, 15000);
  }

  longPressVoiceBubble(voiceBlock);
  let menuPayload = captureAndOcr(12000);
  let menuBlock = findMenuTranscribeBlock(menuPayload.blocks, voiceBlock);
  if (menuBlock) {
    clickBlockCenter(menuBlock, "点击长按菜单转文字");
  } else {
    clickMenuTranscribeFallback(voiceBlock);
  }

  return waitForTranscriptionStable(voiceBlock, 15000);
}

function requestAgentReply(ocrText) {
  writeLog("开始请求后端文本 Agent，ocr_text length=" + ocrText.length);

  let resp = http.post(API_URL, {
    session_id: SESSION_ID,
    contact_name: CONTACT_NAME,
    ocr_text: ocrText
  });

  if (resp.statusCode !== 200) {
    throw new Error("后端请求失败 status=" + resp.statusCode + " body=" + resp.body.string());
  }

  let body = resp.body.string();
  writeLog("后端原始返回: " + body);

  let data = JSON.parse(body);
  if (!data.ok) {
    throw new Error("后端返回 ok=false: " + (data.error || "未知错误"));
  }

  let replyText = String(data.reply_text || "").trim();
  if (!replyText) {
    throw new Error("后端未返回 reply_text");
  }

  return replyText;
}

function focusInputArea() {
  let x = parseInt(device.width * INPUT_X_RATIO, 10);
  let y = parseInt(device.height * INPUT_Y_RATIO, 10);
  click(x, y);
  sleep(1200);
  writeLog("点击输入区: " + x + "," + y);
}

function injectReplyText(text) {
  let intent = new Intent();
  intent.setAction(INJECT_ACTION);
  intent.setComponent(new ComponentName("com.agentime.ime", "com.agentime.ime.InjectTextReceiver"));
  intent.putExtra("text", text);
  context.sendBroadcast(intent);

  writeLog("已广播注入 reply_text，长度=" + text.length);
  sleep(1200);
}

function clickSendHotArea() {
  let x = parseInt(device.width * SEND_X_RATIO, 10);
  let y = parseInt(device.height * SEND_Y_RATIO, 10);
  click(x, y);
  sleep(1000);
  writeLog("点击发送坐标: " + x + "," + y);
}

function main() {
  ensureWechatForeground();

  const receiver = registerOcrReceiver();
  try {
    let preparedPayload = prepareLatestMessageForOcr();
    let ocrText = (preparedPayload.text || "").trim();
    writeLog("本地 OCR 成功，文本长度=" + ocrText.length);

    if (!ocrText) {
      throw new Error("本地 OCR 返回空文本");
    }

    let replyText = requestAgentReply(ocrText);
    writeLog("拿到 reply_text: " + replyText);

    focusInputArea();
    injectReplyText(replyText);
    clickSendHotArea();

    toast("Auto.js + Agent IME 本地 OCR 流程完成");
    writeLog("===== 流程执行完成 =====");
  } finally {
    unregisterReceiverSafe(receiver);
  }
}

try {
  main();
} catch (e) {
  ensureDir();
  writeLog("执行异常: " + e);
  toast("执行失败: " + e);
}
