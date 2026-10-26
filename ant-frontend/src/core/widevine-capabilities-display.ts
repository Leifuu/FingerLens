import { differences } from './compare';
import type { MergedDisplayRow } from './display-compare';
import type { Json } from './types';

const object = (value: Json | undefined): value is Record<string, Json> => value !== null && typeof value === 'object' && !Array.isArray(value);
const scalar = (value: Json | undefined) => value === null || typeof value !== 'object';
const text = (value: Json | undefined): string => value === undefined ? '未采集' : value === '' ? '（空字符串）' : JSON.stringify(value);
const levelNames = new Map([[1, 'SW_SECURE_CRYPTO'], [2, 'SW_SECURE_DECODE'], [3, 'HW_SECURE_CRYPTO'], [4, 'HW_SECURE_DECODE'], [5, 'HW_SECURE_ALL']]);
const statuses = new Map([['ok', '查询成功'], ['unsupported', '系统不支持查询'], ['denied', '读取受限'], ['error', '读取异常']]);

export interface WidevineCapabilityRow {
  key: string;
  label: string;
  left?: Json;
  right?: Json;
  state: MergedDisplayRow['state'];
}

// 业务块：固定采集最多20查询，概览保留原序/重复，完整对象决定差异；复杂或超限旧结构回退原字段。
export function widevineCapabilityRows(left?: Json, right?: Json): WidevineCapabilityRow[] | undefined {
  for (const value of [left, right]) {
    if (value === undefined) continue;
    if (!object(value) || !['schemeUuid', 'scope', 'queryCount', 'queryLimit', 'status', 'errorClass'].every(field => scalar(value[field]))) return undefined;
    if (value.entries === undefined || value.entries === null) continue;
    if (!Array.isArray(value.entries) || value.entries.length > 20 || value.entries.some(entry => !object(entry) || !['mimeType', 'securityLevel', 'status', 'supported', 'errorClass'].every(field => scalar(entry[field])))) return undefined;
  }
  const metadata = (value?: Json): Json | undefined => !object(value) ? undefined : Object.fromEntries(Object.entries(value).map(([key, child]) => [key, key === 'entries' && Array.isArray(child) ? child.length : child]));
  const pair = (key: string, label: string, a?: Json, b?: Json): WidevineCapabilityRow => ({ key, label, left: a, right: b, state: a === undefined ? 'right-only' : b === undefined ? 'left-only' : differences(a, b).length ? 'changed' : 'same' });
  const rows: WidevineCapabilityRow[] = [];
  if (left !== undefined || right !== undefined) rows.push(pair('metadata', '查询与附加字段', metadata(left), metadata(right)));
  const a = object(left) && Array.isArray(left.entries) ? left.entries : [], b = object(right) && Array.isArray(right.entries) ? right.entries : [];
  for (let index = 0; index < Math.max(a.length, b.length); index++) rows.push(pair(String(index), `查询 [${index}]`, a[index], b[index]));
  return rows;
}

export function widevineCapabilityFacts(value?: Json, metadata = false): string[] {
  if (value === undefined) return [metadata ? '未采集' : '此侧没有该查询'];
  if (!object(value)) return [`原始值 ${text(value)}`];
  if (metadata) {
    const entries = value.entries === undefined ? '查询明细未采集' : value.entries === null ? '查询明细 null' : value.entries === 0 ? '查询明细为空 []' : `返回明细 ${text(value.entries)} 项`;
    const facts = [`方案 UUID ${text(value.schemeUuid)}`, `查询范围 ${text(value.scope)}`, `实际尝试 ${text(value.queryCount)} · 上限 ${text(value.queryLimit)}`, entries];
    if (value.status !== undefined) facts.push(`查询状态 ${text(value.status)}`);
    if (value.errorClass !== undefined) facts.push(`错误 ${text(value.errorClass)}`);
    const extra = Object.keys(value).filter(key => !['schemeUuid', 'scope', 'queryCount', 'queryLimit', 'entries', 'status', 'errorClass'].includes(key)).length;
    if (extra) facts.push(`附加字段 ${extra} 项 · 见完整字段`);
    return facts;
  }
  const name = typeof value.securityLevel === 'number' ? levelNames.get(value.securityLevel) : undefined;
  const status = typeof value.status === 'string' ? statuses.get(value.status) : undefined;
  const support = value.status === 'ok' && typeof value.supported === 'boolean' ? value.supported ? '支持（true）' : '不支持（false）' : `返回值 ${text(value.supported)}`;
  const facts = [`媒体容器 ${text(value.mimeType)}`, `请求级别 ${text(value.securityLevel)}${name ? ` · ${name}` : ''}`, support, status ? `${status}（${value.status}）` : `查询状态 ${text(value.status)}`];
  if (value.errorClass !== undefined) facts.push(`错误 ${text(value.errorClass)}`);
  const extra = Object.keys(value).filter(key => !['mimeType', 'securityLevel', 'status', 'supported', 'errorClass'].includes(key)).length;
  if (extra) facts.push(`附加字段 ${extra} 项 · 见完整字段`);
  return facts;
}
