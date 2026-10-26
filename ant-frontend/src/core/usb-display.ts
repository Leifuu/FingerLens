import { differences } from './compare';
import type { Json } from './types';
import type { MergedDisplayRow } from './display-compare';

const object = (value: Json | undefined): value is Record<string, Json> => value !== null && typeof value === 'object' && !Array.isArray(value);
const scalar = (value: Json | undefined) => value === undefined || value === null || typeof value !== 'object';
const deviceFields = ['deviceName', 'vendorId', 'productId', 'deviceClass', 'deviceSubclass', 'deviceProtocol', 'configurationCount', 'interfaceCount'];
const interfaceFields = ['id', 'alternateSetting', 'class', 'subclass', 'protocol', 'endpointCount'];
const endpointFields = ['address', 'endpointNumber', 'direction', 'attributes', 'type', 'maxPacketSize', 'interval'];
const queryFields = ['status', 'endpointCount', 'returnedCount', 'endpointLimit', 'endpointsTruncated', 'endpointsSha256', 'reason', 'errorClass'];
const text = (value: Json | undefined): string => value === undefined ? '未采集' : value === '' ? '（空字符串）' : JSON.stringify(value);
const extras = (value: Record<string, Json>, known: string[]) => {
  const count = Object.keys(value).filter(key => !known.includes(key)).length;
  return count ? [`其它字段 ${count} 项 · 见完整字段`] : [];
};

export interface UsbDisplayRow {
  key: string;
  left?: Record<string, Json>;
  right?: Record<string, Json>;
  state: MergedDisplayRow['state'];
}

// 业务块：按原始序号配对，完整对象决定差异，保留重复、原始类型、哈希和未知字段。
function paired(left: Json[] = [], right: Json[] = []): UsbDisplayRow[] {
  return Array.from({ length: Math.max(left.length, right.length) }, (_, index) => {
    const a = left[index] as Record<string, Json> | undefined, b = right[index] as Record<string, Json> | undefined;
    return { key: String(index), left: a, right: b, state: a === undefined ? 'right-only' : b === undefined ? 'left-only' : differences(a, b).length ? 'changed' : 'same' };
  });
}

// 业务块：只概览有界公开描述符；复杂旧结构与超限输入整体回退原字段表，不截断导入值。
export function usbDeviceRows(left?: Json, right?: Json): UsbDisplayRow[] | undefined {
  for (const value of [left, right]) {
    if (value === undefined) continue;
    if (!object(value) || !Array.isArray(value.devices) || value.devices.length > 256) return undefined;
    let interfaces = 0, endpoints = 0;
    for (const device of value.devices) {
      if (!object(device) || !deviceFields.every(key => scalar(device[key]))) return undefined;
      const faces = device.interfaces;
      if (faces === undefined || faces === null) continue;
      if (!Array.isArray(faces) || faces.length > 256 || (interfaces += faces.length) > 4096) return undefined;
      for (const face of faces) {
        if (!object(face) || !interfaceFields.every(key => scalar(face[key]))) return undefined;
        const query = face.endpointDescriptors;
        if (scalar(query)) continue;
        if (!object(query) || !queryFields.every(key => scalar(query[key]))) return undefined;
        const values = query.endpoints;
        if (values === undefined || values === null) continue;
        if (!Array.isArray(values) || values.length > 64 || (endpoints += values.length) > 4096 || values.some(endpoint => !object(endpoint) || !endpointFields.every(key => scalar(endpoint[key])))) return undefined;
      }
    }
  }
  return paired(object(left) ? left.devices as Json[] : [], object(right) ? right.devices as Json[] : []);
}

export function usbInterfaceRows(left?: Record<string, Json>, right?: Record<string, Json>) {
  return paired(Array.isArray(left?.interfaces) ? left.interfaces : [], Array.isArray(right?.interfaces) ? right.interfaces : []);
}

