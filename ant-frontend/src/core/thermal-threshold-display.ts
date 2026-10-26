import { differences } from './compare';
import type { MergedDisplayRow } from './display-compare';
import type { Json } from './types';

const object = (value: Json | undefined): value is Record<string, Json> => value !== null && typeof value === 'object' && !Array.isArray(value);
const scalar = (value: Json | undefined) => value === null || typeof value !== 'object';
const text = (value: Json | undefined): string => value === undefined ? '未采集' : value === '' ? '（空字符串）' : JSON.stringify(value);
const thermalNames = new Map([[0, 'NONE'], [1, 'LIGHT'], [2, 'MODERATE'], [3, 'SEVERE'], [4, 'CRITICAL'], [5, 'EMERGENCY'], [6, 'SHUTDOWN']]);
const statuses = new Map([['ok', '查询成功'], ['unsupported', '系统不支持查询'], ['unavailable', '服务不可用'], ['denied', '读取受限'], ['error', '读取异常']]);

export interface ThermalThresholdRow {
  key: string;
  label: string;
  left?: Json;
  right?: Json;
  state: MergedDisplayRow['state'];
}

// 业务块：按原始数组序号保留重复和顺序；完整条目决定差异，元数据保留查询及明细存在性/数量。
export function thermalThresholdRows(left?: Json, right?: Json): ThermalThresholdRow[] | undefined {
  for (const value of [left, right]) {
    if (value === undefined) continue;
    if (!object(value) || !['managerAvailable', 'status', 'entryCount', 'entryLimit', 'errorClass'].every(field => scalar(value[field]))) return undefined;
    const entries = value.entries;
    if (entries === undefined || entries === null) continue;
    if (!Array.isArray(entries) || entries.length > 64 || entries.some(entry => !object(entry) || !scalar(entry.thermalStatus))) return undefined;
  }
  const metadata = (value?: Json): Json | undefined => {
    if (!object(value)) return undefined;
    return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, key === 'entries' && Array.isArray(child) ? child.length : child]));
  };
  const a = object(left) && Array.isArray(left.entries) ? left.entries : [];
  const b = object(right) && Array.isArray(right.entries) ? right.entries : [];
  const pair = (key: string, label: string, x?: Json, y?: Json): ThermalThresholdRow => ({ key, label, left: x, right: y, state: x === undefined ? 'right-only' : y === undefined ? 'left-only' : differences(x, y).length ? 'changed' : 'same' });
  const rows: ThermalThresholdRow[] = [];
  if (left !== undefined || right !== undefined) rows.push(pair('metadata', '查询与附加字段', metadata(left), metadata(right)));
  for (let index = 0; index < Math.max(a.length, b.length); index++) rows.push(pair(String(index), `阈值 [${index}]`, a[index], b[index]));
  return rows;
}

export function thermalThresholdFacts(value?: Json, metadata = false): string[] {
  if (value === undefined) return [metadata ? '未采集' : '此侧没有该阈值'];
  if (!object(value)) return [`原始值 ${text(value)}`];
  if (metadata) {
    const status = typeof value.status === 'string' ? statuses.get(value.status) : undefined;
    const available = value.managerAvailable;
    const entries = value.entries === undefined ? '明细未采集' : value.entries === null ? '明细 null' : value.entries === 0 ? '明细为空 []' : `返回明细 ${text(value.entries)} 项`;
    const facts = [status ? `${status}（${value.status}）` : `查询状态 ${text(value.status)}`,
      `管理服务 ${available === true ? '可用（true）' : available === false ? '不可用（false）' : text(available)}`,
      `声明条目 ${text(value.entryCount)} · 上限 ${text(value.entryLimit)}`, entries];
    if (value.errorClass !== undefined) facts.push(`错误 ${text(value.errorClass)}`);
    const extras = Object.keys(value).filter(key => !['managerAvailable', 'status', 'entryCount', 'entryLimit', 'entries', 'errorClass'].includes(key)).length;
    if (extras) facts.push(`附加字段 ${extras} 项 · 见完整字段`);
    return facts;
  }
  const name = typeof value.thermalStatus === 'number' ? thermalNames.get(value.thermalStatus) : undefined;
  const facts = [`热状态 ${text(value.thermalStatus)}${name ? ` · ${name}` : ''}`, `归一化阈值 ${text(value.headroom)}`];
  const extras = Object.keys(value).filter(key => !['thermalStatus', 'headroom'].includes(key)).length;
  if (extras) facts.push(`附加字段 ${extras} 项 · 见完整字段`);
  return facts;
}
