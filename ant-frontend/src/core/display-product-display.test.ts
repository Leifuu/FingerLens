import { describe, expect, it } from 'vitest';
import { displayProductFacts, displayProductRows } from './display-product-display';
import type { Json } from './types';

const product = { name: 'Common Panel', manufacturerPnpId: 'GGL', productId: '0007', modelYear: -1, manufactureYear: 1990, manufactureWeek: 1, connectionToSinkType: 1 };
const query = (value: Json): Json => ({ status: 'ok', value });
const display = (deviceProductInfo: Json): Json => ({ displayId: 0, deviceProductInfo });
const facts = (value: Json | undefined) => displayProductFacts(value).map(fact => [fact.label, fact.value]);

describe('显示产品摘要', () => {
  it('旧JSON缺失与单侧缺项保持未采集，原始对象决定差异且不改写输入', () => {
    const old = { displayId: 0 }, current = display(query(product)), before = JSON.stringify([old, current]);
    expect(displayProductRows(old, old)).toEqual([]);
    expect(displayProductRows(old, current)![0].state).toBe('right-only');expect(displayProductRows(current, undefined)![0].state).toBe('left-only');
    expect(displayProductRows(current, current)![0].state).toBe('same');expect(facts(undefined)).toEqual([['采集', '未采集']]);
    expect(JSON.stringify([old, current])).toBe(before);
  });
  it('成功null/显式null/失败和未知状态分开，原始类型不补推', () => {
    expect(facts(query(null))).toContainEqual(['产品信息', 'null（平台未提供产品信息）']);expect(facts(null)).toEqual([['原始值', 'null']]);
    for (const status of ['unsupported', 'denied', 'error', '__proto__']) {
      const result = facts({ status, value: null, errorClass: 'SyntheticException' });expect(result[0][1]).toContain(status);expect(result).toContainEqual(['产品信息', 'null']);expect(result).toContainEqual(['错误', '"SyntheticException"']);
    }
    expect(facts({ value: false })).toContainEqual(['产品信息', 'false']);expect(facts({ value: '' })).toContainEqual(['产品信息', '（空字符串）']);
    expect(facts({ status: false, value: 0 })).toContainEqual(['查询', '状态 false']);
  });
  it('产品ID前导零/空名保留，仅数值-1和已知连接码附含义，不推导实际日期', () => {
    const result = facts(query(product));expect(result).toContainEqual(['产品 ID', '"0007"']);expect(result).toContainEqual(['型号年份', '-1（平台未提供）']);expect(result).toContainEqual(['制造年 / 周', '1990 / 1']);expect(result).toContainEqual(['连接类型', '内置（1）']);
    for (const [value, label] of [[0, '未知（0）'], [2, '直接连接（2）'], [3, '经中间设备连接（3）'], [999, '未知类型（999）'], ['1', '"1"']] as [Json, string][]) expect(facts(query({ ...product, connectionToSinkType: value }))).toContainEqual(['连接类型', label]);
    const unusual = facts(query({ ...product, name: '', modelYear: '-1', manufactureYear: -1, manufactureWeek: -1 }));expect(unusual).toContainEqual(['名称', '（空字符串）']);expect(unusual).toContainEqual(['型号年份', '"-1"']);expect(unusual).toContainEqual(['制造年 / 周', '-1（平台未提供） / -1（平台未提供）']);
    expect(facts(query({ productId: 7, modelYear: { $type: 'int64', value: '-1' } }))).toContainEqual(['型号年份', '"-1"（int64）']);
  });
  it('已知文字相同时未知查询/产品字段、原始类型变化仍标差异，键顺序不标差异', () => {
    const baseline = display(query(product));
    const variants: Json[] = [{ status: 'ok', value: { ...product, future: false } }, { status: 'ok', value: product, future: false }, { status: 'ok', value: { ...product, productId: 7 } }, { status: 'ok', value: { ...product, modelYear: '-1' } }];
    for (const next of variants) expect(displayProductRows(baseline, display(next))![0].state).toBe('changed');
    expect(displayProductRows(display({ status: 'ok', value: { ...product, future: false } }), display({ status: 'ok', value: { ...product, future: 'false' } }))![0].state).toBe('changed');
    expect(facts({ status: 'ok', value: { ...product, future: false }, futureQuery: true })).toContainEqual(['附加字段', '2 项（详见完整字段）']);
    expect(displayProductRows(baseline, display({ value: Object.fromEntries(Object.entries(product).reverse()), status: 'ok' }))![0].state).toBe('same');
  });
  it('复杂已知字段/数组回退完整字段，特殊数值保留类型而不强制序列化任意结构', () => {
    for (const value of [[], { status: 'ok', value: [] }, { status: 'ok', value: { ...product, name: {} } }, { status: 'ok', value: { ...product, modelYear: { $type: {}, value: '1' } } }]) expect(displayProductRows(display(value as Json), display(query(product)))).toBeUndefined();
    expect(displayProductRows(null, display(query(product)))).toBeUndefined();
    expect(facts({ status: {}, value: null, errorClass: {} })).toContainEqual(['错误', '其他结构（详见完整字段）']);
    const special = { ...product, modelYear: { $type: 'float', value: 'NaN' } };expect(displayProductRows(display(query(special)), undefined)).toHaveLength(1);expect(facts(query(special))).toContainEqual(['型号年份', '"NaN"（float）']);
  });
});
