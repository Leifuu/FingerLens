import type { ReactNode } from 'react';
import { Collapse, Space, Table, Tag, Typography } from 'antd';
import type { Json } from '../core/types';
import type { MergedDisplayRow } from '../core/display-compare';
import { sensorPrivacyFacts, sensorPrivacyRows, type SensorPrivacyRow } from '../core/sensor-privacy-display';

export function SensorPrivacyOverview({ itemKey, sectionKey, left, right, rows, onlyDiffRows, collected, children }: { itemKey: string; sectionKey: string; left?: Json; right?: Json; rows: MergedDisplayRow[]; onlyDiffRows: boolean; collected: { left: boolean; right: boolean }; children: ReactNode }) {
  if (itemKey !== 'security/sensor-privacy-capabilities' || sectionKey !== 'fields') return children;
  const grouped = sensorPrivacyRows(left, right);
  if (!grouped?.length) return children;
  const visible = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const count = onlyDiffRows ? rows.filter(row => row.state !== 'same').length : rows.length;
  const device = (row: SensorPrivacyRow, side: 'left' | 'right') => {
    if (!collected[side]) return <Typography.Text type="secondary">等待采集</Typography.Text>;
    if ((side === 'left' ? left : right) === undefined) return <Typography.Text type="secondary">此侧没有采集项</Typography.Text>;
    return <div className="sensor-privacy-facts">{sensorPrivacyFacts(row[side], row.key === 'metadata').map((line, index) => <Typography.Paragraph key={index} ellipsis={{ rows: 3, expandable: true, symbol: '展开原文' }}>{line}</Typography.Paragraph>)}</div>;
  };
  return <Space className="sensor-privacy-overview-section" direction="vertical" size={12} style={{ display: 'flex' }}>
    <Typography.Text type="secondary">隐私开关的支持能力；当前开关状态、摄像头和麦克风是否可用需另行确认。</Typography.Text>
    <Typography.Text className="sensor-privacy-scroll-hint" type="secondary">左右滑动查看设备 A、B 与对比状态。</Typography.Text>
    <Table<SensorPrivacyRow> className="sensor-privacy-overview" rowKey="key" size="small" bordered dataSource={visible} pagination={false} scroll={{ x: 840 }} rowClassName={row => `row-${row.state}`} locale={{ emptyText: '当前能力与元数据没有差异。' }} columns={[
      { title: '支持能力', dataIndex: 'label', key: 'label', width: 190 },
      { title: '设备 A', key: 'left', width: 270, render: (_, row) => device(row, 'left') },
      { title: '设备 B', key: 'right', width: 270, render: (_, row) => device(row, 'right') },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
    <Collapse items={[{ key: 'fields', label: `逐字段详情（${count} 行）`, children }]} />
  </Space>;
}
