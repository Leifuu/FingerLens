import type { ReactNode } from 'react';
import { Collapse, Space, Table, Tag, Typography } from 'antd';
import { displayColorFacts, displayColorRows, type DisplayColorRow } from '../core/display-color-display';
import type { MergedDisplayRow } from '../core/display-compare';
import type { Json } from '../core/types';

export function DisplayColorOverview({ itemKey, sectionKey, left, right, rows, onlyDiffRows, children }: { itemKey: string; sectionKey: string; left?: Json; right?: Json; rows: MergedDisplayRow[]; onlyDiffRows: boolean; children: ReactNode }) {
  if (!itemKey.startsWith('display/') || itemKey === 'display/summary' || sectionKey !== 'color') return children;
  const grouped = displayColorRows(left, right);
  if (!grouped?.length) return children;
  const visible = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const fieldCount = onlyDiffRows ? rows.filter(row => row.state !== 'same').length : rows.length;
  const device = (row: DisplayColorRow, side: 'left' | 'right') => <dl className="display-color-facts">{displayColorFacts(row.key, row[side]).map(fact => <div key={fact.key}><dt>{fact.label}</dt><dd><Typography.Paragraph className="device-value" ellipsis={{ rows: 3, expandable: true, symbol: '展开原文' }}>{fact.value}</Typography.Paragraph></dd></div>)}</dl>;
  return <Space className="display-color-overview-section" direction="vertical" size={12} style={{ display: 'flex' }}>
    <Typography.Text type="secondary">显示色彩能力 · 显示 {visible.length} / {grouped.length} 项。偏好空间为合成管线声明；完整对象决定差异，未知字段可在下方核对。</Typography.Text>
    <Typography.Text className="display-color-scroll-hint" type="secondary">左右滑动查看设备 A、B 与对比状态。</Typography.Text>
    <Table<DisplayColorRow> className="display-color-overview" rowKey="key" size="small" bordered dataSource={visible} pagination={false} scroll={{ x: 800 }} rowClassName={row => `row-${row.state}`} columns={[
      { title: '查询项', key: 'query', width: 170, render: (_, row) => row.label },
      { title: '设备 A · 色彩', key: 'left', width: 260, render: (_, row) => device(row, 'left') },
      { title: '设备 B · 色彩', key: 'right', width: 260, render: (_, row) => device(row, 'right') },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
    <Collapse items={[{ key: 'fields', label: `逐字段详情（${fieldCount} 行）`, children }]} />
  </Space>;
}
