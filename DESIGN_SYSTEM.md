# Qiri+ Android 设计系统与 UI 工程规范（Design System v3.1）

> **适用范围**：Qiri+ Android 客户端全量界面、统一车控与 MDI 矢量图标体系、启动图标、车控甲板与长按编辑模式、原生桌面小组件（4 种尺寸）、共享组件套件（`DesignComponents.kt`）、空间连续性与触觉分级（`MotionSpec.kt`）、四类交互容器、多层级导航栈、6 位安全密码、5 行首页信息架构、车辆/驾驶员/地点、手机号与敏感信息脱敏、演示模式（`demo/demo`）、调试诊断界面及系统手动配对规范。
> **审美基准**：**Minimalist Automotive Dark（极简暗色汽车美学）** — 深邃中性暗底、克制留白、清晰层级、零装饰线框、高精度同心圆角与网格秩序。
> **资产边界**：视觉编排仅以行业标杆的克制留白与层级密度为参考；Qiri+ 保留自有 3D GLB 车模、Qiri+ 品牌标识、启动图标、统一单色车控矢量套件及暗色原生实现。

---

## 1. 核心设计哲学（Core Principles）

1. **极简克制（Zero Chrome）**：以车辆本体与核心状态为主角，彻底杜绝多余方框、嵌套线框、选区矩形框与装饰性元素。
2. **零 Emoji / 单一图标语言**：严禁在用户可见文案或控件中出现任何 Emoji（如 `☁️`、`🔵`、`🌐`、`📍`、`✓` 等）；所有车控图标严格服从统一 `24 × 24 / 1.65u` 单色描边矢量规范，通用 UI 图标统一采用 Pictogrammers MDI 24×24 填充路径。
3. **零内部协议编号**：严禁向用户暴露内部接口编号或技术代号（如 `(107)`、`(121)`、`(145)`、`(901)`、`op=30` 等）。
4. **零冗余手动刷新（应用内）**：应用内依托自动刷新生命周期维持数据新鲜度，取消常规手动“刷新”按钮（仅网络报错态提供单次“重试”；桌面小组件受系统周期限制保留右上角轻量同步胶囊）。
5. **全链路无缝暗色画布**：登录态所有全屏页面、系统状态栏与导航栏严格统一为 `BG_CANVAS (#161719)`，零色差接缝。
6. **车辆优先、入口克制**：首页先呈现车辆与真实主状态，再呈现快捷车控与 5 行功能入口；一级列表每项仅保留单一明确入口。
7. **隐私默认收束**：车辆身份、车主资料与登录凭据默认按固定掩码脱敏，复制与无障碍朗读不得绕过脱敏。
8. **诚实降级**：缺失定位、街道反查或车况字段时展示明确占位文案，严禁以经纬度或推测状态冒充真实结果。

---

## 2. 色彩、层级与材质 Token（`ThemeTokens` & Elevation）

### 2.1 应用与组件色彩 Token（与 `DesignComponents.kt` 严格同步）

| Token / 用途 | 色值 | 适用场景 |
| --- | --- | --- |
| `BG_CANVAS` | `#161719` | 全应用主画布背景、顶栏、状态栏/导航栏底色 |
| `BG_SURFACE` | `#1C1D21` | 底部物理抽屉、居中确认弹窗、小组件遥测数据卡底色 |
| `BG_POPOVER` | `#202228` | 锚点悬浮下拉菜单、小组件顶部胶囊底色 |
| `BG_CARD_ELEVATED` | `#252831` | 浮层/次级高亮表面 |
| `WIDGET_TILE_IDLE` | `#22242A` | 桌面小组件控车按钮常态底色（边框 `0.75dp #2C2F38`） |
| `BTN_SECONDARY` | `#2B2F3A` | 次级按钮底色（边框 `0.9dp #424858`） |
| `SEGMENT_TRACK` / `SEGMENT_PILL` | `#13151A` / `#343A48` | 分段控制轨道（边框 `#2D323E`）与滑块（边框 `#4B5368`） |
| `DIVIDER` | `#282B33` | 应用内 `0.6dp` 列表分割线与未聚焦底线（小组件细线使用 `#262930`） |
| `BORDER_SUBTLE` | `#363B48` | 浮层与卡片极细描边基准 |
| `TEXT_PRIMARY` | `#FFFFFF` | 主标题、列表主文案、核心数值、常态车控图标 |
| `TEXT_BODY` | `#D1D1D6` | 正文、车控按钮标签、小组件车牌胶囊文字 |
| `TEXT_SUB` | `#8E9299` | 副标题、分组眉标、小组件次级标签与时间戳 |
| `TEXT_MUTED` | `#5C606A` | 占位提示、禁用态、右侧指示箭头 `CHEVRON_RIGHT` |
| `ACCENT_ACTIVE` / `ACCENT_LIGHT` | **`#3D9BFF`** | 激活车控图标/文案、小组件激活态、编辑态固定圆点与悬停光晕、输入框聚焦底线 |
| `ACCENT_BLUE` | `#3E6AE1` | 主 CTA 按钮、`ToggleSwitch` 开启轨道、编辑态“完成”胶囊 |
| `STATUS_GREEN` | `#34D399` | BLE 直连、正常状态、行程起点 |
| `STATUS_AMBER` | `#FBBF24` | BLE 发现/握手连接中、温和告警 |
| `DANGER` | `#F87171` | 破坏性确认操作、低油量告警、校验报错、行程终点 |

### 2.2 表面层级（Surface Elevation & Scrim）

| 层级 | 背景色 | 描边 / 阴影 | 适用对象 |
| --- | --- | --- | --- |
| **L0 画布层** | `#161719` | 无边框、无阴影 | 首页、二级/三级全屏页面、无框列表行 |
| **L1 卡片/容器层** | `#1C1D21` / `#22242A` | `0.75–1dp` `#262930` / `#2C2F38` | 小组件遥测卡与控车砖、一级车辆/驾驶员概要卡 |
| **L2 物理抽屉/模态层** | `#1C1D21` | 顶部圆角 `22dp`；同窗 GPU 合成遮罩 `dimAmount 0.58–0.65` | 底部抽屉（`AppBottomSheet`、`SixDigitPinSheet`）、居中确认弹窗（`16dp` 圆角） |
| **L3 悬浮锚点层** | `#202228` | `0.8dp #2F323B` + `12dp` elevation | 锚点下拉菜单（`AnchoredDropdown`，`12dp` 圆角） |

