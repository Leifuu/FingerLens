import type { Imported, Item, Row, Rules } from '../core/types';
import { categories, diffLabels, statusLabels } from '../core/types';
import { buildSections } from '../display';
import { mergeDisplaySections, type MergedDisplayRow, type MergedDisplaySection } from '../core/display-compare';

export interface ReportView {
  category: string;
  search: string;
  onlyDiff: boolean;
  status: string;
  scope: '当前视图' | '完整差异';
}

export interface DiffReportInput {
  left: Imported;
  right: Imported;
  rows: Row[];
  rules: Rules;
  warnings: string[];
  view: ReportView;
}

const MAX_VALUE_CHARS = 24_000;

export function isDirectoryMetadataBatch(row: Row): boolean {
  return row.category === 'directories' || row.key === 'directories/scope' || row.key === 'directories/summary' || row.key.startsWith('directories/chunk/');
}

export function isPackageManagerEnumeration(row: Row): boolean {
  const apis = [row.left?.source.api, row.right?.source.api].filter((api): api is string => !!api);
  return row.key === 'packages/enumeration' || apis.some(api => /^PackageManager\.getInstalledPackages(?:\(|$)/.test(api));
}

function isReportExcludedRow(row: Row): boolean {
  return isDirectoryMetadataBatch(row) || isPackageManagerEnumeration(row);
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character] || character);
}

function valueText(value: unknown): string {
  if (value === undefined) return '此侧没有对应采集项';
  if (value === null) return 'null';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  try { return JSON.stringify(value, null, 2); } catch { return '[无法序列化的值]'; }
}

function limitedCell(value: string | null): { text: string; truncated: boolean } {
  if (value === null) return { text: '此侧没有对应采集项', truncated: false };
  if (value.length <= MAX_VALUE_CHARS) return { text: value, truncated: false };
  return { text: `${value.slice(0, MAX_VALUE_CHARS)}\n\n[报告展示已截断，完整内容请以原始文件为准]`, truncated: true };
}

function itemMeta(item?: Item): string {
  if (!item) return '—';
  return `${statusLabels[item.status] || item.status} · ${item.source.api || item.source.kind || '未知来源'} · UID ${item.source.uid} · PID ${item.source.pid}`;
}

function appValue(imported: Imported, key: string): string {
  return valueText(imported.fingerprint.data.app[key]);
}

function stateClass(state: Row['state']): string {
  return state === 'same' ? 'same' : state === 'incomparable' ? 'incomparable' : 'changed';
}

function stateColor(state: Row['state']): string {
  return state === 'same' ? 'green' : state === 'incomparable' ? 'gray' : 'orange';
}

function formatTime(value: string | null): string {
  if (!value) return '未完成';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
}

function displaySections(row: Row): MergedDisplaySection[] {
  const left = new Map(buildSections(row.left).map(section => [section.key, section]));
  const right = new Map(buildSections(row.right).map(section => [section.key, section]));
  return [...new Set([...left.keys(), ...right.keys()])]
    .map(key => mergeDisplaySections(left.get(key), right.get(key)))
    .filter(section => section.rows.length);
}

function comparisonRowHtml(record: MergedDisplayRow): string {
  const left = limitedCell(record.left);
  const right = limitedCell(record.right);
  const label = record.state === 'same' ? '相同' : record.state === 'changed' ? '值不同' : record.state === 'left-only' ? '仅设备 A' : '仅设备 B';
  return `<tr class="comparison-row row-${record.state}">
    <th scope="row"><span class="field-label">${escapeHtml(record.label)}</span></th>
    <td><pre>${escapeHtml(left.text)}</pre>${left.truncated ? '<small class="cell-note">展示内容已截断</small>' : ''}</td>
    <td><pre>${escapeHtml(right.text)}</pre>${right.truncated ? '<small class="cell-note">展示内容已截断</small>' : ''}</td>
    <td><span class="row-state state-${record.state}">${label}</span></td>
  </tr>`;
}

