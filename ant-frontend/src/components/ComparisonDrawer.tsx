import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Drawer, Card, Descriptions, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { Item, Row as CompareRow } from '../core/types';
import { diffLabels, statusLabels } from '../core/types';
import { buildSections } from '../display';
import { mergeDisplaySections, type MergedDisplayRow } from '../core/display-compare';
import { copyText } from '../core/clipboard';
import { RootPathOverview } from './RootPathOverview';
import { FontOverview } from './FontOverview';
import { CodecOverview } from './CodecOverview';
import { SensorOverview } from './SensorOverview';
import { TlsOverview } from './TlsOverview';
import { ApexOverview } from './ApexOverview';
import { DisplayModeOverview } from './DisplayModeOverview';
import { DisplayColorOverview } from './DisplayColorOverview';
import { DisplayProductOverview } from './DisplayProductOverview';
import { VibratorOverview } from './VibratorOverview';
import { SecurityStateOverview } from './SecurityStateOverview';
import { AudioOverview } from './AudioOverview';
import { MicrophoneOverview } from './MicrophoneOverview';

function itemMeta(item: Item | undefined, collected: boolean) {
  if (!collected) return [['状态', '等待采集']];
  if (!item) return [['状态', '此侧没有采集项']];
  return [['采集状态', statusLabels[item.status]], ['来源', item.source.api], ['执行身份', `UID ${item.source.uid} · PID ${item.source.pid}`], ['SELinux', item.source.selinux], ['数据类型', item.dataType]];
}

function cell(value: string | null, side: 'A' | 'B', label: string, collected: boolean) {
  if (!collected) return <Typography.Text type="secondary">等待采集</Typography.Text>;
  if (value === null) return <Typography.Text type="secondary">—</Typography.Text>;
  return <Typography.Paragraph className="device-value copyable-value" title="双击复制" onDoubleClick={event => { event.stopPropagation(); void copyText(value, `设备 ${side} · ${label}`); }} ellipsis={{ rows: 4, expandable: true, symbol: '展开' }}>{value}</Typography.Paragraph>;
}

