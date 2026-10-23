# CHANGELOG

> 规矩：每轮修改必须记录（改了什么/为什么/影响面/踩坑），换电脑先读本文件再动手。

## 0.4.2 - 2026-10-23 · 玻璃 Tab 栏重做：快照自模糊（不依赖 ROM 开关）

**改了什么**
- `GlassTabBar`（子窗口 blurBehind）**废弃**——真机上系统跨窗口模糊静默不渲染，用户看到的只是 65% 白透明贴片，设置页文字从后面透上来糊成一片（用户实测差评："明明就是透明效果"）；
- 新方案 `GlassBarView`（**快照自模糊，BlurView 库同款原理**）：挂在内容层 OnPreDraw 上，每帧把内容层 1/8 缩微截屏 → CPU 盒式模糊×3 → 放大垫底，叠 70% 白 + 圆角裁剪 + 描边；不依赖任何 ROM 模糊开关，效果全设备一致；继承 LinearLayout，三个 Tab 文字 weight 平分浮在玻璃上；
- 布局：三个页面 ScrollView 包进独立 `blur_content` 容器作模糊目标（**目标绝不能包含玻璃栏自己，否则 draw 自递归 StackOverflow**——首版实测）；
- 版本 0.4.2 / versionCode 7。

**为什么**
- 用户令：Tab 栏必须是真磨砂玻璃（酷安观感），透明贴片不算数；0.4.0 的子窗口方案在荣耀上没效果。

**影响面**
- 滚动期间每帧多一次内容层小区域绘制 + 百余像素的模糊，实测设置页长列表滚动流畅；玻璃栏必须为 `blur_content` 的兄弟节点。

**踩坑/备注**
- 首版把模糊目标设成含玻璃栏自己的 content_frame → `GlassBarView.onDraw` 里 `target.draw()` 无限自递归，StackOverflowError 启动即崩（logcat 栈里全是 onDraw 互调）；
- 玻璃栏从 LinearLayout 换 FrameLayout 时，子 Tab 文字的 `layout_width=0dp + weight` 失效 → 零宽全隐身（截图文字消失），改回继承 LinearLayout 解决；
- 手动安装的 platform-37 已随 0.4.0 就位，compileSdk 37 保留，为将来真上 `SurfaceView.setBlurRegions` 留着（那需要独立玻璃面板 overlay 场景）。

## 0.4.1 - 2026-10-23 · 点击后验证制 + 手势补刀 + 全链路诊断日志（修"记录了但没跳过"）

**改了什么**
- **真机实锤的失败模式**（用户报：一嗨租车广告没跳过、记录却上了）：`performAction(ACTION_CLICK)` 返回 true ≠ 广告被跳过——国产广告 SDK 普遍不理会无障碍点击、只认真实触摸，而旧版把"返回 true"当成功记账；
- **点击后验证制**：点击后 800ms 重扫当前窗口——目标已消失/换了 App → 才记一次跳过；目标还在 → 用 `dispatchGesture` 在目标中心**手势补刀**（模拟真实触摸，最多补 2 次），3 次都没点掉就不记账（诚实统计，失败只进诊断日志）；
- **全链路诊断日志**（TAG=`SkipAdEngine`）：每个非黑名单窗口的扫描结果全量输出——节点数、采样文本（前 20 条）、命中文案、拒绝原因（文本超长/无可点击祖先/祖先面积超限）——酷安、网易邮箱大师"零记录"类的失败从此有据可查；
- 版本 0.4.1 / versionCode 6。

**为什么**
- 用户实测三连失败：酷安/邮箱大师零反应零记录（疑似跳过按钮无可读文本，待诊断日志确认）；一嗨租车假成功（本次修的）。

**影响面**
- 统计口径变了：只记"验证消失"的真跳过，失败不记；补刀手势只点命中目标的中心，配合会话冷却不产生风暴。

**踩坑/备注**
- 验证的"消失判定"不能拿事件的 className 和重扫根节点的 className 比（前者是 Activity 名后者是布局类名，永不相等 → 永远误判已消失），只比包名 + 是否仍命中；
- NodeScanner 把拒绝原因分支从合并 if 拆成 when，文案直接进 lastDebug；
- 真机回归待用户解锁手机后进行（PIN 锁 adb 无法越过），测试入口：冷启动酷安 / 网易邮箱大师(com.netease.mail) / 一嗨租车。

