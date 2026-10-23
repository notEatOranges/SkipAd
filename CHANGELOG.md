# CHANGELOG

> 规矩：每轮修改必须记录（改了什么/为什么/影响面/踩坑），换电脑先读本文件再动手。

## 0.2.0 - 2026-10-23 · 无障碍保活 + 首次真机部署

**改了什么**
- 新增 `keepalive/` 三件套：`KeepAlive`（保活中枢：配置态/运行态双检查、静默复活写入、周期 Job 调度、掉线本地通知、复活冷却阀）、`KeepAliveJobService`（JobScheduler 原生 15 分钟周期自检，persisted 跨重启，刻意不引 WorkManager 保持零重型依赖）、`BootReceiver`（BOOT_COMPLETED + MY_PACKAGE_REPLACED 兜底，开机/升级后立即尝试恢复）；
- 服务本体加 `onUnbind` 钩子：被解绑后的黄金几秒进程还活着，延迟 1.5s 自检——静默模式当场拉起，提醒模式立刻发通知；周期 Job 继续兜底"进程被整体杀掉"的场景；
- 分级保活：①**静默复活** = 电脑 adb 授权 WRITE_SECURE_SETTINGS 后，检测到自身服务掉线就把服务名写回 enabled_accessibility_services 并置 accessibility_enabled=1，系统自动重绑；写回分两路——配置缺失走单次追加（干净拉起），配置在但没绑定（开机被 ROM 拦）才走两段式制造变更；②**提醒模式** = 默认零授权，掉线发本地通知引导去设置页；
- 防震荡三阀：复活冷却 30s（`isRunningNow` 在系统重绑过渡期会滞后返回 false，没有冷却会震荡成每秒几十次的活循环，真机首测实锤后修）、`scheduleJob` 幂等（`getPendingJob` 已注册不重排，掐断"重连→重排 Job→Job 立即首跑"的反馈回路）、两段式写入仅在陈旧绑定场景启用；
- UI 新增「无障碍保活」卡：授权状态、一键复制 adb 授权命令、立即自检按钮；Android 13+ 首次打开请求通知权限；MainActivity 服务状态判断统一走 KeepAlive；StatsStore 抽出通用 logEvent（保活事件与跳过记录同环展示），服务连接/复活均留痕；
- Manifest：新增 RECEIVE_BOOT_COMPLETED / POST_NOTIFICATIONS / WRITE_SECURE_SETTINGS（tools:ignore ProtectedPermissions）；**红线不变——无 INTERNET / 存储 / 位置**；versionCode 2 / versionName 0.2.0；
- 构建配置：Gradle wrapper **8.2→8.7**（本机 JDK 21 最高只配 Gradle 8.2 不兼容，AGP 8.2.2/Kotlin 1.9.22 不动）；wrapper 发行包源改腾讯云镜像；settings.gradle 加阿里云镜像源（google/central 镜像在前、官方源兜底）——国内直连 mavenCentral 慢到卡死首建（102MB 缓存跑了 14 分钟没完，换镜像后全量解析+编译 76 秒）。

**为什么**
- 用户令：装机前先集成无障碍保活（服务被 ROM 杀 / 升级被关是类李跳跳 App 的头号痛点），原排 M3/M4 提前落地。

**影响面**
- 系统设置只写 enabled_accessibility_services / accessibility_enabled 两项，且仅在检测到自身服务掉线时写；授权静默后用户手动关闭也会被周期任务拉起（UI 有说明，撤销授权即回到提醒模式）；被一键强停后 App 处于 stopped 态、一切调度失效，需打开一次 App 恢复。

**踩坑/备注**
- **真机首测抓到两个保活 bug 并当场修掉**（荣耀 BKQ-AN10 / Android 17 实测）：① 热循环——最初"固定两段式写入 + 每次连接都重排 Job + isRunningNow 过渡期滞后"三者叠加，杀服务后日志出现同秒几十对"掉线→复活→连接"，极端时设置值被写空；修复=冷却阀 + 幂等调度 + 两段式只用于陈旧绑定。② 修掉热循环后暴露无快速触发——周期 Job 最长 15 分钟才跑，杀掉后服务干等；修复=服务加 `onUnbind` 黄金几秒自检。**最终复测通过：09:34:57 杀 → 09:35:00 自动复活，仅一次，30 秒稳定**；
- 首次构建在 Windows 工作站打通：本机 SDK 缺 android-34 / build-tools 34.0.0，已用 cmdline-tools 11 补齐；开发机 JAVA_HOME 是 JDK 21（DevEco JBR），故升 Gradle wrapper；本机 local.properties 指 D:\Program Files\Android-SDK\Sdk（gitignore 内，仅本机）；M1 代码一次编译通过率很高，仅 JobInfo/JobScheduler 导错包（在 android.app.job 非 android.app）、FEEDBACK_ALL 应为 FEEDBACK_ALL_MASK 两处小错；
- **已知行为（M2 待解）**：全局关键字会命中系统页面里含"跳过"的可点击节点（实测误点系统设置 3 次），4s 窗口冷却兜住了频次；per-App 规则（M2）才是根治；
- WRITE_SECURE_SETTINGS 是 signature 级：清单声明 + `adb shell pm grant` 手动给才生效，属"用户显式授权"而非悄悄索权；Android 13+ 侧载应用的「受限设置」会拦设置页里的手动开关，但 adb settings put / 本 App 静默写入不受影响；
- BOOT_COMPLETED receiver 用 exported=true：protected broadcast 仅系统发得出，无安全面、跨 ROM 兼容最稳；
- 荣耀 ROM 还需用户在 启动管理 里放行自启动（UI 有提示）；adb 调试坑：Git Bash 非登录 shell 会丢 MSYS_NO_PATHCONV，`adb shell` 里的 `/sdcard` 路径被转换成本地盘路径，必须命令前补 `MSYS_NO_PATHCONV=1`。

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
