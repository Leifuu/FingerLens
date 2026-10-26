import { ApiOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Select, Tag, Tooltip } from 'antd';
import type { AdbDevice } from '../core/adb';

interface Props {
  side: 'A' | 'B'; devices: AdbDevice[]; selected?: string; onSelect: (serial: string) => void;
  active: boolean; disabled: boolean; loaded: boolean; refreshing: boolean; onRefresh: () => void; onStart: () => void;
}

export function AdbCaptureControls(props: Props) {
  const selectedDevice = props.devices.find(device => device.serial === props.selected);
  return <div className="capture-controls">
    <Select className="device-select" aria-label={`设备 ${props.side} 的 ADB 设备`} placeholder="选择 ADB 设备" value={props.selected}
      disabled={props.active} onChange={props.onSelect} showSearch optionFilterProp="label" popupMatchSelectWidth={false}
      notFoundContent="没有设备，请先 adb connect 或连接 USB" options={props.devices.map(device => ({ value: device.serial,
        label: `${device.serial}${device.model ? ` · ${device.model}` : ''}${device.status !== 'device' ? ` · ${device.status}` : device.busy ? ' · 采集中' : ''}`,
        disabled: device.status !== 'device' || device.busy,
      }))} />
    <Button type="primary" icon={<ApiOutlined />} loading={props.active} aria-label={`采集设备 ${props.side}`}
      disabled={props.disabled || !selectedDevice || selectedDevice.status !== 'device' || selectedDevice.busy}
      onClick={props.onStart}>采集</Button>
    <Tooltip title="刷新 ADB 设备"><Button icon={<ReloadOutlined />} aria-label={`刷新设备 ${props.side} 的 ADB 列表`} loading={props.refreshing} onClick={props.onRefresh} /></Tooltip>
    <Tag className="capture-status-tag" color={props.active ? 'processing' : props.loaded ? 'success' : 'default'}>{props.active ? '采集中' : props.loaded ? '已载入' : '等待采集'}</Tag>
  </div>;
}
