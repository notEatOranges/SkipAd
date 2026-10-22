# CHANGELOG

> 规矩：每轮修改必须记录（改了什么/为什么/影响面/踩坑），换电脑先读本文件再动手。

## 0.1.0 - 2026-10-22 · 节点1：M1 骨架 + 通用跳过引擎

**改了什么**
- 全新仓库。Kotlin + minSdk 26 / targetSdk 34 / AGP 8.2.2 / Gradle 8.2 / Kotlin 1.9.22，单模块，依赖仅 core-ktx + appcompat；
- 核心链路落地：`SkipAccessibilityService`（事件节流 150ms + 包名黑名单 + 会话冷却 4s）→ `NodeScanner`（BFS 遍历，双上限 800 节点/400ms；多语言关键字 + 倒计时正则；text/contentDescription 双查；沿父链找 clickable 祖先）→ `ClickPerformer`（ACTION_CLICK → 祖先逐级 → dispatchGesture 坐标兜底）；
- 防误触三保险：节点文本 ≤24 字、可点击祖先面积 ≤12% 屏、同窗口冷却；
- UI：服务状态卡（含跳转系统设置引导）、跳过统计、最近日志（50 条 ring buffer，SharedPreferences）；
- 文档：`docs/design.md`（五层匹配/快照截屏方案/规则格式/红线清单）、README、本 CHANGELOG；
- **Manifest 零权限**：不申请 INTERNET/存储/任何权限（隐私红线，永久保持）。

**为什么**
- 用户的类李跳跳自用需求；M1 先用"通用关键字 + 防误触约束"覆盖 ~80% 常规开屏，后续节点再加 per-App 规则与调试工具。

**影响面**
- 全新仓库，无存量影响。未经真机验证，预期下一节点在 Android Studio 首次构建并真机跑通。

**踩坑/备注**
- 本节点在 VM103（无 Android SDK/JDK）上完成，**代码未编译验证**：gradle wrapper 已用 Gradle 8.2 官方发行版生成，明天任何电脑 clone 后 Android Studio 直接可开；
- 有意不调用 `AccessibilityNodeInfo.recycle()`（API 33+ 已废弃，低版本不回收仅轻微内存压力，见 NodeScanner 注释）；
- `isAccessibilityTool` 属性写在服务配置 XML（API 31+ 强制声明要求，低版本系统忽略该属性不报错）；
- 图标用纯 XML adaptive icon（mipmap-anydpi-v26），minSdk 26 恰好全覆盖，无需 PNG 兜底；
- colors.xml 初版手滑混入一行 string 占位符，已修（历史见 git 历史）。
