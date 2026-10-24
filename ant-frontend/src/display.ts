import type { Item, Json } from './core/types';

export interface DisplayRow { key: string; label: string; value: string }
export interface DisplaySection { key: string; title: string; rows: DisplayRow[]; table: boolean }

type JsonObject = { [key: string]: Json };

function object(value: Json): value is JsonObject {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function text(value: Json): string {
  if (value === null) return 'null';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  if (object(value) && value.$type === 'int64' && typeof value.value === 'string') return value.value;
  return JSON.stringify(value, null, 2);
}

function row(key: string, value: Json, label = key): DisplayRow { return { key, label, value: text(value) }; }

function objectRows(value: JsonObject, keys?: string[]): DisplayRow[] {
  const order = keys || Object.keys(value);
  const used = new Set<string>();
  return order.flatMap(key => {
    if (used.has(key) || !(key in value) || Array.isArray(value[key])) return [];
    used.add(key);
    return flattenRows(value[key], key);
  });
}

// 数组保留采集顺序与重复项；对象字段各占一行，不再把整条记录塞成 JSON 字符串。
function flattenRows(value: Json, path: string): DisplayRow[] {
  if (object(value) && value.$type !== 'int64' && Object.keys(value).length) return Object.entries(value).flatMap(([key, child]) => flattenRows(child, `${path}.${key}`));
  if (Array.isArray(value) && value.length) return value.flatMap((child, index) => flattenRows(child, `${path}[${index}]`));
  return [row(path, value)];
}

function arrayRows(value: Json[], label: string): DisplayRow[] {
  if (!value.length) return [row(label, [])];
  return value.flatMap((entry, index) => flattenRows(entry, `[${index}]`));
}

function section(key: string, title: string, rows: DisplayRow[], table = false): DisplaySection {
  return { key, title, rows, table };
}

function textLines(value: string, title = '文本行'): DisplaySection[] {
  const normalized = value.replaceAll('\r\n', '\n');
  const lines = normalized.endsWith('\n') ? normalized.slice(0, -1).split('\n') : normalized.split('\n');
  return [section('lines', title, lines.map((line, index) => row(`line-${index}`, line, `第 ${index + 1} 行`)), true)];
}

function commandOutput(value: Json): Json {
  if (!object(value) || typeof value.stdout !== 'string') return value;
  if ('argv' in value || 'exitCode' in value || 'stderr' in value) return value.stdout;
  return value;
}

function buildProperties(value: Json): DisplaySection[] {
  const output = commandOutput(value);
  if (typeof output === 'string') {
    const rows = output.split('\n').filter(line => line.length).map((line, index) => {
      const match = /^\[([^\]]+)\]: \[(.*)\]$/.exec(line);
      return match ? row(match[1], match[2], match[1]) : row(`line-${index}`, line, `第 ${index + 1} 行`);
    });
    return [section('stdout', '设备属性 stdout', rows, true)];
  }
  if (!object(output)) return [section('value', '采集值', [row('value', output)], false)];
  return [section('value', '设备属性', objectRows(output), false)];
}

function displaySections(value: Json): DisplaySection[] {
  if (!object(value)) return [section('value', '显示器数据', [row('value', value)])];
  const sections: DisplaySection[] = [];
  const identity = objectRows(value, ['displayId', 'name', 'flags', 'state', 'rotation']);
  const geometry = objectRows(value, ['widthPixels', 'heightPixels', 'density', 'densityDpi', 'scaledDensity', 'xdpi', 'ydpi', 'refreshRate']);
  if (identity.length) sections.push(section('identity', '基础显示', identity));
  if (geometry.length) sections.push(section('geometry', '分辨率与刷新', geometry));
  if (Array.isArray(value.supportedModes)) sections.push(section('modes', '支持模式', arrayRows(value.supportedModes, 'mode'), true));
  const sync = objectRows(value, ['presentationDeadlineNanos', 'appVsyncOffsetNanos']);
  if (sync.length) sections.push(section('sync', '同步参数', sync));
  if (object(value.hdr)) sections.push(section('hdr', 'HDR', flattenRows(value.hdr, 'hdr'), false));
  const covered = new Set(['displayId', 'name', 'flags', 'state', 'rotation', 'widthPixels', 'heightPixels', 'density', 'densityDpi', 'scaledDensity', 'xdpi', 'ydpi', 'refreshRate', 'supportedModes', 'presentationDeadlineNanos', 'appVsyncOffsetNanos', 'hdr']);
  const extra = Object.fromEntries(Object.entries(value).filter(([key]) => !covered.has(key)));
  if (Object.keys(extra).length) sections.push(...genericSections(extra).map(item => ({ ...item, key: `extra-${item.key}` })));
  return sections.length ? sections : [section('value', '显示器数据', objectRows(value))];
}

