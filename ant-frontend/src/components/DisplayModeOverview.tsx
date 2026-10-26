import type { ReactNode } from 'react';
import { Collapse, Space, Table, Tag, Typography } from 'antd';
import { displayModeRows, type DisplayModeRow } from '../core/display-mode-display';
import type { MergedDisplayRow } from '../core/display-compare';

export function DisplayModeOverview({ itemKey, sectionKey, rows, onlyDiffRows, children }: { itemKey: string; sectionKey: string; rows: MergedDisplayRow[]; onlyDiffRows: boolean; children: ReactNode }) {
  if (!itemKey.startsWith('display/') || sectionKey !== 'modes') return children;
  const grouped = displayModeRows(rows);
  if (!grouped) return children;
  const visible = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const fieldCount = onlyDiffRows ? rows.filter(row => row.state !== 'same').length : rows.length;
  const text = (value: string | null) => value === '' ? '（空字符串）' : value ?? '—';
  const mode = (row: DisplayModeRow, side: 'left' | 'right') => row.modeId[side] === null ? <Typography.Text type="secondary">此侧没有模式</Typography.Text> : <Space direction="vertical" size={0}>
    <Typography.Text className="device-value">{text(row.width[side])} × {text(row.height[side])} px · {text(row.refreshRate[side])} Hz</Typography.Text>
    <Typography.Text type="secondary">模式 ID {text(row.modeId[side])}</Typography.Text>
    <Typography.Text>HDR：{row.hdr[side]}</Typography.Text>
  </Space>;
  return <Space className="display-mode-overview-section" direction="vertical" size={12} style={{ display: 'flex' }}>
    <Typography.Text type="secondary">显示模式概览 · 显示 {visible.length} / {grouped.length} 项。按原始序号比较，保留重复模式；完整字段可在下方展开。</Typography.Text>
    <Typography.Text className="display-mode-scroll-hint" type="secondary">左右滑动查看设备 A、B 与对比状态。</Typography.Text>
    <Table<DisplayModeRow> className="display-mode-overview" rowKey="key" size="small" bordered dataSource={visible} pagination={visible.length > 20 ? { pageSize: 20, showSizeChanger: false, showQuickJumper: true } : false} scroll={{ x: 900 }} rowClassName={row => `row-${row.state}`} columns={[
      { title: '原始序号', key: 'index', width: 100, render: (_, row) => <Typography.Text type="secondary">[{row.key}]</Typography.Text> },
      { title: '设备 A', key: 'left', width: 345, render: (_, row) => mode(row, 'left') },
      { title: '设备 B', key: 'right', width: 345, render: (_, row) => mode(row, 'right') },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
    <Collapse items={[{ key: 'fields', label: `逐字段详情（${fieldCount} 行）`, children }]} />
  </Space>;
}