---

## 3. 字阶、间距网格与同心圆角（Typography, Spacing & Concentric Radii）

### 3.1 字阶规范（Typography Scale）

| 角色 | 字号 / 字重 | 色值 | 约束规则 |
| --- | --- | --- | --- |
| 顶栏标题（`AppTopBar`） | `18sp` Bold | `#FFFFFF` | 单行 `ellipsize="end"` |
| 弹窗/抽屉主标题 | `17–18sp` Bold | `#FFFFFF` | 居中或左对齐，克制不换行 |
| 列表主标题（`AppListRow`） | `16sp` Regular | `#FFFFFF` | 单行截断 |
| 小组件大卡片车型标题 | `15–16sp` Bold | `#FFFFFF` | `includeFontPadding="false"`，单行截断 |
| 正文 / 输入 / 主按钮 | `14.5–15sp` Regular / Bold | `#FFFFFF` / `#D1D1D6` | 输入框单行；按钮居中单行 |
| 列表副标题 / 尾部值 | `13sp` Regular | `#8E9299` | 单行截断，与主标题间距 `2dp` |
| 分组眉标（`AppSectionHeader`） | `12.5sp` Regular | `#8E9299` | 位于分组上方，左右内边距 `20dp` |
| 小组件核心数值（油量/温度/里程） | `12.5–13sp` Bold | `#FFFFFF` / `#3D9BFF` | **强制启用等宽数字** `android:fontFeatureSettings="tnum"` |
| 车控标签（展开甲板 / 编辑态） | `11.5sp` Regular | `#D1D1D6` / `#3D9BFF` | 单行居中，`includeFontPadding="false"` |
| 小组件次级标签 / 双胶囊文案 | `10sp` Regular | `#8E9299` / `#D1D1D6` | `includeFontPadding="false"`，时间/车牌启用 `tnum` |

### 3.2 4dp / 8dp 间距网格与触区规范

全应用与桌面小组件统一以 **`4dp` 微步进 / `8dp` 主节奏** 排布，禁止随意出现 `7dp`、`11dp`、`13dp`、`15dp` 等破坏网格秩序的零散数值：

| 对象 | 规范尺寸 / 间距 |
| --- | --- |
| 页面基础外边距（二级/三级页） | 顶栏左右 `16dp`；列表行左右 `20dp`；分组间距 `12–18dp` |
| 桌面小组件网格基数 | 外边距 `8dp`；元素间距 `8dp`；顶栏光学内缩 `4dp`（合计 `12dp`） |
| 可点击图标与主操作热区 | 最小 `48dp × 48dp`（视觉图标 `18–28dp` 居中于热区） |
| 列表行高度（`AppListRow`） | `minHeight 56dp`，上下内边距 `12dp` |
| 一级车辆 / 驾驶员概要卡 | `minHeight 92dp`，内边距 `18dp / 15dp`，卡间距 `12dp` |
| 纯展示顶栏通道图标 | `28dp` 容器、`16dp` glyph，不可误加点击热区 |

### 3.3 同心圆角几何法则（Concentric Corner Radius Law）

凡存在圆角容器嵌套，内层圆角必须严格遵循同心圆公式，杜绝圆角挤压变形或弧度不平行：

$$R_{\text{inner}} = \max\left(0,\; R_{\text{outer}} - P_{\text{outer}}\right)$$

- **桌面小组件（Pre-API 31）**：$R_{\text{outer}} = 24\text{dp}$，$P_{\text{outer}} = 8\text{dp}$ $\Rightarrow$ $R_{\text{inner}} = 16\text{dp}$；顶部 `24dp` 高全胶囊（$R_{\text{pill}} = 12\text{dp}$）位于 $8 + 4 = 12\text{dp}$ 边距处 $\Rightarrow$ $24 - 12 = 12\text{dp}$ 精确同心。
- **桌面小组件（API 31+）**：直接绑定系统同心 Token：`@android:dimen/system_app_widget_background_radius` 与 `@android:dimen/system_app_widget_inner_radius`（系统保证 $\Delta = 8\text{dp}$），配合统一 `8dp` 外边距实现全机型像素级同心。
- **应用内弹窗 / 抽屉**：底部抽屉顶部圆角 `22dp`，内部数字键盘/按钮圆角 `10–12dp`；确认弹窗外框 `16dp`，底部按钮 `11dp`。

---

## 4. 统一图标体系（Automotive Control & MDI UI Iconography）

### 4.1 强制绘图规范（App 与小组件 1:1 单一真相源）

| 规范项 | 强制标准 |
| --- | --- |
| **画布网格** | 严格 `24 × 24` 视口（`viewportWidth="24"`, `viewportHeight="24"`, `24dp × 24dp`） |
| **光学安全区** | 居中 `~18 × 18u` 有效绘区（四周保留 `2–3u` 内置光学留白，各尺寸下直接居中对齐，严禁按单图标补丁 padding） |
| **车控描边标准** | 统一单色纯描边：`strokeWidth = 1.65u`（`AppIconView.CONTROL_STROKE_WIDTH = 1.65f` & XML `android:strokeWidth="1.65"`）、`Cap.ROUND`（`round`）、`Join.ROUND`（`round`）、`fillColor="#00000000"` |
| **单一真相源（1:1 Parity）** | `AppIcons.kt` 中全部 16 项 `customControlGlyph` 均通过 `PathParser` 渲染 SVG `pathData`；与桌面小组件共用的 6 项控车图标（`lock_closed`、`lock_open`、`fan`、`flash`、`trunk`、`engine`）与 `res/drawable/ic_widget_*.xml` **字符级完全一致**，外加同规范 `ic_widget_refresh.xml` |
| **通用 UI 图标** | 其余 24 项导航与工具图标统一使用 Pictogrammers Material Design Icons（`@mdi/js`）`24 × 24` 填充矢量路径（`customControlGlyph = false`） |
| **单色着色规则** | 源路径纯白 `#FFFFFF`，支持 `AppIconView.tintColor`、`RemoteViews.setInt(..., "setColorFilter", color)` 与 XML `android:tint`；常态 `#FFFFFF`，激活态 `#3D9BFF`，弱化态 `#8E9299` |

