import { differences } from './compare';
import type { Json } from './types';
import type { MergedDisplayRow } from './display-compare';

const object = (value: Json | undefined): value is Record<string, Json> => value !== null && typeof value === 'object' && !Array.isArray(value);
const scalar = (value: Json | undefined) => value === undefined || value === null || typeof value !== 'object';
const atom = (value: Json | undefined) => scalar(value) || object(value) && (value.$type === 'float' || value.$type === 'int64') && typeof value.value === 'string';
const text = (value: Json | undefined): string => value === undefined ? '未采集' : value === '' ? '（空字符串）' : object(value) ? atom(value) ? `${JSON.stringify(value.value)}（${value.$type}）` : '其他结构（详见完整字段）' : JSON.stringify(value);
const statuses = new Map([['ok', '成功'], ['unsupported', '系统不支持查询'], ['denied', '读取受限'], ['error', '读取异常']]);
const kinds = new Map([['finite', '有限数'], ['unknown-sentinel', '未知哨兵'], ['non-finite', '非有限数']]);
const state = (a: Json | undefined, b: Json | undefined): MergedDisplayRow['state'] => a === undefined ? 'right-only' : b === undefined ? 'left-only' : differences(a, b).length ? 'changed' : 'same';
const kind = (value: Json | undefined) => typeof value === 'string' && kinds.has(value) ? `${kinds.get(value)}（${value}）` : `含义 ${text(value)}`;

function query(value: Json | undefined, render: (value: Record<string, Json>) => string): string {
  if (value === undefined) return '未采集';
  if (!object(value)) return `原始值 ${text(value)}`;
  const label = typeof value.status === 'string' ? statuses.get(value.status) : undefined;
  const status = label ? `${label}（${value.status}）` : `读取状态 ${text(value.status)}`;
  const error = value.errorClass !== undefined ? ` · ${text(value.errorClass)}` : '';
  return `${status} · ${object(value.value) ? render(value.value) : `原始值 ${text(value.value)}`}${error}`;
}

export interface MicrophoneFacts {
  identity: string;
  grouping: string;
  calibration: string;
  position: string;
  orientation: string;
  response: string;
  points?: Json[] | null;
}
export interface MicrophoneDisplayRow {
  key: string;
  left?: MicrophoneFacts;
  right?: MicrophoneFacts;
  state: MergedDisplayRow['state'];
}

function facts(mic: Json): MicrophoneFacts | undefined {
  if (!object(mic) || !Object.hasOwn(mic, 'id') || !Object.hasOwn(mic, 'type') || !['id', 'type', 'location', 'directionality', 'group', 'indexInGroup'].every(key => scalar(mic[key]))) return undefined;
  const response = mic.frequencyResponse;
  const value = object(response) && object(response.value) ? response.value : undefined;
  const points = value?.points;
  if (points !== undefined && points !== null && (!Array.isArray(points) || points.length > 128 || points.some(point => !object(point) || !Object.hasOwn(point, 'frequencyHz') || !Object.hasOwn(point, 'responseDb') || !atom(point.frequencyHz) || !atom(point.responseDb)))) return undefined;
  const calibration = (value: Record<string, Json>) => [['sensitivityDbfs', '灵敏度', 'dBFS'], ['maxSplDb', '最大 SPL', 'dB'], ['minSplDb', '最小 SPL', 'dB']].map(([key, label, unit]) => {
    const reading = value[key];
    return `${label} ${object(reading) ? `${text(reading.raw)} ${unit} · ${kind(reading.kind)}` : text(reading)}`;
  }).join('\n');
  const coordinates = (value: Record<string, Json>) => `x ${text(value.x)} · y ${text(value.y)} · z ${text(value.z)} · ${kind(value.kind)}`;
  return {
    identity: `ID ${text(mic.id)} · 类型 ${text(mic.type)}`,
    grouping: `位置枚举 ${text(mic.location)} · 指向性 ${text(mic.directionality)} · 分组 ${text(mic.group)} · 组内序号 ${text(mic.indexInGroup)}`,
    calibration: query(mic.calibration, calibration), position: query(mic.positionMeters, coordinates), orientation: query(mic.orientation, coordinates),
    response: query(response, value => `总数 ${text(value.pointCount)} · 返回 ${text(value.returnedCount)} · 截断 ${text(value.truncated)}${points === undefined ? ' · points未采集' : points === null ? ' · points=null' : Array.isArray(points) && !points.length ? ' · 空数组 []' : ''}`),
    points: points as Json[] | null | undefined,
  };
}

// 业务块：256麦克风/128频响点以内进入概览；原始对象/原序保留，异构条目回退完整字段。
export function microphoneDisplayRows(left: Json | undefined, right: Json | undefined): MicrophoneDisplayRow[] | undefined {
  const list = (value: Json | undefined) => value === undefined ? [] : object(value) && Array.isArray(value.microphones) ? value.microphones : undefined;
  const a = list(left), b = list(right);
  if (!a || !b || a.length > 256 || b.length > 256) return undefined;
  const rows: MicrophoneDisplayRow[] = [];
  for (let index = 0; index < Math.max(a.length, b.length); index++) {
    const l = a[index], r = b[index], lf = l === undefined ? undefined : facts(l), rf = r === undefined ? undefined : facts(r);
    if (l !== undefined && !lf || r !== undefined && !rf) return undefined;
    rows.push({ key: String(index), left: lf, right: rf, state: state(l, r) });
  }
  return rows;
}

// 业务块：频响按原始序号配对，保留重复频率、非有限类型及未知字段的差异，不插值或排序。
export function microphonePointRows(left?: MicrophoneFacts, right?: MicrophoneFacts) {
  const a = left?.points || [], b = right?.points || [];
  return Array.from({ length: Math.max(a.length, b.length) }, (_, index) => {
    const l = a[index], r = b[index];
    const lines = (value: Json | undefined) => object(value) ? [`频率 ${text(value.frequencyHz)} Hz`, `响应 ${text(value.responseDb)} dB`] : undefined;
    return { key: String(index), left: lines(l), right: lines(r), state: state(l, r) };
  });
}
