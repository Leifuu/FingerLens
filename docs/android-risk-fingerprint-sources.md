# Android 风控指纹公开来源（第一批）

更新时间：2026-10-24。本文只记录公开可核验的资料和可映射到普通 Android App 的信号，不把任何单一信号当作“欺诈结论”。

## 官方 Android 网络资料

- [ConnectivityManager](https://developer.android.com/reference/android/net/ConnectivityManager)：定义活动网络查询入口。
- [NetworkCapabilities](https://developer.android.com/reference/android/net/NetworkCapabilities)：公开 transport 与 capability，例如 VPN、VALIDATED、CAPTIVE_PORTAL、NOT_METERED、NOT_ROAMING、TRUSTED 等。
- 本工程新增 `network/risk-signals`，只保存活动链路的这些公开能力、是否计费、接口名和代理摘要；值仍由 `ItemSink` 独立记录状态，VPN 或代理不会被自动判定为恶意。

## 官方 Android 设备标识资料

- [Build](https://developer.android.com/reference/android/os/Build)：公开硬件、产品、指纹、ABI 等构建字段。
- [Settings.Secure.ANDROID_ID](https://developer.android.com/reference/android/provider/Settings.Secure#ANDROID_ID)：系统设置中的应用可见标识，生命周期和作用域受 Android 版本、用户和签名影响；本工程以原始值/状态形式保留，不宣称它是永久硬件 ID。
- [Play Integrity verdicts](https://developer.android.com/google/play/integrity/verdicts)：官方完整性服务的 verdict 维度；本工程当前保持离线采集，未接入远程 Play Integrity 请求，因此相关结论保持 `skipped/unknown`。
- [MediaDrm](https://developer.android.com/reference/android/media/MediaDrm)：公开 DRM 厂商、版本、算法、Widevine 支持和安全级别能力。本工程新增 `hardware/drm/widevine`，只保留 `deviceUniqueId` 的 SHA-256 与长度，原始唯一值永不写入指纹。
- [UsbManager](https://developer.android.com/reference/android/hardware/usb/UsbManager) 与 [UsbDevice](https://developer.android.com/reference/android/hardware/usb/UsbDevice)：公开当前 App 可见的 USB 设备描述符和接口结构。本工程新增 `hardware/usb`，限制 256 个设备，只记录厂商/产品 ID、类协议和接口数量，不申请设备权限、不读取序列号。
- [WebView.getCurrentWebViewPackage](https://developer.android.com/reference/android/webkit/WebView#getCurrentWebViewPackage())：公开当前设备选择的 WebView 提供方包和版本。本工程新增 `environment/webview-provider`，同时保留提供方签名摘要，用于人工核对运行环境，不把版本或签名直接判定为风险。
- [WebSettings.getDefaultUserAgent](https://developer.android.com/reference/android/webkit/WebSettings#getDefaultUserAgent(android.content.Context))：公开当前 WebView 默认 User-Agent。本工程新增 `environment/webview-user-agent`，保留原始 UA 与长度供对照，不把 UA 单独当作设备真实性结论。
- [DevicePolicyManager](https://developer.android.com/reference/android/app/admin/DevicePolicyManager) 与 [UserManager](https://developer.android.com/reference/android/os/UserManager)：公开当前 App 的设备/资料所有者状态、当前用户是否为托管资料、用户解锁状态和有限用户限制。本工程新增 `security/device-management`，只记录当前 App 与当前用户范围，不枚举其它管理包，不自动判定风险。
- [AppOpsManager](https://developer.android.com/reference/android/app/AppOpsManager) 与 [Settings.canDrawOverlays](https://developer.android.com/reference/android/provider/Settings#canDrawOverlays(android.content.Context))：公开当前 UID 的特殊访问模式和悬浮窗状态。本工程新增 `security/special-access`，只调用无副作用的 `checkOpNoThrow`，不申请权限、不调用 `noteOp`。
- [KeyguardManager](https://developer.android.com/reference/android/app/KeyguardManager) 与 [PackageManager 系统特性](https://developer.android.com/reference/android/content/pm/PackageManager#hasSystemFeature(java.lang.String))：公开锁屏是否安全、当前锁定状态及设备声明的指纹/面部/虹膜能力。本工程新增 `security/lock-biometric`，只记录能力和状态，不读取生物特征或模板。
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
