package com.chery.carplus.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.chery.carplus.ble.BlePairingPinCalculator
import com.chery.carplus.ble.CheryBleController
import com.chery.carplus.ble.PairingPinCandidates
import com.chery.carplus.ble.ParsedTBoxResponse
import com.chery.carplus.ble.SessionSnapshot
import com.chery.carplus.ble.TBoxTlvBuilder
import com.chery.carplus.cloud.CheryDeviceBindingRequiredException
import com.chery.carplus.cloud.CheryHttpClient
import com.chery.carplus.cloud.CheryUserInfo
import com.chery.carplus.cloud.InGeekCloudProvisioner
import com.chery.carplus.cloud.RemoteCommand
import com.chery.carplus.cloud.VehicleSummary
import com.chery.carplus.cloud.VehicleTelemetry
import com.chery.carplus.profile.VckInfoParser
import com.chery.carplus.profile.VehicleProfile
import com.chery.carplus.profile.VehicleProfileStore
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Authorized-evaluation console for the Chery HTTP remote-control and BLE digital-key flows.
 *
 * All layouts are created programmatically so that no XML resource inflation can
 * prevent the diagnostic console from starting after an APK rebuild.
 */
class MainActivity : AppCompatActivity(), CheryBleController.Listener {

    private lateinit var profileStore: VehicleProfileStore
    private lateinit var httpClient: CheryHttpClient
    private lateinit var provisioner: InGeekCloudProvisioner
    private lateinit var bleController: CheryBleController

    private lateinit var contentFrame: FrameLayout
    private val tabButtons = mutableListOf<Button>()
    private val tabPages = mutableListOf<View>()

    private lateinit var accountMobileInput: EditText
    private lateinit var accountPasswordInput: EditText
    private lateinit var accountSmsInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var boundVehicleSpinner: Spinner
    private lateinit var boundVehicleAdapter: ArrayAdapter<String>
    private val boundVehicles = mutableListOf<VehicleSummary>()
    private var loginUserId = ""
    private var suppressVehicleSelection = false

    private lateinit var profileSpinner: Spinner
    private lateinit var profileSpinnerAdapter: ArrayAdapter<String>
    private lateinit var profileVinInput: EditText
    private lateinit var profileUserInput: EditText
    private lateinit var profileMobileInput: EditText
    private lateinit var profileBleNameInput: EditText
    private lateinit var profileBleMacInput: EditText
    private lateinit var profileDeviceIdInput: EditText
    private lateinit var profileAccountRoleSpinner: Spinner
    private lateinit var profileRootKeyInput: EditText
    private lateinit var profileMetaView: TextView
    private lateinit var tlvPreviewView: TextView
    private lateinit var cloudResultView: TextView
    private lateinit var cloudProvisionButton: Button
    private var suppressProfileSelection = false

    private lateinit var bleDeviceSpinner: Spinner
    private lateinit var bleDeviceAdapter: ArrayAdapter<String>
    private lateinit var bleStatusView: TextView
    private lateinit var connectButton: Button
    private lateinit var autoPairSwitch: Switch
    private lateinit var pinPrimaryView: TextView
    private lateinit var pinDetailView: TextView
    private lateinit var stageView: TextView
    private lateinit var ffe1Pill: TextView
    private lateinit var auth02Pill: TextView
    private lateinit var session03Pill: TextView
    private lateinit var account80Pill: TextView
    private lateinit var customTlvInput: EditText

    private lateinit var telemetryView: TextView
    private lateinit var httpPinInput: EditText
    private lateinit var httpAcTempInput: EditText
    private lateinit var httpTokenStateView: TextView

    private lateinit var logScroll: ScrollView
    private lateinit var logView: TextView

