import { Table, Tag, Typography } from 'antd';
import { codecPerformancePointRows, type CodecPerformanceRow } from '../core/codec-performance-display';

export function CodecPerformancePoints({ row, onlyDiffRows }: { row: CodecPerformanceRow; onlyDiffRows: boolean }) {
  const grouped = codecPerformancePointRows(row), visible = onlyDiffRows ? grouped.filter(point => point.state !== 'same') : grouped;
  const device = (lines?: string[]) => !lines ? <Typography.Text type="secondary">此侧未返回该性能点</Typography.Text> : <div className="codec-performance-point-facts">{lines.map((line, index) => <Typography.Paragraph key={index} className="device-value" ellipsis={{ rows: 3, expandable: true, symbol: '展开原文' }}>{line}</Typography.Paragraph>)}</div>;
  return <div className="codec-performance-points">
    <Table className="codec-performance-point-table" rowKey="key" size="small" bordered dataSource={visible} locale={{ emptyText: onlyDiffRows ? '性能点明细无差异，可展开完整字段核对状态、哈希或其它变化。' : '没有性能点明细' }} pagination={visible.length > 10 ? { pageSize: 10, showSizeChanger: false } : false} scroll={{ x: 620 }} rowClassName={point => `row-${point.state}`} columns={[
      { title: '点序号', key: 'index', width: 90, render: (_, point) => `[${point.key}]` },
      { title: '设备 A · 参考点覆盖', key: 'left', width: 210, render: (_, point) => device(point.left) },
      { title: '设备 B · 参考点覆盖', key: 'right', width: 210, render: (_, point) => device(point.right) },
      { title: '对比', key: 'state', width: 110, render: (_, point) => <Tag color={point.state === 'same' ? 'success' : point.state === 'changed' ? 'error' : 'warning'}>{point.state === 'same' ? '相同' : point.state === 'changed' ? '差异' : point.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
  </div>;
}
