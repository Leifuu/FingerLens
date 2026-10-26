import { Table, Tag, Typography } from 'antd';
import { audioMixerRows, type AudioMixerFacts } from '../core/audio-mixer-display';

export function AudioMixerDetails({ left, right, onlyDiffRows }: { left?: AudioMixerFacts; right?: AudioMixerFacts; onlyDiffRows: boolean }) {
  const grouped = audioMixerRows(left, right), rows = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const device = (lines?: string[]) => !lines ? <Typography.Text type="secondary">此侧未返回该混音属性</Typography.Text> : <div className="audio-facts">{lines.map((line, index) => <Typography.Paragraph key={index} ellipsis={{ rows: 2, expandable: true, symbol: '展开原文' }}>{line}</Typography.Paragraph>)}</div>;
  return <div className="audio-mixer-details">
    <Typography.Paragraph type="secondary">支持的混音属性 · 显示 {rows.length} / {grouped.length} 项。保留原始顺序和重复；这是能力列表，不代表当前配置或位精确播放成功。</Typography.Paragraph>
    <Table className="audio-mixer-table" rowKey="key" size="small" bordered dataSource={rows} locale={{ emptyText: onlyDiffRows ? '混音属性明细无差异，可展开完整字段核对查询状态、哈希或未知字段。' : '没有混音属性明细' }} pagination={rows.length > 5 ? { pageSize: 5, showSizeChanger: false } : false} scroll={{ x: 700 }} columns={[
      { title: '属性序号', key: 'index', width: 90, render: (_, row) => `[${row.key}]` },
      { title: '设备 A · 混音属性', key: 'left', width: 250, render: (_, row) => device(row.left) },
      { title: '设备 B · 混音属性', key: 'right', width: 250, render: (_, row) => device(row.right) },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} rowClassName={row => `row-${row.state}`} />
  </div>;
}
