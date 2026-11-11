# 音频模块（AURORA）· CONTEXT

一句话摘要：这是一个用 LSPosed 注入 system_server、按**正在播放的音频会话**逐个挂载 DynamicsProcessing 软件均衡器、在音轨末端叠加六段曲线（80/200/500/1.5k/4k/10k Hz）的 Android 调音模块；当前版本 **1.3.2**（versionCode 1302），仓库 shisjsji/AURORA。

> **本文件已于 2026-11-06 按 1.2.0 重写。** 旧版描述的「Dolby DAX3 `dap_hw` + 20 段 GEQ + AudioEffect 厂商参数通道」路线**已整体废弃**，对应代码 `DolbyDap.java` 已删除。若在别处读到 v1.5.0、20 段、`aurora_geq` 之外还带 profile 的说法，一律以本文件为准。
> **1.3.2 变更**：修复沉浸声（空间音频）路径杂音——取证确认该路径输出线程仅有 miSpatializer 一个 HRTF 渲染器（MiSound/Dolby 保护链整条不在），渲染器补偿增益在限幅器下游导致 DAC 削顶；引擎现逐会话读取 isSpatialized，空间化会话限幅器阈值下探 4 dB（SPATIAL_HEADROOM_DB），路由切换时限幅器热更新不重建效果器。
> **1.3.1 变更**：修复 Limiter 反射构造器签名错误——此前限幅器从未生效，所有设备实际跑的是 AOSP 默认的 −2 dBFS/10:1 砖墙，是破音/炸音的主要来源；新增设备适配包机制（服务器 devices.json → App 匹配写入 aurora_device_pack → 引擎逐 tick 消费，频点被拒自动回退 250–6.3 kHz）；设备档案回报 backend 写入状态。
> **1.3.0 变更**：界面按 DESIGN.md 整体重做（token 体系、miuix 分组与原生滑杆、液态玻璃只留在 Dock），新增声场宽度与感知响度两个参数、滑杆「恢复默认」、小屏紧凑模式；旧 Magisk 路线与 20 段 GEQ 描述均为历史。本文件里凡写「250–6300 Hz」的段落，频点以代码 AudioEq.BAND_HZ 为准。

元信息：本次重写只读本地仓库（代码、构建配置、产物时间戳）加知识库 `aurora` 项目记忆，未连真机、未跑构建、未 ADB。所有结论都能在「相关文件路径」一节点到具体文件。

## 1. 目标

把手机听感变成可编程的东西：不动原厂 `/odm` 配置、不重启 `audioserver`、不替换系统文件，只在每条播放链路的末端叠加一条自己可控的曲线，关掉即回原厂。

- 目标设备与基线：Xiaomi 17 Pro（`pandora` / `25098PN5AC`），澎湃 OS 4.0（Android 17），固件 `OS4.0.6.0.XBLCNXM`（证据：README.md — "只在小米 17 Pro（`pandora` / `25098PN5AC`，澎湃 OS 4.0，Android 17）上验证过"）。
- 频段与量纲：**六段**，250 / 500 / 1000 / 2000 / 4000 / 6300 Hz，dB 直接量纲，单段限幅 ±12 dB（证据：AudioEq.java — `BAND_HZ = {250f, 500f, 1000f, 2000f, 4000f, 6300f}`、`MAX_DB = 12f`）。
- 频段为什么只有六段：手机扬声器只在约 300 Hz–6 kHz 有效还原，往 47 Hz 或 12 kHz 堆增益曲线好看但听不出来（证据：README.md — "只做 250 Hz 到 6.3 kHz 这六段，是因为实测手机扬声器只在这段里能有效还原"）。
- 明确不碰的东西：`/odm/etc/dolby/*.xml`、`persist.vendor.audio.*` 属性、`audioserver` 进程、任何系统文件（证据：README.md — "不写 `/odm` 里的 Dolby 配置，不重启 `audioserver`，不替换任何系统文件"）。

### 为什么不是 Dolby DAP

这台机器的固件里 `dap_hw`（UUID `9d4921da-8225-4f29-aefa-39537a04bcaa`）确实接受厂商参数、写入返回 `rc=0`、效果器在链上、框架里 `Enabled=y`——但音乐完全没变化。它是**控制面，不处理 PCM**；流媒体播放器走 spatializer 输出线程时，参数写了不生效。真正处理 PCM 的是 AOSP 的 `android.media.audiofx.DynamicsProcessing`，工作起来在普通路径和 spatializer 路径上都能出声（证据：AudioEq.java 类注释 — "the `dap_hw` effect accepts vendor parameters but does not apply them to audio that goes through the spatializer output thread"）。