### 4.2 车控图标来源与 40 项 `AppIconType` 对照表

| 分组 | 图标枚举（`AppIconType`） | 资产来源与几何规范 |
| --- | --- | --- |
| **核心车控 · `base.apk.1` 直采统一（8 项 + 刷新）** | `LOCK_CLOSED`、`LOCK_OPEN`、`FAN_BLADE`（支持 `spinEnabled` 旋转）、`BOLT_FLASH`、`TRUNK_OPEN`、`ENGINE_POWER`、`CAR_CONTROLS`、`HORN_SOUND`（+ 小组件 `ic_widget_refresh.xml`） | 提取自 `base.apk.1`（`com.xiaote`）矢量路径，统一归一至 `24×24 / 1.65u` 纯描边与 `~18u` 光学安全区（`LOCK_OPEN` 沿用解锁开锁梁姿态匹配统一锁体） |
| **核心车控 · `download.zip` 补齐统一（4 项）** | `TAILGATE_CLOSE`、`SUNROOF_OPEN`、`SUNROOF_CLOSE`、`SUNROOF_TILT` | 提取自 `download.zip`（`iconfont`）：`houbeixiang_guan_SUV`（`TAILGATE_CLOSE`）、`tianchuang_kai_SUV`（`SUNROOF_OPEN`）、`tianchuang_guan_SUV`（`SUNROOF_CLOSE`）；`SUNROOF_TILT` 复用同一整车侧廓底盘叠加后翘玻璃与上仰箭头，全部转为 `1.65u` 单线描边 |
| **核心车控 · 同源语汇延展（4 项）** | `ENGINE_STOP`、`WINDOW_OPEN`、`WINDOW_VENT`、`WINDOW_CLOSE` | `ENGINE_STOP` 复用启停环 + 圆角方块熄火核心；`WINDOW_*` 基于 `base.apk.1` `ic_vehicle_window` A 柱斜角车门廓形纯描边化，分别叠加降窗下箭头 / 透气波纹 / 升窗上箭头 |
| **导航与溢出菜单（7 项 · MDI）** | `CHEVRON_DOWN`、`CHEVRON_RIGHT`、`BACK_ARROW`、`MESSAGE_BUBBLE`、`MENU_HAMBURGER`、`MORE_HORIZ`、`MORE_VERT` | Pictogrammers MDI `24 × 24` 填充路径 |
| **主菜单与系统（9 项 · MDI）** | `LOCATION_ARROW`、`SHIELD_CHECK`、`KEY_ROUND`、`INFO_CIRCLE`、`BLUETOOTH_ICON`、`CLOUD_NET_ICON`、`RADAR`、`CROSSHAIR`、`MAP_PIN` | Pictogrammers MDI `24 × 24` 填充路径 |
| **通用操作（8 项 · MDI）** | `CALENDAR`、`CLOCK`、`CHECK`、`CHECK_CIRCLE`、`SWAP_CAR`、`ADD_ICON`、`CLOSE_ICON`、`LOG_OUT` | Pictogrammers MDI `24 × 24` 填充路径 |

**图标 Do / Don't**：
- **Do**：新增任何车控图标必须落入 `24×24` 视口、`~18u` 安全区、`1.65u` 圆头描边，并在 `AppIcons.kt` 与 `ic_widget_*.xml` 保持同源路径；应用启动图标（Day/Night 自适应图标与各密度 PNG）恒定保持黑底 `#050505` + 纯白 `#FFFFFF` Qiri+ 标识，浅色模式下严禁反转为白底。
- **Don't**：严禁混入 Emoji、位图 PNG 图标、多色渐变图标、实心填充车控图标，或超出 `1.65u` 的粗细混搭。

---

## 5. 车控甲板与长按编辑交互规范（Car Control Dock & Edit Mode）

### 5.1 入口与退出路径

- **进入编辑态**：长按首页顶部 4 枚快捷车控或展开甲板内任意车控按钮 $\rightarrow$ 触发 `HapticLevel.LONG_PRESS_MODE` $\rightarrow$ 自动展开车控甲板并进入编辑态。
- **退出编辑态**：点击编辑栏右上角 `“完成”` 胶囊（`#3E6AE1`）、下拉收起车控甲板，或触发系统返回键（`exitQuickDeckEditMode()`）。

### 5.2 绝对无方框原则（Frameless Zero-Box Rule）

车控按钮在常态、按下、长按、编辑态、聚焦态及拖拽态下**严禁出现任何方形/矩形边框、虚线框、填充卡片底色或系统默认矩形焦点高亮**：

- `AppComponents.createControlIconButton()` 强制设置：
  - `background = null`
  - `foreground = null`
  - `defaultFocusHighlightEnabled = false`（API 26+）
  - `clipChildren = false`、`clipToPadding = false`
- 编辑态中顶部 4 枚槽位与底部候选网格一律保持无框透底，禁用长按二次触发（`isLongClickable = false`）。
- 底部候选区拖回移除时，**不得**给整个甲板容器绘制矩形外框，而是将编辑栏提示文案高亮为 `#3D9BFF`（`“松手从快捷栏移除”`）。

### 5.3 四列槽位解剖与尺寸规范（Top 4 Bar ↔ Bottom Candidate Grid 1:1 对齐）

