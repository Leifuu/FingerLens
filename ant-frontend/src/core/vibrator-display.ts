import { differences } from './compare';
import type { Json } from './types';
import type { MergedDisplayRow } from './display-compare';

interface VibratorFacts {
  id: string;
  captured: boolean;
  supportStatus: string;
  durationStatus: string;
  primitives: { name: string; support: string; duration: string }[];
}
export interface VibratorDisplayRow {
  key: string;
  left?: VibratorFacts;
  right?: VibratorFacts;
  state: MergedDisplayRow['state'];
}

const names = new Map([[1, 'CLICK'], [2, 'THUD'], [3, 'SPIN'], [4, 'QUICK_RISE'], [5, 'SLOW_RISE'], [6, 'QUICK_FALL'], [7, 'TICK'], [8, 'LOW_TICK']]);
const statuses = new Map([['ok', '成功'], ['unsupported', '系统不支持查询'], ['denied', '读取受限'], ['error', '读取异常']]);
const object = (value: Json | undefined): value is Record<string, Json> => value !== null && typeof value === 'object' && !Array.isArray(value);
const scalar = (value: Json | undefined) => value === undefined || value === null || typeof value !== 'object';
const text = (value: Json | undefined): string => value === undefined ? '未采集' : value === '' ? '（空字符串）' : JSON.stringify(value);

function query(value: Json | undefined, length: number): value is { status: string; values: Json[] | null; [key: string]: Json } {
  return object(value) && typeof value.status === 'string' && (value.values === null || Array.isArray(value.values) && value.values.length === length && value.values.every(scalar));
}
function status(value: { status: string; [key: string]: Json }): string {
  const label = statuses.get(value.status);
  return `${label ? `${label} (${value.status})` : text(value.status)}${Object.hasOwn(value, 'errorClass') ? ` · ${text(value.errorClass)}` : ''}${value.values === null ? ' · 原始值 null' : ''}`;
}
function facts(device: Record<string, Json>): VibratorFacts | undefined {
  if (!scalar(device.id)) return undefined;
  if (!Object.hasOwn(device, 'primitiveCapabilities')) return { id: text(device.id), captured: false, supportStatus: '未采集', durationStatus: '未采集', primitives: [] };
  const cap = device.primitiveCapabilities;
  if (!object(cap) || !Array.isArray(cap.primitiveIds) || cap.primitiveIds.length > 64 || !cap.primitiveIds.every(scalar)) return undefined;
  if (!query(cap.support, cap.primitiveIds.length) || !query(cap.durationsMs, cap.primitiveIds.length)) return undefined;
  const support = cap.support, durations = cap.durationsMs;
  return { id: text(device.id), captured: true, supportStatus: status(support), durationStatus: status(durations), primitives: cap.primitiveIds.map((id, index) => {
    const supported = support.values === null ? null : support.values[index], duration = durations.values === null ? null : durations.values[index];
    return { name: typeof id === 'number' && names.has(id) ? `${names.get(id)} (${id})` : `未知编号 ${text(id)}`,
      support: supported === true ? '支持（true）' : supported === false ? '不支持（false）' : text(supported),
      duration: typeof duration === 'number' ? `${duration} ms` : text(duration) };
  }) };
}

// 业务块：最多32个振动器、每侧64个基础单元进入概览；按原始序号保留重复，异构结构回退完整字段。
export function vibratorDisplayRows(left: Json | undefined, right: Json | undefined): VibratorDisplayRow[] | undefined {
  const devices = (value: Json | undefined) => value === undefined ? [] : object(value) && Array.isArray(value.vibrators) ? value.vibrators : undefined;
  const a = devices(left), b = devices(right);
  if (!a || !b || a.length > 32 || b.length > 32) return undefined;
  const result: VibratorDisplayRow[] = [];
  for (let index = 0; index < Math.max(a.length, b.length); index++) {
    const l = a[index], r = b[index];
    if (l !== undefined && !object(l) || r !== undefined && !object(r)) return undefined;
    const leftFacts = l === undefined ? undefined : facts(l as Record<string, Json>), rightFacts = r === undefined ? undefined : facts(r as Record<string, Json>);
    if (l !== undefined && !leftFacts || r !== undefined && !rightFacts) return undefined;
    // 完整原始对象决定差异，包含未知字段和类型；不因概览文本相同而掩盖变化。
    result.push({ key: String(index), left: leftFacts, right: rightFacts,
      state: l === undefined ? 'right-only' : r === undefined ? 'left-only' : differences(l, r).length ? 'changed' : 'same' });
  }
  return result.some(row => row.left?.captured || row.right?.captured) ? result : undefined;
}