## 2. 架构

### 分层

| 层 | 目录/文件 | 职责 | 状态 |
| --- | --- | --- | --- |
| 注入层 | `aurora-lsp/.../AuroraXposed.java` | Hook `AudioService.systemReady()`，起 `aurora-engine` 线程，注册配置观察者，跑一次 Probe | 主线 |
| 引擎层 | `AuroraEngine.java` | 每 2 秒一次 tick：读配置 → 扫会话 → 算曲线 → 按会话写入 → 写回执 | 主线 |
| 效果器层 | `AudioEq.java` | `DynamicsProcessing` 反射封装，一个会话一个实例 | 主线 |
| 曲线数据 | `Presets.java` | 9 套 × 6 段 dB 曲线 + key/名称/听感说明 | 主线 |
| 自检层 | `AuroraProbe.java` | 开机在 system_server 里探一遍链路，结论写 `Settings.Global` | 主线 |
| UI 层 | `MainActivity.kt` + 16 个 Kotlin 文件 | Compose + MIUIX 四页 + 实时状态条 | 主线 |
| 旧路线 | `aurora-pro/` | Magisk/KernelSU + Dolby XML 脚本，v5.7.3 | 已归档，被 `.gitignore` 排除 |

`officelite/`（Office 复刻）是另一个独立项目，与本项目无关。

### 音频链路

```
App 会话  ──▶  系统混音  ──▶  AURORA（DynamicsProcessing preEq）  ──▶  扬声器
 session        AudioFlinger        六段 250 Hz – 6.3 kHz
```

关键点：**挂的是会话，不是音频设备**。系统一开空间音频，音乐就换到独立输出线程（`AudioOut_5D`，SPATIALIZER），全局混音上的任何改动都会被绕过；按会话挂才两边都有效。

### 一次 tick 的流程

`AuroraEngine.tick()` 是唯一入口，全程跑在 `aurora-engine` 线程：

1. `read()` 读 `Settings.Global` 里的六个键。读失败时**保留上次的配置**（`lastGood`），不当作"已关闭"。
2. `panic` → `releaseAll()` 并回执 `panic`；`!enabled` → `releaseAll()` 并回执 `disabled`。
3. `activeSessions()` 反射 `AudioManager.getActivePlaybackConfigurations()`，只收 `getPlayerState() == 2`（STARTED）且 `0 < session <= 1_000_000` 的。
4. 会话为空 → `releaseAll()` 并回执 `idle`。
5. 算曲线：自检激活就全段 −12 dB，否则 `buildGains(config)`。
6. 逐会话：**第一次看到某会话只登记进 `seen`，下一次 tick 才 `open()`**。每个会话只创建一次效果器：既能避开 MiSound 在音轨起始时重建效果链导致的孤悬，又不会像旧实现那样"建→拆→建"，把链拆掉一次（那正是换歌、刷视频、切沉浸声时的断音）。签名相同且已 applied 且不在自检中则跳过重写。
7. `releaseStale()` 回收已消失的会话，`report()` 写回执。
8. `AuroraXposed` 在 tick 末尾 `postDelayed(this, TICK_MS)`，`TICK_MS = 2000L`。

### 曲线怎么算

`buildGains()` 三步，顺序不能换（证据：AuroraEngine.java — `buildGains`）：

1. 取基准：有用户自定义曲线用用户的，否则 `Presets.curve(indexOf(preset))`。
2. 乘强度（`strength / 100`），再把整条曲线**按最大绝对值等比缩放进 ±12 dB**。
3. 预衰减：若抬得最高的那段超过 `MAX_BOOST_DB = 6 dB`，整条曲线整体下移，使峰值刚好 6 dB。形状保住，只降绝对电平——这是硬件 EQ 为不削顶常做的交换。

**不做减均值归一化**。早期版本减均值以求响度平直，结果把每套预设赖以成立的倾斜抹平了，九套听起来一样（证据：AuroraEngine.java — "an earlier version subtracted the mean to keep loudness flat, but that flattened exactly the tilt each preset is built around"）。