function inputSections(value: Json): DisplaySection[] {
  if (!object(value)) return [section('value', '输入设备', [row('value', value)])];
  const sections = [section('identity', '设备信息', objectRows(value))];
  for (const [key, child] of Object.entries(value)) if (Array.isArray(child)) sections.push(section(key, key === 'motionRanges' ? '运动范围' : key, arrayRows(child, key), true));
  return sections.filter(item => item.rows.length);
}

function networkSections(value: Json): DisplaySection[] {
  if (!Array.isArray(value)) return genericSections(value).map(child => ({ ...child, title: `网络 · ${child.title}` }));
  return value.map((entry, index) => {
    if (!object(entry)) return section(`interface-${index}`, `接口 ${index}`, [row('value', entry)]);
    const name = typeof entry.name === 'string' ? entry.name : `接口 ${index}`;
    const sections = [section(`${index}-meta`, name, objectRows(entry))];
    for (const [key, child] of Object.entries(entry)) if (Array.isArray(child)) sections.push(section(`${index}-${key}`, `${name} · ${key}`, arrayRows(child, key), true));
    return sections;
  }).flat().filter(item => item.rows.length);
}

function packageSections(value: Json): DisplaySection[] {
  if (!object(value)) return [section('value', '应用数据', [row('value', value)])];
  const sections: DisplaySection[] = [section('package', '包信息', objectRows(value))];
  for (const [key, child] of Object.entries(value)) if (Array.isArray(child)) {
    if (key === 'signatures') sections.push(signatureSections(child));
    else sections.push(section(key, key, arrayRows(child, key), true));
  }
  return sections.filter(item => item.rows.length);
}

function signatureSections(value: Json[]): DisplaySection {
  const rows: DisplayRow[] = [row('count', value.length, '签名数量')];
  value.forEach((entry, index) => {
    if (!object(entry)) { rows.push(row(`[${index}]`, entry, `签名 ${index + 1}`)); return; }
    rows.push(row(`[${index}].sha256`, entry.sha256 ?? null, `签名 ${index + 1} · SHA-256`));
    if (typeof entry.derBase64 === 'string') rows.push(row(`[${index}].derBase64.length`, entry.derBase64.length, `签名 ${index + 1} · 原始 DER 长度`));
  });
  return section('signatures', '签名摘要（原始 DER 保留在 JSON）', rows, true);
}

function genericSections(value: Json): DisplaySection[] {
  if (typeof value === 'string' && value.includes('\n')) return textLines(value);
  if (Array.isArray(value)) return [section('array', '采集数组', arrayRows(value, 'item'), true)];
  if (!object(value)) return [section('value', '采集值', [row('value', value)])];
  const sections: DisplaySection[] = [];
  const scalarRows = objectRows(value);
  if (scalarRows.length) sections.push(section('fields', '字段', scalarRows));
  for (const [key, child] of Object.entries(value)) if (Array.isArray(child)) sections.push(section(key, key, arrayRows(child, key), true));
  return sections.length ? sections : [section('value', '采集值', [row('value', value)])];
}

