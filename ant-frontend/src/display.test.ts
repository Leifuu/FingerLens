import { describe, expect, it } from 'vitest';
import { buildSections } from './display';
import type { Item, Json } from './core/types';

function item(key: string, category: string, value: Json): Item {
  return { key, category, value, name: key, dataType: 'object', status: 'ok', source: { kind: 'api', api: key, uid: 10001, pid: 1, selinux: 'untrusted_app' } };
}

describe('设备格式化展示边界', () => {
  it('模块关联包版本保留int64精度、未知字段和不可见状态，不改旧模块元数据', () => {
    const source = item('build/modules', 'build', { count: 2, modules: [
      { name: 'same', packageName: 'module.test', hidden: true, packageVersion: { status: 'ok', versionName: '', versionCode: { $type: 'int64', value: '9223372036854775807' }, isApex: true, future: { flag: false } } },
      { name: 'same', packageName: 'module.missing', hidden: false, packageVersion: { status: 'not-found-or-not-visible', versionName: null, versionCode: null, isApex: null } },
    ] });
    const before = JSON.stringify(source), rows = buildSections(source).find(group => group.key === 'modules')!.rows;
    expect(rows.find(row => row.key === '[0].packageVersion.versionCode')?.value).toBe('9223372036854775807');
    expect(rows.find(row => row.key === '[0].packageVersion.versionName')?.value).toBe('');
    expect(rows.find(row => row.key === '[0].packageVersion.future.flag')?.value).toBe('false');
    expect(rows.find(row => row.key === '[1].packageVersion.status')?.value).toBe('not-found-or-not-visible');
    expect(rows.find(row => row.key === '[1].packageVersion.versionCode')?.value).toBe('null');
    expect(rows.filter(row => row.key.endsWith('.name')).map(row => row.value)).toEqual(['same', 'same']);
    expect(JSON.stringify(source)).toBe(before);
  });
  it('TLS 参数分组保留顺序、重复、未知字段及空值，缺失列表不冒充空集合', () => {
    const source = item('security/tls-parameters', 'security', { providerName: 'test', defaultProtocols: ['TLSv1.3', 'TLSv1.2', 'TLSv1.3', '', null], supportedCipherSuites: [], futureField: { flag: false }, futureList: ['future'] });
    const before = JSON.stringify(source), groups = buildSections(source);
    expect(groups[0].title).toBe('默认 TLS 上下文摘要');
    expect(groups[0].rows.find(row => row.key === 'futureField.flag')?.value).toBe('false');
    expect(groups.find(group => group.key === 'defaultProtocols')?.rows.map(row => row.value)).toEqual(['TLSv1.3', 'TLSv1.2', 'TLSv1.3', '', 'null']);
    expect(groups.find(group => group.key === 'supportedCipherSuites')?.rows[0].value).toBe('[]');
    expect(groups.some(group => group.key === 'supportedProtocols')).toBe(false);
    expect(groups.find(group => group.key === 'futureList')?.rows[0].value).toBe('future');
    expect(buildSections(item('security/tls-parameters', 'security', null))[0].rows[0].value).toBe('null');
    expect(JSON.stringify(source)).toBe(before);
  });
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
  it('Root 路径的未知状态不读取对象原型属性', () => {
    for (const status of ['__proto__', 'constructor', 'future_status']) {
      const sections = buildSections(item('security/root-path-summary', 'security', { status, entries: [{ status }] }));
      expect(sections[0].rows.find(row => row.key === 'status')?.value).toBe(status);
      expect(sections[1].rows.find(row => row.key === '[0].status')?.value).toBe(status);
    }
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
  it('风控摘要使用专用分组并保留版本化能力状态', () => {
    const sections = buildSections(item('network/risk-signals', 'network', { present: true, validated: true, vpn: false, transports: [1], capabilityStates: { internet: true, notSuspended: true, localNetwork: null }, capabilitiesRaw: 'raw-capabilities' }));
    expect(sections.find(section => section.key === 'summary')?.title).toBe('活动链路摘要');
    expect(sections.find(section => section.key === 'transports')?.rows[0].value).toBe('1');
    expect(sections.find(section => section.key === 'summary')?.rows.find(row => row.key === 'capabilityStates.internet')?.value).toBe('true');
    expect(sections.find(section => section.key === 'summary')?.rows.find(row => row.key === 'capabilityStates.localNetwork')?.value).toBe('null');
    expect(sections.find(section => section.key === 'capabilitiesRaw')?.rows[0].value).toBe('raw-capabilities');
  });
  it('网络集合摘要展示所有链路能力计数', () => {
    const sections = buildSections(item('network/connectivity-summary', 'network', { available: true, networkCount: 2, activePresent: true, activeIndex: 0, activeNetworkHandle: { $type: 'int64', value: '42' }, capabilitiesAvailableCount: 2, linkPropertiesAvailableCount: 2, validatedCount: 1, vpnCount: 1, notMeteredCount: 2, transportCounts: { '1': 1, '4': 1 } }));
    expect(sections[0].title).toBe('网络集合摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('transportCounts.1');
    expect(sections[0].rows.find(row => row.key === 'activeNetworkHandle')?.value).toBe('42');
  });
  it('网络接口摘要展示受控前缀和地址族计数', () => {
    const sections = buildSections(item('network/interface-summary', 'network', { available: true, status: 'ok', interfaceCount: 3, upCount: 2, downCount: 1, loopbackCount: 1, virtualCount: 1, pointToPointCount: 1, hardwareAddressPresentCount: 2, addressCount: 4, ipv4Count: 2, ipv6Count: 2, mtuMin: 1500, mtuMax: 1500, prefixCounts: { loopback: 1, wifi: 1, vpn_or_tunnel: 1 } }));
    expect(sections[0].title).toBe('网络接口摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['available', 'status', 'interfaceCount', 'upCount', 'downCount', 'loopbackCount', 'virtualCount', 'pointToPointCount', 'hardwareAddressPresentCount', 'addressCount', 'ipv4Count', 'ipv6Count', 'mtuMin', 'mtuMax', 'prefixCounts.loopback', 'prefixCounts.wifi', 'prefixCounts.vpn_or_tunnel']);
  });
  it('安全摘要展示调试、设备管理和访问边界', () => {
    const sections = buildSections(item('security/summary', 'security', { selfDebuggable: false, debuggerConnected: false, waitingForDebugger: false, deviceOwnerForSelf: false, profileOwnerForSelf: false, managedProfile: false, userUnlocked: true, specialAccessAvailable: true, appOpsCheckedCount: 5, appOpsAllowedCount: 1, canDrawOverlays: false, keyguardSecure: true, keyguardLocked: false, deviceSecure: true, deviceLocked: false, fingerprintFeature: true, faceFeature: false, irisFeature: false, accessibilityServiceCount: 0, securityProviderCount: 4 }));
    expect(sections[0].title).toBe('安全环境摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('appOpsAllowedCount');
    expect(sections[0].rows.find(row => row.key === 'securityProviderCount')?.value).toBe('4');
  });
  it('两个锁屏摘要区分未知 null、旧版 false 和缺项且不修改输入', () => {
    const fields = ['keyguardSecure', 'keyguardLocked', 'deviceSecure', 'deviceLocked'];
    for (const key of ['security/summary', 'security/lock-biometric']) {
      for (const state of [null, false, true]) {
        const value = item(key, 'security', { ...Object.fromEntries(fields.map(field => [field, state])), fingerprintFeature: false });
        const original = JSON.stringify(value);
        const rows = buildSections(value)[0].rows;
        for (const field of fields) expect(rows.find(row => row.key === field)?.value).toBe(String(state));
        expect(rows.find(row => row.key === 'fingerprintFeature')?.value).toBe('false');
        expect(JSON.stringify(value)).toBe(original);
      }
      const rows = buildSections(item(key, 'security', { fingerprintFeature: false }))[0].rows;
      expect(rows.some(row => fields.includes(row.key))).toBe(false);
    }
  });
  it('进程身份摘要展示 UID 分类与沙箱边界', () => {
    const sections = buildSections(item('security/process-identity-summary', 'security', { available: true, status: 'ok', uid: 10500, isApplicationUid: true, isIsolatedUid: false, isSdkSandboxUid: false, isIsolatedProcess: false, isSdkSandboxProcess: false, packageName: 'com.example', processName: 'com.example' }));
    expect(sections[0].title).toBe('当前进程身份与沙箱摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('isIsolatedUid');
  });
  it('应用运行时摘要展示公开分析配置', () => {
    const sections = buildSections(item('security/self-runtime', 'security', { available: true, status: 'ok', profileableByShell: false, profileable: false, virtualPreload: false, gwpAsanMode: 0, memtagMode: 0, nativeHeapZeroInitialized: 1, requestRawExternalStorageAccess: 0, attributionsUserVisible: true }));
    expect(sections[0].title).toBe('应用运行时可分析配置');
    expect(sections[0].rows.map(row => row.key)).toContain('profileableByShell');
  });
  it('当前 App 权限摘要展示授权计数与有界权限列表', () => {
    const sections = buildSections(item('security/self-permissions', 'security', { available: true, status: 'ok', requestedCount: 2, grantedCount: 1, deniedCount: 1, unknownCount: 0, dangerousRequestedCount: 1, dangerousGrantedCount: 1, protectionInfoCount: 2, returnedCount: 2, truncated: false, permissions: [{ name: 'android.permission.CAMERA', granted: true, protectionLevel: 1, protectionBase: 1, dangerous: true }] }));
    expect(sections[0].title).toBe('当前 App 运行时权限摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('grantedCount');
    expect(sections.find(section => section.key === 'permissions')?.title).toBe('声明权限（最多 256 项）');
    expect(sections.find(section => section.key === 'permissions')?.rows[0].key).toBe('[0].name');
    expect(sections.find(section => section.key === 'permissions')?.rows.find(row => row.key === '[0].protectionBase')?.value).toBe('危险');
  });
  it('当前 App 组件摘要展示导出面统计', () => {
    const sections = buildSections(item('security/self-components-summary', 'security', { available: true, status: 'ok', activityCount: 2, receiverCount: 1, serviceCount: 2, providerCount: 1, componentCount: 6, enabledCount: 5, exportedCount: 2, permissionFieldCount: 3, directBootAwareCount: 1, isolatedServiceCount: 0, foregroundServiceTypeCount: 1, providerReadPermissionCount: 1, providerWritePermissionCount: 1 }));
    expect(sections[0].title).toBe('当前 App 组件攻击面摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('exportedCount');
  });
  it('安全提供者摘要保留有界服务集合并单独展示提供者详情', () => {
    const sections = buildSections(item('security/providers-summary', 'security', {
      available: true, status: 'ok', providerCount: 2, serviceCount: 3, providerNamesTruncated: false,
      serviceTypeCounts: { MessageDigest: 2, Signature: 1 }, serviceNamesTruncated: false,
      providers: [{ name: 'AndroidKeyStore', version: 1, infoLength: 12, infoSha256: 'a'.repeat(64), serviceCount: 2, serviceTypeCounts: { KeyStore: 2 } }],
      serviceNames: ['MessageDigest:SHA-256', 'Signature:SHA256withRSA'],
    }));
    expect(sections[0].title).toBe('安全提供者摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('serviceTypeCounts.MessageDigest');
    expect(sections.find(section => section.key === 'providers')?.title).toBe('安全提供者');
    expect(sections.find(section => section.key === 'serviceNames')?.title).toBe('服务名（最多 512 项）');
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
  it('ABI 摘要展示架构族与运行时位数', () => {
    const sections = buildSections(item('build/abi-summary', 'build', { available: true, supportedAbiCount: 3, supported32BitAbiCount: 1, supported64BitAbiCount: 2, primaryAbi: 'arm64-v8a', armAbiCount: 2, x86AbiCount: 1, riscvAbiCount: 0, otherAbiCount: 0, runtime64Bit: true }));
    expect(sections[0].title).toBe('ABI 与运行时架构摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['available', 'supportedAbiCount', 'supported32BitAbiCount', 'supported64BitAbiCount', 'primaryAbi', 'armAbiCount', 'x86AbiCount', 'riscvAbiCount', 'otherAbiCount', 'runtime64Bit']);
  });
  it('硬件摘要展示型号与构建指纹哈希', () => {
    const sections = buildSections(item('build/hardware-summary', 'build', { available: true, manufacturer: 'Google', brand: 'google', model: 'Pixel', device: 'oriole', product: 'oriole', hardware: 'oriole', board: 'oriole', fingerprintPresent: true, fingerprintLength: 96, fingerprintSha256: 'a'.repeat(64), socManufacturer: 'Google', socModel: 'Tensor' }));
    expect(sections[0].title).toBe('硬件型号与 SoC 摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['available', 'manufacturer', 'brand', 'model', 'device', 'product', 'hardware', 'board', 'fingerprintPresent', 'fingerprintLength', 'fingerprintSha256', 'socManufacturer', 'socModel']);
    expect(sections[0].rows.find(row => row.key === 'fingerprintSha256')?.value).toBe('a'.repeat(64));
  });
  it('Mainline 模块摘要展示有界名称集合与集合哈希', () => {
    const sections = buildSections(item('build/module-summary', 'build', { available: true, status: 'ok', count: 3, hiddenCount: 1, namedCount: 3, packageNameCount: 3, moduleNames: ['ART', 'Media', 'Permission'], packageNames: ['com.android.art', 'com.android.media', 'com.android.permission'], moduleNamesTruncated: false, packageNamesTruncated: false, moduleSetSha256: 'a'.repeat(64) }));
    expect(sections[0].title).toBe('Mainline 系统模块摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['available', 'status', 'count', 'hiddenCount', 'namedCount', 'packageNameCount', 'moduleNamesTruncated', 'packageNamesTruncated', 'moduleSetSha256']);
    expect(sections.find(section => section.key === 'moduleNames')?.title).toBe('模块名称');
    expect(sections.find(section => section.key === 'packageNames')?.title).toBe('模块包名');
  });
  it('DRM 方案摘要分组显示 UUID，保留重复、未知 UUID 和后续字段', () => {
    const uuid = 'ffffffff-ffff-ffff-ffff-ffffffffffff';
    const value = item('hardware/drm-schemes', 'hardware', { available: true, status: 'partial', scope: 'platform-supported-crypto-schemes', schemeCount: 34, returnedCount: 32, schemeLimit: 32, truncated: true, schemeListSha256: 'a'.repeat(64), schemeUuids: [uuid, uuid], futureField: null });
    const original = JSON.stringify(value);
    const sections = buildSections(value);
    expect(sections[0].title).toBe('DRM 支持方案摘要');
    expect(sections[0].rows.find(row => row.key === 'schemeCount')?.value).toBe('34');
    expect(sections[0].rows.find(row => row.key === 'schemeListSha256')?.value).toBe('a'.repeat(64));
    expect(sections[0].rows.find(row => row.key === 'futureField')?.value).toBe('null');
    expect(sections.find(section => section.key === 'schemeUuids')?.title).toBe('平台支持的 DRM 方案 UUID（最多 32 项）');
    expect(sections.find(section => section.key === 'schemeUuids')?.rows.map(row => row.value)).toEqual([uuid, uuid]);
    expect(JSON.stringify(value)).toBe(original);
  });
  it('摄像头并发组合保留成员、未知字段及空集合与服务缺失的区别', () => {
    const value = item('hardware/camera-concurrency', 'hardware', { available: true, status: 'ok', combinationCount: 1, combinationSetSha256: 'a'.repeat(64), combinations: [{ cameraCount: 2, cameraIds: ['camera-X', '0'], idsTruncated: false }], futureField: null });
    const before = JSON.stringify(value), sections = buildSections(value);
    expect(sections[0].title).toBe('摄像头并发组合摘要');
    expect(sections[0].rows.find(row => row.key === 'futureField')?.value).toBe('null');
    expect(sections[1].title).toBe('当前连接摄像头的并发组合（最多 32 组，每组 16 个 ID）');
    expect(sections[1].rows.filter(row => row.key.includes('cameraIds')).map(row => row.value)).toEqual(['camera-X', '0']);
    const empty = buildSections(item(value.key, 'hardware', { combinationCount: 0, combinations: [] }));
    const missing = buildSections(item(value.key, 'hardware', { available: false, status: 'unavailable', combinationCount: null, combinations: null }));
    expect(empty[1].rows[0].value).toBe('[]');
    expect(missing).toHaveLength(1);
    expect(missing[0].rows.find(row => row.key === 'combinationCount')?.value).toBe('null');
    expect(missing[0].rows.find(row => row.key === 'combinations')?.value).toBe('null');
    expect(JSON.stringify(value)).toBe(before);
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
    const sections = buildSections(item('security/special-access', 'security', { available: true, canDrawOverlays: false, canRequestPackageInstalls: null, canRequestPackageInstallsStatus: 'denied', notificationPolicyAccess: false, notificationPolicyAccessStatus: 'allowed', allFilesAccess: false, allFilesAccessStatus: 'allowed', appOps: { 'android:system_alert_window': { mode: 1, allowed: false } } }));
    expect(sections[0].title).toBe('本 App 特殊访问状态');
    expect(sections[0].rows.some(row => row.key.includes('canDrawOverlays'))).toBe(true);
    expect(sections[0].rows.some(row => row.key.includes('canRequestPackageInstalls'))).toBe(true);
    expect(sections[0].rows.some(row => row.key.includes('canRequestPackageInstallsStatus'))).toBe(true);
    expect(sections[0].rows.some(row => row.key.includes('appOps.android:system_alert_window.mode'))).toBe(true);
  });
  it('WebView UA 摘要保留原始字符串和长度', () => {
    const sections = buildSections(item('environment/webview-user-agent', 'environment', { userAgent: 'Mozilla/5.0 Android', length: 20 }));
    expect(sections[0].title).toBe('WebView 默认 User-Agent');
    expect(sections[0].rows.find(row => row.key === 'userAgent')?.value).toBe('Mozilla/5.0 Android');
    expect(sections[0].rows.find(row => row.key === 'length')?.value).toBe('20');
  });
  it('WebView 安装摘要展示查询状态、版本竞态和有界签名哈希', () => {
    const value = item('environment/webview-package-summary', 'environment', { available: true, packageName: 'synthetic.webview', selectedVersionCode: '1', versionCode: '2', selectionVersionMatches: false, packageQueryStatus: 'allowed', targetSdkVersion: 35, installer: null, installerQueryStatus: 'not-found-or-not-visible', signatureCount: 1, signatureLimit: 16, signaturesTruncated: false, signingCertificateSha256: ['a'.repeat(64)] });
    const original = JSON.stringify(value);
    const sections = buildSections(value);
    expect(sections[0].title).toBe('WebView 提供方安装环境摘要');
    expect(sections[0].rows.find(row => row.key === 'selectionVersionMatches')?.value).toBe('false');
    expect(sections[0].rows.find(row => row.key === 'installerQueryStatus')?.value).toBe('未找到或当前 App 不可见');
    expect(sections.find(section => section.key === 'signingCertificateSha256')?.rows[0].value).toBe('a'.repeat(64));
    expect(JSON.stringify(value)).toBe(original);
    for (const status of ['__proto__', 'constructor', 'future_status']) {
      const rows = buildSections(item('environment/webview-package-summary', 'environment', { packageQueryStatus: status }))[0].rows;
      expect(rows[0].value).toBe(status);
    }
  });
  it('WebView 签名轮换历史保留顺序、重复哈希和未知状态，兼容旧摘要', () => {
    const value = item('environment/webview-package-summary', 'environment', { hasMultipleSigners: false, hasPastSigningCertificates: true, signingHistoryStatus: 'partial', signingHistoryCount: 18, signingHistoryLimit: 16, signingHistoryTruncated: true, signingHistorySha256: 'c'.repeat(64), signingCertificateSha256: ['b'.repeat(64)], signingCertificateHistorySha256: ['a'.repeat(64), 'a'.repeat(64), 'b'.repeat(64)] });
    const original = JSON.stringify(value);
    const sections = buildSections(value);
    expect(sections[0].rows.find(row => row.key === 'signingHistoryCount')?.value).toBe('18');
    expect(sections[0].rows.find(row => row.key === 'signingHistorySha256')?.value).toBe('c'.repeat(64));
    const history = sections.find(section => section.key === 'signingCertificateHistorySha256');
    expect(history?.title).toBe('提供方签名轮换历史 SHA-256（平台顺序，最多 16 项）');
    expect(history?.rows.map(row => row.value)).toEqual(['a'.repeat(64), 'a'.repeat(64), 'b'.repeat(64)]);
    expect(JSON.stringify(value)).toBe(original);
    const multiple = buildSections(item(value.key, 'environment', { signingHistoryStatus: 'multiple-signers', signingHistoryCount: null, signingHistoryTruncated: null }));
    expect(multiple[0].rows.find(row => row.key === 'signingHistoryStatus')?.value).toBe('多签名，不适用单签名轮换历史');
    expect(multiple[0].rows.find(row => row.key === 'signingHistoryCount')?.value).toBe('null');
    const legacy = buildSections(item(value.key, 'environment', { signatureCount: 1, signingCertificateSha256: ['b'.repeat(64)] }));
    expect(legacy.some(section => section.key === 'signingCertificateHistorySha256')).toBe(false);
    expect(legacy[0].rows.some(row => row.key === 'signingHistoryCount')).toBe(false);
  });
  it('USB 设备详情按数量与接口摘要展示', () => {
    const value = item('hardware/usb', 'hardware', { count: 1, devices: [{ deviceName: '/dev/bus/usb/001/002', vendorId: 18, productId: 52, interfaces: [{ id: 0, endpointCount: 2 }] }] });
    const sections = buildSections(value);
    expect(sections[0].title).toContain('原始描述符保留');
    expect(sections[0].rows.map(row => row.key)).toContain('interfaceCount');
    expect(sections.find(section => section.key === 'vendorIds')?.rows[0].value).toBe('18');
    expect(JSON.stringify(sections)).not.toContain('/dev/bus/usb/001/002');
    expect(JSON.stringify(value)).toContain('/dev/bus/usb/001/002');
  });
  it('电源与热状态按公开快照字段展示', () => {
    const sections = buildSections(item('environment/power-thermal', 'environment', { powerSaveMode: false, interactive: true, ignoresBatteryOptimizations: null, batteryOptimizationQueryStatus: 'denied', deviceIdleMode: false, thermalStatus: 0, thermalHeadroom: 1.25 }));
    expect(sections[0].title).toBe('电源与热状态');
    expect(sections[0].rows.map(row => row.key)).toContain('batteryOptimizationQueryStatus');
    expect(sections[0].rows.find(row => row.key === 'batteryOptimizationQueryStatus')?.value).toBe('受限/未授权');
    expect(sections[0].rows.map(row => row.key)).toEqual(['powerSaveMode', 'interactive', 'ignoresBatteryOptimizations', 'batteryOptimizationQueryStatus', 'deviceIdleMode', 'thermalStatus', 'thermalHeadroom']);
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
  it('麦克风摘要展示阵列能力且不展开地址', () => {
    const value = item('hardware/microphone-summary', 'hardware', { available: true, status: 'ok', count: 2, returnedCount: 2, truncated: false, microphoneIds: [1, 2], microphoneIdsTruncated: false, typeCounts: { '15': 2 }, locationValues: [1], directionalityValues: [0], groupCount: 1, addressPresentCount: 2, descriptionPresentCount: 2, channelMappingCount: 4, frequencyResponsePointCount: 6 });
    const sections = buildSections(value);
    expect(sections[0].title).toBe('麦克风阵列能力摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['available', 'status', 'count', 'returnedCount', 'truncated', 'microphoneIdsTruncated', 'typeCounts.15', 'groupCount', 'addressPresentCount', 'descriptionPresentCount', 'channelMappingCount', 'frequencyResponsePointCount']);
    expect(sections.find(section => section.key === 'microphoneIds')?.title).toBe('麦克风 ID');
    expect(sections.find(section => section.key === 'locationValues')?.title).toBe('麦克风位置枚举');
    expect(sections.find(section => section.key === 'directionalityValues')?.title).toBe('麦克风指向性枚举');
  });
  it('振动器摘要展示硬件能力与设备列表', () => {
    const sections = buildSections(item('hardware/vibrator', 'hardware', { available: true, count: 1, hasVibrator: true, hasAmplitudeControl: true, vibrators: [{ id: 0, hasVibrator: true, hasAmplitudeControl: true, effectSupportCodes: [0, 2, 2, 0] }] }));
    expect(sections[0].title).toBe('振动器能力');
    expect(sections[0].rows.map(row => row.key)).toContain('hasAmplitudeControl');
    expect(sections.find(section => section.key === 'vibrators')?.rows.map(row => row.key)).toContain('[0].id');
  });
  it('NFC 摘要只展示适配器能力状态', () => {
    const sections = buildSections(item('hardware/nfc', 'hardware', { available: true, enabled: true, secureNfcSupported: true, secureNfcEnabled: false, antennaInfoAvailable: true, antennaCount: 1, deviceWidth: 68, deviceHeight: 146, deviceFoldable: false }));
    expect(sections[0].title).toBe('NFC 能力');
    expect(sections[0].rows.map(row => row.key)).toContain('secureNfcSupported');
    expect(sections[0].rows.map(row => row.key)).toContain('antennaCount');
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
  it('eSIM 摘要展示能力边界而不展开 EID', () => {
    const sections = buildSections(item('sim/euicc-summary', 'sim', { available: true, status: 'ok', managerPresent: true, enabled: true, euiccInfoPresent: true, euiccOsVersion: 'test', availableMemoryBytes: 4096 }));
    expect(sections[0].title).toBe('eSIM 能力摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('availableMemoryBytes');
    expect(JSON.stringify(sections).toLowerCase()).not.toContain('eid');
  });
  it('电话射频摘要展示设备能力而不展开敏感标识', () => {
    const sections = buildSections(item('sim/radio-summary', 'sim', { available: true, status: 'ok', activeModemCount: 2, supportedModemCount: 2, supportedRadioAccessFamily: { $type: 'int64', value: '123' }, dataCapable: true, voiceCapable: true, smsCapable: true, deviceVoiceCapable: true, deviceSmsCapable: true }));
    expect(sections[0].title).toBe('电话射频能力摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('activeModemCount');
    expect(sections[0].rows.map(row => row.key)).not.toContain('imei');
  });
  it('电话注册摘要展示状态计数与有限信道能力', () => {
    const sections = buildSections(item('sim/service-summary', 'sim', { available: true, status: 'ok', serviceState: 0, registrationCount: 2, registeredCount: 2, roamingCount: 0, searchingCount: 0, emergencyServiceCount: 0, cellBandwidths: [20, 100] }));
    expect(sections[0].title).toBe('电话注册服务摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('registrationCount');
    expect(sections.find(section => section.key === 'cellBandwidths')?.rows).toHaveLength(2);
  });
  it('电话信号摘要展示有界动态证据', () => {
    const sections = buildSections(item('sim/signal-summary', 'sim', { available: true, status: 'ok', signalLevel: 3, timestampMillis: { $type: 'int64', value: '1000' }, cellCount: 2, validCellCount: 2, dbmMin: -105, dbmMax: -75, asuMin: 10, asuMax: 30, typeCounts: { CellSignalStrengthLte: 1 }, levelCounts: { '3': 1 } }));
    expect(sections[0].title).toBe('电话信号摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('signalLevel');
    expect(sections[0].rows.map(row => row.key)).not.toContain('cellIdentity');
  });
  it('USB 摘要不展开设备路径与描述符', () => {
    const sections = buildSections(item('hardware/usb-summary', 'hardware', { available: true, deviceCount: 2, deviceCountTruncated: false, interfaceCount: 3, endpointCount: 5, permissionQueryStatus: 'allowed', permissionGrantedCount: 1, vendorIdCount: 2, productIdCount: 2, vendorIds: [18, 1204], productIds: [52, 4660], vendorIdsTruncated: false, productIdsTruncated: false, deviceClassCounts: { '0': 2 }, interfaceClassCounts: { '3': 1 } }));
    expect(sections[0].title).toBe('USB 能力摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('deviceCount');
    expect(JSON.stringify(sections)).not.toContain('deviceName');
  });
  it('存储摘要展示存储卷拓扑与状态计数', () => {
    const sections = buildSections(item('storage/summary', 'storage', { available: true, volumeCount: 2, mountedCount: 2, primaryCount: 1, removableCount: 1, emulatedCount: 1, uuidPresentCount: 1, stateCounts: { mounted: 2 } }));
    expect(sections[0].title).toBe('存储卷环境摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['available', 'volumeCount', 'mountedCount', 'primaryCount', 'removableCount', 'emulatedCount', 'uuidPresentCount', 'stateCounts.mounted']);
  });
  it('存储容量摘要使用固定路径标签并人性化大整数', () => {
    const sections = buildSections(item('storage/capacity-summary', 'storage', { available: true, status: 'ok', entryCount: 1, errorCount: 0, entries: [{ label: 'data', available: true, blockSizeBytes: 4096, totalBytes: 1073741824, availableBytes: 536870912 }] }));
    expect(sections[0].title).toBe('存储容量摘要');
    expect(sections.find(section => section.key === 'entries')?.title).toBe('固定路径容量');
    expect(sections.find(section => section.key === 'entries')?.rows.find(row => row.key === '[0].totalBytes')?.value).toBe('1 GiB（1073741824 B）');
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
  it('Wi-Fi 链路能力展示标准、速率与 MLO 摘要', () => {
    const sections = buildSections(item('network/wifi-capabilities', 'network', { available: true, access: 'allowed', wifiStandard: 6, maxSupportedRxLinkSpeedMbps: 1201, maxSupportedTxLinkSpeedMbps: 1201, apMldMacPresent: true, apMloLinkId: 0, affiliatedMloLinkCount: 2 }));
    expect(sections[0].title).toBe('Wi-Fi 链路能力');
    expect(sections[0].rows.map(row => row.key)).toEqual(['available', 'access', 'wifiStandard', 'maxSupportedRxLinkSpeedMbps', 'maxSupportedTxLinkSpeedMbps', 'apMldMacPresent', 'apMloLinkId', 'affiliatedMloLinkCount']);
  });
  it('代理摘要只展示配置元数据', () => {
    const sections = buildSections(item('network/proxy-summary', 'network', { available: true, status: 'none', configured: false, hostPresent: false, hostLength: null, hostSha256: null, port: null, exclusionCount: 0, pacFilePresent: false, pacScheme: null }));
    expect(sections[0].title).toBe('代理环境摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['available', 'status', 'configured', 'hostPresent', 'hostLength', 'hostSha256', 'port', 'exclusionCount', 'pacFilePresent', 'pacScheme']);
  });
  it('Private DNS 摘要不展开地址和名称原文', () => {
    const sections = buildSections(item('network/private-dns-summary', 'network', { available: true, status: 'ok', activeNetworkPresent: true, linkPropertiesAvailable: true, privateDnsSupported: true, privateDnsActive: true, dnsServerCount: 2, dnsIpv4Count: 1, dnsIpv6Count: 1, privateDnsServerNamePresent: true, privateDnsServerNameLength: 14, privateDnsServerNameSha256: 'c'.repeat(64) }));
    expect(sections[0].title).toBe('Private DNS 环境摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('dnsServerCount');
    expect(sections[0].rows.map(row => row.key)).not.toContain('privateDnsServerName');
  });
  it('锁屏与生物识别摘要只展示公开状态', () => {
    const sections = buildSections(item('security/lock-biometric', 'security', { keyguardSecure: true, keyguardLocked: false, deviceSecure: true, deviceLocked: false, fingerprintFeature: true, faceFeature: false, irisFeature: false, biometricStrongCode: 0, biometricStrongStatus: 'success', biometricWeakCode: 11, biometricWeakStatus: 'none_enrolled' }));
    expect(sections[0].title).toBe('锁屏与生物识别环境');
    expect(sections[0].rows.some(row => row.key === 'keyguardSecure')).toBe(true);
    expect(sections[0].rows.some(row => row.key === 'fingerprintFeature')).toBe(true);
    expect(sections[0].rows.find(row => row.key === 'biometricStrongStatus')?.value).toBe('可用');
    expect(sections[0].rows.find(row => row.key === 'biometricWeakStatus')?.value).toBe('未录入');
  });
  it('无障碍服务摘要只展示组件与能力元数据', () => {
    const value = item('security/accessibility', 'security', { available: true, count: 1, services: [{ packageName: 'com.example.a11y', serviceName: 'Service', capabilities: 1, canRetrieveWindowContent: true }] });
    const sections = buildSections(value);
    expect(sections[0].title).toBe('启用的无障碍服务');
    expect(sections.find(section => section.key === 'services')?.rows.some(row => row.key === '[0].packageName')).toBe(true);
  });
  it('当前 App 完整性摘要保留签名哈希而不展开证书', () => {
    const value = item('security/self-integrity', 'security', { packageName: 'com.example', versionName: '1.0', versionCode: { $type: 'int64', value: '1' }, installer: 'com.android.vending', initiatingPackageName: 'com.android.vending', originatingPackageName: null, updateOwnerPackageName: null, packageSource: 1, packageSourceName: 'store', packageSourceStatus: 'ok', signingCertificateSha256: ['a'.repeat(64)], signingCertificateHistorySha256: ['a'.repeat(64), 'b'.repeat(64)], hasPastSigningCertificates: true, applicationFlagsSummary: { testOnly: false, usesCleartextTraffic: false, hardwareAccelerated: true }, debuggable: false, hasCode: true, instantApp: false, packageSuspended: false, packageSuspensionQueryStatus: 'allowed' });
    const sections = buildSections(value);
    expect(sections[0].title).toBe('当前 App 签名与安装环境');
    expect(sections[0].rows.map(row => row.key)).toContain('applicationFlagsSummary.testOnly');
    expect(sections.find(section => section.key === 'summary')?.rows.some(row => row.key === 'packageSourceName')).toBe(true);
    expect(sections.find(section => section.key === 'signingCertificateSha256')?.rows[0].value).toBe('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa');
    expect(sections.find(section => section.key === 'signingCertificateHistorySha256')?.title).toBe('签名历史 SHA-256');
    expect(sections[0].rows.find(row => row.key === 'packageSuspensionQueryStatus')?.value).toBe('已授权');
  });
  it('SELinux 摘要展示 enforcing 状态而不展开上下文', () => {
    const sections = buildSections(item('security/selinux-summary', 'security', { available: true, status: 'ok', enforceFilePresent: true, enforcing: true, enforceValue: '1', contextPresent: true, contextLength: 20, contextSha256: 'd'.repeat(64) }));
    expect(sections[0].title).toBe('SELinux 环境摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('enforcing');
    expect(sections[0].rows.map(row => row.key)).not.toContain('context');
  });
  it('系统风控设置摘要按稳定字段顺序展示', () => {
    const sections = buildSections(item('environment/risk-settings', 'environment', { adbEnabled: true, developmentSettingsEnabled: true, mockLocation: false, privateDnsMode: 'opportunistic' }));
    expect(sections[0].title).toBe('系统风控相关设置');
    expect(sections[0].rows.map(row => row.key)).toEqual(['adbEnabled', 'developmentSettingsEnabled', 'privateDnsMode', 'mockLocation']);
  });
  it('语言与时区摘要展示系统与应用区域设置', () => {
    const sections = buildSections(item('environment/locale-summary', 'environment', { available: true, localeTags: 'zh-CN,en-US', localeCount: 2, defaultLocale: 'zh-CN', timezoneId: 'Asia/Shanghai', rawOffsetMillis: 28800000, usesDaylightTime: false, inDaylightTime: false, is24HourFormat: true, layoutDirection: 0, fontScale: 1, densityDpi: 420, uiMode: 33, applicationLocaleAccess: 'allowed', applicationLocaleTags: '' }));
    expect(sections[0].title).toBe('语言与时区摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('applicationLocaleAccess');
    expect(sections[0].rows.map(row => row.key)).toContain('timezoneId');
  });
  it('系统时钟摘要展示运行时与近似开机时间', () => {
    const sections = buildSections(item('environment/clock-summary', 'environment', { elapsedRealtimeMillis: 1000, uptimeMillis: 900, wallClockMillis: 1700000001000, bootTimeMillis: 1700000000000, sleepMillis: 100 }));
    expect(sections[0].title).toBe('系统时钟摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['elapsedRealtimeMillis', 'uptimeMillis', 'wallClockMillis', 'bootTimeMillis', 'sleepMillis']);
  });
  it('Android ID 摘要只展示作用域与哈希', () => {
    const sections = buildSections(item('environment/android-id-summary', 'environment', { available: true, status: 'ok', scope: 'app_signing_key_user_device', androidIdPresent: true, androidIdLength: 16, androidIdSha256: 'b'.repeat(64) }));
    expect(sections[0].title).toBe('Android ID 摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['available', 'status', 'scope', 'androidIdPresent', 'androidIdLength', 'androidIdSha256']);
  });
  it('图形摘要展示渲染器并限制 Vulkan 扩展列表', () => {
    const sections = buildSections(item('hardware/graphics-summary', 'hardware', { available: true, eglInitialized: true, eglError: 12288, eglVendor: 'Android', eglVersion: '1.5', glVendor: 'ARM', glRenderer: 'Mali', glVersion: 'OpenGL ES 3.2', eglExtensionsLength: 796, eglExtensionCount: 20, glExtensionsLength: 2910, glExtensionCount: 100, vulkanLibraryLoaded: true, vulkanExtensionResult: 0, vulkanExtensionCount: 10, vulkanExtensionNames: ['VK_KHR_surface'], vulkanExtensionNamesTruncated: false }));
    expect(sections[0].title).toBe('图形能力摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('glRenderer');
    expect(sections.find(section => section.key === 'vulkanExtensionNames')?.title).toBe('Vulkan 扩展名');
  });
  it('AppOps 模式码显示为可读状态并保留允许标志', () => {
    const sections = buildSections(item('security/special-access', 'security', { available: true, appOps: { 'android:camera': { mode: 0, allowed: true }, 'android:record_audio': { mode: 1, allowed: false }, 'android:fine_location': { mode: 4, allowed: false } } }));
    const rows = sections[0].rows;
    expect(rows.find(row => row.key === 'appOps.android:camera.mode')?.value).toBe('允许');
    expect(rows.find(row => row.key === 'appOps.android:record_audio.mode')?.value).toBe('忽略');
    expect(rows.find(row => row.key === 'appOps.android:fine_location.mode')?.value).toBe('前台允许');
    expect(rows.find(row => row.key === 'appOps.android:camera.allowed')?.value).toBe('true');
  });
  it('设备初始化与运行状态摘要按稳定字段顺序展示', () => {
    const sections = buildSections(item('environment/device-state', 'environment', { deviceProvisioned: true, userSetupComplete: true, bootCount: '7', lowRamDevice: false, userUnlocked: true, userRunning: true }));
    expect(sections[0].title).toBe('设备初始化与运行状态');
    expect(sections[0].rows.map(row => row.key)).toEqual(['deviceProvisioned', 'userSetupComplete', 'bootCount', 'lowRamDevice', 'userUnlocked', 'userRunning']);
  });
  it('用户与工作资料摘要只展示数量和托管状态', () => {
    const sections = buildSections(item('environment/user-profiles', 'environment', { available: true, status: 'ok', profileCount: 2, managedProfile: true }));
    expect(sections[0].title).toBe('用户与工作资料摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['available', 'status', 'profileCount', 'managedProfile']);
  });
  it('环境新增摘要按稳定顺序展示并保留原始对象', () => {
    const standby = item('environment/app-standby', 'environment', { available: true, status: 'ok', bucket: 10, bucketName: 'active' });
    expect(buildSections(standby)[0].rows.map(row => row.key)).toEqual(['available', 'status', 'bucket', 'bucketName']);
    const settings = item('environment/system-settings', 'environment', { available: true, status: 'allowed', settings: { screen_brightness: '120', window_animation_scale: '1' } });
    const original = JSON.stringify(settings);
    expect(buildSections(settings)[0].rows.map(row => row.key)).toEqual(['available', 'status', 'settings.screen_brightness', 'settings.window_animation_scale']);
    expect(JSON.stringify(settings)).toBe(original);
    const provider = item('environment/webview-provider', 'environment', { packageName: 'com.android.webview', versionName: '120.0', versionCode: 1200000, signatures: [{ sha256: 'a'.repeat(64) }] });
    expect(buildSections(provider).find(section => section.key === 'signatures')?.title).toBe('提供方签名 SHA-256');
  });
  it('进程退出摘要展示有限历史并格式化资源快照', () => {
    const sections = buildSections(item('environment/process-exits', 'environment', {
      available: true, status: 'ok', count: 1, limit: 32, reasons: { crash: 1 }, statuses: { '6': 1 },
      entries: [{ processName: 'com.example', pid: 12, reason: 4, reasonName: 'crash', status: 6, importance: 100, timestamp: 1, pssBytes: 1048576, rssBytes: 2097152, processStateSummaryLength: 0, processStateSummarySha256: null }],
    }));
    expect(sections[0].rows.map(row => row.key)).toEqual(['available', 'status', 'count', 'limit', 'reasons.crash', 'statuses.6']);
    expect(sections.find(section => section.key === 'entries')?.rows.find(row => row.key === '[0].pssBytes')?.value).toBe('1 MiB（1048576 B）');
  });
  it('内存摘要展示容量与应用堆边界', () => {
    const value = { available: true, status: 'ok', activityManagerAvailable: true, totalMemBytes: { $type: 'int64' as const, value: '8589934592' }, availMemBytes: { $type: 'int64' as const, value: '4294967296' }, thresholdBytes: { $type: 'int64' as const, value: '805306368' }, lowMemory: false, lowRamDevice: false, memoryClassMb: 256, largeMemoryClassMb: 512, runtimeMaxMemoryBytes: { $type: 'int64' as const, value: '536870912' }, runtimeTotalMemoryBytes: { $type: 'int64' as const, value: '268435456' }, runtimeFreeMemoryBytes: { $type: 'int64' as const, value: '134217728' }, heapUsedBytes: { $type: 'int64' as const, value: '134217728' }, runtimeProcessorCount: 8 };
    const original = JSON.stringify(value);
    const sections = buildSections(item('environment/memory-summary', 'environment', value));
    expect(sections[0].title).toBe('内存环境摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('totalMemBytes');
    expect(sections[0].rows.map(row => row.key)).toContain('memoryClassMb');
    expect(sections[0].rows.find(row => row.key === 'totalMemBytes')?.value).toBe('8 GiB（8589934592 B）');
    expect(sections[0].rows.find(row => row.key === 'thresholdBytes')?.value).toBe('768 MiB（805306368 B）');
    expect(JSON.stringify(value)).toBe(original);
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
  it('CA 信任库摘要展示用户证书计数而不展开 DER', () => {
    const sections = buildSections(item('security/trust-store-summary', 'security', { available: true, status: 'ok', certificateCount: 3, userCertificateCount: 1, systemCertificateCount: 2, otherCertificateCount: 0, hasUserCertificates: true, hashedCertificateCount: 3, uniqueSha256Count: 3, certificateSetSha256: 'a'.repeat(64), certificateReadErrorCount: 0, hashLimit: 4096, hashTruncated: false }));
    expect(sections[0].title).toBe('CA 信任库摘要');
    expect(sections[0].rows.map(row => row.key)).toContain('userCertificateCount');
  });
  it('Root 路径摘要区分不存在与观察受限，保留逐路径 errno', () => {
    const value = item('security/root-path-summary', 'security', { available: true, status: 'partial', pathChecks: 2, pathLimit: 32, missingCount: 1, deniedCount: 1, entries: [{ path: '/system/bin/su', status: 'missing', errno: 2, type: null }, { path: '/data/adb', status: 'denied', errno: 13, type: null }] });
    const original = JSON.stringify(value);
    const sections = buildSections(value);
    expect(sections[0].title).toBe('Root 相关路径检查摘要');
    expect(sections[0].rows.find(row => row.key === 'status')?.value).toBe('部分可观察');
    expect(sections[1].title).toBe('路径观察结果（固定 32 项，不执行）');
    expect(sections[1].rows.some(row => row.value === '路径不存在')).toBe(true);
    expect(sections[1].rows.some(row => row.value === '受限/未授权')).toBe(true);
    expect(sections[1].rows.some(row => row.key.endsWith('.errno') && row.value === '13')).toBe(true);
    expect(JSON.stringify(value)).toBe(original);
  });
  it('SDK 扩展保留版本 0、未知标识和明确枚举范围，原数据不变', () => {
    const value = item('build/sdk-extensions', 'build', { available: true, status: 'ok', enumerationMode: 'all-reported-extensions', entries: [{ extensionId: 30, version: 0 }, { extensionId: 1000000, version: 18 }, { extensionId: 2000000, version: 1 }] });
    const original = JSON.stringify(value), sections = buildSections(value);
    expect(sections[0].title).toBe('SDK 扩展版本摘要');
    expect(sections[0].rows.find(row => row.key === 'enumerationMode')?.value).toBe('系统公开的全部扩展');
    expect(sections[1].rows.find(row => row.key === '[0].version')?.value).toBe('0');
    expect(sections[1].rows.find(row => row.key === '[2].extensionId')?.value).toBe('2000000');
    const scoped = buildSections(item('build/sdk-extensions', 'build', { enumerationMode: 'android-r-only' }));
    expect(scoped[0].rows.find(row => row.key === 'enumerationMode')?.value).toBe('仅 Android R 扩展（API 30）');
    expect(JSON.stringify(value)).toBe(original);
  });
  it('系统字体展示有界元数据，保留无文件字体、未知字段和旧数据', () => {
    const value = item('environment/system-fonts', 'environment', { available: true, status: 'partial', fontCount: 129, returnedCount: 128, entryLimit: 128, entriesTruncated: true, entries: [{ fileName: null, ttcIndex: 0, weight: 400, slant: 0, localeTags: '', variationSettings: null, futureField: 'kept' }] });
    const original = JSON.stringify(value), sections = buildSections(value);
    expect(sections[0].title).toBe('系统字体集合摘要');
    expect(sections[0].rows.find(row => row.key === 'status')?.value).toBe('部分可观察');
    expect(sections[1].title).toBe('系统字体元数据（最多 128 项）');
    expect(sections[1].rows.find(row => row.key === '[0].fileName')?.value).toBe('null');
    expect(sections[1].rows.find(row => row.key === '[0].ttcIndex')?.value).toBe('0');
    expect(sections[1].rows.find(row => row.key === '[0].futureField')?.value).toBe('kept');
    expect(JSON.stringify(value)).toBe(original);
  });
  it('国际化版本按原始文本展示，保留尾部零、未知格式、空字符串与null', () => {
    for (const key of ['environment/icu-version', 'environment/unicode-version', 'environment/tzdata-version', 'environment/cldr-version']) {
      for (const value of ['75.1.0.0', 'future-version+data', '', null]) {
        const source = item(key, 'environment', value), before = JSON.stringify(source);
        expect(buildSections(source)[0].rows[0].value).toBe(value === null ? 'null' : value);
        expect(JSON.stringify(source)).toBe(before);
      }
    }
  });
  it('资源语言区分 App 与系统范围，保留原始标签和空值', () => {
    const value = item('environment/resource-locales', 'environment', { available: true, status: 'partial', appAssetLocaleCount: 257, appAssetLocaleTags: [null, '', 'iw', 'zh_CN', 'future-tag'], systemAssetLocaleTags: ['en-US'], futureField: 0 });
    const original = JSON.stringify(value), sections = buildSections(value);
    expect(sections.map(section => section.title)).toEqual(['资源语言集合摘要', 'App 已加载资源语言（含系统资源，最多 256 项）', '系统资源语言（最多 256 项）']);
    expect(sections[0].rows.find(row => row.key === 'status')?.value).toBe('部分可观察');
    expect(sections[0].rows.find(row => row.key === 'futureField')?.value).toBe('0');
    expect(sections[1].rows.map(row => row.value)).toEqual(['null', '', 'iw', 'zh_CN', 'future-tag']);
    expect(JSON.stringify(value)).toBe(original);
  });
  it('系统运行参数展示 16 KiB 页大小，并保留不可用项与原始值', () => {
    const value = item('kernel/sysconf-summary', 'kernel', { available: true, status: 'partial', pageSizeBytes: { $type: 'int64', value: '16384' }, clockTicksPerSecond: null, entries: [{ name: '_SC_PAGESIZE', status: 'ok', rawValue: { $type: 'int64', value: '16384' }, errorClass: null }, { name: '_SC_CLK_TCK', status: 'unavailable', rawValue: { $type: 'int64', value: '-1' }, errorClass: null }] });
    const original = JSON.stringify(value), sections = buildSections(value);
    expect(sections[0].title).toBe('内存页与时钟节拍摘要');
    expect(sections[0].rows.find(row => row.key === 'pageSizeBytes')?.value).toBe('16 KiB（16384 B）');
    expect(sections[0].rows.find(row => row.key === 'clockTicksPerSecond')?.value).toBe('null');
    expect(sections[1].title).toBe('系统调用原始结果（固定 2 项）');
    expect(sections[1].rows.find(row => row.key === '[1].status')?.value).toBe('接口不可用');
    expect(sections[1].rows.find(row => row.key === '[1].rawValue')?.value).toBe('-1');
    expect(JSON.stringify(value)).toBe(original);
  });
  it('Root 挂载标志摘要保留未知值和 errno，原始数据不变', () => {
    const value = item('security/root-mount-summary', 'security', { available: true, status: 'partial', pathChecks: 2, pathLimit: 7, readOnlyCount: 1, missingCount: 1, writeProbeAttempted: false, entries: [{ path: '/system', status: 'ok', errno: 0, readOnly: true, noSuid: false, noDev: false, noExec: false }, { path: '/sbin', status: 'missing', errno: 2, readOnly: null, noSuid: null, noDev: null, noExec: null }] });
    const original = JSON.stringify(value), sections = buildSections(value);
    expect(sections[0].title).toBe('Root 相关系统路径挂载标志');
    expect(sections[0].rows.find(row => row.key === 'status')?.value).toBe('部分可观察');
    expect(sections[1].title).toBe('系统路径挂载标志（固定 7 项，不写入）');
    expect(sections[1].rows.find(row => row.key === '[1].readOnly')?.value).toBe('null');
    expect(sections[1].rows.find(row => row.key === '[1].status')?.value).toBe('路径不存在');
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
  it('应用组件数组改为计数摘要并保留原始 JSON', () => {
    const value = item('packages/com.example', 'packages', { packageName: 'com.example', activities: [{ name: 'com.example.Main', enabled: true, exported: true, processName: 'main', permission: 'android.permission.CAMERA' }], providers: [{ name: 'com.example.Provider', enabled: true, exported: false, processName: 'provider', authority: 'com.example', grantUriPermissions: true }] });
    const original = JSON.stringify(value);
    const sections = buildSections(value);
    const activities = sections.find(section => section.key === 'activities');
    const providers = sections.find(section => section.key === 'providers');
    expect(activities?.title).toContain('原始条目保留');
    expect(activities?.rows.find(row => row.key === 'count')?.value).toBe('1');
    expect(activities?.rows.find(row => row.key === 'exportedCount')?.value).toBe('1');
    expect(providers?.rows.find(row => row.key === 'authorityCount')?.value).toBe('1');
    expect(JSON.stringify(sections)).not.toContain('com.example.Main');
    expect(JSON.stringify(value)).toBe(original);
  });
  it('proc 多行文本按行拆分，正确行不会因另一行变化而整体标红', () => {
    const sections = buildSections(item('kernel/proc/diskstats', 'kernel', '8 0 sda 1 2\n8 1 sda1 3 4\n'));
    expect(sections[0].table).toBe(true);
    expect(sections[0].rows.map(row => row.key)).toEqual(['line-0', 'line-1']);
  });
  it('当前进程状态摘要展示调试与隔离线索', () => {
    const sections = buildSections(item('kernel/process-status-summary', 'kernel', { available: true, status: 'ok', traced: false, tracerPid: 0, state: 'S (sleeping)', threadCount: 4, noNewPrivs: 0, seccompMode: 2, capEffPresent: true, capBndPresent: true, fieldsObserved: 58, truncated: false }));
    expect(sections[0].title).toBe('当前进程状态摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['available', 'status', 'traced', 'tracerPid', 'state', 'threadCount', 'noNewPrivs', 'seccompMode', 'capEffPresent', 'capBndPresent', 'fieldsObserved', 'truncated']);
  });
  it('内存映射摘要展示权限与来源计数而不展开路径', () => {
    const value = item('kernel/maps-summary', 'kernel', { available: true, status: 'ok', mapCount: 20, invalidLineCount: 0, executableMappingCount: 8, writableMappingCount: 7, privateMappingCount: 18, sharedMappingCount: 2, anonymousMappingCount: 9, fileBackedMappingCount: 11, devMappingCount: 1, memfdMappingCount: 0, ashmemMappingCount: 0, apkMappingCount: 5, apexMappingCount: 3, deletedMappingCount: 0, markerMatchCount: 0, markerPresence: { frida: false, gadget: false, xposed: false, substrate: false, riru: false, zygisk: false, magisk: false }, permissionCounts: { 'r-xp': 8, 'rw-p': 7 }, truncated: false, errno: null });
    const sections = buildSections(value);
    expect(sections[0].title).toBe('当前进程内存映射摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['available', 'status', 'mapCount', 'invalidLineCount', 'executableMappingCount', 'writableMappingCount', 'privateMappingCount', 'sharedMappingCount', 'anonymousMappingCount', 'fileBackedMappingCount', 'devMappingCount', 'memfdMappingCount', 'ashmemMappingCount', 'apkMappingCount', 'apexMappingCount', 'deletedMappingCount', 'markerMatchCount', 'markerPresence.frida', 'markerPresence.gadget', 'markerPresence.xposed', 'markerPresence.substrate', 'markerPresence.riru', 'markerPresence.zygisk', 'markerPresence.magisk', 'permissionCounts.r-xp', 'permissionCounts.rw-p', 'truncated', 'errno']);
  });
  it('挂载拓扑摘要展示文件系统计数而不展开路径', () => {
    const value = item('kernel/mount-summary', 'kernel', { available: true, status: 'ok', entryCount: 12, readOnlyCount: 4, sharedMountCount: 2, masterMountCount: 1, optionalFieldCount: 3, overlayCount: 1, tmpfsCount: 4, procCount: 1, sysfsCount: 1, ext4Count: 2, rootFsType: 'overlay', fsTypeCount: 5, fsTypesTruncated: false, truncated: false, fsTypeCounts: { overlay: 1, tmpfs: 4 } });
    const sections = buildSections(value);
    expect(sections[0].title).toBe('挂载拓扑摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['available', 'status', 'entryCount', 'readOnlyCount', 'sharedMountCount', 'masterMountCount', 'optionalFieldCount', 'overlayCount', 'tmpfsCount', 'procCount', 'sysfsCount', 'ext4Count', 'rootFsType', 'fsTypeCount', 'fsTypesTruncated', 'truncated', 'fsTypeCounts.overlay', 'fsTypeCounts.tmpfs']);
  });
  it('Cgroup 摘要展示版本、控制器和环境标记', () => {
    const sections = buildSections(item('kernel/cgroup-summary', 'kernel', { available: true, status: 'ok', cgroupVersion: 'v2', entryCount: 1, v1EntryCount: 0, v2EntryCount: 1, controllerCount: 3, controllerNamesTruncated: false, maxPathDepth: 2, dockerMarker: false, kubernetesMarker: false, lxcMarker: false, sandboxMarker: false, truncated: false, controllerNames: ['cpu', 'memory', 'pids'] }));
    expect(sections[0].title).toBe('Cgroup 环境摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['available', 'status', 'cgroupVersion', 'entryCount', 'v1EntryCount', 'v2EntryCount', 'controllerCount', 'controllerNamesTruncated', 'maxPathDepth', 'dockerMarker', 'kubernetesMarker', 'lxcMarker', 'sandboxMarker', 'truncated']);
    expect(sections.find(section => section.key === 'controllerNames')?.title).toBe('Cgroup 控制器');
  });
  it('CPU 摘要展示拓扑、频率和调速器计数', () => {
    const sections = buildSections(item('kernel/cpu-summary', 'kernel', { available: true, status: 'ok', logicalProcessorCount: 8, runtimeProcessorCount: 8, presentCount: 8, onlineCount: 8, offlineCount: 0, frequencyValueCount: 16, minFrequencyKHz: 300000, maxFrequencyKHz: 2200000, governorCounts: { schedutil: 8 }, coreIdCount: 4, physicalPackageIdCount: 1, topologyValuesTruncated: false, cpuDirectoriesTruncated: false, coreIds: [0, 1, 2, 3], physicalPackageIds: [0] }));
    expect(sections[0].title).toBe('CPU 拓扑与频率摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['available', 'status', 'logicalProcessorCount', 'runtimeProcessorCount', 'presentCount', 'onlineCount', 'offlineCount', 'frequencyValueCount', 'minFrequencyKHz', 'maxFrequencyKHz', 'governorCounts.schedutil', 'coreIdCount', 'physicalPackageIdCount', 'topologyValuesTruncated', 'cpuDirectoriesTruncated']);
    expect(sections.find(section => section.key === 'coreIds')?.title).toBe('CPU 核心 ID');
  });
});
