import type { ReactNode } from 'react';
import { Collapse, Space, Table, Tag, Typography } from 'antd';
import type { MergedDisplayRow } from '../core/display-compare';
import { tlsDisplayRows } from '../core/tls-display';

export function TlsOverview({ itemKey, sectionKey, rows, onlyDiffRows, children }: { itemKey: string; sectionKey: string; rows: MergedDisplayRow[]; onlyDiffRows: boolean; children: ReactNode }) {
  if (itemKey !== 'security/tls-parameters' || sectionKey !== 'fields') return children;
  const grouped = tlsDisplayRows(rows);
  if (!grouped) return children;
  const visible = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const fieldCount = onlyDiffRows ? rows.filter(row => row.state !== 'same').length : rows.length;
  const value = (text: string | null) => text === '' ? '（空字符串）' : text ?? '—';
  return <Space className="tls-overview-section" direction="vertical" size={12} style={{ display: 'flex' }}>
    <Typography.Text type="secondary">列表数量与截断状态概览；完整哈希和其他元数据仍参与差异判断，可展开逐字段详情查看。协议和密码套件原文保留在下方各组。</Typography.Text>
    <Typography.Text className="tls-scroll-hint" type="secondary">左右滑动查看设备 A、B 与对比状态。</Typography.Text>
    <Table<MergedDisplayRow> className="tls-overview" rowKey="key" size="small" bordered pagination={false} dataSource={visible} scroll={{ x: 760 }} rowClassName={row => `row-${row.state}`} columns={[
      { title: '参数', dataIndex: 'label', key: 'label', width: 220 },
      { title: '设备 A', dataIndex: 'left', key: 'left', render: text => <Typography.Text className="device-value">{value(text)}</Typography.Text> },
      { title: '设备 B', dataIndex: 'right', key: 'right', render: text => <Typography.Text className="device-value">{value(text)}</Typography.Text> },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
    <Collapse items={[{ key: 'fields', label: `逐字段详情（${fieldCount} 行）`, children }]} />
  </Space>;
}
