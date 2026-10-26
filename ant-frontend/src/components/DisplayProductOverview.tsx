import type { ReactNode } from 'react';
import { Collapse, Space, Table, Tag, Typography } from 'antd';
import { displayProductFacts, displayProductRows, type DisplayProductRow } from '../core/display-product-display';
import type { MergedDisplayRow } from '../core/display-compare';
import type { Json } from '../core/types';

export function DisplayProductOverview({ itemKey, sectionKey, left, right, rows, onlyDiffRows, children }: { itemKey: string; sectionKey: string; left?: Json; right?: Json; rows: MergedDisplayRow[]; onlyDiffRows: boolean; children: ReactNode }) {
  if (!itemKey.startsWith('display/') || itemKey === 'display/summary' || sectionKey !== 'product') return children;
  const grouped = displayProductRows(left, right);
  if (!grouped?.length) return children;
  const visible = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const fieldCount = onlyDiffRows ? rows.filter(row => row.state !== 'same').length : rows.length;
  const device = (query: Json | undefined) => <dl className="display-product-facts">{displayProductFacts(query).map(fact => <div key={fact.key}><dt>{fact.label}</dt><dd><Typography.Paragraph className="device-value" ellipsis={{ rows: 3, expandable: true, symbol: '展开原文' }}>{fact.value}</Typography.Paragraph></dd></div>)}</dl>;
  return <Space className="display-product-overview-section" direction="vertical" size={12} style={{ display: 'flex' }}>
    <Typography.Text type="secondary">平台返回的显示链路产品信息，可能属于中间设备；年份与制造周不作为实际生产日期证明。</Typography.Text>
    <Typography.Text className="display-product-scroll-hint" type="secondary">左右滑动查看设备 A、B 与对比状态。</Typography.Text>
    <Table<DisplayProductRow> className="display-product-overview" rowKey="key" size="small" bordered dataSource={visible} pagination={false} scroll={{ x: 710 }} rowClassName={row => `row-${row.state}`} columns={[
      { title: '设备 A · 产品', key: 'left', width: 300, render: (_, row) => device(row.left) },
      { title: '设备 B · 产品', key: 'right', width: 300, render: (_, row) => device(row.right) },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
    <Collapse items={[{ key: 'fields', label: `逐字段详情（${fieldCount} 行）`, children }]} />
  </Space>;
}
