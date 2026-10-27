import { describe, it, expect } from 'vitest';
import { compare, conditions } from './compare';
import { rawText, formattedText } from './format';
import { fixture, fixtureItem } from './fixtures';
import type { Json } from './types';
const raw = { semantic: false, ignoreDynamic: false }, semantic = { semantic: true, ignoreDynamic: false }, smart = { semantic: false, ignoreDynamic: true };
const find = (rows: ReturnType<typeof compare>, key: string) => rows.find(x => x.key === key)!;

describe('原始与语义比较', () => {
  it('Wi-Fi芯片标准支持兼容旧链路JSON，编号/false/null/类型/失败与未知字段继续比较', () => {
    const key = 'network/wifi-standard-support', legacyKey = 'network/wifi-capabilities', old = fixture([fixtureItem(legacyKey, { available: true, wifiStandard: 6 }, { category: 'network' })]);
    const entry = { standard: 1, status: 'ok', supported: false }, value = { managerAvailable: true, queryCount: 6, queryLimit: 6, entries: [entry] };
    const report = (next: Json) => fixture([...old.data.items, fixtureItem(key, next, { category: 'network' })]), current = report(value), before = JSON.stringify(current);
    const changes: Json[] = [{ ...value, managerAvailable: false }, { ...value, queryCount: 0 }, { ...value, future: false }, ...[{ ...entry, supported: true }, { ...entry, supported: 'false' }, { ...entry, standard: '1' }, { ...entry, standard: 8 }, { ...entry, status: 'unsupported', supported: null }, { ...entry, status: 'unavailable', supported: null }, { ...entry, status: 'denied', supported: null }, { ...entry, status: 'error', supported: null, errorClass: 'SYNTHETIC' }, { ...entry, future: false }].map(next => ({ ...value, entries: [next] }))];
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(old, current, rules), legacyKey).state).toBe('same');expect(find(compare(old, current, rules), key).state).toBe('right-only');
      expect(find(compare(current, undefined, rules), key).state).toBe('left-only');expect(find(compare(current, current, rules), key).state).toBe('same');
      for (const next of changes) expect(find(compare(current, report(next), rules), key).state).toBe('different');
    }
    expect(JSON.stringify(current)).toBe(before);
  });
  it('Widevine容器级别兼容旧JSON并保留false/null/失败/类型/顺序及未知字段差异', () => {
    const key = 'hardware/drm/widevine-capabilities', legacyKey = 'hardware/drm/widevine';
    const old = fixture([fixtureItem(legacyKey, { supported: true, maxSecurityLevel: 6 }, { category: 'hardware' })]);
    const entry = { mimeType: 'video/mp4', securityLevel: 1, status: 'ok', supported: false };
    const value = { schemeUuid: 'edef8ba9-79d6-4ace-a3c8-27dcd51d21ed', queryCount: 20, queryLimit: 20, entries: [entry, { ...entry, securityLevel: 2, supported: true }] };
    const report = (next: Json) => fixture([...old.data.items, fixtureItem(key, next, { category: 'hardware' })]);
    const current = report(value), before = JSON.stringify(current);
    const changes: Json[] = [{ ...value, entries: [] }, { ...value, entries: null }, ...[
      { ...entry, supported: true }, { ...entry, supported: 'false' }, { ...entry, status: 'denied', supported: null },
      { ...entry, status: 'unsupported', supported: null }, { ...entry, status: 'error', supported: null, errorClass: 'SYNTHETIC' },
      { ...entry, securityLevel: '1' }, { ...entry, mimeType: 'audio/mp4' }, { ...entry, future: false },
    ].map(next => ({ ...value, entries: [next, value.entries[1]] })), { ...value, entries: [...value.entries].reverse() }, { ...value, future: false }];
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(old, current, rules), legacyKey).state).toBe('same');
      expect(find(compare(old, current, rules), key).state).toBe('right-only');
      expect(find(compare(current, undefined, rules), key).state).toBe('left-only');
      expect(find(compare(current, current, rules), key).state).toBe('same');
      for (const next of changes) expect(find(compare(current, report(next), rules), key).state).toBe('different');
    }
    expect(JSON.stringify(current)).toBe(before);
  });
  it('热阈值兼容旧JSON，缺省/空/失败/非有限值、未知编号与类型变化均参与比较', () => {
    const key = 'environment/thermal-headroom-thresholds', old = fixture([fixtureItem('environment/power-thermal', { thermalStatus: 0 })]);
    const value = { managerAvailable: true, status: 'ok', entryCount: 1, entryLimit: 64, entries: [{ thermalStatus: 3, headroom: 1 }] };
    const report = (next: Json) => fixture([...old.data.items, fixtureItem(key, next, { category: 'environment' })]);
    const current = report(value), before = JSON.stringify(current);
    const changes: Json[] = [{ ...value, entries: [] }, { ...value, status: 'error', entries: null }, { ...value, entries: [{ thermalStatus: 999, headroom: 1 }] }, { ...value, entries: [{ thermalStatus: 3, headroom: '1' }] }, { ...value, entries: [{ thermalStatus: 3, headroom: { $type: 'float', value: 'NaN' } }] }, { ...value, future: false }];
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(old, current, rules), key).state).toBe('right-only');
      expect(find(compare(current, undefined, rules), key).state).toBe('left-only');
      expect(find(compare(current, current, rules), key).state).toBe('same');
      for (const next of changes) expect(find(compare(current, report(next), rules), key).state).toBe('different');
    }
    expect(JSON.stringify(current)).toBe(before);
  });
  it('隐私开关能力兼容旧JSON，false/未知/失败/类型和独立硬件能力继续比较', () => {
    const key = 'security/sensor-privacy-capabilities', old = fixture([fixtureItem('security/lock-biometric', { deviceSecure: true })]);
    const softwareToggle = { status: 'ok', value: true }, hardwareToggle = { status: 'ok', value: false };
    const value = { managerAvailable: true, microphone: { softwareToggle, hardwareToggle }, camera: { softwareToggle, hardwareToggle } };
    const report = (next: Json) => fixture([...old.data.items, fixtureItem(key, next, { category: 'security' })]);
    const current = report(value), before = JSON.stringify(current);
    const changes: Json[] = [{ ...value, microphone: { softwareToggle, hardwareToggle: { status: 'ok', value: true } } }, { ...value, camera: { softwareToggle, hardwareToggle: { status: 'ok', value: 'false' } } }, { ...value, camera: { softwareToggle, hardwareToggle: { status: 'unsupported', value: null } } }, { ...value, microphone: { softwareToggle: { status: 'denied', value: null, errorClass: 'java.lang.SecurityException' }, hardwareToggle } }, { ...value, managerAvailable: null }, { ...value, future: false }];
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(old, current, rules), key).state).toBe('right-only');
      expect(find(compare(current, current, rules), key).state).toBe('same');
      for (const next of changes) expect(find(compare(current, report(next), rules), key).state).toBe('different');
    }
    expect(JSON.stringify(current)).toBe(before);
  });
  it('LE Audio兼容旧JSON，三查询独立，原始码/类型/失败与未知字段不丢失', () => {
    const key = 'network/bluetooth-le-audio', old = fixture([fixtureItem('network/bluetooth-capabilities', { available: true })]);
    const value = { adapterAvailable: true, leAudio: { status: 'ok', code: 10 }, broadcastSource: { status: 'ok', code: 1 }, broadcastAssistant: { status: 'error', code: null, errorClass: 'java.lang.IllegalStateException' } };
    const report = (next: Json) => fixture([...old.data.items, fixtureItem(key, next, { category: 'network' })]);
    const current = report(value), before = JSON.stringify(current);
    const changes: Json[] = [{ ...value, leAudio: { status: 'ok', code: 11 } }, { ...value, leAudio: { status: 'ok', code: '10' } }, { ...value, broadcastSource: { status: 'unsupported', code: null } }, { ...value, broadcastSource: { status: 'ok', code: 2147483647 } }, { ...value, broadcastAssistant: { status: 'denied', code: null, errorClass: 'java.lang.SecurityException' } }, { ...value, future: false }];
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(old, current, rules), key).state).toBe('right-only');
      expect(find(compare(current, current, rules), key).state).toBe('same');
      for (const next of changes) expect(find(compare(current, report(next), rules), key).state).toBe('different');
    }
    expect(JSON.stringify(current)).toBe(before);
  });
  it('NFC天线兼容旧JSON，坐标原序/重复/哈希/失败和原始类型继续比较', () => {
    const key = 'hardware/nfc-antennas', old = fixture([fixtureItem('hardware/nfc', { available: true, antennaCount: 2 })]);
    const a = { locationXmm: 0, locationYmm: -1 }, b = { locationXmm: 1, locationYmm: 2 };
    const value = { status: 'ok', antennasSha256: 'a', antennas: [a, b, a] }, report = (next: Json) => fixture([...old.data.items, fixtureItem(key, next)]);
    const current = report(value), before = JSON.stringify(current);
    const changes: Json[] = [{ ...value, antennas: [a, a, b] }, { ...value, antennas: [a, b] }, { ...value, antennasSha256: 'b' }, { status: 'not-provided', antennas: null }, { ...value, antennas: [{ ...a, locationXmm: '0' }, b, a] }, { ...value, future: false }];
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(old, current, rules), key).state).toBe('right-only');
      expect(find(compare(current, current, rules), key).state).toBe('same');
      for (const next of changes) expect(find(compare(current, report(next), rules), key).state).toBe('different');
    }
    expect(JSON.stringify(current)).toBe(before);
  });
  it('NFC Observe Mode兼容旧JSON，独立状态、false/null和原始类型不混淆', () => {
    const key = 'hardware/nfc-observe-mode', old = fixture([fixtureItem('hardware/nfc', { available: true, enabled: false })]);
    const value = { available: true, supported: { status: 'ok', value: false }, enabled: { status: 'ok', value: true } };
    const current = fixture([...old.data.items, fixtureItem(key, value)]), before = JSON.stringify(current);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(old, current, rules), key).state).toBe('right-only');
      expect(find(compare(current, current, rules), key).state).toBe('same');
      const alternatives: Json[] = [{ ...value, supported: { status: 'ok', value: 'false' } }, { ...value, supported: { status: 'error', value: null } }, { ...value, enabled: { status: 'ok', value: false } }, { ...value, supported: { status: 'unavailable', value: null } }];
      for (const next of alternatives) expect(find(compare(current, fixture([...old.data.items, fixtureItem(key, next)]), rules), key).state).toBe('different');
    }
    expect(JSON.stringify(current)).toBe(before);
  });
  it('USB端点兼容旧接口，完整哈希/原序/重复/原始类型和状态参与比较', () => {
    const key = 'hardware/usb', endpoint = { address: 129, endpointNumber: 1, direction: 128, attributes: 2, type: 2, maxPacketSize: 512, interval: 0 };
    const old = { count: 1, devices: [{ interfaces: [{ id: 0, endpointCount: 2 }] }] };
    const report = (cap?: Json) => fixture([fixtureItem(key, cap === undefined ? old : { count: 1, devices: [{ interfaces: [{ id: 0, endpointCount: 2, endpointDescriptors: cap }] }] })]);
    const base = { status: 'ok', endpointsSha256: 'a', endpoints: [endpoint, endpoint] }, current = report(base);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(report(), current, rules), key).state).toBe('different');
      expect(find(compare(current, current, rules), key).state).toBe('same');
      for (const changed of [{ ...base, endpointsSha256: 'b' }, { ...base, endpoints: [{ ...endpoint, direction: '128' }, endpoint] }, { ...base, future: false }, { status: 'skipped', endpoints: null }, { ...base, endpoints: [endpoint] }]) expect(find(compare(current, report(changed), rules), key).state).toBe('different');
    }
  });
  it('公开音频属性兼容旧报告，保留字符串/null/失败，不把文本声明转成布尔或数字', () => {
    const ids = ['output-sample-rate', 'output-frames-per-buffer', 'mic-near-ultrasound', 'speaker-near-ultrasound', 'source-unprocessed'];
    const items = ids.map((id, index) => fixtureItem(`hardware/audio-property/${id}`, ['0048000', '192', 'false', 'true', null][index], { category: 'hardware' }));
    const current = fixture(items), old = fixture([]), before = JSON.stringify(current);
    for (const rules of [raw, semantic, smart]) {
      for (const item of items) {
        expect(find(compare(old, current, rules), item.key).state).toBe('right-only');
        expect(find(compare(current, current, rules), item.key).state).toBe('same');
        const changed = fixture(items.map(value => value === item ? { ...item, status: 'denied', value: null, dataType: 'null' } : value));
        expect(find(compare(current, changed, rules), item.key).state).toBe('status');
      }
      expect(find(compare(current, fixture([{ ...items[2], value: false, dataType: 'boolean' }]), rules), items[2].key).state).toBe('different');
      expect(find(compare(current, fixture([{ ...items[0], value: 48000, dataType: 'number' }]), rules), items[0].key).state).toBe('different');
    }
    expect(JSON.stringify(current)).toBe(before);
  });
  it('任意单侧可比较，保留真实元数据、重复项及失败状态，不补造另一侧', () => {
    const report = fixture([fixtureItem('present', 1), fixtureItem('denied', null, { status: 'denied' })]);
    const before = JSON.stringify(report);
    for (const rules of [raw, semantic, smart]) {
      expect(compare(undefined, undefined, rules)).toEqual([]);
      for (const side of ['left', 'right'] as const) {
        const rows = compare(side === 'left' ? report : undefined, side === 'right' ? report : undefined, rules);
        expect(rows).toHaveLength(3);
        expect(rows.every(row => row.state === `${side}-only` && row[side] && !row[side === 'left' ? 'right' : 'left'])).toBe(true);
        expect(rows.map(row => row.id)).toEqual(['collection/session-metadata#0', 'present#0', 'denied#0']);
        expect(rows[2][side]?.status).toBe('denied');
        expect(rows.every(row => row.changedPaths.length === 0 && row.ignored === 0)).toBe(true);
      }
      expect(compare(report, report, rules).map(row => row.state)).toEqual(['same', 'same', 'incomparable']);
    }
    const directories = fixture([fixtureItem('directories/chunk/00000', [{ path: '/same' }, { path: '/same' }])]);
    expect(compare(directories, undefined, semantic).map(row => row.id)).toEqual(['collection/session-metadata#0', 'directories/path//same#0', 'directories/path//same#1']);
    expect(JSON.stringify(report)).toBe(before);
  });
  it('逐麦克风元数据兼容旧JSON，区分哨兵/零/失败和原始类型，保留频响及集合哈希差异', () => {
    const key = 'hardware/microphones', summary = fixtureItem('hardware/microphone-summary', { count: 1 });
    const value = { status: 'ok', microphonesSha256: 'a', microphones: [{ id: 0, group: -1, positionMeters: { status: 'ok', value: { x: 0, y: 0, z: 0, kind: 'finite' } }, calibration: { status: 'ok', value: { sensitivityDbfs: { raw: -3.4028235e38, kind: 'unknown-sentinel' } } }, frequencyResponse: { status: 'ok', value: { pointsSha256: 'a', points: [{ frequencyHz: 1000, responseDb: 0 }, { frequencyHz: 1000, responseDb: 0 }] } } }] };
    const report = (v: Json) => fixture([summary, fixtureItem(key, v)]), old = fixture([summary]), current = report(value);
    const mic = value.microphones[0];
    const variants: Json[] = [{ ...value, microphonesSha256: 'b' }, { ...value, microphones: [{ ...mic, id: '0' }] }, { ...value, microphones: [{ ...mic, calibration: { status: 'ok', value: { sensitivityDbfs: { raw: 0, kind: 'finite' } } } }] }, { ...value, microphones: [{ ...mic, frequencyResponse: { status: 'error', value: null } }] }, { ...value, microphones: [{ ...mic, future: false }] }, { status: 'ok', microphones: [] }, { status: 'unsupported', microphones: null }];
    const inputs = [old, current, ...variants.map(report)], before = JSON.stringify(inputs);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(old, current, rules), key).state).toBe('right-only');expect(find(compare(old, current, rules), summary.key).state).toBe('same');
      expect(find(compare(current, current, rules), key).state).toBe('same');
      for (const next of inputs.slice(2)) expect(find(compare(current, next, rules), key).state).toBe('different');
    }
    expect(JSON.stringify(inputs)).toBe(before);
  });
  it('扬声器布局兼容旧JSON，保留零/有符号掩码、不适用、失败与未知字段', () => {
    const key = 'hardware/audio', device = { id: 3, type: 2, channelCounts: [2], audioProfiles: { status: 'ok', profiles: [] } };
    const report = (speakerLayout?: Json) => fixture([fixtureItem(key, [{ ...device, ...(speakerLayout === undefined ? {} : { speakerLayout }) }], { category: 'hardware' })]);
    const value = { status: 'ok', channelMask: 0 }, old = report(), current = report(value);
    const variants: Json[] = [{ ...value, channelMask: 12 }, { ...value, channelMask: -2147483648 }, { ...value, channelMask: '0' }, { status: 'unsupported', channelMask: null }, { status: 'not-applicable', channelMask: null }, { status: 'error', channelMask: null, errorClass: 'synthetic' }, { ...value, future: false }, null];
    const inputs = [old, current, ...variants.map(report)], before = JSON.stringify(inputs);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(old, current, rules), key).state).toBe('different');expect(find(compare(current, current, rules), key).state).toBe('same');
      for (const next of inputs.slice(2)) expect(find(compare(current, next, rules), key).state).toBe('different');
    }
    expect(JSON.stringify(inputs)).toBe(before);
  });
  it('音频混音属性兼容旧JSON，保留原序重复、类型、空与失败及截断外哈希差异', () => {
    const key = 'hardware/audio', device = { id: 1, type: 2, sampleRates: [48000], audioProfiles: { status: 'ok', profiles: [] } };
    const report = (supportedMixerAttributes?: Json) => fixture([fixtureItem(key, [{ ...device, ...(supportedMixerAttributes === undefined ? {} : { supportedMixerAttributes }) }], { category: 'hardware' })]);
    const attribute = { mixerBehavior: 0, sampleRateHz: 48000, encoding: 2, channelMask: 12, channelIndexMask: 0, channelCount: 2 };
    const value = { status: 'partial', attributeCount: 65, returnedCount: 64, attributeLimit: 64, attributesTruncated: true, attributesSha256: 'a', attributes: Array.from({ length: 64 }, () => attribute) };
    const variants: Json[] = [{ ...value, attributesSha256: 'b' }, { ...value, attributes: [{ ...attribute, mixerBehavior: 1 }, ...value.attributes.slice(1)] }, { ...value, attributes: [{ ...attribute, sampleRateHz: '48000' }, ...value.attributes.slice(1)] }, { ...value, future: false }, { status: 'ok', attributes: [] }, { status: 'unsupported', attributes: null }, { status: 'error', attributes: null, errorClass: 'synthetic' }, null];
    const old = report(), current = report(value), inputs = [old, current, ...variants.map(report)], before = JSON.stringify(inputs);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(old, current, rules), key).state).toBe('different');
      expect(find(compare(old, current, rules), key).changedPaths).toContain('/value/0/supportedMixerAttributes');
      expect(find(compare(current, current, rules), key).state).toBe('same');
      for (const next of inputs.slice(2)) expect(find(compare(current, next, rules), key).state).toBe('different');
      expect(find(compare(report({ status: 'ok', attributes: [] }), report({ status: 'ok', attributes: null }), rules), key).state).toBe('different');
    }
    expect(JSON.stringify(inputs)).toBe(before);
  });
  it('音频关联profile兼容旧JSON，保留原序/重复、掩码、空集合及截断外哈希', () => {
    const key = 'hardware/audio', device = { id: 1, type: 2, encodings: [2], sampleRates: [48000], channelCounts: [2] };
    const report = (profiles?: Json) => fixture([fixtureItem(key, [{ ...device, ...(profiles === undefined ? {} : { audioProfiles: profiles }) }], { category: 'hardware' })]);
    const profile = { format: 2, sampleRatesHz: [48000, 48000, 0], channelMasks: [12, -2147483648], channelIndexMasks: [], encapsulationType: 0 };
    const value = { status: 'ok', profiles: [profile, profile], profilesSha256: 'a' };
    const variants: Json[] = [{ ...value, profilesSha256: 'b' }, { ...value, profiles: [{ ...profile, channelMasks: [-2147483648, 12] }, profile] }, { ...value, profiles: [{ ...profile, encapsulationType: 999 }, profile] }, { status: 'ok', profiles: [] }, { status: 'unsupported', profiles: null }, { status: 'denied', profiles: null }, { ...value, future: false }];
    const old = report(), current = report(value), inputs = [old, current, ...variants.map(report)], before = JSON.stringify(inputs);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(old, current, rules), key).state).toBe('different');
      expect(find(compare(current, current, rules), key).state).toBe('same');
      for (const next of inputs.slice(2)) expect(find(compare(current, next, rules), key).state).toBe('different');
    }
    expect(JSON.stringify(inputs)).toBe(before);
  });
  it('传感器公开元数据兼容旧JSON，保留ID哨兵、false、未知值和独立失败', () => {
    const key = 'sensors/list', sensor = { name: 'synthetic', vendor: 'test', type: 1, version: 1 };
    const report = (metadata?: Json) => fixture([fixtureItem(key, [{ ...sensor, ...(metadata === undefined ? {} : { publicMetadata: metadata }) }], { category: 'sensors' })]);
    const metadata = { sensorId: { status: 'ok', value: 0, kind: 'unsupported' }, additionalInfoSupported: { status: 'ok', value: false } };
    const legacy = report(), current = report(metadata);
    const variants: Json[] = [{ ...metadata, sensorId: { status: 'ok', value: -1, kind: 'type-and-name' } }, { ...metadata, sensorId: { status: 'ok', value: -2147483648, kind: 'app-scoped-id' } }, { ...metadata, additionalInfoSupported: { status: 'ok', value: true } }, { ...metadata, additionalInfoSupported: { status: 'error', value: null, errorClass: 'synthetic' } }, { ...metadata, sensorId: { status: 'denied', value: null } }, { ...metadata, future: false }];
    const inputs = [legacy, current, ...variants.map(report)], before = JSON.stringify(inputs);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(legacy, current, rules), key).state).toBe('different');
      expect(find(compare(current, current, rules), key).state).toBe('same');
      for (const next of inputs.slice(2)) expect(find(compare(current, next, rules), key).state).toBe('different');
    }
    expect(JSON.stringify(inputs)).toBe(before);
  });
  it('平台安全状态兼容旧JSON，保留补丁/版本原文、空值及截断外变化', () => {
    const key = 'security/platform-security-state';
    const report = (value: Json) => fixture([fixtureItem(key, value, { category: 'security' })]);
    const value = { available: true, status: 'ok', entries: [{ key: 'system_spl', value: '2026-02-05' }, { key: 'future.package', value: '1.0.0.0' }], entriesSha256: 'a' };
    const legacy = fixture([]), current = report(value);
    const variants = [report({ ...value, entriesSha256: 'b' }), report({ ...value, entries: [{ key: 'system_spl', value: '' }] }), report({ ...value, entries: [{ key: 'system_spl', value: null }] }), report({ available: true, status: 'ok', entries: [] }), report({ available: false, status: 'unavailable', entries: null }), report({ ...value, future: false })];
    const before = JSON.stringify([legacy, current, ...variants]);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(legacy, current, rules), key).state).toBe('right-only');
      expect(find(compare(current, current, rules), key).state).toBe('same');
      for (const next of variants) expect(find(compare(current, next, rules), key).state).toBe('different');
      expect(find(compare(variants[1], variants[2], rules), key).state).toBe('different');
      const failed = fixture([fixtureItem(key, null, { category: 'security', status: 'denied' })]);
      expect(find(compare(current, failed, rules), key).state).toBe('status');
    }
    expect(JSON.stringify([legacy, current, ...variants])).toBe(before);
  });
  it('基础振动单元兼容旧JSON，保留编号顺序、false、零时长、缺项与独立失败', () => {
    const key = 'hardware/vibrator', device = { id: 0, hasVibrator: true, hasAmplitudeControl: true, amplitudeControlStatus: 'ok', effectSupportCodes: [1, 1, 1, 1] };
    const report = (primitiveCapabilities?: Json) => fixture([fixtureItem(key, { vibrators: [{ ...device, ...(primitiveCapabilities === undefined ? {} : { primitiveCapabilities }) }] }, { category: 'hardware' })]);
    const capabilities = { primitiveIds: [1, 4], support: { status: 'ok', values: [true, false] }, durationsMs: { status: 'ok', values: [12, 0] } };
    const legacy = report(), current = report(capabilities);
    const variants = [report({ ...capabilities, support: { status: 'ok', values: [true, true] } }), report({ ...capabilities, durationsMs: { status: 'ok', values: [12, -1] } }), report({ ...capabilities, durationsMs: { status: 'unsupported', values: null } }), report({ ...capabilities, durationsMs: { status: 'error', values: null, errorClass: 'synthetic' } }), report({ ...capabilities, primitiveIds: [4, 1] }), report({ ...capabilities, primitiveIds: [1, 999], future: false }), report(null)];
    const before = JSON.stringify([legacy, current, ...variants]);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(legacy, current, rules), key).changedPaths).toEqual(['/value/vibrators/0/primitiveCapabilities']);
      expect(find(compare(current, current, rules), key).state).toBe('same');
      for (const next of variants) expect(find(compare(current, next, rules), key).state).toBe('different');
      expect(find(compare(variants[2], variants[3], rules), key).state).toBe('different');
    }
    expect(JSON.stringify([legacy, current, ...variants])).toBe(before);
  });
  it('振幅能力兼容旧JSON，区分未知/null/false/缺项及查询失败且不改写原始数据', () => {
    const key = 'hardware/vibrator', device = { id: 0, hasVibrator: true, hasAmplitudeControl: false };
    const report = (fields: Record<string, Json>, summary: Record<string, Json> = {}) => fixture([fixtureItem(key, { ...summary, vibrators: [{ id: 0, hasVibrator: true, ...fields }] }, { category: 'hardware' })]);
    const legacy = report(device), current = report({ ...device, amplitudeControlStatus: 'ok' });
    const variants = [legacy, current, report({ hasAmplitudeControl: null, amplitudeControlStatus: 'unsupported' }), report({ hasAmplitudeControl: null, amplitudeControlStatus: 'error', amplitudeControlErrorClass: 'synthetic' }), report({}), report({ ...device, amplitudeControlStatus: 'future' }), report({ ...device, amplitudeControlStatus: 'ok' }, { hasAmplitudeControl: null }), report({ ...device, amplitudeControlStatus: 'ok' }, { hasAmplitudeControl: false })];
    const before = JSON.stringify(variants);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(legacy, current, rules), key).changedPaths).toEqual(['/value/vibrators/0/amplitudeControlStatus']);
      for (let i = 0; i < variants.length; i++) {
        expect(find(compare(variants[i], variants[i], rules), key).state).toBe('same');
        for (const next of variants.slice(i + 1)) expect(find(compare(variants[i], next, rules), key).state).toBe('different');
      }
    }
    expect(JSON.stringify(variants)).toBe(before);
  });
  it('振动器频率兼容旧数据，区分NaN未知/零/失败及新状态而不改变原效果码', () => {
    const device = { id: 0, hasVibrator: true, hasAmplitudeControl: true, effectSupportCodes: [1, 1, 1, 2] };
    const report = (frequency?: Json) => fixture([fixtureItem('hardware/vibrator', { vibrators: [{ ...device, ...(frequency === undefined ? {} : { frequencyCharacteristics: { resonantFrequencyHz: frequency } }) }] }, { category: 'hardware' })]);
    const legacy = report(), unknown = report({ status: 'unknown', value: { $type: 'float', value: 'NaN' } });
    const variants = [report({ status: 'ok', value: 0 }), report({ status: 'unsupported', value: null }), report({ status: 'error', value: null, errorClass: 'synthetic' }), report({ status: 'non-finite', value: { $type: 'float', value: 'Infinity' } }), report({ status: 'future', value: false })];
    const before = JSON.stringify([legacy, unknown, ...variants]);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(legacy, unknown, rules), 'hardware/vibrator').changedPaths).toEqual(['/value/vibrators/0/frequencyCharacteristics']);
      for (const next of variants) expect(find(compare(unknown, next, rules), 'hardware/vibrator').state).toBe('different');
      expect(find(compare(variants[1], variants[2], rules), 'hardware/vibrator').state).toBe('different');
    }
    expect(JSON.stringify([legacy, unknown, ...variants])).toBe(before);
  });
  it('显示色彩查询兼容旧JSON并保留成功null、失败、原始类型和未知字段差异', () => {
    const report = (color?: Json) => fixture([fixtureItem('display/0', { displayId: 0, hdr: { types: [2, 3] }, ...(color === undefined ? {} : { colorCapabilities: color }) }, { category: 'display' })]);
    const color = { wideColorGamut: { status: 'ok', value: false }, preferredWideGamutColorSpace: { status: 'ok', value: null } };
    const old = report(), current = report(color);
    const variants = [report({ ...color, wideColorGamut: { status: 'ok', value: 'false' } }), report({ ...color, preferredWideGamutColorSpace: { status: 'unsupported', value: null } }), report({ ...color, preferredWideGamutColorSpace: { status: 'error', value: null, errorClass: 'SyntheticException' } }), report({ ...color, preferredWideGamutColorSpace: { status: 'ok', value: { id: -1, name: 'synthetic', componentRanges: [{ index: 0, min: -128, max: 128 }], future: false } } })];
    const before = JSON.stringify([old, current, ...variants]);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(old, current, rules), 'display/0').changedPaths).toEqual(['/value/colorCapabilities']);
      for (const next of variants) expect(find(compare(current, next, rules), 'display/0').state).toBe('different');
      expect(find(compare(variants[1], variants[2], rules), 'display/0').state).toBe('different');
    }
    expect(JSON.stringify([old, current, ...variants])).toBe(before);
  });
  it('显示产品信息兼容旧JSON，成功null/失败/原始类型与未知字段参与三种比较', () => {
    const report = (product?: Json) => fixture([fixtureItem('display/0', { displayId: 0, ...(product === undefined ? {} : { deviceProductInfo: product }) }, { category: 'display' })]);
    const base = { name: null, manufacturerPnpId: 'SYN', productId: '0007', modelYear: -1, manufactureYear: -1, manufactureWeek: -1, connectionToSinkType: 999 };
    const current = report({ status: 'ok', value: base });
    const variants = [report({ status: 'ok', value: { ...base, productId: 7 } }), report({ status: 'ok', value: { ...base, future: false } }), report({ status: 'ok', value: null }), report({ status: 'unsupported', value: null }), report({ status: 'denied', value: null, errorClass: 'SyntheticException' })];
    const before = JSON.stringify([current, ...variants]);
    for (const rules of [raw, semantic, smart]) {
      expect(find(compare(report(), current, rules), 'display/0').changedPaths).toEqual(['/value/deviceProductInfo']);
      for (const next of variants) expect(find(compare(current, next, rules), 'display/0').state).toBe('different');
      expect(find(compare(variants[2], variants[3], rules), 'display/0').state).toBe('different');
    }
    expect(JSON.stringify([current, ...variants])).toBe(before);
  });
  it('显示模式HDR兼容旧字段并区分未知/空集/失败及截断外变化', () => {
    const mode = { modeId: 1, physicalWidth: 1080, physicalHeight: 2400, refreshRate: 60 };
    const report = (support?: Json) => fixture([fixtureItem('display/0', { supportedModes: [{ ...mode, ...(support === undefined ? {} : { hdrTypeSupport: support }) }], hdr: { types: [2, 3] } }, { category: 'display' })]);
    const old = report(), current = report({ status: 'ok', types: [2, 3], typeListSha256: 'a' });
    const variants = [report({ status: 'ok', types: [] }), report({ status: 'unsupported', types: null }), report({ status: 'error', types: null }), report({ status: 'ok', types: [2, 999] }), report({ status: 'ok', types: [2, 3], typeListSha256: 'b' })];
    const before = JSON.stringify([old, current, ...variants]);
    for (const rules of [raw, semantic, smart]) {
      const added = find(compare(old, current, rules), 'display/0');
      expect(added.changedPaths).toEqual(['/value/supportedModes/0/hdrTypeSupport']);
      for (const next of variants) expect(find(compare(current, next, rules), 'display/0').state).toBe('different');
      expect(find(compare(variants[0], variants[1], rules), 'display/0').state).toBe('different');
      expect(find(compare(variants[1], variants[2], rules), 'display/0').state).toBe('different');
    }
    expect(JSON.stringify([old, current, ...variants])).toBe(before);
  });
  it('Widevine插件级别与请求常量分开，兼容旧JSON且不把失败当L3', () => {
    const key = 'hardware/drm/widevine', old = { supported: true, maxSecurityLevel: 6, deviceUniqueId: { sha256: 'a', length: 32, rawExported: false } };
    const report = (value: Json) => fixture([fixtureItem(key, value, { category: 'hardware' })]);
    const legacy = report(old), current = (securityLevelProperty: Json) => report({ ...old, maxSecurityLevelKind: 'request-sentinel', securityLevelProperty });
    const l1 = current({ property: 'securityLevel', status: 'ok', value: 'L1' }), l3 = current({ property: 'securityLevel', status: 'ok', value: 'L3' });
    const failed = current({ property: 'securityLevel', status: 'error', value: null, errorClass: 'synthetic' }), empty = current({ property: 'securityLevel', status: 'ok', value: '' });
    const before = JSON.stringify([legacy, l1, l3, failed, empty]);
    for (const rules of [raw, semantic, smart]) {
      const added = find(compare(legacy, l1, rules), key);
      expect(added.changedPaths).toHaveLength(2);expect(added.changedPaths).not.toContain('/value/maxSecurityLevel');
      for (const changed of [l3, failed, empty]) expect(find(compare(l1, changed, rules), key).state).toBe('different');
    }
    expect(JSON.stringify([legacy, l1, l3, failed, empty])).toBe(before);
  });
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
