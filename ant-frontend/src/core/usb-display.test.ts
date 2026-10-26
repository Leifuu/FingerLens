import { describe, expect, it } from 'vitest';
import type { Json } from './types';
import { usbDeviceFacts, usbDeviceRows, usbEndpointFacts, usbEndpointRows, usbInterfaceFacts, usbInterfaceRows, usbRootFacts } from './usb-display';

const endpoint = { address: 129, endpointNumber: 1, direction: 128, attributes: 2, type: 2, maxPacketSize: 512, interval: 0 };
const query = { status: 'ok', endpointCount: 2, returnedCount: 2, endpointLimit: 64, endpointsTruncated: false, endpointsSha256: 'a', endpoints: [endpoint, endpoint] };
const face = { id: 0, alternateSetting: 0, class: 255, subclass: 0, protocol: 0, endpointCount: 2, endpointDescriptors: query };
const device = { deviceName: '/dev/bus/usb/001/002', vendorId: 18, productId: 52, deviceClass: 0, deviceSubclass: 0, deviceProtocol: 0, interfaceCount: 2, configurationCount: 1, interfaces: [face, face] };
const report = { count: 2, devices: [device, device] };

describe('USB分层概览', () => {
  it('保留原序重复与完整对象差异，三层原始类型/未知字段变化不被格式化吞掉', () => {
    const before = JSON.stringify(report), changed = JSON.parse(before) as typeof report;
    expect(usbDeviceRows(report, report)?.map(row => [row.key, row.state])).toEqual([['0', 'same'], ['1', 'same']]);
    changed.devices[1].interfaces[1].endpointDescriptors.endpoints[1].interval = -1;
    const devices = usbDeviceRows(report, changed)!;
    expect(devices.map(row => row.state)).toEqual(['same', 'changed']);
    const faces = usbInterfaceRows(devices[1].left, devices[1].right);
    expect(faces.map(row => row.state)).toEqual(['same', 'changed']);
    expect(usbEndpointRows(faces[1].left, faces[1].right).map(row => row.state)).toEqual(['same', 'changed']);
    const future = { ...endpoint, future: false };
    expect(usbEndpointRows({ endpointDescriptors: { endpoints: [future] } }, { endpointDescriptors: { endpoints: [{ ...future, future: 'false' }] } })[0].state).toBe('changed');
    expect(usbEndpointFacts(future)).toContain('其它字段 1 项 · 见完整字段');
    const switched = { ...report, devices: [{ ...device, vendorId: '18' }] };
    expect(usbDeviceRows(report, switched)?.[0].state).toBe('changed');
    expect(usbDeviceFacts(switched.devices[0])[1]).toContain('"18"');
    expect(JSON.stringify(report)).toBe(before);
  });

  it('只为已知数值附公开枚举含义，零/负整数/interval原文和空字符串不改写', () => {
    expect(usbEndpointFacts(endpoint)).toContain('方向 IN · device→host（128）');
    expect(usbEndpointFacts({ ...endpoint, direction: 0, type: 0, interval: -1 })).toContain('方向 OUT · host→device（0）');
    expect(usbEndpointFacts({ ...endpoint, direction: '128', type: '2', interval: -1 })).toEqual(['地址 129 · 端点编号 1', '方向 "128"', '属性 2 · 类型 "2"', '最大包大小 512 · interval 原值 -1']);
    for (const [type, name] of [[0, '控制'], [1, '等时'], [2, '批量'], [3, '中断']] as const) expect(usbEndpointFacts({ ...endpoint, type })[2]).toContain(`${name}（${type}）`);
    expect(usbEndpointFacts({ ...endpoint, type: 99, maxPacketSize: -1 })[2]).toBe('属性 2 · 类型 99');
    expect(usbDeviceFacts({ ...device, deviceName: '', vendorId: null })[0]).toBe('设备路径 （空字符串）');
    expect(usbDeviceFacts({ ...device, interfaces: [] })).toContain('接口明细 空列表 []');
    expect(usbDeviceFacts({ ...device, interfaces: null })).toContain('接口明细 null');
    expect(usbRootFacts({ ...report, future: false })).toContain('其它字段 1 项');
  });

  it('旧查询缺失、成功空列表、null/失败/跳过和仅哈希变化分别保留', () => {
    const old = { id: 0, endpointCount: 2 };
    expect(usbInterfaceFacts(old)).toContain('端点描述符未采集（旧报告可能没有此字段）');
    expect(usbInterfaceFacts({ ...old, endpointDescriptors: null })).toContain('端点查询原始值 null');
    for (const status of ['ok', 'partial', 'denied', 'error', 'skipped', 'future']) {
      const value = { ...face, endpointDescriptors: { ...query, status, endpoints: null, reason: 'snapshot-endpoint-budget', errorClass: 'TEST', future: false } };
      expect(usbInterfaceFacts(value).join('\n')).toContain(status);
      expect(usbInterfaceFacts(value)).toContain('端点明细 null');
      expect(usbInterfaceFacts(value)).toContain('reason "snapshot-endpoint-budget"');
    }
    expect(usbInterfaceFacts({ ...face, endpointDescriptors: { ...query, endpoints: [] } })).toContain('端点明细 空列表 []');
    const hashOnly = { ...face, endpointDescriptors: { ...query, endpointsSha256: 'b' } };
    expect(usbInterfaceRows({ interfaces: [face] }, { interfaces: [hashOnly] })[0].state).toBe('changed');
    expect(usbEndpointRows(face, hashOnly).every(row => row.state === 'same')).toBe(true);
    expect(usbInterfaceFacts(hashOnly)).toContain('完整集合 SHA256 "b"');
  });

  it('单侧、空设备与旧结构不伪造端点，缺失与null、单侧原始序号保持区别', () => {
    expect(usbDeviceRows(undefined, undefined)).toEqual([]);
    expect(usbDeviceRows({ count: 0, devices: [] }, { count: 0, devices: [] })).toEqual([]);
    for (const side of ['left', 'right']) {
      const rows = usbDeviceRows(side === 'left' ? report : undefined, side === 'right' ? report : undefined)!;
      expect(rows.map(row => row.state)).toEqual([`${side}-only`, `${side}-only`]);
      expect(rows[0][side === 'left' ? 'right' : 'left']).toBeUndefined();
    }
    const old = { ...device, interfaces: [{ id: 0, endpointCount: 2 }] };
    expect(usbDeviceRows({ devices: [old] }, undefined)).toHaveLength(1);
    expect(usbEndpointRows(old.interfaces[0])).toEqual([]);
    expect(usbInterfaceRows({ interfaces: [face] }, { interfaces: [] })[0].state).toBe('left-only');
    expect(usbDeviceRows(null, report)).toBeUndefined();
  });

  it('有界结构进入概览，异构/超限返回完整字段，不丢弃未知复杂字段', () => {
    const withFaces = (faces: Json[]) => ({ devices: [{ ...device, interfaces: faces }] });
    expect(usbDeviceRows({ devices: Array(256).fill({ ...device, interfaces: [] }) })).toHaveLength(256);
    expect(usbDeviceRows({ devices: Array(257).fill(device) })).toBeUndefined();
    expect(usbDeviceRows(withFaces(Array(256).fill({ id: 0 })))).toHaveLength(1);
    expect(usbDeviceRows(withFaces(Array(257).fill({ id: 0 })))).toBeUndefined();
    const many = (count: number) => ({ ...face, endpointDescriptors: { ...query, endpoints: Array(count).fill(endpoint) } });
    expect(usbDeviceRows(withFaces(Array(64).fill(many(64))))).toHaveLength(1);
    expect(usbDeviceRows(withFaces(Array(65).fill(many(64))))).toBeUndefined();
    expect(usbDeviceRows(withFaces([many(65)]))).toBeUndefined();
    expect(usbDeviceRows({ devices: Array(16).fill({ ...device, interfaces: Array(256).fill({ id: 0 }) }) })).toHaveLength(16);
    expect(usbDeviceRows({ devices: Array(17).fill({ ...device, interfaces: Array(256).fill({ id: 0 }) }) })).toBeUndefined();
    const invalid: Json[] = [{ devices: [null] }, { devices: [{ ...device, vendorId: {} }] }, withFaces([[]]), withFaces([{ ...face, endpointDescriptors: [] }]), withFaces([{ ...face, endpointDescriptors: { ...query, endpoints: [null] } }]), withFaces([{ ...face, endpointDescriptors: { ...query, endpoints: [{ ...endpoint, interval: {} }] } }])];
    for (const bad of invalid) expect(usbDeviceRows(bad)).toBeUndefined();
    expect(usbDeviceRows({ ...report, future: { nested: false } })?.[0].left).toBe(device);
  });
});