## 3. 已完成

**注入链**：入口由 `assets/xposed_init` 指定为 `com.aurora.lsp.AuroraXposed`；模块只在 `pkg == "android"` 时动手，hook `AudioService` 上名为 `systemReady` 且无参的重载，钩子内 `attach(param.thisObject)`；找不到方法就回 `HOOKED=false` 并记日志。Context 是从 `AudioService` 实例反射取 `mContext` 字段，不自己 new。作用域 `res/values/arrays.xml` 只含一项 `android`（证据：AuroraXposed.java — `if (!"android".equals(params.packageName)) return;`、`XposedHelpers.getObjectField(audioService, "mContext")`）。

**线程模型**：`HandlerThread("aurora-engine", Process.THREAD_PRIORITY_BACKGROUND)`，tick、Probe、ContentObserver 回调全部 post 到这个线程的 Handler 上。`HOOKED` / `STARTED` 两个 `AtomicBoolean` 各自 CAS 防重入（证据：AuroraXposed.java — 类注释 "The audio effect calls block in the HAL. Running them on the system_server main looper makes the watchdog kill the system process, so nothing here ever touches the main thread."）。

**配置通道**：App 与 system_server 不共享进程，靠 `Settings.Global` 全局键通信，`ContentObserver` 监听六个键，`onChange` 里 `removeCallbacks(TICK)` 后立即 `post(TICK)`，等效一次即时 tick。

- 输入键：`aurora_enabled`、`aurora_panic`、`aurora_preset`、`aurora_strength`（0–100）、`aurora_geq`（当前曲线）、`aurora_geq_saved`（用户自存曲线，切预设不丢）、`aurora_selftest`（token）。
- 回执键：`aurora_backend_state`、`_message`、`_bands`、`_sessions`、`_applied`、`_updated`，另有 `aurora_backend_log`（最近 30 条状态变更，供反馈表单随件带走）和 `aurora_engine_version`（system_server 里那份代码的 versionCode）。
- 量纲：曲线在 `Settings` 里按**十分之一 dB 的整数**存取（`aurora_geq = "110,90,-30,..."`），避免浮点在字符串往返中失真；回执 `_bands` 同样是十分之一 dB（证据：AuroraEngine.java — `parseCurve` 与 `join(float[])`）。

**`AudioEq` 的反射封装**：`resolve()` 一次性拿齐 `DynamicsProcessing`、`DynamicsProcessing$EqBand` 的构造器与 `setPreEqBandAllChannelsTo` / `setEnabled` / `hasControl` / `release`，失败缓存 `resolveError` 不重复抛。`open(session)` 拒绝 `session <= 0`，构造 `DynamicsProcessing(0, session)`，从 `getPreEqByChannelIndex(0).getBandCount()` 读真实段数，`hasControl()` 为真且段数大于 0 才算成功。`apply()` 只写前 `min(真实段数, 6, 增益数组长度)` 段，多出来的段**故意不动**——默认平直，若全停到最后一个频点会叠出一堆本不想要的滤波器（证据：AudioEq.java — "Surplus bands are left untouched"）。
- Limiter 接在链路最后一级：阈值 −1 dBFS、attack 5 ms、release 60 ms、ratio 10，只压会削顶的峰。它单独解析，取不到就不启用，绝不让整条 EQ 失效（证据：AudioEq.java — `enableLimiter()`、`setLimiterAllChannelsTo`）。

**`Presets` 九套曲线**：`KEYS`/`NAMES`/`NOTES` 三组元数据 + `CURVES` 二维数组（9 × 6）。设计约束写在注释里：每套至少有一个频段相对平直偏 ≥5 dB，两两之间至少一处差 ≥4 dB，保证切换能听出来。原厂平直一行全 0。

| key | 名称 | 250 | 500 | 1k | 2k | 4k | 6.3k |
| --- | --- | --- | --- | --- | --- | --- | --- |
| flat | 原厂平直 | 0 | 0 | 0 | 0 | 0 | 0 |
| natural | 自然均衡 | +2 | +1 | 0 | 0 | +1 | +1 |
| harman | 哈曼卡顿 | +11 | +9 | −3 | −6 | −2 | −5 |
| burmester | 柏林之声 | +4 | +2 | −1 | +4 | +10 | +11 |
| punch | 现场低频 | +12 | +12 | +5 | −7 | −11 | −12 |
| cinema | 影院包围 | +10 | +8 | +2 | −3 | −9 | −11 |
| vocal | 人声清晰 | −10 | −12 | +6 | +9 | +4 | 0 |
| bright | 明亮解析 | −9 | −7 | +1 | +6 | +11 | +12 |
| night | 夜间柔化 | +6 | +4 | 0 | −7 | −11 | −11 |

