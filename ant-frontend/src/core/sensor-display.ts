import type { MergedDisplayRow } from './display-compare';
import { differences } from './compare';
import type { Json } from './types';

const object = (value: Json | undefined): value is Record<string, Json> => value !== null && typeof value === 'object' && !Array.isArray(value);
const raw = (value: Json | undefined): string => value === undefined ? '未采集' : value === '' ? '（空字符串）' : value !== null && typeof value === 'object' ? '其他结构（详见完整字段）' : JSON.stringify(value);
const idKinds = new Map([['unsupported', '不支持 ID'], ['type-and-name', '按类型和名称识别'], ['app-scoped-id', 'App 范围 ID']]);
const queryStates = new Map([['denied', '读取受限'], ['error', '读取异常'], ['unsupported', '不支持查询'], ['unavailable', '接口不可用']]);

// 业务块：直接读取原始JSON以区分数字/文字及false/null；旧报告不推断ID或附加信息能力。
export function sensorPublicMetadataText(sensor: Json | undefined): { sensorId: string; additionalInfo: string } {
  const metadata = object(sensor) ? sensor.publicMetadata : undefined;
  const query = (name: string) => {
    if (metadata === undefined) return '未采集';
    if (!object(metadata)) return `原始值 ${raw(metadata)}`;
    const value = metadata[name];
    if (value === undefined) return '未采集';
    if (!object(value)) return `原始值 ${raw(value)}`;
    const error = Object.hasOwn(value, 'errorClass') ? ` · ${raw(value.errorClass)}` : '';
    if (value.status !== 'ok') {
      const label = typeof value.status === 'string' ? queryStates.get(value.status) : undefined;
      return `${label ? `${label}（${value.status}）` : `读取状态 ${raw(value.status)}`} · 原始值 ${raw(value.value)}${error}`;
    }
    if (name === 'additionalInfoSupported') return `${value.value === true ? '支持（true）' : value.value === false ? '不支持（false）' : `原始值 ${raw(value.value)}`}${error}`;
    const kind = typeof value.kind === 'string' ? idKinds.get(value.kind) : undefined;
    return `${raw(value.value)} · ${kind ? `${kind}（${value.kind}）` : `含义 ${raw(value.kind)}`}${error}`;
  };
  return { sensorId: query('sensorId'), additionalInfo: query('additionalInfoSupported') };
}

export interface SensorDisplayRow {
  key: string;
  name: MergedDisplayRow;
  vendor: MergedDisplayRow;
  type: MergedDisplayRow;
  stringType?: MergedDisplayRow;
  version?: MergedDisplayRow;
  resolution?: MergedDisplayRow;
  maximumRange?: MergedDisplayRow;
  power?: MergedDisplayRow;
  reportingMode?: MergedDisplayRow;
  wakeUp?: MergedDisplayRow;
  dynamic?: MergedDisplayRow;
  state: MergedDisplayRow['state'];
}

// 业务块：沿用原始数组序号，保留重复传感器；全部字段共同决定概览差异。
export function sensorDisplayRows(rows: MergedDisplayRow[], leftValue?: Json, rightValue?: Json): SensorDisplayRow[] | undefined {
  const groups = new Map<string, MergedDisplayRow[]>();
  for (const row of rows) {
    const match = /^\[(\d+)\]\./.exec(row.label);
    if (!match) return undefined;
    const fields = groups.get(match[1]) || [];
    fields.push(row);
    groups.set(match[1], fields);
  }
  const result: SensorDisplayRow[] = [];
  for (const [key, fields] of groups) {
    const field = (name: string) => fields.find(row => row.label === `[${key}].${name}`);
    const name = field('name'), vendor = field('vendor'), type = field('type');
    if (!name || !vendor || !type) return undefined;
    const present = (side: 'left' | 'right') => fields.some(row => row[side] !== null);
    const left = present('left'), right = present('right');
    const a = Array.isArray(leftValue) ? leftValue[Number(key)] : undefined, b = Array.isArray(rightValue) ? rightValue[Number(key)] : undefined;
    const rawChanged = a !== undefined && b !== undefined && differences(a, b).length > 0;
    for (const side of ['left', 'right'] as const) {
      if (present(side) && [name, vendor, type].some(row => row[side] === null)) return undefined;
    }
    result.push({ key, name, vendor, type, stringType: field('stringType'), version: field('version'), resolution: field('resolution'), maximumRange: field('maximumRange'), power: field('power'), reportingMode: field('reportingMode'), wakeUp: field('wakeUp'), dynamic: field('dynamic'),
      state: !left ? 'right-only' : !right ? 'left-only' : rawChanged || fields.some(row => row.state !== 'same') ? 'changed' : 'same' });
  }
  return result.length ? result : undefined;
}
