import type { ReactNode } from 'react';
import { Collapse, Space, Table, Tag, Typography } from 'antd';
import type { Json } from '../core/types';
import type { MergedDisplayRow } from '../core/display-compare';
import { securityStateRows, securityStateText, type SecurityStateRow } from '../core/security-state-display';
import { copyText } from '../core/clipboard';

export function SecurityStateOverview({ itemKey, sectionKey, left, right, rows, onlyDiffRows, children }: { itemKey: string; sectionKey: string; left?: Json; right?: Json; rows: MergedDisplayRow[]; onlyDiffRows: boolean; children: ReactNode }) {
  if (itemKey !== 'security/platform-security-state' || sectionKey !== 'entries') return children;
  const grouped = securityStateRows(left, right);
  if (!grouped) return children;
  const visible = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const fieldCount = onlyDiffRows ? rows.filter(row => row.state !== 'same').length : rows.length;
  const entry = (row: SecurityStateRow, side: 'left' | 'right') => {
    const value = row[side];
    if (!value) return <Typography.Text type="secondary">{(side === 'left' ? left : right) === undefined ? '此侧没有采集项' : '此侧未返回该键'}</Typography.Text>;
    return <Space direction="vertical" size={0}>
      <Typography.Paragraph className="device-value copyable-value security-state-value" title="双击复制原始值" onDoubleClick={() => { void copyText(value.value === null ? 'null' : value.value, row.name); }} ellipsis={{ rows: 3, expandable: true, symbol: '展开' }}>{securityStateText(value.value)}</Typography.Paragraph>
      <Typography.Text type="secondary">原始序号 [{value.index}]</Typography.Text>
    </Space>;
  };
  return <Space className="security-state-overview-section" direction="vertical" size={12} style={{ display: 'flex' }}>
    <Typography.Text type="secondary">补丁与组件版本 · 显示 {visible.length} / {grouped.length} 项。按原始键及重复序号配对，位置与未知字段变化仍标记差异；平台空字符串不代表组件已安装。</Typography.Text>
    <Typography.Text className="security-state-scroll-hint" type="secondary">左右滑动查看设备 A、B 与对比状态。</Typography.Text>
    <Table<SecurityStateRow> className="security-state-overview" rowKey="key" size="small" bordered dataSource={visible} pagination={visible.length > 20 ? { pageSize: 20, showSizeChanger: false, showQuickJumper: true } : false} scroll={{ x: 810 }} rowClassName={row => `row-${row.state}`} columns={[
      { title: '补丁 / 组件键', key: 'name', width: 240, render: (_, row) => <Space direction="vertical" size={0}><Typography.Text>{row.label}</Typography.Text><Typography.Text className="device-value security-state-key">{row.name === '' ? '（空字符串）' : row.name}</Typography.Text></Space> },
      { title: '设备 A', key: 'left', width: 230, render: (_, row) => entry(row, 'left') },
      { title: '设备 B', key: 'right', width: 230, render: (_, row) => entry(row, 'right') },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
    <Collapse items={[{ key: 'fields', label: `逐字段详情（${fieldCount} 行）`, children }]} />
  </Space>;
}