**`AuroraProbe` 开机自检**：模块在 system_server 里跑一次，六步——取 `AudioManager` → 播放会话 API 是否存在 → `DynamicsProcessing` 及其 `EqBand`/方法能否解析 → `AudioEffect.queryEffects()` 列出全局效果器 → `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` 列输出设备 → 读输出采样率/每缓冲帧数。任何一步失败就记 `aurora_probe_stage` 为那一步的编号，并写回机型、SoC、SDK、系统版本。全程不抛异常，失败本身就是一条记录（证据：AuroraProbe.java 类注释 — "Never throws: any failure becomes a recorded stage."）。

stage 语义（证据：DeviceProfile.kt — `stageLabel()`）：`0` 链路可访问、`1` 取不到 AudioManager、`2` 取不到播放会话信息、`3` 本机没有可用的均衡器实现、`-1` 未检测（模块根本没跑）。`stage == -1` 是最常见的"装了但没反应"。

**UI（Compose + MIUIX）**：`MainActivity.kt` 承载四页横向 pager——**音效 / 均衡 / 诊断 / 设置**，每页顶部一条 `StatusStrip` 实时状态条（生效/等待播放/已关闭/写入失败，2 秒刷新）。旧的「场景」页已删除，因为环绕、对白那些开关是 Dolby 通道的，在软件均衡器路线上没有对应参数。

- 音效页：大卡片横滑选预设 + 总开关 + 当前曲线缩略。
- 均衡页：六段可拖 + 强度 + 恢复平直（带确认）+ 恢复自存曲线。
- 诊断页：测试音自检、系统回执、会话列表、机型与链路档案。
- 设置页：外观（模糊底栏 / 液态玻璃 / 角标 / 导航栏形态）、检查更新、反馈、关于。
- 液态玻璃与模糊底栏移植自 KernelSU，`Lens.kt` 的折射 shader 来自 AndroidLiquidGlass（证据：docs/THIRD-PARTY.md）。
- 读侧不走 root：所有 `Settings.Global` 读都在 `Dispatchers.IO` 上批量做完再一次性发布，避免主线程八次 binder 往返把页面切换拖卡（证据：AuroraState.kt — "Settings.Global reads are binder round trips; doing eight of them on the main thread every two seconds was enough to make page switches stutter"）。
- 写侧走 root：`ProcessBuilder("su", "-c", command)`，一条命令串起 `settings put` 并回显 `AURORA_OK` 作为成功凭据，15 秒超时（证据：AuroraState.kt — `runRoot`）。预设 key 进 shell 前先对白名单校验，不在 `Presets.KEYS` 里就退回 `flat`。

**反馈与日志**：`AuroraLog` 是滚动文件日志，上限 192 KB，超了裁到 96 KB 且从行边界切，写日志本身绝不抛异常。反馈表单 `Feedback.reportIssue()` POST 到 `https://lingluandf.online/feedback/report`，日志只在用户开着开关时随件带走。链路不可用时 `reportDevice()` 自动上报一次机型档案，用 `stage:versionCode` 去重，同一版本同一个失败阶段只报一次（证据：Feedback.kt — `stamp = "${profile.stage}:$versionCode"`）。
- `reportIssue` 返回三种结果：提交成功、服务器拒绝（带 HTTP 状态码）、连不上服务器（带异常类型）。UI 分别给不同文案——1.2.0 及以前只返回布尔值，把 405 也报成"请检查网络"（证据：Feedback.kt — `sealed interface SendResult`）。

**检查更新**：`Update.kt` 读 `https://lingluandf.online/aurora/latest.json`，比较 `versionCode`，给「前往下载」；无网/超时/字段缺失分别给明确文案，不白屏。原本读 GitHub Releases，因国内直连不稳才换成自建发布主机（同一台香港节点）（证据：Update.kt 类注释）。

