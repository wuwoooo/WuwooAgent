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
  error: "",
  requestId: ""
};

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
      ocrState.error = intent.getStringExtra("error") || "";
      ocrState.done = true;

      writeLog(
        "收到 OCR 结果 success=" + ocrState.success +
        " text_length=" + (ocrState.text ? ocrState.text.length : 0) +
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

  if (!requestScreenCapture()) {
    throw new Error("请求截图权限失败");
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
    captureChatScreen();

    let requestId = String(UUID.randomUUID());
    requestLocalOcr(requestId);

    let ocrText = waitForOcrResult(20000).trim();
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
