# Android 风控指纹公开来源（第一批）

更新时间：2026-10-25。本文只记录公开可核验的资料和可映射到普通 Android App 的信号，不把任何单一信号当作“欺诈结论”。

## 官方 Android 网络资料

- [ConnectivityManager](https://developer.android.com/reference/android/net/ConnectivityManager)：定义活动网络查询入口。
- [NetworkCapabilities](https://developer.android.com/reference/android/net/NetworkCapabilities)：公开 transport 与 capability，例如 VPN、VALIDATED、CAPTIVE_PORTAL、NOT_METERED、NOT_ROAMING、TRUSTED 等。
- 本工程新增 `network/risk-signals`，只保存活动链路的这些公开能力、是否计费、接口名和代理摘要；值仍由 `ItemSink` 独立记录状态，VPN 或代理不会被自动判定为恶意。
- 本工程新增 `network/connectivity-summary`，汇总可见网络数量、活动链路、transport、VALIDATED、VPN 和链路属性可用性，不复制地址、DNS、路由或原始字符串。

## 官方 Android 设备标识资料

- [Build](https://developer.android.com/reference/android/os/Build)：公开硬件、产品、指纹、ABI 等构建字段。
- [Settings.Secure.ANDROID_ID](https://developer.android.com/reference/android/provider/Settings.Secure#ANDROID_ID)：系统设置中的应用可见标识，生命周期和作用域受 Android 版本、用户和签名影响；本工程以原始值/状态形式保留，不宣称它是永久硬件 ID。
- [Play Integrity verdicts](https://developer.android.com/google/play/integrity/verdicts)：官方完整性服务的 verdict 维度；本工程当前保持离线采集，未接入远程 Play Integrity 请求，因此相关结论保持 `skipped/unknown`。
- [MediaDrm](https://developer.android.com/reference/android/media/MediaDrm)：公开 DRM 厂商、版本、算法、Widevine 支持和安全级别能力。本工程新增 `hardware/drm/widevine`，只保留 `deviceUniqueId` 的 SHA-256 与长度，原始唯一值永不写入指纹。
- [UsbManager](https://developer.android.com/reference/android/hardware/usb/UsbManager) 与 [UsbDevice](https://developer.android.com/reference/android/hardware/usb/UsbDevice)：公开当前 App 可见的 USB 设备描述符和接口结构。本工程新增 `hardware/usb`，限制 256 个设备，只记录厂商/产品 ID、类协议和接口数量，不申请设备权限、不读取序列号。
- [PackageManager.getSystemAvailableFeatures](https://developer.android.com/reference/android/content/pm/PackageManager#getSystemAvailableFeatures()) 与 [FeatureInfo](https://developer.android.com/reference/android/content/pm/FeatureInfo)：公开系统声明的硬件/软件特性、版本、标志和 OpenGL ES 要求版本。本工程新增 `hardware/features-summary`，同时保留 `hardware/features` 原始清单。
- [MediaCodecList](https://developer.android.com/reference/android/media/MediaCodecList) 与 [MediaCodecInfo](https://developer.android.com/reference/android/media/MediaCodecInfo)：公开媒体编解码器、支持的 MIME 类型和编码/解码方向，Android 29+ 还公开硬件加速、软件实现及厂商标记。本工程新增 `hardware/codecs-summary`，同时保留 `hardware/codecs` 原始能力。
- EGL、OpenGL ES 与 Vulkan 的公开运行时查询：本工程新增 `hardware/graphics-summary`，整理 EGL/GL 初始化、厂商、渲染器、版本、扩展长度与数量，以及最多 256 个 Vulkan 扩展名；完整 `hardware/graphics` 原始能力继续保留，避免把长扩展字符串重复展开到摘要。
- [CameraManager](https://developer.android.com/reference/android/hardware/camera2/CameraManager) 与 [CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics)：公开摄像头 ID、镜头朝向、硬件级别、传感器方向、闪光灯和逻辑多摄像头能力。本工程新增 `hardware/camera-summary`，同时保留 `hardware/camera/*` 原始特性，不读取图像帧。
- [InputDevice](https://developer.android.com/reference/android/view/InputDevice)：公开输入设备来源、虚拟/外接标记、键盘类型、运动范围以及麦克风/振动器能力。本工程新增 `input/summary`，同时保留 `input/device/*` 原始元数据，不读取输入事件。
- [StorageManager](https://developer.android.com/reference/android/os/storage/StorageManager) 与 [StorageVolume](https://developer.android.com/reference/android/os/storage/StorageVolume)：公开存储卷数量、挂载状态、主卷/可移除/模拟卷标志和 UUID 是否存在。本工程新增 `storage/summary`，同时保留容量、分区和卷原始条目，不导出 UUID 原文。
- [TelephonyManager](https://developer.android.com/reference/android/telephony/TelephonyManager) 与 [SubscriptionManager](https://developer.android.com/reference/android/telephony/SubscriptionManager)：公开电话类型、SIM 状态、运营商/国家、网络类型、漫游、数据开关和活动订阅元数据。本工程新增 `sim/summary`，摘要不复制 IMEI、IMSI、ICCID 或号码，原始条目仍显式记录权限边界。
- [BatteryManager](https://developer.android.com/reference/android/os/BatteryManager) 与 `ACTION_BATTERY_CHANGED`：公开电量、充电方式、健康、温度、电压和电池属性；Android 14+ 的 `EXTRA_CYCLE_COUNT` 提供电池循环次数。本工程新增 `battery/summary` 与 `battery/cycle-count`，同时保留广播、属性和 sysfs 原始条目，只读取快照；循环次数缺失不伪造为零。
- [DisplayManager](https://developer.android.com/reference/android/hardware/display/DisplayManager) 与 [Display](https://developer.android.com/reference/android/view/Display)：公开显示数量、有效性、真实尺寸、密度、刷新率、secure/presentation 标志、HDR 能力和 presentation 分类计数。本工程新增 `display/summary`，同时保留 `display/*` 原始元数据，不读取图像内容。
- [AudioManager.getDevices](https://developer.android.com/reference/android/media/AudioManager#getDevices(int)) 与 [AudioDeviceInfo](https://developer.android.com/reference/android/media/AudioDeviceInfo)：公开音频输入/输出设备类型、产品名、声道、采样率和编码格式。本工程新增 `hardware/audio-summary` 和 `hardware/audio`，只记录地址存在性，不导出地址或音频样本。
- [Vibrator](https://developer.android.com/reference/android/os/Vibrator) 与 [VibratorManager](https://developer.android.com/reference/android/os/VibratorManager)：公开振动器数量、振动存在性、振幅控制和 API 30+ 的效果支持码。本工程新增 `hardware/vibrator`，只查询能力，不触发振动。
- [NfcAdapter](https://developer.android.com/reference/android/nfc/NfcAdapter)：公开 NFC 适配器是否存在/启用、Secure NFC 支持与状态，以及 API 34+ 天线几何元数据。本工程新增 `hardware/nfc`，不启停适配器、不读取标签内容。
- [BluetoothManager](https://developer.android.com/reference/android/bluetooth/BluetoothManager)、[BluetoothAdapter](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter) 与 [BluetoothDevice](https://developer.android.com/reference/android/bluetooth/BluetoothDevice)：公开适配器状态、已配对设备类型和设备类别；Android 12+ 的设备清单可能需要 `BLUETOOTH_CONNECT`。本工程新增 `network/bluetooth-summary`，权限不足时显式返回 `access=denied`。
- [BluetoothAdapter LE capability APIs](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#isLe2MPhySupported())：公开 2M/Coded PHY、扩展广播和最大广播数据长度能力。本工程新增 `network/bluetooth-capabilities`，只查询适配器能力，不读取地址、名称或广播内容。
- [WifiManager.getConnectionInfo](https://developer.android.com/reference/android/net/wifi/WifiManager#getConnectionInfo())、[WifiInfo](https://developer.android.com/reference/android/net/wifi/WifiInfo) 与 [WifiManager.getScanResults](https://developer.android.com/reference/android/net/wifi/WifiManager#getScanResults())：公开当前 Wi-Fi 链路、Wi-Fi 标准、最大收发速率、API 33+ MLO 链路摘要和系统已有缓存扫描结果。本工程新增 `network/wifi-summary` 与 `network/wifi-capabilities`，只记录连接状态、频段、链路能力和 MLD 地址存在性，不主动扫描、不在摘要中导出 SSID/BSSID/MLD MAC；前端对 `network/wifi-cached-scan` 以计数、频率和能力标记摘要展示，原始数组仍保留。
- [WebView.getCurrentWebViewPackage](https://developer.android.com/reference/android/webkit/WebView#getCurrentWebViewPackage())：公开当前设备选择的 WebView 提供方包和版本。本工程新增 `environment/webview-provider`，同时保留提供方签名摘要，用于人工核对运行环境，不把版本或签名直接判定为风险。
- [WebSettings.getDefaultUserAgent](https://developer.android.com/reference/android/webkit/WebSettings#getDefaultUserAgent(android.content.Context))：公开当前 WebView 默认 User-Agent。本工程新增 `environment/webview-user-agent`，保留原始 UA 与长度供对照，不把 UA 单独当作设备真实性结论。
- [PowerManager](https://developer.android.com/reference/android/os/PowerManager)：公开当前 App 可查询的省电、交互/空闲、热状态和电池优化豁免状态。本工程新增 `environment/power-thermal`，只做快照不控制系统策略；电池优化查询受特殊权限约束时以 `null + batteryOptimizationQueryStatus=denied` 表示，不误报为未豁免。
- [PowerManager.isLowPowerStandbyEnabled](https://developer.android.com/reference/android/os/PowerManager#isLowPowerStandbyEnabled())、[isExemptFromLowPowerStandby](https://developer.android.com/reference/android/os/PowerManager#isExemptFromLowPowerStandby()) 与 [isRebootingUserspaceSupported](https://developer.android.com/reference/android/os/PowerManager#isRebootingUserspaceSupported())：公开低功耗待机开关、当前 App 豁免状态和用户空间重启能力。本工程新增 `environment/low-power-standby`，只读取能力，不改变系统策略。
- [Settings.Global](https://developer.android.com/reference/android/provider/Settings.Global) 与 [Settings.Secure](https://developer.android.com/reference/android/provider/Settings.Secure)：公开当前用户可见的开发者、ADB、时间同步、私有 DNS、无障碍和模拟位置设置。本工程新增 `environment/risk-settings` 可读摘要，同时保留原始键值，不在摘要中重复导出 `ANDROID_ID`。
- [Settings.System](https://developer.android.com/reference/android/provider/Settings.System)：公开屏幕亮度、自动亮度模式、熄屏超时、旋转和动画缩放等系统设置。本工程新增 `environment/system-settings` 摘要与 `environment/system/*` 原始键值，只读固定键集合，不申请写入权限。
- [ActivityManager](https://developer.android.com/reference/android/app/ActivityManager)、[UserManager](https://developer.android.com/reference/android/os/UserManager) 与公开设备初始化 Settings：本工程新增 `environment/device-state`，记录设备/用户初始化、低内存、演示用户、用户解锁与运行、启动次数和测试 harness 状态；这些是环境证据，不自动解释为风险结论。
- [UserManager.getUserProfiles](https://developer.android.com/reference/android/os/UserManager#getUserProfiles()) 与 `isManagedProfile`：公开当前用户可见的资料数量及当前上下文的托管资料状态。本工程新增 `environment/user-profiles`，只记录数量和布尔值，不导出用户句柄、账号或个人内容。
- [UsageStatsManager.getAppStandbyBucket](https://developer.android.com/reference/android/app/usage/UsageStatsManager#getAppStandbyBucket())：公开当前 App 的待机分桶。本工程新增 `environment/app-standby`，只读取自身分桶并保留 API/服务/权限边界，不查询其它应用的使用记录。
- [ActivityManager.getHistoricalProcessExitReasons](https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,%20int,%20int)) 与 [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo)：公开当前 App 最近进程退出的原因、状态、重要性和资源快照。本工程新增 `environment/process-exits`，最多保留 32 条元数据，不读取描述文本或 trace，进程状态摘要只保存长度与 SHA-256。
- [Configuration](https://developer.android.com/reference/android/content/res/Configuration)、[DateFormat.is24HourFormat](https://developer.android.com/reference/android/text/format/DateFormat#is24HourFormat(android.content.Context)) 与 API 33+ [LocaleManager](https://developer.android.com/reference/android/app/LocaleManager)：公开系统语言列表、默认区域、时区、24 小时制、布局方向以及应用级语言覆盖。本工程新增 `environment/locale-summary`，保留可比较的区域设置摘要，完整 Configuration 仍在 `environment/locales`，不读取位置或用户内容。
- [`SystemClock`](https://developer.android.com/reference/android/os/SystemClock)：公开 `elapsedRealtime`、`uptimeMillis` 与墙上时钟的单次读数。本工程新增 `environment/clock-summary`，计算近似开机时间和累计休眠时长；时间字段只用于环境线索，不作为稳定设备标识，旧的 `environment/clocks` 原始条目继续兼容保留。
- [Linux `/proc/<pid>/status`](https://man7.org/linux/man-pages/man5/proc_pid_status.5.html)：公开当前进程的调试、线程、Seccomp、`NoNewPrivs` 和能力元数据。本工程新增 `kernel/process-status-summary`，只提取有限字段并保留原始文件；它只能作为运行环境线索，不能据此宣称检测到某个具体 Hook 工具。
- [Linux `/proc/<pid>/mountinfo`](https://man7.org/linux/man-pages/man5/proc_pid_mountinfo.5.html)：公开进程挂载命名空间中的文件系统类型、只读标志与 shared/master 关系。本工程新增 `kernel/mount-summary`，整理为计数和根文件系统类型，原始条目继续保留且不把 overlay 直接解释为容器或 Root。
- [DevicePolicyManager](https://developer.android.com/reference/android/app/admin/DevicePolicyManager) 与 [UserManager](https://developer.android.com/reference/android/os/UserManager)：公开当前 App 的设备/资料所有者状态、当前用户是否为托管资料、用户解锁状态和有限用户限制。本工程新增 `security/device-management`，只记录当前 App 与当前用户范围，不枚举其它管理包，不自动判定风险。
- [AppOpsManager](https://developer.android.com/reference/android/app/AppOpsManager)、[PackageManager.canRequestPackageInstalls](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())、[NotificationManager](https://developer.android.com/reference/android/app/NotificationManager) 与 [Settings.canDrawOverlays](https://developer.android.com/reference/android/provider/Settings#canDrawOverlays(android.content.Context))：公开当前 UID 的特殊访问、未知来源安装、通知策略、全量存储、悬浮窗以及相机/录音/传感器/位置操作模式。本工程的 `security/special-access` 只读取这些状态和无副作用的 `checkOpNoThrow`，不申请权限、不调用 `noteOp`；接口因权限声明不足时保留字段并以对应 `*Status=denied` 和 `null` 值表示受限。
- 本工程新增 `security/summary`，汇总调试、设备管理、AppOps、锁屏/生物识别、无障碍数量和安全提供者数量，不复制证书、序列号或窗口内容。
- [KeyguardManager](https://developer.android.com/reference/android/app/KeyguardManager) 与 [PackageManager 系统特性](https://developer.android.com/reference/android/content/pm/PackageManager#hasSystemFeature(java.lang.String))：公开锁屏是否安全、当前锁定状态及设备声明的指纹/面部/虹膜能力。本工程新增 `security/lock-biometric`，只记录能力和状态，不读取生物特征或模板。
- [BiometricManager](https://developer.android.com/reference/android/hardware/biometrics/BiometricManager)：公开强/弱生物识别和设备凭据的认证环境结果。本工程在 API 30+ 记录原始结果码与可读状态，只调用 `canAuthenticate`，不弹认证框、不读取生物特征或模板。
- [AccessibilityManager](https://developer.android.com/reference/android/view/accessibility/AccessibilityManager) 与 [AccessibilityServiceInfo](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo)：公开已启用无障碍服务的组件、反馈类型和能力位。本工程新增 `security/accessibility`，只读取服务元数据，不读取窗口内容、文本或用户操作。
- [PackageManager.getPackageInfo](https://developer.android.com/reference/android/content/pm/PackageManager#getPackageInfo(java.lang.String,%20int))、[SigningInfo](https://developer.android.com/reference/android/content/pm/SigningInfo)、[InstallSourceInfo](https://developer.android.com/reference/android/content/pm/InstallSourceInfo)、`isInstantApp` 与 `isPackageSuspended`：公开当前 App 自身的版本、当前/历史签名、安装来源、即时应用和暂停状态。本工程的 `security/self-integrity` 只保留签名 SHA-256 与历史签名存在标记，不导出证书 DER，不枚举其它包；暂停状态查询受限时保留字段级状态。
- [PackageManager.getInstalledPackages](https://developer.android.com/reference/android/content/pm/PackageManager#getInstalledPackages(int))：公开当前 App 按包可见性策略可见的应用版本、组件、权限和应用标志。本工程的 `packages/summary` 还统计涉及包数、权限授予标志和最多 256 个唯一权限名，同时保留 `packages/*` 原始条目。
- Play Integrity 的本地依赖摘要位于 `attestation/play-integrity-environment`，仅记录 Google Play services 与 Play 商店的存在、版本、安装来源和签名 SHA-256；`verdictAvailable=false`，不把本地包状态冒充远程完整性结论。

## GitHub 上的公开 SDK/实现

- [scottyab/rootbeer](https://github.com/scottyab/rootbeer)：README 列出 root 管理应用、危险应用、root cloaking、test-keys、危险属性、BusyBox、`su` 等检查，并明确说明这些规则有漏报和绕过限制。本工程只复用可观察证据（构建标记、属性、路径），不扫描或上传第三方应用清单作为结论。
- [talsec/Free-RASP-Community](https://github.com/talsec/Free-RASP-Community)：公开项目说明其检测范围包含 root/Magisk、Frida、模拟器、VPN、篡改和设备识别。仓库描述是能力分类证据，不能推导其私有实现字段；本工程将 VPN/模拟器/完整性拆成可复核条目。
- [fingerprintjs/fingerprintjs-pro-android](https://github.com/fingerprintjs/fingerprintjs-pro-android)：官方 Android SDK 仓库公开说明提供 Device Intelligence 与 Smart Signals。其具体信号由服务端返回，未在本工程复制未公开字段，后续仅作为供应商能力对照。
- `virtualization/risk-summary` 汇总 RootBeer/Talsec 类公开检查的路径存在性、构建字符串标记和 `test-keys` 线索；逐路径 JNI 结果仍保留，摘要只标记 `evidenceOnly`，不形成自动风控结论。

## 技术论坛交叉核对

- [Stack Overflow：Device identifier of Android emulator](https://stackoverflow.com/questions/4402262/device-identifier-of-android-emulator)
- [Stack Overflow：When multiple users on android, is the ANDROID_ID unique for each user?](https://stackoverflow.com/questions/20876085/when-multiple-users-on-android-is-the-android-id-unique-for-each-user)

论坛内容只作为边界案例索引；涉及标识生命周期时以 Android 官方文档为准，不能用论坛答案替代系统契约。
