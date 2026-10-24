# Android 风控指纹信号矩阵（第一步）

更新时间：2026-10-25。本文把公开 Android API、公开项目说明与本工程当前采集项对齐。矩阵描述“可观察证据”，不把单个字段直接解释为欺诈结论。

| 信号方向 | 公开来源与可核验事实 | 当前采集映射 | 状态与边界 |
|---|---|---|---|
| Root / 提权线索 | [RootBeer](https://github.com/scottyab/rootbeer) README 公开列出 test-keys、危险系统属性、`su`/BusyBox、Root 管理应用和可写系统分区等检查类别，并说明存在漏报和绕过限制。 | `virtualization/path*`、`virtualization/build-evidence`、`virtualization/build-markers`、`build/native-property/*`、`build/risk-properties-summary` | 已有证据采集；不扫描第三方应用清单形成结论，不输出“已 root”判定。 |
| 模拟器 / 虚拟化线索 | [Android Build](https://developer.android.com/reference/android/os/Build) 提供公开构建字段；RootBeer 类公开规则使用 `generic`、`goldfish`、`ranchu`、`qemu` 等字符串作为线索。 | `virtualization/build-evidence`、`virtualization/build-markers`、`virtualization/path*` | 已有线索采集；字符串或路径命中只能进入人工复核。 |
| Hook / 调试 / 篡改 | [Talsec Free-RASP Community](https://github.com/talsec/Free-RASP-Community) 项目说明公开包含 root、Magisk、Frida、模拟器、VPN、篡改等检测方向。 | `security/self` 的 `debuggerConnected`、`waitingForDebugger`；`security/self-integrity` 的签名摘要、debuggable、安装来源；`virtualization` 的公开路径和属性证据 | 已覆盖可由普通 App 公开读取的部分；Frida 等运行时检测不能从供应商描述反推出私有字段，暂不伪造。 |
| 网络环境 | [NetworkCapabilities](https://developer.android.com/reference/android/net/NetworkCapabilities) 公开 transport/capability，包括 VPN、VALIDATED、CAPTIVE_PORTAL、NOT_METERED、TRUSTED 等。 | `network/risk-signals`、`network/proxy`、`network/interfaces`、`network/wifi` | 已有活动链路快照；VPN、代理或私有 DNS 不自动等同于风险。 |
| 应用完整性与安装来源 | [PackageManager.getPackageInfo](https://developer.android.com/reference/android/content/pm/PackageManager#getPackageInfo(java.lang.String,%20int)) 与 [InstallSourceInfo](https://developer.android.com/reference/android/content/pm/InstallSourceInfo) 公开版本、签名和安装来源信息。 | `security/self-integrity`；包清单下的 `packages/*/installer` | 已有摘要；签名只输出 SHA-256，原始 DER 仅按现有数据契约保留。 |
| 远程设备完整性 | [Play Integrity verdicts](https://developer.android.com/google/play/integrity/verdicts) 描述 app/device/account/environment verdict 维度。 | `attestation/play-integrity-environment` 只记录本地 Play services/Play Store 环境；`attestation/play-integrity` 明确为 `skipped` | 未接入远程服务，不把本地依赖存在冒充 verdict。 |
| DRM / 设备唯一值摘要 | [MediaDrm](https://developer.android.com/reference/android/media/MediaDrm) 公开 DRM 能力和 `deviceUniqueId` 接口。 | `hardware/drm/widevine` | 已采集厂商、版本、算法、安全级别，以及唯一值 SHA-256 和长度；不写原始唯一值。 |
| 开发者与特殊权限 | [Settings.Global](https://developer.android.com/reference/android/provider/Settings.Global)、[Settings.Secure](https://developer.android.com/reference/android/provider/Settings.Secure)、[AppOpsManager](https://developer.android.com/reference/android/app/AppOpsManager) 公开 ADB、开发者选项、模拟位置、无障碍和当前 UID 的特殊访问状态。 | `environment/risk-settings`、`environment/global/*`、`environment/secure/*`、`security/special-access`、`security/accessibility` | 已采集；只读当前用户与当前 App 范围，不申请权限、不读取无障碍窗口内容。 |
| 设备管理与用户状态 | [DevicePolicyManager](https://developer.android.com/reference/android/app/admin/DevicePolicyManager) 与 [UserManager](https://developer.android.com/reference/android/os/UserManager) 公开设备/资料所有者、托管资料和用户解锁状态。 | `security/device-management` | 已采集当前 App 和当前用户范围，不枚举其他用户或管理包。 |
| 定位与 GNSS 环境 | [LocationManager](https://developer.android.com/reference/android/location/LocationManager) 公开定位总开关、provider 状态和 GNSS 能力/硬件元数据；这些接口不要求读取最后位置。 | `environment/location` | 已采集 provider 与 GNSS 元数据；不请求定位、不读取经纬度、轨迹或最后位置。 |

## 当前缺口与下一步候选

- 远程 Play Integrity verdict 需要服务端请求、nonce 和云端凭据；离线 APK 不应伪造该结果。
- 供应商 SDK 的私有字段没有公开契约；后续只实现能由公开 Android API、稳定 JNI 或明确授权的本地证据复现的项目。
- 已补充 `build/risk-properties-summary`，把 19 个启动验证、锁定状态、VBMeta 摘要、启动槽、动态分区、SELinux 启动状态、硬件 SKU/ABI、首次 API 级别以及 `ro.debuggable`、`ro.secure`、`ro.adb.secure` 等字段整理为固定摘要，并额外列出当前已观察与缺失的属性；原始 `build/native-property/*` 继续保留。
- 下一步优先评估摘要字段的 Android 版本可用性和前端差异展示，避免把缺失字段误报为异常。
