import { differences } from './compare';
import type { Json } from './types';
import type { MergedDisplayRow } from './display-compare';

const object = (value: Json | undefined): value is Record<string, Json> => value !== null && typeof value === 'object' && !Array.isArray(value);
const scalar = (value: Json | undefined) => value === undefined || value === null || typeof value !== 'object';
const text = (value: Json | undefined): string => value === undefined ? '未采集' : value === '' ? '（空字符串）' : JSON.stringify(value);
const fields = ['mixerBehavior', 'sampleRateHz', 'encoding', 'channelMask', 'channelIndexMask', 'channelCount'];
const metadata = ['status', 'attributeCount', 'returnedCount', 'attributeLimit', 'attributesTruncated', 'attributesSha256', 'attributes', 'errorClass'];
const states = new Map([['ok', '成功'], ['partial', '部分返回'], ['unsupported', '系统不支持查询'], ['unavailable', '服务不可用'], ['denied', '读取受限'], ['error', '读取异常']]);

export interface AudioMixerFacts {
  status: string;
  count: string;
  attributes?: Json[] | null;
}

// 业务块：只格式化公开原值；未知字段仍参与外层比较，异构结构或超过64项时回退完整字段。
export function audioMixerFacts(value: Json): AudioMixerFacts | undefined {
  if (!object(value)) return scalar(value) ? { status: `原始值 ${text(value)}`, count: '详见完整字段' } : undefined;
  if (!metadata.filter(key => key !== 'attributes').every(key => scalar(value[key]))) return undefined;
  const attributes = value.attributes;
  if (attributes !== undefined && attributes !== null && (!Array.isArray(attributes) || attributes.length > 64 || attributes.some(entry => !object(entry) || !fields.every(key => scalar(entry[key]))))) return undefined;
  const label = typeof value.status === 'string' ? states.get(value.status) : undefined;
  const unknown = Object.keys(value).filter(key => !metadata.includes(key)).length;
  const status = `${label ? `${label}（${value.status}）` : `查询状态 ${text(value.status)}`}${value.errorClass === undefined ? '' : ` · ${text(value.errorClass)}`}${unknown ? ` · 其它元数据 ${unknown} 项，见完整字段` : ''}`;
  const detail = attributes === undefined ? 'attributes未采集' : attributes === null ? 'attributes=null' : attributes.length ? `明细 ${attributes.length} 项` : '空列表 []（不能据此判断硬件不支持）';
  return { status, count: `总数 ${text(value.attributeCount)} · 返回 ${text(value.returnedCount)} · 上限 ${text(value.attributeLimit)} · 截断 ${text(value.attributesTruncated)} · ${detail}`, attributes: attributes as Json[] | null | undefined };
}

// 业务块：按原始序号对照，保留重复、类型与完整条目中的未知字段差异，不推导当前混音配置。
export function audioMixerRows(left?: AudioMixerFacts, right?: AudioMixerFacts) {
  const a = left?.attributes || [], b = right?.attributes || [];
  return Array.from({ length: Math.max(a.length, b.length) }, (_, index) => {
    const l = a[index], r = b[index];
    const lines = (value: Json | undefined) => {
      if (!object(value)) return undefined;
      const behavior = value.mixerBehavior === 0 ? '默认（0）' : value.mixerBehavior === 1 ? '位精确（1）' : text(value.mixerBehavior);
      const result = [`混音行为 ${behavior} · 编码 ${text(value.encoding)}`, `采样率 ${text(value.sampleRateHz)} Hz · 声道数 ${text(value.channelCount)}`, `声道位置掩码 ${text(value.channelMask)}`, `声道索引掩码 ${text(value.channelIndexMask)}`];
      const unknown = Object.keys(value).filter(key => !fields.includes(key)).length;
      if (unknown) result.push(`其它字段 ${unknown} 项 · 见完整字段`);
      return result;
    };
    return { key: String(index), left: lines(l), right: lines(r), state: (l === undefined ? 'right-only' : r === undefined ? 'left-only' : differences(l, r).length ? 'changed' : 'same') as MergedDisplayRow['state'] };
  });
}