**构建**：Gradle 9.3.1 + AGP，`compileSdk 37 / minSdk 33 / targetSdk 36`，JDK 17，Compose BOM `2025.09.00`，MIUIX `0.9.4`。`build.sh` 只是一层包装：`gradle :app:assembleRelease --no-daemon` → 复制成 `out/AURORA-1.2.0.apk` → 打印大小与 sha256。签名用 `out/aurora-observer.jks`。仓库里的 `spike/` 是早期 Gradle 试验缓存，已进 `.gitignore`。

**产物与仓库**：`out/AURORA-1.2.0.apk`（31.4 MB，2026-11-06 03:01），`out/` 下并存历史的 `AURORA-1.0.0` / `1.1.0` 与更早的 `AURORA-LSP-0.x/1.x`（javac 时代的产物，41 KB 级）。`.gitignore` 排除 `aurora-lsp/out/`、`_work/`、`device/`、`reference/`、`spike/`、`aurora-pro/`、`officelite/`，所以 GitHub 上只有 `aurora-lsp/`、`docs/`、`assets/`、README、CONTEXT、LICENSE。

## 4. 关键决策

1. **放弃 Dolby DAP，改驱动 AOSP 软件均衡器**。Dolby 在这条链路上只当控制面，参数写了不处理音频；`DynamicsProcessing` 是真正跑在混音链上的软件级，普通路径与 spatializer 路径都生效。
2. **按播放会话挂，不挂 session 0 / 全局混音**。空间音频把音乐切到 `AudioOut_5D` 的 per-session 效果链，全局链上根本没有音频流经过。
3. **每个新会话延迟一拍再挂效果器**。MiSound 在音轨开始时重建整条效果链，第一拍建的效果器会被孤悬——链上还在、`Enabled=y`，但不出声。所以第一次看到只登记，下一拍才 `open()`，而不是建了再拆（见决策 16）。
4. **频段从 20 段收成六段**。20 段是 Dolby 的能力，不是扬声器的能力；六段落在真正能还原的窗口里，每段都有可听效果。
5. **曲线等比缩放 + 预衰减，不减均值**。减均值抹平预设倾斜，九套会听起来一样；保形状、只降绝对电平才是对的选择。
6. **抬升上限 6 dB，衰减放到 12 dB**。手机扬声器满音量已接近削顶，+12 dB 只会听起来"破"，不会更响。
7. **所有音频调用下沉到私有线程**。这是用系统崩溃换来的教训（见下节第一坑），代码里三处注释反复强调。
8. **配置与回执都放 `Settings.Global`**。App 与 system_server 不共享进程，`settings` 全局键是唯一零成本通道；曲线按十分之一 dB 存整数，规避浮点往返失真。
9. **读失败保留上次配置**。一次读失败曾被当成"已关闭"，结果播放中途释放全部效果器、声音弹回原厂。现在有 `lastGood` 兜底。
10. **写全部段会叠滤波器，所以多出的段不动**。`DynamicsProcessing` 的段数由系统给，只写我们认得的前六段，剩下保持平直。
11. **保留急停开关**。`aurora_panic=1` 一条命令释放全部效果器，是踩坑后加的保险。
12. **自检用"全频 −12 dB 持续 6 秒"**。听感上一强一弱就说明链路通，比看任何回执都直接。
13. **不保留场景页**。那些参数只存在于 Dolby 通道，软件均衡器上没有对应物，留着就是假开关。
14. **用户曲线单独存一份**。`aurora_geq_saved` 与 `aurora_geq` 分开，切预设清空当前曲线但不丢用户手调的曲线。
15. **状态回执按签名去重**。`report()` 先把 state+message+bands+applied+sessions 拼成签名，与上次相同就直接返回，避免每 2 秒往 `Settings` 里写一遍同样的东西。
16. **延迟首建，而不是建了再拆**。旧实现第一拍建效果器、第二拍拆掉重建，为的是躲开 MiSound 重建效果链，但代价是每个新音轨 release 一次，听感就是换歌/刷短视频时的断音。改成"第一次只登记、第二次才创建"，同样避开孤悬，且全程只有一次 open。
17. **配置写入合批 150 ms**。保存一次设置要连写四个 `aurora_*` 键，逐条触发 tick 会让引擎读到半更新配置、连发几条中间曲线。观察者改为 `postDelayed(TICK, 150)`，合并成一次。
18. **削顶用 limiter 解决，不靠继续降增益**。降峰值会牺牲预设差异；limiter 只在越界时压峰，既保住曲线形状又不再数字削顶，同时把"开沉浸声有杂音"这条路一并治了。

