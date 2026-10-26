import { describe, expect, it } from 'vitest';
import { displayColorFacts, displayColorRows } from './display-color-display';
import { buildSections } from '../display';
import { fixtureItem } from './fixtures';
import { mergeDisplaySections } from './display-compare';
import type { Json } from './types';

const space = { name: 'Display P3', id: 7, model: 'RGB', componentCount: 3, wideGamut: true, srgb: false, componentRanges: [{ index: 0, min: 0, max: 1 }, { index: 1, min: 0, max: 1 }, { index: 2, min: 0, max: 1 }] };
const query = (value: Json) => ({ status: 'ok', value });
const display = (colorCapabilities: Json): Json => ({ displayId: 0, colorCapabilities });
const color = { wideColorGamut: query(true), preferredWideGamutColorSpace: query(space) };
const facts = (key: 'wideColorGamut' | 'preferredWideGamutColorSpace', value: Json | undefined) => displayColorFacts(key, value).map(fact => [fact.label, fact.value]);

describe('显示色彩摘要', () => {
  it('旧JSON缺失保持未采集，两项查询独立配对且不改写输入', () => {
    const current = display(color), old = { displayId: 0 }, before = JSON.stringify([old, current]);
    expect(displayColorRows(old, old)).toEqual([]);
    const rows = displayColorRows(old, current)!;expect(rows.map(row => [row.key, row.state])).toEqual([['wideColorGamut', 'right-only'], ['preferredWideGamutColorSpace', 'right-only']]);
    expect(displayColorFacts(rows[0].key, rows[0].left)[0].value).toBe('未采集');
    expect(displayColorRows(current, undefined)!.every(row => row.state === 'left-only')).toBe(true);
    expect(displayColorRows(current, current)!.every(row => row.state === 'same')).toBe(true);
    expect(JSON.stringify([old, current])).toBe(before);
  });
  it('成功false/null、显式null、失败和未知状态不互相补推，原始类型有区别', () => {
    expect(facts('wideColorGamut', query(false))).toContainEqual(['宽色域', '否（false）']);
    expect(facts('wideColorGamut', query('false'))).toContainEqual(['宽色域', '"false"']);
    expect(facts('preferredWideGamutColorSpace', query(null))).toContainEqual(['偏好空间', 'null（未返回偏好空间）']);
    expect(facts('preferredWideGamutColorSpace', null)).toEqual([['原始值', 'null']]);
    for (const status of ['unsupported', 'denied', 'error', '__proto__']) {
      const result = facts('preferredWideGamutColorSpace', { status, value: null, errorClass: 'SyntheticException' });
      expect(result[0][1]).toContain(status);expect(result).toContainEqual(['偏好空间', 'null']);expect(result).toContainEqual(['错误', '"SyntheticException"']);
    }
    expect(facts('wideColorGamut', { status: false, value: '' })).toContainEqual(['查询', '状态 false']);expect(facts('wideColorGamut', { status: false, value: '' })).toContainEqual(['宽色域', '（空字符串）']);
    expect(facts('preferredWideGamutColorSpace', query([]))).toContainEqual(['偏好空间', '其他结构（详见完整字段）']);
  });
  it('原始ID/模型/声明分量数与负范围不推导，重复index和特殊浮点按原序保留', () => {
    const custom = { ...space, name: '', id: -1, model: 'FUTURE', componentCount: 99, componentRanges: [{ index: 9, min: -128, max: 128 }, { index: 9, min: { $type: 'float', value: 'NaN' }, max: 100 }] };
    const result = displayColorFacts('preferredWideGamutColorSpace', query(custom));
    expect(result.find(row => row.key === 'name')?.value).toBe('（空字符串）');expect(result.find(row => row.key === 'identity')?.value).toBe('模型 "FUTURE" · ID -1 · 分量 99');
    expect(result.find(row => row.key === 'ranges')?.value).toBe('[0] index 9 · -128 – 128\n[1] index 9 · "NaN"（float） – 100');
    for (const [componentRanges, expected] of [[[], '空数组 []'], [null, 'null']] as const) expect(displayColorFacts('preferredWideGamutColorSpace', query({ ...space, componentRanges: componentRanges as Json })).find(row => row.key === 'ranges')?.value).toBe(expected);
    const missing = { ...space } as Record<string, Json>;delete missing.componentRanges;
    expect(displayColorFacts('preferredWideGamutColorSpace', query(missing)).find(row => row.key === 'ranges')?.value).toBe('未采集');
  });
  it('未知查询/空间字段、顶层元数据与布尔字符串类型变化都进入摘要差异', () => {
    const current = display(color);
    for (const changed of [{ ...color, preferredWideGamutColorSpace: { ...query(space), future: false } }, { ...color, preferredWideGamutColorSpace: query({ ...space, future: false }) }, { ...color, preferredWideGamutColorSpace: query({ ...space, componentRanges: [...space.componentRanges].reverse() }) }, { ...color, wideColorGamut: query('true') }]) expect(displayColorRows(current, display(changed))!.some(row => row.state === 'changed')).toBe(true);
    const rows = displayColorRows(display({ ...color, future: false }), display({ ...color, future: 'false' }))!;
    expect(rows.find(row => row.key === 'other')?.state).toBe('changed');expect(rows.filter(row => row.key !== 'other').every(row => row.state === 'same')).toBe(true);
    expect(displayColorFacts('other', rows.find(row => row.key === 'other')!.left)[0].value).toBe('1 项 · 见完整字段');
    expect(displayColorRows(display(color), display({ ...color, preferredWideGamutColorSpace: query({ ...space, id: '7' }) }))![1].state).toBe('changed');
  });
  it('最多4项范围进入摘要，异构或超限结构保留原字段，复杂类型不强制转换', () => {
    const four = { ...space, componentRanges: Array.from({ length: 4 }, () => space.componentRanges[0]) };
    expect(displayColorRows(display({ ...color, preferredWideGamutColorSpace: query(four) }), undefined)).toHaveLength(2);
    for (const invalid of [null, [], 'legacy']) expect(displayColorRows(display(invalid), display(color))).toBeUndefined();
    for (const invalid of [{ ...space, componentRanges: [...four.componentRanges, four.componentRanges[0]] }, { ...space, componentRanges: [null] }, { ...space, name: {} }, { ...space, componentRanges: 'legacy' }, { ...space, id: { $type: { toString: false }, value: '7' } }]) expect(displayColorRows(display({ ...color, preferredWideGamutColorSpace: query(invalid) }), undefined)).toBeUndefined();
    expect(displayColorRows(null, display(color))).toBeUndefined();
  });
  it('独立色彩分组不重复字段，旧模式/HDR与其它未知顶层分组不变', () => {
    const old = { displayId: 0, hdr: { types: [2, 3] }, supportedModes: [{ modeId: 1 }], futureDisplay: false };
    const before = buildSections(fixtureItem('display/0', old)), after = buildSections(fixtureItem('display/0', { ...old, colorCapabilities: color }));
    for (const section of before) expect(after.find(group => group.key === section.key)).toEqual(section);
    const group = after.find(section => section.key === 'color')!;expect(group.title).toBe('色彩能力');expect(group.table).toBe(true);expect(group.rows).toHaveLength(18);
    expect(after.filter(section => section.key !== 'color').flatMap(section => section.rows).some(row => row.key.startsWith('colorCapabilities'))).toBe(false);
    expect(buildSections(fixtureItem('display/0', { ...old, colorCapabilities: null })).find(section => section.key === 'color')!.rows[0].value).toBe('null');
  });
  it('完整字段的类型差异与摘要一致，未知字段及回退结构也可筛出原始类型变化', () => {
    const group = (value: Json) => buildSections(fixtureItem('display/0', display(value))).find(section => section.key === 'color');
    for (const [a, b] of [[false, 'false'], [7, '7'], [null, 'null'], [[], '[]'], [{}, '{}'], [{ $type: 'int64', value: '7' }, 7], [{ $type: 'float', value: 'NaN' }, { $type: 'float', value: false }]] as [Json, Json][]) {
      const left = group({ wideColorGamut: query(a), future: a }), right = group({ wideColorGamut: query(b), future: b });
      expect(mergeDisplaySections(left, right).rows.filter(row => row.state !== 'same')).not.toHaveLength(0);
      expect(mergeDisplaySections(left, left).rows.every(row => row.state === 'same')).toBe(true);
    }
    const merged = mergeDisplaySections(group({ wideColorGamut: query(false) }), group({ wideColorGamut: query('false') }));
    expect(merged.rows.filter(row => row.state === 'changed').map(row => [row.label, row.left, row.right])).toEqual([['colorCapabilities.wideColorGamut.value', 'false', '"false"']]);
    expect(group({ futureStatus: 'denied', futureBytes: '1024' })!.rows.map(row => row.value)).toEqual(['"denied"', '"1024"']);
  });
});
