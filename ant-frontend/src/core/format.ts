import type { Item, Json } from './types';
import { formatModule } from './module-format';

export function rawText(value: Json): string {
  return typeof value === 'string' ? value : JSON.stringify(value);
}

/** 格式化只改变显示；带 Item 时由模块专属 formatter 决定布局。 */
export function formattedText(value: Json, api = '', category = '', key = ''): string {
  const item = { key, category, value, source: { api, kind: 'unknown', uid: 0, pid: 0, selinux: '' }, dataType: typeof value, status: 'ok' } as Item;
  return formatModule(item).text;
}
