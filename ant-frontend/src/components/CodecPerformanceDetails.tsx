import { Button, Space, Table, Tag, Typography } from 'antd';
import { MinusSquareOutlined, PlusSquareOutlined } from '@ant-design/icons';
import { codecPerformancePointRows, codecPerformanceSummary, codecPerformanceText, type CodecPerformanceRow } from '../core/codec-performance-display';
import { CodecPerformancePoints } from './CodecPerformancePoints';

export function CodecPerformanceDetails({ rows, onlyDiffRows }: { rows: CodecPerformanceRow[]; onlyDiffRows: boolean }) {
  const visible = onlyDiffRows ? rows.filter(row => row.state !== 'same') : rows;
  return <Space className="codec-performance-details" direction="vertical" size={8} style={{ display: 'flex' }}>
    <Typography.Text type="secondary">视频性能点 · 显示 {visible.length} / {rows.length} 项 MIME。按原始序号比较；覆盖结果是平台声明，非实测帧率。完整哈希和原始字段保留在下方。</Typography.Text>
    <Typography.Text className="codec-scroll-hint" type="secondary">左右滑动查看两侧性能点与参考规格覆盖。</Typography.Text>
    <Table className="codec-performance-table" rowKey="key" size="small" bordered dataSource={visible} locale={{ emptyText: '性能点无差异，可展开完整字段核对其它变化。' }} pagination={visible.length > 5 ? { pageSize: 5, showSizeChanger: false } : false} scroll={{ x: 904 }} rowClassName={row => `row-${row.state}`} expandable={{
      columnTitle: '明细', columnWidth: 64, rowExpandable: row => codecPerformancePointRows(row).length > 0,
      expandedRowRender: row => <CodecPerformancePoints row={row} onlyDiffRows={onlyDiffRows} />,
      expandIcon: ({ expanded, expandable, onExpand, record }) => expandable ? <Button type="text" size="small" aria-label={`${expanded ? '收起' : '展开'}性能点明细 [${record.key}]`} aria-expanded={expanded} icon={expanded ? <MinusSquareOutlined /> : <PlusSquareOutlined />} onClick={event => onExpand(record, event)} /> : null,
    }} columns={[
      { title: 'MIME（原始序号）', key: 'mime', width: 210, render: (_, row) => <Space direction="vertical" size={0}><Typography.Text type="secondary">[{row.key}]</Typography.Text>{row.left?.type === row.right?.type ? <Typography.Text className="device-value">{codecPerformanceText(row.left?.type)}</Typography.Text> : <><Typography.Text className="device-value">A · {codecPerformanceText(row.left?.type)}</Typography.Text><Typography.Text className="device-value">B · {codecPerformanceText(row.right?.type)}</Typography.Text></>}</Space> },
      { title: '设备 A · 性能点', key: 'left', width: 260, render: (_, row) => <Typography.Paragraph className="device-value codec-performance-summary" ellipsis={{ rows: 5, expandable: true, symbol: '展开原文' }}>{codecPerformanceSummary(row.left)}</Typography.Paragraph> },
      { title: '设备 B · 性能点', key: 'right', width: 260, render: (_, row) => <Typography.Paragraph className="device-value codec-performance-summary" ellipsis={{ rows: 5, expandable: true, symbol: '展开原文' }}>{codecPerformanceSummary(row.right)}</Typography.Paragraph> },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
  </Space>;
}