function comparisonSectionsHtml(row: Row): string {
  const sections = displaySections(row);
  if (!sections.length) return '<div class="empty compact-empty">没有可展开的逐行内容。</div>';
  return `<div class="comparison-sections">${sections.map(section => `<section class="comparison-section"><h4>${escapeHtml(section.title)}</h4><div class="table-scroll"><table class="comparison-table"><thead><tr><th scope="col">字段</th><th scope="col">设备 A</th><th scope="col">设备 B</th><th scope="col">对比</th></tr></thead><tbody>${section.rows.map(comparisonRowHtml).join('')}</tbody></table></div></section>`).join('')}</div>`;
}

function stat(label: string, value: number, tone: string): string {
  return `<div class="stat stat-${tone}"><strong>${value}</strong><span>${label}</span></div>`;
}

function rowHtml(row: Row): string {
  const paths = row.changedPaths.length
    ? `<ul class="path-list">${row.changedPaths.slice(0, 80).map(path => `<li><code>${escapeHtml(path)}</code></li>`).join('')}${row.changedPaths.length > 80 ? `<li class="muted">以及另外 ${row.changedPaths.length - 80} 条路径</li>` : ''}</ul>`
    : '<span class="muted">没有字段路径差异</span>';
  return `<article class="diff-card ${stateClass(row.state)}" data-state="${row.state}">
    <div class="diff-card-head">
      <div><h3>${escapeHtml(row.name)}</h3><code>${escapeHtml(row.key)}</code></div>
      <div class="card-actions"><button type="button" class="row-filter-toggle" data-filter-toggle aria-pressed="false" title="隐藏绿色一致行，只保留有差异或单侧存在的行">只显示差异行</button><span class="status-pill pill-${stateColor(row.state)}">${escapeHtml(diffLabels[row.state])}</span></div>
    </div>
    <div class="diff-meta"><span>${escapeHtml(categories[row.category] || row.category)}</span><span>${row.ignored ? `已按规则忽略 ${row.ignored} 项` : '未忽略字段'}</span></div>
    <div class="value-grid">
      <div class="value-panel"><div class="panel-label"><span>设备 A</span><small>${escapeHtml(itemMeta(row.left))}</small></div></div>
      <div class="value-panel"><div class="panel-label"><span>设备 B</span><small>${escapeHtml(itemMeta(row.right))}</small></div></div>
    </div>
    <div class="row-legend" aria-label="逐行对比说明"><span class="legend-swatch legend-same"></span>一致 <span class="legend-swatch legend-changed"></span>不同 <span class="legend-swatch legend-missing"></span>单侧存在</div>
    ${comparisonSectionsHtml(row)}
    <div class="same-rows-empty" hidden>当前检测项没有差异行。</div>
    <div class="paths"><strong>变化路径</strong>${paths}</div>
  </article>`;
}

function categorySections(rows: Row[]): string {
  const grouped = new Map<string, Row[]>();
  rows.forEach(row => grouped.set(row.category, [...(grouped.get(row.category) || []), row]));
  return [...grouped.entries()].map(([category, items], index) => `<section class="module" id="module-${index}">
    <div class="module-heading"><div><span class="module-kicker">${String(index + 1).padStart(2, '0')}</span><h2>${escapeHtml(categories[category] || category)}</h2></div><span class="module-count">${items.length} 项</span></div>
    ${items.map(rowHtml).join('')}
  </section>`).join('');
}

export function reportFileName(left: Imported, right: Imported): string {
  const safe = (value: string) => value.replace(/[^a-zA-Z0-9_-]+/g, '-').slice(0, 32) || 'session';
  const stamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\.\d{3}Z$/, 'Z');
  return `fingerprint-diff-${safe(left.fingerprint.data.sessionId)}-${safe(right.fingerprint.data.sessionId)}-${stamp}.html`;
}