export function ComparisonDrawer({ row, open, collected, onClose }: { row?: CompareRow; open: boolean; collected: { left: boolean; right: boolean }; onClose: () => void }) {
  const [onlyDiffRows, setOnlyDiffRows] = useState(false);
  const sections = useMemo(() => {
    if (!row) return [];
    const left = new Map(buildSections(row.left).map(section => [section.key, section]));
    const right = new Map(buildSections(row.right).map(section => [section.key, section]));
    return [...new Set([...left.keys(), ...right.keys()])].map(key => mergeDisplaySections(left.get(key), right.get(key)));
  }, [row]);
  useEffect(() => { setOnlyDiffRows(false); }, [row?.id]);
  if (!row) return null;
  const hasTableSections = sections.some(section => section.table);
  return <Drawer open={open} onClose={onClose} width="min(1180px, 100vw)" title={<div><Typography.Title level={4} style={{ margin: 0 }}>{row.name}</Typography.Title><Typography.Text type="secondary">{row.key}</Typography.Text></div>} extra={<Space size={8}>{hasTableSections && <Button size="small" type={onlyDiffRows ? 'primary' : 'default'} onClick={() => setOnlyDiffRows(value => !value)}>只展示差异</Button>}<Tag color={row.state === 'same' ? 'success' : row.state === 'incomparable' ? 'default' : 'error'}>{diffLabels[row.state]}</Tag></Space>} className="comparison-drawer" destroyOnHidden>
    <Space direction="vertical" size={16} style={{ display: 'flex' }}>
      <Descriptions size="small" bordered column={{ xs: 1, sm: 2 }} items={[{ key: 'a', label: '设备 A', children: <Descriptions size="small" column={1} items={itemMeta(row.left, collected.left).map(([label, value]) => ({ key: label, label, children: value }))} /> }, { key: 'b', label: '设备 B', children: <Descriptions size="small" column={1} items={itemMeta(row.right, collected.right).map(([label, value]) => ({ key: label, label, children: value }))} /> }]} />
      {[row.left, row.right].map((item, index) => {
        if (!item) return null;
        const device = `设备 ${index === 0 ? 'A' : 'B'}`;
        const hasCollectionLimit = Boolean(item.error || item.truncation);
        const hasInstallerIssue = Boolean(item.installer && item.installer.status !== 'ok');
        if (!hasCollectionLimit && !hasInstallerIssue) return null;
        return <Space key={index} direction="vertical" size={8} style={{ display: 'flex' }}>
          {hasCollectionLimit && <Alert
            type="warning"
            message={`${device} · 采集限制`}
            description={<Space direction="vertical">
              {item.error && <Typography.Text>错误：{JSON.stringify(item.error)}</Typography.Text>}
              {item.truncation && <Typography.Text>截断：{JSON.stringify(item.truncation)}</Typography.Text>}
            </Space>}
          />}
          {hasInstallerIssue && item.installer && <Alert
            type="warning"
            message={`${device} · 安装来源受限`}
            description={<Typography.Text>{item.installer.source.api} · {statusLabels[item.installer.status]}</Typography.Text>}
          />}
        </Space>;
      })}
      {row.category === 'collection' ? <Alert type="info" showIcon message="采集会话元数据" description="该项用于解释两份指纹的采集条件，不属于设备原始字段，因此详情不展开会话时间、配置或权限包装。" /> : sections.map(section => <Card key={section.key} title={section.title} size="small" className="comparison-section">
        <RootPathOverview itemKey={row.key} sectionKey={section.key} rows={section.rows} onlyDiffRows={onlyDiffRows}>
        <FontOverview itemKey={row.key} sectionKey={section.key} rows={section.rows} onlyDiffRows={onlyDiffRows}>
        <CodecOverview itemKey={row.key} sectionKey={section.key} left={row.left?.value} right={row.right?.value} rows={section.rows} onlyDiffRows={onlyDiffRows}>
        <SensorOverview itemKey={row.key} sectionKey={section.key} left={row.left?.value} right={row.right?.value} rows={section.rows} onlyDiffRows={onlyDiffRows}>
        <TlsOverview itemKey={row.key} sectionKey={section.key} rows={section.rows} onlyDiffRows={onlyDiffRows}>
        <ApexOverview itemKey={row.key} sectionKey={section.key} rows={section.rows} onlyDiffRows={onlyDiffRows}>
        <DisplayModeOverview itemKey={row.key} sectionKey={section.key} left={row.left?.value} right={row.right?.value} rows={section.rows} onlyDiffRows={onlyDiffRows}>
        <DisplayColorOverview itemKey={row.key} sectionKey={section.key} left={row.left?.value} right={row.right?.value} rows={section.rows} onlyDiffRows={onlyDiffRows}>
        <DisplayProductOverview itemKey={row.key} sectionKey={section.key} left={row.left?.value} right={row.right?.value} rows={section.rows} onlyDiffRows={onlyDiffRows}>
        <VibratorOverview itemKey={row.key} sectionKey={section.key} left={row.left?.value} right={row.right?.value} rows={section.rows} onlyDiffRows={onlyDiffRows}>
        <SecurityStateOverview itemKey={row.key} sectionKey={section.key} left={row.left?.value} right={row.right?.value} rows={section.rows} onlyDiffRows={onlyDiffRows}>
        <AudioOverview itemKey={row.key} sectionKey={section.key} left={row.left?.value} right={row.right?.value} rows={section.rows} onlyDiffRows={onlyDiffRows}>
        <MicrophoneOverview itemKey={row.key} sectionKey={section.key} left={row.left?.value} right={row.right?.value} rows={section.rows} onlyDiffRows={onlyDiffRows}>
        <Table<MergedDisplayRow> rowKey="key" size="small" bordered pagination={(onlyDiffRows ? section.rows.filter(record => record.state !== 'same').length : section.rows.length) > 50 ? { pageSize: 50, showSizeChanger: false, showQuickJumper: true } : false} dataSource={onlyDiffRows ? section.rows.filter(record => record.state !== 'same') : section.rows} rowClassName={record => `row-${record.state}`} columns={[{ title: '字段', dataIndex: 'label', key: 'label', width: 220, fixed: row.key === 'hardware/usb' && section.key === 'descriptors' ? undefined : 'left', render: value => <Typography.Text className="field-label">{value}</Typography.Text> }, { title: collected.left ? '设备 A' : '设备 A · 等待采集', dataIndex: 'left', key: 'left', render: (value, record) => cell(value, 'A', record.label, collected.left) }, { title: collected.right ? '设备 B' : '设备 B · 等待采集', dataIndex: 'right', key: 'right', render: (value, record) => cell(value, 'B', record.label, collected.right) }, { title: '对比', key: 'state', width: 110, render: (_, record) => <Tag color={record.state === 'same' ? 'success' : record.state === 'changed' ? 'error' : 'warning'}>{record.state === 'same' ? '相同' : record.state === 'changed' ? '差异' : record.state === 'left-only' ? '仅 A' : '仅 B'}</Tag> }] as ColumnsType<MergedDisplayRow>} scroll={{ x: 760 }} />
        </MicrophoneOverview>
        </AudioOverview>
        </SecurityStateOverview>
        </VibratorOverview>
        </DisplayProductOverview>
        </DisplayColorOverview>
        </DisplayModeOverview>
        </ApexOverview>
        </TlsOverview>
        </SensorOverview>
        </CodecOverview>
        </FontOverview>
        </RootPathOverview>
      </Card>)}
    </Space>
  </Drawer>;
}
