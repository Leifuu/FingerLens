import type { ReactNode } from 'react';
import { Collapse, Space, Table, Tag, Typography } from 'antd';
import type { Json } from '../core/types';
import type { MergedDisplayRow } from '../core/display-compare';
import { thermalThresholdFacts, thermalThresholdRows, type ThermalThresholdRow } from '../core/thermal-threshold-display';

export function ThermalThresholdOverview({ itemKey, sectionKey, left, right, rows, onlyDiffRows, collected, children }: { itemKey: string; sectionKey: string; left?: Json; right?: Json; rows: MergedDisplayRow[]; onlyDiffRows: boolean; collected: { left: boolean; right: boolean }; children: ReactNode }) {
  if (itemKey !== 'environment/thermal-headroom-thresholds' || sectionKey !== 'fields') return children;
  const grouped = thermalThresholdRows(left, right);
  if (!grouped?.length) return children;
  const visible = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const count = onlyDiffRows ? rows.filter(row => row.state !== 'same').length : rows.length;
  const device = (row: ThermalThresholdRow, side: 'left' | 'right') => {
    if (!collected[side]) return <Typography.Text type="secondary">等待采集</Typography.Text>;
    if ((side === 'left' ? left : right) === undefined) return <Typography.Text type="secondary">此侧没有采集项</Typography.Text>;
    return <div className="thermal-threshold-facts">{thermalThresholdFacts(row[side], row.key === 'metadata').map((line, index) => <Typography.Paragraph key={index} ellipsis={{ rows: 3, expandable: true, symbol: '展开原文' }}>{line}</Typography.Paragraph>)}</div>;
  };
  return <Space className="thermal-threshold-overview-section" direction="vertical" size={12} style={{ display: 'flex' }}>
    <Typography.Text type="secondary">按原始序号对照热状态编号与归一化阈值。此处为平台阈值快照；数值不是摄氏温度，不能直接推导当前热状态。</Typography.Text>
    <Typography.Text className="thermal-threshold-scroll-hint" type="secondary">左右滑动查看设备 A、B 与对比状态。</Typography.Text>
    <Table<ThermalThresholdRow> key={String(onlyDiffRows)} className="thermal-threshold-overview" rowKey="key" size="small" bordered dataSource={visible} pagination={visible.length > 10 ? { pageSize: 10, showSizeChanger: false } : false} scroll={{ x: 820 }} rowClassName={row => `row-${row.state}`} locale={{ emptyText: '当前查询与阈值没有差异。' }} columns={[
      { title: '原始序号', dataIndex: 'label', key: 'label', width: 170 },
      { title: '设备 A', key: 'left', width: 270, render: (_, row) => device(row, 'left') },
      { title: '设备 B', key: 'right', width: 270, render: (_, row) => device(row, 'right') },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
    <Collapse items={[{ key: 'fields', label: `逐字段详情（${count} 行）`, children }]} />
  </Space>;
}
