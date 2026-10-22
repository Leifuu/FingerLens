import Ajv from 'ajv';
import { visit } from 'jsonc-parser';
import schema from '../../../shared/fingerprint.schema.json';
import type { Fingerprint, Imported } from './types';
import { mergePackageInstallers } from './package-items';

export const MAX_JSON = 64 * 1024 * 1024;
export const MAX_GZIP = 16 * 1024 * 1024;
const validate = new Ajv({ allErrors: false, strict: false }).compile(schema);

// JSON.parse 会静默覆盖同名键，先用流式 visitor 校验键、深度和数值精度。
export function parseFingerprint(text: string): Fingerprint {
  if (new TextEncoder().encode(text).length > MAX_JSON) throw new Error('解压后文件超过 64 MiB');
  if (text.charCodeAt(0) === 0xfeff) throw new Error('文件必须是 UTF-8 无 BOM');
  const objects: (Set<string> | null)[] = [];
  let count = 0;
  const enter = (keys: Set<string> | null) => { if (objects.length >= 64) throw new Error('JSON 嵌套超过 64 层'); objects.push(keys); };
  visit(text, {
    onObjectBegin: () => enter(new Set()), onObjectEnd: () => { objects.pop(); },
    onArrayBegin: () => enter(null), onArrayEnd: () => { objects.pop(); },
    onObjectProperty: (key) => { const keys = objects.at(-1); if (keys?.has(key)) throw new Error(`存在重复 JSON 对象键：${key.slice(0, 100)}`); keys?.add(key); if (++count > 2_000_000) throw new Error('JSON 字段过多'); },
    onLiteralValue: (value, offset, length, _line, _column, getPath) => {
      if (typeof value !== 'number') return;
      const token = text.slice(offset, offset + length);
      // Float.MAX_VALUE 等科学计数浮点值在 JS 中也可能 isInteger；只对整数 token 应用 int64 检查。
      if (!Number.isFinite(value) || (/^-?\d+$/.test(token) && !Number.isSafeInteger(value))) throw new Error(`存在无法无损表示的整数或非有限数字（${getPath().join('/')}），整数请按 int64 字符串契约导出`);
    },
    onError: (error, offset) => { throw new Error(`非法 JSON（${error}，位置 ${offset}）`); },
  }, { disallowComments: true, allowTrailingComma: false, allowEmptyContent: false });
  const data: unknown = JSON.parse(text);
  if (!validate(data)) throw new Error(`指纹格式不匹配：${validate.errors?.[0]?.instancePath || '/'} ${validate.errors?.[0]?.message || ''}`);
  const fp = data as unknown as Fingerprint;
  const keys = new Set<string>();
  for (const item of fp.data.items) {
    if (keys.has(item.key)) throw new Error(`采集项 key 重复：${item.key}`); keys.add(item.key);
    for (const record of item.installer ? [item, item.installer] : [item]) {
      const kind = record.dataType, v = record.value;
      if ((kind === 'null' && v !== null) || (kind === 'boolean' && typeof v !== 'boolean') || (kind === 'number' && typeof v !== 'number') || ((kind === 'string' || kind === 'base64' || kind === 'int64') && typeof v !== 'string') || (kind === 'array' && !Array.isArray(v)) || (kind === 'object' && (!v || typeof v !== 'object' || Array.isArray(v)))) throw new Error(`数据类型不匹配：${record.key}`);
      if (kind === 'int64' && (typeof v !== 'string' || !/^-?(0|[1-9][0-9]*)$/.test(v) || BigInt(v) < -(1n << 63n) || BigInt(v) >= (1n << 63n))) throw new Error(`非法 int64：${record.key}`);
    }
  }
  return { ...fp, data: { ...fp.data, items: mergePackageInstallers(fp.data.items) } };
}

export async function readFingerprint(file: File): Promise<Imported> {
  const gzip = file.name.toLowerCase().endsWith('.json.gz');
  if (!gzip && !file.name.toLowerCase().endsWith('.json')) throw new Error('请选择 .json 或 .json.gz 文件');
  if (file.size > (gzip ? MAX_GZIP : MAX_JSON)) throw new Error(gzip ? '压缩输入超过 16 MiB' : 'JSON 超过 64 MiB');
  const stream = gzip ? file.stream().pipeThrough(new DecompressionStream('gzip')) : file.stream();
  const reader = stream.getReader(); const chunks: Uint8Array[] = []; let total = 0;
  try {
    for (;;) { const { done, value } = await reader.read(); if (done) break; total += value.length; if (total > MAX_JSON) throw new Error('解压后文件超过 64 MiB'); chunks.push(value); }
  } catch (error) { await reader.cancel().catch(() => {}); throw error; } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(total); let offset = 0; for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  if (bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) throw new Error('文件必须是 UTF-8 无 BOM');
  const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  const fingerprint = parseFingerprint(text);
  const sha256 = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), x => x.toString(16).padStart(2, '0')).join('');
  return { name: file.name, size: total, sha256, fingerprint };
}
