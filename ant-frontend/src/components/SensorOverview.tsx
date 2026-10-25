import type { ReactNode } from 'react';
import { Collapse, Space, Table, Tag, Typography } from 'antd';
import { sensorDisplayRows, type SensorDisplayRow } from '../core/sensor-display';
import type { MergedDisplayRow } from '../core/display-compare';
import { copyText } from '../core/clipboard';

export function SensorOverview({ itemKey, sectionKey, rows, onlyDiffRows, children }: { itemKey: string; sectionKey: string; rows: MergedDisplayRow[]; onlyDiffRows: boolean; children: ReactNode }) {
  if (itemKey !== 'sensors/list' || sectionKey !== 'array') return children;
  const grouped = sensorDisplayRows(rows);
  if (!grouped) return children;
  const visible = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const fieldCount = onlyDiffRows ? rows.filter(row => row.state !== 'same').length : rows.length;
  const text = (value: string | null | undefined) => value === '' ? '（空字符串）' : value ?? '—';
  const flag = (value: string | null | undefined) => value === 'true' ? '是' : value === 'false' ? '否' : text(value);
  const identity = (row: SensorDisplayRow, side: 'left' | 'right') => <Space direction="vertical" size={0}>
    <Typography.Text className="device-value copyable-value" title="双击复制传感器名" onDoubleClick={() => { const value = row.name[side]; if (value !== null) void copyText(value, '传感器名'); }}>{text(row.name[side])}</Typography.Text>
    <Typography.Text type="secondary">厂商 {text(row.vendor[side])}</Typography.Text>
  </Space>;
  const metadata = (row: SensorDisplayRow, side: 'left' | 'right') => <Space direction="vertical" size={0}>
    <Typography.Text>类型 {text(row.type[side])} · 版本 {text(row.version?.[side])}</Typography.Text>
    <Typography.Text className="device-value" type="secondary">{text(row.stringType?.[side])}</Typography.Text>
    <Typography.Text>分辨率 {text(row.resolution?.[side])} · 量程 {text(row.maximumRange?.[side])}</Typography.Text>
    <Typography.Text type="secondary">功耗（mA）{text(row.power?.[side])} · 上报模式 {text(row.reportingMode?.[side])}</Typography.Text>
    <Typography.Text type="secondary">唤醒 {flag(row.wakeUp?.[side])} · 动态 {flag(row.dynamic?.[side])}</Typography.Text>
  </Space>;
  return <Space className="sensor-overview-section" direction="vertical" size={12} style={{ display: 'flex' }}>
    <Typography.Text type="secondary">传感器概览 · 显示 {visible.length} / {grouped.length} 项。按原始序号比较，保留重复项；完整字段可在下方展开。</Typography.Text>
    <Typography.Text className="sensor-scroll-hint" type="secondary">左右滑动查看设备 A、B 与对比状态。</Typography.Text>
    <Table<SensorDisplayRow> className="sensor-overview" rowKey="key" size="small" bordered dataSource={visible} pagination={visible.length > 20 ? { pageSize: 20, showSizeChanger: false, showQuickJumper: true } : false} scroll={{ x: 940 }} rowClassName={row => `row-${row.state}`} columns={[
      { title: '传感器（原始序号）', key: 'sensor', width: 310, render: (_, row) => <Space direction="vertical" size={0}><Typography.Text type="secondary">[{row.key}]</Typography.Text>{row.name.left === row.name.right && row.vendor.left === row.vendor.right ? identity(row, 'left') : <><div>A · {identity(row, 'left')}</div><div>B · {identity(row, 'right')}</div></>}</Space> },
      { title: '设备 A', key: 'left', width: 260, render: (_, row) => metadata(row, 'left') },
      { title: '设备 B', key: 'right', width: 260, render: (_, row) => metadata(row, 'right') },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
    <Collapse items={[{ key: 'fields', label: `逐字段详情（${fieldCount} 行）`, children }]} />
  </Space>;
}
