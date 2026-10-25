import type { ReactNode } from 'react';
import { Collapse, Space, Table, Tag, Typography } from 'antd';
import { apexDisplayRows, type ApexDisplayRow } from '../core/apex-display';
import type { MergedDisplayRow } from '../core/display-compare';
import { copyText } from '../core/clipboard';

export function ApexOverview({ itemKey, sectionKey, rows, onlyDiffRows, children }: { itemKey: string; sectionKey: string; rows: MergedDisplayRow[]; onlyDiffRows: boolean; children: ReactNode }) {
  if (itemKey !== 'build/apex-packages' || sectionKey !== 'packages') return children;
  const grouped = apexDisplayRows(rows);
  if (!grouped) return children;
  const visible = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const fieldCount = onlyDiffRows ? rows.filter(row => row.state !== 'same').length : rows.length;
  const text = (value: string | null | undefined) => value === '' ? '（空字符串）' : value ?? '—';
  const name = (value: string | null) => <Typography.Text className="device-value copyable-value" title="双击复制包名" onDoubleClick={() => { if (value !== null) void copyText(value, 'APEX 包名'); }}>{text(value)}</Typography.Text>;
  const version = (row: ApexDisplayRow, side: 'left' | 'right') => <Space direction="vertical" size={0}>
    <Typography.Text className="device-value">{text(row.versionCode[side])}</Typography.Text>
    <Typography.Text type="secondary">版本名 {text(row.versionName?.[side])}</Typography.Text>
  </Space>;
  return <Space className="apex-overview-section" direction="vertical" size={12} style={{ display: 'flex' }}>
    <Typography.Text type="secondary">APEX 包概览 · 显示 {visible.length} / {grouped.length} 项。按原始序号比较，保留重复包名；版本号精确显示，其他字段差异可展开核对。</Typography.Text>
    <Typography.Text className="apex-scroll-hint" type="secondary">左右滑动查看设备 A、B 与对比状态。</Typography.Text>
    <Table<ApexDisplayRow> className="apex-overview" rowKey="key" size="small" bordered dataSource={visible} pagination={visible.length > 20 ? { pageSize: 20, showSizeChanger: false, showQuickJumper: true } : false} scroll={{ x: 900 }} rowClassName={row => `row-${row.state}`} columns={[
      { title: 'APEX 包名（原始序号）', key: 'package', width: 350, render: (_, row) => <Space direction="vertical" size={0}><Typography.Text type="secondary">[{row.key}]</Typography.Text>{row.packageName.left === row.packageName.right ? name(row.packageName.left) : <><div>A · {name(row.packageName.left)}</div><div>B · {name(row.packageName.right)}</div></>}</Space> },
      { title: '设备 A · 版本号', key: 'left', width: 220, render: (_, row) => version(row, 'left') },
      { title: '设备 B · 版本号', key: 'right', width: 220, render: (_, row) => version(row, 'right') },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
    <Collapse items={[{ key: 'fields', label: `逐字段详情（${fieldCount} 行）`, children }]} />
  </Space>;
}
