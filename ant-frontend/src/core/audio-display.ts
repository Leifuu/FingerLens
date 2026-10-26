import { differences } from './compare';
import type { Json } from './types';
import type { MergedDisplayRow } from './display-compare';
import { audioMixerFacts, type AudioMixerFacts } from './audio-mixer-display';

const object = (value: Json | undefined): value is Record<string, Json> => value !== null && typeof value === 'object' && !Array.isArray(value);
const scalar = (value: Json | undefined) => value === undefined || value === null || typeof value !== 'object';
const text = (value: Json | undefined): string => value === undefined ? '未采集' : value === '' ? '（空字符串）' : JSON.stringify(value);
const array = (value: Json | undefined) => value === undefined || value === null || Array.isArray(value) && value.length <= 1024 && value.every(scalar);
const states = new Map([['ok', '成功'], ['partial', '部分可观察'], ['unsupported', '系统不支持查询'], ['denied', '读取受限'], ['error', '读取异常']]);

export interface AudioFacts {
  name: string;
  identity: string;
  roles: string;
  aggregate: string;
  profileStatus: string;
  profileCount: string;
  profiles?: Json[] | null;
  mixer?: AudioMixerFacts;
}
export interface AudioDisplayRow {
  key: string;
  left?: AudioFacts;
  right?: AudioFacts;
  state: MergedDisplayRow['state'];
}

function facts(device: Json): AudioFacts | undefined {
  if (!object(device) || !Object.hasOwn(device, 'id') || !Object.hasOwn(device, 'type') || !['productName', 'id', 'type', 'isSource', 'isSink'].every(key => scalar(device[key]))) return undefined;
  if (!['encodings', 'sampleRates', 'channelCounts'].every(key => array(device[key]))) return undefined;
  const mixer = device.supportedMixerAttributes === undefined ? undefined : audioMixerFacts(device.supportedMixerAttributes);
  if (device.supportedMixerAttributes !== undefined && !mixer) return undefined;
  const cap = device.audioProfiles;
  let profileStatus = '未采集', profileCount = '旧报告未采集关联格式', profiles: Json[] | null | undefined;
  if (cap !== undefined) {
    if (!object(cap)) {
      if (!scalar(cap)) return undefined;
      profileStatus = `原始值 ${text(cap)}`;profileCount = '详见完整字段';
    } else {
      if (!['status', 'profileCount', 'returnedCount', 'profilesTruncated', 'errorClass'].every(key => scalar(cap[key]))) return undefined;
      if (cap.profiles !== undefined && cap.profiles !== null && (!Array.isArray(cap.profiles) || cap.profiles.length > 64)) return undefined;
      profiles = cap.profiles as Json[] | null | undefined;
      if (Array.isArray(profiles) && profiles.some(profile => !object(profile) || !scalar(profile.format) || !scalar(profile.encapsulationType) || !['sampleRatesHz', 'channelMasks', 'channelIndexMasks'].every(key => array(profile[key])))) return undefined;
      const label = typeof cap.status === 'string' ? states.get(cap.status) : undefined;
      profileStatus = `${label ? `${label}（${cap.status}）` : `读取状态 ${text(cap.status)}`}${cap.errorClass !== undefined ? ` · ${text(cap.errorClass)}` : ''}`;
      profileCount = `总数 ${text(cap.profileCount)} · 返回 ${text(cap.returnedCount)} · 截断 ${text(cap.profilesTruncated)}`;
      if (profiles === null) profileCount += ' · profiles=null';
      else if (profiles === undefined) profileCount += ' · profiles未采集';
      else if (!profiles.length) profileCount += ' · 空数组 []';
    }
  }
  return { name: typeof device.productName === 'string' ? device.productName || '（空字符串）' : text(device.productName), identity: `ID ${text(device.id)} · 类型 ${text(device.type)}`,
    roles: `输入 ${text(device.isSource)} · 输出 ${text(device.isSink)}`,
    aggregate: `编码 ${text(device.encodings)} · 采样率 Hz ${text(device.sampleRates)} · 声道数 ${text(device.channelCounts)}`,
    profileStatus, profileCount, profiles, mixer };
}

// 业务块：最多256设备/64关联格式/数组1024项进入概览；保留原始序号和重复，异构数据回退完整字段。
export function audioDisplayRows(left: Json | undefined, right: Json | undefined): AudioDisplayRow[] | undefined {
  const a = left === undefined ? [] : left, b = right === undefined ? [] : right;
  if (!Array.isArray(a) || !Array.isArray(b) || a.length > 256 || b.length > 256) return undefined;
  const rows: AudioDisplayRow[] = [];
  for (let index = 0; index < Math.max(a.length, b.length); index++) {
    const l = a[index], r = b[index], leftFacts = l === undefined ? undefined : facts(l), rightFacts = r === undefined ? undefined : facts(r);
    if (l !== undefined && !leftFacts || r !== undefined && !rightFacts) return undefined;
    rows.push({ key: String(index), left: leftFacts, right: rightFacts,
      state: l === undefined ? 'right-only' : r === undefined ? 'left-only' : differences(l, r).length ? 'changed' : 'same' });
  }
  return rows;
}

// 业务块：关联格式只按原始序号对齐，不从聚合数组推导组合；所有未知字段继续参与差异判断。
export function audioProfileRows(left?: AudioFacts, right?: AudioFacts) {
  const a = left?.profiles || [], b = right?.profiles || [];
  return Array.from({ length: Math.max(a.length, b.length) }, (_, index) => {
    const l = a[index], r = b[index];
    const lines = (value: Json | undefined) => !object(value) ? undefined : [
      `格式 ${text(value.format)} · 封装 ${text(value.encapsulationType)}`,
      `采样率 Hz ${text(value.sampleRatesHz)}`,
      `声道位置掩码 ${text(value.channelMasks)}`,
      `声道索引掩码 ${text(value.channelIndexMasks)}`,
    ];
    return { key: String(index), left: lines(l), right: lines(r),
      state: (l === undefined ? 'right-only' : r === undefined ? 'left-only' : differences(l, r).length ? 'changed' : 'same') as MergedDisplayRow['state'] };
  });
}
