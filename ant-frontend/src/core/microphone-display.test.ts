import { describe, expect, it } from 'vitest';
import { microphoneDisplayRows, microphonePointRows } from './microphone-display';
import type { Json } from './types';

const reading = { raw: -3.4028235e38, kind: 'unknown-sentinel' };
const point = { frequencyHz: 1000, responseDb: 0 };
const mic = { id: 0, type: 15, location: 1, directionality: 0, group: -1, indexInGroup: -1,
  calibration: { status: 'ok', value: { sensitivityDbfs: reading, maxSplDb: { raw: 0, kind: 'finite' }, minSplDb: { raw: { $type: 'float', value: 'NaN' }, kind: 'non-finite' } } },
  positionMeters: { status: 'ok', value: { x: 0, y: 0, z: 0, kind: 'finite' } }, orientation: { status: 'ok', value: { x: 0, y: 0, z: 0, kind: 'unknown-sentinel' } },
  frequencyResponse: { status: 'ok', value: { pointCount: 2, returnedCount: 2, truncated: false, pointsSha256: 'a', points: [point, point] } },
};
function grouped(left: Json | undefined, right?: Json) {
  const before = JSON.stringify([left, right]);
  const rows = microphoneDisplayRows(left, right);
  if (rows) rows.forEach(row => microphonePointRows(row.left, row.right));
  expect(JSON.stringify([left, right])).toBe(before);
  return rows;
}

describe('麦克风声学与频响概览', () => {
  it('按原始序号保留重复条目/频率，明确显示哨兵、零、分组-1和非有限类型', () => {
    const value = { microphones: [mic, mic] }, rows = grouped(value, value)!;
    expect(rows.map(row => [row.key, row.state])).toEqual([['0', 'same'], ['1', 'same']]);
    expect(rows[0].left!.identity).toBe('ID 0 · 类型 15');expect(rows[0].left!.grouping).toContain('分组 -1 · 组内序号 -1');
    expect(rows[0].left!.calibration).toContain('-3.4028235e+38 dBFS · 未知哨兵（unknown-sentinel）');
    expect(rows[0].left!.calibration).toContain('最大 SPL 0 dB · 有限数（finite）');expect(rows[0].left!.calibration).toContain('"NaN"（float） dB · 非有限数（non-finite）');
    expect(rows[0].left!.position).toContain('x 0 · y 0 · z 0 · 有限数（finite）');expect(rows[0].left!.orientation).toContain('未知哨兵');
    const points = microphonePointRows(rows[0].left, rows[0].right);
    expect(points.map(row => [row.key, row.state])).toEqual([['0', 'same'], ['1', 'same']]);expect(points[0].left).toEqual(['频率 1000 Hz', '响应 0 dB']);
  });
  it('保留缺失/null/失败/未知状态，不从值或旧摘要推断kind或补数据', () => {
    const legacy = { id: 0, type: 15 }, rows = grouped({ microphones: [legacy] }, { microphones: [mic] })!;
    expect(rows[0].left).toMatchObject({ calibration: '未采集', position: '未采集', orientation: '未采集', response: '未采集', points: undefined });
    const value = { ...mic, calibration: { status: 'denied', value: null, errorClass: 'synthetic-error' }, positionMeters: null, orientation: { status: 'ok', value: { x: 0, y: 0, z: 0 } }, frequencyResponse: { status: 'constructor', value: null } };
    const facts = grouped({ microphones: [value] })![0].left!;
    expect(facts.calibration).toBe('读取受限（denied） · 原始值 null · "synthetic-error"');expect(facts.position).toBe('原始值 null');expect(facts.orientation).toContain('含义 未采集');expect(facts.orientation).not.toContain('未知哨兵');expect(facts.response).toBe('读取状态 "constructor" · 原始值 null');
    expect(grouped({ count: 1 }, { microphones: [mic] })).toBeUndefined();
    expect(grouped(undefined, { microphones: [mic] })![0]).toMatchObject({ left: undefined, state: 'right-only' });expect(grouped({ microphones: [mic] })![0].state).toBe('left-only');
  });
  it('成功空数组区别于points缺失/null；保留截断计数与完整哈希变化', () => {
    const empty = { ...mic, frequencyResponse: { status: 'ok', value: { pointCount: 0, returnedCount: 0, truncated: false, points: [] } } };
    const row = grouped({ microphones: [empty] })![0];expect(row.left!.response).toContain('空数组 []');expect(microphonePointRows(row.left)).toEqual([]);
    for (const [value, expected] of [[{}, 'points未采集'], [{ points: null }, 'points=null']] as const) expect(grouped({ microphones: [{ ...mic, frequencyResponse: { status: 'ok', value } }] })![0].left!.response).toContain(expected);
    const max = { ...mic, frequencyResponse: { status: 'ok', value: { pointCount: 200, returnedCount: 128, truncated: true, pointsSha256: 'a', points: Array(128).fill(point) } } };
    const other = { ...max, frequencyResponse: { ...max.frequencyResponse, value: { ...max.frequencyResponse.value, pointsSha256: 'b' } } };
    expect(grouped({ microphones: [max] }, { microphones: [other] })![0].state).toBe('changed');
    expect(grouped({ microphones: [max] })![0].left!.response).toContain('总数 200 · 返回 128 · 截断 true');
  });
  it('完整原始对象、未知字段、原始类型和频响点的单侧/顺序差异均保留', () => {
    for (const changed of [{ ...mic, id: '0' }, { ...mic, future: false }, { ...mic, calibration: { ...mic.calibration, future: false } }]) expect(grouped({ microphones: [mic] }, { microphones: [changed] })![0].state).toBe('changed');
    const other = { ...mic, frequencyResponse: { ...mic.frequencyResponse, value: { ...mic.frequencyResponse.value, points: [{ ...point, future: false }, { frequencyHz: '1000', responseDb: { $type: 'float', value: 'Infinity' } }, point] } } };
    const row = grouped({ microphones: [mic] }, { microphones: [other] })![0], points = microphonePointRows(row.left, row.right);
    expect(points.map(row => row.state)).toEqual(['changed', 'changed', 'right-only']);expect(points[1].right).toEqual(['频率 "1000" Hz', '响应 "Infinity"（float） dB']);
  });
  it('不兼容或过大结构回退完整字段，合法上限及空数组保留', () => {
    const invalid: Json[] = [null, [], {}, { microphones: null }, { microphones: [null] }, { microphones: [{}] }, { microphones: [{ ...mic, id: {} }] }, { microphones: Array(257).fill(mic) }, { microphones: [{ ...mic, frequencyResponse: { status: 'ok', value: { points: Array(129).fill(point) } } }] }, { microphones: [{ ...mic, frequencyResponse: { status: 'ok', value: { points: [null] } } }] }, { microphones: [{ ...mic, frequencyResponse: { status: 'ok', value: { points: [{ frequencyHz: {} }] } } }] }];
    for (const value of invalid) expect(grouped(value, { microphones: [mic] })).toBeUndefined();
    expect(grouped({ microphones: Array(256).fill(mic) })).toHaveLength(256);expect(grouped({ microphones: [] }, { microphones: [] })).toEqual([]);
  });
});
