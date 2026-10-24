import { describe, expect, it } from 'vitest';
import { buildSections } from './display';
import type { Item, Json } from './core/types';

function item(key: string, category: string, value: Json): Item {
  return { key, category, value, name: key, dataType: 'object', status: 'ok', source: { kind: 'api', api: key, uid: 10001, pid: 1, selinux: 'untrusted_app' } };
}

describe('设备格式化展示边界', () => {
  it('只展示 getprop 输出，保留重复属性和空属性值', () => {
    const value = item('build/getprop', 'build', { argv: ['/system/bin/getprop'], stdout: '[a]: []\n[a]: [two]\n', stderr: 'not-device-output', exitCode: 0 });
    expect(buildSections(value)[0].rows.map(row => row.value)).toEqual(['', 'two']);
    expect(JSON.stringify(buildSections(value))).not.toContain('argv');
  });
  it('应用枚举不把固定范围文案当作设备返回值', () => {
    const value = item('packages/enumeration', 'packages', { flags: 123, returnedCount: 144, visibility: '固定说明' });
    const original = JSON.stringify(value);
    expect(buildSections(value)[0].rows.map(row => row.key)).toEqual(['returnedCount']);
    expect(JSON.stringify(value)).toBe(original);
  });
  it('应用清单摘要展示包环境统计并保留旧枚举条目', () => {
    const sections = buildSections(item('packages/summary', 'packages', { returnedCount: 3, applicationInfoCount: 3, enabledCount: 2, debuggableCount: 1, systemAppCount: 2, updatedSystemAppCount: 1, nativeCodeCount: 2, splitPackageCount: 1, sharedUserIdCount: 0, versionNameCount: 3, requestedPermissionCount: 8, permissionPackageCount: 2, grantedRequestedPermissionCount: 4, uniqueRequestedPermissionCount: 3, requestedPermissionNames: ['android.permission.CAMERA', 'android.permission.INTERNET'], requestedPermissionNamesTruncated: false, activityCount: 4, receiverCount: 2, serviceCount: 3, providerCount: 1, targetSdkCounts: { '35': 2, '37': 1 } }));
    expect(sections[0].title).toBe('应用清单摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('debuggableCount');
    expect(sections[0].rows.map(row => row.key)).toContain('targetSdkCounts.35');
    expect(sections.find(section => section.key === 'requestedPermissionNames')?.title).toContain('最多 256 项');
  });
  it('输入设备摘要展示虚拟化与能力计数', () => {
    const sections = buildSections(item('input/summary', 'input', { count: 2, virtualCount: 1, externalCount: 0, microphoneCount: 1, vibratorCount: 1, motionRangeCount: 3, accessErrorCount: 0, keyboardTypeCounts: { '1': 2 }, sourceCounts: { '16777232': 1 } }));
    expect(sections[0].title).toBe('输入设备能力摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('virtualCount');
    expect(sections[0].rows.map(row => row.key)).toContain('keyboardTypeCounts.1');
  });
  it('显示摘要展示能力计数并保留尺寸数组', () => {
    const sections = buildSections(item('display/summary', 'display', { count: 2, validCount: 2, secureCount: 2, presentationCount: 0, presentationCategoryCount: 0, hdrCount: 1, widths: [1080, 1920], heights: [2400, 1080], densityDpi: [420], refreshRates: [60, 120] }));
    expect(sections[0].title).toBe('显示能力摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('hdrCount');
    expect(sections.find(section => section.key === 'widths')?.title).toBe('显示宽度（px）');
    expect(sections.find(section => section.key === 'refreshRates')?.rows.map(row => row.value)).toEqual(['60', '120']);
  });
  it('屏幕字段只属于对应子面板，支持模式和 HDR 数组不遗漏', () => {
    const value = item('display/0', 'display', { displayId: 0, name: '内置屏幕', widthPixels: 720, refreshRate: 60, supportedModes: [{ modeId: 1, physicalWidth: 720 }], presentationDeadlineNanos: { $type: 'int64', value: '9007199254740993' }, hdr: { types: [1], minLuminance: 0 }, futureField: null });
    const sections = buildSections(value);
    expect(sections.find(section => section.key === 'identity')?.rows.map(row => row.key)).toEqual(['displayId', 'name']);
    expect(sections.find(section => section.key === 'geometry')?.rows.map(row => row.key)).toEqual(['widthPixels', 'refreshRate']);
    expect(sections.find(section => section.key === 'modes')?.rows[1].value).toBe('720');
    expect(sections.find(section => section.key === 'hdr')?.rows.map(row => row.key)).toContain('hdr.types[0]');
    expect(sections.find(section => section.key === 'sync')?.rows[0].value).toBe('9007199254740993');
    expect(JSON.stringify(sections)).toContain('futureField');
  });
  it('数组逐字段保留空值、嵌套数据、顺序与重复项', () => {
    const value = item('sensors/list', 'sensors', [{ name: '<script>', values: [null, '中文\n'] }, { name: '<script>', values: [] }]);
    const sections = buildSections(value);
    expect(sections[0].table).toBe(true);
    expect(sections[0].rows.map(row => row.value)).toEqual(['<script>', 'null', '中文\n', '<script>', '[]']);
  });
  it('合并包记录保留安装来源字段', () => {
    const value = item('packages/test', 'packages', { packageName: 'test' });
    value.installer = item('packages/test/installer', 'packages', { installingPackageName: 'installer' });
    expect(buildSections(value).some(section => section.key.startsWith('installer-') && section.rows[0].value === 'installer')).toBe(true);
  });
  it('网络对象中的 DNS 与地址数组不丢失', () => {
    const sections = buildSections(item('network/active', 'network', { connected: true, dns: ['1.1.1.1'], routes: [] }));
    expect(sections.find(section => section.key === 'dns')?.rows[0].value).toBe('1.1.1.1');
    expect(sections.find(section => section.key === 'routes')?.rows[0].value).toBe('[]');
  });
  it('Wi-Fi 缓存扫描只展开摘要，不展开 SSID 与 BSSID', () => {
    const value = item('network/wifi-cached-scan', 'network', [{ ssid: 'Cafe', bssid: 'AA:BB:CC:DD:EE:FF', capabilities: '[WPA2-PSK-CCMP][ESS]', frequency: 2412, level: -45, channelWidth: 0 }, { ssid: '', bssid: '', capabilities: '[ESS]', frequency: 5180, level: -70, channelWidth: 1 }]);
    const original = JSON.stringify(value);
    const sections = buildSections(value);
    expect(sections[0].title).toContain('原始 SSID/BSSID 保留');
    expect(sections[0].rows.find(row => row.key === 'count')?.value).toBe('2');
    expect(sections.find(section => section.key === 'frequencies')?.rows.map(row => row.value)).toEqual(['2412', '5180']);
    expect(JSON.stringify(sections)).not.toContain('Cafe');
    expect(JSON.stringify(sections)).not.toContain('AA:BB:CC:DD:EE:FF');
    expect(JSON.stringify(value)).toBe(original);
  });
  it('风控摘要使用专用分组并保留数组与原始链路字符串', () => {
    const sections = buildSections(item('network/risk-signals', 'network', { present: true, validated: true, vpn: false, transports: [1], capabilitiesRaw: 'raw-capabilities' }));
    expect(sections.find(section => section.key === 'summary')?.title).toBe('活动链路摘要');
    expect(sections.find(section => section.key === 'transports')?.rows[0].value).toBe('1');
    expect(sections.find(section => section.key === 'capabilitiesRaw')?.rows[0].value).toBe('raw-capabilities');
  });
  it('网络集合摘要展示所有链路能力计数', () => {
    const sections = buildSections(item('network/connectivity-summary', 'network', { available: true, networkCount: 2, activePresent: true, activeIndex: 0, activeNetworkHandle: { $type: 'int64', value: '42' }, capabilitiesAvailableCount: 2, linkPropertiesAvailableCount: 2, validatedCount: 1, vpnCount: 1, notMeteredCount: 2, transportCounts: { '1': 1, '4': 1 } }));
    expect(sections[0].title).toBe('网络集合摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('transportCounts.1');
    expect(sections[0].rows.find(row => row.key === 'activeNetworkHandle')?.value).toBe('42');
  });
  it('安全摘要展示调试、设备管理和访问边界', () => {
    const sections = buildSections(item('security/summary', 'security', { selfDebuggable: false, debuggerConnected: false, waitingForDebugger: false, deviceOwnerForSelf: false, profileOwnerForSelf: false, managedProfile: false, userUnlocked: true, specialAccessAvailable: true, appOpsCheckedCount: 5, appOpsAllowedCount: 1, canDrawOverlays: false, keyguardSecure: true, keyguardLocked: false, deviceSecure: true, deviceLocked: false, fingerprintFeature: true, faceFeature: false, irisFeature: false, accessibilityServiceCount: 0, securityProviderCount: 4 }));
    expect(sections[0].title).toBe('安全环境摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('appOpsAllowedCount');
    expect(sections[0].rows.find(row => row.key === 'securityProviderCount')?.value).toBe('4');
  });
  it('密钥认证展示能力与长度，不展开 Base64 证书链', () => {
    const value = item('attestation/tee', 'attestation', { requestedAttestation: true, requestedStrongBox: false, algorithm: 'SHA256withECDSA', insideSecureHardware: true, securityLevel: 2, challengeBase64: 'C'.repeat(128), signatureBase64: 'S'.repeat(160), publicKeyBase64: 'P'.repeat(88), certificateChainBase64: ['A'.repeat(512), 'B'.repeat(256)] });
    const original = JSON.stringify(value);
    const sections = buildSections(value);
    expect(sections[0].title).toBe('TEE 密钥认证');
    expect(sections.find(section => section.key === 'encoded')?.rows.find(row => row.key === 'challengeBase64.length')?.value).toBe('128');
    expect(JSON.stringify(sections)).not.toContain('C'.repeat(32));
    expect(JSON.stringify(sections)).not.toContain('A'.repeat(32));
    expect(JSON.stringify(value)).toBe(original);
  });
  it('原生风控属性摘要保持固定字段与可读分组', () => {
    const sections = buildSections(item('build/risk-properties-summary', 'build', { observedCount: 4, knownCount: 19, observedProperties: ['ro.debuggable'], missingProperties: ['ro.boot.flash.locked'], properties: { 'ro.boot.verifiedbootstate': 'green', 'ro.debuggable': '0', 'ro.adb.secure': '1' } }));
    expect(sections[0].title).toBe('风控原生属性摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['observedCount', 'knownCount', 'properties.ro.boot.verifiedbootstate', 'properties.ro.debuggable', 'properties.ro.adb.secure']);
    expect(sections.map(section => section.title)).toEqual(['风控原生属性摘要', '已观察属性', '当前缺失属性']);
  });
  it('旧版原生风控摘要缺少新增数组时仍可展示', () => {
    const sections = buildSections(item('build/risk-properties-summary', 'build', { observedCount: 2, knownCount: 11, properties: { 'ro.debuggable': '0', 'ro.adb.secure': '1' } }));
    expect(sections.map(section => section.title)).toEqual(['风控原生属性摘要']);
    expect(sections[0].rows.map(row => row.key)).toContain('properties.ro.debuggable');
  });
  it('Widevine 与 Play Integrity 摘要不展开成大段 JSON', () => {
    const drm = buildSections(item('hardware/drm/widevine', 'hardware', { supported: true, vendor: 'Google', deviceUniqueId: { sha256: 'a'.repeat(64), length: 32, rawExported: false } }));
    const integrity = buildSections(item('attestation/play-integrity-environment', 'attestation', { verdictAvailable: false, playServices: { present: true, versionName: '1' } }));
    expect(drm[0].title).toBe('Widevine DRM 能力');
    expect(drm[0].rows.some(row => row.key.includes('deviceUniqueId.sha256'))).toBe(true);
    expect(integrity[0].title).toBe('Play Integrity 本地环境');
  });
  it('设备管理摘要保留当前 App 范围和限制项', () => {
    const sections = buildSections(item('security/device-management', 'security', { deviceOwnerForSelf: false, profileOwnerForSelf: false, managedProfile: false, userUnlocked: true, restrictions: { 'no_debugging_features': false } }));
    expect(sections[0].title).toBe('设备管理与用户环境');
    expect(sections[0].rows.some(row => row.key.includes('deviceOwnerForSelf'))).toBe(true);
    expect(sections[0].rows.some(row => row.key.includes('restrictions.no_debugging_features'))).toBe(true);
  });
  it('特殊访问摘要保留 AppOps 与悬浮窗状态', () => {
    const sections = buildSections(item('security/special-access', 'security', { available: true, canDrawOverlays: false, appOps: { 'android:system_alert_window': { mode: 1, allowed: false } } }));
    expect(sections[0].title).toBe('本 App 特殊访问状态');
    expect(sections[0].rows.some(row => row.key.includes('canDrawOverlays'))).toBe(true);
    expect(sections[0].rows.some(row => row.key.includes('appOps.android:system_alert_window.mode'))).toBe(true);
  });
  it('WebView UA 摘要保留原始字符串和长度', () => {
    const sections = buildSections(item('environment/webview-user-agent', 'environment', { userAgent: 'Mozilla/5.0 Android', length: 20 }));
    expect(sections[0].title).toBe('WebView 默认 User-Agent');
    expect(sections[0].rows.find(row => row.key === 'userAgent')?.value).toBe('Mozilla/5.0 Android');
    expect(sections[0].rows.find(row => row.key === 'length')?.value).toBe('20');
  });
  it('USB 设备详情按数量与接口摘要展示', () => {
    const value = item('hardware/usb', 'hardware', { count: 1, devices: [{ deviceName: '/dev/bus/usb/001/002', vendorId: 18, productId: 52, interfaces: [{ id: 0, endpointCount: 2 }] }] });
    const sections = buildSections(value);
    expect(sections[0].title).toBe('USB 设备公开清单');
    expect(sections[0].rows.map(row => row.key)).toContain('count');
    expect(sections.find(section => section.key === 'devices')?.rows.some(row => row.key === '[0].vendorId')).toBe(true);
  });
  it('电源与热状态按公开快照字段展示', () => {
    const sections = buildSections(item('environment/power-thermal', 'environment', { powerSaveMode: false, interactive: true, deviceIdleMode: false, thermalStatus: 0, thermalHeadroom: 1.25 }));
    expect(sections[0].title).toBe('电源与热状态');
    expect(sections[0].rows.map(row => row.key)).toEqual(['powerSaveMode', 'interactive', 'deviceIdleMode', 'thermalStatus', 'thermalHeadroom']);
  });
  it('定位摘要只展示 provider 与 GNSS 元数据', () => {
    const sections = buildSections(item('environment/location', 'environment', { available: true, locationEnabled: true, providerCount: 1, hasGpsProvider: true, providers: [{ name: 'gps', enabled: true }], gnssHardwareModelName: 'test-gnss', gnssAntennaCount: 1, gnssAntennaInfoTruncated: false, gnssAntennaCarrierFrequenciesMHz: [1575.42] }));
    expect(sections[0].title).toBe('定位与 GNSS 能力');
    expect(sections[0].rows.map(row => row.key)).toContain('gnssHardwareModelName');
    expect(sections.find(section => section.key === 'providers')?.rows[0].value).toBe('gps');
    expect(sections.find(section => section.key === 'gnssAntennaCarrierFrequenciesMHz')?.title).toBe('GNSS 天线载波频率（MHz）');
  });
  it('传感器摘要分开展示计数、类型和厂商', () => {
    const sections = buildSections(item('sensors/summary', 'sensors', { count: 2, wakeUpCount: 1, dynamicCount: 0, directChannelCount: 1, vendorsTruncated: false, typeCounts: { '1': 1, '4': 1 }, vendors: ['Google', 'Bosch'] }));
    expect(sections[0].title).toBe('传感器能力摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['count', 'wakeUpCount', 'dynamicCount', 'directChannelCount', 'vendorsTruncated', 'typeCounts.1', 'typeCounts.4']);
    expect(sections.find(section => section.key === 'vendors')?.title).toBe('传感器厂商');
    expect(sections.find(section => section.key === 'vendors')?.rows.map(row => row.value)).toEqual(['Google', 'Bosch']);
  });
  it('系统特性摘要保留原始数组并展示能力列表', () => {
    const sections = buildSections(item('hardware/features-summary', 'hardware', { count: 2, namedCount: 1, versionedCount: 1, maxVersion: 2, featureNamesTruncated: false, featureNames: ['android.hardware.camera'], glEsVersions: [196608] }));
    expect(sections[0].title).toBe('系统特性能力摘要');
    expect(sections.find(section => section.key === 'featureNames')?.title).toBe('系统特性名');
    expect(sections.find(section => section.key === 'glEsVersions')?.title).toBe('OpenGL ES 要求版本');
    expect(sections.find(section => section.key === 'featureNames')?.rows[0].value).toBe('android.hardware.camera');
  });
  it('编解码摘要展示数量与媒体类型列表', () => {
    const sections = buildSections(item('hardware/codecs-summary', 'hardware', { count: 2, encoderCount: 1, decoderCount: 1, hardwareAcceleratedCount: 1, softwareOnlyCount: 1, vendorCount: 1, codecNamesTruncated: false, mimeTypesTruncated: false, codecNames: ['c2.android.avc.decoder'], mimeTypes: ['video/avc'] }));
    expect(sections[0].title).toBe('媒体编解码能力摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('hardwareAcceleratedCount');
    expect(sections.find(section => section.key === 'codecNames')?.title).toBe('编解码器名');
    expect(sections.find(section => section.key === 'mimeTypes')?.rows[0].value).toBe('video/avc');
  });
  it('摄像头摘要展示能力计数与 ID 列表', () => {
    const sections = buildSections(item('hardware/camera-summary', 'hardware', { available: true, count: 2, cameraIdsTruncated: false, flashAvailableCount: 1, logicalMultiCameraCount: 1, physicalCameraIdCount: 2, cameraAccessErrorCount: 0, cameraIds: ['0', '1'], lensFacingCounts: { '0': 1, '1': 1 } }));
    expect(sections[0].title).toBe('摄像头能力摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('cameraAccessErrorCount');
    expect(sections.find(section => section.key === 'cameraIds')?.title).toBe('摄像头 ID');
    expect(sections.find(section => section.key === 'cameraIds')?.rows.map(row => row.value)).toEqual(['0', '1']);
  });
  it('音频摘要展示输入输出与能力数组', () => {
    const sections = buildSections(item('hardware/audio-summary', 'hardware', { available: true, count: 2, sourceCount: 1, sinkCount: 2, productNamesTruncated: false, typeCounts: { '2': 1 }, productNames: ['Speaker'], sampleRates: [48000], encodings: [2] }));
    expect(sections[0].title).toBe('音频能力摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('sourceCount');
    expect(sections.find(section => section.key === 'productNames')?.title).toBe('音频设备名');
    expect(sections.find(section => section.key === 'sampleRates')?.rows[0].value).toBe('48000');
  });
  it('电池摘要展示电量、供电状态和公开属性', () => {
    const sections = buildSections(item('battery/summary', 'battery', { available: true, level: 80, scale: 100, percentage: 80, status: 2, charging: true, plugged: 1, pluggedAc: true, pluggedUsb: false, pluggedWireless: false, health: 2, temperatureC: 30.5, voltageMv: 4000, technology: 'Li-ion', present: true, capacityPercent: 80, chargeCounterUah: 3000000, currentNowUa: -500000, currentAverageUa: -400000, energyCounterNwh: 1000000000 }));
    expect(sections[0].title).toBe('电池环境摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['available', 'level', 'scale', 'percentage', 'status', 'charging', 'plugged', 'pluggedAc', 'pluggedUsb', 'pluggedWireless', 'health', 'temperatureC', 'voltageMv', 'technology', 'present', 'capacityPercent', 'chargeCounterUah', 'currentNowUa', 'currentAverageUa', 'energyCounterNwh']);
    expect(sections[0].rows.find(row => row.key === 'currentNowUa')?.value).toBe('-500000');
  });
  it('SIM 摘要展示电话环境并保留订阅元数据数组', () => {
    const sections = buildSections(item('sim/summary', 'sim', { available: true, phoneType: 1, phoneCount: 2, simState: 5, simOperator: '46001', simOperatorName: 'carrier', simCountry: 'cn', networkOperator: '46001', networkName: 'network', networkCountry: 'cn', dataNetworkType: 13, roaming: false, dataEnabled: true, subscriptionAccess: 'allowed', subscriptionCount: 1, embeddedCount: 0, simStates: [5, 1], simSlotIndexes: [0], carrierNames: ['carrier'], mccs: ['460'], mncs: ['01'] }));
    const summary = sections[0];
    expect(summary.title).toBe('SIM 与电话环境摘要');
    expect(summary.rows.map(row => row.key)).toContain('subscriptionCount');
    expect(sections.find(section => section.key === 'simStates')?.rows.map(row => row.value)).toEqual(['5', '1']);
    expect(sections.find(section => section.key === 'mccs')?.rows[0].value).toBe('460');
  });
  it('存储摘要展示存储卷拓扑与状态计数', () => {
    const sections = buildSections(item('storage/summary', 'storage', { available: true, volumeCount: 2, mountedCount: 2, primaryCount: 1, removableCount: 1, emulatedCount: 1, uuidPresentCount: 1, stateCounts: { mounted: 2 } }));
    expect(sections[0].title).toBe('存储卷环境摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['available', 'volumeCount', 'mountedCount', 'primaryCount', 'removableCount', 'emulatedCount', 'uuidPresentCount', 'stateCounts.mounted']);
  });
  it('蓝牙摘要展示访问状态与已配对设备计数', () => {
    const sections = buildSections(item('network/bluetooth-summary', 'network', { available: true, access: 'allowed', adapterState: 12, adapterNamePresent: true, adapterAddressPresent: true, bondedCount: 1, bondedNameCount: 1, bondedAddressCount: 1, bondedTypeCounts: { '1': 1 }, bondedClassCounts: { '1028': 1 } }));
    expect(sections[0].title).toBe('蓝牙能力摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('bondedCount');
    expect(sections[0].rows.map(row => row.key)).toContain('bondedTypeCounts.1');
  });
  it('Wi-Fi 摘要展示权限边界与频段计数', () => {
    const sections = buildSections(item('network/wifi-summary', 'network', { available: true, wifiState: 3, connectionAccess: 'allowed', scanAccess: 'denied', connectionInfoAvailable: true, ssidPresent: false, bssidPresent: false, macAddressPresent: true, frequency: 5180, rssi: -45, linkSpeed: 866, cachedScanCount: null, band2_4GHzCount: null, band5GHzCount: null, band6GHzCount: null, frequencies: [5180], frequenciesTruncated: false }));
    expect(sections[0].title).toBe('Wi-Fi 能力摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('scanAccess');
    expect(sections.find(section => section.key === 'frequencies')?.title).toBe('缓存扫描频率（MHz）');
  });
  it('锁屏与生物识别摘要只展示公开状态', () => {
    const sections = buildSections(item('security/lock-biometric', 'security', { keyguardSecure: true, keyguardLocked: false, deviceSecure: true, deviceLocked: false, fingerprintFeature: true, faceFeature: false, irisFeature: false }));
    expect(sections[0].title).toBe('锁屏与生物识别环境');
    expect(sections[0].rows.some(row => row.key === 'keyguardSecure')).toBe(true);
    expect(sections[0].rows.some(row => row.key === 'fingerprintFeature')).toBe(true);
  });
  it('无障碍服务摘要只展示组件与能力元数据', () => {
    const value = item('security/accessibility', 'security', { available: true, count: 1, services: [{ packageName: 'com.example.a11y', serviceName: 'Service', capabilities: 1, canRetrieveWindowContent: true }] });
    const sections = buildSections(value);
    expect(sections[0].title).toBe('启用的无障碍服务');
    expect(sections.find(section => section.key === 'services')?.rows.some(row => row.key === '[0].packageName')).toBe(true);
  });
  it('当前 App 完整性摘要保留签名哈希而不展开证书', () => {
    const value = item('security/self-integrity', 'security', { packageName: 'com.example', versionName: '1.0', versionCode: { $type: 'int64', value: '1' }, signingCertificateSha256: ['a'.repeat(64)], debuggable: false, hasCode: true });
    const sections = buildSections(value);
    expect(sections[0].title).toBe('当前 App 签名与安装环境');
    expect(sections.find(section => section.key === 'signingCertificateSha256')?.rows[0].value).toBe('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa');
  });
  it('系统风控设置摘要按稳定字段顺序展示', () => {
    const sections = buildSections(item('environment/risk-settings', 'environment', { adbEnabled: true, developmentSettingsEnabled: true, mockLocation: false, privateDnsMode: 'opportunistic' }));
    expect(sections[0].title).toBe('系统风控相关设置');
    expect(sections[0].rows.map(row => row.key)).toEqual(['adbEnabled', 'developmentSettingsEnabled', 'privateDnsMode', 'mockLocation']);
  });
  it('设备初始化与运行状态摘要按稳定字段顺序展示', () => {
    const sections = buildSections(item('environment/device-state', 'environment', { deviceProvisioned: true, userSetupComplete: true, bootCount: '7', lowRamDevice: false, userUnlocked: true, userRunning: true }));
    expect(sections[0].title).toBe('设备初始化与运行状态');
    expect(sections[0].rows.map(row => row.key)).toEqual(['deviceProvisioned', 'userSetupComplete', 'bootCount', 'lowRamDevice', 'userUnlocked', 'userRunning']);
  });
  it('信任库详情压缩 DER，只展示可读摘要并保留长度', () => {
    const value = item('security/trust-store', 'security', [{ alias: 'system:root', sha256: 'a'.repeat(64), derBase64: 'A'.repeat(10000) }]);
    const original = JSON.stringify(value);
    const sections = buildSections(value);
    expect(sections[0].title).toContain('原始 DER 保留在 JSON');
    expect(sections[0].rows.find(row => row.key === '[0].derBase64.length')?.value).toBe('10000');
    expect(JSON.stringify(sections)).not.toContain('A'.repeat(100));
    expect(JSON.stringify(value)).toBe(original);
  });
  it('应用清单签名详情压缩 DER，只展示哈希和长度', () => {
    const value = item('packages/com.example', 'packages', { packageName: 'com.example', signatures: [{ sha256: 'b'.repeat(64), derBase64: 'B'.repeat(12000) }] });
    const original = JSON.stringify(value);
    const sections = buildSections(value);
    const signatures = sections.find(section => section.key === 'signatures');
    expect(signatures?.title).toContain('原始 DER 保留在 JSON');
    expect(signatures?.rows.find(row => row.key === '[0].derBase64.length')?.value).toBe('12000');
    expect(JSON.stringify(signatures)).not.toContain('B'.repeat(100));
    expect(JSON.stringify(value)).toBe(original);
  });
  it('proc 多行文本按行拆分，正确行不会因另一行变化而整体标红', () => {
    const sections = buildSections(item('kernel/proc/diskstats', 'kernel', '8 0 sda 1 2\n8 1 sda1 3 4\n'));
    expect(sections[0].table).toBe(true);
    expect(sections[0].rows.map(row => row.key)).toEqual(['line-0', 'line-1']);
  });
});
