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

const BYTE_UNITS = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB', 'EiB'];

function byteText(key: string, value: Json): string | undefined {
  const field = key.split('.').pop() || key;
  if (!field.endsWith('Bytes')) return undefined;
  const raw = typeof value === 'number' && Number.isInteger(value)
    ? String(value)
    : object(value) && value.$type === 'int64' && typeof value.value === 'string'
      ? value.value
      : typeof value === 'string' && /^-?\d+$/.test(value)
        ? value
        : undefined;
  if (raw === undefined) return undefined;
  const bytes = BigInt(raw);
  if (bytes < 1024n) return `${raw} B`;
  let unit = 0;
  let divisor = 1n;
  while (bytes / divisor >= 1024n && unit < BYTE_UNITS.length - 1) {
    divisor *= 1024n;
    unit += 1;
  }
  const whole = bytes / divisor;
  const hundredths = (bytes % divisor * 100n) / divisor;
  const fraction = hundredths === 0n ? '' : `.${hundredths.toString().padStart(2, '0').replace(/0+$/, '')}`;
  return `${whole}${fraction} ${BYTE_UNITS[unit]}（${raw} B）`;
}

const STATUS_LABELS: Record<string, string> = {
  allowed: '已授权', denied: '受限/未授权', unsupported: '系统不支持', unavailable: '接口不可用',
  success: '可用', none_enrolled: '未录入', no_hardware: '无硬件', hardware_unavailable: '硬件暂不可用',
  security_update_required: '需要安全更新', error: '读取异常', skipped: '未执行', truncated: '已截断', ok: '成功',
  present: '已观察到', missing: '路径不存在', unresolved: '路径未解析', partial: '部分可观察',
  'not-found-or-not-visible': '未找到或当前 App 不可见',
  'multiple-signers': '多签名，不适用单签名轮换历史',
};

const APP_OP_MODE_LABELS: Record<number, string> = {
  0: '允许', 1: '忽略', 2: '拒绝', 3: '默认', 4: '前台允许',
};

const PERMISSION_PROTECTION_LABELS: Record<number, string> = {
  0: '普通', 1: '危险', 2: '签名', 3: '签名或系统', 4: '内部',
};

function displayText(key: string, value: Json): string {
  const field = key.split('.').pop() || key;
  const bytes = byteText(key, value);
  if (bytes !== undefined) return bytes;
  if (typeof value === 'number' && field === 'mode' && key.startsWith('appOps.')) return APP_OP_MODE_LABELS[value] || `模式 ${value}`;
  if (typeof value === 'number' && field === 'protectionBase') return PERMISSION_PROTECTION_LABELS[value] || `保护级别 ${value}`;
  if (typeof value === 'string' && (field === 'access' || field.endsWith('Access') || field.endsWith('Status'))) {
    if (Object.hasOwn(STATUS_LABELS, value)) return STATUS_LABELS[value];
    return value.startsWith('error_') ? `异常（${value.slice(6)}）` : value;
  }
  return text(value);
}

