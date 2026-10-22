import type { Item, Json } from './types';

export type ModuleKind = 'getprop' | 'display' | 'input' | 'binder' | 'storage' | 'network' | 'directories' | 'packages' | 'security' | 'attestation' | 'generic';

export interface ModuleDisplay {
  kind: ModuleKind;
  title: string;
  description: string;
  text: string;
}

type JsonObject = { [key: string]: Json };

function isObject(value: Json): value is JsonObject {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function compact(value: Json): string {
  if (value === null) return 'null';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}

function pretty(value: Json): string {
  return JSON.stringify(value, null, 2);
}

function linesForObject(value: JsonObject, order: string[] = Object.keys(value)): string[] {
  const used = new Set<string>();
  const lines: string[] = [];
  for (const key of [...order, ...Object.keys(value)]) {
    if (used.has(key) || !(key in value)) continue;
    used.add(key);
    const child = value[key];
    if (Array.isArray(child)) {
      if (!child.length) lines.push(`${key}: []`);
      else child.forEach((entry, index) => lines.push(`${key}[${index}]: ${compact(entry)}`));
    } else if (isObject(child)) {
      lines.push(`${key}: ${compact(child)}`);
    } else {
      lines.push(`${key}: ${compact(child)}`);
    }
  }
  return lines;
}

function formatGetprop(value: Json): string {
  // getprop 的命令元数据对比价值较低；显示层只呈现 stdout，原始对象仍完整保留。
  return isObject(value) && typeof value.stdout === 'string' ? value.stdout : typeof value === 'string' ? value : '';
}

function formatDisplay(value: Json): string {
  if (!isObject(value)) return pretty(value);
  const lines: string[] = [];
  const identity = ['displayId', 'name', 'state', 'flags', 'rotation'];
  const geometry = ['widthPixels', 'heightPixels', 'density', 'densityDpi', 'scaledDensity', 'xdpi', 'ydpi', 'refreshRate'];
  lines.push('基础显示');
  lines.push(...linesForObject(value, identity));
  lines.push('', '分辨率与刷新');
  lines.push(...linesForObject(value, geometry));
  if (Array.isArray(value.supportedModes)) {
    lines.push('', `支持模式（${value.supportedModes.length}）`);
    value.supportedModes.forEach((mode, index) => lines.push(`mode[${index}]: ${compact(mode)}`));
  }
  for (const key of ['presentationDeadlineNanos', 'appVsyncOffsetNanos', 'hdr']) {
    if (key in value) lines.push('', key, compact(value[key]));
  }
  return lines.join('\n');
}

function formatInput(value: Json): string {
  if (!isObject(value)) return pretty(value);
  const lines = linesForObject(value, ['id', 'name', 'descriptor', 'vendorId', 'productId', 'sources', 'keyboardType', 'controllerNumber', 'virtual', 'external', 'hasMicrophone', 'hasVibrator']);
  if (Array.isArray(value.motionRanges)) {
    lines.push('', `运动范围（${value.motionRanges.length}）`);
    value.motionRanges.forEach((range, index) => lines.push(`range[${index}]: ${compact(range)}`));
  }
  return lines.join('\n');
}

function formatBinder(value: Json): string {
  if (isObject(value) && typeof value.stdout === 'string') return value.stdout;
  return isObject(value) ? linesForObject(value).join('\n') : pretty(value);
}

function formatStorage(value: Json): string {
  if (typeof value === 'string') return value.replaceAll('\t', '    ');
  if (!isObject(value)) return pretty(value);
  const order = ['path', 'fileSystemType', 'blockSize', 'blocks', 'availableBlocks', 'freeBlocks', 'totalBytes', 'availableBytes'];
  return linesForObject(value, order).join('\n');
}

function formatNetwork(value: Json): string {
  if (!Array.isArray(value)) return isObject(value) ? linesForObject(value).join('\n') : pretty(value);
  const lines: string[] = [];
  value.forEach((entry, index) => {
    if (!isObject(entry)) { lines.push(`[${index}] ${compact(entry)}`); return; }
    const name = typeof entry.name === 'string' ? entry.name : `接口 ${index}`;
    lines.push(`${name} · ${entry.up === true ? 'up' : entry.up === false ? 'down' : 'unknown'} · MTU ${compact(entry.mtu ?? null)}`);
    for (const key of ['displayName', 'index', 'loopback', 'virtual', 'pointToPoint', 'hardwareAddressBase64']) if (key in entry) lines.push(`  ${key}: ${compact(entry[key])}`);
    if (Array.isArray(entry.addresses)) entry.addresses.forEach((address, addressIndex) => lines.push(`  address[${addressIndex}]: ${compact(address)}`));
    lines.push('');
  });
  return lines.join('\n').trimEnd();
}

function formatDirectories(value: Json): string {
  if (!Array.isArray(value)) return isObject(value) ? linesForObject(value).join('\n') : pretty(value);
  return value.map((entry, index) => {
    if (!isObject(entry)) return `[${index}] ${compact(entry)}`;
    const fields = ['path', 'type', 'mode', 'uid', 'gid', 'size', 'mtimeSeconds', 'depth', 'linkTarget', 'listingStatus'];
    return fields.filter(key => key in entry).map(key => `${key}=${compact(entry[key])}`).join(' · ');
  }).join('\n');
}

function formatPackages(value: Json): string {
  if (Array.isArray(value)) return value.map((entry, index) => `package[${index}] ${compact(entry)}`).join('\n');
  if (!isObject(value)) return pretty(value);
  const lines = linesForObject(value, ['packageName', 'versionName', 'versionCode', 'firstInstallTime', 'lastUpdateTime', 'sharedUserId', 'installLocation']);
  for (const key of ['requestedPermissions', 'activities', 'receivers', 'services', 'providers', 'signatures']) {
    const child = value[key];
    if (Array.isArray(child)) {
      lines.push('', `${key}（${child.length}）`);
      child.forEach((entry, index) => lines.push(`${key}[${index}]: ${compact(entry)}`));
    }
  }
  if (isObject(value.application)) lines.push('', 'application', ...linesForObject(value.application, ['uid', 'flags', 'enabled', 'targetSdkVersion', 'minSdkVersion', 'sourceDir', 'dataDir', 'processName']));
  return lines.join('\n');
}

function formatCertificate(value: Json): string {
  if (Array.isArray(value)) return value.map((entry, index) => `certificate[${index}]: ${compact(entry)}`).join('\n');
  if (!isObject(value)) return pretty(value);
  const lines: string[] = [];
  for (const key of ['requestedAttestation', 'requestedStrongBox', 'algorithm', 'insideSecureHardware', 'challengeBase64', 'signatureBase64', 'publicKeyBase64']) if (key in value) lines.push(`${key}: ${compact(value[key])}`);
  if (Array.isArray(value.certificateChainBase64)) {
    lines.push('', `certificateChain（${value.certificateChainBase64.length}）`);
    value.certificateChainBase64.forEach((entry, index) => lines.push(`certificate[${index}]: ${compact(entry)}`));
  }
  return lines.join('\n');
}

function formatSecurity(value: Json): string {
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) return formatCertificate(value);
  if (!isObject(value)) return pretty(value);
  if ('certificateChainBase64' in value || 'derBase64' in value || 'challengeBase64' in value) return formatCertificate(value);
  return linesForObject(value, ['uid', 'pid', 'debuggable', 'debuggerConnected', 'waitingForDebugger', 'enforce', 'policyvers']).join('\n');
}

