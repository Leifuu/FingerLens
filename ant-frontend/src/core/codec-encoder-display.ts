import type { MergedDisplayRow } from './display-compare';

export interface CodecEncoderDisplayRow {
  key: string;
  mime?: MergedDisplayRow;
  fields: Map<string, MergedDisplayRow>;
  state: MergedDisplayRow['state'];
}

// 业务块：按MIME原始序号聚合编码参数，不按名称去重；未知字段仍参与整行差异。
export function codecEncoderDisplayRows(rows: MergedDisplayRow[], codecKey: string): CodecEncoderDisplayRow[] {
  const groups = new Map<string, Map<string, MergedDisplayRow>>();
  for (const row of rows) {
    const match = /^\[(\d+)\]\.capabilities\[(\d+)\]\.(.+)$/.exec(row.label);
    if (!match || match[1] !== codecKey) continue;
    const fields = groups.get(match[2]) || new Map<string, MergedDisplayRow>();
    fields.set(match[3], row);groups.set(match[2], fields);
  }
  const result: CodecEncoderDisplayRow[] = [];
  for (const [key, group] of groups) {
    // 旧/异构结构留给完整字段表，不能把显式null或复杂值误报为未采集。
    if (group.has('encoderCapabilities') || [...group.keys()].some(name => /^type[.\[]/.test(name))) continue;
    const fields = new Map([...group].filter(([name]) => name.startsWith('encoderCapabilities.')).map(([name, row]) => [name.slice('encoderCapabilities.'.length), row]));
    const present = (side: 'left' | 'right') => [...fields.values()].some(row => row[side] !== null);
    const left = present('left'), right = present('right');
    if (!left && !right) continue;
    if ((['left', 'right'] as const).some(side => present(side) && (!['true', 'false'].includes(fields.get('available')?.[side] ?? '') || fields.get('status')?.[side] == null))) continue;
    if ((['left', 'right'] as const).some(side => fields.get('available')?.[side] === 'false' && ['ok', 'partial'].includes(fields.get('status')?.[side] ?? ''))) continue;
    if ((['left', 'right'] as const).every(side => !present(side) || fields.get('status')?.[side] === 'not-encoder')) continue;
    const mime = group.get('type');
    result.push({ key, mime, fields, state: !left ? 'right-only' : !right ? 'left-only' : [...fields.values(), ...(mime ? [mime] : [])].some(row => row.state !== 'same') ? 'changed' : 'same' });
  }
  return result;
}

const statusLabels: Record<string, string> = { ok: '成功', partial: '部分可用', 'not-encoder': '非编码器', unsupported: '未支持查询', denied: '访问受限', error: '查询失败' };
const text = (value: string | null | undefined) => value === '' ? '（空字符串）' : value ?? '未采集';
const statusText = (value: string | null | undefined) => value == null ? '未采集' : Object.hasOwn(statusLabels, value) ? statusLabels[value] : text(value);

// 业务块：仅格式化原文，不数值转换；未采集、null、0值和成功false分别展示。
export function codecEncoderFacts(row: CodecEncoderDisplayRow, side: 'left' | 'right'): { key: string; label: string; value: string }[] {
  const read = (name: string) => row.fields.get(name)?.[side];
  const captured = (prefix: string) => [...row.fields].some(([name, field]) => name.startsWith(prefix) && field[side] !== null);
  const status = read('status');
  if (status == null) return [{ key: 'status', label: '编码能力', value: '未采集' }];
  const facts = [{ key: 'status', label: '编码能力', value: statusText(status) }];
  if (read('available') !== 'true') return facts;
  for (const [name, label] of [['complexity', '复杂度'], ['quality', '质量']]) {
    if (read(name) != null) { facts.push({ key: name, label, value: text(read(name)) });continue; }
    const state = read(`${name}.status`), lower = read(`${name}.range.lower`), upper = read(`${name}.range.upper`);
    const range = lower != null || upper != null ? `${text(lower)} – ${text(upper)}` : read(`${name}.range`) != null ? text(read(`${name}.range`)) : captured(`${name}.range.`) ? '见完整字段' : '未采集';
    facts.push({ key: name, label, value: state == null ? captured(`${name}.`) ? '见完整字段' : '未采集' : state === 'ok' ? range : `${statusText(state)}${lower != null || upper != null ? ` · ${range}` : ''}` });
  }
  if (read('bitrateModes') != null) { facts.push({ key: 'modes', label: '码率模式', value: text(read('bitrateModes')) });return facts; }
  const modes = new Set([...row.fields.keys()].flatMap(name => /^bitrateModes\.([^.]+)\.(?:mode|status|supported)$/.exec(name)?.[1] || []));
  // 模式明细限制为8项；未来/异构报告的长清单保留计数，完整原文仍可分页核对。
  for (const name of [...modes].slice(0, 8)) {
    const prefix = `bitrateModes.${name}`, mode = read(`${prefix}.mode`), state = read(`${prefix}.status`), supported = read(`${prefix}.supported`);
    facts.push({ key: `mode:${name}`, label: mode == null ? name : `${name} (${text(mode)})`, value: state === 'ok' ? supported === 'true' ? '支持' : supported === 'false' ? '不支持' : text(supported) : statusText(state) });
  }
  const remaining = [...modes].slice(8).filter(name => ['mode', 'status', 'supported'].some(field => read(`bitrateModes.${name}.${field}`) != null)).length;
  if (remaining) facts.push({ key: 'modes-more', label: '其他码率模式', value: `${remaining} 项 · 见完整字段` });
  if (!modes.size) facts.push({ key: 'modes', label: '码率模式', value: captured('bitrateModes.') ? '见完整字段' : '未采集' });
  return facts;
}