function row(key: string, value: Json, label = key): DisplayRow { return { key, label, value: displayText(key, value) }; }

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
function flattenRows(value: Json, path: string, raw = false): DisplayRow[] {
  if (object(value) && value.$type !== 'int64' && Object.keys(value).length) return Object.entries(value).flatMap(([key, child]) => flattenRows(child, `${path}.${key}`, raw));
  if (Array.isArray(value) && value.length) return value.flatMap((child, index) => flattenRows(child, `${path}[${index}]`, raw));
  // 业务块：指定完整字段保留JSON类型，避免字符串与布尔/数字/null格式化后漏标差异。
  return [raw ? { key: path, label: path, value: JSON.stringify(value, null, 2) } : row(path, value)];
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
  const capabilities = objectRows(value, ['highestHdrSdrRatio', 'highestHdrSdrRatioStatus', 'hasArrSupport', 'hasArrSupportStatus', 'highestHdrSdrRatioErrorClass', 'hasArrSupportErrorClass']);
  if (capabilities.length) sections.push(section('capabilities', 'HDR 与自动刷新率能力', capabilities));
  if (object(value.hdr)) sections.push(section('hdr', 'HDR', flattenRows(value.hdr, 'hdr'), false));
  if (Object.hasOwn(value, 'colorCapabilities')) sections.push(section('color', '色彩能力', flattenRows(value.colorCapabilities, 'colorCapabilities', true), true));
  if (Object.hasOwn(value, 'deviceProductInfo')) sections.push(section('product', '显示设备产品信息', flattenRows(value.deviceProductInfo, 'deviceProductInfo', true), true));
  const covered = new Set(['displayId', 'name', 'flags', 'state', 'rotation', 'widthPixels', 'heightPixels', 'density', 'densityDpi', 'scaledDensity', 'xdpi', 'ydpi', 'refreshRate', 'supportedModes', 'presentationDeadlineNanos', 'appVsyncOffsetNanos', 'highestHdrSdrRatio', 'highestHdrSdrRatioStatus', 'hasArrSupport', 'hasArrSupportStatus', 'highestHdrSdrRatioErrorClass', 'hasArrSupportErrorClass', 'hdr']);
  covered.add('colorCapabilities');
  covered.add('deviceProductInfo');
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

function wifiCachedScanSections(value: Json): DisplaySection[] {
  if (!Array.isArray(value)) return genericSections(value);
  const frequencies: number[] = [], capabilities: string[] = [], channelWidths: Record<string, number> = {};
  let ssidPresentCount = 0, bssidPresentCount = 0, levelMin: number | null = null, levelMax: number | null = null;
  for (const entry of value) {
    if (!object(entry)) continue;
    if (typeof entry.ssid === 'string' && entry.ssid.length) ssidPresentCount++;
    if (typeof entry.bssid === 'string' && entry.bssid.length) bssidPresentCount++;
    if (typeof entry.frequency === 'number' && !frequencies.includes(entry.frequency) && frequencies.length < 64) frequencies.push(entry.frequency);
    if (typeof entry.capabilities === 'string' && !capabilities.includes(entry.capabilities) && capabilities.length < 64) capabilities.push(entry.capabilities);
    if (typeof entry.channelWidth === 'number') channelWidths[String(entry.channelWidth)] = (channelWidths[String(entry.channelWidth)] || 0) + 1;
    if (typeof entry.level === 'number') { levelMin = levelMin === null ? entry.level : Math.min(levelMin, entry.level); levelMax = levelMax === null ? entry.level : Math.max(levelMax, entry.level); }
  }
  const sections = [section('summary', 'Wi-Fi 缓存扫描摘要（原始 SSID/BSSID 保留在 JSON）', objectRows({ count: value.length, ssidPresentCount, bssidPresentCount, frequencyCount: frequencies.length, capabilitiesCount: capabilities.length, levelMin, levelMax }))];
  if (frequencies.length) sections.push(section('frequencies', '频率（MHz）', frequencies.map((frequency, index) => row(`[${index}]`, frequency)), true));
  if (capabilities.length) sections.push(section('capabilities', '能力标记（去重）', capabilities.map((capability, index) => row(`[${index}]`, capability)), true));
  if (Object.keys(channelWidths).length) sections.push(section('channelWidths', '信道宽度计数', objectRows(channelWidths)));
  return sections;
}

function packageSections(value: Json): DisplaySection[] {
  if (!object(value)) return [section('value', '应用数据', [row('value', value)])];
  const sections: DisplaySection[] = [section('package', '包信息', objectRows(value))];
  for (const [key, child] of Object.entries(value)) if (Array.isArray(child)) {
    if (key === 'signatures') sections.push(signatureSections(child));
    else if (key === 'activities' || key === 'receivers' || key === 'services' || key === 'providers') sections.push(componentSections(key, child));
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

function componentSections(key: string, value: Json[]): DisplaySection {
  const labels: Record<string, string> = { activities: 'Activity', receivers: 'Receiver', services: 'Service', providers: 'Provider' };
  let enabledCount = 0, exportedCount = 0, permissionCount = 0, authorityCount = 0, grantUriPermissionsCount = 0;
  const processes = new Set<string>();
  for (const entry of value) {
    if (!object(entry)) continue;
    if (entry.enabled === true) enabledCount++;
    if (entry.exported === true) exportedCount++;
    if (typeof entry.permission === 'string' && entry.permission) permissionCount++;
    if (typeof entry.processName === 'string' && entry.processName) processes.add(entry.processName);
    if (key === 'providers') {
      if (typeof entry.authority === 'string' && entry.authority) authorityCount++;
      if (entry.grantUriPermissions === true) grantUriPermissionsCount++;
    }
  }
  const summary: JsonObject = { count: value.length, enabledCount, exportedCount, permissionCount, processNameCount: processes.size };
  if (key === 'providers') Object.assign(summary, { authorityCount, grantUriPermissionsCount });
  return section(key, `${labels[key] || key} 摘要（原始条目保留在 JSON）`, objectRows(summary));
}

function usbSections(value: Json): DisplaySection[] {
  if (!object(value) || !Array.isArray(value.devices)) return genericSections(value);
  const vendorIds: number[] = [], productIds: number[] = [], classCounts: JsonObject = {};
  let interfaceCount = 0, endpointCount = 0;
  for (const device of value.devices) {
    if (!object(device)) continue;
    if (typeof device.vendorId === 'number' && !vendorIds.includes(device.vendorId) && vendorIds.length < 64) vendorIds.push(device.vendorId);
    if (typeof device.productId === 'number' && !productIds.includes(device.productId) && productIds.length < 64) productIds.push(device.productId);
    if (!Array.isArray(device.interfaces)) continue;
    interfaceCount += device.interfaces.length;
    for (const face of device.interfaces) if (object(face)) {
      if (typeof face.endpointCount === 'number') endpointCount += face.endpointCount;
      if (typeof face.class === 'number') classCounts[String(face.class)] = typeof classCounts[String(face.class)] === 'number' ? (classCounts[String(face.class)] as number) + 1 : 1;
    }
  }
  const summary = objectRows({ count: typeof value.count === 'number' ? value.count : value.devices.length, vendorIdCount: vendorIds.length, productIdCount: productIds.length, interfaceCount, endpointCount, interfaceClassCounts: classCounts });
  return [section('summary', 'USB 设备摘要', summary), section('vendorIds', '厂商 ID', vendorIds.map((id, index) => row(`[${index}]`, id)), true), section('productIds', '产品 ID', productIds.map((id, index) => row(`[${index}]`, id)), true), section('descriptors', 'USB 设备与接口完整字段', Object.entries(value).flatMap(([key, child]) => flattenRows(child, key, true)), true)].filter(item => item.rows.length);
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

function attestationSections(key: string, value: Json): DisplaySection[] {
  if (!object(value)) return [section('value', '密钥认证数据', [row('value', value)])];
  const title = key === 'attestation/tee' ? 'TEE 密钥认证' : key === 'attestation/strongbox' ? 'StrongBox 密钥认证' : 'AndroidKeyStore 密钥安全级别';
  const summary = objectRows(value, ['requestedAttestation', 'requestedStrongBox', 'algorithm', 'insideSecureHardware', 'securityLevel']);
  const encoded: DisplayRow[] = [];
  for (const field of ['challengeBase64', 'signatureBase64', 'publicKeyBase64']) if (typeof value[field] === 'string') encoded.push(row(`${field}.length`, value[field].length, `${field} 长度`));
  if (Array.isArray(value.certificateChainBase64)) {
    encoded.push(row('certificateChainBase64.count', value.certificateChainBase64.length, '证书链数量'));
    value.certificateChainBase64.forEach((certificate, index) => { if (typeof certificate === 'string') encoded.push(row(`certificateChainBase64[${index}].length`, certificate.length, `证书 ${index + 1} 长度`)); });
  }
  const sections: DisplaySection[] = [section('summary', title, summary)];
  if (encoded.length) sections.push(section('encoded', '编码字段长度（原始值保留在 JSON）', encoded, true));
  const covered = new Set(['requestedAttestation', 'requestedStrongBox', 'algorithm', 'insideSecureHardware', 'securityLevel', 'challengeBase64', 'signatureBase64', 'publicKeyBase64', 'certificateChainBase64']);
  const extra = Object.fromEntries(Object.entries(value).filter(([field]) => !covered.has(field)));
  if (Object.keys(extra).length) sections.push(...genericSections(extra).map(item => ({ ...item, key: `extra-${item.key}` })));
  return sections.filter(item => item.rows.length);
}

function riskSummarySections(key: string, value: Json): DisplaySection[] | undefined {
  if (!object(value)) return undefined;
  const definitions: Record<string, { title: string; order: string[]; arrays: string[] }> = {
    'build/sdk-extensions': { title: 'SDK 扩展版本摘要', order: ['available', 'status', 'enumerationMode', 'entryCount', 'returnedCount', 'entryLimit', 'entriesTruncated', 'extensionSetSha256', 'errorClass'], arrays: ['entries'] },
    'kernel/sysconf-summary': { title: '内存页与时钟节拍摘要', order: ['available', 'status', 'scope', 'queryCount', 'knownCount', 'unknownCount', 'pageSizeBytes', 'clockTicksPerSecond'], arrays: ['entries'] },
    'build/risk-properties-summary': { title: '风控原生属性摘要', order: ['observedCount', 'knownCount', 'properties'], arrays: ['observedProperties', 'missingProperties'] },
    'build/abi-summary': { title: 'ABI 与运行时架构摘要', order: ['available', 'supportedAbiCount', 'supported32BitAbiCount', 'supported64BitAbiCount', 'primaryAbi', 'armAbiCount', 'x86AbiCount', 'riscvAbiCount', 'otherAbiCount', 'runtime64Bit'], arrays: [] },
    'build/hardware-summary': { title: '硬件型号与 SoC 摘要', order: ['available', 'manufacturer', 'brand', 'model', 'device', 'product', 'hardware', 'board', 'fingerprintPresent', 'fingerprintLength', 'fingerprintSha256', 'socManufacturer', 'socModel'], arrays: [] },
    'build/os-summary': { title: 'Android 系统版本摘要', order: ['available', 'sdkInt', 'sdkIntFull', 'sdkIntFullMajor', 'sdkIntFullMinor', 'sdkIntFullStatus', 'sdkIntFullErrorClass', 'release', 'codename', 'incremental', 'securityPatch', 'baseOs', 'previewSdkInt', 'firstApiLevel', 'mediaPerformanceClass'], arrays: [] },
    'build/fingerprinted-partitions': { title: '系统分区构建指纹', order: ['available', 'status', 'scope', 'partitionCount', 'returnedCount', 'partitionLimit', 'partitionsTruncated', 'partitionSetSha256', 'errorClass'], arrays: ['partitions'] },
    'build/module-summary': { title: 'Mainline 系统模块摘要', order: ['available', 'status', 'count', 'hiddenCount', 'namedCount', 'packageNameCount', 'moduleNamesTruncated', 'packageNamesTruncated', 'moduleSetSha256'], arrays: ['moduleNames', 'packageNames'] },
    'network/risk-signals': { title: '活动链路摘要', order: ['present', 'capabilitiesAvailable', 'networkHandle', 'capabilityStates', 'vpn', 'validated', 'captivePortal', 'notVpn', 'notMetered', 'notRoaming', 'notRestricted', 'trusted', 'foreground', 'metered', 'interfaceName', 'httpProxy'], arrays: ['transports'] },
    'network/connectivity-summary': { title: '网络集合摘要', order: ['available', 'access', 'networkCount', 'activePresent', 'activeIndex', 'activeNetworkHandle', 'capabilitiesAvailableCount', 'linkPropertiesAvailableCount', 'validatedCount', 'vpnCount', 'notMeteredCount', 'transportCounts'], arrays: [] },
    'network/private-dns-summary': { title: 'Private DNS 环境摘要', order: ['available', 'status', 'activeNetworkPresent', 'linkPropertiesAvailable', 'privateDnsSupported', 'privateDnsActive', 'dnsServerCount', 'dnsIpv4Count', 'dnsIpv6Count', 'privateDnsServerNamePresent', 'privateDnsServerNameLength', 'privateDnsServerNameSha256'], arrays: [] },
    'network/proxy-summary': { title: '代理环境摘要', order: ['available', 'status', 'configured', 'hostPresent', 'hostLength', 'hostSha256', 'port', 'exclusionCount', 'pacFilePresent', 'pacScheme'], arrays: [] },
    'virtualization/risk-summary': { title: '虚拟化与提权线索摘要', order: ['pathChecks', 'evidenceOnly', 'testKeys', 'conclusion'], arrays: ['presentPaths', 'pathErrors', 'matchedBuildMarkers'] },
    'security/root-path-summary': { title: 'Root 相关路径检查摘要', order: ['available', 'status', 'pathChecks', 'pathLimit', 'presentCount', 'missingCount', 'deniedCount', 'unresolvedCount', 'errorCount', 'unknownCount', 'binaryExecutionAttempted', 'followParentSymlinks', 'evidenceOnly'], arrays: ['entries'] },
    'security/root-mount-summary': { title: 'Root 相关系统路径挂载标志', order: ['available', 'status', 'scope', 'pathChecks', 'pathLimit', 'knownCount', 'unknownCount', 'readOnlyCount', 'readWriteFlagCount', 'deniedCount', 'missingCount', 'unresolvedCount', 'errorCount', 'followSymlinks', 'writeProbeAttempted', 'evidenceOnly'], arrays: ['entries'] },
    'hardware/drm/widevine': { title: 'Widevine DRM 能力', order: ['supported', 'schemeUuid', 'vendor', 'version', 'description', 'algorithms', 'securityLevelProperty', 'maxSecurityLevel', 'maxSecurityLevelKind', 'deviceUniqueId'], arrays: [] },
    'hardware/features-summary': { title: '系统特性能力摘要', order: ['count', 'namedCount', 'versionedCount', 'maxVersion', 'featureNamesTruncated'], arrays: ['featureNames', 'glEsVersions'] },
     'hardware/graphics-summary': { title: '图形能力摘要', order: ['available', 'eglInitialized', 'eglError', 'eglVendor', 'eglVersion', 'glVendor', 'glRenderer', 'glVersion', 'eglExtensionsLength', 'eglExtensionCount', 'glExtensionsLength', 'glExtensionCount', 'vulkanLibraryLoaded', 'vulkanExtensionResult', 'vulkanExtensionCount', 'vulkanExtensionNamesTruncated'], arrays: ['vulkanExtensionNames'] },
    'hardware/codecs-summary': { title: '媒体编解码能力摘要', order: ['count', 'encoderCount', 'decoderCount', 'hardwareAcceleratedCount', 'softwareOnlyCount', 'vendorCount', 'codecNamesTruncated', 'mimeTypesTruncated'], arrays: ['codecNames', 'mimeTypes'] },
    'hardware/camera-summary': { title: '摄像头能力摘要', order: ['available', 'count', 'cameraIdsTruncated', 'flashAvailableCount', 'logicalMultiCameraCount', 'physicalCameraIdCount', 'cameraAccessErrorCount'], arrays: ['cameraIds'] },
    'hardware/audio-summary': { title: '音频能力摘要', order: ['available', 'count', 'sourceCount', 'sinkCount', 'productNamesTruncated', 'typeCounts'], arrays: ['productNames', 'sampleRates', 'encodings'] },
    'hardware/microphone-summary': { title: '麦克风阵列能力摘要', order: ['available', 'status', 'count', 'returnedCount', 'truncated', 'microphoneIdsTruncated', 'typeCounts', 'locationValues', 'directionalityValues', 'groupCount', 'addressPresentCount', 'descriptionPresentCount', 'channelMappingCount', 'frequencyResponsePointCount'], arrays: ['microphoneIds', 'locationValues', 'directionalityValues'] },
    'hardware/vibrator': { title: '振动器能力', order: ['available', 'count', 'hasVibrator', 'hasAmplitudeControl'], arrays: ['vibrators'] },
    'hardware/nfc': { title: 'NFC 能力', order: ['available', 'enabled', 'secureNfcSupported', 'secureNfcEnabled', 'antennaInfoAvailable', 'antennaCount', 'deviceWidth', 'deviceHeight', 'deviceFoldable'], arrays: [] },
     'hardware/usb-summary': { title: 'USB 能力摘要', order: ['available', 'deviceCount', 'deviceCountTruncated', 'interfaceCount', 'endpointCount', 'permissionQueryStatus', 'permissionGrantedCount', 'vendorIdCount', 'productIdCount', 'vendorIdsTruncated', 'productIdsTruncated', 'deviceClassCounts', 'interfaceClassCounts'], arrays: ['vendorIds', 'productIds'] },
    'battery/summary': { title: '电池环境摘要', order: ['available', 'level', 'scale', 'percentage', 'status', 'charging', 'plugged', 'pluggedAc', 'pluggedUsb', 'pluggedWireless', 'health', 'temperatureC', 'voltageMv', 'technology', 'present', 'capacityPercent', 'chargeCounterUah', 'currentNowUa', 'currentAverageUa', 'energyCounterNwh'], arrays: [] },
    'battery/cycle-count': { title: '电池循环次数', order: ['available', 'status', 'cycleCount'], arrays: [] },
    'battery/capacity-level': { title: '电池容量等级', order: ['available', 'status', 'capacityLevel', 'capacityLevelName'], arrays: [] },
    'sim/summary': { title: 'SIM 与电话环境摘要', order: ['available', 'phoneType', 'phoneCount', 'simState', 'simOperator', 'simOperatorName', 'simCountry', 'networkOperator', 'networkName', 'networkCountry', 'dataNetworkType', 'roaming', 'dataEnabled', 'subscriptionAccess', 'subscriptionCount', 'embeddedCount'], arrays: ['simStates', 'simSlotIndexes', 'carrierNames', 'mccs', 'mncs'] },
    'sim/euicc-summary': { title: 'eSIM 能力摘要', order: ['available', 'status', 'managerPresent', 'enabled', 'euiccInfoPresent', 'euiccOsVersion', 'availableMemoryBytes'], arrays: [] },
    'sim/radio-summary': { title: '电话射频能力摘要', order: ['available', 'status', 'activeModemCount', 'supportedModemCount', 'supportedRadioAccessFamily', 'dataCapable', 'voiceCapable', 'smsCapable', 'deviceVoiceCapable', 'deviceSmsCapable'], arrays: [] },
     'sim/service-summary': { title: '电话注册服务摘要', order: ['available', 'status', 'serviceState', 'roaming', 'searching', 'nonTerrestrial', 'manualSelection', 'operatorAlphaLongPresent', 'operatorAlphaShortPresent', 'operatorNumericPresent', 'channelNumber', 'duplexMode', 'registrationCount', 'registeredCount', 'roamingCount', 'searchingCount', 'emergencyServiceCount', 'nonTerrestrialCount'], arrays: ['cellBandwidths'] },
     'sim/signal-summary': { title: '电话信号摘要', order: ['available', 'status', 'signalLevel', 'timestampMillis', 'cellCount', 'validCellCount', 'dbmMin', 'dbmMax', 'asuMin', 'asuMax', 'typeCounts', 'levelCounts'], arrays: [] },
    'storage/summary': { title: '存储卷环境摘要', order: ['available', 'volumeCount', 'mountedCount', 'primaryCount', 'removableCount', 'emulatedCount', 'uuidPresentCount', 'stateCounts'], arrays: [] },
    'storage/capacity-summary': { title: '存储容量摘要', order: ['available', 'status', 'entryCount', 'errorCount'], arrays: ['entries'] },
    'network/bluetooth-summary': { title: '蓝牙能力摘要', order: ['available', 'access', 'adapterState', 'adapterNamePresent', 'adapterAddressPresent', 'bondedCount', 'bondedNameCount', 'bondedAddressCount', 'bondedTypeCounts', 'bondedClassCounts'], arrays: [] },
    'network/bluetooth-capabilities': { title: '蓝牙 LE 能力', order: ['available', 'access', 'adapterState', 'le2MPhySupported', 'leCodedPhySupported', 'leExtendedAdvertisingSupported', 'leMaximumAdvertisingDataLength'], arrays: [] },
    'network/wifi-summary': { title: 'Wi-Fi 能力摘要', order: ['available', 'wifiState', 'connectionAccess', 'scanAccess', 'connectionInfoAvailable', 'ssidPresent', 'bssidPresent', 'macAddressPresent', 'frequency', 'rssi', 'linkSpeed', 'cachedScanCount', 'ssidPresentCount', 'bssidPresentCount', 'band2_4GHzCount', 'band5GHzCount', 'band6GHzCount', 'frequenciesTruncated'], arrays: ['frequencies'] },
    'network/interface-summary': { title: '网络接口摘要', order: ['available', 'status', 'interfaceCount', 'upCount', 'downCount', 'loopbackCount', 'virtualCount', 'pointToPointCount', 'hardwareAddressPresentCount', 'addressCount', 'ipv4Count', 'ipv6Count', 'mtuMin', 'mtuMax', 'prefixCounts'], arrays: [] },
     'network/wifi-capabilities': { title: 'Wi-Fi 链路能力', order: ['available', 'access', 'wifiStandard', 'maxSupportedRxLinkSpeedMbps', 'maxSupportedTxLinkSpeedMbps', 'apMldMacPresent', 'apMloLinkId', 'affiliatedMloLinkCount'], arrays: [] },
    'packages/summary': { title: '应用清单摘要', order: ['returnedCount', 'applicationInfoCount', 'enabledCount', 'debuggableCount', 'systemAppCount', 'updatedSystemAppCount', 'nativeCodeCount', 'splitPackageCount', 'sharedUserIdCount', 'versionNameCount', 'requestedPermissionCount', 'permissionPackageCount', 'grantedRequestedPermissionCount', 'uniqueRequestedPermissionCount', 'requestedPermissionNamesTruncated', 'activityCount', 'receiverCount', 'serviceCount', 'providerCount', 'targetSdkCounts'], arrays: ['requestedPermissionNames'] },
    'input/summary': { title: '输入设备能力摘要', order: ['count', 'virtualCount', 'externalCount', 'microphoneCount', 'vibratorCount', 'motionRangeCount', 'accessErrorCount', 'keyboardTypeCounts', 'sourceCounts'], arrays: [] },
    'display/summary': { title: '显示能力摘要', order: ['count', 'validCount', 'secureCount', 'presentationCount', 'presentationCategoryCount', 'hdrCount'], arrays: ['widths', 'heights', 'densityDpi', 'refreshRates'] },
    'attestation/play-integrity-environment': { title: 'Play Integrity 本地环境', order: ['verdictAvailable', 'conclusion', 'playServices', 'playStore'], arrays: [] },
    'security/device-management': { title: '设备管理与用户环境', order: ['deviceOwnerForSelf', 'profileOwnerForSelf', 'managedProfile', 'organizationOwnedDeviceWithManagedProfile', 'organizationOwnedQueryStatus', 'organizationOwnedErrorClass', 'userUnlocked', 'storageEncryptionStatus', 'restrictions'], arrays: [] },
    'security/special-access': { title: '本 App 特殊访问状态', order: ['available', 'canDrawOverlays', 'canRequestPackageInstalls', 'canRequestPackageInstallsStatus', 'notificationPolicyAccess', 'notificationPolicyAccessStatus', 'allFilesAccess', 'allFilesAccessStatus', 'appOps'], arrays: [] },
    'security/lock-biometric': { title: '锁屏与生物识别环境', order: ['keyguardSecure', 'keyguardLocked', 'deviceSecure', 'deviceLocked', 'fingerprintFeature', 'fingerprintSensorStatus', 'fingerprintHardwareDetected', 'fingerprintEnrolled', 'fingerprintSensorErrorClass', 'faceFeature', 'irisFeature', 'biometricManagerAvailable', 'biometricStrongCode', 'biometricStrongStatus', 'biometricWeakCode', 'biometricWeakStatus', 'deviceCredentialCode', 'deviceCredentialStatus'], arrays: [] },
    'security/accessibility': { title: '启用的无障碍服务', order: ['available', 'count'], arrays: ['services'] },
    'security/summary': { title: '安全环境摘要', order: ['selfDebuggable', 'debuggerConnected', 'waitingForDebugger', 'deviceOwnerForSelf', 'profileOwnerForSelf', 'managedProfile', 'userUnlocked', 'specialAccessAvailable', 'appOpsCheckedCount', 'appOpsAllowedCount', 'canDrawOverlays', 'keyguardSecure', 'keyguardLocked', 'deviceSecure', 'deviceLocked', 'fingerprintFeature', 'faceFeature', 'irisFeature', 'accessibilityServiceCount', 'securityProviderCount'], arrays: [] },
    'security/process-identity-summary': { title: '当前进程身份与沙箱摘要', order: ['available', 'status', 'uid', 'isApplicationUid', 'isIsolatedUid', 'isSdkSandboxUid', 'isIsolatedProcess', 'isSdkSandboxProcess', 'packageName', 'processName'], arrays: [] },
    'security/self-runtime': { title: '应用运行时可分析配置', order: ['available', 'status', 'profileableByShell', 'profileable', 'virtualPreload', 'gwpAsanMode', 'memtagMode', 'nativeHeapZeroInitialized', 'requestRawExternalStorageAccess', 'attributionsUserVisible'], arrays: [] },
    'security/self-permissions': { title: '当前 App 运行时权限摘要', order: ['available', 'status', 'requestedCount', 'grantedCount', 'deniedCount', 'unknownCount', 'dangerousRequestedCount', 'dangerousGrantedCount', 'protectionInfoCount', 'returnedCount', 'truncated'], arrays: ['permissions'] },
    'security/self-components-summary': { title: '当前 App 组件攻击面摘要', order: ['available', 'status', 'activityCount', 'receiverCount', 'serviceCount', 'providerCount', 'componentCount', 'enabledCount', 'exportedCount', 'permissionFieldCount', 'directBootAwareCount', 'isolatedServiceCount', 'foregroundServiceTypeCount', 'providerReadPermissionCount', 'providerWritePermissionCount'], arrays: [] },
    'security/selinux-summary': { title: 'SELinux 环境摘要', order: ['available', 'status', 'enforceFilePresent', 'enforcing', 'enforceValue', 'contextPresent', 'contextLength', 'contextSha256'], arrays: [] },
    'security/self-integrity': { title: '当前 App 签名与安装环境', order: ['packageName', 'versionName', 'versionCode', 'installer', 'initiatingPackageName', 'originatingPackageName', 'updateOwnerPackageName', 'packageSource', 'packageSourceName', 'packageSourceStatus', 'enabled', 'applicationFlags', 'applicationFlagsSummary', 'debuggable', 'hasCode', 'instantApp', 'packageSuspended', 'packageSuspensionQueryStatus', 'packageStopped', 'packageStoppedQueryStatus', 'packageStoppedErrorClass', 'hasPastSigningCertificates'], arrays: ['signingCertificateSha256', 'signingCertificateHistorySha256'] },
    'security/providers-summary': { title: '安全提供者摘要', order: ['available', 'status', 'providerCount', 'serviceCount', 'providerNamesTruncated', 'serviceTypeCounts', 'serviceNamesTruncated'], arrays: ['providers', 'serviceNames'] },
    'security/trust-store-summary': { title: 'CA 信任库摘要', order: ['available', 'status', 'certificateCount', 'userCertificateCount', 'systemCertificateCount', 'otherCertificateCount', 'hasUserCertificates', 'hashedCertificateCount', 'uniqueSha256Count', 'certificateSetSha256', 'certificateReadErrorCount', 'hashLimit', 'hashTruncated'], arrays: [] },
    'environment/risk-settings': { title: '系统风控相关设置', order: ['adbEnabled', 'developmentSettingsEnabled', 'airplaneModeOn', 'autoTime', 'autoTimeZone', 'privateDnsMode', 'privateDnsSpecifier', 'wifiOn', 'bluetoothOn', 'accessibilityEnabled', 'mockLocation', 'installNonMarketApps'], arrays: [] },
    'environment/system-settings': { title: '显示与动画设置摘要', order: ['available', 'status', 'settings'], arrays: [] },
     'environment/locale-summary': { title: '语言与时区摘要', order: ['available', 'localeTags', 'localeCount', 'defaultLocale', 'timezoneId', 'rawOffsetMillis', 'usesDaylightTime', 'inDaylightTime', 'is24HourFormat', 'layoutDirection', 'fontScale', 'densityDpi', 'uiMode', 'applicationLocaleAccess', 'applicationLocaleTags'], arrays: [] },
    'environment/clock-summary': { title: '系统时钟摘要', order: ['elapsedRealtimeMillis', 'uptimeMillis', 'wallClockMillis', 'bootTimeMillis', 'sleepMillis'], arrays: [] },
    'environment/resource-locales': { title: '资源语言集合摘要', order: ['available', 'status', 'scope', 'entryLimit', 'appAssetLocaleCount', 'appAssetReturnedCount', 'appAssetNullCount', 'appAssetLocalesTruncated', 'appAssetLocalesSha256', 'systemAssetLocaleCount', 'systemAssetReturnedCount', 'systemAssetNullCount', 'systemAssetLocalesTruncated', 'systemAssetLocalesSha256'], arrays: ['appAssetLocaleTags', 'systemAssetLocaleTags'] },
    'environment/system-fonts': { title: '系统字体集合摘要', order: ['available', 'status', 'scope', 'fontCount', 'fileBackedCount', 'noFileCount', 'italicCount', 'variationConfiguredCount', 'returnedCount', 'entryLimit', 'entriesTruncated', 'metadataSetSha256'], arrays: ['entries'] },
    'environment/android-id-summary': { title: 'Android ID 摘要', order: ['available', 'status', 'scope', 'androidIdPresent', 'androidIdLength', 'androidIdSha256'], arrays: [] },
    'environment/device-state': { title: '设备初始化与运行状态', order: ['deviceProvisioned', 'userSetupComplete', 'bootCount', 'lowRamDevice', 'demoUser', 'systemUser', 'headlessSystemUserMode', 'userUnlocked', 'userRunning', 'userMonkey', 'testHarness', 'backgroundRestricted'], arrays: [] },
    'environment/memory-summary': { title: '内存环境摘要', order: ['available', 'status', 'activityManagerAvailable', 'totalMemBytes', 'availMemBytes', 'thresholdBytes', 'lowMemory', 'lowRamDevice', 'memoryClassMb', 'largeMemoryClassMb', 'runtimeMaxMemoryBytes', 'runtimeTotalMemoryBytes', 'runtimeFreeMemoryBytes', 'heapUsedBytes', 'runtimeProcessorCount'], arrays: [] },
    'environment/user-profiles': { title: '用户与工作资料摘要', order: ['available', 'status', 'profileCount', 'managedProfile'], arrays: [] },
    'environment/app-standby': { title: '当前 App 待机分桶', order: ['available', 'status', 'bucket', 'bucketName'], arrays: [] },
    'environment/process-exits': { title: '当前 App 进程退出历史', order: ['available', 'status', 'count', 'limit', 'reasons', 'statuses'], arrays: ['entries'] },
    'environment/low-power-standby': { title: '低功耗待机与重启能力', order: ['available', 'status', 'lowPowerStandbyEnabled', 'lowPowerStandbyExempt', 'rebootingUserspaceSupported'], arrays: [] },
    'environment/battery-discharge-prediction': { title: '电池预计剩余时间', order: ['available', 'status', 'predictionPresent', 'predictionSeconds', 'predictionNanos', 'predictionIso', 'predictionStatus', 'personalized', 'personalizedStatus'], arrays: [] },
    'kernel/process-status-summary': { title: '当前进程状态摘要', order: ['available', 'status', 'traced', 'tracerPid', 'state', 'threadCount', 'noNewPrivs', 'seccompMode', 'capEffPresent', 'capBndPresent', 'fieldsObserved', 'truncated'], arrays: [] },
    'kernel/maps-summary': { title: '当前进程内存映射摘要', order: ['available', 'status', 'mapCount', 'invalidLineCount', 'executableMappingCount', 'writableMappingCount', 'privateMappingCount', 'sharedMappingCount', 'anonymousMappingCount', 'fileBackedMappingCount', 'devMappingCount', 'memfdMappingCount', 'ashmemMappingCount', 'apkMappingCount', 'apexMappingCount', 'deletedMappingCount', 'markerMatchCount', 'markerPresence', 'permissionCounts', 'truncated', 'errno'], arrays: [] },
    'kernel/mount-summary': { title: '挂载拓扑摘要', order: ['available', 'status', 'entryCount', 'readOnlyCount', 'sharedMountCount', 'masterMountCount', 'optionalFieldCount', 'overlayCount', 'tmpfsCount', 'procCount', 'sysfsCount', 'ext4Count', 'rootFsType', 'fsTypeCount', 'fsTypesTruncated', 'truncated', 'fsTypeCounts'], arrays: [] },
    'kernel/cgroup-summary': { title: 'Cgroup 环境摘要', order: ['available', 'status', 'cgroupVersion', 'entryCount', 'v1EntryCount', 'v2EntryCount', 'controllerCount', 'controllerNamesTruncated', 'maxPathDepth', 'dockerMarker', 'kubernetesMarker', 'lxcMarker', 'sandboxMarker', 'truncated'], arrays: ['controllerNames'] },
    'kernel/cpu-summary': { title: 'CPU 拓扑与频率摘要', order: ['available', 'status', 'logicalProcessorCount', 'runtimeProcessorCount', 'presentCount', 'onlineCount', 'offlineCount', 'frequencyValueCount', 'minFrequencyKHz', 'maxFrequencyKHz', 'governorCounts', 'coreIdCount', 'physicalPackageIdCount', 'topologyValuesTruncated', 'cpuDirectoriesTruncated'], arrays: ['coreIds', 'physicalPackageIds'] },
    'environment/location': { title: '定位与 GNSS 能力', order: ['available', 'locationEnabled', 'providerCount', 'hasGpsProvider', 'hasNetworkProvider', 'hasFusedProvider', 'gnssHardwareModelName', 'gnssYearOfHardware', 'gnssCapabilities', 'gnssAntennaCount', 'gnssAntennaInfoTruncated'], arrays: ['providers', 'gnssAntennaCarrierFrequenciesMHz'] },
    'sensors/summary': { title: '传感器能力摘要', order: ['count', 'wakeUpCount', 'dynamicCount', 'directChannelCount', 'vendorsTruncated', 'typeCounts'], arrays: ['vendors'] },
    'environment/webview-user-agent': { title: 'WebView 默认 User-Agent', order: ['userAgent', 'length'], arrays: [] },
    'environment/webview-provider': { title: 'WebView 提供方环境', order: ['packageName', 'versionName', 'versionCode'], arrays: ['signatures'] },
    'environment/webview-package-summary': { title: 'WebView 提供方安装环境摘要', order: ['available', 'status', 'packageName', 'selectedVersionName', 'selectedVersionCode', 'packageQueryStatus', 'selectionVersionMatches', 'versionName', 'versionCode', 'applicationInfoAvailable', 'enabled', 'systemApp', 'updatedSystemApp', 'targetSdkVersion', 'minSdkVersion', 'firstInstallTime', 'lastUpdateTime', 'signatureCount', 'signatureLimit', 'signaturesTruncated', 'hasMultipleSigners', 'hasPastSigningCertificates', 'signingHistoryStatus', 'signingHistoryCount', 'signingHistoryLimit', 'signingHistoryTruncated', 'signingHistorySha256', 'installer', 'installerQueryStatus'], arrays: ['signingCertificateSha256', 'signingCertificateHistorySha256'] },
    'environment/power-thermal': { title: '电源与热状态', order: ['powerSaveMode', 'interactive', 'ignoresBatteryOptimizations', 'batteryOptimizationQueryStatus', 'deviceIdleMode', 'lightDeviceIdleMode', 'locationPowerSaveMode', 'thermalStatus', 'thermalHeadroom'], arrays: [] },
    'hardware/drm-schemes': { title: 'DRM 支持方案摘要', order: ['available', 'status', 'scope', 'schemeCount', 'returnedCount', 'schemeLimit', 'truncated', 'schemeListSha256'], arrays: ['schemeUuids'] },
    'hardware/camera-concurrency': { title: '摄像头并发组合摘要', order: ['available', 'status', 'scope', 'combinationCount', 'returnedCount', 'combinationLimit', 'cameraIdsPerCombinationLimit', 'truncated', 'combinationSetSha256'], arrays: ['combinations'] },
    'hardware/usb': { title: 'USB 设备公开清单', order: ['count'], arrays: ['devices'] },
  };
  const definition = definitions[key];
  if (!definition) return undefined;
  const summaryOrder = key === 'environment/resource-locales' || key === 'hardware/drm-schemes' || key === 'hardware/camera-concurrency' || key === 'build/fingerprinted-partitions' || key === 'build/os-summary' ? [...definition.order, ...Object.keys(value)] : definition.order;
  const sections: DisplaySection[] = [section('summary', definition.title, objectRows(value, summaryOrder))];
  // 业务块：修正历史字段的展示语义，保留其key和原值，不把请求常量标成设备实测级别。
  if (key === 'hardware/drm/widevine') for (const field of sections[0].rows) {
    if (field.key === 'maxSecurityLevel') field.label = 'maxSecurityLevel（最大级别请求常量）';
  }
  for (const arrayKey of definition.arrays) {
    if (Array.isArray(value[arrayKey])) {
      const title = key === 'build/risk-properties-summary'
        ? arrayKey === 'observedProperties' ? '已观察属性' : '当前缺失属性'
        : key === 'build/module-summary'
          ? arrayKey === 'moduleNames' ? '模块名称' : '模块包名'
        : key === 'environment/location'
          ? arrayKey === 'providers' ? '定位 provider' : 'GNSS 天线载波频率（MHz）'
          : key === 'environment/resource-locales' ? arrayKey === 'appAssetLocaleTags' ? 'App 已加载资源语言（含系统资源，最多 256 项）' : '系统资源语言（最多 256 项）'
          : key === 'sensors/summary' && arrayKey === 'vendors' ? '传感器厂商'
           : key === 'hardware/graphics-summary' && arrayKey === 'vulkanExtensionNames' ? 'Vulkan 扩展名'
           : key === 'hardware/features-summary'
            ? arrayKey === 'featureNames' ? '系统特性名' : 'OpenGL ES 要求版本'
          : key === 'hardware/codecs-summary'
            ? arrayKey === 'codecNames' ? '编解码器名' : '媒体类型'
          : key === 'hardware/camera-summary' && arrayKey === 'cameraIds' ? '摄像头 ID'
          : key === 'security/self-integrity'
            ? arrayKey === 'signingCertificateSha256' ? '当前签名 SHA-256' : '签名历史 SHA-256'
          : key === 'environment/process-exits' && arrayKey === 'entries' ? '最近退出条目'
          : key === 'environment/webview-provider' && arrayKey === 'signatures' ? '提供方签名 SHA-256'
          : key === 'environment/webview-package-summary' ? arrayKey === 'signingCertificateSha256' ? '提供方当前签名 SHA-256（最多 16 项）' : '提供方签名轮换历史 SHA-256（平台顺序，最多 16 项）'
          : key === 'security/providers-summary' && arrayKey === 'providers' ? '安全提供者'
          : key === 'security/providers-summary' && arrayKey === 'serviceNames' ? '服务名（最多 512 项）'
          : key === 'security/self-permissions' && arrayKey === 'permissions' ? '声明权限（最多 256 项）'
          : key === 'security/root-path-summary' && arrayKey === 'entries' ? '路径观察结果（固定 32 项，不执行）'
          : key === 'security/root-mount-summary' && arrayKey === 'entries' ? '系统路径挂载标志（固定 7 项，不写入）'
          : key === 'kernel/sysconf-summary' && arrayKey === 'entries' ? '系统调用原始结果（固定 2 项）'
          : key === 'build/sdk-extensions' && arrayKey === 'entries' ? '扩展标识与版本（最多 32 项）'
          : key === 'build/fingerprinted-partitions' && arrayKey === 'partitions' ? '系统分区名称、指纹与构建时间（最多 128 项）'
          : key === 'environment/system-fonts' && arrayKey === 'entries' ? '系统字体元数据（最多 128 项）'
          : key === 'hardware/drm-schemes' && arrayKey === 'schemeUuids' ? '平台支持的 DRM 方案 UUID（最多 32 项）'
          : key === 'hardware/camera-concurrency' && arrayKey === 'combinations' ? '当前连接摄像头的并发组合（最多 32 组，每组 16 个 ID）'
          : key === 'storage/capacity-summary' && arrayKey === 'entries' ? '固定路径容量'
          : key === 'hardware/audio-summary'
            ? arrayKey === 'productNames' ? '音频设备名' : arrayKey === 'sampleRates' ? '采样率' : '编码格式'
          : key === 'hardware/microphone-summary'
            ? arrayKey === 'microphoneIds' ? '麦克风 ID' : arrayKey === 'locationValues' ? '麦克风位置枚举' : '麦克风指向性枚举'
          : key === 'hardware/usb-summary' ? arrayKey === 'vendorIds' ? 'USB 厂商 ID' : 'USB 产品 ID'
          : key === 'kernel/cgroup-summary' && arrayKey === 'controllerNames' ? 'Cgroup 控制器'
          : key === 'kernel/cpu-summary' ? arrayKey === 'coreIds' ? 'CPU 核心 ID' : '物理封装 ID'
          : key === 'network/wifi-summary' && arrayKey === 'frequencies' ? '缓存扫描频率（MHz）'
          : key === 'display/summary'
            ? arrayKey === 'widths' ? '显示宽度（px）' : arrayKey === 'heights' ? '显示高度（px）' : arrayKey === 'densityDpi' ? '密度（dpi）' : '刷新率（Hz）'
          : key === 'packages/summary' && arrayKey === 'requestedPermissionNames' ? '请求的权限名（最多 256 项）'
          : key === 'sim/summary'
            ? arrayKey === 'simStates' ? 'SIM 槽位状态' : arrayKey === 'simSlotIndexes' ? '订阅槽位' : arrayKey === 'carrierNames' ? '运营商名称' : arrayKey === 'mccs' ? 'MCC' : 'MNC'
          : arrayKey;
      sections.push(section(arrayKey, title, arrayRows(value[arrayKey] as Json[], arrayKey), true));
    }
  }
  if (key === 'network/risk-signals' && typeof value.capabilitiesRaw === 'string') sections.push(section('capabilitiesRaw', '系统原始能力字符串', [row('value', value.capabilitiesRaw)], false));
  if (key === 'security/root-path-summary' || key === 'security/root-mount-summary' || key === 'kernel/sysconf-summary' || key === 'build/sdk-extensions' || key === 'build/fingerprinted-partitions' || key === 'environment/system-fonts' || key === 'environment/resource-locales') for (const group of sections) for (const entry of group.rows) {
    if ((entry.key === 'status' || entry.key.endsWith('.status')) && Object.hasOwn(STATUS_LABELS, entry.value)) entry.value = STATUS_LABELS[entry.value];
  }
  if (key === 'build/sdk-extensions') for (const entry of sections[0].rows) if (entry.key === 'enumerationMode') {
    if (entry.value === 'all-reported-extensions') entry.value = '系统公开的全部扩展';
    else if (entry.value === 'android-r-only') entry.value = '仅 Android R 扩展（API 30）';
  }
  return sections.filter(item => item.rows.length);
}

export function buildSections(item?: Item): DisplaySection[] {
  if (!item) return [];
  const value = commandOutput(item.value);
  // 业务块：芯片标准支持保留JSON类型，不能把成功false、字符串false和查询失败null合并。
  if (item.key === 'network/wifi-standard-support') return object(value) && Object.keys(value).length ? [section('fields', 'Wi-Fi 芯片标准支持', Object.entries(value).flatMap(([key, child]) => flattenRows(child, key, true)), true)] : genericSections(value);
  // 业务块：容器/级别查询逐项保留原始JSON类型，成功false与失败null及未知字段均可区分。
  if (item.key === 'hardware/drm/widevine-capabilities' && object(value) && Object.keys(value).length) return [section('fields', 'Widevine 容器与安全级别支持', Object.entries(value).flatMap(([key, child]) => flattenRows(child, key, true)), true)];
  // 业务块：热阈值的原始状态编号与归一化值保留JSON类型，不推导摄氏温度或当前热状态。
  if (item.key === 'environment/thermal-headroom-thresholds' && object(value) && Object.keys(value).length) return [section('fields', '热余量阈值快照', Object.entries(value).flatMap(([key, child]) => flattenRows(child, key, true)), true)];
  // 业务块：隐私开关完整字段保留JSON类型，避免布尔false与字符串false在差异筛选中混淆。
  if (item.key === 'security/sensor-privacy-capabilities' && object(value) && Object.keys(value).length) return [section('fields', '隐私开关支持能力', Object.entries(value).flatMap(([key, child]) => flattenRows(child, key, true)), true)];
  // visibility 是采集器写入的固定范围说明，不是从设备读取的返回值。
  if (item.key === 'packages/enumeration' && object(value)) return [section('enumeration', '应用枚举结果', objectRows(value, ['returnedCount']))];
  if (item.key === 'security/trust-store') return trustStoreSections(value);
  if (item.key === 'attestation/tee' || item.key === 'attestation/plain' || item.key === 'attestation/strongbox') return attestationSections(item.key, value);
  if (item.key === 'hardware/usb') return usbSections(value);
  // 业务块：混音与布局对象保留JSON类型，旧音频字段继续沿用原格式与顺序。
  if (item.key === 'hardware/audio' && Array.isArray(value) && value.length) return [section('array', '采集数组', value.flatMap((device, index) =>
    object(device) && Object.keys(device).length ? Object.entries(device).flatMap(([key, child]) => flattenRows(child, `[${index}].${key}`, key === 'supportedMixerAttributes' || key === 'speakerLayout')) : flattenRows(device, `[${index}]`)), true)];
  if (item.key === 'security/tls-parameters') {
    const titles: Record<string, string> = {
      fields: '默认 TLS 上下文摘要', supportedProtocols: '支持的 TLS 协议', defaultProtocols: '默认启用的 TLS 协议',
      supportedCipherSuites: '支持的密码套件', defaultCipherSuites: '默认启用的密码套件',
    };
    return genericSections(value).map(group => ({ ...group, title: Object.hasOwn(titles, group.key) ? titles[group.key] : group.title }));
  }
  const riskSummary = riskSummarySections(item.key, value);
  if (riskSummary) return riskSummary;
  if (item.key === 'build/getprop' || item.source.api === '/system/bin/getprop' || item.category === 'build' && item.key.includes('property')) return buildProperties(value);
  if (item.category === 'display' || item.key.startsWith('display/')) return displaySections(value);
  if (item.category === 'input' || item.key.startsWith('input/')) return inputSections(value);
  if (item.key === 'binder/service-list') return typeof value === 'string' ? [section('stdout', '服务清单 stdout', value.split('\n').filter(Boolean).map((line, index) => row(String(index), line, `第 ${index + 1} 行`)), true)] : genericSections(value);
  if (item.key === 'network/wifi-cached-scan') return wifiCachedScanSections(value);
  if (item.category === 'network' || item.key.startsWith('network/')) return networkSections(value);
  if (item.category === 'packages' || item.key.startsWith('packages/')) return [...packageSections(value), ...(item.installer ? genericSections(item.installer.value).map(child => ({ ...child, key: `installer-${child.key}`, title: `安装来源 · ${child.title}` })) : [])];
  return genericSections(value);
}

export function displayValue(item?: Item): string {
  const sections = buildSections(item);
  return sections.flatMap(sectionItem => sectionItem.rows.map(valueRow => `${sectionItem.key}/${valueRow.key}:${valueRow.value}`)).join('\n');
}
