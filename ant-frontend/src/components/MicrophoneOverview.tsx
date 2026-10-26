import type { ReactNode } from 'react';
import { Button, Collapse, Space, Table, Tag, Typography } from 'antd';
import type { Json } from '../core/types';
import type { MergedDisplayRow } from '../core/display-compare';
import { microphoneDisplayRows, type MicrophoneFacts, type MicrophoneDisplayRow } from '../core/microphone-display';
import { MicrophoneResponseDetails } from './MicrophoneResponseDetails';

export function MicrophoneOverview({ itemKey, sectionKey, left, right, rows, onlyDiffRows, children }: { itemKey: string; sectionKey: string; left?: Json; right?: Json; rows: MergedDisplayRow[]; onlyDiffRows: boolean; children: ReactNode }) {
  if (itemKey !== 'hardware/microphones' || sectionKey !== 'microphones') return children;
  const grouped = microphoneDisplayRows(left, right);
  if (!grouped) return children;
  const visible = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const fieldCount = onlyDiffRows ? rows.filter(row => row.state !== 'same').length : rows.length;
  const device = (facts?: MicrophoneFacts) => !facts ? <Typography.Text type="secondary">此侧没有麦克风条目</Typography.Text> : <div className="microphone-facts">
    <Typography.Text strong className="microphone-identity">{facts.identity}</Typography.Text>
    <Typography.Paragraph className="microphone-grouping" ellipsis={{ rows: 2, expandable: true, symbol: '展开原文' }}>{facts.grouping}</Typography.Paragraph>
    <Typography.Paragraph className="microphone-calibration" ellipsis={{ rows: 4, expandable: true, symbol: '展开原文' }}>校准：{facts.calibration}</Typography.Paragraph>
    <Typography.Paragraph className="microphone-position" ellipsis={{ rows: 2, expandable: true, symbol: '展开原文' }}>位置 m：{facts.position}</Typography.Paragraph>
    <Typography.Paragraph className="microphone-orientation" ellipsis={{ rows: 2, expandable: true, symbol: '展开原文' }}>朝向：{facts.orientation}</Typography.Paragraph>
    <Typography.Paragraph className="microphone-response" ellipsis={{ rows: 2, expandable: true, symbol: '展开原文' }}>频响：{facts.response}</Typography.Paragraph>
  </div>;
  return <Space className="microphone-overview-section" direction="vertical" size={12} style={{ display: 'flex' }}>
    <Typography.Text type="secondary">麦克风元数据 · 显示 {visible.length} / {grouped.length} 项。保留原始序号与重复项，未知哨兵和读取失败分别展示；集合哈希可在上方核对。</Typography.Text>
    <Typography.Text className="microphone-scroll-hint" type="secondary">左右滑动查看设备 A、B 与对比状态。</Typography.Text>
    <Table<MicrophoneDisplayRow> className="microphone-overview" rowKey="key" size="small" bordered dataSource={visible} pagination={visible.length > 5 ? { pageSize: 5, showSizeChanger: false, showQuickJumper: true } : false} scroll={{ x: 860 }} rowClassName={row => `row-${row.state}`} expandable={grouped.some(row => row.left?.points?.length || row.right?.points?.length) ? {
      expandedRowRender: row => <MicrophoneResponseDetails left={row.left} right={row.right} onlyDiffRows={onlyDiffRows} />,
      rowExpandable: row => !!(row.left?.points?.length || row.right?.points?.length),
      expandIcon: ({ expanded, onExpand, record, expandable }) => expandable ? <Button type="link" size="small" aria-expanded={expanded} onClick={event => onExpand(record, event)}>{expanded ? '收起频响点' : '展开频响点'} [{record.key}]</Button> : null,
      columnWidth: 130,
    } : undefined} columns={[
      { title: '原始序号', key: 'index', width: 90, render: (_, row) => `[${row.key}]` },
      { title: '设备 A', key: 'left', width: 265, render: (_, row) => device(row.left) },
      { title: '设备 B', key: 'right', width: 265, render: (_, row) => device(row.right) },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
    <Collapse items={[{ key: 'fields', label: `逐字段详情（${fieldCount} 行）`, children }]} />
  </Space>;
}
