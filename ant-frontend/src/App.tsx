import { useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Badge, Button, Card, Col, ConfigProvider, Empty, Layout, Menu, Row, Select, Space, Table, Tag, theme, Typography, Input, message } from 'antd';
import { AppstoreOutlined, ApiOutlined, DatabaseOutlined, DesktopOutlined, FileSearchOutlined, HddOutlined, SafetyCertificateOutlined, SearchOutlined, WifiOutlined } from '@ant-design/icons';
import zhCN from 'antd/locale/zh_CN';
import type { ColumnsType } from 'antd/es/table';
import type { Imported, Row as CompareRow, Rules } from './core/types';
import { categories, diffLabels } from './core/types';
import { useEvidenceWorker } from './core/useEvidenceWorker';
import { useAdbDevices } from './core/useAdbDevices';
import { copyText } from './core/clipboard';
import { FileImportCard } from './components/FileImportCard';
import { StatCards } from './components/StatCards';
import { ComparisonDrawer } from './components/ComparisonDrawer';
import { AttestationDrawer } from './components/AttestationDrawer';
import { buildDiffReportHtml, reportFileName } from './report/report';
import './styles.css';

const { Header, Sider, Content } = Layout;

function preview(row: CompareRow, side: 'left' | 'right') {
  const item = row[side];
  if (!item) return '此侧没有对应采集项';
  if (row.category === 'collection') return '采集条件与会话元数据（非设备字段）';
  if (item.key === 'packages/enumeration' && item.value && typeof item.value === 'object' && !Array.isArray(item.value)) return `返回包数：${item.value.returnedCount}`;
  if (item.value === null) return 'null';
  if (item.value && typeof item.value === 'object' && !Array.isArray(item.value) && typeof item.value.stdout === 'string') return item.value.stdout.slice(0, 180);
  if (typeof item.value === 'string') return item.value.slice(0, 180);
  return JSON.stringify(item.value).slice(0, 180);
}

const categoryIcons: Record<string, ReactNode> = { packages: <AppstoreOutlined />, build: <DesktopOutlined />, display: <DesktopOutlined />, network: <WifiOutlined />, security: <SafetyCertificateOutlined />, storage: <HddOutlined />, hardware: <DatabaseOutlined />, binder: <ApiOutlined /> };
const rawRules: Rules = { semantic: false, ignoreDynamic: false };

