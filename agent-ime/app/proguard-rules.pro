# ── ML Kit 文本识别 ──
# ML Kit 内部使用反射加载模型和 TFLite 推理引擎，
# R8 混淆会裁掉这些类导致 OCR 静默返回空结果。
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common** { *; }

# TFLite 运行时（ML Kit 底层依赖）
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ML Kit 自身自带的 keep 规则可能被 R8 忽略，显式保留
-keep class com.google.mlkit.vision.text.** { *; }
-keep class com.google.mlkit.vision.common.** { *; }
-keep class com.google.mlkit.common.** { *; }

# 保留 ML Kit 模型资源文件（tflite 模型在 assets/ 下）
-keepclassmembers class * {
    @com.google.mlkit.common.internal.MlKitThreadPool *;
}

# ── 通用 ──
# 保留无障碍服务相关类
-keep class com.agentime.ime.host.automation.WechatAccessibilityService { *; }
# 保留 OkHttp（如果有使用）
-dontwarn okhttp3.**
-dontwarn okio.**