| 状态 / 区域 | 网格与尺寸 | 图标与文案结构 |
| --- | --- | --- |
| **常态顶部 4 快捷栏** | 4 列等宽（`weight = 1f`），行高 `54dp`，左右内边距 `18dp` | `26dp` 无框单色图标居中（无下方文字，保持首屏极致纯净），按压缩放 `0.88×` |
| **编辑态顶部 4 快捷栏** | 4 列等宽（`LayoutParams(0, WRAP_CONTENT, 1f)`），`minimumHeight = 74dp`，左右内边距 `18dp`，零槽位方框外边距 | **必须展示中文文字标签**：`25dp` 图标居中于 `39dp` `control_icon_frame` + `5dp` 间距 + 单行居中 `11.5sp` `TEXT_BODY (#D1D1D6)` 标签（`includeFontPadding = false`，`MATCH_PARENT` 宽，`ellipsize = END`），进入时 `180ms EASE_OUT_EXPO` 淡入；容器高度由 `measureQuickActionRowHeight()` 精确测算，消除展开跳变 |
| **编辑态底部候选网格** | 与顶部 4 栏严格同列对齐（4 列等宽，左右 `18dp`），`minimumHeight = 74dp` | 与顶部 4 栏 1:1 同构（`25dp` 图标 / `39dp` `control_icon_frame` / `5dp` 间距 / `11.5sp` 标签），仅展示未固定的候选车控（过滤已固定项，拖拽中源槽位淡化至 `alpha = 0.28`） |
| **已固定标记（Pinned Dot）** | 顶部 4 槽位 `control_icon_frame` 右上角（`topMargin 3dp` / `marginEnd 3dp`） | `8dp × 8dp` 圆形 `#3D9BFF` 圆点 + `1.5dp` `#161719` 镂空描边 |
| **空槽位（固定不足 4 项）** | `39dp` 圆形 `OVAL`（`rgba(255,255,255,0.085)`） | 居中 `18dp` `ADD_ICON (#8E9299)` + 下方同基线 `“可拖入”` 标签（`11.5sp`） |

### 5.4 按压、拖拽与悬停反馈

- **按下准备**：`0.92×` 缩放 + `0.85` 透明度（`80ms EASE_OUT_EXPO`）。
- **起拖（越过 `touchSlop`）**：拉起无方框背景的纯图标+文案 `DragShadowBuilder`，源位置淡化至 `alpha = 0.28`。
- **顶部槽位悬停（`applyTopSlotDragHover`）**：在 `control_icon_frame` 后方叠放 `39dp` 圆形 `OVAL` `#3D9BFF` 柔和光晕（`alpha = 46/255`）+ 图标与文字着色 `#3D9BFF` + `1.08×` 微放大（`100ms EASE_OUT_EXPO`），严禁方形高亮框。

**车控编辑态 Do / Don't**：
- **Do**：编辑态顶部 4 枚槽位与底部候选网格始终展示 `11.5sp` 中文标签并使用 `WRAP_CONTENT` + `minHeight 74dp` 防字号放大截断；悬停反馈一律用圆形柔光晕或标题文字着色。
- **Don't**：严禁在任何车控槽位、候选网格、拖拽影子或甲板容器上绘制矩形实线框、虚线框或方形底卡；严禁硬编码 `< 70dp` 行高导致编辑态文字被裁切。

---

## 6. 原生桌面小组件架构规范（Android Home-Screen Widgets）

### 6.1 四款尺寸矩阵

| 规格 | 布局与 Provider | 最小尺寸（`widget_info_*.xml`） | 信息与控件结构 |
| --- | --- | --- | --- |
| **4×1 极简控车条** | `widget_car_bar.xml` / `CarWidgetBarProvider` | `250 × 56dp`（`resizeMode="horizontal"`） | 左车型+主状态/能源（`weight 1`），右 4 等宽同心图标砖（`weight 1.7`，`20dp` 图标） |
| **2×2 紧凑车况** | `widget_car_compact.xml` / `CarWidgetCompactProvider` | `130 × 130dp` | 顶栏车型/状态 + 右上 `24dp` 圆形刷新胶囊，中车模 + `4dp` 能源细条，底 2 等宽图标砖（`40dp` 高） |
| **4×2 经典车控卡** | `widget_car_medium.xml` / `CarWidgetMediumProvider` | `250 × 130dp` | 顶栏车型 + 右上双胶囊（车牌 + 同步时间/刷新），中左状态/续航/进度条 + 右无遮挡车模，底 4 等宽带文字控车砖（`48dp` 高） |
| **4×3 旗舰仪表盘** | `widget_car_large.xml` / `CarWidgetLargeProvider` | `250 × 210dp` | 顶栏 Row1 车型+双胶囊 & Row2 状态，中展台车模，下统一 3 列遥测数据条，底 5 等宽带文字控车砖（`48dp` 高） |

### 6.2 强制几何、双胶囊与数据栏规范

1. **同心圆角与 4dp/8dp 间距（`values/dimens.xml` & `values-v31/dimens.xml`）**：
   - 全 4 款小组件根节点统一 `android:padding="@dimen/widget_outer_padding"`（`8dp`），网格间距 `@dimen/widget_grid_gap`（`8dp`）。
   - Pre-API 31：`widget_outer_radius = 24dp`，`widget_inner_radius = 16dp`（$24 - 8 = 16\text{dp}$）；API 31+：`@android:dimen/system_app_widget_background_radius` / `@android:dimen/system_app_widget_inner_radius`。
   - 顶栏设置 `widget_header_inset = 4dp`，使 `24dp` 高右上角胶囊（圆角 `999dp`，实际半径 `12dp`）距外边缘为 $8 + 4 = 12\text{dp}$，满足 $24 - 12 = 12\text{dp}$ 顶部同心。
2. **右上角双胶囊对齐契约（Medium & Large）**：
   - `widget_plate_badge`（车牌）与 `widget_btn_refresh`（同步时间 + 刷新图标）必须位于**同一水平单行**（Large 中锁定在 Row 1，禁止跨两行垂直错位）。
   - 共享严格一致的几何与字阶：`layout_height="@dimen/widget_pill_height"`（`24dp`）、`paddingStart/End="@dimen/widget_pill_padding_h"`（`8dp`）、`layout_marginEnd="@dimen/widget_pill_gap"`（`8dp`，车牌 `GONE` 时自动折叠零残留）、`999dp` 胶囊底（`widget_pill_bg.xml`）、`10sp` `includeFontPadding="false"` `tnum` 文字；`widget_plate_badge` 用 `gravity="center"`，`widget_update_time` 用 `layout_height="match_parent"` + `gravity="center_vertical"` 紧邻居中 `12dp` 刷新图标，确保双胶囊水平中线与文字基线像素级重合。
   - **强制属性**：所有水平 `LinearLayout` 必须显式声明 `android:baselineAligned="false"`，防止 Android 默认文字基线对齐覆盖 `gravity="center_vertical"` 导致双胶囊上下错位。
