import { Button, Table, Tag, Typography } from 'antd';
import { usbEndpointRows, usbInterfaceFacts, usbInterfaceRows } from '../core/usb-display';
import type { UsbDisplayRow } from '../core/usb-display';
import type { Json } from '../core/types';
import { UsbEndpointDetails } from './UsbEndpointDetails';

export function UsbInterfaceDetails({ left, right, onlyDiffRows, collected }: { left?: Record<string, Json>; right?: Record<string, Json>; onlyDiffRows: boolean; collected: { left: boolean; right: boolean } }) {
  const grouped = usbInterfaceRows(left, right), rows = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const device = (value: Record<string, Json> | undefined, side: 'left' | 'right') => !value ? <Typography.Text type="secondary">{collected[side] ? '此侧没有该接口' : '等待采集'}</Typography.Text> : <div className="usb-facts">{usbInterfaceFacts(value).map((line, index) => <Typography.Paragraph key={index} ellipsis={{ rows: 2, expandable: true, symbol: '展开原文' }}>{line}</Typography.Paragraph>)}</div>;
  const hasEndpoints = (row: UsbDisplayRow) => usbEndpointRows(row.left, row.right).length > 0;
  return <div className="usb-interface-details">
    <Typography.Paragraph type="secondary">接口 · 显示 {rows.length} / {grouped.length} 项。按原始序号保留重复；失败、空列表、旧报告未采集分别展示。</Typography.Paragraph>
    <Table<UsbDisplayRow> className="usb-interface-table" rowKey="key" size="small" bordered dataSource={rows} pagination={rows.length > 5 ? { pageSize: 5, showSizeChanger: false } : false} scroll={{ x: 880 }} locale={{ emptyText: onlyDiffRows ? '接口明细无差异；设备或其它字段变化可展开完整字段核对。' : '没有接口明细' }} rowClassName={row => `row-${row.state}`} expandable={{
      expandedRowRender: row => <UsbEndpointDetails left={row.left} right={row.right} onlyDiffRows={onlyDiffRows} collected={collected} />,
      rowExpandable: hasEndpoints,
      expandIcon: ({ expanded, onExpand, record, expandable }) => expandable ? <Button type="link" size="small" aria-expanded={expanded} onClick={event => onExpand(record, event)}>{expanded ? '收起' : '展开'}端点 [{record.key}]</Button> : null,
      columnWidth: 140,
    }} columns={[
      { title: '接口序号', key: 'index', width: 90, render: (_, row) => `[${row.key}]` },
      { title: '设备 A · 接口', key: 'left', width: 270, render: (_, row) => device(row.left, 'left') },
      { title: '设备 B · 接口', key: 'right', width: 270, render: (_, row) => device(row.right, 'right') },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
  </div>;
}
