# CarPlus 统一组件库与多层级导航规范（Design System v2.6）

> 适用范围：CarPlus Android 客户端全量界面、Pictogrammers MDI Automotive 图标体系、共享组件套件、四类交互容器、多层级导航栈、触觉分级、二次确认规则、6 位安全密码组件、5 行首页信息架构、车辆与驾驶员管理、地点信息、敏感字段脱敏及系统手动配对原则。
> 审美基准：Dark Automotive / Porsche / iOS Dark Mode — **极简暗色汽车美学（Minimalist Automotive Dark）**。视觉编排另参考 [Tesla Support — Tesla App](https://www.tesla.com/support/tesla-app?redirect=no) 与 [App Store — Tesla](https://apps.apple.com/us/app/tesla/id582007913) 的官方公开截图：深色中性背景、车型与简短状态在上、车辆为主视觉、少数快捷入口横排、纵向单行功能入口、克制留白与少量强调色。
> 资产边界：上述页面仅用于核对信息层级、留白和入口密度；CarPlus 保留自有 3D GLB 车模、CHERY 标识、启动图标、MDI Automotive 图标与暗色组件实现，不复制、不替换为第三方品牌资产。

---

## 1. 核心设计哲学

1. **极简克制**：以车辆本体与核心状态为主角，彻底杜绝多余线框、嵌套方框与装饰性元素。
2. **零 Emoji / 零混用图标**：全应用严禁在用户可见文案或控件中出现任何 Emoji（如 `☁️`、`🔵`、`🌐`、`📍`、`✓` 等），所有图标严格统一为官方 **Pictogrammers Material Design Icons (MDI) Automotive** 矢量体系（`@mdi/js`）。
3. **零内部协议编号**：严禁向用户暴露任何内部接口编号或技术代号（如 `(107)`、`(121)`、`(145)`、`(148)`、`(152)`、`(901)`、`(942)`、`(950)`、`(961)`、`op=30` 等）。
4. **零冗余手动刷新**：依托自动刷新生命周期维持数据新鲜度，全面取消常规“刷新”按钮（仅在网络报错态提供单次“重试”）。
5. **全链路无缝暗色画布**：登录态所有页面（首页、设置、安全、地点、消息及三级子页）及系统状态栏/导航栏严格统一为 **`#161719`**，消除任何顶部色差接缝。
6. **车辆优先、信息克制**：首页先呈现车辆和少量真实状态，再呈现功能入口；一级列表每项只保留一个明确入口，避免把同一对象的多个动作拆成密集按钮。
7. **隐私默认收束**：车辆身份、车主资料和登录凭据默认脱敏；只有用户在明确打开的既有编辑流程中才接触可编辑明文，复制与无障碍朗读不得绕过脱敏。
8. **诚实降级**：没有车辆定位、用户定位、街道反查或业务字段时显示明确占位，不以经纬度、推测地址或“已驻车”等推测状态冒充真实结果。

---

## 2. 强制图标规范（Pictogrammers MDI Automotive Spec）

| 规范项 | 强制标准 |
| --- | --- |
| **图标库** | **Pictogrammers Material Design Icons (`@mdi/js`)** — Automotive 分类及配套 `-outline` 图标 |
| **画布网格** | `24 × 24` 标准 `viewBox` |
| **绘制模式** | **`Paint.Style.FILL`**（MDI 官方 SVG Path 为闭合填充轮廓，严禁叠加 `STROKE`） |
| **默认颜色** | `#FFFFFF`（纯白，高对比单色） |
| **激活颜色** | **`#3D9BFF`**（汽车激活蓝） |
| **格式** | SVG PathData 矢量绘制（零位图模糊） |
| **禁用规则** | **严禁混用其他图标库或任何 Emoji 字符** |

### 2.1 已核验的 38 项 `AppIconType` → `@mdi/js` 官方映射表

| 分组 | `AppIconType` → 官方 `@mdi/js` 导出名 |
| --- | --- |
| **导航与顶栏** | `CHEVRON_DOWN` → `mdiChevronDown`、`CHEVRON_RIGHT` → `mdiChevronRight`、`BACK_ARROW` → `mdiArrowLeft`、`MESSAGE_BUBBLE` → `mdiMessageTextOutline`、`MENU_HAMBURGER` → `mdiMenu` |
| **核心车控** | `LOCK_CLOSED` → `mdiLockOutline`、`LOCK_OPEN` → `mdiLockOpenVariantOutline`、`FAN_BLADE` → `mdiFan`、`BOLT_FLASH`（闪灯寻车）→ `mdiCarLightHigh`、`TRUNK_OPEN` → `mdiCarHatchback`、`TAILGATE_CLOSE` → `mdiCarBack`、`ENGINE_POWER` → `mdiEngineOutline`、`ENGINE_STOP` → `mdiEngineOffOutline`、`HORN_SOUND` → `mdiBugle` |
| **车窗与天窗** | `WINDOW_OPEN` → `mdiWindowOpenVariant`、`WINDOW_VENT` → `mdiCarDoor`、`WINDOW_CLOSE` → `mdiWindowClosedVariant`、`SUNROOF_OPEN` → `mdiCarConvertible`、`SUNROOF_TILT` → `mdiSunAngleOutline`、`SUNROOF_CLOSE` → `mdiCarSide` |
| **主菜单与系统** | `CAR_CONTROLS` → `mdiCarOutline`、`LOCATION_ARROW` → `mdiMapMarkerOutline`、`SHIELD_CHECK` → `mdiShieldCheckOutline`、`KEY_ROUND`（驾驶员）→ `mdiCarKey`、`INFO_CIRCLE` → `mdiInformationOutline`、`BLUETOOTH_ICON` → `mdiBluetooth`、`CLOUD_NET_ICON` → `mdiCloudOutline`、`RADAR` → `mdiRadar`、`CROSSHAIR` → `mdiCrosshairsGps`、`MAP_PIN` → `mdiMapMarkerOutline` |
| **通用操作** | `CALENDAR` → `mdiCalendarOutline`、`CLOCK` → `mdiClockOutline`、`CHECK` → `mdiCheck`、`CHECK_CIRCLE` → `mdiCheckCircleOutline`、`SWAP_CAR` → `mdiSwapHorizontal`、`ADD_ICON` → `mdiPlus`、`CLOSE_ICON` → `mdiClose`、`LOG_OUT` → `mdiLogout` |

---

## 3. 色彩与字阶 Token（`ThemeTokens`）

| Token | 色值 | 用途 |
| --- | --- | --- |
| `BG_CANVAS` | `#161719` | 全应用主画布背景 + 状态栏 + 导航栏（登录态零色差） |
| `BG_SURFACE` | `#1C1D21` | 底部物理抽屉、居中确认弹窗表面 |
| `BG_POPOVER` | `#202228` | 锚点悬浮下拉菜单表面 |
| `BG_CONTROL` / `BTN_SECONDARY` | `#26282E` / `#2A2C32` | 次级按钮、分段栏轨道、输入底色 |
| `DIVIDER` | `#23252B` | `0.6dp` 极细分割线 |
| `TEXT_PRIMARY` | `#FFFFFF` | 主标题、列表主文案、默认图标 |
| `TEXT_BODY` | `#D1D1D6` | 正文与说明 |
| `TEXT_SECONDARY` | `#8E9299` | 副标题、分组眉标、时间戳 |
| `ACCENT_ACTIVE` / `ACCENT_LIGHT` | **`#3D9BFF`** | 激活图标、选中态、地图围栏/轨迹高亮 |
| `ACCENT_BLUE` | `#3E6AE1` | 主 CTA 按钮底色 |
| `STATUS_GREEN` | `#34D399` | BLE 直连、正常状态、行程起点 |
| `STATUS_AMBER` | `#FBBF24` | BLE 发现/握手连接中 |
| `ERROR_RED` | `#F87171` | 危险操作、低油量告警、行程终点 |

---

## 4. 完整统一组件库（`DesignComponents.kt`）

`MainActivity`、`SettingsScreen` 与 `VehicleFeatureSheets` 必须统一复用以下组件，严禁各自拼装不一致的顶栏、列表行或表单：

1. **`AppTopBar`（`AppComponents.createTopBar`）**：
   - 固定高度 **`56dp`**，底色 `BG_CANVAS (#161719)`，左右统一 `16dp` 内边距；
   - 左侧 `40dp × 40dp` 返回热区 + 居中 `22dp` Lucide `arrow-left`（`BACK_ARROW`）；
   - 标题 `18sp` Bold `#FFFFFF`；可选右侧操作文案（如“保存”）或自定义 View。
   - **所有全屏二级/三级页面必须 100% 复用 `AppTopBar`**，确保返回箭头位置与高度像素级一致。
2. **`AppListRow`（`AppComponents.createListRow`）**：无边框 `#161719` 列表行（`minHeight 60dp`、`22dp` 左 Lucide 图标、`16sp` 主标题、可选 `13sp` 副标题 / 右侧文本 / `ToggleSwitch`、`16dp` `chevron-right`）。
3. **`AppSectionHeader`（`AppComponents.createSectionHeader`）**：`12sp` Bold `#8E9299` 分组眉标（`letterSpacing 0.04f`）。
4. **`ToggleSwitch`**：统一平滑胶囊开关（激活色 `#3D9BFF`）。
5. **`UnderlineInput`（`AppComponents.createUnderlineInput`）**：无方框极简底线输入框（底部 `1dp` 细线，聚焦高亮 `#3D9BFF`）。
6. **`DateTimePickerField`（`AppComponents.createDateTimePickerField`）**：无边框底线行 + 尾随 `18dp` Lucide `calendar`/`clock` 图标；点击唤起暗色原生 `DatePickerDialog` / `TimePickerDialog`（支持 `DATE_TIME` `yyyy-MM-dd HH:mm:ss`、`DATE` `yyyy-MM-dd`、`TIME` `HH:mm`），彻底消灭手动输入日期时间。
7. **`AnchoredDropdown`（`AppComponents.showAnchoredDropdown`）**：紧贴触发锚点展开，`scale 0.92 -> 1.0`、`180ms EASE_OUT_EXPO`。
8. **`ControlIconButton`（`AppComponents.createControlIconButton`）**：无边框 `28dp` 车控矢量图标按钮（常态无框、编辑态浮现选中标记与简短标签）。
9. **`AppActionButton`（`AppComponents.createActionButton`）**：统一 `PRIMARY` / `SECONDARY` / `DANGER_TEXT` 三态按钮。
10. **`ConfirmDialog`（`AppComponents.showConfirmDialog`）**：居中缩放 `0.94 -> 1.0`、`220ms`，最多 2 个操作按钮。
11. **`AppBottomSheet`**：底部上滑 `260ms`、下滑退出 `170ms`、顶部 `38×4dp` 拖拽手柄、支持下拉关闭、**无“关闭”文字按钮**。
12. **`AppEmptyState`（`AppComponents.createEmptyState`）**：居中 Lucide 图标 + 柔和说明文案的极简空状态占位。

---

## 5. 四类交互容器选型与多层级导航栈规范

### 5.1 四类容器唯一白名单

| 容器类型 | 适用场景 |
| --- | --- |
| **1. 全屏二级 / 三级页面** | 设置、个人资料、关于 CarPlus、安全性和驾驶员、地点与历史行程、消息提醒、电子围栏地图可视化编辑页 |
| **2. 锚点下拉菜单** | 左上角车名切换车辆、个人资料性别选择（男 / 女 / 保密） |
| **3. 底部物理抽屉** | 温度调节、车辆升级（OTA）、车辆详细指标（5 击页脚）、防盗与体检、电子围栏列表 |
| **4. 居中确认弹窗** | 行程轨迹开关确认、退出登录确认、修改登录密码、车控 PIN 管理、隐私协议 |

### 5.2 多层级导航栈（Multi-Level Navigation Stack）

- **结构**：`Home（持久常驻，3D 场景永不销毁重建） -> Level 2 Overlay -> Level 3 Overlay`（如 `首页 -> 安全性和驾驶员 -> 电子围栏地图编辑页`、`首页 -> 设置 -> 个人资料 / 关于 CarPlus`）。
- **栈管理**：`MainActivity` 维护 `subpageOverlayStack`（有序覆盖层栈），入栈统一执行右滑入场（`+28dp -> 0`，`240ms EASE_OUT_EXPO`），系统返回/点击 `AppTopBar` 返回按钮逐级右滑出栈并执行对应的销毁回调（如释放地图 WebView）。
- **状态栏一致性**：`themes.xml` 与 `configureSystemBars` 全程锁定 `#161719`，进出任意层级均无顶部色条闪烁或色差接缝。

---

## 6. 动效节奏与自动数据刷新生命周期

- **动效曲线**：`EASE_OUT_EXPO (0.22, 1, 0.36, 1)`、`EASE_STANDARD (0.2, 0, 0, 1)`、`EASE_SPRING_RELEASE (0.22, 1.12, 0.36, 1)`；按压 `90ms/160ms`，下拉 `180ms`，页面/抽屉 `240–260ms`，地图轨迹回放 `1200ms`。
- **自动刷新矩阵**：
  - **冷启动 / 前台恢复**：自动同步车辆列表（含 `is_tracks_open`）、实时车况、BLE 密钥状态、静默检查 App 更新；
  - **首页前台周期**：每 `30s` 静默刷新车况与定位（后台自动暂停）；BLE `5s/15s` 低功耗近场扫描；
  - **进入子页即拉取**：地点行程（含逆地理编码）、消息提醒、OTA、防盗体检、围栏、授权列表均自动加载；
  - **控车后自动回验**：指令下发后乐观更新 UI 并在 `1.5s` 后自动回刷云端真实车况。

---

## 7. 触觉震动反馈分级规范（`AppHaptics`）

触觉反馈遵循“克制、有语义、绝不滥用”原则，全应用统一调用 `AppHaptics.perform(view, level)`：

| 级别（`HapticLevel`） | 体感特征 | 唯一适用场景 |
| --- | --- | --- |
| **`LIGHT_TICK`** | 轻脆微震（`CLOCK_TICK`） | `ToggleSwitch` 开关切换、分段栏 Tab 切换、温度 `+/-` 步进、6 位 PIN 每输入 1 位数字、日期胶囊点选、左上角电量/油量 `% ↔ km` 切换 |
| **`MEDIUM_CONFIRM`** | 沉稳确认震（`VIRTUAL_KEY` / `CONFIRM`） | 车控指令下发（锁车/解锁/空调/闪灯/后备箱/车窗/天窗）、3D 展台单击闪灯与双击展开/收起、控制甲板跨阈值吸附展开/收起、表单保存成功 |
| **`LONG_PRESS_MODE`** | 长按模式进入震（`LONG_PRESS`） | 长按展开控车图标进入编辑模式 |
| **`ERROR_REJECT`** | 拒绝脉冲 + 水平抖动（`REJECT` + `shakeError`） | 6 位车控安全密码校验失败、表单校验不通过、指令执行失败 |
| **`NONE`** | **严禁震动** | 普通页面导航列表行、顶部返回箭头、列表滚动、收起菜单/抽屉——保持极简克制，`attachPressPhysics` 默认 `haptic = false` |

---

## 8. 二次弹窗确认规范（`ConfirmDialog`）

| 规则 | 适用场景 |
| --- | --- |
| **必须二次确认**（`AppComponents.showConfirmDialog`） | 1. **破坏性/不可逆操作**：`退出登录`、`删除电子围栏`、`撤销远程授权`、`取消车辆实名认证`、`解除车辆绑定`（使用 `dangerConfirm = true`）<br>2. **隐私或安全门禁降级**：`开启/关闭行程轨迹记录`、`关闭每次控车验证密码`<br>3. **高风险不可中断车控**：`触发车辆 OTA 升级`（提示驻车、电量充足、过程中勿操作车辆） |
| **严禁二次确认**（即时响应） | 常规可逆车控指令（锁车/解锁、空调、闪灯、后备箱、车窗、天窗）、切换车辆、保存个人资料、切换页面 |

---

## 9. 固定 6 位车控安全密码规范（`SixDigitPinSheet`）

所有涉及 6 位车控安全密码的校验与修改**必须**使用统一 `SixDigitPinSheet`，禁止使用普通 `EditText` 输入框：

- **视觉结构**：顶部拖拽手柄（支持下拉关闭）+ 标题与引导副标题 + **6 枚独立圆角数字框**（`44dp × 52dp`，填充 `#1E2026`，当前焦点框 `1.5dp #3D9BFF` 边框，输入数字后以 `110ms` 缩放弹入纯白圆点 `●`）。
- **内置暗色 3×4 数字键盘**：`1..9`、`清空`、`0`、Lucide 退格键；每次按键触发 `LIGHT_TICK` 触觉反馈。
- **自动提交与错误态**：输满第 6 位立即自动提交校验，无需额外点击确认；校验失败时在输入框下方展示 `#F87171` 红色提示，同时触发 `360ms` 水平阻尼抖动（`MotionSpec.shakeError`）+ `ERROR_REJECT` 震动并自动清空重试。
- **分步修改向导（`showChangePinWizardSheet`）**：横向平滑切步：`[步骤 1：输入原 6 位密码]`（若已设置）→ `步骤 2：输入新 6 位密码` → `步骤 3：再次确认新密码`（不一致则抖动重置步骤 3，一致则自动提交）。

---

## 10. 首页 5 行信息架构（5-Row Home IA）

首页主菜单严格统一为 **5 行无边框 Lucide `AppListRow`**，并解耦“安全策略”“驾驶员授权”与“车辆信息”：

1. **`温度`**（`FAN_BLADE`）：空调状态副标题 → 温度调节底部抽屉。
2. **`地点`**（`LOCATION_ARROW`）：副标题展示 **近似街道名 + 用户到车的直线距离**（如 `直线距离 320 米` 或 `直线距离 1.2 公里`），进入全屏 `Screen.LOCATION`。无用户定位显示 `距离暂不可用`。街道反查期间显示 `街道信息待获取`，失败显示 `街道信息暂不可用`。无车辆定位显示 `车辆位置暂不可用`。
3. **`安全性`**（`SHIELD_CHECK`）→ 全屏 `Screen.SECURITY`（`AppTopBar("安全性")`）：
   - `车控安全密码`（`ToggleSwitch` 直接开关；关闭需二次确认，开启即时 `LIGHT_TICK`）
   - `修改车控安全密码`（6 位分步密码向导）
   - `防盗与车辆体检` / `电子围栏`（含可视化地图围栏编辑）
4. **`驾驶员`**（`KEY_ROUND`，副标题含 BLE 手机钥匙实时状态与 `StatusDotView`）→ 全屏 **`驾驶员`**（`AppTopBar("驾驶员")`）：
   - 页面直接进入驾驶员列表。列表跟随当前车辆，查看其他车辆的驾驶员前先切换车辆。
   - 一名驾驶员一张单一入口卡片；详情内集中呈现启停、编辑、操作记录及默认收起的危险操作。吸底「＋ 添加驾驶员」。
5. **`车辆信息`**（`CAR_CONTROLS` / `INFO_CIRCLE`）→ 全屏 `Screen.VEHICLE_INFO`（`AppTopBar("车辆信息")`）：
   - 分组 `基本信息`：车型名称、车牌号、总里程、四轮胎压（`%.2f` bar）、蓄电池电压、蓝牙钥匙名称。
   - 分组 `车辆资料`：车架号、发动机号、ICCID、SN、车主姓名、购车时间；仅使用既有 UI 模型缓存展示，缺失显示 `—`，默认按 §13 脱敏，不新增字段查询或服务查询。
   - 顶栏「编辑」保留既有用户主动打开的编辑流程；下方「车辆管理」进入一车一卡列表；
   - 分组 `车辆管理与系统`：`车辆管理`、`车辆升级 (OTA)`。

> **设置页精简边界**：右上角 `设置 → 账号与安全` 仅保留**登录密码**修改，不再重复放置车控安全密码（PIN）入口。

---

## 11. 车辆、驾驶员与首页状态层级

### 11.1 首页视觉锚点

- 左上角常驻**同一枚蓝牙图标**，位于车辆标题左侧；尺寸 `16dp`，展示容器 `28dp`，只读、不点击、不改变连接或导航状态。
- 图标颜色复用界面已经消费的当前通道状态：蓝牙会话就绪时使用 `ACCENT_LIGHT (#3D9BFF)`；正在连接或当前使用远程网络时保持 `TEXT_SECONDARY (#8E9299)`。不得再以云图标表达“非蓝牙”，也不得因条件分支更换图标形状。
- 首页左侧信息顺序固定为：蓝牙状态图标 → 车型（可下拉切换）→ 能源/车牌 → **唯一主状态**。右侧仅保留消息与设置。

### 11.2 主状态合并规则

| 已知状态 | 首位展示 | 说明 |
| --- | --- | --- |
| 引擎/行驶状态为启动 | `已启动` | 真实行驶相关状态优先，不并列追加锁止 |
| 引擎/行驶状态为停止 | `已驻车` | 真实驻车优先；即使同时已锁止，也只显示 `已驻车` |
| 驻车状态未知、锁止已知 | `已锁止` / `已解锁` | 锁止只能作为未知驻车状态下的后备信息 |
| 均未知 | `状态待同步` | 不推测、不默认成已驻车或已驻车 + 已锁止 |

禁止把“已锁止”与“已驻车”视为同义，也禁止用锁止状态推导引擎或驻车状态。

### 11.3 车辆管理（一级）

- 每车仅一张概要卡，整卡为**唯一入口**；不在一级卡上放第二组可点击操作。
- 内容只保留必要性最高的三项：车型、车牌（缺失为 `未设置车牌号`）、一个状态（当前控车/默认车辆/认证状态/被授权/已绑定）。
- 移除 VIN、CHERY/CarPlus 装饰徽标、仿真车牌铭牌、状态圆点、额外说明行和“查看详情”文字；只保留右侧 `CHEVRON_RIGHT` 作为层级提示。
- 卡片最小高度 `92dp`，内边距 `18dp / 15dp`，卡间距 `12dp`；点击时整卡 `0.98` 缩放反馈。

### 11.4 车辆详情（二级）

- 顶部身份卡只呈现车型、认证/授权状态、默认车辆状态、车牌及既有操作入口（设为默认、切换为当前控车、驾驶员）。
- 「驾驶员」入口只对**当前控车车辆**展示，避免在非当前车辆详情中产生跨车辆驾驶员列表歧义。
- `车辆资料` 使用单列标签—值行：车架号、发动机号、ICCID、SN；`车主资料` 使用单列标签—值行：车主姓名、购车时间。缺失统一为 `—`。
- “编辑车辆资料”默认收起；标题行最小触区 `48dp`，点击后展开既有车牌/车主/购车时间编辑，展开状态以箭头旋转与 `LIGHT_TICK` 反馈。用户未主动展开时不显示可编辑明文。
- 危险操作继续默认收起并保留二次确认，不在本轮改变其业务动作。

### 11.5 驾驶员（一级/二级）

- 顶栏下直接显示驾驶员列表，省去车型、车牌、VIN、统计摘要和车辆详情导航。
- 驾驶员列表绑定当前选择车辆；只有切换车辆后才重新加载该车列表。不得从其他车辆卡片直接把驾驶员列表切到另一 VIN。
- 一级仍为一驾驶员一张单一入口卡片；详情与危险操作的结构沿用现有规范。

### 11.6 触区与留白

| 对象 | 最小尺寸/间距 |
| --- | --- |
| 可点击图标与按钮 | `48dp × 48dp` 触区；视觉图标可小于触区 |
| 标签—值行 | 高度 `48dp`，左右内边距由父卡片 `16–18dp` 控制 |
| 一级车辆/驾驶员卡 | 高度 `92dp` 起，卡间距 `12dp` |
| 页面外边距 | 二级页左右 `16dp`，段落分组间距 `12–18dp` |
| 纯展示顶栏通道图标 | `28dp` 容器、`16dp`  glyph；不可误加点击热区 |

---

## 12. 地点、街道与距离

- 摘要显示近似街道名和用户到车的直线距离。小于 1000 米用整数米，如「直线距离 320 米」。从 1000 米起用一位小数公里，如「直线距离 1.2 公里」。
- 地图、车辆位置与用户定位继续复用既有授权数据和既有地图资源；不新增定位采集、第三方地理服务或上传链路。
- 使用当前车辆位置对应的现有地理编码结果。结果返回后刷新地点摘要。
- 降级文案固定：

| 缺失条件 | 文案 |
| --- | --- |
| 无车辆定位 | `车辆位置暂不可用` |
| 有车辆定位、街道解析中 | `街道信息待获取` |
| 街道解析失败或空 | `街道信息暂不可用` |
| 无用户定位/无最近位置 | `距离暂不可用` |

- 任何地点相关页面、定位详情或行程详情都不得把经纬度作为可见回退值；地图内部坐标仅用于渲染，不进入用户可见文本、无障碍文本或剪贴板。
- 保留既有地图、实时位置按钮、导航入口与历史行程；无坐标时导航提示“暂无可导航的车辆位置”。

---

## 13. 敏感资料脱敏与密码输入

### 13.1 固定掩码

| 字段 | 展示规则 | 缺失 |
| --- | --- | --- |
| 车架号 VIN、发动机号、ICCID、SN | 只保留末四位：`••••` + 末四位 | `—` |
| 短标识（长度 ≤ 4） | 全遮蔽为 `••••` | `—` |
| 车主姓名 | 只保留首字，其余遮蔽；单字姓名全遮蔽为 `•` | `—` |
| 购车时间 | 只保留年份（四位数字） | `—` |

- 相同掩码同时用于「车辆信息」「车辆详情」「车辆切换浮层」及其他可见身份摘要；缺失字段不得显示空白长串或协议占位符。
- 「车辆信息」复用登录和首页同步时已取得的车辆资料。资料在当前会话内按车辆匹配，同步结束后刷新已打开页面的资料分组。
- 默认视图、无障碍文本、`contentDescription`、剪贴板副本均只能取得脱敏值。复制入口如保留，必须复制脱敏值并明确提示“已脱敏”；不得提供隐藏的“查看明文”动作。
- 可编辑明文只在用户主动展开既有编辑区或主动进入既有编辑流程后出现；不新增字段加载、真实数据校验或服务查询。

### 13.2 登录密码字段

- 字段使用 Android 文本密码类型并关闭联想建议；统一应用 `PasswordTransformationMethod`，保证新输入、程序化预填和系统密码管理器预填均以圆点呈现。
- 登录页沿用原有系统自动填充配置。
- 设计、代码、日志和交付记录不得写入任何真实登录口令；界面测试只使用虚构值。

---

## 14. 系统手动配对交互原则（本轮仅设计记录）

1. **用户发起、系统承载**：手动配对应由用户在 Android 系统蓝牙设置或系统配对界面明确发起并确认；CarPlus 不代替系统自动接受绑定。
2. **状态如实反馈**：应用只呈现“未配对 / 配对中 / 已连接 / 失败或超时”等既有状态，不把连接态、锁止态或驻车态混为配对结论。
3. **凭据零展示**：产品界面、无障碍文本、剪贴板、日志和截图流程均不得获取、生成、复制、推导或展示配对验证码；不提供“查看验证码”入口。
4. **失败可恢复**：取消、超时或失败只更新提示，不进行破坏性重置；重新配对仍由用户通过系统入口发起。
5. **最小暴露**：不新增配对数据采集、持久化字段、上传接口或旁路观测；配对期间的设备与车辆信息保持脱敏。

**本轮实际实现边界**：仅更新 UI 与设计/工作文档；未进入、修改或验证任何配对、验证码、BLE、数字钥匙或服务协议实现，也未连接真实设备读取或展示配对材料。上述内容作为后续交互评审的约束，不构成本轮功能变更。

---

## 15. 本轮交付与验收边界

- 本轮仅涉及 `car_plus/app/src/main/java/com/chery/carplus/ui/` 下的 UI 组合、展示状态与设计/工作文档；不构建或发布 APK，不安装应用，不提交或推送。
- 验收以离线 Kotlin 编译、`git diff --check`、UI 模型/虚构数据走查为准；未连接真实账号、车辆或设备，不执行任何业务或实车指令。
- 视觉参考页仅用于公开信息层级核对；CarPlus 自有 3D、Logo、图标与颜色 Token 保持不变。
