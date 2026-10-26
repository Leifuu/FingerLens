import { Table, Tag, Typography } from 'antd';
import { audioProfileRows, type AudioFacts } from '../core/audio-display';

export function AudioProfileDetails({ left, right, onlyDiffRows }: { left?: AudioFacts; right?: AudioFacts; onlyDiffRows: boolean }) {
  const grouped = audioProfileRows(left, right), rows = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const device = (lines?: string[]) => !lines ? <Typography.Text type="secondary">此侧未返回该关联格式</Typography.Text> : <div className="audio-facts">{lines.map((line, index) => <Typography.Paragraph key={index} ellipsis={{ rows: 2, expandable: true, symbol: '展开原文' }}>{line}</Typography.Paragraph>)}</div>;
  return <div className="audio-profile-details">
    <Table className="audio-profile-table" rowKey="key" size="small" bordered dataSource={rows} locale={{ emptyText: onlyDiffRows ? '关联格式明细无差异，可展开完整字段核对设备差异。' : '没有关联格式明细' }} pagination={rows.length > 5 ? { pageSize: 5, showSizeChanger: false } : false} scroll={{ x: 700 }} columns={[
      { title: '格式序号', key: 'index', width: 90, render: (_, row) => `[${row.key}]` },
      { title: '设备 A · 关联格式', key: 'left', width: 250, render: (_, row) => device(row.left) },
      { title: '设备 B · 关联格式', key: 'right', width: 250, render: (_, row) => device(row.right) },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} rowClassName={row => `row-${row.state}`} />
  </div>;
}