function trustStoreSections(value: Json): DisplaySection[] {
  if (!Array.isArray(value)) return genericSections(value);
  const rows: DisplayRow[] = [row('count', value.length, '证书数量')];
  value.forEach((entry, index) => {
    if (!object(entry)) { rows.push(row(`[${index}]`, entry, `证书 ${index + 1}`)); return; }
    rows.push(row(`[${index}].alias`, entry.alias ?? null, `证书 ${index + 1} · 别名`));
    rows.push(row(`[${index}].sha256`, entry.sha256 ?? null, `证书 ${index + 1} · SHA-256`));
    if (typeof entry.derBase64 === 'string') rows.push(row(`[${index}].derBase64.length`, entry.derBase64.length, `证书 ${index + 1} · 原始 DER 长度`));
  });
  return [section('certificates', 'CA 证书摘要（原始 DER 保留在 JSON）', rows, true)];
}

function riskSummarySections(key: string, value: Json): DisplaySection[] | undefined {
  if (!object(value)) return undefined;
  const definitions: Record<string, { title: string; order: string[]; arrays: string[] }> = {
    'build/risk-properties-summary': { title: '风控原生属性摘要', order: ['observedCount', 'knownCount', 'properties'], arrays: ['observedProperties', 'missingProperties'] },
    'network/risk-signals': { title: '活动链路摘要', order: ['present', 'capabilitiesAvailable', 'networkHandle', 'vpn', 'validated', 'captivePortal', 'notVpn', 'notMetered', 'notRoaming', 'notRestricted', 'trusted', 'foreground', 'metered', 'interfaceName', 'httpProxy'], arrays: ['transports'] },
    'virtualization/risk-summary': { title: '虚拟化与提权线索摘要', order: ['pathChecks', 'evidenceOnly', 'testKeys', 'conclusion'], arrays: ['presentPaths', 'pathErrors', 'matchedBuildMarkers'] },
    'hardware/drm/widevine': { title: 'Widevine DRM 能力', order: ['supported', 'schemeUuid', 'vendor', 'version', 'description', 'algorithms', 'maxSecurityLevel', 'deviceUniqueId'], arrays: [] },
    'hardware/features-summary': { title: '系统特性能力摘要', order: ['count', 'namedCount', 'versionedCount', 'maxVersion', 'featureNamesTruncated'], arrays: ['featureNames', 'glEsVersions'] },
    'attestation/play-integrity-environment': { title: 'Play Integrity 本地环境', order: ['verdictAvailable', 'conclusion', 'playServices', 'playStore'], arrays: [] },
    'security/device-management': { title: '设备管理与用户环境', order: ['deviceOwnerForSelf', 'profileOwnerForSelf', 'managedProfile', 'userUnlocked', 'storageEncryptionStatus', 'restrictions'], arrays: [] },
    'security/special-access': { title: '本 App 特殊访问状态', order: ['available', 'canDrawOverlays', 'appOps'], arrays: [] },
    'security/lock-biometric': { title: '锁屏与生物识别环境', order: ['keyguardSecure', 'keyguardLocked', 'deviceSecure', 'deviceLocked', 'fingerprintFeature', 'faceFeature', 'irisFeature'], arrays: [] },
    'security/accessibility': { title: '启用的无障碍服务', order: ['available', 'count'], arrays: ['services'] },
    'security/self-integrity': { title: '当前 App 签名与安装环境', order: ['packageName', 'versionName', 'versionCode', 'installer', 'enabled', 'applicationFlags', 'debuggable', 'hasCode'], arrays: ['signingCertificateSha256'] },
    'environment/risk-settings': { title: '系统风控相关设置', order: ['adbEnabled', 'developmentSettingsEnabled', 'airplaneModeOn', 'autoTime', 'autoTimeZone', 'privateDnsMode', 'privateDnsSpecifier', 'wifiOn', 'bluetoothOn', 'accessibilityEnabled', 'mockLocation', 'installNonMarketApps'], arrays: [] },
    'environment/location': { title: '定位与 GNSS 能力', order: ['available', 'locationEnabled', 'providerCount', 'hasGpsProvider', 'hasNetworkProvider', 'hasFusedProvider', 'gnssHardwareModelName', 'gnssYearOfHardware', 'gnssCapabilities', 'gnssAntennaCount', 'gnssAntennaInfoTruncated'], arrays: ['providers', 'gnssAntennaCarrierFrequenciesMHz'] },
    'sensors/summary': { title: '传感器能力摘要', order: ['count', 'wakeUpCount', 'dynamicCount', 'directChannelCount', 'vendorsTruncated', 'typeCounts'], arrays: ['vendors'] },
    'environment/webview-user-agent': { title: 'WebView 默认 User-Agent', order: ['userAgent', 'length'], arrays: [] },
    'environment/power-thermal': { title: '电源与热状态', order: ['powerSaveMode', 'interactive', 'deviceIdleMode', 'lightDeviceIdleMode', 'locationPowerSaveMode', 'thermalStatus', 'thermalHeadroom'], arrays: [] },
    'hardware/usb': { title: 'USB 设备公开清单', order: ['count'], arrays: ['devices'] },
  };
  const definition = definitions[key];
  if (!definition) return undefined;
  const sections: DisplaySection[] = [section('summary', definition.title, objectRows(value, definition.order))];
  for (const arrayKey of definition.arrays) {
    if (Array.isArray(value[arrayKey])) {
      const title = key === 'build/risk-properties-summary'
        ? arrayKey === 'observedProperties' ? '已观察属性' : '当前缺失属性'
        : key === 'environment/location'
          ? arrayKey === 'providers' ? '定位 provider' : 'GNSS 天线载波频率（MHz）'
          : key === 'sensors/summary' && arrayKey === 'vendors' ? '传感器厂商'
          : key === 'hardware/features-summary'
            ? arrayKey === 'featureNames' ? '系统特性名' : 'OpenGL ES 要求版本'
          : arrayKey;
      sections.push(section(arrayKey, title, arrayRows(value[arrayKey] as Json[], arrayKey), true));
    }
  }
  if (key === 'network/risk-signals' && typeof value.capabilitiesRaw === 'string') sections.push(section('capabilitiesRaw', '系统原始能力字符串', [row('value', value.capabilitiesRaw)], false));
  return sections.filter(item => item.rows.length);
}