3. **旗舰仪表盘遥测数据栏（Large Telemetry Strip）**：
   - 承载体 `widget_surface_card`（`#1C1D21`，`0.75dp #262930` 描边，`@dimen/widget_inner_radius`），内含 3 列指标（`油量续航` | `空调状态` | `总里程`）。
   - **严禁列宽或对齐割裂**：三列严格等权 `layout_weight="1"`，统一居中对齐 `gravity="center_horizontal"`（含所有单元格及其内嵌 `TextView`），统一左右内边距 `6dp`，统一 `1dp × 24dp` `#262930` 垂直细线分隔；标签统一 `10sp #8E9299`，数值统一 `12.5sp Bold #FFFFFF`（空调开启 `#3D9BFF`）+ `tnum` + `4dp` 垂直间距，双基线笔直对齐。
4. **色彩与动态态一致性（`CarWidgetEngine.kt`）**：
   - 外壳渐变 `#1A1B1F → #141518`（`1dp #262930` 描边）；控车砖常态 `#22242A`（`0.75dp #2C2F38`），激活态 `#243D9BFF` 填充 + `1dp #803D9BFF` 描边；`COLOR_LABEL_IDLE = #8E9299`，`COLOR_ACTIVE = #3D9BFF`。
5. **`RemoteViews` 严格兼容性**：
   - 布局仅允许使用 `@RemoteView` 白名单控件：`LinearLayout`、`FrameLayout`、`TextView`、`ImageView`、`ProgressBar`（分割线必须用 `<FrameLayout>`，**严禁** `<View>`、`Space`、`ConstraintLayout`、`CardView`）。
   - 图标着色在 XML 使用 `android:tint`、在运行时使用 `RemoteViews.setInt(id, "setColorFilter", color)`。

---

## 7. 共享组件库与多层级导航规范（`DesignComponents.kt`）

`MainActivity`、`SettingsScreen` 与 `VehicleFeatureSheets` 必须 100% 复用以下组件，严禁局部私自拼装：

1. **`AppTopBar`（`AppComponents.createTopBar`）**：固定高度 `56dp`，底色 `BG_CANVAS (#161719)`，左右内边距 `16dp`；左侧 `40dp × 40dp` 返回热区（`marginStart -8dp` 光学对齐）+ 居中 `22dp` `BACK_ARROW`；标题 `18sp` Bold `#FFFFFF`；可选右侧操作文案（`15sp` Bold `#3D9BFF`）或自定义视图。
2. **`AppListRow`（`AppComponents.createListRow`）**：无边框透底列表行，`minHeight 56dp`，内边距 `20dp H / 12dp V`；左 `22dp` MDI 图标（`TEXT_BODY`，`marginEnd 16dp`）、`16sp` 主标题、可选 `13sp` 副标题（`topMargin 2dp`） / 右侧 `13sp` 文本 / `trailingView`、`16dp` `CHEVRON_RIGHT (#5C606A)`；分割线使用 `createHairline`（`0.6dp #282B33`）。
3. **`AppSectionHeader`（`AppComponents.createSectionHeader`）**：`12.5sp` `TEXT_SUB (#8E9299)`，内边距 `20dp H / 18dp T / 6dp B`。
4. **`ToggleSwitch`**：`46dp × 26dp` 平滑胶囊开关，关闭轨道 `#2C2F36`，开启轨道 `ACCENT_BLUE (#3E6AE1)`，切换动效 `200ms EASE_OUT_EXPO`，触发 `LIGHT_TICK` 触觉；支持 `onToggleAttempt` 拦截以挂载二次确认。
5. **`UnderlineInput`（`AppComponents.createUnderlineInput`）**：无方框极简底线输入框（`15sp #FFFFFF`，提示 `#5C606A`，底部 `0.8dp` 细线，聚焦高亮 `#3D9BFF`）。
6. **`WheelDateTimePicker` / `DateTimePickerField`**：暗色滚轮底部抽屉选择器（`#1C1D21` 表面、居中高亮条、5 行渐隐字阶、`LIGHT_TICK` 滚轮触觉），覆盖 `YEAR_MONTH`、`DATE`、`DATE_HOUR`、`DATE_TIME`、`DATE_TIME_MINUTE`、`TIME`、`TIME_SECONDS` 及日期/日期时间区间，彻底替代系统弹窗与手写时间。
7. **`AnchoredDropdown`（`AppComponents.showAnchoredDropdown`）**：`BG_POPOVER (#202228)`、`12dp` 圆角、`0.8dp #2F323B` 边框、`12dp` elevation，紧贴锚点展开（`scale 0.92 → 1.0`，`180ms EASE_OUT_EXPO`）。
8. **`ControlIconButton`（`AppComponents.createControlIconButton`）**：无边框单色车控图标按钮（详见 §5）。
9. **`AppActionButton`（`AppComponents.createActionButton`）**：`PRIMARY`（`minHeight 46dp`、`12dp` 圆角、`#3E6AE1`）、`SECONDARY`（`minHeight 44dp`、`11dp` 圆角、`#2B2F3A` + `0.9dp #424858`）、`DANGER_TEXT`（`minHeight 48dp`、无框透底、`#F87171`）。
10. **`ConfirmDialog`（`AppComponents.showConfirmDialog`）**：居中模态卡（`BG_SURFACE`、`16dp` 圆角、`22dp` 内边距），通过 `AppSystemInsets.installModalContent` 挂载同窗 GPU 合成遮罩，入场 `MotionSpec.animateModalEnter`（`scale 0.94 → 1.0` + `+12dp → 0` 上浮，`220ms EASE_OUT_EXPO`），退场 `MotionSpec.dismissModalAnimated`（精确镜像回缩 + 遮罩淡出，`160ms EASE_STANDARD`），最多 2 个操作按钮。
11. **`AppBottomSheet`**：`BG_SURFACE (#1C1D21)`、统一顶部 `22dp` 圆角、顶部 `38dp × 4dp` `#444854` 拖拽手柄、同窗 GPU 合成遮罩、支持 1:1 跟手下拉关闭与弹簧回弹、**无冗余“关闭”文字按钮**。
12. **`AppEmptyState`（`AppComponents.createEmptyState`）**：居中 `36dp` MDI 图标（`#5C606A`）+ `15sp` 主文案 + 可选 `13sp` 说明文案。

### 7.1 四类交互容器唯一白名单