## 5. 踩过的坑

- **在 system_server 主线程做音频调用 → watchdog 杀系统进程**。现象是 SystemUI 连环重启、壁纸变黑、桌面高斯模糊丢失。根因：`AuroraEngine` 早期跑在 `Handler(Looper.getMainLooper())` 上，而 `getParameter` 六个命令一律返回 `rc=12` 且不回填，读出 `profileCount = 50331648`，对五千万个非法 profile 反复写把主线程钉死。修法：工作移到 `HandlerThread` + `THREAD_PRIORITY_BACKGROUND`，数量按固件写死，读值只做校验。（Dolby 时期的直接原因，但"独立线程"这条规矩一直留到现在。）
- **挂 session 0 完全没声音变化**。曾以为全局输出混音就是唯一入口，实测被 spatializer 的 per-session 链绕过。
- **Dolby DAP 写入成功但听感无变化**。诊断页测试音有明显区别、音效页放音乐完全无区别，写入返回成功。根因：Dolby 在 spatializer 链路上只是控制面，参数能写不处理音频。修法：整条路线换成 `DynamicsProcessing`。
- **空间音频效果器创建报 `-3`**。Dolby 效果器在空间音频链路上创建直接失败（未实现厂商参数通道）。同一个决策的另一个证据。
- **第一拍建的效果器被挂空**。现象：链上还在、`Enabled=y`、就是不出声。根因：MiSound 在音轨开始时重建效果链，第一个 tick 创建的效果器挂到临时链上随后孤悬。修法：第一次看到会话时不要建它，等下一拍链路稳定再挂，全程只创建一次（见决策 3、16）。
- **每个新会话被建了两次效果器（1.2.0 及以前）**。现象：换歌、刚进抖音、每刷一个视频都有断音。根因：第一拍建、第二拍 `close()` 后重建，release 会拆链。修法：改成延迟首建，全程只 open 一次（见决策 16）。
- **减均值归一化让九套预设听起来一样**。根因：倾斜型曲线减完均值后差异被压平。修法：等比缩放保形状，另加 6 dB 抬升上限做预衰减。
- **`points` 数组越界导致 App 闪退**。`points` 只有 20 个元素，按 `i * 2` 索引需要 40 个。修法：修正索引与数组长度后重编。
- **多写段数会叠出多余滤波器**。`DynamicsProcessing` 的段数与我们的频段表不一定相等，若把多余段全按到最后一个频点，等于叠了一串没打算要的滤波器。修法：只写认得的段，其余不动。
- **主线程八次 `Settings` 读把页面切换拖卡**。每次读都是一次 binder 往返。修法：`Dispatchers.IO` 上批量读，一次性发布。
- **切预设时引擎读到半更新配置**。现象：切换预设瞬间声音抖一下。根因：App 连写四个 settings 键，`ContentObserver` 每次都立即触发 tick，下发了"新预设 + 旧强度"这类中间态曲线。修法：观察者合批 150 ms（见决策 17）。
- **`getParameter` 读接口是幻觉**（Dolby 时期）。六类 get 命令 rc 恒为 12、缓冲区原样返回，任何用读值当循环上界或"当前 profile"的做法都是错误来源。现在的代码已不再依赖任何读接口。
- **增益堆在两头听不出来**。47–328 Hz 与 9 kHz 以上几乎不可闻，同一套 ±12 dB 曲线堆中频是"明显变亮"、堆两端是"一丝丝变化"。这是六段方案与预设重做的直接依据。
- **会话切换有约 2 秒空窗**（track 重建时旧会话已释放、新会话还没被 2 s 轮询捡到）。
- **`/feedback/` 反代只配在子域，主域 POST 落到 SPA 兜底**。App 的地址是主域 `lingluandf.online/feedback/*`，而反代只写在 `aurora.lingluandf.online` 的 server 块里，主域没有对应 location，静态服务器对 POST 返回 405，客户端于是报"网络未连接"。同一台机器上 `/lib/`、`/dl/`、`/aurora/latest.json` 都踩过这个坑，这是第四次。修法：主域补 `location /feedback/` 反代到 `127.0.0.1:5002`，`client_max_body_size` 提到 320k。
- **LSPosed 卸载重装后默认未启用也没勾作用域**。表现为模块不出现在日志、无任何回执。修法：在 LSPosed 管理器界面手动启用并在作用域钩「系统框架」，不要手改 LSPosed 数据库。
- **升级 App 但不重启设备会留下旧引擎**。system_server 里跑的还是上一版代码：App 显示新预设，引擎照旧曲线。修法：`aurora_engine_version` 记下引擎版本，`DeviceProfile.staleEngine()` 比对不一致就提示重启。
- **PowerShell 写文件会加 BOM 并展开变量**。BOM 会让 javac 不认，`$EqBand` / `$apkname` / `$host` 会被当变量展开写坏文件。修法：改用 Python 脚本或文件方式传输，避开 shell 转义与插值。
- **GitHub Releases 在国内直连不稳**。检查更新原设计读 GitHub Releases、无需后端，实测不可靠，改读同一台香港节点上的 `latest.json`。