export function buildSections(item?: Item): DisplaySection[] {
  if (!item) return [];
  const value = commandOutput(item.value);
  // visibility 是采集器写入的固定范围说明，不是从设备读取的返回值。
  if (item.key === 'packages/enumeration' && object(value)) return [section('enumeration', '应用枚举结果', objectRows(value, ['returnedCount']))];
  if (item.key === 'security/trust-store') return trustStoreSections(value);
  const riskSummary = riskSummarySections(item.key, value);
  if (riskSummary) return riskSummary;
  if (item.key === 'build/getprop' || item.source.api === '/system/bin/getprop' || item.category === 'build' && item.key.includes('property')) return buildProperties(value);
  if (item.category === 'display' || item.key.startsWith('display/')) return displaySections(value);
  if (item.category === 'input' || item.key.startsWith('input/')) return inputSections(value);
  if (item.key === 'binder/service-list') return typeof value === 'string' ? [section('stdout', '服务清单 stdout', value.split('\n').filter(Boolean).map((line, index) => row(String(index), line, `第 ${index + 1} 行`)), true)] : genericSections(value);
  if (item.category === 'network' || item.key.startsWith('network/')) return networkSections(value);
  if (item.category === 'packages' || item.key.startsWith('packages/')) return [...packageSections(value), ...(item.installer ? genericSections(item.installer.value).map(child => ({ ...child, key: `installer-${child.key}`, title: `安装来源 · ${child.title}` })) : [])];
  return genericSections(value);
}

export function displayValue(item?: Item): string {
  const sections = buildSections(item);
  return sections.flatMap(sectionItem => sectionItem.rows.map(valueRow => `${sectionItem.key}/${valueRow.key}:${valueRow.value}`)).join('\n');
}