export default function App() {
  const [files, setFiles] = useState<{ left?: Imported; right?: Imported }>({});
  const [loading, setLoading] = useState({ left: false, right: false, compare: false });
  const [rows, setRows] = useState<CompareRow[]>([]), [warnings, setWarnings] = useState<string[]>([]);
  const [category, setCategory] = useState('all'), [search, setSearch] = useState(''), [status, setStatus] = useState('all');
  const [selected, setSelected] = useState<CompareRow>();
  const [attestationOpen, setAttestationOpen] = useState(false);
  const originals = useRef<{ left?: File; right?: File }>({});
  const { request, cancel } = useEvidenceWorker();
  const adb = useAdbDevices();
  const lastAdbError = useRef('');
  const notifiedWarnings = useRef('');
  useEffect(() => {
    if (adb.error && adb.error !== lastAdbError.current) { lastAdbError.current = adb.error; message.error(`ADB 连接不可用：${adb.error}`); }
    if (!adb.error) lastAdbError.current = '';
  }, [adb.error]);
  useEffect(() => {
    const text = warnings.join('；');
    if (!text) { notifiedWarnings.current = ''; return; }
    if (text !== notifiedWarnings.current) { notifiedWarnings.current = text; message.warning(text); }
  }, [warnings]);
  useEffect(() => {
    const focusSearch = (event: KeyboardEvent) => {
      if (event.key !== '/' || event.ctrlKey || event.metaKey || event.altKey || document.querySelector('[role="dialog"]')) return;
      const target = event.target as HTMLElement;
      if (target.matches('input,textarea,select,[contenteditable="true"]')) return;
      event.preventDefault(); document.querySelector<HTMLInputElement>('.filter-row input')?.focus();
    };
    window.addEventListener('keydown', focusSearch);
    return () => window.removeEventListener('keydown', focusSearch);
  }, []);
  const epoch = useRef({ left: 0, right: 0, compare: 0 });
  const ready = !!files.left && !!files.right;
  const busy = Object.values(loading).some(Boolean);

  async function importFile(side: 'left' | 'right', file: File) {
    if (!file.size) throw new Error('指纹文件为空');
    const token = ++epoch.current[side];
    setLoading(value => ({ ...value, [side]: true }));
    try {
      const result = await request('import', { side, file });
      if (token === epoch.current[side]) { originals.current[side] = file; setFiles(value => ({ ...value, [side]: result.result })); setSelected(undefined); }
    } catch (cause) { if (token === epoch.current[side]) message.error(cause instanceof Error ? cause.message : String(cause)); throw cause; }
    finally { if (token === epoch.current[side]) setLoading(value => ({ ...value, [side]: false })); }
  }

  useEffect(() => {
    if (!files.left || !files.right) return;
    const token = ++epoch.current.compare;
    setLoading(value => ({ ...value, compare: true }));
    request('compare', { rules: rawRules }).then(result => { if (token === epoch.current.compare) { setRows(result.rows); setWarnings(result.conditions); } }).catch(cause => { if (token === epoch.current.compare) message.error(cause instanceof Error ? cause.message : String(cause)); }).finally(() => { if (token === epoch.current.compare) setLoading(value => ({ ...value, compare: false })); });
  }, [files, request]);

  const counts = useMemo(() => { const value = new Map<string, number>(); rows.forEach(row => value.set(row.category, (value.get(row.category) || 0) + 1)); return value; }, [rows]);
  function stopProcessing() {
    epoch.current.left++; epoch.current.right++; epoch.current.compare++;
    cancel(); setLoading({ left: false, right: false, compare: false });
    message.info('处理已取消；已成功导入的原件仍然保留。');
  }
  function exportJsonReport() {
    const payload = { data: { files: { left: files.left?.sha256, right: files.right?.sha256 }, rules: rawRules, conditions: warnings, rows: filtered.map(({ id, key, name, state, changedPaths, ignored }) => ({ id, key, name, state, changedPaths, ignored })) }, message: '本地差异报告，非设备原始指纹', code: 0 };
    const url = URL.createObjectURL(new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' }));
    const link = document.createElement('a'); link.href = url; link.download = 'fingerprint-diff-report.json'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  function exportHtmlReport(scope: 'current' | 'all-diff') {
    if (!files.left || !files.right) return;
    const left = files.left, right = files.right;
    const reportRows = scope === 'current' ? filtered : rows.filter(row => row.state !== 'same');
    const html = buildDiffReportHtml({ left, right, rows: reportRows, rules: rawRules, warnings, view: { category, search, onlyDiff: false, status, scope: scope === 'current' ? '当前视图' : '完整差异' } });
    const url = URL.createObjectURL(new Blob([html], { type: 'text/html;charset=utf-8' }));
    const link = document.createElement('a'); link.href = url; link.download = reportFileName(left, right); link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    message.success(`已导出 ${scope === 'current' ? '当前视图' : '完整差异'} HTML 报告`);
  }
  const stats = useMemo(() => ({ changed: rows.filter(row => ['different', 'status'].includes(row.state)).length, same: rows.filter(row => row.state === 'same').length, oneSide: rows.filter(row => row.state.endsWith('only')).length, unavailable: rows.filter(row => row.state === 'incomparable').length }), [rows]);
  const filtered = useMemo(() => {
    const query = search.toLocaleLowerCase();
    return rows.filter(row => {
      const statusMatch = status === 'all' || row.state === status;
      const content = `${row.key} ${row.name} ${row.left?.source.api || ''} ${row.right?.source.api || ''} ${row.left?.installer?.key || ''} ${row.left?.installer?.source.api || ''} ${row.right?.installer?.key || ''} ${row.right?.installer?.source.api || ''}`.toLocaleLowerCase();
      return (category === 'all' || row.category === category) && statusMatch && (!query || content.includes(query));
    });
  }, [rows, category, search, status]);

  const menuItems = [{ key: 'all', icon: <FileSearchOutlined />, label: <Space>全部信息<Badge count={rows.length} overflowCount={100000} showZero color="#1677ff" /></Space> }, ...Object.entries(categories).map(([key, label]) => ({ key, icon: categoryIcons[key] || <FileSearchOutlined />, label: <Space>{label}<Badge count={counts.get(key) || 0} overflowCount={100000} showZero color="#434343" /></Space> }))];
  const columns: ColumnsType<CompareRow> = [
    { title: '信息名称', dataIndex: 'name', key: 'name', width: 270, fixed: 'left', render: (value: string, row) => <Space direction="vertical" size={0}><Typography.Text strong ellipsis={{ tooltip: value }}>{value}</Typography.Text><Typography.Text type="secondary" ellipsis>{row.key}</Typography.Text></Space> },
    { title: '设备 A · 原始值', key: 'left', render: (_, row) => <Typography.Text className="table-preview copyable-value" title="双击复制" onDoubleClick={event => { event.stopPropagation(); void copyText(preview(row, 'left'), '设备 A 原始显示值'); }} ellipsis={{ tooltip: preview(row, 'left') }}>{preview(row, 'left')}</Typography.Text> },
    { title: '设备 B · 原始值', key: 'right', render: (_, row) => <Typography.Text className="table-preview copyable-value" title="双击复制" onDoubleClick={event => { event.stopPropagation(); void copyText(preview(row, 'right'), '设备 B 原始显示值'); }} ellipsis={{ tooltip: preview(row, 'right') }}>{preview(row, 'right')}</Typography.Text> },
    { title: '结果', dataIndex: 'state', key: 'state', width: 130, render: (value: CompareRow['state']) => <Tag color={value === 'same' ? 'success' : value === 'incomparable' ? 'default' : 'error'}>{value === 'status' ? '状态不同' : diffLabels[value]}</Tag> },
  ];

  return <ConfigProvider locale={zhCN} theme={{ algorithm: theme.darkAlgorithm, token: { colorPrimary: '#4096ff', borderRadius: 10, fontSize: 18, colorBgLayout: '#0d1117' } }}>
    <Layout className="app-layout">
      <Sider breakpoint="lg" collapsedWidth="0" theme="dark" width={260}><div className="brand"><div className="brand-mark">F</div><div><Typography.Title level={4}>FingerLens • 观纹</Typography.Title><Typography.Link className="brand-byline" href="https://www.yuque.com/again-719bb" target="_blank" rel="noreferrer">By 小T</Typography.Link></div></div><Menu theme="dark" mode="inline" selectedKeys={[category]} items={menuItems} onClick={({ key }) => setCategory(key)} /></Sider>
      <Layout><Header className="app-header"><div><Typography.Text type="secondary">设备研究 / </Typography.Text><Typography.Text strong>指纹对比工作台</Typography.Text></div><Tag color="success">本地处理 · 不上传文件</Tag></Header>
        <Content className="app-content"><div className="content-inner">
          {category === 'all' && <>
          <div className="hero"><div><Typography.Title>两份指纹，一处核对。</Typography.Title><Typography.Paragraph type="secondary">用模块化视图定位设备差异，保留原始采集值与来源边界。</Typography.Paragraph></div></div>
          <Row gutter={[16, 16]}><Col xs={24} xl={12}><FileImportCard side="A" value={files.left} original={originals.current.left} busy={busy} adb={adb} onFile={file => importFile('left', file)} /></Col><Col xs={24} xl={12}><FileImportCard side="B" value={files.right} original={originals.current.right} busy={busy} adb={adb} onFile={file => importFile('right', file)} /></Col></Row>
          <Space wrap className="action-row"><Button type="primary" disabled={!ready || busy} onClick={() => exportHtmlReport('current')}>导出 HTML 报告</Button><Button disabled={!ready || busy} onClick={() => exportHtmlReport('all-diff')}>导出完整差异</Button><Button disabled={!ready || busy} onClick={exportJsonReport}>导出 JSON 摘要</Button><Button disabled={!ready || busy} onClick={() => setAttestationOpen(true)}>离线认证检查</Button>{busy && <Button onClick={stopProcessing}>取消处理</Button>}</Space>
          <StatCards stats={stats} />
          </>}
          <Card className="results-card" title={<Space><span>{category === 'all' ? '信息对照' : categories[category]}</span><Tag>{filtered.length}</Tag></Space>} extra={<Typography.Text type="secondary">{ready ? `显示 ${filtered.length} / ${rows.length} 项` : '等待导入两份指纹'}</Typography.Text>}>
            <Space className="filter-row" wrap size={[12, 12]}><Input prefix={<SearchOutlined />} placeholder="搜索名称、字段或来源" value={search} onChange={event => setSearch(event.target.value)} allowClear style={{ width: 320 }} /><Select aria-label="筛选对比状态" value={status} onChange={value => setStatus(value)} style={{ width: 170 }} options={[{ value: 'all', label: '全部状态' }, { value: 'different', label: '值不同' }, { value: 'same', label: '相同' }, { value: 'left-only', label: '仅左侧' }, { value: 'right-only', label: '仅右侧' }, { value: 'incomparable', label: '不可比较' }]} /></Space>
            <Table<CompareRow> className="comparison-table" rowKey="id" loading={loading.compare || !ready && busy} dataSource={filtered} columns={columns} pagination={{ pageSize: 50, showSizeChanger: false, showQuickJumper: true, showTotal: total => `共 ${total} 项` }} scroll={{ x: 1060, y: 'calc(100vh - 440px)' }} onRow={row => ({ onClick: () => setSelected(row), tabIndex: 0, onKeyDown: event => { if (event.key === 'Enter' || event.key === ' ') setSelected(row); } })} locale={{ emptyText: ready ? <Empty description="当前条件下没有结果" /> : <Empty description="请先导入两份设备指纹" /> }} />
          </Card>
        </div></Content>
      </Layout>
    </Layout>
    <ComparisonDrawer row={selected} open={!!selected} onClose={() => setSelected(undefined)} />
    <AttestationDrawer open={attestationOpen} onClose={() => setAttestationOpen(false)} request={request} />
  </ConfigProvider>;
}
