import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { parseArgs } from 'node:util';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { packageInstallerPairs, mergePackageInstallers } from '../../ant-frontend/src/core/package-items.ts';

const project = fileURLToPath(new URL('../../', import.meta.url));
const require = createRequire(new URL('../../ant-frontend/package.json', import.meta.url));
const { parseTree, findNodeAtLocation, visit } = require('jsonc-parser');
const Ajv = require('ajv');
const schema = JSON.parse(await fs.readFile(path.join(project, 'shared/fingerprint.schema.json'), 'utf8'));
const validate = new Ajv({ strict: false }).compile(schema);
const { values, positionals } = parseArgs({ allowPositionals: true, options: { write: { type: 'boolean', default: false }, 'backup-directory': { type: 'string' }, verification: { type: 'string' } } });
if (!positionals.length || positionals.length > 100 || values.write && (!values['backup-directory'] || !values.verification)) throw new Error('用法：node merge-package-installers.mjs <JSON...> [--write --backup-directory <新目录> --verification <新JSON>]；默认只检查');
const paths = positionals.map(p => path.resolve(p));
if (new Set(paths.map(p => process.platform === 'win32' ? p.toLowerCase() : p)).size !== paths.length || new Set(paths.map(p => path.basename(p).toLowerCase())).size !== paths.length) throw new Error('不允许重复输入或同名输入文件');
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
function parse(text) {
  let fields = 0; const stack = [];
  const enter = keys => { if (stack.length >= 64) throw new Error('JSON嵌套超限'); stack.push(keys); };
  visit(text, {
    onObjectBegin: () => enter(new Set()), onObjectEnd: () => stack.pop(), onArrayBegin: () => enter(null), onArrayEnd: () => stack.pop(),
    onObjectProperty: key => { if (++fields > 2_000_000 || stack.at(-1)?.has(key)) throw new Error('重复JSON对象键或字段超限'); stack.at(-1)?.add(key); },
    onLiteralValue: (v, offset, length) => { if (typeof v === 'number' && (!Number.isFinite(v) || /^-?\d+$/.test(text.slice(offset, offset + length)) && !Number.isSafeInteger(v))) throw new Error('不安全数字'); },
    onError: () => { throw new Error('非法JSON'); },
  }, { disallowComments: true, allowTrailingComma: false, allowEmptyContent: false });
  const result = JSON.parse(text);
  if (!validate(result)) throw new Error(`Schema校验失败：${JSON.stringify(validate.errors)}`);
  return result;
}

// 原始采集值直接使用原文本切片，避免JSON重序列化改变浮点token、负零或字符串转义。
const plans = [];
for (const filename of paths) {
  if (!filename.toLowerCase().endsWith('.json') || (await fs.stat(filename)).size > 64 * 1024 * 1024) throw new Error('仅接受64MiB以内的JSON');
  const bytes = await fs.readFile(filename);
  if (bytes.subarray(0, 3).equals(Buffer.from([239, 187, 191]))) throw new Error('输入不能含BOM');
  const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes), fp = parse(text);
  const pairs = packageInstallerPairs(fp.data.items), removed = new Set(pairs.values());
  const tree = parseTree(text), array = findNodeAtLocation(tree, ['data', 'items']);
  const originalItem = index => { const n = array.children[index]; return text.slice(n.offset, n.offset + n.length); };
  const outputItems = array.children.flatMap((_, index) => {
    if (removed.has(index)) return [];
    const raw = originalItem(index);
    if (!pairs.has(index)) return [raw];
    return [raw.slice(0, -1) + ',"installer":' + originalItem(pairs.get(index)) + '}'];
  });
  const mergedText = pairs.size ? text.slice(0, array.offset) + '[' + outputItems.join(',') + ']' + text.slice(array.offset + array.length) : text;
  const merged = parse(mergedText), expected = { ...fp, data: { ...fp.data, items: mergePackageInstallers(fp.data.items) } };
  assert.deepEqual(merged, expected);
  assert.equal(packageInstallerPairs(merged.data.items).size, 0);
  plans.push({ filename, bytes, output: Buffer.from(mergedText, 'utf8'), mergedPairs: pairs.size, beforeItems: fp.data.items.length, afterItems: merged.data.items.length });
}

if (values.write) {
  const backup = path.resolve(values['backup-directory']), verification = path.resolve(values.verification);
  await fs.access(verification).then(() => { throw new Error('核验文件已存在'); }, error => { if (error.code !== 'ENOENT') throw error; });
  await fs.mkdir(backup, { recursive: false });
  const temporary = path.join(project, '.codex/tmp'); await fs.mkdir(temporary, { recursive: true });
  for (const plan of plans) await fs.writeFile(path.join(backup, path.basename(plan.filename)), plan.bytes, { flag: 'wx' });
  for (const plan of plans) {
    if (hash(await fs.readFile(plan.filename)) !== hash(plan.bytes)) throw new Error('源文件并发变更，停止写入');
    const tmp = path.join(temporary, `package-merge-${crypto.randomUUID()}.json`);
    await fs.writeFile(tmp, plan.output, { flag: 'wx' }); await fs.rename(tmp, plan.filename);
    assert.equal(hash(await fs.readFile(plan.filename)), hash(plan.output));
  }
  await fs.writeFile(verification, JSON.stringify({ data: { backup, files: plans.map(({ filename, bytes, output, ...stats }) => ({ filename, ...stats, originalSha256: hash(bytes), mergedSha256: hash(output) })) }, message: '已合并；原采集记录与数字token保留，原件已备份', code: 0 }, null, 2), { encoding: 'utf8', flag: 'wx' });
}
console.log(JSON.stringify({ data: plans.map(({ filename, mergedPairs, beforeItems, afterItems }) => ({ filename, mergedPairs, beforeItems, afterItems })), message: values.write ? '文件已更新，原件已备份' : '仅检查，未写入', code: 0 }, null, 2));
