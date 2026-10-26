import type { ReactNode } from 'react';
import { Button, Collapse, Space, Table, Tag, Typography } from 'antd';
import type { Json } from '../core/types';
import type { MergedDisplayRow } from '../core/display-compare';
import { usbDeviceFacts, usbDeviceRows, usbInterfaceRows, usbRootFacts } from '../core/usb-display';
import type { UsbDisplayRow } from '../core/usb-display';
import { UsbInterfaceDetails } from './UsbInterfaceDetails';

export function UsbOverview({ itemKey, sectionKey, left, right, rows, onlyDiffRows, collected, children }: { itemKey: string; sectionKey: string; left?: Json; right?: Json; rows: MergedDisplayRow[]; onlyDiffRows: boolean; collected: { left: boolean; right: boolean }; children: ReactNode }) {
  if (itemKey !== 'hardware/usb' || sectionKey !== 'descriptors') return children;
  const grouped = usbDeviceRows(left, right);
  if (!grouped) return children;
  const visible = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const fieldCount = onlyDiffRows ? rows.filter(row => row.state !== 'same').length : rows.length;
  const device = (value: Record<string, Json> | undefined, side: 'left' | 'right') => !value ? <Typography.Text type="secondary">{collected[side] ? '此侧没有该USB设备' : '等待采集'}</Typography.Text> : <div className="usb-facts">{usbDeviceFacts(value).map((line, index) => <Typography.Paragraph key={index} ellipsis={{ rows: 2, expandable: true, symbol: '展开原文' }}>{line}</Typography.Paragraph>)}</div>;
  return <Space className="usb-overview-section" direction="vertical" size={12} style={{ display: 'flex' }}>
    <Typography.Text type="secondary">USB 设备 · 显示 {visible.length} / {grouped.length} 项。按原始序号对照，不合并相同 ID；描述符不代表传输成功、实际吞吐量或永久设备身份。</Typography.Text>
    {(['left', 'right'] as const).map((side, index) => <Typography.Paragraph key={side} className="usb-root-summary" type="secondary">设备 {index ? 'B' : 'A'} · {collected[side] ? usbRootFacts(side === 'left' ? left : right) : '等待采集'}</Typography.Paragraph>)}
    <Typography.Text className="usb-scroll-hint" type="secondary">左右滑动查看设备 A、B；接口和端点可分别展开，完整字段保留在下方。</Typography.Text>
    <Table<UsbDisplayRow> className="usb-overview" rowKey="key" size="small" bordered dataSource={visible} pagination={visible.length > 5 ? { pageSize: 5, showSizeChanger: false } : false} scroll={{ x: 880 }} locale={{ emptyText: onlyDiffRows ? '设备明细无差异；总数或其它字段变化可展开完整字段核对。' : '没有USB设备明细；空列表不等于不支持USB，也不等于ADB未连接。' }} rowClassName={row => `row-${row.state}`} expandable={{
      expandedRowRender: row => <UsbInterfaceDetails left={row.left} right={row.right} onlyDiffRows={onlyDiffRows} collected={collected} />,
      rowExpandable: row => usbInterfaceRows(row.left, row.right).length > 0,
      expandIcon: ({ expanded, onExpand, record, expandable }) => expandable ? <Button type="link" size="small" aria-expanded={expanded} onClick={event => onExpand(record, event)}>{expanded ? '收起' : '展开'}接口 [{record.key}]</Button> : null,
      columnWidth: 140,
    }} columns={[
      { title: '原始序号', key: 'index', width: 90, render: (_, row) => `[${row.key}]` },
      { title: '设备 A', key: 'left', width: 270, render: (_, row) => device(row.left, 'left') },
      { title: '设备 B', key: 'right', width: 270, render: (_, row) => device(row.right, 'right') },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
    <Collapse items={[{ key: 'fields', label: `逐字段详情（${fieldCount} 行）`, children }]} />
  </Space>;
}