    private val scannedDevices = linkedMapOf<String, BluetoothDevice>()
    private var selectedBleDevice: BluetoothDevice? = null
    private var currentPinCandidates: PairingPinCandidates? = null
    private var bleConnected = false
    private var bleAuthenticated = false
    private var pendingBindingLoginMethod: String? = null
    private var provisioningVin: String? = null
    private var pendingPermissionAction: (() -> Unit)? = null
    private val logBuffer = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }

    private data class CardParts(val root: MaterialCardView, val body: LinearLayout)
    private data class ActionButtonSpec(val label: String, val actionKey: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileStore = VehicleProfileStore(this)
        httpClient = CheryHttpClient()
        provisioner = InGeekCloudProvisioner(applicationContext)
        val active = profileStore.getActiveProfile()
        bleController = CheryBleController(
            context = this,
            config = CheryBleController.Config(
                activeBleMac = active.bleMac.takeIf { it.isNotBlank() },
                activeBleName = active.bleName.takeIf { it.isNotBlank() }
            ),
            listener = this
        )

        setContentView(buildRoot())
        reloadProfiles(active.vin)
        refreshTokenState(active)
        selectTab(0)

        appendLog("SYS", "CarPlus UI initialized; active VIN=${active.vin}")
        appendLog("SYS", "BLE scan/connect permissions are requested on first use (Android 6–17 compatible).")
    }

    override fun onDestroy() {
        pendingPermissionAction = null
        bleController.close()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // UI construction
    // ---------------------------------------------------------------------

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(243, 246, 250))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            setBackgroundColor(COLOR_PRIMARY)
        }
        header.addView(textView("CarPlus 数字钥匙受控验证台", 22f, Color.WHITE, bold = true))
        header.addView(
            textView("HTTP 远控 / BLE 数字钥匙 / 协议观测", 13f, Color.parseColor("#DCEBFF")),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(3) }
        )
        header.addView(
            textView("仅限授权车辆、已绑定测试账号与脱敏材料", 11f, Color.parseColor("#BFD8F7")),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(7) }
        )
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        val tabNames = listOf("1 账号钥匙", "2 蓝牙控车", "3 网络控车", "4 抓包日志")
        tabNames.forEachIndexed { index, name ->
            val button = Button(this).apply {
                text = name
                isAllCaps = false
                textSize = 12f
                minHeight = dp(44)
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { selectTab(index) }
            }
            tabButtons += button
            tabBar.addView(
                button,
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
            )
        }
        root.addView(tabBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        contentFrame = FrameLayout(this)
        root.addView(contentFrame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        tabPages += buildAccountPage()
        tabPages += buildBlePage()
        tabPages += buildHttpPage()
        tabPages += buildLogPage()
        tabPages.forEach { page ->
            contentFrame.addView(page, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        return root
    }

    private fun buildAccountPage(): View {
        val (scroll, page) = createScrollPage()
        val active = profileStore.getActiveProfile()

        val loginCard = createCard(
            "智云互联登录与新设备绑定",
            "serviceType 101 密码登录；920(type=7) + 921 + 105 短信登录；0843 时执行 107 + 108(deviceId)。"
        )
        accountMobileInput = createInput("请输入智云互联手机号", active.mobileNo, InputType.TYPE_CLASS_PHONE)
        accountPasswordInput = createInput(
            "请输入登录密码",
            "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        ).apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    runPasswordLogin()
                    true
                } else {
                    false
                }
            }
        }
        accountSmsInput = createInput("请输入短信验证码", "", InputType.TYPE_CLASS_NUMBER)
        addField(loginCard.body, "手机号", accountMobileInput)
        addField(loginCard.body, "密码", accountPasswordInput)

        val passwordButton = actionButton("账号密码登录", ButtonStyle.PRIMARY)
        passwordButton.setOnClickListener { runPasswordLogin() }
        loginCard.body.addTop(passwordButton, 12)

        addField(loginCard.body, "短信验证码", accountSmsInput)
        val smsRow = linearRow()
        val sendSmsButton = actionButton("发送验证码", ButtonStyle.SECONDARY, compact = true)
        sendSmsButton.setOnClickListener { runSendSmsCode() }
        val smsLoginButton = actionButton("短信登录", ButtonStyle.PRIMARY, compact = true)
        smsLoginButton.setOnClickListener { runSmsLogin() }
        smsRow.addView(sendSmsButton, weightedParams())
        smsRow.addView(smsLoginButton, weightedParams(startMargin = 8))
        loginCard.body.addTop(smsRow, 10)

        val bindingRow = linearRow()
        val requestBindingButton = actionButton("发送新设备绑定短信(107)", ButtonStyle.SECONDARY, compact = true)
        requestBindingButton.setOnClickListener { requestNewDeviceBindingSms() }
        val confirmBindingButton = actionButton("确认设备绑定(108)", ButtonStyle.PRIMARY, compact = true)
        confirmBindingButton.setOnClickListener { confirmNewDeviceBinding() }
        bindingRow.addView(requestBindingButton, weightedParams())
        bindingRow.addView(confirmBindingButton, weightedParams(startMargin = 8))
        loginCard.body.addTop(bindingRow, 8)
        page.addCard(loginCard)

        val tokenCard = createCard(
            "globalId 会话恢复与账号车辆同步",
            "登录成功后自动执行 112(userId/telephone) + 900(vehicleList/DataResp900a)，也可手工恢复 globalId。"
        )
        tokenInput = createInput(
            "登录后自动获取或输入 globalId",
            active.accessToken,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            monospace = true
        )
        addField(tokenCard.body, "globalId", tokenInput)
        val tokenRow = linearRow()
        val saveTokenButton = actionButton("保存 Token 到当前 Profile", ButtonStyle.SECONDARY, compact = true)
        saveTokenButton.setOnClickListener { saveManualToken() }
        val fetchVehiclesButton = actionButton("🔄 同步账号信息(112)与名下车辆(900)并自动解封蓝牙钥匙", ButtonStyle.PRIMARY, compact = true)
        fetchVehiclesButton.setOnClickListener { fetchBoundVehiclesFromToken() }
        tokenRow.addView(saveTokenButton, weightedParams())
        tokenRow.addView(fetchVehiclesButton, weightedParams(startMargin = 8))
        tokenCard.body.addTop(tokenRow, 10)

        boundVehicleSpinner = createSpinner()
        boundVehicleAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            mutableListOf("尚未拉取车辆")
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        boundVehicleSpinner.adapter = boundVehicleAdapter
        boundVehicleSpinner.onItemSelectedListener = object : SimpleItemSelectedListener() {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!suppressVehicleSelection && position in boundVehicles.indices) {
                    activateBoundVehicle(boundVehicles[position])
                }
            }
        }
        addField(tokenCard.body, "账号绑定车辆", boundVehicleSpinner)
        page.addCard(tokenCard)

        val profileCard = createCard(
            "多车 Profile 切换与编辑",
            "下拉切换已保存档案；修改后保存会立即更新 0x80 TLV 预览和 BLE 会话材料。"
        )
        profileSpinner = createSpinner()
        profileSpinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            mutableListOf<String>()
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        profileSpinner.adapter = profileSpinnerAdapter
        profileSpinner.onItemSelectedListener = object : SimpleItemSelectedListener() {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressProfileSelection) return
                val profiles = runCatching { profileStore.listProfiles() }.getOrDefault(emptyList())
                val selected = profiles.getOrNull(position) ?: return
                profileStore.setActiveVin(selected.vin)?.let {
                    loadProfileIntoEditor(it)
                    refreshTokenState(it)
                    maybeAutoProvision(it, "profile-switch")
                }
            }
        }
        addField(profileCard.body, "已保存车辆 Profile", profileSpinner)

        profileVinInput = createInput("登录后自动获取或输入17位VIN", active.vin, InputType.TYPE_CLASS_TEXT)
        profileUserInput = createInput("登录后自动获取用户ID", active.userId, InputType.TYPE_CLASS_TEXT)
        profileMobileInput = createInput("请输入智云互联手机号", active.mobileNo, InputType.TYPE_CLASS_PHONE)
        profileBleNameInput = createInput("自动扫描或输入 CHRxxxxxx", active.bleName, InputType.TYPE_CLASS_TEXT)
        profileBleMacInput = createInput(
            "登录后自动获取或输入蓝牙MAC",
            active.bleMac,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            monospace = true
        )
        profileDeviceIdInput = createInput(
            "自动生成或输入 DeviceID",
            active.deviceId,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            monospace = true
        )
        profileRootKeyInput = createInput(
            "软解完成后自动填充 K_root Hex",
            active.rootKeyHex,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            monospace = true
        )

        addField(profileCard.body, "VIN", profileVinInput)
        addField(profileCard.body, "UserID", profileUserInput)
        addField(profileCard.body, "手机号", profileMobileInput)
        addField(profileCard.body, "BLE 名称", profileBleNameInput)
        addField(profileCard.body, "BLE MAC", profileBleMacInput)
        addField(profileCard.body, "DeviceID", profileDeviceIdInput)

        profileAccountRoleSpinner = createSpinner()
        profileAccountRoleSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("0 = 车主 / 主钥匙", "1 = 子钥匙 / 分享钥匙")
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        addField(profileCard.body, "账号角色", profileAccountRoleSpinner)
        addField(profileCard.body, "K_root Hex", profileRootKeyInput)

        profileMetaView = textView("", 12f, COLOR_MUTED, monospace = true).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(Color.parseColor("#F4F7FB"), COLOR_BORDER, 10f)
            setTextIsSelectable(true)
        }
        profileCard.body.addTop(profileMetaView, 10)

        tlvPreviewView = textView("0x80 TLV 预览将在输入完成后显示", 12f, COLOR_TEXT, monospace = true).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(Color.parseColor("#F4F7FB"), COLOR_BORDER, 10f)
            setTextIsSelectable(true)
        }
        profileCard.body.addTop(labelView("实时 0x80 ACCOUNT_TYPE_SETTING TLV"), 12)
        profileCard.body.addTop(tlvPreviewView, 6)

        val saveProfileButton = actionButton("保存 Profile", ButtonStyle.PRIMARY)
        saveProfileButton.setOnClickListener { saveEditorProfile(showConfirmation = true) }
        profileCard.body.addTop(saveProfileButton, 12)
        page.addCard(profileCard)

        val cloudCard = createCard(
            "One-Click Cloud Key Unwrap",
            "TokenRequest 00 + KeyReLoadRequest 31，在软件层完成 RSA-OAEP-SHA1 + AES-256-GCM 解封并保存 K_root / info.bin / edf.bin。"
        )
        cloudProvisionButton = actionButton(
            "☁️ 云端 00+31 一键拉取并纯软件解封数字钥匙 K_root (免 KeyMint 兼容 Android 10–17)",
            ButtonStyle.CLOUD
        ).apply {
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { runCloudProvisioning() }
        }
        cloudCard.body.addView(cloudProvisionButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        cloudResultView = textView("尚未执行云端解封", 12f, COLOR_MUTED).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(Color.parseColor("#F4F7FB"), COLOR_BORDER, 10f)
        }
        cloudCard.body.addTop(cloudResultView, 10)
        page.addCard(cloudCard)

        val clearAllButton = actionButton("🚪 退出登录并清空本地数据", ButtonStyle.DANGER)
        clearAllButton.setOnClickListener { logoutAndClearLocalData() }
        page.addTop(clearAllButton, 2)

        val tlvWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateTlvPreview()
        }
        profileUserInput.addTextChangedListener(tlvWatcher)
        profileDeviceIdInput.addTextChangedListener(tlvWatcher)
        profileAccountRoleSpinner.onItemSelectedListener = object : SimpleItemSelectedListener() {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateTlvPreview()
            }
        }
        loadProfileIntoEditor(active)
        return scroll
    }

    private fun buildBlePage(): View {
        val (scroll, page) = createScrollPage()

        val scanCard = createCard(
            "附近 BLE 扫描与连接",
            "扫描 CHR* / BLE_DK_* / InGeek 广播；连接后自动执行 FFE1=0x81、0x02、0x03 与 0x80。"
        )
        val scanRow = linearRow()
        val scanButton = actionButton("扫描附近车辆 (CHR* / 0xFFF0)", ButtonStyle.PRIMARY, compact = true)
        scanButton.setOnClickListener { scanBleDevices() }
        connectButton = actionButton("连接 / 握手", ButtonStyle.SECONDARY, compact = true)
        connectButton.setOnClickListener { toggleBleConnection() }
        scanRow.addView(scanButton, weightedParams())
        scanRow.addView(connectButton, weightedParams(startMargin = 8))
        scanCard.body.addView(scanRow)

        bleDeviceSpinner = createSpinner()
        bleDeviceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            mutableListOf("扫描后选择目标设备（也可按当前 Profile MAC 直连）")
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        bleDeviceSpinner.adapter = bleDeviceAdapter
        bleDeviceSpinner.onItemSelectedListener = object : SimpleItemSelectedListener() {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val devices = scannedDevices.values.toList()
                selectedBleDevice = devices.getOrNull(position)
                selectedBleDevice?.let(::bindScannedDeviceToActiveProfile)
            }
        }
        addField(scanCard.body, "扫描结果", bleDeviceSpinner)

        autoPairSwitch = Switch(this).apply {
            text = "Auto-Pair：ACTION_PAIRING_REQUEST 自动填写 6 位动态 PIN"
            textSize = 13f
            isChecked = true
            setTextColor(COLOR_TEXT)
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                bleController.setAutoPairEnabled(checked)
                appendLog("BLE", "Auto-Pair ${if (checked) "ENABLED" else "DISABLED"}")
            }
        }
        scanCard.body.addTop(autoPairSwitch, 6)
        bleStatusView = textView("BLE：未连接", 12f, COLOR_MUTED, monospace = true)
        scanCard.body.addTop(bleStatusView, 6)
        page.addCard(scanCard)

        val pinCard = createCard(
            "6-Digit Dynamic Bluetooth Pairing PIN",
            "主 MAC PIN、BLE 名称后缀 PIN 与 Type-1 INGEEK PIN 均来自实时广播随机值。"
        )
        pinCard.root.setCardBackgroundColor(Color.parseColor("#FFF7E8"))
        pinCard.root.strokeColor = Color.parseColor("#F2B84B")
        pinPrimaryView = textView("------", 42f, Color.parseColor("#9A4D00"), bold = true, monospace = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
        }
        pinCard.body.addTop(pinPrimaryView, 8)
        pinDetailView = textView(
            "Primary MAC PIN: ------\nBLE name suffix PIN: ------\nType-1 INGEEK PIN: ------\n广播随机值: -",
            12f,
            COLOR_TEXT,
            monospace = true
        ).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(Color.WHITE, Color.parseColor("#EBCB98"), 10f)
            setTextIsSelectable(true)
        }
        pinCard.body.addTop(pinDetailView, 8)
        val copyPinButton = actionButton("复制主 PIN", ButtonStyle.CLOUD, compact = true)
        copyPinButton.setOnClickListener { copyPrimaryPin() }
        pinCard.body.addTop(copyPinButton, 10)
        page.addCard(pinCard)

        val stageCard = createCard("Handshake Status Pill Bar", "实时显示链路、认证、会话与账号绑定阶段。")
        stageView = textView("阶段：空闲", 14f, COLOR_TEXT, bold = true, monospace = true)
        stageCard.body.addView(stageView)
        ffe1Pill = statusPill("FFE1 (0x81)：等待")
        auth02Pill = statusPill("0x02 Auth：等待")
        session03Pill = statusPill("0x03 Session：等待")
        account80Pill = statusPill("0x80 Binding：等待")
        val pillRow1 = linearRow()
        pillRow1.addView(ffe1Pill, weightedParams())
        pillRow1.addView(auth02Pill, weightedParams(startMargin = 6))
        val pillRow2 = linearRow()
        pillRow2.addView(session03Pill, weightedParams())
        pillRow2.addView(account80Pill, weightedParams(startMargin = 6))
        stageCard.body.addTop(pillRow1, 10)
        stageCard.body.addTop(pillRow2, 6)
        page.addCard(stageCard)

        val doorCard = createCard(
            "Primary Door Control",
            "未连接时会自动扫描目标 Profile、建立会话、完成 0x80 后发送车门 TLV。"
        )
        val unlockButton = actionButton("🔓 蓝牙直连解锁车门 (0x01=02)", ButtonStyle.SUCCESS)
        unlockButton.textSize = 18f
        unlockButton.minHeight = dp(68)
        unlockButton.setOnClickListener { performBleAction("unlock") }
        val lockButton = actionButton("🔒 蓝牙直连闭锁车门 (0x01=01)", ButtonStyle.DANGER)
        lockButton.textSize = 18f
        lockButton.minHeight = dp(68)
        lockButton.setOnClickListener { performBleAction("lock") }
        doorCard.body.addView(unlockButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        doorCard.body.addTop(lockButton, 10)
        page.addCard(doorCard)

        val actionsCard = createCard(
            "Full 17-Action TBox BLE Vehicle Control Grid",
            "所有按钮均调用 TBoxTlvBuilder，并通过已认证的 0x04 加密业务帧发送。"
        )
        val actionSpecs = listOf(
            ActionButtonSpec("🔓 解锁", "unlock"),
            ActionButtonSpec("🔒 闭锁", "lock"),
            ActionButtonSpec("📣 寻车", "find_car"),
            ActionButtonSpec("🪟 开窗", "window_open"),
            ActionButtonSpec("🌬️ 通风", "window_ventilate"),
            ActionButtonSpec("🪟 关窗", "window_close"),
            ActionButtonSpec("🚪 尾门开", "tailgate_open"),
            ActionButtonSpec("🚪 尾门关", "tailgate_close"),
            ActionButtonSpec("🧳 后备箱开", "trunk_open"),
            ActionButtonSpec("🧳 后备箱关", "trunk_close"),
            ActionButtonSpec("☀️ 天窗开", "skylight_open"),
            ActionButtonSpec("🌤️ 天窗翘起", "skylight_tilt"),
            ActionButtonSpec("☀️ 天窗关", "skylight_close"),
            ActionButtonSpec("🔑 启动发动机", "engine_start"),
            ActionButtonSpec("🛑 停止发动机", "engine_stop"),
            ActionButtonSpec("❄️ 空调开", "ac_on"),
            ActionButtonSpec("❄️ 空调关", "ac_off")
        )
        actionsCard.body.addTop(buildActionGrid(actionSpecs), 10)
        page.addCard(actionsCard)

        val customCard = createCard("Custom TLV Hex", "连接并认证后可直接发送原始 TLV。")
        customTlvInput = createInput(
            "例如 01 03 01 01 01",
            "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            monospace = true
        )
        addField(customCard.body, "TLV Hex", customTlvInput)
        val customSendButton = actionButton("发送自定义 TLV", ButtonStyle.PRIMARY)
        customSendButton.setOnClickListener { sendCustomTlv() }
        customCard.body.addTop(customSendButton, 10)
        page.addCard(customCard)
        return scroll
    }

    private fun buildHttpPage(): View {
        val (scroll, page) = createScrollPage()

        val telemetryCard = createCard(
            "实时车辆状态遥测 (serviceType=911 / net910)",
            "展示门锁、四门、四窗、天窗、尾门、后备箱、发动机、空调、里程、油量/电量、续航、胎压胎温、GPS 与在线状态。"
        )
        val refreshButton = actionButton("刷新车辆状态", ButtonStyle.PRIMARY)
        refreshButton.setOnClickListener { refreshTelemetry() }
        telemetryCard.body.addView(refreshButton)
        httpTokenStateView = textView("", 11f, COLOR_MUTED, monospace = true)
        telemetryCard.body.addTop(httpTokenStateView, 8)
        telemetryView = textView("尚未刷新车辆状态", 13f, COLOR_TEXT, monospace = true).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBackground(Color.parseColor("#F4F7FB"), COLOR_BORDER, 10f)
            setTextIsSelectable(true)
        }
        telemetryCard.body.addTop(telemetryView, 10)
        page.addCard(telemetryCard)

        val remoteCard = createCard(
            "远程网络控车 125 + 970",
            "6 位车辆安全密码经 l.SHA256 哈希为 Base64(lowercase hex SHA-256)；125 校验后以 safePasswd 发送 970。"
        )
        httpPinInput = createInput("请输入6位车辆安全密码", profileStore.getActiveProfile().controlPin, InputType.TYPE_CLASS_NUMBER)
        addField(remoteCard.body, "6 位车辆安全密码 safePasswd", httpPinInput)
        httpAcTempInput = createInput("24", "24", InputType.TYPE_CLASS_NUMBER)
        addField(remoteCard.body, "空调目标温度 (℃)", httpAcTempInput)
        val remoteSpecs = listOf(
            ActionButtonSpec("🔓 远程解锁", RemoteCommand.DOOR_UNLOCK.name),
            ActionButtonSpec("🔒 远程闭锁", RemoteCommand.DOOR_LOCK.name),
            ActionButtonSpec("🔑 发动机启动", RemoteCommand.ENGINE_START.name),
            ActionButtonSpec("🛑 发动机停止", RemoteCommand.ENGINE_STOP.name),
            ActionButtonSpec("❄️ 远程空调开", RemoteCommand.AC_ON.name),
            ActionButtonSpec("❄️ 远程空调关", RemoteCommand.AC_OFF.name),
            ActionButtonSpec("📣 寻车闪灯/鸣笛", RemoteCommand.FIND_CAR.name),
            ActionButtonSpec("🪟 车窗全开", RemoteCommand.WINDOW_OPEN.name),
            ActionButtonSpec("🌬️ 车窗通风", RemoteCommand.WINDOW_VENTILATE.name),
            ActionButtonSpec("🪟 车窗关闭", RemoteCommand.WINDOW_CLOSE.name),
            ActionButtonSpec("🚪 尾门开启", RemoteCommand.TAILGATE_OPEN.name),
            ActionButtonSpec("🚪 尾门关闭", RemoteCommand.TAILGATE_CLOSE.name),
            ActionButtonSpec("🧳 后备箱解锁", RemoteCommand.TRUNK_UNLOCK.name),
            ActionButtonSpec("☀️ 天窗开启", RemoteCommand.SKYLIGHT_OPEN.name),
            ActionButtonSpec("🌤️ 天窗翘起", RemoteCommand.SKYLIGHT_TILT.name),
            ActionButtonSpec("☀️ 天窗关闭", RemoteCommand.SKYLIGHT_CLOSE.name)
        )
        remoteCard.body.addTop(buildRemoteActionGrid(remoteSpecs), 10)
        page.addCard(remoteCard)
        return scroll
    }

    private fun buildLogPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = dp(2).toFloat()
            strokeWidth = dp(1)
            strokeColor = COLOR_BORDER
            setCardBackgroundColor(Color.WHITE)
            useCompatPadding = true
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(body, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val toolbar = linearRow()
        val copyButton = actionButton("复制全部日志", ButtonStyle.PRIMARY, compact = true)
        copyButton.setOnClickListener { copyAllLogs() }
        val clearButton = actionButton("清空日志", ButtonStyle.SECONDARY, compact = true)
        clearButton.setOnClickListener { clearLogs() }
        toolbar.addView(copyButton, weightedParams())
        toolbar.addView(clearButton, weightedParams(startMargin = 8))
        body.addView(toolbar)

        logScroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#111827"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10.5f
            setTextColor(Color.parseColor("#D7E3F4"))
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.08f)
        }
        logScroll.addView(logView, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        body.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        root.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    private fun selectTab(index: Int) {
        tabPages.forEachIndexed { pageIndex, page ->
            page.visibility = if (pageIndex == index) View.VISIBLE else View.GONE
        }
        tabButtons.forEachIndexed { buttonIndex, button ->
            val selected = buttonIndex == index
            button.backgroundTintList = ColorStateList.valueOf(
                if (selected) COLOR_PRIMARY else Color.parseColor("#E7EEF7")
            )
            button.setTextColor(if (selected) Color.WHITE else COLOR_TEXT)
        }
    }

    private fun createScrollPage(): Pair<ScrollView, LinearLayout> {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        scroll.addView(page, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return scroll to page
    }

    private fun createCard(title: String, subtitle: String? = null): CardParts {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = dp(2).toFloat()
            strokeWidth = dp(1)
            strokeColor = COLOR_BORDER
            setCardBackgroundColor(Color.WHITE)
            useCompatPadding = true
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(14))
        }
        card.addView(body, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        body.addView(textView(title, 18f, COLOR_TEXT, bold = true))
        if (!subtitle.isNullOrBlank()) {
            body.addView(
                textView(subtitle, 12f, COLOR_MUTED),
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) }
            )
        }
        return CardParts(card, body)
    }

    private fun LinearLayout.addCard(card: CardParts) {
        addView(
            card.root,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) }
        )
    }

    private fun LinearLayout.addTop(view: View, marginTop: Int) {
        addView(
            view,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(marginTop) }
        )
    }

    private fun addField(container: LinearLayout, label: String, input: View) {
        container.addTop(labelView(label), 11)
        container.addTop(input, 5)
    }

    private fun labelView(text: String): TextView =
        textView(text, 12f, COLOR_MUTED, bold = true)

    private fun createInput(
        hint: String,
        initialValue: String,
        inputType: Int,
        monospace: Boolean = false
    ): EditText = EditText(this).apply {
        this.hint = hint
        setText(initialValue)
        this.inputType = inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        textSize = 14f
        setTextColor(COLOR_TEXT)
        setHintTextColor(Color.parseColor("#8A99AB"))
        minHeight = dp(48)
        setSingleLine(true)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = roundedBackground(Color.parseColor("#FAFBFD"), COLOR_BORDER, 11f)
        if (monospace) typeface = Typeface.MONOSPACE
    }

    private fun createSpinner(): Spinner = Spinner(this).apply {
        background = roundedBackground(Color.parseColor("#FAFBFD"), COLOR_BORDER, 11f)
        minimumHeight = dp(48)
        setPadding(dp(10), 0, dp(10), 0)
    }

    private fun textView(
        text: String,
        sizeSp: Float = 14f,
        color: Int = COLOR_TEXT,
        bold: Boolean = false,
        monospace: Boolean = false
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        if (monospace) typeface = Typeface.MONOSPACE
    }

    private fun actionButton(
        label: String,
        style: ButtonStyle,
        compact: Boolean = false
    ): Button {
        val background = when (style) {
            ButtonStyle.PRIMARY -> COLOR_PRIMARY
            ButtonStyle.SECONDARY -> Color.parseColor("#E4EEF9")
            ButtonStyle.SUCCESS -> Color.parseColor("#138A5B")
            ButtonStyle.DANGER -> Color.parseColor("#B42318")
            ButtonStyle.CLOUD -> Color.parseColor("#D97706")
        }
        val foreground = if (style == ButtonStyle.SECONDARY) COLOR_PRIMARY else Color.WHITE
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = if (compact) 12.5f else 15f
            setTextColor(foreground)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            minHeight = dp(if (compact) 46 else 52)
            minimumHeight = dp(if (compact) 46 else 52)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            backgroundTintList = ColorStateList.valueOf(background)
        }
    }

    private fun statusPill(label: String): TextView = textView(label, 11f, COLOR_MUTED, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(7), dp(8), dp(7), dp(8))
        background = roundedBackground(Color.parseColor("#EEF2F7"), COLOR_BORDER, 20f)
    }

    private fun setPillState(pill: TextView, label: String, active: Boolean, failed: Boolean = false) {
        pill.text = "$label：${if (active) "完成" else if (failed) "失败" else "等待"}"
        val background = when {
            failed -> Color.parseColor("#FDE8E7")
            active -> Color.parseColor("#E2F7EE")
            else -> Color.parseColor("#EEF2F7")
        }
        val foreground = when {
            failed -> Color.parseColor("#B42318")
            active -> Color.parseColor("#087443")
            else -> COLOR_MUTED
        }
        val border = when {
            failed -> Color.parseColor("#F2B8B5")
            active -> Color.parseColor("#A7E3C7")
            else -> COLOR_BORDER
        }
        pill.background = roundedBackground(background, border, 20f)
        pill.setTextColor(foreground)
    }

    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            setStroke(dp(1), stroke)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }

    private fun linearRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun weightedParams(startMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = dp(startMargin)
        }

    private fun buildActionGrid(specs: List<ActionButtonSpec>): LinearLayout {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        specs.forEachIndexed { index, spec ->
            if (index % 2 == 0) {
                row = linearRow().also { grid.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    if (index > 0) topMargin = dp(8)
                }) }
            }
            val button = actionButton(spec.label, ButtonStyle.SECONDARY, compact = true)
            button.setOnClickListener { performBleAction(spec.actionKey) }
            row!!.addView(button, weightedParams(if (index % 2 == 0) 0 else 8))
        }
        if (specs.size % 2 == 1) {
            row?.addView(View(this), weightedParams(startMargin = 8))
        }
        return grid
    }

    private fun buildRemoteActionGrid(specs: List<ActionButtonSpec>): LinearLayout {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        specs.forEachIndexed { index, spec ->
            if (index % 2 == 0) {
                row = linearRow().also { grid.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    if (index > 0) topMargin = dp(8)
                }) }
            }
            val button = actionButton(spec.label, ButtonStyle.SECONDARY, compact = true)
            val command = runCatching { RemoteCommand.valueOf(spec.actionKey) }.getOrNull()
            button.setOnClickListener { command?.let(::runRemoteCommand) }
            row!!.addView(button, weightedParams(if (index % 2 == 0) 0 else 8))
        }
        if (specs.size % 2 == 1) {
            row?.addView(View(this), weightedParams(startMargin = 8))
        }
        return grid
    }

    // ---------------------------------------------------------------------
    // Profile editing and cloud provisioning
    // ---------------------------------------------------------------------

    private fun reloadProfiles(preferredVin: String? = null, loadEditor: Boolean = true) {
        val profiles = profileStore.listProfiles()
        val activeVin = preferredVin ?: profileStore.getActiveProfile().vin
        suppressProfileSelection = true
        profileSpinnerAdapter.clear()
        profileSpinnerAdapter.addAll(profiles.map { profile ->
            val keyState = when {
                profile.rootKeyHex.isBlank() -> "无 K_root"
                profile.infoHex.isBlank() -> "K_root 已载入"
                else -> "完整钥匙"
            }
            "${profile.vin} · ${profile.bleName.ifBlank { "未命名 BLE" }} · $keyState"
        })
        profileSpinnerAdapter.notifyDataSetChanged()
        val index = profiles.indexOfFirst { it.vin.equals(activeVin, ignoreCase = true) }.coerceAtLeast(0)
        if (profiles.isNotEmpty()) profileSpinner.setSelection(index, false)
        suppressProfileSelection = false
        if (loadEditor && profiles.isNotEmpty()) {
            val profile = profiles.getOrNull(index) ?: profileStore.getActiveProfile()
            loadProfileIntoEditor(profile)
            refreshTokenState(profile)
        }
        refreshBoundVehicleSelection(activeVin)
    }

    private fun loadProfileIntoEditor(profile: VehicleProfile) {
        profileVinInput.setText(profile.vin)
        profileUserInput.setText(profile.userId)
        profileMobileInput.setText(profile.mobileNo)
        profileBleNameInput.setText(profile.bleName)
        profileBleMacInput.setText(profile.bleMac)
        profileDeviceIdInput.setText(profile.deviceId)
        profileAccountRoleSpinner.setSelection(if (profile.accountType == 1) 1 else 0, false)
        profileRootKeyInput.setText(profile.rootKeyHex)
        profileMetaView.text = profileMeta(profile)
        updateTlvPreview()
        refreshTokenState(profile)
        httpPinInputIfReady()?.setText(profile.controlPin)
    }

    private fun profileMeta(profile: VehicleProfile): String {
        val rootBytes = runCatching { profile.rootKeyBytes().size }.getOrDefault(0)
        val infoBytes = runCatching { profile.infoBytes().size }.getOrDefault(0)
        val edfBytes = runCatching { profile.edfBytes().size }.getOrDefault(0)
        return buildString {
            appendLine("K_root: $rootBytes B | info.bin: $infoBytes B | edf.bin: $edfBytes B")
            appendLine("有效期: ${profile.validFrom.ifBlank { "-" }} → ${profile.validTo.ifBlank { "-" }}")
            append("Access Token: ${if (profile.accessToken.isBlank()) "缺失" else "已保存"} | ")
            append("SDK Token: ${if (profile.sdkToken.isBlank()) "缺失" else "已保存"}")
        }
    }

    private fun updateTlvPreview() {
        if (!::tlvPreviewView.isInitialized || !::profileUserInput.isInitialized) return
        val userId = profileUserInput.text?.toString().orEmpty()
        val deviceId = profileDeviceIdInput.text?.toString().orEmpty()
        val role = profileAccountRoleSpinner.selectedItemPosition.coerceIn(0, 1)
        val preview = runCatching {
            TBoxTlvBuilder.buildAccountSettingTlv(role, userId, deviceId)
        }
        tlvPreviewView.setTextColor(if (preview.isSuccess) COLOR_TEXT else Color.parseColor("#B42318"))
        tlvPreviewView.text = preview.fold(
            onSuccess = { bytes -> bytes.joinToString(" ") { "%02X".format(Locale.US, it.toInt() and 0xFF) } },
            onFailure = { error -> "TLV 预览不可用：${error.message}" }
        )
    }

    private fun saveEditorProfile(showConfirmation: Boolean): VehicleProfile? {
        val existing = profileStore.getActiveProfile()
        val vin = profileVinInput.text?.toString()?.trim()?.uppercase(Locale.US).orEmpty()
        if (vin.isBlank()) {
            toast("VIN 不能为空")
            return null
        }
        val vinChanged = !vin.equals(existing.vin, ignoreCase = true)
        val edited = existing.copy(
            vin = vin,
            userId = profileUserInput.text?.toString()?.trim().orEmpty(),
            mobileNo = profileMobileInput.text?.toString()?.trim().orEmpty(),
            bleName = profileBleNameInput.text?.toString()?.trim().orEmpty(),
            bleMac = profileBleMacInput.text?.toString()?.trim()?.uppercase(Locale.US).orEmpty(),
            deviceId = profileDeviceIdInput.text?.toString()?.trim().orEmpty(),
            accountType = profileAccountRoleSpinner.selectedItemPosition.coerceIn(0, 1),
            rootKeyHex = profileRootKeyInput.text?.toString()?.trim().orEmpty(),
            infoHex = if (vinChanged) "" else existing.infoHex,
            edfHex = if (vinChanged) "" else existing.edfHex,
            secAHex = if (vinChanged) "" else existing.secAHex,
            secBHex = if (vinChanged) "" else existing.secBHex,
            validFrom = if (vinChanged) "" else existing.validFrom,
            validTo = if (vinChanged) "" else existing.validTo,
            accessToken = tokenInputIfReady()?.text?.toString()?.trim().orEmpty().ifBlank { existing.accessToken },
            controlPin = httpPinInputIfReady()?.text?.toString()?.trim().orEmpty()
        )
        val saved = runCatching { profileStore.saveProfile(edited, activate = true) }.getOrElse { error ->
            appendLog("PROFILE", "保存失败：${error.message}")
            toast("保存 Profile 失败：${error.message}")
            return null
        }
        appendLog(
            "PROFILE",
            "Saved VIN=${saved.vin}, accountType=${saved.accountType}, BLE=${saved.bleName}/${saved.bleMac}"
        )
        reloadProfiles(saved.vin)
        if (showConfirmation) toast("Profile 已保存并切换为当前车辆")
        return saved
    }

    private fun refreshTokenState(profile: VehicleProfile) {
        if (::httpTokenStateView.isInitialized) {
            httpTokenStateView.text = "Token 状态：access=${if (profile.accessToken.isBlank()) "缺失" else "已保存"} · " +
                "sdk=${if (profile.sdkToken.isBlank()) "缺失" else "已保存"} · UserID=${profile.userId.ifBlank { "-" }}"
        }
        if (::tokenInput.isInitialized) tokenInput.setText(profile.accessToken)
    }

    private fun runCloudProvisioning() {
        val saved = saveEditorProfile(showConfirmation = false) ?: return
        provisionCloudKey(saved, automatic = false)
    }

    /**
     * Automatic post-login / vehicle-switch provisioning. It is deliberately
     * limited to profiles with the three server inputs and no locally cached key.
     */
    private fun maybeAutoProvision(profile: VehicleProfile, reason: String) {
        if (profile.vin.isBlank() || profile.userId.isBlank() || profile.mobileNo.isBlank()) {
            appendLog("CLOUD", "AUTO skipped ($reason): VIN/userId/mobileNo incomplete")
            return
        }
        if (profile.rootKeyHex.isNotBlank() && profile.infoHex.isNotBlank() && profile.edfHex.isNotBlank()) {
            appendLog("CLOUD", "AUTO skipped ($reason): digital key already present for VIN=${profile.vin}")
            return
        }
        if (provisioningVin != null) {
            appendLog("CLOUD", "AUTO deferred ($reason): provisioning already running for VIN=$provisioningVin")
            return
        }
        provisionCloudKey(profile, automatic = true)
    }

    private fun provisionCloudKey(profile: VehicleProfile, automatic: Boolean) {
        if (profile.userId.isBlank() || profile.mobileNo.isBlank() || profile.vin.isBlank()) {
            toast("云端解封前需要 VIN、UserID 与手机号")
            return
        }
        if (provisioningVin != null) {
            toast("已有车辆正在执行云端解封：$provisioningVin")
            return
        }
        provisioningVin = profile.vin
        cloudProvisionButton.isEnabled = false
        cloudResultView.setTextColor(COLOR_MUTED)
        cloudResultView.text = (if (automatic) "自动" else "") +
            "正在执行 DKtrans 00 + 31，并使用 RSA-2048 OAEP + AES-256-GCM 纯软件解封…"
        appendLog(
            "CLOUD",
            "${if (automatic) "AUTO " else ""}Provisioning START VIN=${profile.vin}, user=${profile.userId}, device=${profile.deviceId}"
        )

        lifecycleScope.launch {
            try {
                val result = provisioner.provisionVehicleKeyAsync(
                    userId = profile.userId,
                    mobileNo = profile.mobileNo,
                    vin = profile.vin,
                    bleName = profile.bleName,
                    bleMac = profile.bleMac,
                    deviceId = profile.deviceId,
                    accountType = profile.accountType,
                    profileStore = profileStore,
                    logger = { message -> appendLog("DKTRANS", message) }
                )
                if (result.success) {
                    val message = buildString {
                        appendLine(if (automatic) "自动云端解封成功" else "云端解封成功")
                        appendLine("keyId: ${result.keyId}")
                        appendLine("downloadTime: ${result.keyDownloadTime}")
                        appendLine("K_root: ${result.kRootHex}")
                        appendLine("info.bin: ${result.infoHex.length / 2} B")
                        append("edf.bin: ${result.edfHex.length / 2} B")
                    }
                    cloudResultView.setTextColor(Color.parseColor("#087443"))
                    cloudResultView.text = message
                    appendLog("DKTRANS", message)
                    val refreshed = result.profile ?: profileStore.getActiveProfile()
                    reloadProfiles(refreshed.vin)
                } else {
                    val message = "云端解封失败：${result.error ?: result.message}"
                    cloudResultView.setTextColor(Color.parseColor("#B42318"))
                    cloudResultView.text = message
                    appendLog("DKTRANS", message)
                }
            } catch (error: Throwable) {
                val message = "云端解封异常：${error.message ?: error.javaClass.simpleName}"
                cloudResultView.setTextColor(Color.parseColor("#B42318"))
                cloudResultView.text = message
                appendLog("DKTRANS", message)
            } finally {
                provisioningVin = null
                cloudProvisionButton.isEnabled = true
            }
        }
    }

    // ---------------------------------------------------------------------
    // Chery HTTP account, vehicle list, telemetry and remote control
    // ---------------------------------------------------------------------

    private fun runPasswordLogin() {
        val mobile = accountMobileInput.text?.toString()?.trim().orEmpty()
        val password = accountPasswordInput.text?.toString().orEmpty()
        if (mobile.isBlank() || password.isBlank()) {
            toast("请输入智云互联手机号和登录密码")
            return
        }
        val profile = profileStore.getActiveProfile()
        val deviceId = profile.deviceId.ifBlank { computeDeviceId() }
        appendLog("HTTP", "REQUEST serviceType=101 password-login mobile=$mobile deviceId=$deviceId")
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    httpClient.loginWithPassword(mobile, password, deviceId)
                }
                appendLog(
                    "HTTP",
                    "RESPONSE serviceType=101 OK globalId=${maskToken(result.accessToken)} userId=${result.accountId}\n${result.rawJson}"
                )
                completeLoginPipeline(result, mobile, deviceId)
            } catch (error: CheryDeviceBindingRequiredException) {
                handleDeviceBindingRequired(error, CheryHttpClient.LOGIN_METHOD_PASSWORD)
            } catch (error: Throwable) {
                appendLog("HTTP", "FAILED serviceType=101 password-login: ${error.message ?: error.javaClass.simpleName}")
                toast("密码登录失败：${error.message}")
            }
        }
    }

    private fun runSendSmsCode() {
        val mobile = accountMobileInput.text?.toString()?.trim().orEmpty()
        if (mobile.isBlank()) {
            toast("请输入智云互联手机号")
            return
        }
        appendLog("HTTP", "REQUEST serviceType=920 type=7 send-sms-code mobile=$mobile")
        lifecycleScope.launch {
            try {
                val sent = withContext(Dispatchers.IO) {
                    httpClient.sendSmsCode(mobile, type = CheryHttpClient.SMS_TYPE_LOGIN)
                }
                appendLog("HTTP", "RESPONSE serviceType=920 accepted=$sent")
                toast(if (sent) "验证码请求已提交" else "验证码请求未返回成功")
            } catch (error: Throwable) {
                appendLog("HTTP", "FAILED serviceType=920 send-sms-code: ${error.message ?: error.javaClass.simpleName}")
                toast("发送验证码失败：${error.message}")
            }
        }
    }

    private fun runSmsLogin() {
        val mobile = accountMobileInput.text?.toString()?.trim().orEmpty()
        val smsCode = accountSmsInput.text?.toString()?.trim().orEmpty()
        if (mobile.isBlank() || smsCode.isBlank()) {
            toast("请输入智云互联手机号和短信验证码")
            return
        }
        val profile = profileStore.getActiveProfile()
        val deviceId = profile.deviceId.ifBlank { computeDeviceId() }
        appendLog("HTTP", "REQUEST serviceType=921 verify-sms mobile=$mobile type=7")
        lifecycleScope.launch {
            try {
                val verified = withContext(Dispatchers.IO) {
                    httpClient.verifySmsCode(mobile, smsCode, CheryHttpClient.SMS_TYPE_LOGIN)
                }
                appendLog("HTTP", "RESPONSE serviceType=921 verified=$verified")
                if (!verified) error("serviceType=921 短信验证未通过")

                appendLog("HTTP", "REQUEST serviceType=105 sms-login mobile=$mobile deviceId=$deviceId")
                val result = withContext(Dispatchers.IO) {
                    httpClient.loginWithSms(mobile, smsCode, deviceId)
                }
                appendLog(
                    "HTTP",
                    "RESPONSE serviceType=105 OK globalId=${maskToken(result.accessToken)} userId=${result.accountId}\n${result.rawJson}"
                )
                completeLoginPipeline(result, mobile, deviceId)
            } catch (error: CheryDeviceBindingRequiredException) {
                handleDeviceBindingRequired(error, CheryHttpClient.LOGIN_METHOD_SMS)
            } catch (error: Throwable) {
                appendLog("HTTP", "FAILED serviceType=921/105 sms-login: ${error.message ?: error.javaClass.simpleName}")
                toast("短信登录失败：${error.message}")
            }
        }
    }

    private fun requestNewDeviceBindingSms() {
        val mobile = accountMobileInput.text?.toString()?.trim().orEmpty()
        if (mobile.isBlank()) {
            toast("请输入智云互联手机号")
            return
        }
        val loginMethod = pendingBindingLoginMethod
            ?: if (accountPasswordInput.text?.isNotEmpty() == true) {
                CheryHttpClient.LOGIN_METHOD_PASSWORD
            } else {
                CheryHttpClient.LOGIN_METHOD_SMS
            }
        appendLog("HTTP", "REQUEST serviceType=107 loginMethod=$loginMethod mobile=$mobile")
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    httpClient.requestDeviceBindSmsDetailed(mobile, loginMethod)
                }
                appendLog(
                    "HTTP",
                    "RESPONSE serviceType=107 success=${result.success} resultCode=${result.resultCode} " +
                        "telephone=${result.telephone}\n${result.rawJson}"
                )
                toast(if (result.success) "新设备绑定短信已发送，请输入验证码" else "107 绑定短信失败：${result.message}")
            } catch (error: Throwable) {
                appendLog("HTTP", "FAILED serviceType=107: ${error.message ?: error.javaClass.simpleName}")
                toast("发送新设备绑定短信失败：${error.message}")
            }
        }
    }

    private fun confirmNewDeviceBinding() {
        val mobile = accountMobileInput.text?.toString()?.trim().orEmpty()
        val smsCode = accountSmsInput.text?.toString()?.trim().orEmpty()
        if (mobile.isBlank() || smsCode.isBlank()) {
            toast("请输入智云互联手机号和新设备绑定验证码")
            return
        }
        val loginMethod = pendingBindingLoginMethod ?: CheryHttpClient.LOGIN_METHOD_SMS
        val deviceId = profileStore.getActiveProfile().deviceId.ifBlank { computeDeviceId() }
        appendLog("HTTP", "REQUEST serviceType=108 loginMethod=$loginMethod deviceId=$deviceId")
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    httpClient.confirmDeviceBindSmsDetailed(
                        loginName = mobile,
                        validateCode = smsCode,
                        loginMethod = loginMethod,
                        deviceId = deviceId
                    )
                }
                appendLog(
                    "HTTP",
                    "RESPONSE serviceType=108 success=${result.success} resultCode=${result.resultCode}\n${result.rawJson}"
                )
                if (!result.success) {
                    toast("108 设备绑定失败：${result.message}")
                    return@launch
                }
                pendingBindingLoginMethod = null
                toast("108 设备绑定成功，正在自动重试登录")
                if (loginMethod == CheryHttpClient.LOGIN_METHOD_PASSWORD) {
                    runPasswordLogin()
                } else {
                    runSmsLogin()
                }
            } catch (error: Throwable) {
                appendLog("HTTP", "FAILED serviceType=108: ${error.message ?: error.javaClass.simpleName}")
                toast("设备绑定失败：${error.message}")
            }
        }
    }

    private fun handleDeviceBindingRequired(
        error: CheryDeviceBindingRequiredException,
        loginMethod: String
    ) {
        pendingBindingLoginMethod = loginMethod
        appendLog(
            "HTTP",
            "RESPONSE resultCode=${error.resultCode} DEVICE_BIND_REQUIRED telephone=${error.telephone}; ${error.message}"
        )
        toast("新设备需要 107+108 短信绑定：${error.message}")
        if (loginMethod == CheryHttpClient.LOGIN_METHOD_SMS) {
            requestNewDeviceBindingSms()
        }
    }

    private suspend fun completeLoginPipeline(
        result: com.chery.carplus.cloud.CheryLoginResult,
        mobile: String,
        deviceId: String
    ) {
        val token = result.accessToken
        tokenInput.setText(token)
        loginUserId = result.accountId
        val user = withContext(Dispatchers.IO) {
            runCatching { httpClient.fetchUserInfo(token) }
                .getOrElse { error ->
                    result.userInfo ?: throw error
                }
        }
        appendLog("HTTP", "RESPONSE serviceType=112 userId=${user.userId} telephone=${user.telephone}\n${user.rawJson}")

        val vehicles = withContext(Dispatchers.IO) {
            runCatching { httpClient.fetchVehicleList(token) }
                .getOrDefault(result.vehicles)
        }
        appendLog(
            "HTTP",
            "RESPONSE serviceType=900 count=${vehicles.size}\n" +
                vehicles.mapIndexed { index, vehicle -> "[$index] ${vehicle.rawJson}" }.joinToString("\n")
        )

        val active = applyAccountAndVehicleSnapshot(
            user = user,
            vehicles = vehicles,
            mobile = mobile,
            deviceId = deviceId,
            accessToken = token,
            sdkToken = result.sdkToken
        )
        appendLog(
            "PROFILE",
            "Account sync saved ${vehicles.size} vehicle(s); active VIN=${active.vin.ifBlank { "draft" }}"
        )
        maybeAutoProvision(active, "login")
    }

    private fun applyAccountAndVehicleSnapshot(
        user: CheryUserInfo,
        vehicles: List<VehicleSummary>,
        mobile: String,
        deviceId: String,
        accessToken: String,
        sdkToken: String
    ): VehicleProfile {
        val existingProfiles = profileStore.listProfiles()
        val currentDraft = profileStore.getActiveProfile()
        val preferredVin = currentDraft.vin
        vehicles.forEachIndexed { index, summary ->
            val vin = summary.vin.trim().uppercase(Locale.US)
            if (vin.isBlank()) return@forEachIndexed
            val existing = existingProfiles.firstOrNull { it.vin.equals(vin, ignoreCase = true) }
                ?: currentDraft.takeIf { index == 0 && it.vin.isBlank() }
            val profile = VehicleProfile(
                vin = vin,
                userId = user.userId.ifBlank { existing?.userId.orEmpty() },
                mobileNo = user.telephone.ifBlank { mobile.trim() },
                bleName = summary.bleName.ifBlank { existing?.bleName.orEmpty() },
                bleMac = summary.bleMac.trim().uppercase(Locale.US).ifBlank { existing?.bleMac.orEmpty() },
                deviceId = deviceId,
                accountType = summary.accountType,
                rootKeyHex = existing?.rootKeyHex.orEmpty(),
                infoHex = existing?.infoHex.orEmpty(),
                edfHex = existing?.edfHex.orEmpty(),
                validFrom = existing?.validFrom.orEmpty(),
                validTo = existing?.validTo.orEmpty(),
                secAHex = existing?.secAHex.orEmpty(),
                secBHex = existing?.secBHex.orEmpty(),
                keyRole = existing?.keyRole ?: 1,
                accessToken = accessToken,
                sdkToken = sdkToken.ifBlank { existing?.sdkToken.orEmpty() },
                controlPin = existing?.controlPin.orEmpty()
            )
            profileStore.saveProfile(profile, activate = false)
        }

        val primaryVin = when {
            vehicles.any { it.vin.equals(preferredVin, ignoreCase = true) } -> preferredVin
            vehicles.firstOrNull { it.selected == "1" }?.vin?.uppercase(Locale.US) != null ->
                vehicles.first { it.selected == "1" }.vin.uppercase(Locale.US)
            else -> vehicles.firstOrNull()?.vin?.trim()?.uppercase(Locale.US).orEmpty()
        }

        if (primaryVin.isNotBlank()) {
            profileStore.setActiveVin(primaryVin)
        } else {
            val draft = profileStore.getActiveProfile().copy(
                userId = user.userId,
                mobileNo = user.telephone.ifBlank { mobile.trim() },
                deviceId = deviceId,
                accessToken = accessToken,
                sdkToken = sdkToken
            )
            profileStore.saveProfile(draft, activate = false)
        }

        val active = profileStore.getActiveProfile()
        updateBoundVehicleSpinner(vehicles, active.vin)
        reloadProfiles(active.vin)
        loadProfileIntoEditor(active)
        refreshTokenState(active)
        return active
    }

    private fun updateBoundVehicleSpinner(vehicles: List<VehicleSummary>, activeVin: String) {
        boundVehicles.clear()
        boundVehicles.addAll(vehicles)
        suppressVehicleSelection = true
        boundVehicleAdapter.clear()
        if (vehicles.isEmpty()) {
            boundVehicleAdapter.add("未发现绑定车辆")
        } else {
            boundVehicleAdapter.addAll(vehicles.map { vehicle ->
                val label = vehicle.plateNo.ifBlank { vehicle.seriesName.ifBlank { "Chery" } }
                "${vehicle.vin} · $label · ${vehicle.bleName.ifBlank { "BLE 未命名" }}"
            })
        }
        boundVehicleAdapter.notifyDataSetChanged()
        val activeIndex = vehicles.indexOfFirst { it.vin.equals(activeVin, ignoreCase = true) }
        if (activeIndex >= 0) boundVehicleSpinner.setSelection(activeIndex, false)
        suppressVehicleSelection = false
    }

    private fun maskToken(token: String): String = when {
        token.isBlank() -> "-"
        token.length <= 12 -> "***"
        else -> "${token.take(6)}…${token.takeLast(4)}"
    }

    private fun saveManualToken() {
        val globalId = tokenInput.text?.toString()?.trim().orEmpty()
        if (globalId.isBlank()) {
            toast("globalId 不能为空")
            return
        }
        val current = profileStore.getActiveProfile()
        val saved = profileStore.saveProfile(
            current.copy(accessToken = globalId),
            activate = current.vin.isNotBlank()
        )
        loadProfileIntoEditor(saved)
        appendLog("HTTP", "Manual globalId saved to ${saved.vin.ifBlank { "account draft" }}")
        toast("globalId 已保存，可继续执行 112 + 900 同步")
    }

    private fun fetchBoundVehiclesFromToken() {
        val globalId = tokenInput.text?.toString()?.trim()?.ifBlank {
            profileStore.getActiveProfile().accessToken
        }.orEmpty()
        if (globalId.isBlank()) {
            toast("请先登录或输入 globalId")
            return
        }
        val active = profileStore.getActiveProfile()
        val mobile = active.mobileNo.ifBlank { accountMobileInput.text?.toString()?.trim().orEmpty() }
        val deviceId = active.deviceId.ifBlank { computeDeviceId() }
        appendLog("HTTP", "REQUEST serviceType=112 sync-account globalId=${maskToken(globalId)}")
        lifecycleScope.launch {
            try {
                val user = withContext(Dispatchers.IO) { httpClient.fetchUserInfo(globalId) }
                appendLog("HTTP", "RESPONSE serviceType=112 userId=${user.userId} telephone=${user.telephone}\n${user.rawJson}")
                appendLog("HTTP", "REQUEST serviceType=900 vehicle-list globalId=${maskToken(globalId)}")
                val vehicles = withContext(Dispatchers.IO) { httpClient.fetchVehicleList(globalId) }
                appendLog(
                    "HTTP",
                    "RESPONSE serviceType=900 count=${vehicles.size}\n" +
                        vehicles.mapIndexed { index, vehicle -> "[$index] ${vehicle.rawJson}" }.joinToString("\n")
                )
                val activeSaved = applyAccountAndVehicleSnapshot(
                    user = user,
                    vehicles = vehicles,
                    mobile = user.telephone.ifBlank { mobile },
                    deviceId = deviceId,
                    accessToken = globalId,
                    sdkToken = active.sdkToken
                )
                maybeAutoProvision(activeSaved, "manual-sync")
                toast("112 + 900 同步完成，已保存 ${vehicles.size} 辆车辆")
            } catch (error: Throwable) {
                appendLog("HTTP", "FAILED serviceType=112/900: ${error.message ?: error.javaClass.simpleName}")
                toast("账号与车辆同步失败：${error.message}")
            }
        }
    }

    private fun activateBoundVehicle(summary: VehicleSummary) {
        val allProfiles = profileStore.listProfiles()
        val existing = allProfiles.firstOrNull { it.vin.equals(summary.vin, ignoreCase = true) }
        val current = profileStore.getActiveProfile()
        val base = existing ?: current
        val updated = base.copy(
            vin = summary.vin.trim().uppercase(Locale.US),
            userId = loginUserId.ifBlank { base.userId },
            mobileNo = base.mobileNo.ifBlank { accountMobileInput.text?.toString()?.trim().orEmpty() },
            bleName = summary.bleName.ifBlank { base.bleName },
            bleMac = summary.bleMac.trim().uppercase(Locale.US).ifBlank { base.bleMac },
            accountType = summary.accountType,
            accessToken = tokenInput.text?.toString()?.trim().orEmpty().ifBlank { base.accessToken },
            rootKeyHex = if (existing != null) existing.rootKeyHex else "",
            infoHex = if (existing != null) existing.infoHex else "",
            edfHex = if (existing != null) existing.edfHex else "",
            secAHex = if (existing != null) existing.secAHex else "",
            secBHex = if (existing != null) existing.secBHex else "",
            validFrom = if (existing != null) existing.validFrom else "",
            validTo = if (existing != null) existing.validTo else "",
            sdkToken = if (existing != null) existing.sdkToken else "",
            controlPin = if (existing != null) existing.controlPin else ""
        )
        val saved = profileStore.saveProfile(updated, activate = true)
        appendLog(
            "PROFILE",
            "Bound vehicle selected VIN=${saved.vin}, BLE=${saved.bleName}/${saved.bleMac}; " +
                "cloud key ${if (saved.rootKeyHex.isBlank()) "required" else "preserved"}"
        )
        reloadProfiles(saved.vin)
        maybeAutoProvision(saved, "vehicle-select")
    }

    private fun refreshBoundVehicleSelection(vin: String) {
        if (!::boundVehicleSpinner.isInitialized || boundVehicles.isEmpty()) return
        val index = boundVehicles.indexOfFirst { it.vin.equals(vin, ignoreCase = true) }
        if (index >= 0) {
            suppressVehicleSelection = true
            boundVehicleSpinner.setSelection(index, false)
            suppressVehicleSelection = false
        }
    }

    private fun refreshTelemetry() {
        val profile = profileStore.getActiveProfile()
        if (profile.accessToken.isBlank()) {
            toast("当前 Profile 缺少 Access Token，请先登录")
            return
        }
        appendLog("HTTP", "REQUEST serviceType=911 vehicle-status VIN=${profile.vin}, userId=${profile.userId}")
        lifecycleScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) { prepareSdkToken(profile) }
                val telemetry = withContext(Dispatchers.IO) {
                    httpClient.fetchVehicleStatus(
                        vin = prepared.vin,
                        accessToken = prepared.accessToken,
                        sdkToken = prepared.sdkToken,
                        userId = prepared.userId
                    )
                }
                telemetryView.text = formatTelemetry(telemetry)
                appendLog("HTTP", "RESPONSE serviceType=911 light-vehicle-status OK\n${telemetry.rawJson}")
                reloadProfiles(prepared.vin)
            } catch (error: Throwable) {
                appendLog("HTTP", "FAILED vehicle-status: ${error.message ?: error.javaClass.simpleName}")
                toast("刷新车况失败：${error.message}")
            }
        }
    }

    private fun runRemoteCommand(command: RemoteCommand) {
        val profile = profileStore.getActiveProfile()
        if (profile.accessToken.isBlank()) {
            toast("当前 Profile 缺少 globalId，请先登录")
            return
        }
        val pin = httpPinInput.text?.toString()?.trim().orEmpty()
        if (pin.isBlank()) {
            toast("请输入 6 位车辆安全密码")
            return
        }
        appendLog(
            "HTTP",
            "REQUEST serviceType=125 PIN check VIN=${profile.vin}, userId=${profile.userId}"
        )
        lifecycleScope.launch {
            try {
                val pinAccepted = withContext(Dispatchers.IO) {
                    httpClient.verifyVehiclePin(profile.vin, pin, profile.accessToken)
                }
                appendLog("HTTP", "RESPONSE serviceType=125 PIN accepted=$pinAccepted")
                if (!pinAccepted) {
                    toast("车辆安全密码校验未通过(125)")
                    return@launch
                }
                val prepared = profileStore.saveProfile(profile.copy(controlPin = pin), activate = true)
                val temperature = httpAcTempInput.text?.toString()?.trim()?.toIntOrNull()
                    ?.coerceIn(16, 30)
                    ?.toString()
                    ?: "24"
                val result = withContext(Dispatchers.IO) {
                    httpClient.sendRemoteCommand(
                        vin = prepared.vin,
                        userId = prepared.userId,
                        accessToken = prepared.accessToken,
                        sdkToken = prepared.sdkToken,
                        command = command,
                        pin = pin,
                        params = mapOf("temperature" to temperature)
                    )
                }
                appendLog(
                    "HTTP",
                    buildString {
                        appendLine("RESPONSE serviceType=970 remote-command ${command.name} success=${result.success}")
                        appendLine("requestId=${result.requestId} resultCode=${result.resultCode} seq=${result.seq}")
                        appendLine("message=${result.resultMessage.ifBlank { result.error.orEmpty() }}")
                        append(result.rawResponse)
                    }
                )
                toast(if (result.success) "${command.name} 请求已受理(970)" else "${command.name} 失败：${result.error}")
                reloadProfiles(prepared.vin)
            } catch (error: Throwable) {
                appendLog("HTTP", "FAILED remote-command ${command.name}: ${error.message ?: error.javaClass.simpleName}")
                toast("远控请求失败：${error.message}")
            }
        }
    }

    private fun prepareSdkToken(profile: VehicleProfile): VehicleProfile {
        if (profile.sdkToken.isNotBlank()) return profile
        val sdk = httpClient.exchangeSdkToken(
            accessToken = profile.accessToken,
            vin = profile.vin,
            verificationCode = profile.controlPin
        )
        appendLog("HTTP", "RESPONSE sdk-token OK userId=${sdk.userId}, expires=${sdk.sdkTokenExpire}")
        return profileStore.saveProfile(
            profile.copy(
                userId = sdk.userId.ifBlank { profile.userId },
                sdkToken = sdk.sdkToken
            ),
            activate = true
        )
    }

    private fun formatTelemetry(telemetry: VehicleTelemetry): String = buildString {
        appendLine("VIN: ${telemetry.vin} | 在线: ${telemetry.online.ifBlank { "-" }} | 上报时间: ${telemetry.time.ifBlank { "-" }}")
        appendLine("门锁: ${telemetry.doorLock.ifBlank { telemetry.doorLockState }} (${booleanLabel(telemetry.doorLocked)})")
        appendLine("车门: 左前=${telemetry.frontLeftDoor.ifBlank { "-" }}, 右前=${telemetry.frontRightDoor.ifBlank { "-" }}, 左后=${telemetry.backLeftDoor.ifBlank { "-" }}, 右后=${telemetry.backRightDoor.ifBlank { "-" }}, 引擎盖=${telemetry.hood.ifBlank { "-" }}")
        appendLine("车窗: 左前=${telemetry.frontLeftWindowState.ifBlank { "-" }}, 右前=${telemetry.frontRightWindowState.ifBlank { "-" }}, 左后=${telemetry.backLeftWindowState.ifBlank { "-" }}, 右后=${telemetry.backRightWindowState.ifBlank { "-" }}")
        appendLine("天窗: ${telemetry.sunroofState.ifBlank { telemetry.skylightState.ifBlank { "-" } }} | 尾门: ${telemetry.liftgateState.ifBlank { telemetry.tailgateState.ifBlank { "-" } }}")
        appendLine("后备箱: ${telemetry.trunkDoor.ifBlank { telemetry.trunkState.ifBlank { "-" } }} / lock=${telemetry.trunkLock.ifBlank { "-" }} (${booleanLabel(telemetry.trunkOpen)})")
        appendLine("发动机: ${telemetry.engineState.ifBlank { "-" }} (${booleanLabel(telemetry.engineRunning)}) | 空调: ${telemetry.airState.ifBlank { telemetry.acState.ifBlank { "-" } }} (${booleanLabel(telemetry.acOn)})")
        appendLine("蓄电池: ${telemetry.batteryVoltage?.let { "%.1f V".format(Locale.US, it) } ?: "-" } | 油量: ${telemetry.oilSurplus.ifBlank { telemetry.fuelPercent?.let { "%.1f%%".format(Locale.US, it) } ?: "-" }}")
        appendLine("电量: ${telemetry.electricitySurplus.ifBlank { "-" }} | 续航: ${telemetry.mileageSurplus.ifBlank { telemetry.remainingRangeKm?.let { "%.1f km".format(Locale.US, it) } ?: "-" }}")
        appendLine("里程: ${telemetry.odometer.ifBlank { telemetry.odometerKm?.let { "%.1f km".format(Locale.US, it) } ?: "-" }}")
        appendLine("胎压/胎温: LF=${telemetry.lFrontTyre.ifBlank { "-" }}, RF=${telemetry.rFrontTyre.ifBlank { "-" }}, LR=${telemetry.lRearTyre.ifBlank { "-" }}, RR=${telemetry.rRearTyre.ifBlank { "-" }}")
        append("GPS: ${telemetry.lat.ifBlank { telemetry.latitude?.toString().orEmpty().ifBlank { "-" } }}, ${telemetry.lon.ifBlank { telemetry.longitude?.toString().orEmpty().ifBlank { "-" } }}")
    }

    private fun booleanLabel(value: Boolean?): String = when (value) {
        true -> "是"
        false -> "否"
        null -> "未知"
    }

    // ---------------------------------------------------------------------
    // BLE scanning, connection and action dispatch
    // ---------------------------------------------------------------------

    private fun scanBleDevices() {
        withBlePermissions {
            scannedDevices.clear()
            updateBleDeviceAdapter()
            appendLog("BLE", "Scan START (CHR* / BLE_DK_* / InGeek / FFF0)")
            bleController.scanForDevices()
        }
    }

    @SuppressLint("MissingPermission")
    private fun toggleBleConnection() {
        if (bleConnected) {
            appendLog("BLE", "Disconnect requested")
            bleController.disconnect()
            return
        }
        withBlePermissions {
            try {
                val material = buildSessionMaterial()
                val profile = profileStore.getActiveProfile()
                val device = selectedBleDevice ?: run {
                    require(profile.bleMac.isNotBlank()) { "未选择扫描设备，且当前 Profile 没有 BLE MAC" }
                    BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(profile.bleMac)
                        ?: error("无法从 BLE MAC 创建 BluetoothDevice")
                }
                val adapter = BluetoothAdapter.getDefaultAdapter()
                require(adapter?.isEnabled == true) { "系统蓝牙未开启" }
                appendLog(
                    "BLE",
                    "Connect START ${device.address} material=${material.infoBin147.size}B/$material.edfBin.size/B; " +
                        "accountType=${material.accountBinding.accountType}"
                )
                bleController.connectAndAuthenticate(device, material)
            } catch (error: Throwable) {
                appendLog("BLE", "Connect preparation failed: ${error.message ?: error.javaClass.simpleName}")
                toast("连接准备失败：${error.message}")
            }
        }
    }

    private fun performBleAction(actionKey: String) {
        withBlePermissions {
            try {
                val material = buildSessionMaterial()
                val profile = profileStore.getActiveProfile()
                if (bleController.isAuthenticated) {
                    val accepted = bleController.sendVehicleAction(actionKey)
                    appendLog("BLE", "Action $actionKey accepted=$accepted")
                } else {
                    appendLog(
                        "BLE",
                        "Auto-connect requested for action=$actionKey target=${profile.bleMac}/${profile.bleName}"
                    )
                    bleController.executeActionWithAutoConnect(
                        actionKey = actionKey,
                        material = material,
                        targetAddress = profile.bleMac.takeIf { it.isNotBlank() },
                        targetName = profile.bleName.takeIf { it.isNotBlank() }
                    )
                }
            } catch (error: Throwable) {
                appendLog("BLE", "Action preparation failed: ${error.message ?: error.javaClass.simpleName}")
                toast("BLE 动作准备失败：${error.message}")
            }
        }
    }

    private fun sendCustomTlv() {
        if (!bleController.isAuthenticated) {
            toast("请先连接并完成 0x80 账号绑定，再发送自定义 TLV")
            return
        }
        val hex = customTlvInput.text?.toString()?.trim().orEmpty()
        if (hex.isBlank()) {
            toast("请输入自定义 TLV Hex")
            return
        }
        appendLog("BLE", "Custom TLV requested: $hex")
        val accepted = bleController.sendCustomTlv(hex)
        appendLog("BLE", "Custom TLV accepted=$accepted")
    }

    private fun buildSessionMaterial(): CheryBleController.SessionMaterial {
        val profile = profileStore.getActiveProfile()
        val root = profile.rootKeyBytes()
        val info = profile.infoBytes()
        val edf = profile.edfBytes()
        require(root.size == 16) { "K_root 必须为 16 字节，当前 ${root.size} 字节" }
        require(info.size == VckInfoParser.INFO_SIZE) { "info.bin 必须为 ${VckInfoParser.INFO_SIZE} 字节，当前 ${info.size} 字节" }
        require(edf.size in 68..74) { "edf.bin 必须为 68–74 字节，当前 ${edf.size} 字节" }
        require(profile.userId.isNotBlank()) { "UserID 为空，无法构造 0x80 账号绑定" }
        require(profile.deviceId.isNotBlank()) { "DeviceID 为空，无法构造 0x80 账号绑定" }
        return CheryBleController.SessionMaterial(
            rootKey16 = root,
            infoBin147 = info,
            edfBin = edf,
            accountBinding = CheryBleController.AccountBinding(
                accountType = profile.accountType.coerceIn(0, 1),
                userId = profile.userId,
                deviceId = profile.deviceId
            ),
            bleMac = profile.bleMac.takeIf { it.isNotBlank() },
            bleName = profile.bleName.takeIf { it.isNotBlank() }
        )
    }

    private fun updateBleDeviceAdapter() {
        if (!::bleDeviceAdapter.isInitialized) return
        bleDeviceAdapter.clear()
        if (scannedDevices.isEmpty()) {
            bleDeviceAdapter.add("尚未发现匹配设备")
        } else {
            scannedDevices.forEach { (address, device) ->
                val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "未知设备" }
                bleDeviceAdapter.add("$name · $address")
            }
        }
        bleDeviceAdapter.notifyDataSetChanged()
        if (selectedBleDevice == null || !scannedDevices.containsKey(selectedBleDevice?.address)) {
            selectedBleDevice = scannedDevices.values.firstOrNull()
        }
    }

    @SuppressLint("MissingPermission")
    private fun bindScannedDeviceToActiveProfile(device: BluetoothDevice) {
        val profile = profileStore.getActiveProfile()
        if (profile.bleName.isNotBlank() && profile.bleMac.isNotBlank()) return
        val scannedName = runCatching { device.name }.getOrNull()
            ?.trim()
            .orEmpty()
            .ifBlank { BlePairingPinCalculator.extractBleNameSuffix(device.address) ?: device.address }
        val updated = profile.copy(
            bleName = profile.bleName.ifBlank { scannedName },
            bleMac = profile.bleMac.ifBlank { device.address.uppercase(Locale.US) }
        )
        val saved = profileStore.saveProfile(updated, activate = updated.vin.isNotBlank())
        loadProfileIntoEditor(saved)
        if (saved.vin.isNotBlank()) reloadProfiles(saved.vin)
        appendLog(
            "PROFILE",
            "Bound scanned BLE device to active profile: name=${saved.bleName}, mac=${saved.bleMac}, VIN=${saved.vin.ifBlank { "draft" }}"
        )
    }

    private fun logoutAndClearLocalData() {
        httpClient.clearSession()
        runCatching { bleController.disconnect() }
        profileStore.clearAllProfiles()
        boundVehicles.clear()
        scannedDevices.clear()
        selectedBleDevice = null
        currentPinCandidates = null
        loginUserId = ""
        pendingBindingLoginMethod = null
        provisioningVin = null
        suppressVehicleSelection = true
        boundVehicleAdapter.clear()
        boundVehicleAdapter.add("尚未同步车辆")
        boundVehicleAdapter.notifyDataSetChanged()
        suppressVehicleSelection = false
        tokenInput.setText("")
        accountMobileInput.setText("")
        accountPasswordInput.setText("")
        accountSmsInput.setText("")
        httpPinInput.setText("")
        updateBleDeviceAdapter()
        val cleared = profileStore.getActiveProfile()
        reloadProfiles("")
        loadProfileIntoEditor(cleared)
        refreshTokenState(cleared)
        synchronized(logBuffer) { logBuffer.setLength(0) }
        if (::logView.isInitialized) logView.text = ""
        appendLog("AUTH", "Local session and all vehicle profiles cleared")
        toast("已退出登录并清空本地账号、车辆与数字钥匙数据")
    }

    private fun copyPrimaryPin() {
        val pin = currentPinCandidates?.primaryMacPin
        if (pin.isNullOrBlank()) {
            toast("尚无动态 PIN，请先扫描车辆")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CarPlus BLE pairing PIN", pin))
        appendLog("BLE", "Primary PIN copied to clipboard")
        toast("已复制动态配对 PIN")
    }

    // ---------------------------------------------------------------------
    // Runtime permissions and CheryBleController.Listener
    // ---------------------------------------------------------------------

    private fun requiredBlePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    private fun withBlePermissions(action: () -> Unit) {
        val missing = requiredBlePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
            return
        }
        pendingPermissionAction = action
        appendLog("PERM", "Requesting ${missing.joinToString()}")
        requestPermissions(missing.toTypedArray(), REQUEST_BLE_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLE_PERMISSIONS) return
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            appendLog("PERM", "BLE permissions granted")
            action?.invoke()
        } else {
            appendLog("PERM", "BLE permissions denied")
            toast("BLE 权限被拒绝，无法扫描、连接或自动配对")
        }
    }

    override fun onDeviceScanned(
        device: BluetoothDevice,
        rssi: Int,
        advRandomHex: String,
        candidatePins: PairingPinCandidates
    ) {
        val name = runCatching { device.name }.getOrNull()
        val profile = profileStore.getActiveProfile()
        val upperName = name?.uppercase(Locale.US).orEmpty()
        val relevant = upperName.startsWith("CHR") ||
            upperName.startsWith("BLE_DK_") ||
            upperName.contains("INGEEK") ||
            name.equals(profile.bleName, ignoreCase = true) ||
            device.address.equals(profile.bleMac, ignoreCase = true)
        currentPinCandidates = candidatePins
        pinPrimaryView.text = candidatePins.primaryMacPin
        pinDetailView.text = buildString {
            appendLine("Primary MAC PIN: ${candidatePins.primaryMacPin}")
            appendLine("BLE name suffix PIN: ${candidatePins.bleNameSuffixPin}")
            appendLine("Type-1 INGEEK PIN: ${candidatePins.type1IngeekPin}")
            appendLine("广播随机值: ${advRandomHex.ifBlank { candidatePins.randomHex }}")
            append("目标: ${name ?: "未知名称"} · ${device.address} · RSSI $rssi")
        }
        if (relevant || scannedDevices.isEmpty()) {
            val isNew = !scannedDevices.containsKey(device.address)
            scannedDevices[device.address] = device
            if (isNew) updateBleDeviceAdapter()
            bleStatusView.text = "BLE：扫描中 · ${name ?: "未知设备"} ${device.address} RSSI=$rssi"
            appendLog(
                "SCAN",
                "device=${name ?: "-"} addr=${device.address} rssi=$rssi advRandom=${advRandomHex.ifBlank { "-" }} " +
                    "pins=${candidatePins.primaryMacPin}/${candidatePins.bleNameSuffixPin}/${candidatePins.type1IngeekPin}"
            )
        }
    }

    override fun onHandshakeStageChanged(
        stageName: String,
        ffe1Hex: String?,
        auth02Done: Boolean,
        session03Done: Boolean,
        account80Done: Boolean
    ) {
        runOnUiThread {
            val failed = stageName.contains("错误")
            stageView.text = "阶段：$stageName · FFE1=${ffe1Hex ?: "-"}"
            stageView.setTextColor(if (failed) Color.parseColor("#B42318") else COLOR_TEXT)
            setPillState(ffe1Pill, "FFE1 (0x81)", ffe1Hex != null, failed && ffe1Hex == null)
            setPillState(auth02Pill, "0x02 Auth", auth02Done, failed && !auth02Done)
            setPillState(session03Pill, "0x03 Session", session03Done, failed && !session03Done)
            setPillState(account80Pill, "0x80 Binding", account80Done, failed && !account80Done)
            bleStatusView.text = "BLE：$stageName · FFE1=${ffe1Hex ?: "-"}"
        }
    }

    override fun onPacketLog(
        direction: String,
        msgIdHex: String,
        serial: Int,
        rawHex: String,
        decryptedSummary: String
    ) {
        appendLog(
            "BLE-$direction",
            "msg=$msgIdHex serial=$serial raw=${rawHex.ifBlank { "-" }} :: ${decryptedSummary.ifBlank { "-" }}"
        )
    }

    override fun onVehicleResponse(parsedResponse: ParsedTBoxResponse) {
        appendLog(
            "BLE-ACK",
            "msg=0x%02X success=%s service=%s result=%s error=%s :: %s".format(
                Locale.US,
                parsedResponse.messageId and 0xFF,
                parsedResponse.success,
                parsedResponse.serviceTag?.let { "0x%02X".format(Locale.US, it) } ?: "-",
                parsedResponse.resultCode?.toString() ?: "-",
                parsedResponse.errorCode?.toString() ?: "-",
                parsedResponse.summaryText
            )
        )
    }

    override fun onConnectionStateChanged(connected: Boolean, stateName: String) {
        runOnUiThread {
            bleConnected = connected
            if (!connected) bleAuthenticated = false
            connectButton.text = if (connected) "断开连接" else "连接 / 握手"
            connectButton.backgroundTintList = ColorStateList.valueOf(
                if (connected) Color.parseColor("#B42318") else Color.parseColor("#E4EEF9")
            )
            bleStatusView.text = "BLE：$stateName"
            appendLog("BLE", "Connection state: $stateName")
        }
    }

    override fun onAuthenticated(sessionSnapshot: SessionSnapshot) {
        runOnUiThread {
            bleAuthenticated = true
            bleStatusView.text = "BLE：已认证 · VIN=${sessionSnapshot.vin}"
            appendLog(
                "BLE",
                "AUTHENTICATED VIN=${sessionSnapshot.vin} mobile=${sessionSnapshot.mobileNo} " +
                    "txIv=${if (sessionSnapshot.txIvHex.isNullOrBlank()) "missing" else "present"} " +
                    "rxIv=${if (sessionSnapshot.rxIvHex.isNullOrBlank()) "missing" else "present"}"
            )
        }
    }

    override fun onActionDispatched(actionKey: String, serial: Int) {
        appendLog("BLE", "ACTION DISPATCHED action=$actionKey serial=$serial")
    }

    override fun onError(message: String, throwable: Throwable?) {
        appendLog("ERROR", "$message${throwable?.let { " :: ${it.message ?: it.javaClass.simpleName}" }.orEmpty()}")
        runOnUiThread { toast(message) }
    }

    // ---------------------------------------------------------------------
    // Logging and small utilities
    // ---------------------------------------------------------------------

    private fun appendLog(kind: String, message: String) {
        runOnUiThread {
            val timestamp = timeFormat.format(Date())
            synchronized(logBuffer) {
                logBuffer.append(timestamp).append(' ').append('[').append(kind).append("] ").append(message).append('\n')
                if (logBuffer.length > MAX_LOG_CHARS) {
                    val trimAt = logBuffer.indexOf("\n", logBuffer.length - MAX_LOG_CHARS)
                    if (trimAt >= 0) logBuffer.delete(0, trimAt + 1)
                }
                if (::logView.isInitialized) {
                    logView.text = logBuffer.toString()
                    logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun copyAllLogs() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = synchronized(logBuffer) { logBuffer.toString() }
        clipboard.setPrimaryClip(ClipData.newPlainText("CarPlus protocol logs", text))
        toast("全部日志已复制")
    }

    private fun clearLogs() {
        synchronized(logBuffer) { logBuffer.setLength(0) }
        if (::logView.isInitialized) logView.text = ""
        appendLog("SYS", "Log buffer cleared")
    }

    private fun computeDeviceId(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        return if (androidId.isBlank()) "" else VckInfoParser.computeCheryDeviceId(androidId)
    }

    private fun tokenInputIfReady(): EditText? = if (::tokenInput.isInitialized) tokenInput else null

    private fun httpPinInputIfReady(): EditText? = if (::httpPinInput.isInitialized) httpPinInput else null

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private abstract class SimpleItemSelectedListener : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private enum class ButtonStyle { PRIMARY, SECONDARY, SUCCESS, DANGER, CLOUD }

    companion object {
        private const val REQUEST_BLE_PERMISSIONS = 7011
        private const val MAX_LOG_CHARS = 120_000
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
        private val COLOR_PRIMARY = Color.rgb(11, 92, 173)
        private val COLOR_TEXT = Color.rgb(24, 38, 55)
        private val COLOR_MUTED = Color.rgb(91, 107, 125)
        private val COLOR_BORDER = Color.rgb(207, 216, 227)
    }
}
