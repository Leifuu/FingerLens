import type { MergedDisplayRow } from './display-compare';
import { differences } from './compare';
import type { Json } from './types';

export interface DisplayModeRow {
  key: string;
  modeId: MergedDisplayRow;
  width: MergedDisplayRow;
  height: MergedDisplayRow;
  refreshRate: MergedDisplayRow;
  hdr: { left: string; right: string };
  state: MergedDisplayRow['state'];
}

const hdrNames = new Map([['1', 'Dolby Vision'], ['2', 'HDR10'], ['3', 'HLG'], ['4', 'HDR10+']]);
const statuses = new Map([['ok', '成功'], ['partial', '部分返回'], ['unsupported', '系统不支持查询'], ['denied', '读取受限'], ['error', '读取异常']]);
const raw = (value: string | null | undefined) => value === '' ? '（空字符串）' : value ?? '未采集';

// 业务块：以原始数组序号比较模式，不用分辨率或ID合并；原始对象差异保留格式化文字隐藏的类型变化。
export function displayModeRows(rows: MergedDisplayRow[], leftModes?: Json[], rightModes?: Json[]): DisplayModeRow[] | undefined {
  const groups = new Map<string, MergedDisplayRow[]>();
  for (const row of rows) {
    const match = /^\[(\d+)\]\./.exec(row.label);
    if (!match) return undefined;
    const fields = groups.get(match[1]) || [];
    fields.push(row);
    groups.set(match[1], fields);
  }
  const result: DisplayModeRow[] = [];
  for (const [key, fields] of groups) {
    const field = (name: string) => fields.find(row => row.label === `[${key}].${name}`);
    const modeId = field('modeId'), width = field('physicalWidth'), height = field('physicalHeight'), refreshRate = field('refreshRate');
    if (!modeId || !width || !height || !refreshRate) return undefined;
    const present = (side: 'left' | 'right') => fields.some(row => row[side] !== null);
    const left = present('left'), right = present('right');
    for (const side of ['left', 'right'] as const) {
      if (present(side) && [modeId, width, height, refreshRate].some(row => row[side] === null)) return undefined;
    }
    const hdr = (side: 'left' | 'right') => {
      if (!present(side)) return '—';
      const prefix = `[${key}].hdrTypeSupport`;
      if (!fields.some(row => (row.label === prefix || row.label.startsWith(prefix + '.') || row.label.startsWith(prefix + '[')) && row[side] !== null)) return '未采集';
      const direct = field('hdrTypeSupport')?.[side];
      if (direct !== null && direct !== undefined) return `原始值 ${raw(direct)}`;
      const status = field('hdrTypeSupport.status')?.[side];
      const types = fields.filter(row => /^\[\d+\]\.hdrTypeSupport\.types\[\d+\]$/.test(row.label) && row[side] !== null).map(row => row[side]!);
      const names = types.slice(0, 8).map(type => hdrNames.has(type) ? `${hdrNames.get(type)} (${type})` : raw(type));
      const complexTypes = fields.some(row => (row.label.startsWith(prefix + '.types.') || row.label.startsWith(prefix + '.types[')) && row[side] !== null);
      const typeText = types.length ? names.join('、') + (types.length > 8 ? '…（仅列前8项）' : '') : complexTypes ? '其他结构（详见完整字段）' : raw(field('hdrTypeSupport.types')?.[side]);
      const parts = [statuses.get(status ?? '') ?? raw(status), `类型 ${typeText}`];
      const count = field('hdrTypeSupport.typeCount')?.[side];
      if (count !== null && count !== undefined) parts.push(`数量 ${raw(count)}`);
      const truncated = field('hdrTypeSupport.typesTruncated')?.[side];
      if (truncated === 'true') parts.push(`已截断（返回 ${raw(field('hdrTypeSupport.returnedCount')?.[side])} 项）`);
      else if (truncated !== null && truncated !== undefined && truncated !== 'false') parts.push(`截断状态 ${raw(truncated)}`);
      return parts.join(' · ');
    };
    result.push({ key, modeId, width, height, refreshRate, hdr: { left: hdr('left'), right: hdr('right') },
      state: !left ? 'right-only' : !right ? 'left-only' : fields.some(row => row.state !== 'same') || leftModes && rightModes && differences(leftModes[Number(key)], rightModes[Number(key)]).length ? 'changed' : 'same' });
  }
  return result.length ? result : undefined;
}
