import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Drawer, Card, Descriptions, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { Item, Row as CompareRow } from '../core/types';
import { diffLabels, statusLabels } from '../core/types';
import { buildSections, type DisplaySection } from '../display';
import { copyText } from '../core/clipboard';

interface MergedRow { key: string; label: string; left: string | null; right: string | null; state: 'same' | 'changed' | 'left-only' | 'right-only' }

function itemMeta(item?: Item) {
  if (!item) return [['状态', '此侧没有采集项']];
  return [['采集状态', statusLabels[item.status]], ['来源', item.source.api], ['执行身份', `UID ${item.source.uid} · PID ${item.source.pid}`], ['SELinux', item.source.selinux], ['数据类型', item.dataType]];
}

function mergeSection(left: DisplaySection | undefined, right: DisplaySection | undefined): DisplaySection & { merged: MergedRow[] } {
  const rows = new Map<string, { label: string; left: string | null; right: string | null }>();
  for (const [side, section] of [['left', left], ['right', right]] as const) {
    const occurrences = new Map<string, number>();
    for (const value of section?.rows || []) {
      const occurrence = occurrences.get(value.key) || 0;
      occurrences.set(value.key, occurrence + 1);
      const key = JSON.stringify([value.key, occurrence]);
      const existing = rows.get(key) || { label: value.label, left: null, right: null };
      existing[side] = value.value;
      rows.set(key, existing);
    }
  }
  const merged = [...rows.entries()].map(([key, value]) => ({ key, ...value, state: value.left === null ? 'right-only' : value.right === null ? 'left-only' : value.left === value.right ? 'same' : 'changed' } as MergedRow));
  return { key: left?.key || right?.key || 'section', title: left?.title || right?.title || '采集值', rows: [], table: !!(left?.table || right?.table), merged };
}

function cell(value: string | null, side: 'A' | 'B', label: string) {
  if (value === null) return <Typography.Text type="secondary">—</Typography.Text>;
  return <Typography.Paragraph className="device-value copyable-value" title="双击复制" onDoubleClick={event => { event.stopPropagation(); void copyText(value, `设备 ${side} · ${label}`); }} ellipsis={{ rows: 4, expandable: true, symbol: '展开' }}>{value}</Typography.Paragraph>;
}

export function ComparisonDrawer({ row, open, onClose }: { row?: CompareRow; open: boolean; onClose: () => void }) {
  const [onlyDiffRows, setOnlyDiffRows] = useState(false);
  const sections = useMemo(() => {
    if (!row) return [];
    const left = new Map(buildSections(row.left).map(section => [section.key, section]));
    const right = new Map(buildSections(row.right).map(section => [section.key, section]));
    return [...new Set([...left.keys(), ...right.keys()])].map(key => mergeSection(left.get(key), right.get(key)));
  }, [row]);
  useEffect(() => { setOnlyDiffRows(false); }, [row?.id]);
  if (!row) return null;
  const hasTableSections = sections.some(section => section.table);
  return <Drawer open={open} onClose={onClose} width="min(1180px, 100vw)" title={<div><Typography.Title level={4} style={{ margin: 0 }}>{row.name}</Typography.Title><Typography.Text type="secondary">{row.key}</Typography.Text></div>} extra={<Space size={8}>{hasTableSections && <Button size="small" type={onlyDiffRows ? 'primary' : 'default'} onClick={() => setOnlyDiffRows(value => !value)}>只展示差异</Button>}<Tag color={row.state === 'same' ? 'success' : row.state === 'incomparable' ? 'default' : 'error'}>{diffLabels[row.state]}</Tag></Space>} className="comparison-drawer" destroyOnHidden>
    <Space direction="vertical" size={16} style={{ display: 'flex' }}>
      <Descriptions size="small" bordered column={{ xs: 1, sm: 2 }} items={[{ key: 'a', label: '设备 A', children: <Descriptions size="small" column={1} items={itemMeta(row.left).map(([label, value]) => ({ key: label, label, children: value }))} /> }, { key: 'b', label: '设备 B', children: <Descriptions size="small" column={1} items={itemMeta(row.right).map(([label, value]) => ({ key: label, label, children: value }))} /> }]} />
      {[row.left, row.right].map((item, index) => item && (item.error || item.truncation || item.installer) ? <Alert key={index} type={item.error || item.truncation ? 'warning' : 'info'} message={`设备 ${index === 0 ? 'A' : 'B'} · 采集边界`} description={<Space direction="vertical">{item.error && <Typography.Text>错误：{JSON.stringify(item.error)}</Typography.Text>}{item.truncation && <Typography.Text>截断：{JSON.stringify(item.truncation)}</Typography.Text>}{item.installer && <Typography.Text>安装来源：{item.installer.source.api} · {statusLabels[item.installer.status]}</Typography.Text>}</Space>} /> : null)}
      {row.category === 'collection' ? <Alert type="info" showIcon message="采集会话元数据" description="该项用于解释两份指纹的采集条件，不属于设备原始字段，因此详情不展开会话时间、配置或权限包装。" /> : sections.map(section => <Card key={section.key} title={section.title} size="small" className="comparison-section">
        <Table<MergedRow> rowKey="key" size="small" bordered pagination={(onlyDiffRows ? section.merged.filter(record => record.state !== 'same').length : section.merged.length) > 50 ? { pageSize: 50, showSizeChanger: false, showQuickJumper: true } : false} dataSource={onlyDiffRows ? section.merged.filter(record => record.state !== 'same') : section.merged} rowClassName={record => `row-${record.state}`} columns={[{ title: '字段', dataIndex: 'label', key: 'label', width: 220, fixed: 'left', render: value => <Typography.Text className="field-label">{value}</Typography.Text> }, { title: '设备 A', dataIndex: 'left', key: 'left', render: (value, record) => cell(value, 'A', record.label) }, { title: '设备 B', dataIndex: 'right', key: 'right', render: (value, record) => cell(value, 'B', record.label) }, { title: '对比', key: 'state', width: 110, render: (_, record) => <Tag color={record.state === 'same' ? 'success' : record.state === 'changed' ? 'error' : 'warning'}>{record.state === 'same' ? '相同' : record.state === 'changed' ? '差异' : record.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> }] as ColumnsType<MergedRow>} scroll={{ x: 760 }} />
      </Card>)}
    </Space>
  </Drawer>;
}
