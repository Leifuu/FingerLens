import { differences } from './compare';
import type { MergedDisplayRow } from './display-compare';
import type { Json } from './types';

const object = (value: Json | undefined): value is Record<string, Json> => value !== null && typeof value === 'object' && !Array.isArray(value);
const fields = ['name', 'manufacturerPnpId', 'productId', 'modelYear', 'manufactureYear', 'manufactureWeek', 'connectionToSinkType'];
const atom = (value: Json | undefined) => value === null || typeof value !== 'object' || object(value) && (value.$type === 'int64' || value.$type === 'float') && typeof value.value === 'string';
const text = (value: Json | undefined): string => value === undefined ? '未采集' : value === '' ? '（空字符串）' : value !== null && typeof value === 'object' ? object(value) && atom(value) ? `${JSON.stringify(value.value)}（${value.$type}）` : '其他结构（详见完整字段）' : JSON.stringify(value);
const year = (value: Json | undefined) => value === -1 ? '-1（平台未提供）' : text(value);
const statuses = new Map([['ok', '成功'], ['unsupported', '系统不支持查询'], ['denied', '读取受限'], ['error', '读取异常']]);
const connections = new Map([[0, '未知'], [1, '内置'], [2, '直接连接'], [3, '经中间设备连接']]);

export interface DisplayProductRow {
  key: 'product';
  left?: Json;
  right?: Json;
  state: MergedDisplayRow['state'];
}

// 业务块：完整查询对象决定差异；只概览固定标量元数据，异构结构保留完整字段而不强制转文字。
export function displayProductRows(left: Json | undefined, right: Json | undefined): DisplayProductRow[] | undefined {
  if ([left, right].some(value => value !== undefined && !object(value))) return undefined;
  const a = object(left) ? left.deviceProductInfo : undefined, b = object(right) ? right.deviceProductInfo : undefined;
  const valid = (query: Json | undefined) => {
    if (query === undefined || query === null || typeof query !== 'object') return true;
    if (!object(query)) return false;
    const value = query.value;
    return value === undefined || value === null || typeof value !== 'object' || object(value) && fields.every(key => atom(value[key]));
  };
  if (!valid(a) || !valid(b)) return undefined;
  if (a === undefined && b === undefined) return [];
  return [{ key: 'product', left: a, right: b, state: a === undefined ? 'right-only' : b === undefined ? 'left-only' : differences(a, b).length ? 'changed' : 'same' }];
}

// 业务块：仅为公开数值添加含义；-1不改为null，字符串编号不套整数含义，年份与制造周互不补推。
export function displayProductFacts(query: Json | undefined): { key: string; label: string; value: string }[] {
  if (query === undefined) return [{ key: 'missing', label: '采集', value: '未采集' }];
  if (!object(query)) return [{ key: 'raw', label: '原始值', value: text(query) }];
  const status = typeof query.status === 'string' ? statuses.get(query.status) : undefined;
  const facts = [{ key: 'status', label: '查询', value: status ? `${status}（${query.status}）` : `状态 ${text(query.status)}` }];
  const value = query.value;
  if (!object(value)) facts.push({ key: 'value', label: '产品信息', value: query.status === 'ok' && value === null ? 'null（平台未提供产品信息）' : text(value) });
  else {
    const connection = value.connectionToSinkType;
    facts.push({ key: 'name', label: '名称', value: text(value.name) },
      { key: 'manufacturer', label: '厂商 PnP ID', value: text(value.manufacturerPnpId) },
      { key: 'product', label: '产品 ID', value: text(value.productId) },
      { key: 'model', label: '型号年份', value: year(value.modelYear) },
      { key: 'manufacture', label: '制造年 / 周', value: `${year(value.manufactureYear)} / ${year(value.manufactureWeek)}` },
      { key: 'connection', label: '连接类型', value: typeof connection === 'number' ? `${connections.get(connection) ?? '未知类型'}（${text(connection)}）` : text(connection) });
  }
  if (query.errorClass !== undefined) facts.push({ key: 'error', label: '错误', value: text(query.errorClass) });
  const extras = Object.keys(query).filter(key => !['status', 'value', 'errorClass'].includes(key)).length + (object(value) ? Object.keys(value).filter(key => !fields.includes(key)).length : 0);
  if (extras) facts.push({ key: 'extras', label: '附加字段', value: `${extras} 项（详见完整字段）` });
  return facts;
}
