import { differences } from './compare';
import type { Json } from './types';
import type { MergedDisplayRow } from './display-compare';

const object = (value: Json | undefined): value is Record<string, Json> => value !== null && typeof value === 'object' && !Array.isArray(value);
const atom = (value: Json | undefined) => value === null || typeof value !== 'object' || object(value) && (value.$type === 'float' || value.$type === 'int64') && typeof value.value === 'string';
const text = (value: Json | undefined): string => value === undefined ? '未采集' : value === '' ? '（空字符串）' : value !== null && typeof value === 'object' ? object(value) && atom(value) ? `${JSON.stringify(value.value)}（${value.$type}）` : '其他结构（详见完整字段）' : JSON.stringify(value);
const flag = (value: Json | undefined) => value === true ? '是（true）' : value === false ? '否（false）' : text(value);
const statuses = new Map([['ok', '成功'], ['unsupported', '系统不支持查询'], ['denied', '读取受限'], ['error', '读取异常']]);
const keys = ['wideColorGamut', 'preferredWideGamutColorSpace'] as const;

export interface DisplayColorRow {
  key: 'wideColorGamut' | 'preferredWideGamutColorSpace' | 'other';
  label: string;
  left?: Json;
  right?: Json;
  state: MergedDisplayRow['state'];
}

// 业务块：只概览两项独立查询和其它元数据；空间最多4分量，异构/超限结构回退完整字段。
export function displayColorRows(left: Json | undefined, right: Json | undefined): DisplayColorRow[] | undefined {
  if ([left, right].some(value => value !== undefined && !object(value))) return undefined;
  const a = object(left) ? left.colorCapabilities : undefined, b = object(right) ? right.colorCapabilities : undefined;
  if ([a, b].some(value => value !== undefined && !object(value))) return undefined;
  const valid = (color: Json | undefined) => {
    const query = object(color) ? color.preferredWideGamutColorSpace : undefined, value = object(query) ? query.value : undefined;
    if (!object(value)) return true;
    if (!['name', 'id', 'model', 'componentCount', 'wideGamut', 'srgb'].every(key => atom(value[key]))) return false;
    const ranges = value.componentRanges;
    return ranges === undefined || ranges === null || Array.isArray(ranges) && ranges.length <= 4 && ranges.every(range => object(range) && ['index', 'min', 'max'].every(key => atom(range[key])));
  };
  if (!valid(a) || !valid(b)) return undefined;
  const extra = (value: Json | undefined) => {
    if (!object(value)) return undefined;
    const other = Object.fromEntries(Object.entries(value).filter(([key]) => !(keys as readonly string[]).includes(key)));
    return Object.keys(other).length ? other : undefined;
  };
  const rows: DisplayColorRow[] = [
    { key: 'wideColorGamut', label: '宽色域内容', left: object(a) ? a.wideColorGamut : undefined, right: object(b) ? b.wideColorGamut : undefined, state: 'same' },
    { key: 'preferredWideGamutColorSpace', label: '偏好宽色域空间', left: object(a) ? a.preferredWideGamutColorSpace : undefined, right: object(b) ? b.preferredWideGamutColorSpace : undefined, state: 'same' },
    { key: 'other', label: '其它色彩元数据', left: extra(a), right: extra(b), state: 'same' },
  ];
  return rows.filter(row => row.left !== undefined || row.right !== undefined).map(row => ({ ...row, state: row.left === undefined ? 'right-only' : row.right === undefined ? 'left-only' : differences(row.left, row.right).length ? 'changed' : 'same' }));
}

// 业务块：名称/ID/模型不推导含义；成功null、失败、原始类型和原序分量范围分别呈现。
export function displayColorFacts(key: DisplayColorRow['key'], query: Json | undefined): { key: string; label: string; value: string }[] {
  if (query === undefined) return [{ key: 'missing', label: '采集', value: '未采集' }];
  if (key === 'other') return [{ key: 'other', label: '其它字段', value: `${object(query) ? Object.keys(query).length : 0} 项 · 见完整字段` }];
  if (!object(query)) return [{ key: 'raw', label: '原始值', value: text(query) }];
  const label = typeof query.status === 'string' ? statuses.get(query.status) : undefined;
  const facts = [{ key: 'status', label: '查询', value: label ? `${label}（${query.status}）` : `状态 ${text(query.status)}` }];
  const value = query.value;
  if (key === 'wideColorGamut') facts.push({ key: 'value', label: '宽色域', value: flag(value) });
  else if (!object(value)) facts.push({ key: 'value', label: '偏好空间', value: query.status === 'ok' && value === null ? 'null（未返回偏好空间）' : text(value) });
  else {
    facts.push({ key: 'name', label: '名称', value: text(value.name) },
      { key: 'identity', label: '空间参数', value: `模型 ${text(value.model)} · ID ${text(value.id)} · 分量 ${text(value.componentCount)}` },
      { key: 'flags', label: '空间标记', value: `宽色域 ${flag(value.wideGamut)} · sRGB ${flag(value.srgb)}` });
    const ranges = value.componentRanges;
    const rangeText = Array.isArray(ranges) ? ranges.length ? ranges.map((range, index) => object(range) ? `[${index}] index ${text(range.index)} · ${text(range.min)} – ${text(range.max)}` : text(range)).join('\n') : '空数组 []' : text(ranges);
    facts.push({ key: 'ranges', label: '范围（原序）', value: rangeText });
  }
  if (query.errorClass !== undefined) facts.push({ key: 'error', label: '错误', value: text(query.errorClass) });
  return facts;
}
