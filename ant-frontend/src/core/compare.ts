import type { Fingerprint, Item, Json, Row, Rules, DiffState } from './types';
import { mergePackageInstallers } from './package-items';

function object(v: Json | undefined): v is Record<string, Json> { return v !== null && typeof v === 'object' && !Array.isArray(v); }
function canonical(v: Json): string { if (Array.isArray(v)) return `[${v.map(canonical).join(',')}]`; if (object(v)) return `{${Object.keys(v).sort().map(k => `${JSON.stringify(k)}:${canonical(v[k])}`).join(',')}}`; return JSON.stringify(v); }
// 智能过滤只忽略采集进程、运行时和启动时间会自然变化的字段，稳定设备字段仍参与比较。
const transient = new Set(['pid', 'uid', 'startedAt', 'finishedAt', 'startTime', 'startTimeMillis', 'processStartTime', 'processStartTimeMillis', 'runTime', 'runtime', 'runningTime', 'elapsedRealtime', 'uptimeMillis', 'wallClockMillis', 'elapsedRealtimeMillis', 'bootTimeMillis', 'sleepMillis', 'tracerPid', 'state', 'threadCount', 'firstInstallTime', 'lastUpdateTime', 'challengeBase64', 'signatureBase64', 'publicKeyBase64', 'certificateChainBase64', 'timestamp', 'powerSaveMode', 'interactive', 'deviceIdleMode', 'lightDeviceIdleMode', 'locationPowerSaveMode', 'thermalStatus', 'thermalHeadroom', 'availMemBytes', 'runtimeTotalMemoryBytes', 'runtimeFreeMemoryBytes', 'heapUsedBytes']);
const transientByItem: Record<string, ReadonlySet<string>> = {
  'sim/service-summary': new Set(['serviceState', 'roaming', 'searching', 'nonTerrestrial', 'channelNumber', 'duplexMode', 'cellBandwidths', 'registrationCount', 'registeredCount', 'roamingCount', 'searchingCount', 'emergencyServiceCount', 'nonTerrestrialCount', 'domainCounts', 'transportCounts', 'accessNetworkTechnologyCounts']),
  'sim/signal-summary': new Set(['timestampMillis', 'signalLevel', 'cellCount', 'validCellCount', 'typeCounts', 'levelCounts', 'dbmMin', 'dbmMax', 'asuMin', 'asuMax']),
};
function isDynamicField(itemKey: string, field: string, path: string): boolean {
  if (transient.has(field)) return true;
  if (transientByItem[itemKey]?.has(field)) return true;
  // 应用清单的旧/新对象都可能把 UID 放在 application.uid；按路径兼容两种表示。
  return itemKey.startsWith('packages/') && (field === 'application.uid' || path.endsWith('.application.uid'));
}
function project(v: Json, rules: Rules, counter: { n: number }, key: string, path = ''): Json {
  if (Array.isArray(v)) {
    const result = v.map(x => project(x, rules, counter, key, path + '[]'));
    // 只对已知集合应用排序；重复项保留，不按业务键覆盖。
    const isSet = key === 'sensors/list' || key === 'hardware/usb-summary' && /(?:vendorIds|productIds)$/.test(path) || /^(input\/|display\/|packages\/|network\/)/.test(key) && /(?:motionRanges|supportedModes|signatures|requestedPermissions|activities|receivers|services|providers|interfaces|addresses|dns|routes|pairedDevices|capabilities)$/.test(path);
    return rules.semantic && isSet ? result.sort((a, b) => canonical(a).localeCompare(canonical(b))) : result;
  }
  if (object(v)) {
    const result: Record<string, Json> = Object.create(null);
    for (const k of Object.keys(v)) {
      if (rules.ignoreDynamic && (isDynamicField(key, k, path ? `${path}.${k}` : k) || key.startsWith('directories/path/') && ['mtimeSeconds', 'size'].includes(k))) { counter.n++; continue; }
      result[k] = project(v[k], rules, counter, key, path ? `${path}.${k}` : k);
    }
    return result;
  }
  return v;
}
export function differences(a: Json, b: Json, prefix = '', result: string[] = []): string[] {
  if (result.length >= 64 || Object.is(a, b)) return result;
  if (Array.isArray(a) && Array.isArray(b)) {
    if (a.length !== b.length) result.push(`${prefix}/length`);
    for (let i = 0; i < Math.max(a.length, b.length) && result.length < 64; i++) {
      if (i >= a.length || i >= b.length) result.push(`${prefix}/${i}`); else differences(a[i], b[i], `${prefix}/${i}`, result);
    } return result;
  }
  if (object(a) && object(b)) {
    for (const key of new Set([...Object.keys(a), ...Object.keys(b)])) {
      if (result.length >= 64) break;
      const path = `${prefix}/${key.replaceAll('~', '~0').replaceAll('/', '~1')}`;
      if (!Object.hasOwn(a, key) || !Object.hasOwn(b, key)) result.push(path); else differences(a[key], b[key], path, result);
    } return result;
  }
  result.push(prefix || '/'); return result;
}
function meta(fp: Fingerprint): Item { const { items: _, ...metadata } = fp.data; return { key: 'collection/session-metadata', category: 'collection', name: '采集条件与会话原始信息', source: { kind: 'collector', api: 'data metadata', uid: Number(fp.data.app.uid) || 0, pid: 0, selinux: '不适用' }, dataType: 'object', status: 'ok', value: metadata as unknown as Json }; }
function expand(fp: Fingerprint, semantic: boolean): Map<string, Item[]> {
  const groups = new Map<string, Item[]>();
  const add = (key: string, item: Item) => { const values = groups.get(key) || []; values.push(item); groups.set(key, values); };
  // 会话元数据保留为独立的“采集状态”行，便于解释 108 项差异；详情面板
  // 对该行只显示边界说明，不把时间、配置或权限包装冒充设备字段。
  for (const item of [meta(fp), ...mergePackageInstallers(fp.data.items)]) {
    if (semantic && item.key.startsWith('directories/chunk/') && Array.isArray(item.value)) {
      for (const entry of item.value) { if (object(entry) && typeof entry.path === 'string') add(`directories/path/${entry.path}`, { ...item, name: entry.path, value: entry, dataType: 'object' }); else add(item.key, item); }
    } else if (semantic && item.category === 'input' && object(item.value) && typeof item.value.descriptor === 'string') add(`input/descriptor/${item.value.descriptor}`, item);
    else if (semantic && item.key === 'binder/service-list' && item.status === 'ok' && object(item.value) && typeof item.value.stdout === 'string') {
      const lines = item.value.stdout.split('\n'); let parsed = 0;
      for (const line of lines) { const match = /^\s*\d+\s+([^\s:]+):\s*\[(.*)\]\s*$/.exec(line); if (match) { parsed++; add(`binder/service/${match[1]}`, { ...item, name: match[1], dataType: 'object', value: { serviceName: match[1], descriptor: match[2] } }); } }
      if (!parsed) add(item.key, item);
      else add('binder/command-status', { ...item, name: 'Binder 枚举命令状态', value: { exitCode: item.value.exitCode, stderr: item.value.stderr } });
    } else add(item.key, item);
  }
  return groups;
}
export function compare(left: Fingerprint, right: Fingerprint, rules: Rules): Row[] {
  const a = expand(left, rules.semantic), b = expand(right, rules.semantic); const rows: Row[] = [];
  for (const key of new Set([...a.keys(), ...b.keys()])) {
    const aa = a.get(key) || [], bb = b.get(key) || [];
    for (let i = 0; i < Math.max(aa.length, bb.length); i++) {
      const l = aa[i], r = bb[i], counter = { n: 0 }; let state: DiffState; let changedPaths: string[] = [];
      if (!l) state = 'right-only'; else if (!r) state = 'left-only';
      else {
        const va = project(comparisonValue(l), rules, counter, key);
        const vb = project(comparisonValue(r), rules, counter, key);
        changedPaths = differences(va, vb);
        if (l.status !== r.status) { state = 'status'; changedPaths.unshift('/status'); }
        else if (l.installer && r.installer && l.installer.status !== r.installer.status) state = 'status';
        else if (l.status !== 'ok' || l.source.kind !== r.source.kind || l.source.api !== r.source.api) state = 'incomparable';
        else if (l.installer && r.installer && (l.installer.status !== 'ok' || l.installer.source.kind !== r.installer.source.kind || l.installer.source.api !== r.installer.source.api)) state = changedPaths.some(path => !path.startsWith('/installer')) ? 'different' : 'incomparable';
        else state = changedPaths.length ? 'different' : 'same';
      }
      const item = l || r;
      rows.push({ id: `${key}#${i}`, key, name: item.name + (Math.max(aa.length, bb.length) > 1 ? `（重复项 ${i + 1}）` : ''), category: item.category, state, left: l, right: r, changedPaths, ignored: counter.n });
    }
  }
  return rows;
}
function comparisonValue(item: Item): Json {
  const installer = item.installer;
  return { dataType: item.dataType, value: item.value, error: item.error || null, truncation: item.truncation || null,
    // 来源身份在详情保留；沿用独立记录规则，不把采集进程PID变化当作安装来源变化。
    installer: installer ? { dataType: installer.dataType, value: installer.value, status: installer.status, source: { kind: installer.source.kind, api: installer.source.api }, error: installer.error || null, truncation: installer.truncation || null } : null };
}
export function conditions(a: Fingerprint, b: Fingerprint): string[] {
  const warnings: string[] = [];
  for (const [key, label] of [['androidSdk', 'Android API'], ['targetSdk', 'targetSdk'], ['signatures', 'APK 签名'], ['versionName', 'APK 版本']] as const) if (canonical(a.data.app[key] ?? null) !== canonical(b.data.app[key] ?? null)) warnings.push(`${label}不同`);
  if (canonical(a.data.permissions) !== canonical(b.data.permissions)) warnings.push('权限授权不同');
  if (canonical(a.data.config) !== canonical(b.data.config)) warnings.push('采集范围或配置不同');
  if (a.data.state !== 'complete' || b.data.state !== 'complete') warnings.push('包含非完整采集，请结合逐项状态比较');
  return warnings;
}
