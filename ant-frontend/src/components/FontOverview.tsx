import type { ReactNode } from 'react';
import { Collapse, Space, Table, Tag, Typography } from 'antd';
import { fontDisplayRows, type FontDisplayRow } from '../core/font-display';
import type { MergedDisplayRow } from '../core/display-compare';
import { copyText } from '../core/clipboard';

export function FontOverview({ itemKey, sectionKey, rows, onlyDiffRows, children }: { itemKey: string; sectionKey: string; rows: MergedDisplayRow[]; onlyDiffRows: boolean; children: ReactNode }) {
  if (itemKey !== 'environment/system-fonts' || sectionKey !== 'entries') return children;
  const grouped = fontDisplayRows(rows);
  if (!grouped) return children;
  const visible = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const fieldCount = onlyDiffRows ? rows.filter(row => row.state !== 'same').length : rows.length;
  const text = (value: string | null | undefined) => value === '' ? '（空字符串）' : value ?? '—';
  const identity = (row: FontDisplayRow, side: 'left' | 'right') => <Space direction="vertical" size={0}>
    <Typography.Text className="device-value copyable-value" title="双击复制文件名" onDoubleClick={() => { const value = row.fileName[side]; if (value !== null) void copyText(value, '字体文件名'); }}>{text(row.fileName[side])}</Typography.Text>
    <Typography.Text type="secondary">TTC 索引 {text(row.ttcIndex[side])}</Typography.Text>
  </Space>;
  const metadata = (row: FontDisplayRow, side: 'left' | 'right') => {
    const slant = row.slant?.[side];
    return <Space direction="vertical" size={0}>
      <Typography.Text>字重 {text(row.weight?.[side])} · 倾斜 {slant === '0' ? '0（直立）' : slant === '1' ? '1（斜体）' : text(slant)}</Typography.Text>
      <Typography.Text type="secondary">语言 {text(row.localeTags?.[side])}</Typography.Text>
      <Typography.Text type="secondary">变体 {text(row.variationSettings?.[side])}</Typography.Text>
    </Space>;
  };
  return <Space className="font-overview-section" direction="vertical" size={12} style={{ display: 'flex' }}>
    <Typography.Text type="secondary">字体概览 · 显示 {visible.length} / {grouped.length} 项。按原始序号比较，保留同名字体；完整字段可在下方展开。</Typography.Text>
    <Typography.Text className="font-scroll-hint" type="secondary">左右滑动查看设备 A、B 与对比状态。</Typography.Text>
    <Table<FontDisplayRow> className="font-overview" rowKey="key" size="small" bordered dataSource={visible} pagination={visible.length > 50 ? { pageSize: 50, showSizeChanger: false, showQuickJumper: true } : false} scroll={{ x: 900 }} rowClassName={row => `row-${row.state}`} columns={[
      { title: '字体文件与 TTC（原始序号）', key: 'font', width: 310, render: (_, row) => <Space direction="vertical" size={0}><Typography.Text type="secondary">[{row.key}]</Typography.Text>{row.fileName.left === row.fileName.right && row.ttcIndex.left === row.ttcIndex.right ? identity(row, 'left') : <><div>A · {identity(row, 'left')}</div><div>B · {identity(row, 'right')}</div></>}</Space> },
      { title: '设备 A', key: 'left', width: 240, render: (_, row) => metadata(row, 'left') },
      { title: '设备 B', key: 'right', width: 240, render: (_, row) => metadata(row, 'right') },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
    <Collapse items={[{ key: 'fields', label: `逐字段详情（${fieldCount} 行）`, children }]} />
  </Space>;
}