旧 Magisk/KernelSU 路线的坑（改 XML 的半状态、soft-reboot 后绑定残留、`AURORA-marked XML` 挡住快照、ADB 授权后掉线）随 `aurora-pro` 归档一并封存，细节见 `aurora-pro/releases/` 两份修复说明，不再影响当前实现。

## 6. 当前待办

1. **真机听感复核**。九套预设 + 本轮三项修复（沉浸声杂音、刷视频断音、切预设抖动）都要上机确认；仓库里没有听感复核记录。
2. **延迟首建带来的最多一拍空窗**。现在每个新会话要等下一拍（≤2 s）才挂上效果器，不再有断音但起播时仍有一小段无 EQ。可选方向：缩短轮询或改监听 `registerAudioPlaybackCallback`。
3. **非小米机型适配**。兼容路径只有 probe 的六个检查点，没有针对其它品牌音频链路的特判；`stage` 报回来之后如何映射成具体适配动作还没写。
4. **limiter 参数待真机校准**。阈值 −1 dBFS、attack 5 ms、release 60 ms、ratio 10 是保守起点；若低频出现"抽吸感"，说明 release 偏长或 ratio 偏狠。
5. **`aurora_geq_saved` 未注册观察者**。`observe()` 只监听六个键，它不在其中；目前靠下一次 tick 顺带反映，行为正确但依赖隐式时序。
6. **构建脚本硬编码版本号**。`build.sh` 的产物名与 `app/build.gradle.kts` 的 `versionCode`/`versionName` 要手改三处，1.2.1 已同步，但仍是隐患。
7. **签名口令明文**在 `app/build.gradle.kts`（`out/aurora-observer.jks` 亦在同处）。自用无妨，仓库若转公开值得改成从 `local.properties` 读。
8. **`spike/` 可清理**。早期 Gradle 试验缓存，已被忽略，删掉不影响构建。

## 7. 相关文件路径

> 相对根目录 `C:\Users\lldf\XiaomiMiMoProjects\音频模块`。只列本次实际读过或用于定位的文件。

注入与引擎（`aurora-lsp/app/src/main/java/com/aurora/lsp/`）：
- `AuroraXposed.java`（6.0 KB）— 入口、hook、线程、观察者、Probe 触发
- `AuroraEngine.java`（16 KB）— tick 主流程、会话扫描、曲线计算、回执、滚动日志
- `AudioEq.java`（6.0 KB）— `DynamicsProcessing` 反射封装、六段频点、±12 dB 限幅、limiter 防削顶
- `AuroraProbe.java`（10 KB）— 开机六步链路自检，结论写 `Settings.Global`
- `Presets.java`（2.3 KB）— 9 × 6 曲线与元数据

UI（`aurora-lsp/app/src/main/kotlin/com/aurora/lsp/`，共 16 个文件）：
- `MainActivity.kt`（55 KB）— 四页 pager、状态条、音效/均衡/诊断/设置、测试音播放
- `AuroraState.kt`（8.4 KB）— UI 单一数据源，读走 IO 线程、写走 su
- `DeviceProfile.kt`（3.2 KB）— probe 结果的类型化读取与 `stage` 释义
- `Feedback.kt`（4.8 KB）/ `FeedbackSheet.kt`（9.8 KB）— 反馈上报与表单
- `AuroraLog.kt`（1.7 KB）— 192 KB 滚动日志
- `Update.kt`（2.7 KB）— `latest.json` 检查更新
- `About.kt`、`AuroraBottomBar.kt`、`FloatingBottomBar.kt`、`liquid/{Lens,CombinedBackdrop,InnerShadow,Vibrancy}.kt`、`animation/{DampedDragAnimation,InteractiveHighlight}.kt`、`modifier/DragGestureInspector.kt`、`theme/DarkTheme.kt` — 液态玻璃与底栏（移植自 KernelSU，见 docs/THIRD-PARTY.md）