function formatGeneric(value: Json): string {
  if (typeof value === 'string' && /^\s*[\[{]/.test(value)) {
    try { return pretty(JSON.parse(value) as Json); } catch { /* 保留非 JSON 原文。 */ }
  }
  return typeof value === 'string' ? value : pretty(value);
}

function kindFor(item: Item): ModuleKind {
  if (item.key === 'build/getprop' || item.source.api === '/system/bin/getprop') return 'getprop';
  if (item.category === 'display' || item.key.startsWith('display/')) return 'display';
  if (item.category === 'input' || item.key.startsWith('input/')) return 'input';
  if (item.key === 'binder/service-list' || item.source.api.includes('service list')) return 'binder';
  if (item.category === 'storage' || item.key.startsWith('storage/')) return 'storage';
  if (item.category === 'network' || item.key.startsWith('network/')) return 'network';
  if (item.category === 'directories' || item.key.startsWith('directories/')) return 'directories';
  if (item.category === 'packages' || item.key.startsWith('packages/')) return 'packages';
  if (item.category === 'attestation' || item.category === 'security' || item.key.startsWith('security/')) return item.category === 'attestation' ? 'attestation' : 'security';
  return 'generic';
}

export function formatModule(item: Item): ModuleDisplay {
  const kind = kindFor(item);
  const config: Record<ModuleKind, { title: string; description: string; format: (value: Json) => string }> = {
    getprop: { title: '系统属性 stdout', description: '仅显示 /system/bin/getprop 的 stdout；完整命令元数据仍保留在原始值中。', format: formatGetprop },
    display: { title: '显示器参数', description: '按显示身份、分辨率密度、刷新模式和 HDR 分组。', format: formatDisplay },
    input: { title: '输入设备', description: '按设备身份、能力和运动范围分组，保留运动范围顺序。', format: formatInput },
    binder: { title: 'Binder 服务清单', description: '按 service list 原始输出逐行对照，避免把服务名误当成普通 JSON 字段。', format: formatBinder },
    storage: { title: '分区与容量', description: '按路径、块统计和可用容量展示；文本表格只调整制表符间距。', format: formatStorage },
    network: { title: '网络接口', description: '每个接口独立成组，地址列表保持采集顺序。', format: formatNetwork },
    directories: { title: '目录清单', description: '每条目录记录按路径、类型、权限、所有者和时间排列。', format: formatDirectories },
    packages: { title: '应用清单', description: '先展示包身份，再展开权限、组件和签名摘要。', format: formatPackages },
    security: { title: '安全与完整性', description: '保留安全状态字段与原始证书列表，便于定位单项变化。', format: formatSecurity },
    attestation: { title: '密钥认证', description: '按挑战、签名、公钥、认证链和硬件状态分段。', format: formatCertificate },
    generic: { title: '结构化值', description: '当前模块没有专属布局，使用缩进 JSON 便于阅读。', format: formatGeneric },
  };
  const selected = config[kind];
  return { kind, title: selected.title, description: selected.description, text: selected.format(item.value) };
}
