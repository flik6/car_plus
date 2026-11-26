# CarPlus 统一组件库与多层级导航规范（Design System v2.3）

> 适用范围：CarPlus Android 客户端全量界面、Pictogrammers MDI Automotive 图标体系、共享组件套件、四类交互容器、多层级导航栈、触觉分级、二次确认规则、6 位安全密码组件与 5 行首页信息架构。  
> 审美基准：Dark Automotive / Porsche / iOS Dark Mode — **极简暗色汽车美学（Minimalist Automotive Dark）**。

---

## 1. 核心设计哲学

1. **极简克制**：以车辆本体与核心状态为主角，彻底杜绝多余线框、嵌套方框与装饰性元素。
2. **零 Emoji / 零混用图标**：全应用严禁在用户可见文案或控件中出现任何 Emoji（如 `☁️`、`🔵`、`🌐`、`📍`、`✓` 等），所有图标严格统一为官方 **Pictogrammers Material Design Icons (MDI) Automotive** 矢量体系（`@mdi/js`）。
3. **零内部协议编号**：严禁向用户暴露任何内部接口编号或技术代号（如 `(107)`、`(121)`、`(145)`、`(148)`、`(152)`、`(901)`、`(942)`、`(950)`、`(961)`、`op=30` 等）。
4. **零冗余手动刷新**：依托自动刷新生命周期维持数据新鲜度，全面取消常规“刷新”按钮（仅在网络报错态提供单次“重试”）。
5. **全链路无缝暗色画布**：登录态所有页面（首页、设置、安全、地点、消息及三级子页）及系统状态栏/导航栏严格统一为 **`#161719`**，消除任何顶部色差接缝。

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
2. **`地点`**（`LOCATION_ARROW`）：副标题实时展示 **人车距离 + 逆地理街道地址**（如 `距您 320m · 西湖区古墩路`，`<30m` 显示 `就在附近`） → 全屏 `Screen.LOCATION`。
3. **`安全性`**（`SHIELD_CHECK`）→ 全屏 `Screen.SECURITY`（`AppTopBar("安全性")`）：
   - `车控安全密码`（`ToggleSwitch` 直接开关；关闭需二次确认，开启即时 `LIGHT_TICK`）
   - `修改车控安全密码`（6 位分步密码向导）
   - `防盗与车辆体检` / `电子围栏`（含可视化地图围栏编辑）
4. **`驾驶员`**（`KEY_ROUND`，副标题含 BLE 手机钥匙实时状态与 `StatusDotView`）→ 全屏 **`远程授权管理`**（`AppTopBar("远程授权管理")`）：
   - 顶部座驾与授权统计概览卡 + 远程授权列表卡片（启停开关、编辑信息、操作记录、撤销授权）+ 吸底「＋ 新增远程授权」；仅提供官方远程授权一种体系。
5. **`车辆信息`**（`CAR_CONTROLS` / `INFO_CIRCLE`）→ 全屏 `Screen.VEHICLE_INFO`（`AppTopBar("车辆信息")`）：
   - 分组 `基本信息`：车型名称、车牌号、车辆识别码（VIN）、总里程、四轮胎压（`%.2f` bar）、蓄电池电压、蓝牙钥匙名称；顶栏「编辑」与下方「车辆管理」直达空间连续的一体化「座驾全息档案卡片流」；
   - 分组 `车辆管理与系统`：`车辆管理`、`车辆升级 (OTA)`。

> **设置页精简边界**：右上角 `设置 → 账号与安全` 仅保留**登录密码**修改，不再重复放置车控安全密码（PIN）入口。
