# 本项目默认 release 未开启混淆（isMinifyEnabled = false）。
# 若后续开启混淆，注意：
# - NotificationListenerService 子类在 Manifest 中声明，系统通过组件名加载，无需额外 keep；
# - org.json 为平台内置类，不参与混淆；
# - 如新增反射/序列化框架，请在此补充 keep 规则。
