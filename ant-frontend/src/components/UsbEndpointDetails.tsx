import { Table, Tag, Typography } from 'antd';
import { usbEndpointFacts, usbEndpointRows } from '../core/usb-display';
import type { UsbDisplayRow } from '../core/usb-display';
import type { Json } from '../core/types';

export function UsbEndpointDetails({ left, right, onlyDiffRows, collected }: { left?: Record<string, Json>; right?: Record<string, Json>; onlyDiffRows: boolean; collected: { left: boolean; right: boolean } }) {
  const grouped = usbEndpointRows(left, right), rows = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const device = (value: Record<string, Json> | undefined, side: 'left' | 'right') => !value ? <Typography.Text type="secondary">{collected[side] ? '此侧未返回该端点' : '等待采集'}</Typography.Text> : <div className="usb-facts">{usbEndpointFacts(value).map((line, index) => <Typography.Paragraph key={index} ellipsis={{ rows: 2, expandable: true, symbol: '展开原文' }}>{line}</Typography.Paragraph>)}</div>;
  return <div className="usb-endpoint-details">
    <Typography.Paragraph type="secondary">端点 · 显示 {rows.length} / {grouped.length} 项。方向相对 USB host；interval 保留原值，不统一换算毫秒。</Typography.Paragraph>
    <Table<UsbDisplayRow> className="usb-endpoint-table" rowKey="key" size="small" bordered dataSource={rows} pagination={rows.length > 10 ? { pageSize: 10, showSizeChanger: false } : false} scroll={{ x: 600 }} locale={{ emptyText: onlyDiffRows ? '端点明细无差异；查询状态、哈希或未知字段请核对接口摘要与完整字段。' : '没有返回端点明细' }} rowClassName={row => `row-${row.state}`} columns={[
      { title: '端点序号', key: 'index', width: 90, render: (_, row) => `[${row.key}]` },
      { title: '设备 A · 端点', key: 'left', width: 200, render: (_, row) => device(row.left, 'left') },
      { title: '设备 B · 端点', key: 'right', width: 200, render: (_, row) => device(row.right, 'right') },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
  </div>;
}