## 0.4.0 - 2026-10-23 · 真·玻璃 Tab 栏（系统跨窗口模糊）+ 底部全沉浸 + compileSdk 37

**改了什么**
- **玻璃悬浮 Tab 栏**：`GlassTabBar` 用依附子窗口（TYPE_APPLICATION_ATTACHED_DIALOG）+ `FLAG_BLUR_BEHIND` + `blurBehindRadius`，走系统对话框同一条 Kawase 模糊管线，真实模糊栏后滚动的页面内容；**三个 Tab 文字放进玻璃窗口内**（玻璃必然盖住底下视图，文字必须在玻璃上面），窗口 NOT_FOCUSABLE＝可点不可焦点，点击回调切页；无模糊环境自动加浓白底退化；
- **底部全沉浸**：删掉底部 inset 垫片，页面内容直接滚到手势条和 Tab 栏底下（尾部留 96dp+inset 清空区），玻璃栏浮在手势条上方 14dp；
- **compileSdk 34→37**（targetSdk 仍 34）：AGP 8.2.2 + `android.suppressUnsupportedCompileSdk=37` 压警告，不冒险升 AGP/Gradle；platform-37 手动解压进 SDK 并手写 package.xml（api-level 整数 37）；
- 版本 0.4.0 / versionCode 5。

**为什么**
- 用户令：Tab 栏要「安卓17的玻璃，不是毛玻璃」≈ 酷安效果；底部安全区也要真沉浸（内容延伸到底）。

**影响面**
- Tab 文字从布局迁进子窗口：`selectTab` 双写（布局 Tab 兜底 + 玻璃 Tab），`currentTab` 统一状态；玻璃创建失败/低版本自动退回自绘底色的普通 Tab 栏，功能无损；无权限变化。

**踩坑/备注**
- **Android 17 玻璃 API 考据**（SDK 37 的 api-versions.xml 对比 36.1 差集）：37 新增 `android.view.BlurRegion` / `RoundedRectBlurRegion` / `SurfaceView.setBlurRegions()`——玻璃片语义，但 SurfaceView 在单窗口打孔模式下"身后"没有可模糊层，实测渲染成黑块；其正确场景是独立 overlay 的玻璃片。当前采用 **31+ 跨窗口模糊管线**（`mBlurEnabled=true` 实测可用，dumpsys 可见 `AtchDlg` 窗口 `blurBehindRadius=98`），后续做独立玻璃面板时再上 BlurRegion；
- **子窗口创建时机**：onCreate 阶段 `decorView.windowToken` 还是 null，addView 必 BadToken——必须推迟到首帧 attach 后（decorView.post 重试），就绪后回调里再隐藏布局 Tab 栏，失败保持兜底底色；
- Android 12+ 有些 ROM 把跨窗口模糊藏在开发者选项，关掉时 `isCrossWindowBlurEnabled=false`，本 App 自动加浓白底不破相；
- platform-37 的 source.properties 里 ApiLevel 写作 "37.0"（minor 版本化），手动装平台时 package.xml 的 api-level 要写整数 37。

## 0.3.1 - 2026-10-23 · 悬浮 Tab 信息架构（首页 / 设置 / 关于）

**改了什么**
- **单 Activity 三 Tab + 悬浮底部胶囊栏**（自定义实现，零新依赖）：首页＝服务状态 + 立即自检 + 广告排行榜；设置＝服务保活（模式/adb 授权/自检）+ 系统权限（电池优化/通知/自启动直达）+ 保活日志 + 应用维护（内存/清缓存/清统计）+ 边界说明；关于＝版本、定位、**隐私红线与权限清单**、开发路线（M1→M4 勾选清单）、开源信息（GitHub/MIT）；
- **砍掉 KeepAliveActivity 二级页**（内容摊平进设置 Tab，层级从"首页→二级页"降为"Tab 直达"）；最近记录保留二级页（长列表适合独立页，从首页排行榜卡进入）；
- Tab 切换原地显隐 + 胶囊高亮（bg_tab_on 浅绿底），悬浮栏 elevation 浮于内容上、随 inset 避开手势条；版本 0.3.1 / versionCode 4。

