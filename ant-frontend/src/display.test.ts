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
  it('风控摘要使用专用分组并保留数组与原始链路字符串', () => {
    const sections = buildSections(item('network/risk-signals', 'network', { present: true, validated: true, vpn: false, transports: [1], capabilitiesRaw: 'raw-capabilities' }));
    expect(sections.find(section => section.key === 'summary')?.title).toBe('活动链路摘要');
    expect(sections.find(section => section.key === 'transports')?.rows[0].value).toBe('1');
    expect(sections.find(section => section.key === 'capabilitiesRaw')?.rows[0].value).toBe('raw-capabilities');
  });
  it('原生风控属性摘要保持固定字段与可读分组', () => {
    const sections = buildSections(item('build/risk-properties-summary', 'build', { observedCount: 4, knownCount: 19, properties: { 'ro.boot.verifiedbootstate': 'green', 'ro.debuggable': '0', 'ro.adb.secure': '1' } }));
    expect(sections[0].title).toBe('风控原生属性摘要');
    expect(sections[0].rows.map(row => row.key)).toEqual(['observedCount', 'knownCount', 'properties.ro.boot.verifiedbootstate', 'properties.ro.debuggable', 'properties.ro.adb.secure']);
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