| 容器类型 | 唯一适用场景 |
| --- | --- |
| **1. 全屏二级 / 三级页面** | 设置、个人资料、关于 Qiri+、安全性、驾驶员、车辆信息、地点与历史行程、消息提醒、电子围栏地图编辑页 |
| **2. 锚点下拉菜单** | 左上角车名切换车辆、个人资料性别选择、车辆信息右上角更多操作 |
| **3. 底部物理抽屉** | 温度调节、车辆升级（OTA）、防盗与电子围栏列表、日期时间滚轮、6 位 PIN 安全键盘 |
| **4. 居中确认弹窗** | 行程轨迹开关确认、退出登录、修改登录密码、隐私协议、不可逆破坏性操作确认 |

### 7.2 多层级导航栈与 Edge-to-Edge 安全区

- **结构**：`Home（持久常驻，3D 场景永不销毁重建） → Level 2 Overlay → Level 3 Overlay`，由 `MainActivity` 维护 `subpageOverlayStack`，入栈右滑 `+28dp → 0`（`240ms EASE_OUT_EXPO`），返回逐级出栈并释放 WebView/资源。
- **全面屏沉浸（`AppSystemInsets`）**：全程透明状态栏与导航栏、`shortEdges` 挖孔延伸、禁用系统导航栏对比度遮罩；滚动列表与底部抽屉通过 `applyBottomNavPadding` / `installBottomSheetContent` 动态避让小白条与输入法，同时 `#1C1D21` 抽屉无缝延展至小白条后方；启用 Android 13+ 预测性返回（`enableOnBackInvokedCallback="true"`）与桌面长按快捷方式（`res/xml/shortcuts.xml`）。

---

## 8. 空间连续性、触觉分级与数据刷新（`MotionSpec` & `AppHaptics`）

### 8.1 空间连续性规范（Spatial Continuity）

界面切换必须保持明确的空间方向、深度层级与进退对称性，严禁生硬跳变（`visibility` 瞬切）或无退场动画的窗口闪烁消失：

- **标准曲线**：`EASE_OUT_EXPO (0.22, 1, 0.36, 1)`（入场减速落位）、`EASE_STANDARD (0.2, 0, 0, 1)`（退场利落收束）、`EASE_SPRING_RELEASE (0.22, 1.12, 0.36, 1)`（按压回弹）。
- **核心场景转场矩阵**：

| 场景 | 统一 API | 空间连续性规范 |
| --- | --- | --- |
| **登录 / 欢迎页 → 首页** | 离场层水平滑出 | 首页（含 3D WebView `DrawGLFunctor`）保持恒等变换挂载，认证层在其上方 `-28dp` 水平滑淡离场（`240ms EASE_OUT_EXPO`），零跳帧 |
| **二级 / 三级全屏覆盖页入出栈** | `MotionSpec.animateSubpageEnter` / `animateSubpageExit` | 进栈：新页面沿共享 X 轴自 `+20%` 宽滑入（`260ms EASE_OUT_EXPO`），底层页面同步 `-28dp` 视差 + `0.975×` 景深后退（`alpha 0.86`）；返回：严格镜像右滑退出（`200ms`）并同步恢复底层景深（`240ms`） |
| **容器内子页切换（如设置子页 / 诊断页签）** | `MotionSpec.transitionContainer(host, view, dir)` | 前进（`dir > 0`）：新视图 `+22%` 宽 + `0.985→1.0` 缩放滑入（`260ms`），旧视图 `-12%` 宽 + `0.98×` 缩放离场（`200ms`）；后退（`dir < 0`）严格反向镜像 |
| **底部物理抽屉** | `AppSystemInsets.installBottomSheetContent` + `animateSheetEnter` / `dismissSheetAnimated` | 统一 `22dp` 顶部圆角 + 同窗 GPU 合成遮罩；入场 `+64dp → 0`（`260ms`）；支持 1:1 跟手下拉与速度阈值关闭；退场完整滑出屏幕底部（`200ms`） |
| **居中模态弹窗** | `AppSystemInsets.installModalContent` + `animateModalEnter` / `dismissModalAnimated` | 入场 `0.94 → 1.0` 缩放 + `+12dp → 0` 轻浮 + 同窗遮罩淡入（`220ms EASE_OUT_EXPO`）；关闭/点击遮罩/返回键一律执行 `1.0 → 0.94` + `+10dp` 下沉 + 遮罩淡出镜像退场（`160ms EASE_STANDARD`） |
| **分段栏 / 切车抬头 / 过滤切换** | `MotionSpec.createSlidingSegmentedBar` + `animatePanelSwitch` | 胶囊滑块沿 X 轴连续平移（`220ms`），内容区沿对应方向 `±18dp`（或垂直 `+10dp`）轻滑入场（`200ms`） |
| **折叠面板展开 / 收起** | `MotionSpec.animateExpandCollapse` | 面板平滑显隐与轻位移动效（展开 `200ms` / 收起 `150ms`），同步驱动指示箭头 `180°` 旋转 |
| **微交互按压与错误反馈** | `attachPressPhysics` / `shakeError` | 按压 `90ms` / 回弹 `160ms`；编辑态悬停 `100ms`；下拉菜单 `180ms`；错误阻尼抖动 `360ms`；轨迹回放 `1200ms` |

**空间连续性 Do / Don't**：
- **Do**：同级切换用滑动胶囊 + `animatePanelSwitch`，层级深入用共享 X 轴推入与底层景深联动，模态弹窗一律走 `installModalContent` + `dismissModalAnimated` 确保进退对称；高频刷新的调试/状态面板必须原地更新（禁止周期性 `removeAllViews()` 破坏滚动位置与动画）。
- **Don't**：严禁直接调用无退场动画的原生弹窗 `dismiss()` 导致闪退，严禁在二级页面返回时与进栈方向不一致。

### 8.2 触觉震动反馈分级规范（`AppHaptics.perform(view, level)`）

