import type { ReactNode } from 'react';
import { Collapse, Space, Table, Tag, Typography } from 'antd';
import type { Json } from '../core/types';
import type { MergedDisplayRow } from '../core/display-compare';
import { vibratorDisplayRows, type VibratorDisplayRow } from '../core/vibrator-display';

export function VibratorOverview({ itemKey, sectionKey, left, right, rows, onlyDiffRows, children }: { itemKey: string; sectionKey: string; left?: Json; right?: Json; rows: MergedDisplayRow[]; onlyDiffRows: boolean; children: ReactNode }) {
  if (itemKey !== 'hardware/vibrator' || sectionKey !== 'vibrators') return children;
  const grouped = vibratorDisplayRows(left, right);
  if (!grouped) return children;
  const visible = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const fieldCount = onlyDiffRows ? rows.filter(row => row.state !== 'same').length : rows.length;
  const device = (facts: VibratorDisplayRow['left']) => !facts ? <Typography.Text type="secondary">此侧没有振动器</Typography.Text> : <div className="vibrator-facts">
    <Typography.Text strong>振动器 ID {facts.id}</Typography.Text>
    {facts.captured ? <>
      <Typography.Text type="secondary">支持查询：{facts.supportStatus} · 时长查询：{facts.durationStatus}</Typography.Text>
      {facts.primitives.length ? <dl className="vibrator-primitives">{facts.primitives.map((primitive, index) => <div key={index}>
        <dt>{primitive.name}</dt><dd>{primitive.support} · {primitive.duration}</dd>
      </div>)}</dl> : <Typography.Text>基础单元列表为空（[]）</Typography.Text>}
    </> : <Typography.Text type="secondary">未采集基础单元</Typography.Text>}
  </div>;
  return <Space className="vibrator-overview-section" direction="vertical" size={12} style={{ display: 'flex' }}>
    <Typography.Text type="secondary">基础振动单元 · 显示 {visible.length} / {grouped.length} 个振动器。按原始序号保留重复项；时长为平台估算值。完整振动器字段参与差异判定，可在下方展开核对。</Typography.Text>
    <Typography.Text className="vibrator-scroll-hint" type="secondary">左右滑动查看设备 A、B 与对比状态。</Typography.Text>
    <Table<VibratorDisplayRow> className="vibrator-overview" rowKey="key" size="small" bordered dataSource={visible} pagination={visible.length > 5 ? { pageSize: 5, showSizeChanger: false, showQuickJumper: true } : false} scroll={{ x: 810 }} rowClassName={row => `row-${row.state}`} columns={[
      { title: '原始序号', key: 'index', width: 100, render: (_, row) => <Typography.Text type="secondary">[{row.key}]</Typography.Text> },
      { title: '设备 A · 基础单元', key: 'left', width: 300, render: (_, row) => device(row.left) },
      { title: '设备 B · 基础单元', key: 'right', width: 300, render: (_, row) => device(row.right) },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
    <Collapse items={[{ key: 'fields', label: `逐字段详情（${fieldCount} 行）`, children }]} />
  </Space>;
}
