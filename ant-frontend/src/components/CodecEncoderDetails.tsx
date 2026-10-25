import { Space, Table, Tag, Typography } from 'antd';
import { codecEncoderFacts, type CodecEncoderDisplayRow } from '../core/codec-encoder-display';

export function CodecEncoderDetails({ rows, onlyDiffRows }: { rows: CodecEncoderDisplayRow[]; onlyDiffRows: boolean }) {
  const visible = onlyDiffRows ? rows.filter(row => row.state !== 'same') : rows;
  const values = (row: CodecEncoderDisplayRow, side: 'left' | 'right') => <dl className="codec-encoder-facts">
    {codecEncoderFacts(row, side).map(fact => <div key={fact.key}><dt>{fact.label}</dt><dd className="device-value">{fact.value}</dd></div>)}
  </dl>;
  const mime = (value: string | null | undefined) => value === '' ? '（空字符串）' : value ?? '未采集';
  return <Space className="codec-encoder-details" direction="vertical" size={8} style={{ display: 'flex' }}>
    <Typography.Text type="secondary">编码参数 · 显示 {visible.length} / {rows.length} 项。按 MIME 原始序号比较，范围为平台原值；完整字段保留在下方。</Typography.Text>
    <Typography.Text className="codec-scroll-hint" type="secondary">左右滑动查看两侧编码参数。</Typography.Text>
    <Table<CodecEncoderDisplayRow> rowKey="key" size="small" bordered dataSource={visible} pagination={visible.length > 5 ? { pageSize: 5, showSizeChanger: false, showQuickJumper: true } : false} scroll={{ x: 840 }} rowClassName={row => `row-${row.state}`} columns={[
      { title: 'MIME（原始序号）', key: 'mime', width: 230, render: (_, row) => <Space direction="vertical" size={0}><Typography.Text type="secondary">[{row.key}]</Typography.Text>{row.mime?.left === row.mime?.right ? <Typography.Text className="device-value">{mime(row.mime?.left)}</Typography.Text> : <><Typography.Text className="device-value">A · {mime(row.mime?.left)}</Typography.Text><Typography.Text className="device-value">B · {mime(row.mime?.right)}</Typography.Text></>}</Space> },
      { title: '设备 A', key: 'left', width: 250, render: (_, row) => values(row, 'left') },
      { title: '设备 B', key: 'right', width: 250, render: (_, row) => values(row, 'right') },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
  </Space>;
}
