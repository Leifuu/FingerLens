import type { MergedDisplayRow } from './display-compare';
import { codecEncoderDisplayRows, type CodecEncoderDisplayRow } from './codec-encoder-display';
import { codecPerformanceRows, type CodecPerformanceRow } from './codec-performance-display';
import type { Json } from './types';

export interface CodecDisplayRow {
  key: string;
  name: MergedDisplayRow;
  encoder: MergedDisplayRow;
  canonicalName?: MergedDisplayRow;
  hardwareAccelerated?: MergedDisplayRow;
  softwareOnly?: MergedDisplayRow;
  vendor?: MergedDisplayRow;
  securityModel?: MergedDisplayRow;
  securityModelName?: MergedDisplayRow;
  securityModelStatus?: MergedDisplayRow;
  supportedTypes: MergedDisplayRow[];
  encoderControls: CodecEncoderDisplayRow[];
  performancePoints: CodecPerformanceRow[];
  state: MergedDisplayRow['state'];
}

// 业务块：按原始数组序号聚合，保留别名与重复条目；任何完整字段差异都影响概览状态。
export function codecDisplayRows(rows: MergedDisplayRow[], leftCodecs?: Json[], rightCodecs?: Json[]): CodecDisplayRow[] | undefined {
  const groups = new Map<string, MergedDisplayRow[]>();
  for (const row of rows) {
    const match = /^\[(\d+)\]\./.exec(row.label);
    if (!match) return undefined;
    const fields = groups.get(match[1]) || [];
    fields.push(row);
    groups.set(match[1], fields);
  }
  const result: CodecDisplayRow[] = [];
  for (const [key, fields] of groups) {
    const field = (name: string) => fields.find(row => row.label === `[${key}].${name}`);
    const name = field('name'), encoder = field('encoder');
    if (!name || !encoder) return undefined;
    const present = (side: 'left' | 'right') => fields.some(row => row[side] !== null);
    const left = present('left'), right = present('right');
    for (const side of ['left', 'right'] as const) {
      if (present(side) && (name[side] === null || encoder[side] === null)) return undefined;
    }
    const performancePoints = codecPerformanceRows(leftCodecs?.[Number(key)], rightCodecs?.[Number(key)]);
    result.push({ key, name, encoder, canonicalName: field('canonicalName'), hardwareAccelerated: field('hardwareAccelerated'), softwareOnly: field('softwareOnly'), vendor: field('vendor'), securityModel: field('securityModel'), securityModelName: field('securityModelName'), securityModelStatus: field('securityModelStatus'),
      supportedTypes: fields.filter(row => /^\[\d+\]\.supportedTypes(?:\[\d+\])?$/.test(row.label)),
      encoderControls: codecEncoderDisplayRows(fields, key),
      performancePoints,
      state: !left ? 'right-only' : !right ? 'left-only' : fields.some(row => row.state !== 'same') || performancePoints.some(row => row.state !== 'same') ? 'changed' : 'same' });
  }
  return result.length ? result : undefined;
}
