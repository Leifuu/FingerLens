import type { ReactNode } from 'react';
import { Button, Collapse, Space, Table, Tag, Typography } from 'antd';
import type { Json } from '../core/types';
import type { MergedDisplayRow } from '../core/display-compare';
import { audioDisplayRows, type AudioFacts, type AudioDisplayRow } from '../core/audio-display';
import { AudioProfileDetails } from './AudioProfileDetails';
import { AudioMixerDetails } from './AudioMixerDetails';

export function AudioOverview({ itemKey, sectionKey, left, right, rows, onlyDiffRows, children }: { itemKey: string; sectionKey: string; left?: Json; right?: Json; rows: MergedDisplayRow[]; onlyDiffRows: boolean; children: ReactNode }) {
  if (itemKey !== 'hardware/audio' || sectionKey !== 'array') return children;
  const grouped = audioDisplayRows(left, right);
  if (!grouped) return children;
  const visible = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const fieldCount = onlyDiffRows ? rows.filter(row => row.state !== 'same').length : rows.length;
  const showMixer = grouped.some(row => row.left?.mixer || row.right?.mixer);
  const hasProfiles = (row: AudioDisplayRow) => !!(row.left?.profiles?.length || row.right?.profiles?.length);
  const hasMixer = (row: AudioDisplayRow) => !!(row.left?.mixer?.attributes?.length || row.right?.mixer?.attributes?.length);
  const detailLabel = (row: AudioDisplayRow) => hasProfiles(row) ? hasMixer(row) ? '关联格式与混音属性' : '关联格式' : '混音属性';
  const device = (facts?: AudioFacts) => !facts ? <Typography.Text type="secondary">此侧没有音频设备</Typography.Text> : <div className="audio-facts">
    <Typography.Text strong className="audio-name">{facts.name}</Typography.Text>
    <Typography.Text>{facts.identity} · {facts.roles}</Typography.Text>
    <Typography.Paragraph className="audio-aggregate" ellipsis={{ rows: 2, expandable: true, symbol: '展开原文' }}>{facts.aggregate}</Typography.Paragraph>
    <Typography.Text className="audio-profile-status">关联格式：{facts.profileStatus}</Typography.Text>
    <Typography.Text className="audio-profile-count" type="secondary">{facts.profileCount}</Typography.Text>
    {showMixer && <>
      <Typography.Text className="audio-mixer-status">混音属性：{facts.mixer?.status ?? '未采集'}</Typography.Text>
      <Typography.Text className="audio-mixer-count" type="secondary">{facts.mixer?.count ?? '旧报告未采集混音属性'}</Typography.Text>
    </>}
  </div>;
  return <Space className="audio-overview-section" direction="vertical" size={12} style={{ display: 'flex' }}>
    <Typography.Text type="secondary">音频设备 · 显示 {visible.length} / {grouped.length} 项。按原始序号保留重复；关联格式按编码连接采样率、声道掩码及封装，不代表当前路由或录放音成功。</Typography.Text>
    <Typography.Text className="audio-scroll-hint" type="secondary">左右滑动查看设备 A、B 与对比状态。</Typography.Text>
    <Table<AudioDisplayRow> className="audio-overview" rowKey="key" size="small" bordered dataSource={visible} pagination={visible.length > 5 ? { pageSize: 5, showSizeChanger: false, showQuickJumper: true } : false} scroll={{ x: 860 }} rowClassName={row => `row-${row.state}`} expandable={grouped.some(row => hasProfiles(row) || hasMixer(row)) ? {
      expandedRowRender: row => <Space direction="vertical" size={12} style={{ display: 'flex' }}>
        {hasProfiles(row) && <AudioProfileDetails left={row.left} right={row.right} onlyDiffRows={onlyDiffRows} />}
        {hasMixer(row) && <AudioMixerDetails left={row.left?.mixer} right={row.right?.mixer} onlyDiffRows={onlyDiffRows} />}
      </Space>,
      rowExpandable: row => hasProfiles(row) || hasMixer(row),
      expandIcon: ({ expanded, onExpand, record, expandable }) => expandable ? <Button type="link" size="small" data-audio-details="true" aria-expanded={expanded} onClick={event => onExpand(record, event)}>{expanded ? '收起' : '展开'}{detailLabel(record)} [{record.key}]</Button> : null,
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
