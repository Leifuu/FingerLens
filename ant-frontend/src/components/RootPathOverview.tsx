import type { ReactNode } from 'react';
import { Collapse, Space, Table, Tag, Typography } from 'antd';
import { rootPathDisplayRows, type RootPathDisplayRow } from '../core/root-path-display';
import type { MergedDisplayRow } from '../core/display-compare';
import { copyText } from '../core/clipboard';

export function RootPathOverview({ itemKey, sectionKey, rows, onlyDiffRows, children }: { itemKey: string; sectionKey: string; rows: MergedDisplayRow[]; onlyDiffRows: boolean; children: ReactNode }) {
  if (itemKey !== 'security/root-path-summary' || sectionKey !== 'entries') return children;
  const grouped = rootPathDisplayRows(rows);
  if (!grouped) return children;
  const visible = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const fieldCount = onlyDiffRows ? rows.filter(row => row.state !== 'same').length : rows.length;
  const pathText = (value: string | null) => <Typography.Text className="device-value copyable-value" title="双击复制路径" onDoubleClick={() => { if (value !== null) void copyText(value, '路径'); }}>{value ?? '—'}</Typography.Text>;
  const observation = (row: RootPathDisplayRow, side: 'left' | 'right') => <Space direction="vertical" size={0}>
    <Typography.Text>{row.status?.[side] ?? '—'}</Typography.Text>
    <Typography.Text type="secondary">errno {row.errno?.[side] ?? '—'} · 类型 {row.type?.[side] ?? '—'}</Typography.Text>
  </Space>;
  return <Space direction="vertical" size={12} style={{ display: 'flex' }}>
    <Typography.Text type="secondary">路径概览 · 显示 {visible.length} / {grouped.length} 项。保留原始顺序与重复路径，完整字段可在下方展开。</Typography.Text>
    <Typography.Text className="root-path-scroll-hint" type="secondary">左右滑动查看设备 A、B 与对比状态。</Typography.Text>
    <Table<RootPathDisplayRow> className="root-path-overview" rowKey="key" size="small" bordered dataSource={visible} pagination={visible.length > 50 ? { pageSize: 50, showSizeChanger: false } : false} scroll={{ x: 760 }} rowClassName={row => `row-${row.state}`} columns={[
      { title: '路径（原始序号）', key: 'path', width: 340, render: (_, row) => <Space direction="vertical" size={0}><Typography.Text type="secondary">[{row.key}]</Typography.Text>{row.path.left === row.path.right ? pathText(row.path.left) : <><span>A · {pathText(row.path.left)}</span><span>B · {pathText(row.path.right)}</span></>}</Space> },
      { title: '设备 A', key: 'left', render: (_, row) => observation(row, 'left') },
      { title: '设备 B', key: 'right', render: (_, row) => observation(row, 'right') },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
    <Collapse items={[{ key: 'fields', label: `逐字段详情（${fieldCount} 行）`, children }]} />
  </Space>;
}
