import { Table, Tag, Typography } from 'antd';
import { microphonePointRows, type MicrophoneFacts } from '../core/microphone-display';

export function MicrophoneResponseDetails({ left, right, onlyDiffRows }: { left?: MicrophoneFacts; right?: MicrophoneFacts; onlyDiffRows: boolean }) {
  const grouped = microphonePointRows(left, right), rows = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const device = (lines?: string[]) => !lines ? <Typography.Text type="secondary">此侧未返回该频响点</Typography.Text> : <div className="microphone-point-facts">{lines.map((line, index) => <Typography.Text key={index}>{line}</Typography.Text>)}</div>;
  return <div className="microphone-response-details">
    <Table className="microphone-response-table" rowKey="key" size="small" bordered dataSource={rows} locale={{ emptyText: onlyDiffRows ? '频响点明细无差异，可展开完整字段核对其它变化。' : '没有频响点明细' }} pagination={rows.length > 10 ? { pageSize: 10, showSizeChanger: false } : false} scroll={{ x: 700 }} rowClassName={row => `row-${row.state}`} columns={[
      { title: '频响序号', key: 'index', width: 90, render: (_, row) => `[${row.key}]` },
      { title: '设备 A · 频响', key: 'left', width: 250, render: (_, row) => device(row.left) },
      { title: '设备 B · 频响', key: 'right', width: 250, render: (_, row) => device(row.right) },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
  </div>;
}