| 级别（`HapticLevel`） | 体感特征 | 唯一适用场景 |
| --- | --- | --- |
| **`LIGHT_TICK`** | 轻脆微震（`CLOCK_TICK`） | `ToggleSwitch` 切换、分段 Tab 切换、温度 `+/-` 步进、6 位 PIN 按键、滚轮拨动、日期胶囊、电量/油量 `% ↔ km` 切换 |
| **`MEDIUM_CONFIRM`** | 沉稳确认震（`VIRTUAL_KEY`） | 车控指令下发（锁车/解锁/空调/闪灯/尾箱/车窗/天窗）、3D 展台单击闪灯与双击展收、甲板跨阈值吸附、表单保存成功 |
| **`LONG_PRESS_MODE`** | 长按模式进入震（`LONG_PRESS`） | 长按车控按钮进入编辑模式 |
| **`ERROR_REJECT`** | 拒绝脉冲 + 抖动（`REJECT` + `shakeError`） | 6 位车控 PIN 校验失败、表单校验失败、指令执行失败 |
| **`NONE`** | **严禁震动** | 常规页面导航列表行、顶部返回箭头、列表滚动、收起菜单/抽屉（`attachPressPhysics` 默认 `haptic = false`） |

### 8.3 自动数据刷新生命周期

- **冷启动 / 前台恢复**：自动同步车辆列表（含 `is_tracks_open`）、实时车况、BLE 密钥状态、静默检查更新。
- **首页前台周期**：每 `30s` 静默刷新车况与定位（后台自动暂停）；BLE `5s/15s` 近场低功耗扫描。
- **进入子页即拉取**：地点行程（含逆地理编码）、消息提醒、OTA、围栏、驾驶员列表均自动加载。
- **控车后自动回验**：指令下发后乐观更新 UI，并在 `1.2–1.5s` 后回刷云端真实车况。

---

## 9. 二次确认与固定 6 位安全密码（`ConfirmDialog` & `SixDigitPinSheet`）

### 9.1 二次确认边界

| 规则 | 适用场景 |
| --- | --- |
| **必须二次确认** | 1. **不可逆/破坏性操作**（`dangerConfirm = true`）：退出登录、删除电子围栏、撤销远程授权、取消车辆实名认证、解除车辆绑定<br>2. **隐私/安全降级**：开启/关闭行程轨迹记录、关闭每次控车验证 PIN 码<br>3. **高风险不可中断**：触发车辆 OTA 升级 |
| **严禁二次确认** | 常规可逆车控指令（锁车/解锁/空调/闪灯/尾箱/车窗/天窗）、切换车辆、保存个人资料、页面跳转 |

### 9.2 固定 6 位安全密码规范（`SixDigitPinSheet`）

所有 6 位车控安全密码校验与修改**必须**使用 `SixDigitPinSheet`，禁止普通 `EditText`：

- **视觉结构**：顶部拖拽手柄 + 居中标题/副标题 + **6 枚独立圆角数字框**（`44dp × 52dp`，`10dp` 圆角，填充 `#1E2026`，聚焦框 `1.5dp #3D9BFF` 描边，非聚焦 `1dp #2C2F38`，输入后 `110ms` 弹入 `11dp` 纯白圆点）。
- **内置暗色 3×4 键盘**：`1..9`（`#24262E`，`12dp` 圆角，`52dp` 高）、`清空`、`0`、`BACK_ARROW` 退格键，每次点击触发 `LIGHT_TICK`。
- **自动提交与错误态**：满 6 位自动提交；失败时展示 `#F87171` 提示 + `MotionSpec.shakeError` + `ERROR_REJECT` 震动并清空重试；修改向导横向滑步切换（原 PIN $\rightarrow$ 新 PIN $\rightarrow$ 确认新 PIN）。

---

## 10. 核心页面信息架构与业务展示约束

### 10.1 首页视觉锚点与 5 行主菜单（5-Row Home IA）

- **左上角顺序**：中文车型名（可下拉切换）+ 右侧 `BLUETOOTH_ICON`（就绪 `#3D9BFF`，其余 `#8E9299`，`3s` 防抖）$\rightarrow$ 纯净能源条与百分比 $\rightarrow$ **唯一主状态**；右上角仅保留消息与设置；页底居中单行合并信息栏（默认车牌，单击循环切换总里程/剩余续航/剩余油量）。
- **主状态合并优先级**：`启动/行驶`（`已启动`/`已上电`）$>$ `引擎停止`（`已驻车`，不并列锁止）$>$ `驻车未知但锁止已知`（`已锁止`/`已解锁`）$>$ `均未知`（`状态待同步`）。严禁以锁止推导驻车或引擎状态。
- **5 行无框主菜单**：
  1. **`温度`**（`FAN_BLADE`）$\rightarrow$ 温度调节底部抽屉。
  2. **`地点`**（`LOCATION_ARROW`）$\rightarrow$ 地图（同时展示用户蓝点与车辆位置，右下角悬浮导航胶囊）+ 地址距离条 + 历史行程（空态用 `AppEmptyState`）。距离 `< 1000m` 用整数米（`距 320 米`），`≥ 1000m` 用一位小数公里（`距 1.2 公里`）；降级文案固定为 `车辆位置暂不可用` / `街道信息待获取` / `街道信息暂不可用` / `距离暂不可用`，严禁暴露经纬度。
  3. **`安全性`**（`SHIELD_CHECK` + `StatusDotView`）$\rightarrow$ 三分组：`控车安全`（PIN 码默认开启、蓝牙无感解锁/上锁 6 项前置条件校验、修改 PIN、忘记 PIN）、`车辆防护`（防盗提醒、电子围栏）、`隐私与轨迹`（行程轨迹记录）。
  4. **`驾驶员`**（`KEY_ROUND`）$\rightarrow$ 绑定当前车辆的驾驶员单入口卡片列表 + 吸底“＋ 添加驾驶员”。
  5. **`车辆信息`**（`CAR_CONTROLS` / `INFO_CIRCLE`）$\rightarrow$ 顶部车型+认证标签+右侧 `MORE_HORIZ` 危险操作菜单，下方无框分组（`基本信息`含`车辆版本`、`车辆状态`、`车辆标识`），右上角就地切换「编辑 / 保存」。
- **设置与关于边界**：`设置 → 账号与安全` 仅保留登录密码修改；`关于 Qiri+` 顶部暗色圆角品牌徽章（`#1E2026` + 细边框 + 纯白标识，禁止纯白方块直铺暗底），下方无框列表承接「软件更新」「构建版本」，已有新版本弹底部抽屉、已是最新仅弹 Toast。

### 10.2 一级车辆概要卡

- 每车仅一张概要卡，整卡为唯一入口；仅呈现车型、车牌（缺失为 `未设置车牌号`）、单一状态与右侧 `CHEVRON_RIGHT`，不堆叠 VIN、仿真车牌或次级按钮；按压 `0.98×` 缩放。

