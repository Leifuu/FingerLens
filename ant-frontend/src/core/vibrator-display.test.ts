import { describe, expect, it } from 'vitest';
import { vibratorDisplayRows } from './vibrator-display';
import type { Json } from './types';

const cap = { primitiveIds: [1, 4, 1], support: { status: 'ok', values: [true, false, true] }, durationsMs: { status: 'ok', values: [11, 0, -1] } };
const device = { id: 0, hasVibrator: true, hasAmplitudeControl: true, primitiveCapabilities: cap };
function grouped(left: Json | undefined, right?: Json) {
  const before = JSON.stringify([left, right]);
  const rows = vibratorDisplayRows(left, right);
  expect(JSON.stringify([left, right])).toBe(before);
  return rows;
}
describe('基础振动单元概览', () => {
  it('按原始序号保留重复振动器和单元，精确显示false/零/负时长', () => {
    const value = { vibrators: [device, device] }, rows = grouped(value, value)!;
    expect(rows.map(row => [row.key, row.state])).toEqual([['0', 'same'], ['1', 'same']]);
    expect(rows[0].left!.primitives).toEqual([{ name: 'CLICK (1)', support: '支持（true）', duration: '11 ms' }, { name: 'QUICK_RISE (4)', support: '不支持（false）', duration: '0 ms' }, { name: 'CLICK (1)', support: '支持（true）', duration: '-1 ms' }]);
  });
  it('旧JSON不补值，区分未采集、单侧、空数组与显式null异构回退', () => {
    const old = { vibrators: [{ id: 0, hasVibrator: true }] }, current = { vibrators: [device] };
    expect(grouped(old, old)).toBeUndefined();
    expect(grouped(old, current)![0]).toMatchObject({ state: 'changed', left: { captured: false, primitives: [] }, right: { captured: true } });
    expect(grouped(undefined, current)![0]).toMatchObject({ state: 'right-only', left: undefined });
    expect(grouped(current)![0].state).toBe('left-only');
    expect(grouped({ vibrators: [] }, { vibrators: [] })).toBeUndefined();
    expect(grouped({ vibrators: [{ id: 0, primitiveCapabilities: { primitiveIds: [], support: { status: 'ok', values: [] }, durationsMs: { status: 'ok', values: [] } } }] })![0].left!.primitives).toEqual([]);
    expect(grouped({ vibrators: [{ ...device, primitiveCapabilities: null }] }, current)).toBeUndefined();
  });
  it('不合并原始编号，未知编号/查询状态和失败原文仍可核对', () => {
    const value = { vibrators: [{ ...device, primitiveCapabilities: { primitiveIds: [999, 0, 999], support: { status: 'unsupported', values: null }, durationsMs: { status: 'constructor', values: null, errorClass: 'synthetic.exception' } } }] };
    const facts = grouped(value, value)![0].left!;
    expect(facts.primitives.map(row => row.name)).toEqual(['未知编号 999', '未知编号 0', '未知编号 999']);
    expect(facts.supportStatus).toBe('系统不支持查询 (unsupported) · 原始值 null');
    expect(facts.durationStatus).toBe('"constructor" · "synthetic.exception" · 原始值 null');
    expect(facts.primitives[0]).toMatchObject({ support: 'null', duration: 'null' });
  });
  it('完整对象和类型差异决定整行状态，忽略对象键书写顺序', () => {
    const value = { vibrators: [device] };
    expect(grouped(value, { vibrators: [{ primitiveCapabilities: cap, hasAmplitudeControl: true, id: 0, hasVibrator: true }] })![0].state).toBe('same');
    for (const changed of [{ ...device, future: false }, { ...device, id: '0' }, { ...device, primitiveCapabilities: { ...cap, future: false } }, { ...device, primitiveCapabilities: { ...cap, primitiveIds: ['1', 4, 1] } }, { ...device, primitiveCapabilities: { ...cap, support: { status: 'ok', values: ['true', false, true] } } }]) {
      expect(grouped(value, { vibrators: [changed] })![0].state).toBe('changed');
    }
    const facts = grouped({ vibrators: [{ ...device, primitiveCapabilities: { ...cap, primitiveIds: ['1', 4, 1], durationsMs: { status: '', values: ['11', 0, null] } } }] })![0].left!;
    expect(facts.primitives[0]).toMatchObject({ name: '未知编号 "1"', duration: '"11"' });expect(facts.durationStatus).toBe('（空字符串）');
  });
  it('超出概览上限、缺失查询、错类型与长度不匹配回退完整字段', () => {
    const invalid: Json[] = [null, {}, { vibrators: [null] }, { vibrators: [{ ...device, id: {} }] }, { vibrators: Array(33).fill(device) }, { vibrators: [{ ...device, primitiveCapabilities: { ...cap, primitiveIds: Array(65).fill(1) } }] }, { vibrators: [{ ...device, primitiveCapabilities: { ...cap, support: { status: 'ok', values: [true] } } }] }, { vibrators: [{ ...device, primitiveCapabilities: { ...cap, durationsMs: {} } }] }, { vibrators: [{ ...device, primitiveCapabilities: { ...cap, primitiveIds: [{ id: 1 }] } }] }];
    for (const value of invalid) expect(grouped(value, { vibrators: [device] })).toBeUndefined();
    expect(grouped({ vibrators: Array(32).fill(device) })!).toHaveLength(32);
    const maximum = { ...cap, primitiveIds: Array(64).fill(1), support: { status: 'ok', values: Array(64).fill(false) }, durationsMs: { status: 'ok', values: Array(64).fill(0) } };
    expect(grouped({ vibrators: [{ ...device, primitiveCapabilities: maximum }] })![0].left!.primitives).toHaveLength(64);
  });
});
