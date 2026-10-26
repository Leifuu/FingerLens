import { describe, expect, it } from 'vitest';
import { sensorPrivacyFacts, sensorPrivacyRows } from './sensor-privacy-display';
import { buildSections } from '../display';
import { mergeDisplaySections } from './display-compare';
import { fixtureItem } from './fixtures';
import type { Json } from './types';

const yes = { status: 'ok', value: true }, no = { status: 'ok', value: false };
const current = { managerAvailable: true, microphone: { softwareToggle: yes, hardwareToggle: no }, camera: { softwareToggle: yes, hardwareToggle: no } };

describe('隐私开关能力概览', () => {
  it('固定四项语义顺序，保留false，键顺序无关且原件不变', () => {
    const before = JSON.stringify(current), rows = sensorPrivacyRows(current, current)!;
    expect(rows.map(row => row.key)).toEqual(['metadata', 'microphone.softwareToggle', 'microphone.hardwareToggle', 'camera.softwareToggle', 'camera.hardwareToggle']);
    expect(rows.every(row => row.state === 'same')).toBe(true);
    expect(sensorPrivacyFacts(rows[2].left)).toEqual(['不支持（false）', '查询成功（ok）']);
    expect(sensorPrivacyRows(current, Object.fromEntries(Object.entries(current).reverse()))!.every(row => row.state === 'same')).toBe(true);
    expect(JSON.stringify(current)).toBe(before);
  });
  it('旧缺项与单侧查询保留未采集，显式null/原始值不补造状态', () => {
    expect(sensorPrivacyRows(undefined, undefined)).toEqual([]);expect(sensorPrivacyRows({}, {})).toEqual([]);
    expect(sensorPrivacyRows(undefined, current)!.every(row => row.state === 'right-only')).toBe(true);
    expect(sensorPrivacyRows(current, undefined)!.every(row => row.state === 'left-only')).toBe(true);
    const partial = { camera: { softwareToggle: null } };
    expect(sensorPrivacyRows(partial, {})!).toMatchObject([{ key: 'camera.softwareToggle', left: null, state: 'left-only' }]);
    expect(sensorPrivacyFacts(undefined)).toEqual(['未采集']);expect(sensorPrivacyFacts(null)).toEqual(['原始值 null']);
    expect(sensorPrivacyFacts(false)).toEqual(['原始值 false']);expect(sensorPrivacyFacts('')).toEqual(['原始值 （空字符串）']);
  });
  it('布尔/字符串/null与失败独立，异常值不解释为能力支持', () => {
    expect(sensorPrivacyFacts({ status: 'ok', value: 'false' })).toEqual(['返回值 "false"', '查询成功（ok）']);
    for (const status of ['unsupported', 'unavailable', 'denied', 'error', '__proto__']) {
      const lines = sensorPrivacyFacts({ status, value: null, errorClass: 'SyntheticException' });
      expect(lines[0]).toBe('返回值 null');expect(lines[1]).toContain(status);expect(lines[2]).toBe('错误 "SyntheticException"');
    }
    expect(sensorPrivacyFacts({ status: 'error', value: true })[0]).toBe('返回值 true');
    expect(sensorPrivacyFacts({ status: false, value: 0 })[1]).toBe('查询状态 false');
    for (const value of [null, false, 'false', '']) expect(sensorPrivacyFacts({ managerAvailable: value }, true)[0]).toContain(value === '' ? '（空字符串）' : JSON.stringify(value));
  });
  it('完整查询与未知顶层/传感器元数据决定各行差异，不按已格式化文字吞掉变化', () => {
    const withQueryExtra = { ...current, camera: { ...current.camera, hardwareToggle: { ...no, future: { flag: false } } } };
    expect(sensorPrivacyRows(current, withQueryExtra)!.filter(row => row.state !== 'same').map(row => row.key)).toEqual(['camera.hardwareToggle']);
    expect(sensorPrivacyFacts(withQueryExtra.camera.hardwareToggle)).toContain('附加字段 1 项 · 见完整字段');
    for (const next of [{ ...current, future: { flag: false } }, { ...current, camera: { ...current.camera, future: { flag: false } } }, { ...current, managerAvailable: 'true' }]) {
      expect(sensorPrivacyRows(current, next)!.filter(row => row.state !== 'same').map(row => row.key)).toEqual(['metadata']);
    }
    const typed = { ...current, camera: { ...current.camera, hardwareToggle: { ...no, value: 'false' } } };
    expect(sensorPrivacyRows(current, typed)!.filter(row => row.state !== 'same').map(row => row.key)).toEqual(['camera.hardwareToggle']);
    expect(sensorPrivacyRows({ camera: current.camera, microphone: {} }, { camera: current.camera })!.filter(row => row.state !== 'same').map(row => row.key)).toEqual(['metadata']);
  });
  it('异构和复杂已知字段回退完整字段，未知扩展结构仍保留', () => {
    const invalid: Json[] = [null, [], false, { managerAvailable: {} }, { camera: null }, { camera: [] }, { camera: { softwareToggle: [] } }, { camera: { softwareToggle: { status: {} } } }, { camera: { hardwareToggle: { value: [] } } }, { camera: { hardwareToggle: { errorClass: {} } } }];
    for (const value of invalid) expect(sensorPrivacyRows(current, value)).toBeUndefined();
    expect(sensorPrivacyRows(current, { ...current, future: [] })).toHaveLength(5);
  });
  it('完整字段保留JSON类型，类型变化在逐字段差异筛选中也可见', () => {
    const item = (value: Json) => fixtureItem('security/sensor-privacy-capabilities', value, { category: 'security' });
    const left = buildSections(item(current))[0], right = buildSections(item({ ...current, camera: { ...current.camera, hardwareToggle: { ...no, value: 'false' } } }))[0];
    const changed = mergeDisplaySections(left, right).rows.filter(row => row.state !== 'same');
    expect(changed).toMatchObject([{ label: 'camera.hardwareToggle.value', left: 'false', right: '"false"', state: 'changed' }]);
    expect(left.table).toBe(true);
  });
});
