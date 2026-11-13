# AURORA · 界面重做（DESIGN.md）

本文件是这次翻新的契约：方向、依据、token、页面职责。代码按它写，评审也按它看。

## 一、方向

**产品.** 给一台小米手机外放调音的模块。人打开它，是为了听懂「现在声音到底被改成了什么样」，然后拧几档。

**性格.** 精确、克制、有一点仪器感。不是播放器，不是美化器。

**世界.** 测量仪器面板：铝面板、刻度、音箱网罩。中性色偏冷灰，颜色只出现在读数和"已选"上。

**两处 moment.**
1. 均衡页的曲线图：整屏唯一有大面积颜色的地方，带 dB 网格与刻度，六段读数直接标在曲线上。
2. 顶部状态胶囊：把"正在生效 / 等待播放 / 已关闭 / 写入失败"做成一颗真实信号灯加三个读数，而不是一行灰字。

**签名.** 六颗竖条的 EQ 字形（`EqGlyph`）。凡是"这条曲线在干什么"的地方都用它——预设行、状态胶囊、Tab 图标旁。只此一个，不扩散。

## 二、平台依据（不是凭喜好）

**Apple HIG · Materials**
- Liquid Glass 是**功能层**材质，只给浮动在内容之上的控件与导航（Dock、浮起面板）。内容层不得使用玻璃，内容层用标准材质。
  出处：<https://developer.apple.com/design/human-interface-guidelines/materials> — "Don't use Liquid Glass in the content layer."
- regular 变体做两件事：模糊 + 按背景亮度调整，用来保住文字可读性；clear 变体几乎全透，只配视觉丰富的底。
- 玻璃很克制地用：它存在的目的是让人看见底下的内容，滥用会反过来抢注意力。

**Apple HIG · Tab bars**
- tab bar 浮在内容之上，底下的内容要能"探出来"；标签用单词；不要用与内容层相近的颜色去染标签；徽标只留给关键信息。
  出处：<https://developer.apple.com/design/human-interface-guidelines/tab-bars>

**miuix（澎湃 OS 官方设计系统，本项目已依赖）**
- 分组标题用 `SmallTitle`，分组容器用 `Card`，行内分隔用 0.75dp `HorizontalDivider`。
- 行的解剖结构统一为 title / summary / endAction（`ArrowPreference`、`SwitchPreference`、`SliderPreference` 就是这个结构）。
- 滑块原生支持 `steps`、`keyPoints`、`magnetThreshold`（磁吸到关键点），不需要手写。
- 浮动导航栏 `FloatingNavigationBar` 在 0.9.4 里是**不透明** `surfaceContainer`，无模糊——所以 Dock 继续用现有的液态玻璃实现，不用它。

**已核实的事实**（本轮真去查过，不是推测）
- `miuix-glass` 只在源码里，Maven Central 上没有任何版本 → **官方玻璃用不了**，手的这套保持。
- `miuix-preference-android:0.9.4` 存在 → 行组件可用。
- `Slider` 签名含 `steps: Int`、`showKeyPoints`、`keyPoints`、`magnetThreshold`。
- `Card` 参数是 `cornerRadius` + `insideMargin`（没有 `insideContentPadding`）。

**参考样本**（Cantrip 移动端条目的手法，只取手法不抄样子）
- Deck Card Fan：把"当前对象"做成页头的一部分，滚动时收缩贴到标题旁，而不是消失——所以预设/曲线在滚动里要有去处。
- Floating Icon Dock：图标与状态各自固定位置，状态永远读得出来。

## 三、Token

现状的问题不是单点难看，是**没有系统**：圆角有 10/12/14/16/20 五种，内边距有 14/16/22，字阶从 10 到 24 共八个，权重 400/500/600 混用无序，颜色还有硬编码的 `0xFF23262D`。这一节把它们收成一套。

### 间距（4 网格）

| 用途 | 值 |
| --- | --- |
| 页面左右边距 | 20 |
| 分组之间 | 24 |
| 分组标题 → 组内首行 | 8 |
| 卡片内边距 | 16 |
| 行与行 | 0（靠 hairline 分隔） |
| 行内垂直内边距 | 12（整行最小高 56） |
| 图标与文字 | 12 |
| 图标列宽 | 24 |

### 圆角（同心，不套娃）

| 元素 | 值 |
| --- | --- |
| 分组卡片 | 22 |
| 浮起面板 | 28（顶部两角） |
| 行内小块 / 输入框 | 14 |
| 控件（按钮、滑块 thumb） | 系统默认，不覆盖 |