export function usbEndpointRows(left?: Record<string, Json>, right?: Record<string, Json>) {
  const values = (face?: Record<string, Json>) => object(face?.endpointDescriptors) && Array.isArray(face.endpointDescriptors.endpoints) ? face.endpointDescriptors.endpoints : [];
  return paired(values(left), values(right));
}

export function usbRootFacts(value?: Json): string {
  if (!object(value)) return '此侧没有USB采集项';
  const other = extras(value, ['count', 'devices']);
  return `设备总数 ${text(value.count)} · 明细 ${Array.isArray(value.devices) ? value.devices.length : text(value.devices)} 项${other.length ? ` · ${other[0]}` : ''}`;
}

export function usbDeviceFacts(device: Record<string, Json>): string[] {
  const faces = device.interfaces;
  return [`设备路径 ${text(device.deviceName)}`, `厂商 ID ${text(device.vendorId)} · 产品 ID ${text(device.productId)}`,
    `设备类 / 子类 / 协议 ${text(device.deviceClass)} / ${text(device.deviceSubclass)} / ${text(device.deviceProtocol)}`,
    `配置数 ${text(device.configurationCount)} · 接口数 ${text(device.interfaceCount)}`,
    `接口明细 ${Array.isArray(faces) ? faces.length ? `${faces.length} 项` : '空列表 []' : text(faces)}`, ...extras(device, [...deviceFields, 'interfaces'])];
}

export function usbInterfaceFacts(face: Record<string, Json>): string[] {
  const result = [`接口 ID ${text(face.id)} · 替代设置 ${text(face.alternateSetting)}`,
    `接口类 / 子类 / 协议 ${text(face.class)} / ${text(face.subclass)} / ${text(face.protocol)}`, `端点数 ${text(face.endpointCount)}`];
  const query = face.endpointDescriptors;
  if (query === undefined) result.push('端点描述符未采集（旧报告可能没有此字段）');
  else if (!object(query)) result.push(`端点查询原始值 ${text(query)}`);
  else {
    const labels = new Map([['ok', '成功'], ['partial', '部分返回'], ['skipped', '已跳过'], ['denied', '读取受限'], ['error', '读取异常']]);
    const label = typeof query.status === 'string' ? labels.get(query.status) : undefined;
    result.push(`端点查询 ${label ? `${label}（${query.status}）` : text(query.status)}`,
      `总数 ${text(query.endpointCount)} · 返回 ${text(query.returnedCount)} · 上限 ${text(query.endpointLimit)} · 截断 ${text(query.endpointsTruncated)}`,
      `端点明细 ${Array.isArray(query.endpoints) ? query.endpoints.length ? `${query.endpoints.length} 项` : '空列表 []' : text(query.endpoints)}`,
      `完整集合 SHA256 ${text(query.endpointsSha256)}`);
    for (const key of ['reason', 'errorClass']) if (query[key] !== undefined) result.push(`${key} ${text(query[key])}`);
    result.push(...extras(query, [...queryFields, 'endpoints']));
  }
  return [...result, ...extras(face, [...interfaceFields, 'endpointDescriptors'])];
}

export function usbEndpointFacts(endpoint: Record<string, Json>): string[] {
  const directions = new Map([[0, 'OUT · host→device'], [128, 'IN · device→host']]);
  const types = new Map([[0, '控制'], [1, '等时'], [2, '批量'], [3, '中断']]);
  const meaning = (value: Json | undefined, names: Map<number, string>) => typeof value === 'number' && names.has(value) ? `${names.get(value)}（${value}）` : text(value);
  return [`地址 ${text(endpoint.address)} · 端点编号 ${text(endpoint.endpointNumber)}`, `方向 ${meaning(endpoint.direction, directions)}`,
    `属性 ${text(endpoint.attributes)} · 类型 ${meaning(endpoint.type, types)}`, `最大包大小 ${text(endpoint.maxPacketSize)} · interval 原值 ${text(endpoint.interval)}`,
    ...extras(endpoint, endpointFields)];
}