构建与清单：
- `aurora-lsp/build.sh` — Gradle 包装 + 复制产物 + sha256
- `aurora-lsp/build.gradle.kts` / `settings.gradle.kts`（阿里云 Maven 镜像）/ `app/build.gradle.kts`（1.2.0 / 1200 / compileSdk 37）
- `aurora-lsp/app/src/main/AndroidManifest.xml` — 包名 `com.aurora.audio.lsp`、`xposedmodule`、`xposedminversion 93`、`xposedscope`
- `aurora-lsp/app/src/main/assets/xposed_init` — `com.aurora.lsp.AuroraXposed`
- `aurora-lsp/app/src/main/res/values/arrays.xml` — `xposed_scope = [android]`
- `aurora-lsp/build-legacy.sh` — javac + d8 时代的老构建脚本，仅存档

产物：
- `aurora-lsp/out/AURORA-1.2.1.apk` — 当前产物（30 MB，2026-11-06 21:07）
- `aurora-lsp/out/AURORA-1.0.0.apk` / `1.1.0.apk` — 历史版本
- `aurora-lsp/out/AURORA-LSP-*.apk` — javac 时代产物（29–41 KB），仅供考古

文档：
- `README.md` — 对外口径（1.2.0，已与代码一致）
- `docs/PLAN-1.0.0.md` — 1.0.0 重构计划：路线 A/B 抉择、四页信息架构、预设重做原则、里程碑
- `docs/THIRD-PARTY.md` — KernelSU / AndroidLiquidGlass / miuix 的许可与移植清单
- `docs/RELEASE-1.2.1.md` — 1.2.1 交付说明：三条反馈的根因、修法与验证边界
- `docs/固件逆向报告-OS4.0.5.0.md` — Dolby DAX3 时期的逆向报告（16 KB）。**仅作历史证据**，其中 20 段 GEQ、命令码、`getParameter` 行为已不在当前实现里

已归档、被 `.gitignore` 排除（不在 GitHub 上）：
- `aurora-pro/` — Magisk/KernelSU 旧路线，`module.prop` v5.7.3，改 Dolby XML + 写 4 个 `persist.*` 属性
- `device/` — 真机取证：`dumpsys-audio*.txt`、`system-props.txt`、`lsposed-modules.log`、两份 `lsposed-config-*.db`
- `_work/` — OTA 解包产物、`payload_manifest.json`、早期 `probe/AuroraProbe.java`
- `reference/` — `framework.jar`（51 MB）、`MiSound.apk`（19 MB）等只读二进制
- `spike/` — Gradle 试验缓存

## 8. 待确认

19. **听感复核没有留痕**。九套曲线是否逐套上机确认过"能明显听出差别"，仓库里没有记录；`Presets.java` 注释里的设计约束说明写过一轮，但验收结论缺失。
20. **空间音频开/关两种情况下是否都实测过**。README 与代码注释都声称"开不开空间音频都一样有效"，这是按会话挂载的设计推论；仓库内没有一份对照记录（哪种输出线程、哪个 session、听感差异）。
21. **`aurora_geq_saved` 的实际读取路径**。代码里 `K_GEQ_SAVED` 只被 App 侧读取，引擎侧 `read()` 不消费它；这是有意为之还是遗漏，需要确认设计意图。
22. **非小米机型的适配契约未定义**。`stage` 报回来之后，谁来决定"这机型能不能用、缺什么、怎么补"，目前只有上报通道，没有处理流程。
23. **`device/` 里的旧取证是否还适用**。`dumpsys-audio*.txt` 抓的是 Dolby 时期的链路，对现在的 `DynamicsProcessing` 路线已无直接参考价值；保留是为了历史，但容易误导后来者（本文件第 7 节已标注）。