规则：内层圆角 = 外层圆角 − 两者之间的内边距；不低于 8。不在卡片里再放一层带底色的方块。

### 字阶（尺寸 + 角色，权重只用 400/500/600）

| 角色 | 尺寸 | 权重 | 颜色角色 |
| --- | --- | --- | --- |
| 页面大标题 | 30 | 600 | onSurface |
| 页面副标题 | 13 | 400 | onSurfaceVariantSummary |
| 区块读数（大数字） | 22 | 500 | onSurface |
| 行标题 | 15 | 400 | onSurface |
| 行摘要 | 12 | 400 | onSurfaceVariantSummary |
| 组标题 | 12 | 500 | onSurfaceVariantSummary |
| 元信息 / 刻度 | 10 | 400 | onSurfaceVariantSummary |

字重只承担强调，层级主要靠尺寸与颜色。中文用系统字体（目标机上就是 MiSans），不打包字体。

### 颜色角色（**禁止硬编码**）

一律走 `MiuixTheme.colorScheme`：

| 用途 | 角色 |
| --- | --- |
| 页面底 | `background` |
| 卡片 | `surfaceContainer` |
| 行标题 | `onSurface` |
| 行摘要 / 组标题 / 刻度 | `onSurfaceVariantSummary` |
| 分隔线 | `dividerLine` |
| 强调（已选、读数） | `primary` |
| 已选底 | `primary` @ 0.12（浅）/ 0.18（深） |
| 警告 | `error` |
| 隐藏到 Dock 之下的底 | `surface` |

Accent 仍由用户选（`AuroraPalette`），喂给 `MiuixTheme(colors = ...primary = ...)`，只出现在"已选"和读数上。

### 动效
- 一条缓动：`FastOutSlowInEasing`；反馈类 120ms，进入类 260ms。
- 只动颜色 / 透明度 / transform。不动宽高、不动 margin、不动大模糊。
- 全局最多一次入场：内容淡入上移 12dp。
- 说明性动效只有三处：曲线换形状时六段各自一根弹簧（让"这个预设做了什么"看得见）、状态胶囊的信号灯变色过渡、选中勾的淡入缩放。
- 拖动手感优先于动效：滑杆拖动只改本地草稿，松手才提交一次写入。任何"每帧一次写入"的交互都算 bug。

## 四、页面职责（消除"这一块那一块"）

四个 Tab 各只负责一件事，不重复：

| 页 | 唯一职责 | 内容 |
| --- | --- | --- |
| 音效 | 选一个声音 | 状态胶囊 → 总开关 → 当前曲线（hero）→ 预设列表 → 声场与响度 |
| 均衡器 | 改这条曲线 | 状态胶囊 → 曲线图 + 六段滑杆 → 强度 → 恢复 → 按应用分配入口 |
| 社区 | 借别人的耳朵 | 分享入口 → 身份行 → 信息流卡片（EqGlyph 缩略 + 昵称 + 喜欢/评论 + 右下机型徽标）→ 详情面板（曲线 + 保存到我的预设 + 评论） |
| 设置 | 改这个 App | 外观 → 主题 → 交互 → 社区（我的喜欢 / 社区身份）→ 设备（适配 / 诊断与链路）→ 更新 → 反馈 → 关于 |

**诊断是二级入口（1.3.3 起）**
它的职责原文是「出问题时能自证」——按定义低频，不占 Dock。入口三处：点顶部状态胶囊（出问题的那一刻就在眼前）、设置 → 诊断与链路、新手引导的「查看适配详情」。形态是全屏 Panel，与设备适配、主题等面板同一套骨架。

**删掉的重复**
- 预设网格不再同时出现在音效页和均衡页；只留在音效页。
- 均衡页的"新建曲线"独立面板取消：六段滑杆直接在该页，"存为新预设"降为一个行内按钮。
- `GroupLabel`（浮动灰色小标题）改为 `SmallTitle`。
- 三种控件语言收敛为一种：Material3 `Slider` 全部换成 miuix `Slider`；`LiquidSwitch` 换成 miuix `Switch`（HIG 明确玻璃只留给功能层与激活态）。

**保留**
- 液态玻璃 Dock（用户已验收）。
- 折射 / innerShadow / blur 那套工具（Dock 与浮起面板仍用）。

## 五、不做什么
- 不加新功能、不改引擎行为、不改文案里的事实。
- 不引入生成式配图（这是仪器，moment 来自它自己的曲线图）。
- 不为了"看起来高级"加渐变、光晕、彩色阴影、emoji 图标。