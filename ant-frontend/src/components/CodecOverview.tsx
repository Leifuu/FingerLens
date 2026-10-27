import type { ReactNode } from 'react';
import { Button, Collapse, Space, Table, Tag, Typography } from 'antd';
import { MinusSquareOutlined, PlusSquareOutlined } from '@ant-design/icons';
import { codecDisplayRows, type CodecDisplayRow } from '../core/codec-display';
import type { MergedDisplayRow } from '../core/display-compare';
import { copyText } from '../core/clipboard';
import { CodecEncoderDetails } from './CodecEncoderDetails';
import { CodecPerformanceDetails } from './CodecPerformanceDetails';
import type { Json } from '../core/types';

export function CodecOverview({ itemKey, sectionKey, left, right, rows, onlyDiffRows, children }: { itemKey: string; sectionKey: string; left?: Json; right?: Json; rows: MergedDisplayRow[]; onlyDiffRows: boolean; children: ReactNode }) {
  if (itemKey !== 'hardware/codecs' || sectionKey !== 'array') return children;
  const grouped = codecDisplayRows(rows, Array.isArray(left) ? left : undefined, Array.isArray(right) ? right : undefined);
  if (!grouped) return children;
  const visible = onlyDiffRows ? grouped.filter(row => row.state !== 'same') : grouped;
  const hasDetails = grouped.some(row => row.encoderControls.length || row.performancePoints.length);
  const fieldCount = onlyDiffRows ? rows.filter(row => row.state !== 'same').length : rows.length;
  const text = (value: string | null | undefined) => value === '' ? '（空字符串）' : value ?? '—';
  const flag = (value: string | null | undefined) => value === 'true' ? '是' : value === 'false' ? '否' : text(value);
  const identity = (row: CodecDisplayRow, side: 'left' | 'right') => <Space direction="vertical" size={0}>
    <Typography.Text className="device-value copyable-value" title="双击复制编解码器名" onDoubleClick={() => { const value = row.name[side]; if (value !== null) void copyText(value, '编解码器名'); }}>{text(row.name[side])}</Typography.Text>
    {row.canonicalName?.[side] != null && <Typography.Text type="secondary">规范名称 {text(row.canonicalName[side])}</Typography.Text>}
  </Space>;
  const metadata = (row: CodecDisplayRow, side: 'left' | 'right') => {
    const encoder = row.encoder[side];
    const types = row.supportedTypes.filter(field => field[side] !== null).map(field => text(field[side]));
    return <Space direction="vertical" size={0}>
      <Typography.Text>{encoder === 'true' ? '编码' : encoder === 'false' ? '解码' : text(encoder)} · 硬件加速 {flag(row.hardwareAccelerated?.[side])}</Typography.Text>
      <Typography.Text type="secondary">软件实现 {flag(row.softwareOnly?.[side])} · 厂商组件 {flag(row.vendor?.[side])}</Typography.Text>
      <Typography.Text type="secondary">安全模型 {text(row.securityModelName?.[side])} · 查询状态 {text(row.securityModelStatus?.[side])}</Typography.Text>
      <Typography.Text className="device-value">MIME：{types.length ? types.join('、') : '—'}</Typography.Text>
    </Space>;
  };
  return <Space className="codec-overview-section" direction="vertical" size={12} style={{ display: 'flex' }}>
    <Typography.Text type="secondary">编解码器概览 · 显示 {visible.length} / {grouped.length} 项。按原始序号比较，保留别名和重复条目；可展开编码参数与性能点，完整字段保留在下方。</Typography.Text>
    <Typography.Text className="codec-scroll-hint" type="secondary">左右滑动查看设备 A、B 与对比状态。</Typography.Text>
    <Table<CodecDisplayRow> className="codec-overview" rowKey="key" size="small" bordered dataSource={visible} pagination={visible.length > 20 ? { pageSize: 20, showSizeChanger: false, showQuickJumper: true } : false} scroll={{ x: hasDetails ? 1004 : 940 }} rowClassName={row => `row-${row.state}`} expandable={hasDetails ? {
      columnTitle: '能力', columnWidth: 64, rowExpandable: row => row.encoderControls.length > 0 || row.performancePoints.length > 0,
      expandedRowRender: row => <Space direction="vertical" size={12} style={{ display: 'flex' }}>{row.encoderControls.length > 0 && <CodecEncoderDetails rows={row.encoderControls} onlyDiffRows={onlyDiffRows} />}{row.performancePoints.length > 0 && <CodecPerformanceDetails rows={row.performancePoints} onlyDiffRows={onlyDiffRows} />}</Space>,
      expandIcon: ({ expanded, expandable, onExpand, record }) => expandable ? <Button type="text" size="small" data-encoder-controls={record.encoderControls.length ? 'true' : undefined} data-performance-points={record.performancePoints.length ? 'true' : undefined} aria-label={`${expanded ? '收起' : '展开'}能力详情 [${record.key}]`} title="编码参数与性能点" aria-expanded={expanded} icon={expanded ? <MinusSquareOutlined /> : <PlusSquareOutlined />} onClick={event => onExpand(record, event)} /> : null,
    } : undefined} columns={[
      { title: '编解码器（原始序号）', key: 'codec', width: 310, render: (_, row) => <Space direction="vertical" size={0}><Typography.Text type="secondary">[{row.key}]</Typography.Text>{row.name.left === row.name.right && row.canonicalName?.left === row.canonicalName?.right ? identity(row, 'left') : <><div>A · {identity(row, 'left')}</div><div>B · {identity(row, 'right')}</div></>}</Space> },
      { title: '设备 A', key: 'left', width: 260, render: (_, row) => metadata(row, 'left') },
      { title: '设备 B', key: 'right', width: 260, render: (_, row) => metadata(row, 'right') },
      { title: '对比', key: 'state', width: 110, render: (_, row) => <Tag color={row.state === 'same' ? 'success' : row.state === 'changed' ? 'error' : 'warning'}>{row.state === 'same' ? '相同' : row.state === 'changed' ? '差异' : row.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> },
    ]} />
    <Collapse items={[{ key: 'fields', label: `逐字段详情（${fieldCount} 行）`, children }]} />
  </Space>;
}
