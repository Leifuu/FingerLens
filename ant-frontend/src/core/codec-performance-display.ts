import { differences } from './compare';
import type { MergedDisplayRow } from './display-compare';
import type { Json } from './types';

const object = (value: Json | undefined): value is Record<string, Json> => value !== null && typeof value === 'object' && !Array.isArray(value);
const scalar = (value: Json | undefined) => value === null || typeof value !== 'object';
export const codecPerformanceText = (value: Json | undefined): string => value === undefined ? '未采集' : value === '' ? '（空字符串）' : scalar(value) ? JSON.stringify(value) : '其他结构（详见完整字段）';
const state = (a: Json | undefined, b: Json | undefined): MergedDisplayRow['state'] => a === undefined && b === undefined ? 'same' : a === undefined ? 'right-only' : b === undefined ? 'left-only' : differences(a, b).length ? 'changed' : 'same';
const statuses = new Map([['ok', '成功'], ['partial', '部分返回'], ['not-video', '非视频'], ['not-published', '未发布性能点信息'], ['unsupported', '系统不支持查询'], ['denied', '读取受限'], ['error', '读取异常']]);

export interface CodecPerformanceRow {
  key: string;
  left?: Record<string, Json>;
  right?: Record<string, Json>;
  state: MergedDisplayRow['state'];
}

const points = (cap?: Record<string, Json>): Json[] => object(cap?.performancePoints) && Array.isArray(cap.performancePoints.points) ? cap.performancePoints.points : [];

// 业务块：最多256个MIME/每项64点进入概览；按原始序号配对，异构结构继续留在完整字段中。
export function codecPerformanceRows(left: Json | undefined, right: Json | undefined): CodecPerformanceRow[] {
  const list = (value: Json | undefined) => object(value) && Array.isArray(value.capabilities) ? value.capabilities : [];
  const a = list(left), b = list(right);
  if (a.length > 256 || b.length > 256) return [];
  const valid = (cap: Json | undefined) => {
    if (cap === undefined) return true;
    if (!object(cap) || !scalar(cap.type)) return false;
    const support = cap.performancePoints;
    if (support === undefined) return true;
    if (!object(support)) return false;
    const values = support.points;
    return values === undefined || values === null || Array.isArray(values) && values.length <= 64 && values.every(point => object(point) && scalar(point.description));
  };
  const rows: CodecPerformanceRow[] = [];
  for (let index = 0; index < Math.max(a.length, b.length); index++) {
    const l = a[index], r = b[index];
    if (!valid(l) || !valid(r)) continue;
    const lc = object(l) ? l : undefined, rc = object(r) ? r : undefined;
    if (lc?.performancePoints === undefined && rc?.performancePoints === undefined) continue;
    const difference = state(lc?.performancePoints, rc?.performancePoints);
    rows.push({ key: String(index), left: lc, right: rc, state: difference === 'same' && state(lc?.type, rc?.type) !== 'same' ? 'changed' : difference });
  }
  return rows;
}

// 业务块：状态、空/null、原始数量/类型与截断分别展示；哈希及未知字段仍决定整行差异。
export function codecPerformanceSummary(cap?: Record<string, Json>): string {
  const support = cap?.performancePoints;
  if (support === undefined) return '未采集';
  if (!object(support)) return `原始值 ${codecPerformanceText(support)}`;
  const label = typeof support.status === 'string' ? statuses.get(support.status) : undefined;
  const status = label ? `${label}（${support.status}）` : `查询状态 ${codecPerformanceText(support.status)}`;
  const values = support.points;
  const detail = values === undefined ? 'points未采集' : values === null ? 'points=null' : Array.isArray(values) ? values.length ? `明细 ${values.length} 点` : '空清单 []（未声明性能点保证）' : `points ${codecPerformanceText(values)}`;
  const error = support.errorClass === undefined ? '' : `\n错误 ${codecPerformanceText(support.errorClass)}`;
  return `${status} · ${detail}\n总数 ${codecPerformanceText(support.pointCount)} · 返回 ${codecPerformanceText(support.returnedCount)} · 上限 ${codecPerformanceText(support.pointLimit)}\n截断 ${codecPerformanceText(support.pointsTruncated)}${error}`;
}

export function codecPerformancePointFacts(point: Json | undefined): string[] | undefined {
  if (!object(point)) return undefined;
  const coverage = point.coversReferencePoints;
  const facts = [`描述 ${codecPerformanceText(point.description)}`];
  if (!object(coverage)) return [...facts, `参考点覆盖 ${codecPerformanceText(coverage)}`];
  for (const [name, size] of [['HD', '1280×720'], ['FHD', '1920×1080'], ['UHD', '3840×2160']]) {
    facts.push(`${name} ${size} · ${[30, 60, 120].map(fps => { const value = coverage[`${name}_${fps}`]; return `${fps}fps ${value === true ? '覆盖' : value === false ? '不覆盖' : codecPerformanceText(value)}`; }).join(' · ')}`);
  }
  const unknown = Object.keys(coverage).filter(key => !/^(?:HD|FHD|UHD)_(?:30|60|120)$/.test(key));
  if (unknown.length) facts.push(`其它参考点 ${unknown.length} 项 · 见完整字段`);
  return facts;
}

// 业务块：性能点不排序、不合并；完整原对象对比保留未知字段和布尔/字符串等类型差异。
export function codecPerformancePointRows(row: CodecPerformanceRow) {
  const a = points(row.left), b = points(row.right);
  return Array.from({ length: Math.max(a.length, b.length) }, (_, index) => ({ key: String(index), left: codecPerformancePointFacts(a[index]), right: codecPerformancePointFacts(b[index]), state: state(a[index], b[index]) }));
}
