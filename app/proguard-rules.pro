# R8 混淆规则（isMinifyEnabled = true + shrinkResources = true）
#
# 核心原则：只在 R8 静态分析不够时才加 keep，否则会阻碍裁剪。

# ---- JNI ----
# llama.cpp C++ 侧通过 JNI 函数名（Java_com_notifyme_android_LlamaJni_nativeXxx）
# 反射调用，R8 重命名会断链。
-keep class com.notifyme.android.LlamaJni { *; }

# ---- 序列化 ----
# ChatMessage / AnalysisCaseRecord / ReminderRecord / AdvisorReport 等
# 通过 org.json 手动序列化（put/optXxx），R8 直接调用 getter/setter 不会改名，
# 无需额外 keep。但如果后续改用 Gson/Moshi 反射，需在此补充。

# ---- WorkManager ----
# Worker 子类在构建脚本中通过类名引用，R8 可追踪；无需额外 keep。

# ---- AccessibilityService / NotificationListenerService ----
# Manifest 中声明，系统通过组件名加载；AGP 自动生成 keep 规则。

# ---- 保险：保留注解和异常类名（崩溃堆栈可读） ----
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature

# ---- 调试用：映射文件输出到 build/outputs/mapping/ ----
-printusage build/outputs/mapping/usage.txt