**为什么**
- 用户令：「菜单层级好混乱，加个悬浮 tab 栏，首页/设置/关于，好好梳理」——原结构首页塞了入口行 + 两个二级页（最近记录/保活）确实绕。

**影响面**
- KeepAliveActivity 及其布局删除（git rm）；Main 视图切换为显隐不重建，刷新逻辑统一在 onResume；无数据/权限变化。

**踩坑/备注**
- 对「桌面为何触发跳过」的结论沉淀：桌面没有广告，是通用关键字规则把**本 App 桌面图标文字里的 "skip"** 当跳过按钮点了（0.3.0 的 HOME 动态拉黑已根治，桌面整体不再扫描）；
- adb 远程验证教训：`input tap` 前必须先 `dumpsys window` 确认前台是我们自己的 App——本轮盲点坐标点进了用户正在用的酷安切了人家的 Tab；坐标换算按 截图像素 × (实际分辨率/截图分辨率) 算。

## 0.3.0 - 2026-10-23 · UI 二级页重构 + 统计数据库化 + 电池/自启动 + 沉浸式

**改了什么**
- **信息架构**：最近记录、保活设置收进二级页（`RecentActivity` / `KeepAliveActivity`），「立即自检」按需求留在首页；保活页补齐：模式与边界说明、adb 授权、**申请忽略电池优化**（新权限 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，弹系统确认框，状态实时显示）、**去自启动管理**（荣耀管家 `hihonor.intent.action.HSM_STARTUPAPP_MANAGER` → 已知组件 → 应用信息页逐级兜底，荣耀组件实测探测自真机）、去通知设置；保活页明示边界：**只保活 SkipAd 自己，无法替其他 App 保活**；
- **广告排行榜（分应用统计）**：新增 `StatsDb`（SQLiteOpenHelper，零外部依赖）单表事件流，聚合出累计次数/涉及应用数/分应用计数；首页 Top10 行 = 名次 + **真实应用图标 + 应用名 + 比例计数条**，超出部分汇总"还有 N 个应用"；`prune` 只留最近 1000 条事件；
- **最近记录页**：每行图标 + 应用名 + 时间 + 命中文案（图标名称经 `AppLabels` 进程内缓存，卸载应用回退包名+系统默认图标）；清空按钮与首页一致走警示弹窗（清空不可恢复）；
- **应用维护卡**：实时内存占用（自身进程 PSS，一位小数）+ 数据库体积；「清除内存缓存」（仅图标缓存，无数据影响）与「清空统计数据」（红色按钮 + AlertDialog 警示：累计/排行/最近记录全部归零不可恢复）；
- **沉浸式**：主题状态栏/导航栏透明 + `Ui.edgeToEdge`（systemBars+displayCutout inset 落到专用垫片 View），三页全部生效，浅色背景配深色状态栏图标；
- 修复：**桌面图标被当跳过按钮**——HOME 类应用动态拉黑（`CATEGORY_HOME` 解析，不枚举包名，全桌面通吃）；版本 0.3.0 / versionCode 3。

**为什么**
- 用户令：二级页收纳 + 统计样式重做（分应用/图标/排行榜）+ 本地数据库 + 内存占用与清缓存（带影响提示）+ 电池忽略优化 + 自启动入口 + 沉浸式，一次性全做。

**影响面**
- 统计从 SharedPreferences 迁 SQLite，旧统计（3 次误报）未迁移直接清零起算；保活日志环仍在 SP；事件表 1000 条上限，库体积有界；新增权限 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 属运营性，红线（无 INTERNET/存储/位置）不变。

**踩坑/备注**
- **真机抓到自点 bug**：荣耀桌面（com.hihonor.android.launcher）不在 M1 静态黑名单里，主屏上的「SkipAd」图标文字含 skip，被引擎连点 6 次（App 不断自己打开）；修复后桌面待 12 秒零记录；
- Kotlin 字符串模板会把 SQL 里的 `$_id` 当变量解析（Unresolved reference），建表语句改用列名常量；
- 内存 PSS 用 `ActivityManager.getProcessMemoryInfo`，系统对高频调用有节流，本 App 只在 onResume 刷新一次，无碍；
- 荣耀启动管理页无公开直达开关，action + 组件链兜底到应用信息页已在真机登记组件名。

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
