import { describe, it, expect } from 'vitest';
import { compare, conditions } from './compare';
import { rawText, formattedText } from './format';
import { fixture, fixtureItem } from './fixtures';
import type { Json } from './types';
const raw = { semantic: false, ignoreDynamic: false }, semantic = { semantic: true, ignoreDynamic: false }, smart = { semantic: false, ignoreDynamic: true };
const find = (rows: ReturnType<typeof compare>, key: string) => rows.find(x => x.key === key)!;

describe('原始与语义比较', () => {
  it('编码器控制兼容旧JSON，区分未查询/不支持模式/错误，保留原能力和未知字段', () => {
    const key = 'hardware/codecs', old = { type: 'video/test', videoCapabilities: { available: true, widthPixels: { lower: 2, upper: 4096 } } };
    const report = (encoderCapabilities?: Json) => fixture([fixtureItem(key, [{ name: 'encoder', encoder: true, capabilities: [{ ...old, ...(encoderCapabilities === undefined ? {} : { encoderCapabilities }) }] }], { category: 'hardware' })]);
    const support = { available: true, status: 'ok', complexity: { status: 'ok', range: { lower: 0, upper: 10 } }, quality: { status: 'unsupported', range: null }, bitrateModes: { CBR_FD: { mode: 3, status: 'unsupported', supported: null } } };
    const legacy = report(), current = report(support), unavailable = report({ ...support, bitrateModes: { CBR_FD: { mode: 3, status: 'ok', supported: false } } });
    const error = report({ ...support, status: 'partial', quality: { status: 'error', range: null, errorClass: 'synthetic' } }), future = report({ ...support, future: false });
    const before = JSON.stringify([legacy, current, unavailable, error, future]);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(legacy, current, rules), key).changedPaths).toEqual(['/value/0/capabilities/0/encoderCapabilities']);
      expect(find(compare(current, current, rules), key).state).toBe('same');
      for (const changed of [unavailable, error, future]) expect(find(compare(current, changed, rules), key).state).toBe('different');
    }
    expect(JSON.stringify([legacy, current, unavailable, error, future])).toBe(before);
  });
  it('精确声道区间兼容旧音频JSON，不填充间隙，区分不支持/空集合/哈希变化', () => {
    const key = 'hardware/codecs', audio = { available: true, status: 'ok', maxInputChannelCount: 8 };
    const report = (value: Json) => fixture([fixtureItem(key, [{ name: 'codec', encoder: false, capabilities: [{ type: 'audio/test', audioCapabilities: value }] }], { category: 'hardware' })]);
    const legacy = report(audio), ranges = { available: true, status: 'ok', minInputChannelCount: 2, ranges: [{ lower: 2, upper: 2 }, { lower: 6, upper: 8 }], rangesSha256: 'a' };
    const current = report({ ...audio, inputChannelSupport: ranges }), hash = report({ ...audio, inputChannelSupport: { ...ranges, rangesSha256: 'b' } });
    const unsupported = report({ ...audio, inputChannelSupport: { available: false, status: 'unsupported', minInputChannelCount: null, ranges: null } });
    const empty = report({ ...audio, inputChannelSupport: { ...ranges, ranges: [] } });
    const before = JSON.stringify([legacy, current, hash, unsupported, empty]);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(legacy, current, rules), key).changedPaths).toEqual(['/value/0/capabilities/0/audioCapabilities/inputChannelSupport']);
      for (const changed of [hash, unsupported, empty]) expect(find(compare(current, changed, rules), key).state).toBe('different');
    }
    expect(JSON.stringify([legacy, current, hash, unsupported, empty])).toBe(before);
  });
  it('音频MIME能力兼容旧JSON，连续/离散采样率和截断外哈希变化保持区别', () => {
    const key = 'hardware/codecs', old = { name: 'codec', encoder: false, capabilities: [{ type: 'audio/test', profileLevels: [] }] };
    const legacy = fixture([fixtureItem(key, [old], { category: 'hardware' })]);
    const current = (audioCapabilities: Json) => fixture([fixtureItem(key, [{ ...old, capabilities: [{ ...old.capabilities[0], audioCapabilities }] }], { category: 'hardware' })]);
    const audio = { available: true, status: 'ok', sampleRateMode: 'ranges', sampleRatesHz: null, sampleRateRangesHz: [{ lower: 8000, upper: 48000 }], sampleRateRangesSha256: 'a', maxInputChannelCount: 2 };
    const base = current(audio), empty = current({ ...audio, sampleRateMode: 'discrete', sampleRatesHz: [] }), hash = current({ ...audio, sampleRateRangesSha256: 'b' });
    const before = JSON.stringify([legacy, base, empty, hash]);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(legacy, base, rules), key).changedPaths).toEqual(['/value/0/capabilities/0/audioCapabilities']);
      expect(find(compare(base, empty, rules), key).state).toBe('different');
      expect(find(compare(base, hash, rules), key).state).toBe('different');
    }
    expect(JSON.stringify([legacy, base, empty, hash])).toBe(before);
  });
  it('视频MIME能力新增不改旧字段，范围/空值/非视频状态参与比较', () => {
    const key = 'hardware/codecs', old = { name: 'codec', encoder: false, capabilities: [{ type: 'video/test', profileLevels: [] }] };
    const legacy = fixture([fixtureItem(key, [old], { category: 'hardware' })]);
    const current = (value: Json) => fixture([fixtureItem(key, [{ ...old, capabilities: [{ ...old.capabilities[0], videoCapabilities: value }] }], { category: 'hardware' })]);
    const video = { available: true, status: 'ok', frameRatesPerSecond: { lower: 0, upper: 960 }, widthPixels: { lower: 2, upper: 4096 }, heightPixels: { lower: 2, upper: 4096 }, widthAlignmentPixels: 2, heightAlignmentPixels: 2 };
    const report = current(video), changed = current({ ...video, widthPixels: { lower: 2, upper: 8192 } });
    const missing = current({ available: false, status: 'not-video', widthPixels: null });
    const before = JSON.stringify([legacy, report, changed, missing]);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(legacy, report, rules), key).changedPaths).toEqual(['/value/0/capabilities/0/videoCapabilities']);
      expect(find(compare(report, changed, rules), key).state).toBe('different');
      expect(find(compare(report, missing, rules), key).state).toBe('different');
    }
    expect(JSON.stringify([legacy, report, changed, missing])).toBe(before);
  });
  it('独立APEX清单兼容旧模块JSON，精确版本和完整集合哈希参与比较', () => {
    const key = 'build/apex-packages';
    const legacy = fixture([fixtureItem('build/modules', { modules: [{ packageName: 'configured.module' }] }, { category: 'build' })]);
    const current = fixture([...legacy.data.items, fixtureItem(key, { packages: [{ packageName: 'actual.apex', versionName: null, versionCode: { $type: 'int64', value: '9007199254740992' } }], packageSetSha256: 'a' }, { category: 'build' })]);
    const version = fixture([...legacy.data.items, fixtureItem(key, { packages: [{ packageName: 'actual.apex', versionName: null, versionCode: { $type: 'int64', value: '9007199254740993' } }], packageSetSha256: 'a' }, { category: 'build' })]);
    const hash = structuredClone(current);(hash.data.items[1].value as Record<string, unknown>).packageSetSha256 = 'b';
    const before = JSON.stringify([legacy, current, version, hash]);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(legacy, current, rules), key).state).toBe('right-only');
      expect(find(compare(legacy, current, rules), 'build/modules').state).toBe('same');
      expect(find(compare(current, version, rules), key).state).toBe('different');
      expect(find(compare(current, hash, rules), key).changedPaths).toEqual(['/value/packageSetSha256']);
    }
    expect(JSON.stringify([legacy, current, version, hash])).toBe(before);
  });
  it('旧Mainline JSON保留模块元数据，关联包版本缺项与64位版本变化参与比较', () => {
    const key = 'build/modules', module = { name: 'test', packageName: 'module.test', hidden: true };
    const legacy = fixture([fixtureItem(key, { modules: [module] }, { category: 'build' })]);
    const withVersion = (value: string) => fixture([fixtureItem(key, { modules: [{ ...module, packageVersion: { status: 'ok', versionCode: { $type: 'int64', value }, versionName: null, isApex: true } }] }, { category: 'build' })]);
    const current = withVersion('9007199254740992'), changed = withVersion('9007199254740993');
    const before = JSON.stringify([legacy, current, changed]);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(legacy, current, rules), key).changedPaths).toEqual(['/value/modules/0/packageVersion']);
      expect(find(compare(current, changed, rules), key).state).toBe('different');
    }
    expect(JSON.stringify([legacy, current, changed])).toBe(before);
  });
  it('CLDR 新条目兼容旧国际化JSON，API28以下不支持区别于成功值', () => {
    const key = 'environment/cldr-version';
    const legacy = fixture([fixtureItem('environment/icu-version', '75.1.0.0', { category: 'environment' })]);
    const current = fixture([...legacy.data.items, fixtureItem(key, '45.0.0.0', { category: 'environment' })]);
    const unsupported = fixture([...legacy.data.items, fixtureItem(key, null, { category: 'environment', status: 'unsupported', dataType: 'null' })]);
    const changed = structuredClone(current);changed.data.items[1].value = '45.0';
    const before = JSON.stringify([legacy, current, unsupported, changed]);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(legacy, current, rules), key).state).toBe('right-only');
      expect(find(compare(current, unsupported, rules), key).state).toBe('status');
      expect(find(compare(current, unsupported, rules), 'environment/icu-version').state).toBe('same');
      expect(find(compare(current, changed, rules), key).changedPaths).toEqual(['/value']);
    }
    expect(JSON.stringify([legacy, current, unsupported, changed])).toBe(before);
  });
  it('TLS 参数兼容旧JSON，默认/支持列表及截断外哈希独立参与比较', () => {
    const key = 'security/tls-parameters';
    const legacy = fixture([fixtureItem('security/providers', [{ name: 'test' }], { category: 'security' })]);
    const current = fixture([...legacy.data.items, fixtureItem(key, { defaultProtocols: ['TLSv1.3', 'TLSv1.2'], supportedProtocols: ['TLSv1.2', 'TLSv1.3'], supportedCipherSuitesSha256: 'a', future: false }, { category: 'security' })]);
    const changed = structuredClone(current);(changed.data.items[1].value as Record<string, unknown>).supportedCipherSuitesSha256 = 'b';
    const reordered = structuredClone(current);(reordered.data.items[1].value as Record<string, unknown>).defaultProtocols = ['TLSv1.2', 'TLSv1.3'];
    const before = JSON.stringify([legacy, current, changed, reordered]);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(legacy, current, rules), key).state).toBe('right-only');
      expect(find(compare(legacy, current, rules), 'security/providers').state).toBe('same');
      expect(find(compare(current, changed, rules), key).changedPaths).toEqual(['/value/supportedCipherSuitesSha256']);
      expect(find(compare(current, reordered, rules), key).state).toBe('different');
    }
    expect(JSON.stringify([legacy, current, changed, reordered])).toBe(before);
  });
  it('国际化版本兼容旧JSON，保留完整版本字符串并独立记录失败状态', () => {
    const keys = ['environment/icu-version', 'environment/unicode-version', 'environment/tzdata-version'];
    const legacy = fixture([fixtureItem('environment/locale-summary', { timezoneId: 'Asia/Shanghai' }, { category: 'environment' })]);
    const current = fixture([...legacy.data.items, ...keys.map((key, i) => fixtureItem(key, ['75.1.0.0', '15.1.0.0', '2024a'][i], { category: 'environment' }))]);
    const changed = structuredClone(current);changed.data.items[1].value = '75.1';
    const failed = structuredClone(current);failed.data.items[3].status = 'error';failed.data.items[3].value = null;
    const before = JSON.stringify([legacy, current, changed, failed]);
    for (const rules of [raw, semantic, smart]) {
      const rows = compare(legacy, current, rules);
      expect(find(rows, 'environment/locale-summary').state).toBe('same');
      for (const key of keys) expect(find(rows, key).state).toBe('right-only');
      expect(find(compare(current, changed, rules), keys[0]).changedPaths).toEqual(['/value']);
      expect(find(compare(current, failed, rules), keys[2]).state).toBe('status');
      expect(find(compare(current, failed, rules), keys[0]).state).toBe('same');
    }
    expect(JSON.stringify([legacy, current, changed, failed])).toBe(before);
  });
  it('编解码MIME特性保持旧JSON缺项、未知与false及必须启用标志的差异', () => {
    const key = 'hardware/codecs';
    const old = { name: 'codec', encoder: false, capabilities: [{ type: 'video/test', profileLevels: [] }] };
    const legacy = fixture([fixtureItem(key, [old], { category: 'hardware' })]);
    const current = (supported: boolean | null, required: boolean | null) => fixture([fixtureItem(key, [{ ...old, capabilities: [{ ...old.capabilities[0], featureFlags: { 'secure-playback': { supported, required } } }] }], { category: 'hardware' })]);
    const unknown = current(null, null), disabled = current(false, false), optional = current(true, false), mandatory = current(true, true);
    const before = JSON.stringify([legacy, unknown, disabled, optional, mandatory]);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(legacy, legacy, rules), key).state).toBe('same');
      expect(find(compare(legacy, unknown, rules), key).state).toBe('different');
      expect(find(compare(unknown, disabled, rules), key).state).toBe('different');
      expect(find(compare(optional, mandatory, rules), key).changedPaths).toEqual(['/value/0/capabilities/0/featureFlags/secure-playback/required']);
    }
    expect(JSON.stringify([legacy, unknown, disabled, optional, mandatory])).toBe(before);
  });
  it('旧摄像头 JSON 保留新组合单侧状态，截断外组合变化与空集合区别参与所有比较模式', () => {
    const key = 'hardware/camera-concurrency';
    const legacy = fixture([fixtureItem('hardware/camera-summary', { count: 2 }, { category: 'hardware' })]);
    const current = fixture([...legacy.data.items, fixtureItem(key, { combinationCount: 34, combinations: [{ cameraIds: ['0', '1'] }], combinationSetSha256: 'full-a' }, { category: 'hardware' })]);
    const outside = structuredClone(current);
    outside.data.items[1].value = { combinationCount: 34, combinations: [{ cameraIds: ['0', '1'] }], combinationSetSha256: 'full-b' };
    const empty = fixture([fixtureItem(key, { combinationCount: 0, combinations: [] }, { category: 'hardware' })]);
    const missing = fixture([fixtureItem(key, { combinationCount: null, combinations: null }, { category: 'hardware' })]);
    const before = JSON.stringify([legacy, current, outside]);
    for (const rules of [raw, semantic, smart]) {
      const rows = compare(legacy, current, rules);
      expect(find(rows, 'hardware/camera-summary').state).toBe('same');
      expect(find(rows, key).state).toBe('right-only');
      expect(find(compare(current, outside, rules), key).changedPaths).toEqual(['/value/combinationSetSha256']);
      expect(find(compare(empty, missing, rules), key).state).toBe('different');
    }
    expect(JSON.stringify([legacy, current, outside])).toBe(before);
  });
  it('旧 DRM JSON 保留新清单单侧状态，截断外方案变化仍参与比较', () => {
    const legacy = fixture([fixtureItem('hardware/drm/widevine', { supported: true }, { category: 'hardware' })]);
    const current = fixture([...legacy.data.items, fixtureItem('hardware/drm-schemes', { schemeUuids: ['uuid'], schemeListSha256: 'full-a' }, { category: 'hardware' })]);
    const changed = structuredClone(current);
    changed.data.items[1].value = { schemeUuids: ['uuid'], schemeListSha256: 'full-b' };
    for (const rules of [raw, semantic, smart]) {
      const rows = compare(legacy, current, rules);
      expect(find(rows, 'hardware/drm/widevine').state).toBe('same');
      expect(find(rows, 'hardware/drm-schemes').state).toBe('right-only');
      expect(find(compare(current, changed, rules), 'hardware/drm-schemes').changedPaths).toEqual(['/value/schemeListSha256']);
    }
  });
  it('WebView 签名轮换顺序和截断外历史哈希参与所有模式比较，旧 JSON 缺项保留差异', () => {
    const key = 'environment/webview-package-summary';
    const old = fixture([fixtureItem(key, { signatureCount: 1 }, { category: 'environment' })]);
    const left = fixture([fixtureItem(key, { signatureCount: 1, signingCertificateHistorySha256: ['a', 'b'], signingHistorySha256: 'full-a' }, { category: 'environment' })]);
    const reversed = structuredClone(left);
    reversed.data.items[0].value = { signatureCount: 1, signingCertificateHistorySha256: ['b', 'a'], signingHistorySha256: 'full-a' };
    const outside = structuredClone(left);
    outside.data.items[0].value = { signatureCount: 1, signingCertificateHistorySha256: ['a', 'b'], signingHistorySha256: 'full-b' };
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(left, reversed, rules), key).state).toBe('different');
      expect(find(compare(left, outside, rules), key).changedPaths).toEqual(['/value/signingHistorySha256']);
      expect(find(compare(old, left, rules), key).changedPaths).toContain('/value/signingCertificateHistorySha256');
      expect(find(compare(old, old, rules), key).state).toBe('same');
    }
  });
  it('新锁屏未知值与旧 false 保持差异，各种比较模式不将其合并', () => {
    const fields = ['keyguardSecure', 'keyguardLocked', 'deviceSecure', 'deviceLocked'];
    for (const key of ['security/summary', 'security/lock-biometric']) {
      const old = fixture([fixtureItem(key, Object.fromEntries(fields.map(field => [field, false])), { category: 'security' })]);
      const current = fixture([fixtureItem(key, Object.fromEntries(fields.map(field => [field, null])), { category: 'security' })]);
      const before = JSON.stringify([old, current]);
      for (const rules of [raw, semantic, smart]) {
        const row = find(compare(old, current, rules), key);
        expect(row.state).toBe('different');
        expect(row.changedPaths).toEqual(fields.map(field => `/value/${field}`));
        expect(find(compare(old, old, rules), key).state).toBe('same');
      }
      expect(JSON.stringify([old, current])).toBe(before);
    }
  });
  it('覆盖相同、单侧、状态不同、类型不同与不可比较', () => { const a = fixture([fixtureItem('same'), fixtureItem('left'), fixtureItem('status'), fixtureItem('type', 1), fixtureItem('denied', null, { status: 'denied' })]); const b = fixture([fixtureItem('same'), fixtureItem('right'), fixtureItem('status', null, { status: 'denied' }), fixtureItem('type', '1'), fixtureItem('denied', null, { status: 'denied' })]); const rows = compare(a, b, raw); expect(find(rows, 'same').state).toBe('same'); expect(find(rows, 'left').state).toBe('left-only'); expect(find(rows, 'right').state).toBe('right-only'); expect(find(rows, 'status').state).toBe('status'); expect(find(rows, 'type').state).toBe('different'); expect(find(rows, 'denied').state).toBe('incomparable'); });
  it('默认保留数组顺序，已知集合语义排序', () => { const a = fixture([fixtureItem('sensors/list', [{ name: 'a' }, { name: 'b' }], { category: 'sensors' })]); const b = fixture([fixtureItem('sensors/list', [{ name: 'b' }, { name: 'a' }], { category: 'sensors' })]); expect(find(compare(a, b, raw), 'sensors/list').state).toBe('different'); expect(find(compare(a, b, semantic), 'sensors/list').state).toBe('same'); });
  it('目录按路径对齐并保留重复项，不覆盖', () => { const a = fixture([fixtureItem('directories/chunk/00000', [{ path: '/a' }, { path: '/a' }, { path: '/b' }], { category: 'directories' })]); const b = fixture([fixtureItem('directories/chunk/00001', [{ path: '/b' }, { path: '/a' }], { category: 'directories' })]); const rows = compare(a, b, semantic).filter(x => x.category === 'directories'); expect(rows).toHaveLength(3); expect(rows.filter(x => x.state === 'left-only')).toHaveLength(1); });
  it('不改变原件，只按显式规则过滤动态字段', () => { const a = fixture([fixtureItem('attestation/tee', { challengeBase64: 'a', stable: 1 })]); const b = fixture([fixtureItem('attestation/tee', { challengeBase64: 'b', stable: 1 })]); const before = JSON.stringify(a); expect(find(compare(a, b, raw), 'attestation/tee').state).toBe('different'); const filtered = find(compare(a, b, { semantic: true, ignoreDynamic: true }), 'attestation/tee'); expect(filtered.state).toBe('same'); expect(filtered.ignored).toBe(2); expect(JSON.stringify(a)).toBe(before); });
	it('智能过滤忽略 pid、uid、运行与启动时间，但保留稳定字段', () => { const a = fixture([fixtureItem('security/self', { pid: 10, uid: 1000, runTime: 100, startTime: 'a', stable: 'same' })]); const b = fixture([fixtureItem('security/self', { pid: 20, uid: 1001, runTime: 200, startTime: 'b', stable: 'changed' })]); const row = find(compare(a, b, smart), 'security/self'); expect(row.state).toBe('different'); expect(row.changedPaths).toContain('/value/stable'); expect(row.changedPaths.some(path => /pid|uid|runTime|startTime/.test(path))).toBe(false); expect(row.ignored).toBe(8); });
	it('应用清单只因 application.uid 变化时被智能过滤，稳定字段变化仍保留', () => {
		const a = fixture([fixtureItem('packages/com.example.app', { packageName: 'com.example.app', versionName: '1.0', application: { uid: 10001, processName: 'com.example.app' } }, { category: 'packages' })]);
		const b = fixture([fixtureItem('packages/com.example.app', { packageName: 'com.example.app', versionName: '1.0', application: { uid: 10042, processName: 'com.example.app' } }, { category: 'packages' })]);
		const same = find(compare(a, b, smart), 'packages/com.example.app');
		expect(same.state).toBe('same');
		expect(same.changedPaths).toEqual([]);
		expect(same.ignored).toBe(2);
		b.data.items[0].value = { packageName: 'com.example.app', versionName: '2.0', application: { uid: 10042, processName: 'com.example.app' } };
		const changed = find(compare(a, b, smart), 'packages/com.example.app');
		expect(changed.state).toBe('different');
		expect(changed.changedPaths).toContain('/value/versionName');
		expect(changed.changedPaths.some(path => path.endsWith('/uid'))).toBe(false);
	});
	it('智能过滤忽略电源与热状态快照，但保留稳定字段', () => {
		const a = fixture([fixtureItem('environment/power-thermal', { powerSaveMode: false, interactive: true, thermalStatus: 0, stable: 'a' }, { category: 'environment' })]);
		const b = fixture([fixtureItem('environment/power-thermal', { powerSaveMode: true, interactive: false, thermalStatus: 3, stable: 'b' }, { category: 'environment' })]);
		const row = find(compare(a, b, smart), 'environment/power-thermal');
		expect(row.state).toBe('different');
		expect(row.changedPaths).toEqual(['/value/stable']);
		expect(row.ignored).toBe(6);
	});
  it('智能过滤忽略内存瞬时值，但保留设备内存容量', () => {
    const a = fixture([fixtureItem('environment/memory-summary', { totalMemBytes: 8, availMemBytes: 4, runtimeTotalMemoryBytes: 3, runtimeFreeMemoryBytes: 2, heapUsedBytes: 1 }, { category: 'environment' })]);
    const b = fixture([fixtureItem('environment/memory-summary', { totalMemBytes: 16, availMemBytes: 6, runtimeTotalMemoryBytes: 5, runtimeFreeMemoryBytes: 1, heapUsedBytes: 4 }, { category: 'environment' })]);
    const row = find(compare(a, b, smart), 'environment/memory-summary');
    expect(row.state).toBe('different');
    expect(row.changedPaths).toEqual(['/value/totalMemBytes']);
    expect(row.ignored).toBe(8);
  });
  it('智能过滤忽略电话注册与信号动态摘要字段', () => {
    const a = fixture([
      fixtureItem('sim/service-summary', { serviceState: 0, registrationCount: 2, registeredCount: 2, domainCounts: { '1': 2 }, manualSelection: false }, { category: 'sim' }),
      fixtureItem('sim/signal-summary', { timestampMillis: '100', signalLevel: 2, dbmMin: -110, dbmMax: -80, levelCounts: { '2': 1 }, typeCounts: { Lte: 1 }, available: true }, { category: 'sim' }),
    ]);
    const b = fixture([
      fixtureItem('sim/service-summary', { serviceState: 1, registrationCount: 0, registeredCount: 0, domainCounts: {}, manualSelection: false }, { category: 'sim' }),
      fixtureItem('sim/signal-summary', { timestampMillis: '200', signalLevel: 4, dbmMin: -70, dbmMax: -50, levelCounts: { '4': 1 }, typeCounts: { Nr: 1 }, available: true }, { category: 'sim' }),
    ]);
    const service = find(compare(a, b, smart), 'sim/service-summary');
    const signal = find(compare(a, b, smart), 'sim/signal-summary');
    expect(service.state).toBe('same');
    expect(signal.state).toBe('same');
    expect(service.ignored).toBeGreaterThan(0);
    expect(signal.ignored).toBeGreaterThan(0);
  });
  it('智能过滤忽略 eUICC 可用内存变化，但保留能力字段', () => {
    const a = fixture([fixtureItem('sim/euicc-summary', { enabled: true, availableMemoryBytes: 4096 }, { category: 'sim' })]);
    const b = fixture([fixtureItem('sim/euicc-summary', { enabled: true, availableMemoryBytes: 2048 }, { category: 'sim' })]);
    const same = find(compare(a, b, smart), 'sim/euicc-summary');
    expect(same.state).toBe('same');
    expect(same.changedPaths).toEqual([]);
    expect(same.ignored).toBe(2);
    b.data.items[0].value = { enabled: false, availableMemoryBytes: 2048 };
    const changed = find(compare(a, b, smart), 'sim/euicc-summary');
    expect(changed.state).toBe('different');
    expect(changed.changedPaths).toEqual(['/value/enabled']);
  });
  it('USB 摘要语义比较按厂商与产品 ID 集合排序', () => {
    const left = fixture([fixtureItem('hardware/usb-summary', { vendorIds: [1204, 18], productIds: [4660, 52] }, { category: 'hardware' })]);
    const right = fixture([fixtureItem('hardware/usb-summary', { vendorIds: [18, 1204], productIds: [52, 4660] }, { category: 'hardware' })]);
    expect(find(compare(left, right, raw), 'hardware/usb-summary').state).toBe('different');
    expect(find(compare(left, right, semantic), 'hardware/usb-summary').state).toBe('same');
  });
  it('同一对象键顺序不产生差异，字段缺失区别于 null', () => { const a = fixture([fixtureItem('object', { x: 1, y: null })]); const b = fixture([fixtureItem('object', { y: null, x: 1 })]); expect(find(compare(a, b, raw), 'object').state).toBe('same'); b.data.items[0].value = { x: 1 }; expect(find(compare(a, b, raw), 'object').changedPaths).toContain('/value/y'); });
  it('提示 API、签名、授权、配置与非完整结果差异', () => { const a = fixture(), b = fixture(); b.data.app.androidSdk = 35; b.data.permissions.camera = true; b.data.config.changed = true; b.data.state = 'partial'; expect(conditions(a, b)).toHaveLength(4); });
  it('恶意内容保持文本，格式化不改原值', () => { const text = '{"x":"<script>globalThis.pwned=true</script>"}'; expect(formattedText(text)).toContain('\n'); expect(rawText(text)).toBe(text); expect((globalThis as Record<string, unknown>).pwned).toBeUndefined(); });
  it('输入设备按 descriptor 对齐，不把 id 漂移变成删除新增', () => { const a = fixture([fixtureItem('input/device/1', { descriptor: 'same', id: 1 }, { category: 'input' })]); const b = fixture([fixtureItem('input/device/9', { descriptor: 'same', id: 9 }, { category: 'input' })]); const rows = compare(a, b, semantic).filter(x => x.category === 'input'); expect(rows).toHaveLength(1); expect(rows[0].state).toBe('different'); });
});