### 10.3 手机号与敏感字段脱敏规范（`SensitiveUiText`）

| 字段 | 统一调用接口与默认掩码规则 | 缺失占位 |
| --- | --- | --- |
| **手机号 / 账号手机** | `SensitiveUiText.maskPhone(raw)`：**前 3 后 2，隐藏中间 6 位**（标准 11 位：`13812345678` $\rightarrow$ `138******78`；含 `+86` 前缀保留为 `+86 138******78`；后端返回的旧式 4 星掩码自动重掩为 6 星；非标准长度 $\ge 6$ 保留前 3 后 2，长度 $= 5$ 保留前 2 后 2，长度 $\le 4$ 统一遮为 `••••`） | `""` / `—` |
| **混合账号名 / 昵称兜底** | `SensitiveUiText.maskIfPhone(raw)`：仅当形似手机号时应用 `maskPhone`，非手机号（如 `demo`、昵称）原样保留 | `""` / `—` |
| **诊断文本 / 运行日志** | `SensitiveUiText.maskPhonesInText(text)`：对展示、复制、导出的文本中内嵌手机号统一脱敏为 `138******78` | 原文 |
| 车架号 VIN、发动机号、ICCID、SN | `SensitiveUiText.identifier(raw)`：仅保留末四位 `••••` + 末四位（长度 $\le 4$ 全遮为 `••••`） | `—` |
| 车主姓名 | `SensitiveUiText.personName(raw)`：仅保留首字，其余遮蔽；单字姓名遮为 `•` | `—` |
| 购车时间 | `SensitiveUiText.year(raw)`：仅保留四位年份 | `—` |

- **严格禁令**：全应用（首页、设置、个人资料、驾驶员/远程授权、车辆信息、调试诊断、复制与导出日志）严禁出现未脱敏手机号或旧式 `3+4+4` 四星掩码；脱敏同步覆盖可见文本、`contentDescription` 与剪贴板（复制时提示“已脱敏”）；仅在用户主动进入编辑/短信验证表单输入时允许编辑明文。
- 登录密码字段统一关闭联想建议并应用 `PasswordTransformationMethod`，确保所有输入与自动填充均以圆点呈现。

### 10.4 系统手动蓝牙配对原则

1. **用户发起、系统承载**：手动配对由用户在 Android 系统蓝牙设置发起确认，应用不绕过系统自动接受绑定。
2. **状态如实反馈**：仅呈现 `未配对 / 配对中 / 已连接 / 失败或超时`，不与锁止或驻车态混淆。
3. **凭据零展示**：界面、无障碍、剪贴板、日志均不得展示或推导配对验证码。
4. **失败可恢复**：取消/超时仅更新提示，不执行破坏性重置。

### 10.5 演示模式规范（Demo Mode · `demo` / `demo`）

- **登录入口**：登录页支持账号 `demo`、密码 `demo`（忽略大小写与首尾空白，自动跳过 11 位手机号格式校验），并在“忘记密码”下方提供克制的单行快捷提示 `“演示账号：demo / demo（点击填入）”`，轻触自动切至密码登录并预填凭据，不增加页面杂乱度。
- **动态全车型覆盖（N 台演示车）**：演示车库由 `VehicleModelRepository.listSupportedModels(context)` 与 `DemoVehicleBackend` 动态构建，**覆盖应用当前支持的全部 N 款 3D 车型**（随 `manifest.json` 车型扩展自动增长），每台演示车具备独立车型名、演示车牌（`皖B·D0001..D000N`）、17 位演示 VIN 及匹配动力形态（燃油 / 插混 / 纯电）的完整遥测数据，左上角车型切换器可平滑切换全部 N 台车模。
- **全功能离线可交互**：通过 `CheryHttpClient` 演示处理器钩子覆盖车控（锁车/解锁、空调/温控、闪灯、尾箱、启停、车窗、天窗及 3D 联动）、地点与行程轨迹、电子围栏、驾驶员（手机号严格 `138******78` 脱敏）、消息、OTA、设置与调试诊断，全程零网络/蓝牙报错弹窗。
- **强账号隔离**：`VehicleProfileStore` 以纯内存演示覆盖层隔离演示会话，绝不覆写、污染或清空本机已持久化的真实用户账号、Token、离线蓝牙密钥与设置；退出演示模式即刻还原真实环境。

### 10.6 调试诊断界面规范（`DebugDiagnosticsScreen.kt`）

- **言简意赅与视觉秩序**：杜绝长段解释文本与混乱堆叠；全部操作按钮文案控制在 `2–6` 字，数值字段启用 `tnum` 等宽数字，零 Emoji。
- **三段式信息层级**：
  1. **顶部实时状态概览卡（`18dp` 圆角，`12dp` 内边距）**：单行抬头展示车型、车牌胶囊、车库台数徽标（`N台`）、模式胶囊（`正式` 绿 / `演示` 琥珀）与可点刷新开关（`实时` / `已暂停`）；中段 5 格语义状态条（`云端` / `蓝牙` / `车辆` / `3D` / `无感`）；底栏脱敏身份摘要（`账号 138******78 · VIN ••••XXXX · vX.Y.Z`）。
  2. **四组分类操作网格（每组 3 列等宽、`42dp` 高、`10dp` 同心内圆角，共 24 项动作）**：`状态与同步`、`蓝牙诊断`、`控车测试`（复用统一单色车控矢量图标 + `MEDIUM_CONFIRM` 触觉）、`会话与数据`（含校验 Token、同步配置、复制诊断、导出脱敏日志、清空日志、暂停/恢复刷新）。
  3. **底部检视与日志面板（`状态快照` | `蓝牙TLV` | `运行日志`）**：采用 `MotionSpec.createSlidingSegmentedBar` + `transitionContainer` 水平共享轴切换、原地差量刷新（告别定时 `removeAllViews()` 闪烁与丢滚动位置）、折叠快照卡（`animateExpandCollapse`）、6 阶段 GATT 握手胶囊条与日志过滤胶囊（`animatePanelSwitch`），全部展示/复制/导出内容强制经 `SensitiveUiText.maskPhonesInText` 脱敏且不暴露配对 PIN。