export function buildDiffReportHtml(input: DiffReportInput): string {
  const { left, right, rules, warnings, view } = input;
  const excludedDirectoryBatches = input.rows.filter(isDirectoryMetadataBatch).length;
  const excludedPackageItems = input.rows.filter(isPackageManagerEnumeration).length;
  const rows = input.rows.filter(row => !isReportExcludedRow(row));
  const changed = rows.filter(row => row.state !== 'same');
  const counts = {
    total: rows.length,
    changed: changed.length,
    same: rows.filter(row => row.state === 'same').length,
    status: rows.filter(row => row.state === 'status').length,
    oneSide: rows.filter(row => row.state === 'left-only' || row.state === 'right-only').length,
    incomparable: rows.filter(row => row.state === 'incomparable').length,
  };
  const generatedAt = new Date().toLocaleString('zh-CN', { hour12: false });
  const leftState = left.fingerprint.data.state;
  const rightState = right.fingerprint.data.state;
  const warningHtml = warnings.length ? `<section class="notice notice-warn"><strong>比较条件提示</strong><ul>${warnings.map(item => `<li>${escapeHtml(item)}</li>`).join('')}</ul></section>` : '';
  const exclusions: string[] = [];
  if (excludedDirectoryBatches) exclusions.push(`已忽略 ${excludedDirectoryBatches} 个目录元数据批次`);
  if (excludedPackageItems) exclusions.push(`已忽略 ${excludedPackageItems} 个 PackageManager.getInstalledPackages 对比项`);
  const excludedHtml = exclusions.length ? `<section class="notice notice-info"><strong>报告范围调整</strong><p>${exclusions.map(item => `${escapeHtml(item)}。`).join('<br>')} 原始指纹文件和 JSON 摘要不受影响。</p></section>` : '';
  const nav = rows.length ? `<nav class="toc"><strong>报告目录</strong><a href="#summary">摘要</a><a href="#conditions">采集条件</a>${[...new Set(rows.map(row => row.category))].map((category, index) => `<a href="#module-${index}">${escapeHtml(categories[category] || category)}</a>`).join('')}</nav>` : '';
  const empty = '<div class="empty">当前报告范围内没有可展示的比较项。</div>';
  return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="color-scheme" content="light"><title>FingerLens · 设备指纹差异报告</title><style>
:root{--ink:#17212b;--muted:#5f6b76;--line:#d9e0e6;--paper:#f4f6f8;--card:#fff;--navy:#102a43;--cyan:#0f8b8d;--orange:#c46b19;--green:#18794e;--gray:#65717c}*{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;background:var(--paper);color:var(--ink);font:14px/1.65 "Segoe UI","Microsoft YaHei",sans-serif}a{color:var(--navy);text-decoration:none}a:hover{text-decoration:underline}.report-shell{max-width:1180px;margin:0 auto;padding:48px 28px 80px}.cover{position:relative;overflow:hidden;background:var(--navy);color:#fff;padding:48px;border-radius:4px;box-shadow:0 14px 40px rgba(16,42,67,.18)}.cover:after{content:"";position:absolute;right:-120px;bottom:-170px;width:420px;height:420px;border:1px solid rgba(77,208,225,.45);border-radius:50%;box-shadow:0 0 0 30px rgba(77,208,225,.08),0 0 0 60px rgba(77,208,225,.06)}.brand{font-size:12px;letter-spacing:.16em;color:#8bd3dd}.cover h1{max-width:650px;margin:42px 0 16px;font-size:clamp(40px,7vw,76px);line-height:1.04;letter-spacing:-.04em;font-weight:650}.cover p{max-width:620px;margin:0;color:#c7d8e3;font-size:16px}.cover-meta{position:relative;z-index:1;display:grid;grid-template-columns:repeat(3,1fr);gap:22px;margin-top:54px;padding-top:20px;border-top:1px solid rgba(255,255,255,.23)}.cover-meta span{display:block;color:#8fa7b6;font-size:12px}.cover-meta strong{display:block;margin-top:4px;font-size:15px;font-weight:500;overflow-wrap:anywhere}.toc{position:sticky;top:0;z-index:20;display:flex;gap:18px;align-items:center;flex-wrap:wrap;margin:0 -28px;padding:14px 28px;border-bottom:1px solid var(--line);background:rgba(244,246,248,.96);backdrop-filter:blur(10px);color:var(--muted)}.toc strong{color:var(--ink);margin-right:4px}.toc a{font-size:13px}.section{margin-top:42px;scroll-margin-top:76px}.section h2{margin:0 0 16px;font-size:24px;letter-spacing:-.02em}.summary-grid{display:grid;grid-template-columns:repeat(6,1fr);gap:10px}.stat{background:var(--card);box-shadow:inset 0 3px 0 var(--line);padding:16px 14px;min-height:92px}.stat strong{display:block;font-size:30px;line-height:1.1}.stat span{display:block;margin-top:8px;color:var(--muted);font-size:12px}.stat-orange{box-shadow:inset 0 3px 0 var(--orange)}.stat-green{box-shadow:inset 0 3px 0 var(--green)}.stat-gray{box-shadow:inset 0 3px 0 var(--gray)}.notice{margin-top:18px;background:var(--card);padding:18px 22px;border-left:4px solid var(--orange)}.notice-info{border-left-color:var(--cyan)}.notice strong{display:block;margin-bottom:6px}.notice p{margin:0}.notice ul{margin:0;padding-left:20px}.conditions{display:grid;grid-template-columns:1fr 1fr;gap:14px}.condition-card{background:var(--card);padding:18px 20px;border:1px solid var(--line)}.condition-card h3{margin:0 0 10px;font-size:14px}.condition-card dl{display:grid;grid-template-columns:145px 1fr;gap:5px 12px;margin:0}.condition-card dt{color:var(--muted)}.condition-card dd{margin:0;overflow-wrap:anywhere}.module{margin-top:48px;scroll-margin-top:76px}.module-heading{display:flex;align-items:end;justify-content:space-between;gap:20px;border-bottom:2px solid var(--navy);padding-bottom:10px}.module-heading>div{display:flex;align-items:baseline;gap:12px}.module-heading h2{margin:0;font-size:25px}.module-kicker{color:var(--cyan);font:600 13px Consolas,monospace}.module-count{color:var(--muted);font-size:13px}.diff-card{margin-top:16px;background:var(--card);border:1px solid var(--line);border-left:4px solid var(--orange);break-inside:avoid}.diff-card.same{border-left-color:var(--green)}.diff-card.incomparable{border-left-color:var(--gray)}.diff-card-head{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;padding:18px 20px 8px}.diff-card h3{margin:0 0 3px;font-size:17px}.diff-card code,.path-list code{font:12px/1.5 Consolas,"Microsoft YaHei",monospace;color:var(--muted);overflow-wrap:anywhere}.card-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap;justify-content:flex-end}.row-filter-toggle{border:1px solid var(--cyan);border-radius:999px;background:#fff;color:var(--navy);padding:4px 10px;font:inherit;font-size:12px;cursor:pointer;white-space:nowrap}.row-filter-toggle:hover,.row-filter-toggle:focus-visible{background:#e8f7f8;outline:2px solid rgba(15,139,141,.24);outline-offset:2px}.diff-card.hide-same-rows .row-filter-toggle{background:var(--cyan);color:#fff}.status-pill{flex:0 0 auto;border:1px solid currentColor;border-radius:999px;padding:2px 9px;font-size:12px;white-space:nowrap}.pill-orange{color:var(--orange)}.pill-green{color:var(--green)}.pill-gray{color:var(--gray)}.diff-meta{display:flex;gap:18px;flex-wrap:wrap;padding:0 20px 14px;color:var(--muted);font-size:12px}.value-grid{display:grid;grid-template-columns:1fr 1fr;gap:1px;background:var(--line);border-top:1px solid var(--line)}.value-panel{min-width:0;background:#fbfcfd}.panel-label{display:flex;justify-content:space-between;gap:12px;padding:10px 14px;background:#edf2f5;border-bottom:1px solid var(--line);font-weight:600}.panel-label small{color:var(--muted);font-weight:400;text-align:right;overflow-wrap:anywhere}.row-legend{display:flex;gap:7px;align-items:center;padding:11px 20px 0;color:var(--muted);font-size:12px}.legend-swatch{width:11px;height:11px;border:1px solid rgba(23,33,43,.16);display:inline-block;margin-left:8px}.legend-same{background:#ecf8f0}.legend-changed{background:#fff0ef}.legend-missing{background:#fff8e6}.comparison-sections{padding:0 20px}.comparison-section{margin-top:16px;border:1px solid var(--line);background:#fff}.comparison-section h4{margin:0;padding:10px 12px;background:#f4f6f8;border-bottom:1px solid var(--line);font-size:13px}.table-scroll{overflow-x:auto}.comparison-table{width:100%;border-collapse:collapse;table-layout:fixed;font-size:12px}.comparison-table th,.comparison-table td{padding:9px 10px;border-bottom:1px solid rgba(217,224,230,.8);text-align:left;vertical-align:top}.comparison-table thead th{background:#edf2f5;color:var(--muted);font-weight:600;white-space:nowrap}.comparison-table th:first-child{width:19%}.comparison-table th:last-child{width:92px}.comparison-table tbody tr:last-child>*{border-bottom:0}.comparison-table tr.row-same>*{background:#ecf8f0}.comparison-table tr.row-changed>*{background:#fff0ef}.comparison-table tr.row-left-only>*,.comparison-table tr.row-right-only>*{background:#fff8e6}.diff-card.hide-same-rows .comparison-table tr.row-same{display:none}.comparison-table pre{margin:0;max-height:190px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;font:12px/1.55 Consolas,"Microsoft YaHei",monospace}.field-label{font-weight:600;overflow-wrap:anywhere}.cell-note{display:block;margin-top:5px;color:var(--orange)}.row-state{display:inline-block;padding:2px 6px;border:1px solid currentColor;border-radius:4px;white-space:nowrap;font-size:11px}.state-same{color:var(--green)}.state-changed{color:#b33a34}.state-left-only,.state-right-only{color:#9a6812}.same-rows-empty{margin:16px 20px;padding:14px;background:#f4f6f8;border:1px dashed var(--line);color:var(--muted);text-align:center}.compact-empty{margin:16px 20px}.paths{padding:14px 20px 18px}.path-list{display:flex;gap:6px;flex-wrap:wrap;margin:8px 0 0;padding:0;list-style:none}.path-list li{background:#f0f3f5;padding:2px 7px}.muted{color:var(--muted)}.empty{padding:36px;background:var(--card);border:1px dashed var(--line);text-align:center;color:var(--muted)}.report-filter{position:fixed;right:22px;bottom:22px;z-index:10;display:flex;align-items:center;gap:12px;padding:10px 14px;background:rgba(16,42,67,.96);color:#fff;box-shadow:0 8px 24px rgba(16,42,67,.22);border-radius:999px;font-size:12px}.report-filter span{color:#9edee5;font-weight:600}.report-filter label{display:inline-flex;align-items:center;gap:5px;cursor:pointer;white-space:nowrap}.report-filter input{accent-color:#70d3dc}.footer{margin-top:54px;padding-top:18px;border-top:1px solid var(--line);color:var(--muted);font-size:12px}.footer strong{color:var(--ink)}@media(max-width:800px){.report-shell{padding:20px 14px 50px}.cover{padding:28px 24px}.cover h1{margin-top:30px}.cover-meta{grid-template-columns:1fr;gap:12px;margin-top:34px}.summary-grid{grid-template-columns:repeat(3,1fr)}.conditions,.value-grid{grid-template-columns:1fr}.toc{margin:0 -14px;padding-left:14px;padding-right:14px}.panel-label{display:block}.panel-label small{display:block;text-align:left;margin-top:4px}.module-heading h2{font-size:21px}.comparison-table{min-width:720px}}@media(max-width:480px){.summary-grid{grid-template-columns:repeat(2,1fr)}.diff-card-head{display:block}.card-actions{justify-content:flex-start;margin-top:10px}.status-pill{display:inline-block;margin-top:0}.condition-card dl{grid-template-columns:1fr}.report-filter{right:10px;bottom:10px;left:10px;justify-content:center;border-radius:12px;flex-wrap:wrap}}@media print{body{background:#fff}.report-shell{max-width:none;padding:0}.cover{box-shadow:none;border-radius:0;min-height:250px}.toc{display:none}.report-filter{display:none}.module{margin-top:28px}.diff-card{box-shadow:none}.value-panel pre{max-height:none;overflow:visible}.comparison-table pre{max-height:none;overflow:visible}.row-filter-toggle{display:none}.footer{margin-bottom:0}a{color:inherit;text-decoration:none}}
</style></head><body><main class="report-shell">
<header class="cover"><div class="brand">FINGERLENS / 观纹 · LOCAL EVIDENCE REPORT</div><h1>设备指纹<br>差异报告</h1><p>基于两份本地采集原件生成的可复核对照。报告保留来源边界，不对差异作风控结论。</p><div class="cover-meta"><div><span>报告范围</span><strong>${escapeHtml(view.scope)}</strong></div><div><span>生成时间</span><strong>${escapeHtml(generatedAt)}</strong></div><div><span>比较模式</span><strong>${rules.semantic ? '语义对比' : '原始对比'}${rules.ignoreDynamic ? ' · 忽略动态字段' : ''}</strong></div></div></header>
${nav}<section id="summary" class="section"><h2>执行摘要</h2><div class="summary-grid">${stat('比较项', counts.total, 'gray')}${stat('存在差异', counts.changed, 'orange')}${stat('相同', counts.same, 'green')}${stat('状态不同', counts.status, 'orange')}${stat('仅一侧存在', counts.oneSide, 'orange')}${stat('不可比较', counts.incomparable, 'gray')}</div>${warningHtml}${excludedHtml}</section>
<section id="conditions" class="section"><h2>采集条件</h2><div class="conditions"><div class="condition-card"><h3>设备 A · ${escapeHtml(left.name)}</h3><dl><dt>SHA-256</dt><dd><code>${escapeHtml(left.sha256)}</code></dd><dt>应用包名</dt><dd>${escapeHtml(appValue(left, 'packageName'))}</dd><dt>采集状态</dt><dd>${escapeHtml(leftState)}</dd><dt>采集时间</dt><dd>${escapeHtml(formatTime(left.fingerprint.data.startedAt))} → ${escapeHtml(formatTime(left.fingerprint.data.finishedAt))}</dd><dt>采集器版本</dt><dd>${escapeHtml(left.fingerprint.data.collectorVersion)}</dd></dl></div><div class="condition-card"><h3>设备 B · ${escapeHtml(right.name)}</h3><dl><dt>SHA-256</dt><dd><code>${escapeHtml(right.sha256)}</code></dd><dt>应用包名</dt><dd>${escapeHtml(appValue(right, 'packageName'))}</dd><dt>采集状态</dt><dd>${escapeHtml(rightState)}</dd><dt>采集时间</dt><dd>${escapeHtml(formatTime(right.fingerprint.data.startedAt))} → ${escapeHtml(formatTime(right.fingerprint.data.finishedAt))}</dd><dt>采集器版本</dt><dd>${escapeHtml(right.fingerprint.data.collectorVersion)}</dd></dl></div></div></section>
<section class="section"><h2>差异明细</h2>${rows.length ? categorySections(rows) : empty}</section>
<div class="report-filter" role="group" aria-label="报告内容筛选"><span>显示内容</span><label><input type="radio" name="report-diff-filter" value="all" checked>全部对比项</label><label><input type="radio" name="report-diff-filter" value="different">仅值不同</label></div>
<footer class="footer"><strong>报告说明</strong> · 原始文件未被改写；报告展示值可能因单项过大而截断。比较结果只描述两份采集快照的可见差异，不等同于设备真实性、远程证明或第三方风控结论。筛选条件：${escapeHtml(view.category === 'all' ? '全部模块' : categories[view.category] || view.category)} / ${escapeHtml(view.status === 'all' ? '全部状态' : view.status)}${view.onlyDiff ? ' / 仅差异' : ''}${view.search ? ` / 搜索“${escapeHtml(view.search)}”` : ''}</footer>
</main><script>(() => { const controls = document.querySelectorAll('input[name="report-diff-filter"]'); const cards = document.querySelectorAll('.diff-card'); const modules = document.querySelectorAll('.module'); const apply = (mode) => { cards.forEach(card => { card.style.display = mode === 'different' && card.dataset.state !== 'different' ? 'none' : ''; }); modules.forEach(module => { module.style.display = [...module.querySelectorAll('.diff-card')].some(card => card.style.display !== 'none') ? '' : 'none'; }); }; controls.forEach(control => control.addEventListener('change', () => apply(control.value))); document.querySelectorAll('[data-filter-toggle]').forEach(control => control.addEventListener('click', () => { const card = control.closest('.diff-card'); if (!card) return; const active = control.getAttribute('aria-pressed') !== 'true'; card.classList.toggle('hide-same-rows', active); control.setAttribute('aria-pressed', String(active)); control.textContent = active ? '显示全部行' : '只显示差异行'; const empty = card.querySelector('.same-rows-empty'); if (empty) empty.hidden = !active || card.querySelector('.comparison-row:not(.row-same)') !== null; })); })();</script></body></html>`;
}
