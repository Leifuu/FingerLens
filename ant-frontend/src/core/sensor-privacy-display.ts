import { differences } from './compare';
import type { MergedDisplayRow } from './display-compare';
import type { Json } from './types';

const object = (value: Json | undefined): value is Record<string, Json> => value !== null && typeof value === 'object' && !Array.isArray(value);
const scalar = (value: Json | undefined) => value === null || typeof value !== 'object';
const sensors = ['microphone', 'camera'];
const toggles = ['softwareToggle', 'hardwareToggle'];
const text = (value: Json | undefined): string => value === undefined ? '未采集' : value === '' ? '（空字符串）' : JSON.stringify(value);
const statuses = new Map([['ok', '查询成功'], ['unsupported', '系统不支持查询'], ['unavailable', '服务不可用'], ['denied', '读取受限'], ['error', '读取异常']]);

export interface SensorPrivacyRow {
  key: string;
  label: string;
  left?: Json;
  right?: Json;
  state: MergedDisplayRow['state'];
}

// 业务块：四项能力按固定语义对照；完整查询对象和附加元数据决定差异，复杂已知结构回退原字段。
export function sensorPrivacyRows(left?: Json, right?: Json): SensorPrivacyRow[] | undefined {
  for (const value of [left, right]) {
    if (value === undefined) continue;
    if (!object(value) || !scalar(value.managerAvailable)) return undefined;
    for (const sensor of sensors) {
      const entry = value[sensor];
      if (entry === undefined) continue;
      if (!object(entry)) return undefined;
      for (const toggle of toggles) {
        const query = entry[toggle];
        if (!scalar(query) && (!object(query) || !['status', 'value', 'errorClass'].every(field => scalar(query[field])))) return undefined;
      }
    }
  }
  const metadata = (value?: Json): Json | undefined => {
    if (!object(value)) return undefined;
    const extra = Object.fromEntries(Object.entries(value).filter(([field]) => !sensors.includes(field)));
    for (const sensor of sensors) {
      const entry = value[sensor];
      if (!object(entry)) continue;
      const fields = Object.fromEntries(Object.entries(entry).filter(([field]) => !toggles.includes(field)));
      if (Object.keys(fields).length || !Object.keys(entry).length) extra[sensor] = fields;
    }
    return Object.keys(extra).length ? extra : undefined;
  };
  const rows: SensorPrivacyRow[] = [];
  const pair = (key: string, label: string, a?: Json, b?: Json) => {
    if (a === undefined && b === undefined) return;
    rows.push({ key, label, left: a, right: b, state: a === undefined ? 'right-only' : b === undefined ? 'left-only' : differences(a, b).length ? 'changed' : 'same' });
  };
  pair('metadata', '管理服务与附加字段', metadata(left), metadata(right));
  for (const sensor of sensors) for (const toggle of toggles) {
    const query = (value?: Json) => object(value) && object(value[sensor]) ? value[sensor][toggle] : undefined;
    pair(`${sensor}.${toggle}`, `${sensor === 'microphone' ? '麦克风' : '摄像头'} · ${toggle === 'softwareToggle' ? '软件开关' : '硬件开关'}`, query(left), query(right));
  }
  return rows;
}

export function sensorPrivacyFacts(query: Json | undefined, metadata = false): string[] {
  if (query === undefined) return ['未采集'];
  if (!object(query)) return [`原始值 ${text(query)}`];
  if (metadata) {
    const available = query.managerAvailable, count = Object.keys(query).filter(field => field !== 'managerAvailable').length;
    return [`管理服务 ${available === true ? '可用（true）' : available === false ? '不可用（false）' : text(available)}`, ...(count ? [`附加字段 ${count} 项 · 见完整字段`] : [])];
  }
  const label = typeof query.status === 'string' ? statuses.get(query.status) : undefined;
  const support = query.status === 'ok' && typeof query.value === 'boolean' ? query.value ? '支持（true）' : '不支持（false）' : `返回值 ${text(query.value)}`;
  const facts = [support, label ? `${label}（${query.status}）` : `查询状态 ${text(query.status)}`];
  if (query.errorClass !== undefined) facts.push(`错误 ${text(query.errorClass)}`);
  const extra = Object.keys(query).filter(field => !['status', 'value', 'errorClass'].includes(field)).length;
  if (extra) facts.push(`附加字段 ${extra} 项 · 见完整字段`);
  return facts;
}
